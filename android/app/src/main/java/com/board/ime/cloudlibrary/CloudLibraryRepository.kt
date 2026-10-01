package com.board.ime.cloudlibrary

import com.board.ime.clipboard.ClipboardHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 统一的 Library 错误状态（UI 只认这几种） */
enum class LibraryError(val message: String) {
    NONE(""),
    NETWORK("网络不可用"),
    AUTH("认证失败"),
    SERVER("服务器错误"),
    PROTOCOL("远端数据异常"),
    /**
     * 只读到部分设备（有设备索引读取失败）。
     * **此时绝不能据 partial 结果判定过期 / 删除。**
     */
    PARTIAL("部分设备暂时不可读"),
    UNKNOWN("未知错误"),
}

/** 内存态 Library 快照（**不持久化**） */
data class LibrarySnapshot(
    val items: List<CloudLibraryItem> = emptyList(),
    val partial: Boolean = false,
    val error: LibraryError = LibraryError.NONE,
    val refreshedAt: Long = 0L,
    /** `deviceId -> deviceType`（**只读映射**，用于把 item 还原成「云端 · Windows」） */
    val deviceTypes: Map<String, String> = emptyMap(),
)

/** 下载结果（UI 负责把 [message] 变成 transient 提示） */
sealed class DownloadOutcome {
    /** 图片：✓ 已下载到相册 */
    object ImageSaved : DownloadOutcome()
    /** 文件：✓ 已下载 */
    object FileSaved : DownloadOutcome()
    data class Failed(val error: LibraryError) : DownloadOutcome()
    data class Rejected(val reason: String) : DownloadOutcome()

    val message: String
        get() = when (this) {
            ImageSaved -> "✓ 已下载到相册"
            FileSaved -> "✓ 已下载"
            is Failed -> "× 下载失败"
            is Rejected -> "× 下载失败"
        }
}

/** 上传结果（**只有远端 index 成功才算成功**） */
sealed class UploadResult {
    data class Success(val item: CloudLibraryItem) : UploadResult()
    data class Rejected(val reason: String) : UploadResult()
    data class Failed(val error: LibraryError) : UploadResult()
}

/** prune 结果（只记数量，不记内容） */
data class PruneResult(
    val candidates: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
    val skipped: Boolean = false,
)

/**
 * 下载落盘（Android 侧由 MediaStore / Downloads 实现；测试用 fake）。
 *
 * 传入的是**原始 bytes**；实现方不得改写内容。
 */
interface LibraryFileSink {
    /** 写 `Pictures/Board/<name>`（相册可见）。返回落盘后的展示路径，失败返回 null。 */
    suspend fun saveImage(bytes: ByteArray, name: String): String?

    /** 写 `Downloads/Board/<name>`。返回落盘后的展示路径，失败返回 null。 */
    suspend fun saveFile(bytes: ByteArray, name: String): String?
}

/**
 * SAF 单文件读取（Android 侧用 ContentResolver；测试用 fake）。
 *
 * 实现方必须**有界读取**：超过 [limit] 立即返回 null，不要把整个大文件复制到额外临时文件。
 */
interface LibraryUploadSource {
    suspend fun read(uri: String, limit: Long): ByteArray?
}

/**
 * Cloud Library 业务编排层。
 *
 * 职责：非阻塞 refresh / 下载 / SAF 上传 / 24h prune / 统一错误状态 / 内存态列表。
 *
 * **线程边界**：本类所有方法都是 `suspend` 且**内部不切换线程**——
 * 网络与文件 I/O 的执行线程由调用方决定（Android 侧请在 `Dispatchers.IO` 上调用）。
 * 本类**不做任何 UI 操作**（无 Toast / 无 Activity / 无 View）。
 *
 * **隐私**：不写 plaintext 缓存、不写缩略图到磁盘、不写系统剪贴板；
 * 只有用户主动下载才落盘。
 */
class CloudLibraryRepository(
    private val remote: CloudLibraryRemote,
    private val sink: LibraryFileSink,
    private val uploadSource: LibraryUploadSource,
    /** 本机 deviceId（用于判定「本地 / 云端」来源） */
    val deviceId: String,
    private val deviceType: String = "Android",
    /** 只接收 status/size/type/结果（不含正文） */
    private val log: (String) -> Unit = {},
) {

    private val _state = MutableStateFlow(LibrarySnapshot())

    /** 内存态列表（进程内有效，不持久化） */
    val state: StateFlow<LibrarySnapshot> = _state.asStateFlow()

    // ---------------------------------------------------------------- Refresh

    /**
     * 拉取所有设备索引并合并。
     *
     * - 成功 ⇒ 新列表
     * - partial ⇒ **保留可读设备的资料**并标记 [LibraryError.PARTIAL]
     * - 完全失败 ⇒ **保留旧列表**，只置失败状态（**绝不清空伪装成“暂无资料”**）
     *
     * 刷新**不修改** `uploadedAt`，也**不下载任何图片**。
     */
    suspend fun refresh(now: Long = System.currentTimeMillis()): LibrarySnapshot {
        val batch = remote.readIndexes()
        val previous = _state.value

        if (batch.partial && batch.jsons.isEmpty()) {
            // 完全失败：保留旧列表
            val failed = previous.copy(error = LibraryError.NETWORK, refreshedAt = now)
            _state.value = failed
            log("library refresh result=fail keep_previous=${previous.items.size}")
            return failed
        }

        val merged = CloudLibraryCodec.mergeIndexes(batch.jsons)
        val snapshot = LibrarySnapshot(
            items = merged,
            partial = batch.partial,
            error = if (batch.partial) LibraryError.PARTIAL else LibraryError.NONE,
            refreshedAt = now,
            // 只读映射：把 item.deviceId 还原出来源设备类型（不改远端 schema）
            deviceTypes = CloudLibraryCodec.deviceTypes(batch.jsons),
        )
        _state.value = snapshot
        log("library refresh count=${merged.size} partial=${batch.partial}")
        return snapshot
    }

    // ---------------------------------------------------------------- Download

    /**
     * 下载一项到本地（图片 → 相册，文件 → Downloads）。
     *
     * 后台执行由调用方保证；**不写系统剪贴板、不修改远端 index、不刷新 `uploadedAt`**。
     */
    suspend fun download(item: CloudLibraryItem): DownloadOutcome {
        if (item.hash.isEmpty()) return DownloadOutcome.Rejected("缺少 hash")
        val bytes = when (val read = remote.downloadBinary(item.hash, extensionOf(item.name))) {
            is LibraryRead.Ok -> read.value
            is LibraryRead.Missing -> return DownloadOutcome.Failed(LibraryError.PROTOCOL)
            is LibraryRead.Failure -> return DownloadOutcome.Failed(mapError(read.error))
        }
        val name = CloudLibrary.sanitizeFileName(item.name)
        return if (CloudLibrary.isImage(item.type)) {
            if (sink.saveImage(bytes, name) != null) {
                log("library download type=image size=${bytes.size} result=ok")
                DownloadOutcome.ImageSaved
            } else {
                log("library download type=image size=${bytes.size} result=fail")
                DownloadOutcome.Failed(LibraryError.UNKNOWN)
            }
        } else {
            if (sink.saveFile(bytes, name) != null) {
                log("library download type=file size=${bytes.size} result=ok")
                DownloadOutcome.FileSaved
            } else {
                log("library download type=file size=${bytes.size} result=fail")
                DownloadOutcome.Failed(LibraryError.UNKNOWN)
            }
        }
    }

    // ---------------------------------------------------------------- Upload

    /**
     * SAF 单文件主动上传。
     *
     * - 单文件、文件夹不接受（由 [LibraryUploadSource] 保证按单文件读取）
     * - >10 MiB 直接拒绝（**有界读取**，不额外复制临时文件）
     * - `hash = SHA-256(原始 bytes)`；保留原始扩展名
     * - index 失败 ⇒ **绝不 DELETE binary**（Step 2.1 safety-first）
     */
    suspend fun upload(
        uri: String,
        name: String,
        mime: String?,
        now: Long = System.currentTimeMillis(),
    ): UploadResult {
        val bytes = uploadSource.read(uri, CloudLibrary.MAX_LIBRARY_BYTES)
            ?: return UploadResult.Rejected("不是可读取的单文件，或超过 10 MiB")
        if (bytes.isEmpty()) return UploadResult.Rejected("空文件")
        if (!CloudLibrary.withinLimit(bytes.size.toLong())) return UploadResult.Rejected("超过 10 MiB 上限")

        val hash = ClipboardHasher.hashBytes(bytes)
        val type = CloudLibrary.typeFor(mime, name)
        val safeName = CloudLibrary.sanitizeFileName(name)

        return when (val outcome = remote.upload(hash, extensionOf(safeName), safeName, type, bytes, now)) {
            is UploadOutcome.Success -> {
                val item = CloudLibraryItem(
                    hash = hash,
                    type = type,
                    name = safeName,
                    size = bytes.size.toLong(),
                    uploadedAt = now,
                    deviceId = CloudLibrary.sanitizeToken(deviceId),
                )
                // 列表刷新（保持内存态与远端一致；失败也不谎报上传成功）
                refresh(now)
                log("library upload size=${bytes.size} type=$type result=ok")
                UploadResult.Success(item)
            }

            is UploadOutcome.Rejected -> UploadResult.Rejected(outcome.reason)
            is UploadOutcome.BinaryFailed -> UploadResult.Failed(mapError(outcome.error))
            is UploadOutcome.IndexFailed -> UploadResult.Failed(mapError(outcome.error))
        }
    }

    // ---------------------------------------------------------------- Prune

    /**
     * 24h prune。严格按 Windows 1-H 已验证算法：
     *
     * 1. **再**读取所有 index（partial ⇒ 直接放弃，不做任何删除）
     * 2. 用 `max(uploadedAt)` 的合并结果判定候选（只有**所有设备**都过期才是候选）
     * 3. `DELETE binary`；`200/204/404` 才算成功
     * 4. 只有在 binary 删除成功后才从**自己的** index 里移除该条目（失败 ⇒ 保留）
     *
     * **不扫描整个 `/library/` 找 orphan**（无 PROPFIND、不新增 orphan registry）。
     */
    suspend fun prune(now: Long = System.currentTimeMillis()): PruneResult {
        val batch = remote.readIndexes()
        if (batch.partial) {
            log("library prune result=skip partial=true")
            return PruneResult(skipped = true)
        }
        val merged = CloudLibraryCodec.mergeIndexes(batch.jsons)
        val candidates = CloudLibraryCodec.deletableItems(now, merged)
        log("library prune candidates=${candidates.size}")

        var deleted = 0
        var failed = 0
        val removedHashes = HashSet<String>()
        for (item in candidates) {
            when (remote.deleteBinary(item.hash, extensionOf(item.name))) {
                is LibraryRead.Ok -> {
                    deleted++
                    removedHashes.add(item.hash)
                }
                is LibraryRead.Failure, is LibraryRead.Missing -> failed++
            }
        }

        if (removedHashes.isNotEmpty()) {
            rewriteOwnIndexWithout(removedHashes, now)
        }
        log("library prune deleted=$deleted failed=$failed")
        return PruneResult(candidates = candidates.size, deleted = deleted, failed = failed)
    }

    /** 只有 binary 确认删除后才重写自己的 index；重写失败不影响已删除的事实（下次 prune 收敛） */
    private suspend fun rewriteOwnIndexWithout(hashes: Set<String>, now: Long) {
        val raw = when (val read = remote.readIndexRaw(deviceId)) {
            is LibraryRead.Ok -> read.value
            is LibraryRead.Missing -> null
            is LibraryRead.Failure -> {
                log("library prune index-rewrite result=fail")
                return
            }
        }
        val existing = raw?.let { CloudLibraryCodec.parseIndex(it) } ?: return
        val kept = existing.items.filter { !hashes.contains(it.hash) }
        if (kept.size == existing.items.size) return
        remote.putOwnIndex(
            CloudLibraryIndex(
                schema = existing.schema,
                deviceId = existing.deviceId,
                deviceType = existing.deviceType,
                updatedAt = now,
                items = kept,
            ),
        )
    }

    /**
     * 缩略图 / 预览用：只取**原始 bytes**，不落盘、不改任何远端状态、不触发刷新。
     */
    suspend fun fetchBytes(item: CloudLibraryItem): ByteArray? {
        if (item.hash.isEmpty()) return null
        return when (val read = remote.downloadBinary(item.hash, extensionOf(item.name))) {
            is LibraryRead.Ok -> read.value
            else -> null
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun extensionOf(name: String): String =
        CloudLibrary.normalizeExtension(name.substringAfterLast('.', ""))

    private fun mapError(error: com.board.ime.sync.webdav.WebDavError): LibraryError =
        when (error) {
            com.board.ime.sync.webdav.WebDavError.NONE -> LibraryError.NONE
            com.board.ime.sync.webdav.WebDavError.AUTH,
            com.board.ime.sync.webdav.WebDavError.NOT_CONFIGURED,
            -> LibraryError.AUTH

            com.board.ime.sync.webdav.WebDavError.NETWORK,
            com.board.ime.sync.webdav.WebDavError.TIMEOUT,
            -> LibraryError.NETWORK

            com.board.ime.sync.webdav.WebDavError.SERVER,
            com.board.ime.sync.webdav.WebDavError.CONFLICT,
            -> LibraryError.SERVER

            com.board.ime.sync.webdav.WebDavError.PROTOCOL -> LibraryError.PROTOCOL
            else -> LibraryError.UNKNOWN
        }
}
