package com.board.ime.clipboard

import android.content.Context
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
import java.io.File
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
 * ## 存储策略（本轮重点）
 *
 * 内容有两种存放方式，见 [ClipboardContentStorage]：
 * - 小文本 → 直接存 SQLite 的 `content` 列
 * - 大文本 → 存 Board 私有目录的文件，库里只留路径 + 大小 + hash
 *
 * 判断依据是 **UTF-8 字节数**（`inlineMaxBytes`）。为什么必须分流：
 * Android 的 SQLite 用 `CursorWindow`（约 2MB）取数据，**单行超过它就读不回来**
 * （`Row too big to fit into CursorWindow`），而且会让整个列表 / 搜索查询一起失败。
 *
 * ## 读路径
 *
 * 列表 / 搜索 / 去重全部走 [ClipboardSummary]（不含 content）；
 * 只有「全文」和「插到输入框」才调用 [loadContent]。
 * 因此**任何大小的文本都不可能把列表查询搞崩**。
 *
 * ## 同步
 *
 * 小文本走 SyncClipboard 的 `text` 字段；大文本走 `/file/{dataName}`
 * （映射见 [SyncClipboardMapper.INLINE_TEXT_MAX_BYTES]），不生成超大 JSON。
 */
class ClipboardRepository(
    private val dao: ClipboardDao,
    private val syncOperationDao: SyncOperationDao? = null,
    private val sensitivityDetector: ClipboardSensitivityDetector = HeuristicClipboardSensitivityDetector(),
    /** 大文本文件仓库；为 null 时全部内联（老调用点兼容） */
    private val contentStore: ClipboardContentStore? = null,
) {

    /** 串行化抓取：系统的剪贴板回调可能连续触发多次 */
    private val captureLock = Mutex()

    /** 刚由同步写进来的内容：这段时间内系统回调不再产生上传任务 */
    private val suppressed = HashMap<String, Long>()

    /**
     * 阈值：UTF-8 字节数超过它就走文件。
     *
     * 取值经过实测（见 `releases/Board-Android-v0.1.2-clipboard-notes.md`）：
     * 256KB 会让文件数量明显变多而收益很小；512KB 时列表查询依然毫秒级，
     * 且远低于 CursorWindow 的 2MB 上限，留足余量。
     */
    @Volatile
    var inlineMaxBytes: Long = DEFAULT_INLINE_MAX_BYTES

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

    /** 列表：只返回不含 content 的投影 */
    fun observe(
        limit: Int = DEFAULT_LIMIT,
        remoteOnly: Boolean = false,
    ): Flow<List<ClipboardSummary>> =
        // 「云端」= 服务器当前内容（remoteCurrent），不是「历史上同步过」也不是「origin=REMOTE」
        if (remoteOnly) dao.observeRemoteCurrent(limit) else dao.observeActive(limit)

    /** 空查询等同于「最近」；非空时走 SQLite LIKE，本地即时过滤（不加载完整内容） */
    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        remoteOnly: Boolean = false,
    ): Flow<List<ClipboardSummary>> {
        val keyword = query.trim()
        return when {
            keyword.isEmpty() && remoteOnly -> dao.observeRemoteCurrent(limit)
            keyword.isEmpty() -> dao.observeActive(limit)
            remoteOnly -> dao.observeSearchRemoteCurrent(keyword, limit)
            else -> dao.observeSearch(keyword, limit)
        }
    }

    suspend fun counts(): ClipboardCounts = ClipboardCounts(
        plain = dao.countPlain(),
        pinned = dao.countPinned(),
        favorite = dao.countFavorite(),
    )

    suspend fun find(id: Long): ClipboardSummary? = dao.findSummaryById(id)

    suspend fun findByHash(hash: String): ClipboardSummary? = dao.findSummaryByHash(hash)

    suspend fun latestActive(): ClipboardSummary? = dao.latestSummary()

    /** 列表投影（供宿主 UI 用） */
    suspend fun list(limit: Int = DEFAULT_LIMIT): List<ClipboardSummary> = dao.active(limit)

    // ---------------------------------------------------------------- 完整内容

    /**
     * 按 id 取完整内容。
     *
     * - INLINE：直接读 `content` 列
     * - FILE：读文件；文件丢了返回 null（界面显示「文件缺失」而不是崩）
     *
     * **这是唯一会触碰完整内容的读路径**。
     */
    suspend fun loadContent(id: Long): String? {
        val item = dao.findById(id) ?: return null
        return loadContent(item)
    }

    suspend fun loadContent(item: ClipboardItem): String? = when (item.contentStorage) {
        ClipboardContentStorage.INLINE -> item.content
        ClipboardContentStorage.FILE -> contentStore?.read(item.contentFilePath)
    }

    suspend fun loadContent(summary: ClipboardSummary): String? = loadContent(summary.id)

    /** 内容是否还能读到（文件型条目用来在界面上标注） */
    suspend fun isContentAvailable(summary: ClipboardSummary): Boolean = when (summary.contentStorage) {
        ClipboardContentStorage.INLINE -> true
        ClipboardContentStorage.FILE -> contentStore?.exists(summary.contentFilePath) == true
    }

    // ---------------------------------------------------------------- 抓取

    /** 记录一段系统剪贴板文本（默认是本机用户复制） */
    suspend fun capture(
        content: String,
        sourceDeviceId: String? = null,
        source: ClipboardCaptureSource = ClipboardCaptureSource.LOCAL,
        /**
         * 是否允许因为这次抓取而产生上传。
         *
         * 打开剪贴板面板时会"顺手读一次系统剪贴板"补抓 —— 那不是用户复制，
         * **绝不能因此产生上传**：否则每次打开面板都会把自己的内容推到云端、
         * 把另一台设备的内容覆盖掉，导致"拉不到对方的内容"（真机+模拟器实测）。
         */
        enqueuePush: Boolean = true,
    ): ClipboardCaptureResult {
        val text = content.trim()
        if (text.isEmpty()) return ClipboardCaptureResult.SKIPPED_EMPTY

        // 敏感判定 / 类型判定只看开头一段：正文可能有几十 MB，
        // 没有任何理由为「是不是验证码」「是不是链接」去扫描整段内容。
        // 这**不是**长度上限，超长正文依旧会被完整保存（走文件）。
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
            val existing = dao.findSummaryByHash(hash)
            if (existing != null) {
                dao.touchAndRestore(id = existing.id, updatedAt = now)
                // 老数据的文件型条目没有搜索索引（content 已清空），顺手补上
                if (existing.contentStorage == ClipboardContentStorage.FILE &&
                    runCatching { dao.searchTextOf(existing.id) }.getOrNull().isNullOrEmpty()
                ) {
                    runCatching { dao.updateSearchText(existing.id, text.take(SEARCH_INDEX_CHARS)) }
                }
                Log.i(TAG, "剪贴板内容已存在（hash=${SyncLog.shortHash(hash)}），刷新时间")
                if (!fromSync && enqueuePush) enqueueClipboardPush(existing.id)
                return@withLock ClipboardCaptureResult.UPDATED
            }

            val stored = store(text, hash)
            val id = dao.insert(
                ClipboardItem(
                    content = stored.inlineContent,
                    contentType = contentType,
                    preview = buildPreview(text),
                    contentHash = hash,
                    sourceDeviceId = sourceDeviceId,
                    createdAt = now,
                    updatedAt = now,
                    contentStorage = stored.storage,
                    contentFilePath = stored.path,
                    contentSize = stored.size,
                    searchText = stored.searchText,
                ),
            )
            if (!fromSync && enqueuePush) {
                enqueueClipboardPush(id)
            } else {
                SyncLog.event(SyncEvent.SUPPRESS_LOOP, "hash=${SyncLog.shortHash(hash)} 来自同步，不上传")
            }
            if (stored.storage == ClipboardContentStorage.FILE) {
                Log.i(TAG, "超长剪贴板已存为文件（hash=${SyncLog.shortHash(hash)} ${stored.size}B）")
            }
            ClipboardCaptureResult.INSERTED
        }
    }

    /**
     * 决定这条内容放哪里：小文本内联，大文本写文件。
     * 写文件失败时**退回内联**（宁可占点数据库空间，也不能丢用户内容）。
     */
    private fun store(text: String, hash: String): StoredContent {
        val bytes = ClipboardHasher.utf8Length(text)
        val searchText = text.take(SEARCH_INDEX_CHARS)
        val store = contentStore
        if (store == null || bytes <= inlineMaxBytes) {
            return StoredContent(ClipboardContentStorage.INLINE, "", text, bytes, searchText)
        }
        val file = store.write(hash, text)
            ?: return StoredContent(ClipboardContentStorage.INLINE, "", text, bytes, searchText)
        return StoredContent(ClipboardContentStorage.FILE, file.absolutePath, "", file.length(), searchText)
    }

    private data class StoredContent(
        val storage: ClipboardContentStorage,
        val path: String,
        val inlineContent: String,
        val size: Long,
        val searchText: String,
    )

    /**
     * 把远端内容写进本地历史（**不覆盖、不删除**）。
     *
     * 来源标记为 [ClipboardOrigin.REMOTE]；如果本地已经有同一条内容
     * （说明是本机自己复制过的），保留它原来的 LOCAL 来源 —— 判断依据是
     * 「内容由哪台设备产生」，不是「有没有上传成功」。
     *
     * @return 写入或命中的本地条目（列表投影）
     */
    suspend fun applyRemote(
        content: String,
        contentType: ClipboardContentType = ClipboardContentType.TEXT,
        remoteHash: String? = null,
        originDeviceId: String? = null,
        originType: String? = null,
    ): ClipboardSummary? {
        val text = content.trim()
        if (text.isEmpty()) return null
        val hash = remoteHash?.takeIf { it.isNotBlank() } ?: ClipboardHasher.hash(text)

        // 先登记抑制：写入后系统剪贴板回调不该再触发上传
        suppressCapture(hash)
        lastRemoteHash = hash

        captureLock.withLock {
            val now = System.currentTimeMillis()
            val existing = dao.findSummaryByHash(hash)
            if (existing != null) {
                dao.touchAndRestore(id = existing.id, updatedAt = now)
                return dao.findSummaryById(existing.id)
            }
            val stored = store(text, hash)
            val id = dao.insert(
                ClipboardItem(
                    content = stored.inlineContent,
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
                    contentStorage = stored.storage,
                    contentFilePath = stored.path,
                    contentSize = stored.size,
                    searchText = stored.searchText,
                ),
            )
            return dao.findSummaryById(id)
        }
    }

    /**
     * 确认「服务器当前内容」就是 [hash] 这条记录。
     *
     * 语义严格：**先把所有旧标记清掉，再给这条打上**，
     * 所以服务器从 A 变成 B 之后，A 会自动从「云端」筛选里消失。
     *
     * 只在**成功拿到并校验通过服务器内容**之后调用；网络失败/超时一律不调用。
     *
     * @return 被打上标记的记录 id；本地没有这条时返回 null
     */
    suspend fun markRemoteCurrent(hash: String): Long? {
        val target = dao.findSummaryByHash(hash) ?: return null
        dao.clearRemoteCurrent()
        dao.setRemoteCurrent(target.id)
        SyncLog.event(SyncEvent.DONE, "云端当前内容 → hash=${SyncLog.shortHash(hash)}（已重置旧标记）")
        return target.id
    }

    /** 本机成功上传后，本地这条就是「服务器当前内容」 */
    suspend fun markRemoteCurrentById(id: Long) {
        dao.clearRemoteCurrent()
        dao.setRemoteCurrent(id)
        SyncLog.event(SyncEvent.UPLOAD, "本机已上传，标记为云端当前内容 id=$id")
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
        val hash = ClipboardHasher.hash(text)
        val stored = store(text, hash)
        val id = dao.insert(
            ClipboardItem(
                content = stored.inlineContent,
                contentType = contentType,
                preview = buildPreview(text),
                contentHash = hash,
                sourceDeviceId = sourceDeviceId,
                createdAt = now,
                updatedAt = now,
                contentStorage = stored.storage,
                contentFilePath = stored.path,
                contentSize = stored.size,
                searchText = stored.searchText,
            ),
        )
        enqueueClipboardPush(id)
        return id
    }

    /**
     * 从一段「已知哈希」的内容导入（例如分享 / 导入文本文件）。
     * 与 [addLocal] 的区别是来源可控，且**不会**重复写文件（按 hash 去重）。
     */
    suspend fun importText(
        content: String,
        contentType: ClipboardContentType = ClipboardContentType.TEXT,
    ): ClipboardCaptureResult = capture(content, source = ClipboardCaptureSource.LOCAL)

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

    /**
     * 软删除。文件型条目的文件**先留着**：
     * 软删除还能复活，等 [purgeDeleted] 时再一起清理。
     */
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

    /**
     * 真正从库里抹掉软删除的条目，并删除它们对应的内容文件
     * （不做跨设备同步，所以可以直接物理删）。
     */
    suspend fun purgeDeleted(): Int {
        val paths = runCatching { dao.deletedFilePaths() }.getOrDefault(emptyList())
        val removed = dao.purgeDeleted()
        val store = contentStore
        if (store != null) {
            paths.forEach { store.delete(it) }
        }
        // 顺手回收没有记录引用的孤儿文件
        if (store != null) {
            val referenced = runCatching { dao.allFilePaths().toSet() }.getOrDefault(emptySet())
            store.collectOrphans(referenced)
        }
        return removed
    }

    /**
     * 把历史遗留的「超大内联行」搬进文件。
     *
     * v8 之前所有内容都在 `content` 列里，包括那些曾经把列表查询搞崩的大文本。
     * 现在列表不再读 content，所以这些行已经**不会再破坏列表**；
     * 这里再把能搬的搬走，让「全文」也能稳定打开。
     *
     * 读不出来的（单行超过 CursorWindow）会记日志跳过 —— 它们本来就打不开，
     * 但至少不会连累其它条目。
     *
     * @return 成功搬到文件的行数
     */
    suspend fun migrateLargeInlineRows(limit: Int = 50): Int {
        val store = contentStore ?: return 0
        val minChars = (inlineMaxBytes / 3).toInt().coerceAtLeast(MIN_MIGRATE_CHARS)
        val rows = runCatching { dao.inlineRowsLargerThan(minChars, limit) }.getOrDefault(emptyList())
        var moved = 0
        rows.forEach { row ->
            val item = runCatching { dao.findById(row.id) }.getOrNull() ?: return@forEach
            val text = runCatching { item.content }.getOrNull()
            if (text.isNullOrEmpty()) return@forEach
            val file = store.write(item.contentHash, text) ?: return@forEach
            dao.updateContent(
                id = item.id,
                content = "",
                storage = ClipboardContentStorage.FILE,
                path = file.absolutePath,
                size = file.length(),
                searchText = text.take(SEARCH_INDEX_CHARS),
                updatedAt = item.updatedAt,
            )
            moved++
        }
        if (moved > 0) Log.i(TAG, "已把 $moved 条超大内联内容搬进文件")
        return moved
    }

    /**
     * 给老的文件型条目补搜索索引（只读文件开头一小段，不加载全文）。
     *
     * @return 补好的行数
     */
    suspend fun rebuildSearchIndex(limit: Int = 50): Int {
        val store = contentStore ?: return 0
        val rows = runCatching { dao.fileRowsWithoutSearchText(limit) }.getOrDefault(emptyList())
        var fixed = 0
        rows.forEach { row ->
            val head = store.readHead(row.contentFilePath, SEARCH_INDEX_CHARS)
            if (!head.isNullOrEmpty()) {
                runCatching { dao.updateSearchText(row.id, head) }
                fixed++
            }
        }
        if (fixed > 0) Log.i(TAG, "已为 $fixed 条文件型剪贴板补上搜索索引")
        return fixed
    }

    /**
     * 把历史记录里空的哈希补上。
     *
     * 老版本没有 `contentHash` 列（迁移时统一填 ''），不补的话新内容会和旧记录重复。
     * 只处理这一小批行，而且读取单行内容是有界的（超大的会被 [migrateLargeInlineRows] 先搬走）。
     *
     * @return 处理过的条数
     */
    suspend fun dedupeExisting(): Int {
        var touched = 0
        runCatching { dao.itemsWithEmptyHash() }.getOrDefault(emptyList()).forEach { item ->
            val text = loadContent(item)
            if (!text.isNullOrEmpty()) {
                dao.updateHash(item.id, ClipboardHasher.hash(text))
                touched++
            }
        }

        val now = System.currentTimeMillis()
        runCatching { dao.duplicateContents() }.getOrDefault(emptyList()).forEach { content ->
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

    // ---------------------------------------------------------------- 工具

    fun detectContentType(text: String): ClipboardContentType =
        if (LINK_PATTERN.matches(text)) ClipboardContentType.LINK else ClipboardContentType.TEXT

    /**
     * 列表摘要：压掉换行与连续空白，再截断。
     *
     * **只处理开头一小段**：正文可能有几十 MB，摘要只是显示用，
     * 没必要为了 160 字的摘要去正则替换整段内容。
     * 完整内容始终可以按 id 取回（[loadContent]）。
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
         * 默认阈值：UTF-8 字节数超过它就把内容放到文件里。
         *
         * 512KB —— 实测依据：
         * - 500KB 内联时单行约 0.84MB，仍远低于 CursorWindow 的 2MB，列表查询毫秒级；
         * - 1MB 以上单行就逼近上限，历史版本里已经出现过「一条记录搞崩整个列表」；
         * - 阈值再往下调（256KB）只会让文件数量变多、收益很小。
         */
        const val DEFAULT_INLINE_MAX_BYTES = 512L * 1024L

        /** 搬移历史大行时的最小字符数下限（避免中文按字节折算后过小） */
        private const val MIN_MIGRATE_CHARS = 100_000

        /**
         * 轻量搜索索引保留的字符数（约 2KB）。
         * 文件型条目的 `content` 是空的，搜索靠这一小段头部命中。
         */
        const val SEARCH_INDEX_CHARS = 2_048

        /** 建一个指向应用私有目录的文件仓库 */
        fun contentStoreFor(context: Context): ClipboardContentStore =
            ClipboardContentStore(File(context.filesDir, "clipboard"))

        /** 同上，但直接给 files 目录（自测 / 测试用，不依赖 Context） */
        fun contentStoreFor(filesDir: File): ClipboardContentStore =
            ClipboardContentStore(File(filesDir, "clipboard"))

        private val WHITESPACE = Regex("\\s+")
        private val LINK_PATTERN = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    }
}
