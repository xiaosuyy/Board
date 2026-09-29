package com.board.ime.clipboard

import android.util.Log
import com.board.ime.database.dao.ClipboardDao
import com.board.ime.database.dao.SyncOperationDao
import com.board.ime.database.entity.ClipboardItem
import com.board.ime.database.entity.SyncOperation
import com.board.ime.sync.SyncEntityType
import com.board.ime.sync.SyncEvent
import com.board.ime.sync.SyncLog
import com.board.ime.sync.SyncOperationAction
import com.board.ime.sync.SyncOperationStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次抓取的结果，调用方（输入法服务）用来决定要不要提示用户 */
enum class ClipboardCaptureResult {
    /** 新内容，已入库 */
    INSERTED,

    /** 已有同内容：复活（如果被删过）并把「最近使用时间」刷新 */
    UPDATED,

    /** 疑似验证码 / 密码，按策略不入库 */
    SKIPPED_SENSITIVE,

    /** 空内容 */
    SKIPPED_EMPTY,

    /**
     * 内容超过「单行可读」上限。
     *
     * 这不是人为的长度策略：SQLite 的 `CursorWindow` 只有约 2MB，
     * 单行内容超过它之后 `SELECT` 会直接抛
     * `Row too big to fit into CursorWindow` —— 写进去也读不回来，
     * 而且会让整个列表查询一起失败。所以这里**明确拒绝**，
     * 既不截断也不留下一条读不出来的记录。
     */
    SKIPPED_TOO_LARGE,
}

/** 剪贴板统计，用于「清空全部历史」的二次确认文案 */
data class ClipboardCounts(
    val plain: Int,
    val pinned: Int,
    val favorite: Int,
) {
    val total: Int get() = plain + pinned + favorite
}

/**
 * 内容从哪来。
 *
 * - [LOCAL]：用户自己复制的 → 产生同步任务（会被上传）
 * - [REMOTE]：WebDAV 拉下来的 → **不产生**同步任务（否则 A→B→A 无限回环）
 */
enum class ClipboardCaptureSource {
    LOCAL,
    REMOTE,
}

/**
 * 剪贴板仓库（本地存储 + 同步任务）。
 *
 * 去重规则：按内容哈希判断。同一段文本反复复制**不会**产生新记录，
 * 只会刷新它的「最近使用时间」；如果它之前被删掉了，就复活它。
 * 这样历史里同一内容永远只有一条，同时最近用过的自然排在最前。
 *
 * 敏感内容策略见 [ClipboardSensitivityDetector]：判定为敏感的内容按
 * [ClipboardSyncPolicy.DO_NOT_SAVE] 直接不入库。
 *
 * 同步相关的两个职责：
 * 1. 本机复制 → 写 `sync_operation` 队列（只保留一条待推送，由 SyncEngine 消费）
 * 2. 远端内容落库时带 [ClipboardCaptureSource.REMOTE] + 抑制窗口，
 *    防止「远端写入 → 系统回调 → 又上传」的回环
 */
class ClipboardRepository(
    private val dao: ClipboardDao,
    private val syncOperationDao: SyncOperationDao? = null,
    private val sensitivityDetector: ClipboardSensitivityDetector = HeuristicClipboardSensitivityDetector(),
) {

    /** 串行化抓取：系统的剪贴板回调可能连续触发多次 */
    private val captureLock = Mutex()

    /** 刚由同步写进来的内容：这段时间内系统回调不再产生上传任务 */
    private val suppressed = HashMap<String, Long>()

    /**
     * 最近一次由远端写入本地的哈希。
     *
     * 比时间窗口更可靠的回环防护：远端内容落到系统剪贴板后，用户过一会儿再打开
     * 剪贴板面板会再次触发 `ClipboardManager` 回调；只要内容还是这条，
     * 就还是「同步来的」，不能当成用户新复制的内容去上传。
     */
    @Volatile
    private var lastRemoteHash: String? = null

    // ---------------------------------------------------------------- 读取

    fun observe(
        limit: Int = DEFAULT_LIMIT,
        remoteOnly: Boolean = false,
    ): Flow<List<ClipboardItem>> =
        if (remoteOnly) dao.observeByOrigin(ClipboardOrigin.REMOTE, limit) else dao.observeActive(limit)

    /** 空查询等同于「最近」；非空时走 SQLite LIKE，本地即时过滤 */
    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        remoteOnly: Boolean = false,
    ): Flow<List<ClipboardItem>> {
        val keyword = query.trim()
        return when {
            keyword.isEmpty() && remoteOnly -> dao.observeByOrigin(ClipboardOrigin.REMOTE, limit)
            keyword.isEmpty() -> dao.observeActive(limit)
            remoteOnly -> dao.observeSearchByOrigin(keyword, ClipboardOrigin.REMOTE, limit)
            else -> dao.observeSearch(keyword, limit)
        }
    }

    suspend fun counts(): ClipboardCounts = ClipboardCounts(
        plain = dao.countPlain(),
        pinned = dao.countPinned(),
        favorite = dao.countFavorite(),
    )

    suspend fun find(id: Long): ClipboardItem? = dao.findById(id)

    suspend fun findByHash(hash: String): ClipboardItem? = dao.findByHash(hash)

    suspend fun latestActive(): ClipboardItem? = dao.latestActive()

    // ---------------------------------------------------------------- 抓取

    /** 记录一段系统剪贴板文本（默认是本机用户复制） */
    suspend fun capture(
        content: String,
        sourceDeviceId: String? = null,
        source: ClipboardCaptureSource = ClipboardCaptureSource.LOCAL,
    ): ClipboardCaptureResult {
        val text = content.trim()
        if (text.isEmpty()) return ClipboardCaptureResult.SKIPPED_EMPTY

        // 单行能不能读回来，是 SQLite CursorWindow（约 2MB）决定的，不是我们设的限制。
        // 超过就明确拒绝：既不截断用户内容，也不留下一条「写进去但读不出来」的记录
        // （那种记录会让整个剪贴板列表查询一起抛 Row too big to fit into CursorWindow）。
        val bytes = ClipboardHasher.utf8Length(text)
        if (bytes > MAX_SINGLE_ROW_BYTES) {
            Log.w(
                TAG,
                "剪贴板内容 ${bytes}B 超过单行可读上限 ${MAX_SINGLE_ROW_BYTES}B（CursorWindow ~2MB），不入库",
            )
            return ClipboardCaptureResult.SKIPPED_TOO_LARGE
        }

        // 敏感判定 / 类型判定只看开头一段：正文可能有几 MB，
        // 没有任何理由为「是不是验证码」「是不是链接」去扫描整段内容。
        // 这**不是**长度上限，超长正文依旧会完整入库。
        val sample = if (text.length > DETECT_SAMPLE_LENGTH) {
            text.substring(0, DETECT_SAMPLE_LENGTH)
        } else {
            text
        }
        val contentType = detectContentType(sample)
        val sensitivity = sensitivityDetector.detect(sample, contentType)
        val policy = sensitivityDetector.policyFor(sensitivity)
        if (policy == ClipboardSyncPolicy.DO_NOT_SAVE) {
            Log.i(TAG, "剪贴板内容判定为敏感（$sensitivity），按策略不入库")
            return ClipboardCaptureResult.SKIPPED_SENSITIVE
        }

        val hash = ClipboardHasher.hash(text)
        // 同步写进来的内容：这里不能再产生上传任务
        // （时间窗口 + 「最近一条远端哈希」双重判定，后者不随时间失效）
        val fromSync = source == ClipboardCaptureSource.REMOTE ||
            hash == lastRemoteHash ||
            consumeSuppression(hash)
        if (fromSync && hash == lastRemoteHash) {
            SyncLog.event(SyncEvent.SUPPRESS_LOOP, "hash=${SyncLog.shortHash(hash)} 是最近一条远端内容，不上传")
        }

        // 系统可能在很短时间内连续回调多次；串行化，避免两次「查无此 hash」同时插入
        return captureLock.withLock {
            val now = System.currentTimeMillis()
            val existing = dao.findByHash(hash)
            if (existing != null) {
                dao.touchAndRestore(id = existing.id, updatedAt = now)
                Log.i(TAG, "剪贴板内容已存在（hash=${SyncLog.shortHash(hash)}），刷新时间")
                if (!fromSync) enqueueClipboardPush(existing.id)
                return@withLock ClipboardCaptureResult.UPDATED
            }

            val id = dao.insert(
                ClipboardItem(
                    content = text,
                    contentType = contentType,
                    preview = buildPreview(text),
                    contentHash = hash,
                    sourceDeviceId = sourceDeviceId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            if (!fromSync) {
                enqueueClipboardPush(id)
            } else {
                SyncLog.event(SyncEvent.SUPPRESS_LOOP, "hash=${SyncLog.shortHash(hash)} 来自同步，不上传")
            }
            ClipboardCaptureResult.INSERTED
        }
    }

    /**
     * 把远端内容写进本地历史（**不覆盖、不删除**）。
     *
     * 来源标记为 [ClipboardOrigin.REMOTE]；如果本地已经有同一条内容
     * （说明是本机自己复制过的），保留它原来的 LOCAL 来源 —— 判断依据是
     * 「内容由哪台设备产生」，不是「有没有上传成功」。
     *
     * @return 写入或命中的本地条目
     */
    suspend fun applyRemote(
        content: String,
        contentType: ClipboardContentType = ClipboardContentType.TEXT,
        remoteHash: String? = null,
        originDeviceId: String? = null,
        originType: String? = null,
    ): ClipboardItem? {
        val text = content.trim()
        if (text.isEmpty()) return null
        // 同 capture：单行超过 CursorWindow 上限就拒绝（远端再大的内容也不能把本机列表搞坏）
        if (ClipboardHasher.utf8Length(text) > MAX_SINGLE_ROW_BYTES) {
            Log.w(
                TAG,
                "远端内容 ${ClipboardHasher.utf8Length(text)}B 超过单行可读上限，已跳过（不覆盖本地任何内容）",
            )
            return null
        }
        val hash = remoteHash?.takeIf { it.isNotBlank() } ?: ClipboardHasher.hash(text)

        // 先登记抑制：写入后系统剪贴板回调不该再触发上传
        suppressCapture(hash)
        lastRemoteHash = hash

        captureLock.withLock {
            val now = System.currentTimeMillis()
            val existing = dao.findByHash(hash)
            if (existing != null) {
                dao.touchAndRestore(id = existing.id, updatedAt = now)
                return dao.findById(existing.id)
            }
            val id = dao.insert(
                ClipboardItem(
                    content = text,
                    contentType = contentType,
                    preview = buildPreview(text),
                    contentHash = hash,
                    sourceDeviceId = SOURCE_REMOTE,
                    origin = ClipboardOrigin.REMOTE,
                    originDeviceId = originDeviceId,
                    originType = originType,
                    receivedAt = now,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            return dao.findById(id)
        }
    }

    /** 标记某个哈希在 [SUPPRESS_WINDOW_MS] 内不要产生上传任务 */
    fun suppressCapture(hash: String, windowMs: Long = SUPPRESS_WINDOW_MS) {
        synchronized(suppressed) {
            suppressed[hash] = System.currentTimeMillis() + windowMs
        }
    }

    private fun consumeSuppression(hash: String): Boolean = synchronized(suppressed) {
        val expiry = suppressed[hash] ?: return false
        suppressed.remove(hash)
        if (expiry >= System.currentTimeMillis()) {
            SyncLog.event(SyncEvent.SUPPRESS_LOOP, "hash=${SyncLog.shortHash(hash)} 命中抑制窗口")
            true
        } else {
            false
        }
    }

    /** 明确添加一条本机内容（有自己的哈希、可指定类型） */
    suspend fun addLocal(
        content: String,
        contentType: ClipboardContentType = ClipboardContentType.TEXT,
        sourceDeviceId: String? = null,
    ): Long {
        val text = content.trim()
        require(text.isNotEmpty()) { "剪贴板内容为空" }
        val now = System.currentTimeMillis()
        val id = dao.insert(
            ClipboardItem(
                content = text,
                contentType = contentType,
                preview = buildPreview(text),
                contentHash = ClipboardHasher.hash(text),
                sourceDeviceId = sourceDeviceId,
                createdAt = now,
                updatedAt = now,
            ),
        )
        enqueueClipboardPush(id)
        return id
    }

    // ---------------------------------------------------------------- 同步任务

    /**
     * 生成本机剪贴板推送任务。
     *
     * SyncClipboard 的模型是「服务器上只有一份当前剪贴板」，所以队列里
     * **只保留最新的一条待推送任务**，避免历史越积越多。
     */
    suspend fun enqueueClipboardPush(itemId: Long) {
        val queue = syncOperationDao ?: return
        queue.deleteByTypeAndStatus(SyncEntityType.CLIPBOARD, SyncOperationStatus.PENDING)
        queue.enqueue(
            SyncOperation(
                entityType = SyncEntityType.CLIPBOARD,
                entityId = itemId.toString(),
                operation = SyncOperationAction.UPDATE,
            ),
        )
    }

    // ---------------------------------------------------------------- 操作

    /** 收藏 / 取消收藏 */
    suspend fun setFavorite(id: Long, favorite: Boolean) {
        dao.updateFavorite(id = id, favorite = favorite, updatedAt = System.currentTimeMillis())
    }

    /** 固定 / 取消固定 */
    suspend fun setPinned(id: Long, pinned: Boolean) {
        dao.updatePinned(id = id, pinned = pinned, updatedAt = System.currentTimeMillis())
    }

    /** 被插入输入框：算一次「使用」，顶到最近 */
    suspend fun markUsed(id: Long) {
        dao.touch(id = id, updatedAt = System.currentTimeMillis())
    }

    suspend fun remove(id: Long) {
        val now = System.currentTimeMillis()
        dao.markDeleted(id = id, deletedAt = now, updatedAt = now)
    }

    /**
     * 清空历史（软删除）。
     *
     * @param includeProtected true 表示连固定 / 收藏一起删；默认 false，即固定 / 收藏保留
     * @return 实际删除的条数
     */
    suspend fun clearHistory(includeProtected: Boolean = false): Int {
        val now = System.currentTimeMillis()
        return if (includeProtected) {
            dao.markAllDeleted(deletedAt = now, updatedAt = now)
        } else {
            dao.markAllPlainDeleted(deletedAt = now, updatedAt = now)
        }
    }

    /** 真正从库里抹掉软删除的条目（不做跨设备同步，所以可以直接物理删） */
    suspend fun purgeDeleted(): Int = dao.purgeDeleted()

    /**
     * 把历史导出成 SyncClipboard 兼容的 JSON（`SyncClipboard.json` 的字段形状）。
     *
     * **本轮不发网络请求**：只提供映射，等以后接 SyncClipboard / WebDAV 时直接复用。
     */
    suspend fun exportSyncClipboardJson(limit: Int = DEFAULT_LIMIT): String =
        SyncClipboardMapper.historyToJson(dao.active(limit))

    // ---------------------------------------------------------------- 工具

    /**
     * 历史清理：给旧数据补内容哈希，并把「同内容多条」合并成一条。
     *
     * 老版本没有 `contentHash` 列（迁移时统一填 ''），不补的话新内容会和旧记录重复；
     * 另外迁移前的版本可能已经存了重复内容，这里一次性收敛。
     * 合并时会把固定 / 收藏标记带到保留的那条上，不会丢状态。
     *
     * @return 处理过的条数
     */
    suspend fun dedupeExisting(): Int {
        var touched = 0
        dao.itemsWithEmptyHash().forEach { item ->
            dao.updateHash(item.id, ClipboardHasher.hash(item.content))
            touched++
        }

        val now = System.currentTimeMillis()
        dao.duplicateContents().forEach { content ->
            val group = dao.activeByContent(content)
            if (group.size < 2) return@forEach
            val keep = group.maxWithOrNull(
                compareBy(
                    { if (it.isProtected) 1 else 0 },
                    { it.updatedAt },
                ),
            ) ?: return@forEach
            val pinned = group.any { it.isPinned }
            val favorite = group.any { it.isFavorite }
            if (pinned != keep.isPinned || favorite != keep.isFavorite) {
                dao.updateFlags(id = keep.id, pinned = pinned, favorite = favorite, updatedAt = now)
            }
            group.filter { it.id != keep.id }.forEach { duplicate ->
                dao.markDeleted(id = duplicate.id, deletedAt = now, updatedAt = now)
                touched++
            }
        }
        return touched
    }

    fun detectContentType(text: String): ClipboardContentType =
        if (LINK_PATTERN.matches(text)) ClipboardContentType.LINK else ClipboardContentType.TEXT

    /**
     * 列表摘要：压掉换行与连续空白，再截断。
     *
     * **只处理开头一小段**：正文可能有几 MB，摘要只是显示用，
     * 没必要为了 160 字的摘要去正则替换整段内容（那会白白再分配一份大字符串）。
     * 完整内容始终在 [ClipboardItem.content] 里，全文查看用的是它。
     */
    fun buildPreview(text: String): String {
        val head = if (text.length > PREVIEW_HEAD_LENGTH) {
            text.substring(0, PREVIEW_HEAD_LENGTH)
        } else {
            text
        }
        val compact = head.replace(WHITESPACE, " ").trim()
        return if (compact.length <= PREVIEW_MAX_LENGTH) {
            compact
        } else {
            compact.take(PREVIEW_MAX_LENGTH) + "…"
        }
    }

    companion object {
        private const val TAG = "BoardClipboard"
        const val DEFAULT_LIMIT = 200
        /** 摘要只取开头这么多字符（显示 160 字足够，避免处理整段超长文本） */
        const val PREVIEW_MAX_LENGTH = 160
        private const val PREVIEW_HEAD_LENGTH = PREVIEW_MAX_LENGTH * 4
        /** 敏感 / 类型判定只看开头这么多字符 */
        private const val DETECT_SAMPLE_LENGTH = 4_096
        /** 同步写入后，多久之内不再把系统回调当作本机复制 */
        const val SUPPRESS_WINDOW_MS = 15_000L
        const val SOURCE_REMOTE = "remote"

        /**
         * 单条剪贴板内容的上限（UTF-8 字节）。
         *
         * 依据是 Android SQLite 的 `CursorWindow`（约 2MB）：单行超过它，
         * `SELECT` 会抛 `Row too big to fit into CursorWindow`。
         * 取 1.5MB 留出余量，保证写进去的内容一定能完整读回来。
         *
         * 顺带说明真实的端到端上限：**系统剪贴板自身在约 1MB 处就会抛
         * `TransactionTooLargeException`**（Binder 事务缓冲是进程级共享的），
         * 所以日常使用根本到不了这个值。
         */
        const val MAX_SINGLE_ROW_BYTES = 1_500_000L
        private val WHITESPACE = Regex("\\s+")
        private val LINK_PATTERN = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    }
}
