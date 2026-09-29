package com.board.ime.sync

import android.os.Build
import com.board.ime.clipboard.ClipboardContentType
import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.clipboard.SyncClipboardMapper
import com.board.ime.clipboard.SyncClipboardPaths
import com.board.ime.clipboard.SyncClipboardPayload
import com.board.ime.database.dao.ClipboardDao
import com.board.ime.database.dao.SyncOperationDao
import com.board.ime.database.entity.ClipboardItem
import com.board.ime.database.entity.SyncOperation
import com.board.ime.dictionary.DictionaryRepository
import com.board.ime.dictionary.WordType
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.sync.webdav.WebDavClient
import com.board.ime.sync.webdav.WebDavConfig
import com.board.ime.sync.webdav.WebDavConfigStore
import com.board.ime.sync.webdav.WebDavError
import com.board.ime.sync.webdav.WebDavResponse
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 同步引擎。
 *
 * 数据流严格是：
 * ```
 * 剪贴板 UI ──► ClipboardRepository ──► sync_operation 队列
 *                                            │
 *                              SyncEngine ───┘
 *                                  │  (SyncClipboardMapper 做协议映射)
 *                                  ▼
 *                            WebDavClient（GET/PUT/MKCOL）
 * ```
 * UI **从不**直接调用 WebDAV。
 *
 * 同步语义按 SyncClipboard 的模型：服务器上只有**一份当前剪贴板**（`SyncClipboard.json`）。
 * 因此：
 * - 推送 = 把本地最新内容 PUT 上去
 * - 拉取 = GET 下来，和本地按 hash 比对，需要时作为**新记录**写进本地历史
 *
 * 冲突策略（第一版，明确可预测）：
 * - hash 相同 → `SYNC_SKIP_SAME_HASH`，什么都不做
 * - 本地没有 → 创建（并要求宿主把它设为系统剪贴板）
 * - 远端没有（404）→ 只上传
 * - 双方都有但不同 → 用 Last-Modified 和本地 `updatedAt` 比时间：
 *   远端更新 → 下载成新记录；本地更新 → 上传本地。
 *   **任何情况下都不删除、不覆盖本地已有记录**，并且写 `SYNC_CONFLICT` 日志。
 */
class SyncEngine(
    private val configStore: WebDavConfigStore,
    private val clipboardDao: ClipboardDao,
    private val clipboardRepository: ClipboardRepository,
    private val syncOperationDao: SyncOperationDao,
    private val dictionaryRepository: DictionaryRepository? = null,
    private val contentGate: SyncContentGate = MemorySyncContentGate(),
    private val clientFactory: (WebDavConfig) -> WebDavClient = { OkHttpWebDavClientFactory.create(it) },
) {

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** 同步把内容写进本地后，宿主（输入法服务）据此更新系统剪贴板 */
    private val _applied = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val applied: SharedFlow<String> = _applied.asSharedFlow()

    private val syncMutex = Mutex()

    @Volatile
    private var lastTriggeredAt = 0L

    init {
        // 恢复「最近同步时间 / 摘要 / 错误」，重启设置页不会变成空白
        val (at, summary, error) = configStore.loadLastSync()
        _status.value = SyncStatus(
            kind = if (configStore.load().isConfigured) SyncStatusKind.CONNECTED else SyncStatusKind.NOT_CONFIGURED,
            lastSyncAt = at,
            lastSummary = summary,
            lastError = error,
        )
    }

    private fun publish(status: SyncStatus) {
        _status.value = status
        configStore.saveLastSync(status)
    }

    // ---------------------------------------------------------------- 配置

    fun loadConfig(): WebDavConfig = configStore.load()

    /** 加密存储是否可用（不可用时设置页会给出提示） */
    val isSecureStorageAvailable: Boolean
        get() = configStore.secureStorageAvailable

    fun saveConfig(config: WebDavConfig) {
        configStore.save(config)
        refreshStatusLabel()
    }

    private fun setAutoSyncInternal(enabled: Boolean) {
        configStore.setAutoSync(enabled)
        refreshStatusLabel()
    }

    /** 只改自动同步开关（不重写其它字段） */
    fun setAutoSync(enabled: Boolean) = setAutoSyncInternal(enabled)

    /** 测连接：不写入任何内容，只确认目录可达 + 能读清单 */
    suspend fun testConnection(config: WebDavConfig = loadConfig()): WebDavResponse<Unit> {
        if (!config.isConfigured) {
            return WebDavResponse.fail(WebDavError.NOT_CONFIGURED, message = "未填写 WebDAV 地址")
        }
        val response = clientFactory(config).testConnection()
        recordConnectionResult(response)
        return response
    }

    private fun recordConnectionResult(response: WebDavResponse<Unit>) {
        when {
            response.isOk -> {
                _status.value = _status.value.copy(kind = SyncStatusKind.CONNECTED, lastError = null)
                SyncLog.event(SyncEvent.DONE, "测试连接成功 http=${response.httpCode}")
            }

            response.error == WebDavError.AUTH -> {
                _status.value = _status.value.copy(
                    kind = SyncStatusKind.FAILED,
                    lastError = "认证失败（401/403）",
                )
                SyncLog.warn(SyncEvent.AUTH_ERROR, "测试连接 http=${response.httpCode}")
            }

            else -> {
                val detail = describe(response.error, response.message)
                _status.value = _status.value.copy(kind = SyncStatusKind.FAILED, lastError = detail)
                SyncLog.warn(SyncEvent.NETWORK_ERROR, "测试连接失败：$detail")
            }
        }
    }

    // ---------------------------------------------------------------- 同步

    /**
     * 触发一次同步。
     *
     * @param debounce 用于「复制后自动推送」这类高频触发：距上次同步太近就直接跳过
     */
    suspend fun syncNow(
        trigger: SyncTrigger,
        debounce: Boolean = false,
    ): SyncResult {
        val config = loadConfig()
        if (!config.isConfigured) {
            _status.value = _status.value.copy(kind = SyncStatusKind.NOT_CONFIGURED)
            SyncLog.event(SyncEvent.NOT_CONFIGURED, "触发=$trigger")
            return SyncResult(SyncOutcome.SKIPPED, _status.value)
        }
        if (debounce && System.currentTimeMillis() - lastTriggeredAt < DEBOUNCE_MS) {
            SyncLog.event(SyncEvent.DONE, "触发=$trigger 被去抖跳过")
            return SyncResult(SyncOutcome.SKIPPED, _status.value)
        }

        // 同一时刻只允许一个同步在跑（手动 / 输入法 / Worker 可能同时触发）
        return syncMutex.withLock {
            lastTriggeredAt = System.currentTimeMillis()
            _status.value = _status.value.copy(kind = SyncStatusKind.SYNCING, lastError = null)
            SyncLog.event(SyncEvent.DONE, "开始同步 触发=$trigger dir=${config.normalizedDir}")

            val client = clientFactory(config)
            val tStart = System.currentTimeMillis()
            val ensure = client.ensureDirectory(config.normalizedDir)
            val tEnsure = System.currentTimeMillis()
            if (!ensure.isOk && ensure.error != WebDavError.CONFLICT) {
                return@withLock fail(ensure, trigger)
            }

            val push = pushLocal(client)
            val tPush = System.currentTimeMillis()
            if (push != null) return@withLock push

            val pull = pullRemote(client)
            val tPull = System.currentTimeMillis()
            if (pull is PullOutcome.Failed) return@withLock pull.result

            val downloaded = (pull as? PullOutcome.Applied)?.applied ?: 0
            val conflicts = (pull as? PullOutcome.Applied)?.conflicts ?: 0
            val skipped = (pull as? PullOutcome.Applied)?.skipped ?: 0

            // 词库 / 学习词 / 常用语：一个实体一个文件，合并而不是互相覆盖
            val words = syncWordEntities(client, config)
            val tWords = System.currentTimeMillis()
            if (words is WordOutcome.Failed) return@withLock words.result

            val wordResult = words as? WordOutcome.Applied ?: WordOutcome.Applied(0, 0, 0)

            val status = SyncStatus(
                kind = SyncStatusKind.SUCCESS,
                lastSyncAt = System.currentTimeMillis(),
                lastError = null,
                lastSummary = buildSummary(
                    pushCount,
                    downloaded,
                    conflicts,
                    skipped,
                    wordResult,
                ),
            )
            publish(status)
            SyncLog.event(
                SyncEvent.DONE,
                "同步完成 上传=$pushCount 下载=$downloaded 冲突=$conflicts 跳过=$skipped " +
                    "词库=${wordResult.words} 学习词=${wordResult.learned} 常用语=${wordResult.phrases} " +
                    "耗时[目录=${tEnsure - tStart} 上传=${tPush - tEnsure} 剪贴板=${tPull - tPush} 词库学习词=${tWords - tPull} 合计=${tWords - tStart}]ms",
            )
            return@withLock SyncResult(
                outcome = SyncOutcome.SUCCESS,
                status = status,
                uploaded = pushCount,
                downloaded = downloaded,
                conflicts = conflicts,
                skipped = skipped,
            )
        }
    }

    private var pushCount: Int = 0

    /** @return 非 null 表示同步应当在这里失败返回 */
    private suspend fun pushLocal(client: WebDavClient): SyncResult? {
        pushCount = 0
        val pending = syncOperationDao.findByStatus(SyncOperationStatus.PENDING, QUEUE_LIMIT) +
            syncOperationDao.findByStatus(SyncOperationStatus.FAILED, QUEUE_LIMIT)

        if (pending.isEmpty()) {
            SyncLog.event(SyncEvent.DONE, "没有待上传的任务")
            return null
        }

        // SyncClipboard 只保留「当前剪贴板」，所以只推最新的一条
        val operation = pending.maxByOrNull { it.updatedAt } ?: return null
        val item = operation.entityId.toLongOrNull()?.let { clipboardDao.findById(it) }

        if (item == null || item.isDeleted) {
            complete(operation)
            return null
        }
        if (!isTextSupported(item)) {
            SyncLog.warn(
                SyncEvent.IGNORED_TYPE,
                "本地类型 ${item.contentType} 暂不支持上传，已忽略（不伪装成已同步）",
            )
            complete(operation)
            return null
        }

        val payload = SyncClipboardMapper.toPayload(item)
        val response = client.putText(
            SyncClipboardPaths.MANIFEST,
            SyncClipboardMapper.payloadToJson(payload),
            CONTENT_TYPE_JSON,
        )
        return when {
            response.isOk -> {
                complete(operation)
                pushCount = 1
                SyncLog.event(
                    SyncEvent.UPLOAD,
                    "hash=${SyncLog.shortHash(payload.hash)} bytes=${payload.size}",
                )
                null
            }

            response.error.isRetryable || response.error == WebDavError.AUTH -> failPush(operation, response)
            else -> failPush(operation, response)
        }
    }

    private suspend fun failPush(
        operation: SyncOperation,
        response: WebDavResponse<Unit>,
    ): SyncResult {
        val attempt = operation.attemptCount + 1
        syncOperationDao.update(
            operation.copy(
                status = SyncOperationStatus.FAILED,
                attemptCount = attempt,
                lastError = "${response.error}#${response.httpCode}",
                updatedAt = System.currentTimeMillis(),
            ),
        )
        SyncLog.warn(
            SyncEvent.RETRY,
            "上传失败（第 $attempt 次）：${describe(response.error, response.message)}，任务保留待重试",
        )
        return fail(response, SyncTrigger.AFTER_CLIPBOARD_CHANGE)
    }

    /** 拉取远端清单并合并进本地历史 */
    private suspend fun pullRemote(client: WebDavClient): PullOutcome {
        val response = client.getText(SyncClipboardPaths.MANIFEST)
        if (response.error == WebDavError.NOT_FOUND) {
            SyncLog.event(SyncEvent.DOWNLOAD, "远端还没有 SyncClipboard.json（首次同步）")
            return PullOutcome.Applied(0, 0, 0)
        }
        if (!response.isOk) return PullOutcome.Failed(fail(response, SyncTrigger.MANUAL))

        val payload = SyncClipboardMapper.payloadFromJson(response.value.orEmpty())
        if (payload == null) {
            SyncLog.warn(SyncEvent.IGNORED_TYPE, "SyncClipboard.json 解析失败或字段缺失，已安全忽略")
            return PullOutcome.Applied(0, 0, 0)
        }
        if (!SyncClipboardMapper.isSupported(payload)) {
            SyncLog.warn(
                SyncEvent.IGNORED_TYPE,
                "远端 type=${payload.type} hasData=${payload.hasData} 本轮不支持，安全忽略",
            )
            return PullOutcome.Applied(0, 0, 0)
        }

        val text = payload.text.orEmpty()
        if (text.isEmpty()) {
            SyncLog.warn(SyncEvent.IGNORED_TYPE, "远端文本为空，已忽略")
            return PullOutcome.Applied(0, 0, 0)
        }

        // 校验 hash：协议里的 hash 和内容算出来的不一致时，以「拒绝静默覆盖」为原则
        val computed = ClipboardHasher.hash(text)
        if (payload.hash.isNotEmpty() && payload.hash != computed) {
            SyncLog.warn(
                SyncEvent.CONFLICT,
                "远端 hash 与内容不一致（远端=${SyncLog.shortHash(payload.hash)} 本地算=${SyncLog.shortHash(computed)}），拒绝写入",
            )
            return PullOutcome.Applied(0, 1, 0)
        }

        val local = clipboardDao.findByHash(computed)
        if (local != null) {
            SyncLog.event(SyncEvent.SKIP_SAME_HASH, "hash=${SyncLog.shortHash(computed)} 本地已存在")
            return PullOutcome.Applied(0, 0, 1)
        }

        val localLatest = clipboardDao.latestActive()
        if (localLatest != null) {
            // 双方内容不同：按时间判断谁更新
            val remoteTime = response.lastModified
            val localTime = localLatest.updatedAt
            if (remoteTime != null && localTime > remoteTime) {
                SyncLog.event(
                    SyncEvent.CONFLICT,
                    "远端=${SyncLog.shortHash(payload.hash)} 本地更新（本地 ${localTime} > 远端 $remoteTime），保留本地并上传",
                )
                return PullOutcome.Applied(0, 1, 0)
            }
            SyncLog.event(
                SyncEvent.CONFLICT,
                "远端=${SyncLog.shortHash(payload.hash)} 更新，作为**新记录**写入本地（不覆盖、不删除本地任何条目）",
            )
            val applied = clipboardRepository.applyRemote(
                content = text,
                contentType = if (payload.type == SyncClipboardMapper.TYPE_TEXT) {
                    ClipboardContentType.TEXT
                } else {
                    ClipboardContentType.UNKNOWN
                },
                remoteHash = computed,
                originDeviceId = payload.device,
                originType = payload.device,
            )
            if (applied != null) {
                SyncLog.event(
                    SyncEvent.DOWNLOAD,
                    "hash=${SyncLog.shortHash(computed)} bytes=${payload.size}",
                )
                _applied.tryEmit(applied.content)
            }
            return PullOutcome.Applied(if (applied != null) 1 else 0, 1, 0)
        }

        val applied = clipboardRepository.applyRemote(
            content = text,
            contentType = if (payload.type == SyncClipboardMapper.TYPE_TEXT) {
                ClipboardContentType.TEXT
            } else {
                ClipboardContentType.UNKNOWN
            },
            remoteHash = computed,
        )
        if (applied != null) {
            SyncLog.event(
                SyncEvent.DOWNLOAD,
                "hash=${SyncLog.shortHash(computed)} bytes=${payload.size}",
            )
            // 通知宿主把它设为系统剪贴板（宿主会先登记抑制，避免回环）
            _applied.tryEmit(applied.content)
        }
        return PullOutcome.Applied(if (applied != null) 1 else 0, 0, 0)
    }

    // ---------------------------------------------------------------- 词库 / 学习词 / 常用语

    private sealed interface WordOutcome {
        data class Applied(val words: Int, val learned: Int, val phrases: Int) : WordOutcome
        data class Failed(val result: SyncResult) : WordOutcome
    }

    private data class WordGroup(
        val file: String,
        val label: String,
        val types: List<WordType>,
    )

    /**
     * 按用户勾选的同步内容，逐个实体文件做「拉取 → 合并 → 写回」。
     *
     * 合并**不是互相覆盖**：同词不同拼音算两条、词频取最大、墓碑（删除）按时间生效。
     * 学习词合并后写回 librime 用户词典，所以它真的会参与候选排序。
     */
    private suspend fun syncWordEntities(client: WebDavClient, config: WebDavConfig): WordOutcome {
        val repository = dictionaryRepository
        val groups = wordGroups()
        var words = 0
        var learned = 0
        var phrases = 0

        if (repository != null) {
            groups.forEach { group ->
                val result = syncWordGroup(client, group, repository)
                if (result is WordOutcome.Failed) return result
                val applied = result as WordOutcome.Applied
                if (group.types.contains(WordType.PHRASE)) phrases += applied.words else words += applied.words
            }
        }

        if (contentGate.isEnabled(SyncContent.LEARNED_WORD)) {
            val result = syncLearnedWords(client, config)
            if (result is WordOutcome.Failed) return result
            learned = (result as WordOutcome.Applied).words
        }
        return WordOutcome.Applied(words, learned, phrases)
    }

    private fun wordGroups(): List<WordGroup> {
        val groups = ArrayList<WordGroup>()
        val dictionaryTypes = LinkedHashSet<WordType>()
        if (contentGate.isEnabled(SyncContent.USER_DICTIONARY)) {
            dictionaryTypes += listOf(WordType.CUSTOM, WordType.PINNED, WordType.BLOCKED)
        }
        if (contentGate.isEnabled(SyncContent.CUSTOM_WORD)) {
            dictionaryTypes += WordType.CUSTOM
        }
        if (dictionaryTypes.isNotEmpty()) {
            groups += WordGroup(FILE_DICTIONARY, "用户词库", dictionaryTypes.toList())
        }
        if (contentGate.isEnabled(SyncContent.PHRASE)) {
            groups += WordGroup(FILE_PHRASES, "常用语", listOf(WordType.PHRASE))
        }
        return groups
    }

    private suspend fun syncWordGroup(
        client: WebDavClient,
        group: WordGroup,
        repository: DictionaryRepository,
    ): WordOutcome {
        val local = repository.exportRecords(group.types)
        val response = client.getText(group.file)
        val remote = when {
            response.isOk -> SyncWordCodec.fromJson(response.value.orEmpty())?.words
                ?: run {
                    SyncLog.warn(SyncEvent.IGNORED_TYPE, "${group.label} 远端文件解析失败，已安全忽略")
                    return WordOutcome.Applied(0, 0, 0)
                }

            response.error == WebDavError.NOT_FOUND -> emptyList()
            else -> return WordOutcome.Failed(fail(response, SyncTrigger.MANUAL))
        }

        if (local.isEmpty() && remote.isEmpty()) return WordOutcome.Applied(0, 0, 0)
        val merged = SyncWordMerge.merge(local, remote)

        // 合并结果写回服务器（这样别的设备也能拿到合并后的并集）
        if (merged != remote) {
            val put = client.putText(
                group.file,
                SyncWordCodec.toJson(
                    SyncWordFile(
                        device = localDeviceLabel(),
                        exportedAt = System.currentTimeMillis(),
                        words = merged,
                    ),
                ),
                CONTENT_TYPE_JSON,
            )
            if (!put.isOk) return WordOutcome.Failed(fail(put, SyncTrigger.MANUAL))
        }

        val applied = repository.mergeRecords(merged)
        if (applied > 0) {
            // 让自定义词 / 固定词 / 常用语真正进 librime
            repository.syncCustomPhrasesToRime()
        }
        SyncLog.event(
            SyncEvent.UPLOAD,
            "${group.label} 合并：本地=${local.size} 远端=${remote.size} 写回=$applied",
        )
        return WordOutcome.Applied(applied, 0, 0)
    }

    /**
     * 学习词：从 librime 用户词典导出 → 与远端合并（词频取最大）→ 写回 librime。
     *
     * 写回用的是 `UserDbImporter::Put` 的 `max(commits)` 语义，
     * 所以 A 的 10 和 B 的 6 合并后是 10，重复同步也不会累加。
     */
    private suspend fun syncLearnedWords(
        client: WebDavClient,
        config: WebDavConfig,
    ): WordOutcome {
        // 用户正在打字：这次不碰 librime 用户词典（导出会先销毁会话，会打断组词）
        if (RimeRuntime.isRecentlyActive(INPUT_QUIET_WINDOW_MS)) {
            SyncLog.event(
                SyncEvent.IGNORED_TYPE,
                "最近 $INPUT_QUIET_WINDOW_MS ms 内有输入，本次跳过学习词同步（下次再传）",
            )
            return WordOutcome.Applied(0, 0, 0)
        }
        val dir = RimeRuntime.userDataDir()
            ?: run {
                SyncLog.warn(
                    SyncEvent.IGNORED_TYPE,
                    "Rime 尚未初始化，本次跳过学习词同步（打开一次键盘即可）",
                )
                return WordOutcome.Applied(0, 0, 0)
            }

        val exportFile = File(dir, LEARNED_EXPORT_FILE)
        val count = RimeRuntime.exportUserDict(exportFile)
        val localRecords = if (count > 0 && exportFile.exists()) {
            runCatching { LearnedWordBridge.parseUserDictText(exportFile.readText()) }
                .getOrDefault(emptyList())
                .filter { !it.deleted }
        } else {
            emptyList()
        }
        exportFile.delete()

        val response = client.getText(FILE_LEARNED)
        val remoteFile = when {
            response.isOk -> SyncWordCodec.fromJson(response.value.orEmpty())
                ?: run {
                    SyncLog.warn(SyncEvent.IGNORED_TYPE, "学习词远端文件解析失败，已安全忽略")
                    return WordOutcome.Applied(0, 0, 0)
                }

            response.error == WebDavError.NOT_FOUND -> SyncWordFile()
            else -> return WordOutcome.Failed(fail(response, SyncTrigger.MANUAL))
        }

        if (localRecords.isEmpty() && remoteFile.words.isEmpty()) return WordOutcome.Applied(0, 0, 0)
        val merged = SyncWordMerge.merge(localRecords, remoteFile.words)

        if (merged != remoteFile.words) {
            val put = client.putText(
                FILE_LEARNED,
                SyncWordCodec.toJson(
                    SyncWordFile(
                        device = localDeviceLabel(),
                        exportedAt = System.currentTimeMillis(),
                        words = merged,
                    ),
                ),
                CONTENT_TYPE_JSON,
            )
            if (!put.isOk) return WordOutcome.Failed(fail(put, SyncTrigger.MANUAL))
        }

        // 真正写回 librime：这才会影响候选排序
        val imported = LearnedWordBridge.applyToRime(merged)
        SyncLog.event(
            SyncEvent.DOWNLOAD,
            "学习词合并：本地=${localRecords.size} 远端=${remoteFile.words.size} librime导入=$imported",
        )
        // 刷新设置页里的学习词镜像
        dictionaryRepository?.refreshLearnedFromRime()
        return WordOutcome.Applied(if (imported > 0) merged.size else 0, 0, 0)
    }

    /** 只用于同步文件里的「来自哪台设备」，不是账号信息 */
    private fun localDeviceLabel(): String = Build.MODEL ?: "android"

    // ---------------------------------------------------------------- 工具

    private sealed interface PullOutcome {
        data class Applied(val applied: Int, val conflicts: Int, val skipped: Int) : PullOutcome
        data class Failed(val result: SyncResult) : PullOutcome
    }

    private suspend fun complete(operation: SyncOperation) {
        syncOperationDao.update(
            operation.copy(status = SyncOperationStatus.COMPLETED, updatedAt = System.currentTimeMillis()),
        )
    }

    private fun isTextSupported(item: ClipboardItem): Boolean = when (item.contentType) {
        ClipboardContentType.TEXT, ClipboardContentType.LINK -> true
        ClipboardContentType.IMAGE, ClipboardContentType.FILE, ClipboardContentType.UNKNOWN -> false
    }

    private fun fail(response: WebDavResponse<*>, trigger: SyncTrigger): SyncResult {
        val detail = describe(response.error, response.message)
        val event = when (response.error) {
            WebDavError.AUTH -> SyncEvent.AUTH_ERROR
            WebDavError.NETWORK, WebDavError.TIMEOUT -> SyncEvent.NETWORK_ERROR
            WebDavError.SERVER -> SyncEvent.SERVER_ERROR
            else -> SyncEvent.NETWORK_ERROR
        }
        SyncLog.warn(event, "$detail（触发=$trigger，本地数据与待同步任务都保留）")

        val status = SyncStatus(
            kind = SyncStatusKind.FAILED,
            lastSyncAt = _status.value.lastSyncAt,
            lastError = detail,
            lastSummary = null,
        )
        publish(status)

        val outcome = when (response.error) {
            WebDavError.NOT_CONFIGURED, WebDavError.AUTH, WebDavError.PROTOCOL -> SyncOutcome.CONFIG_ERROR
            WebDavError.NOT_FOUND -> SyncOutcome.SUCCESS
            else -> SyncOutcome.RETRYABLE_ERROR
        }
        return SyncResult(outcome = outcome, status = status)
    }

    private fun describe(error: WebDavError, message: String?): String {
        val base = when (error) {
            WebDavError.NONE -> "正常"
            WebDavError.NOT_CONFIGURED -> "未配置 WebDAV"
            WebDavError.NOT_FOUND -> "服务器上没有该文件（404）"
            WebDavError.AUTH -> "认证失败（401/403）"
            WebDavError.CONFLICT -> "目录不存在或冲突（409）"
            WebDavError.SERVER -> "服务器错误（5xx）"
            WebDavError.NETWORK -> "网络不可用"
            WebDavError.TIMEOUT -> "连接超时"
            WebDavError.PROTOCOL -> "响应格式错误"
            WebDavError.UNEXPECTED -> "未预期的错误"
        }
        return if (message.isNullOrBlank()) base else "$base · $message"
    }

    private fun buildSummary(
        uploaded: Int,
        downloaded: Int,
        conflicts: Int,
        skipped: Int,
        words: WordOutcome.Applied = WordOutcome.Applied(0, 0, 0),
    ): String = buildString {
        append("上传 $uploaded · 下载 $downloaded")
        if (words.words > 0) append(" · 词库 ${words.words}")
        if (words.learned > 0) append(" · 学习词 ${words.learned}")
        if (words.phrases > 0) append(" · 常用语 ${words.phrases}")
        if (conflicts > 0) append(" · 冲突 $conflicts")
        if (skipped > 0) append(" · 跳过 $skipped")
    }

    private fun refreshStatusLabel() {
        val config = loadConfig()
        if (!config.isConfigured) {
            _status.value = _status.value.copy(kind = SyncStatusKind.NOT_CONFIGURED, lastError = null)
        } else if (_status.value.kind == SyncStatusKind.NOT_CONFIGURED) {
            _status.value = _status.value.copy(kind = SyncStatusKind.CONNECTED)
        }
    }

    private companion object {
        const val CONTENT_TYPE_JSON = "application/json"
        const val QUEUE_LIMIT = 20
        const val DEBOUNCE_MS = 3_000L

        /** 每种实体一个文件，不塞进一个巨大 JSON */
        const val FILE_DICTIONARY = "board_dictionary.json"
        const val FILE_LEARNED = "board_learned.json"
        const val FILE_PHRASES = "board_phrases.json"
        const val LEARNED_EXPORT_FILE = "board_learned_export.txt"

        /** 最近这么久内有输入，就先不导出学习词（避免打断组词） */
        const val INPUT_QUIET_WINDOW_MS = 5_000L
    }
}

/** 让默认参数不用直接依赖 OkHttp 的具体类 */
object OkHttpWebDavClientFactory {
    fun create(config: WebDavConfig): WebDavClient =
        com.board.ime.sync.webdav.OkHttpWebDavClient(config)
}
