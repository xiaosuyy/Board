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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
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
    /** 大型词库文件同步（可选：老调用点不传也能工作） */
    private val dictionarySyncProvider: ((WebDavClient) -> DictionarySyncBinding?)? = null,
    private val clientFactory: (WebDavConfig) -> WebDavClient = { OkHttpWebDavClientFactory.create(it) },
) {

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** 同步把内容写进本地后，宿主（输入法服务）据此更新系统剪贴板 */
    private val _applied = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val applied: SharedFlow<String> = _applied.asSharedFlow()

    private val syncMutex = Mutex()

    // ---------------------------------------------------------------- 剪贴板专用通道

    /**
     * 剪贴板**独立**锁：复制触发的自动同步走这条通道，
     * **不等待**词库上传 / 学习词合并，也不会被它们阻塞。
     */
    private val clipboardMutex = Mutex()

    /**
     * 剪贴板同步请求。用 `conflate()` 实现 **trailing-edge 合并**：
     * 窗口内来多少次都只保留一次，且**窗口结束一定执行**（不会像前沿去抖那样永久 SKIPPED）。
     */
    private val clipboardRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 本次同步里真正上传成功的 hash（用于状态判定） */
    @Volatile
    private var pushedHashThisRun: String? = null

    @Volatile
    private var lastTriggeredAt = 0L

    init {
        // 恢复「最近同步时间 / 摘要 / 错误 / 各通道结束状态」，重启设置页不会变成空白
        val (at, summary, error) = configStore.loadLastSync()
        val (clipboardPart, dictionaryPart) = configStore.loadLastParts()
        val (clipState, pushedHash, pushedAt) = configStore.loadClipboardExtras()
        val clipError = configStore.loadClipboardError()
        _status.value = SyncStatus(
            kind = if (configStore.load().isConfigured) SyncStatusKind.CONNECTED else SyncStatusKind.NOT_CONFIGURED,
            lastSyncAt = at,
            lastSummary = summary,
            lastError = error,
            clipboard = clipboardPart,
            dictionary = dictionaryPart,
            clipboardState = clipState,
            lastPushedHash = pushedHash,
            lastPushedAt = pushedAt,
            clipboardError = clipError,
        )
        // 剪贴板同步通道：合并窗口结束后的那次一定执行（trailing edge，不会永久丢任务）
        engineScope.launch {
            clipboardRequests.conflate().collect {
                delay(DEBOUNCE_MS)
                runCatching { syncClipboardOnly() }
            }
        }
    }

    /**
     * 请求一次「剪贴板同步」（复制后 / 打开剪贴板面板时调用）。
     *
     * trailing-edge 合并：这里只登记"还需要同步一次"，窗口结束一定会执行最新一次。
     */
    fun requestClipboardSync() {
        val accepted = clipboardRequests.tryEmit(Unit)
        SyncLog.event(
            SyncEvent.DONE,
            "clipboard auto trigger source=AFTER_CLIPBOARD_CHANGE debounce scheduled=true coalesced=${!accepted}",
        )
        if (_status.value.clipboardState != ClipboardSyncState.SYNCED) {
            publishClipboardState(ClipboardSyncState.PENDING)
        }
    }

    private fun publish(status: SyncStatus) {
        _status.value = status
        configStore.saveLastSync(status)
    }

    /**
     * 只更新内存里的状态，**不写持久化**。
     *
     * 词库下载进度可能每秒回调好几次，每次都写 SharedPreferences 太重，
     * 所以进度用这个；阶段结束态才走 [publish]。
     */
    private fun publishTransient(status: SyncStatus) {
        _status.value = status
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
        if (!config.hasValidScheme) {
            val message = "WebDAV 地址必须以 http:// 或 https:// 开头"
            _status.value = _status.value.copy(kind = SyncStatusKind.FAILED, lastError = message)
            SyncLog.warn(SyncEvent.NETWORK_ERROR, "测试连接被拒绝：$message")
            return WebDavResponse.fail(WebDavError.PROTOCOL, message = message)
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

        // 地址 scheme 拼错（例如 htpps://）时不要走到 OkHttp —— 它会抛
        // IllegalArgumentException，用户只会看到看不懂的异常名。这里提前给出人话。
        if (!config.hasValidScheme) {
            val message = "WebDAV 地址必须以 http:// 或 https:// 开头"
            SyncLog.warn(SyncEvent.NETWORK_ERROR, "同步被拒绝：$message（地址非法）")
            val failed = _status.value.copy(
                kind = SyncStatusKind.FAILED,
                lastError = message,
                clipboard = SyncPart(SyncPartState.FAILED, "地址非法"),
                dictionary = SyncPart(SyncPartState.SKIPPED, "地址非法"),
            )
            publish(failed)
            return SyncResult(outcome = SyncOutcome.CONFIG_ERROR, status = failed)
        }

        // 同一时刻只允许一个同步在跑（手动 / 输入法 / Worker 可能同时触发）
        return syncMutex.withLock {
            lastTriggeredAt = System.currentTimeMillis()
            _status.value = _status.value.copy(
                kind = SyncStatusKind.SYNCING,
                lastError = null,
                clipboard = SyncPart(SyncPartState.SYNCING),
                dictionary = if (contentGate.isEnabled(SyncContent.IMPORTED_DICTIONARY)) {
                    SyncPart(SyncPartState.IDLE, "等待剪贴板同步")
                } else {
                    SyncPart(SyncPartState.SKIPPED, "未勾选")
                },
            )
            SyncLog.event(SyncEvent.DONE, "开始同步 触发=$trigger dir=${config.normalizedDir}")

            // 从这里开始整段兜底：任何异常都必须把状态推进到终态（FAILED），
            // 绝不允许停在 SYNCING —— 否则 UI 会永久显示「同步中」。
            try {
            val client = clientFactory(config)
            val tStart = System.currentTimeMillis()
            val ensure = client.ensureDirectory(config.normalizedDir)
            val tEnsure = System.currentTimeMillis()
            if (!ensure.isOk && ensure.error != WebDavError.CONFLICT) {
                return@withLock fail(ensure, trigger)
            }

            // ---------- 阶段 1：剪贴板（永远优先，先结束自己的状态） ----------
            val push = pushLocal(client)
            val tPush = System.currentTimeMillis()
            if (push != null) {
                publishTransient(_status.value.copy(clipboard = SyncPart(SyncPartState.FAILED, push.status.lastError)))
                return@withLock push
            }

            val pull = pullRemote(client)
            val tPull = System.currentTimeMillis()
            if (pull is PullOutcome.Failed) {
                publishTransient(_status.value.copy(clipboard = SyncPart(SyncPartState.FAILED, pull.result.status.lastError)))
                return@withLock pull.result
            }

            val downloaded = (pull as? PullOutcome.Applied)?.applied ?: 0
            val conflicts = (pull as? PullOutcome.Applied)?.conflicts ?: 0
            val skipped = (pull as? PullOutcome.Applied)?.skipped ?: 0
            // 剪贴板阶段到此结束：大型词库再慢也不会让这一行显示「同步中」
            // 剪贴板阶段结束：只有真的推上去了才 SYNCED，否则 IDLE（不许虚报「已同步」）
            val pushedNow = pushedHashThisRun
            if (pushCount > 0 && pushedNow != null) {
                publishClipboardState(ClipboardSyncState.SYNCED, hash = pushedNow)
            } else {
                publishClipboardState(ClipboardSyncState.IDLE)
            }
            SyncLog.event(
                SyncEvent.DONE,
                "剪贴板阶段完成 上传=$pushCount 下载=$downloaded 冲突=$conflicts 跳过=$skipped " +
                    "耗时=${tPull - tPush}ms",
            )

            // ---------- 阶段 2：导入词库（大型文件，独立状态 + 进度） ----------
            val dictionaryPart = syncDictionarySources(client, config.normalizedDir)
            val tDict = System.currentTimeMillis()

            // ---------- 阶段 3：词库 / 学习词 / 常用语：一个实体一个文件，合并而不是互相覆盖 ----------
            val words = syncWordEntities(client, config)
            val tWords = System.currentTimeMillis()
            if (words is WordOutcome.Failed) {
                return@withLock words.result
            }

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
                    dictionaryPart.detail,
                ),
                clipboard = SyncPart(SyncPartState.SUCCESS, "上传 $pushCount · 下载 $downloaded"),
                dictionary = dictionaryPart,
            )
            publish(status)
            SyncLog.event(
                SyncEvent.DONE,
                "同步完成 上传=$pushCount 下载=$downloaded 冲突=$conflicts 跳过=$skipped " +
                    "词库=${wordResult.words} 学习词=${wordResult.learned} 常用语=${wordResult.phrases} " +
                    "导入词库=${dictionaryPart.label} " +
                    "耗时[目录=${tEnsure - tStart} 剪贴板=${tPull - tEnsure} 导入词库=${tDict - tPull} " +
                    "词库学习词=${tWords - tDict} 合计=${tWords - tStart}]ms",
            )
            return@withLock SyncResult(
                outcome = SyncOutcome.SUCCESS,
                status = status,
                uploaded = pushCount,
                downloaded = downloaded,
                conflicts = conflicts,
                skipped = skipped,
            )
            } catch (throwable: Throwable) {
                // 兜底：异常也要给 UI 一个明确结束状态
                SyncLog.warn(
                    SyncEvent.NETWORK_ERROR,
                    "同步异常：${throwable.javaClass.simpleName}: ${throwable.message}（本地数据与任务都保留）",
                )
                val previous = _status.value
                val status = SyncStatus(
                    kind = SyncStatusKind.FAILED,
                    lastSyncAt = previous.lastSyncAt,
                    lastError = "${throwable.javaClass.simpleName}: ${shorten(throwable.message.orEmpty())}",
                    clipboard = previous.clipboard.settledIfRunning(throwable.javaClass.simpleName),
                    dictionary = previous.dictionary.settledIfRunning(throwable.javaClass.simpleName),
                )
                publish(status)
                SyncResult(outcome = SyncOutcome.RETRYABLE_ERROR, status = status)
            }
        }
    }

    private var pushCount: Int = 0

    /** @return 非 null 表示同步应当在这里失败返回 */
    private suspend fun pushLocal(client: WebDavClient): SyncResult? {
        pushCount = 0
        pushedHashThisRun = null
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

        // 完整内容可能在文件里（超长剪贴板），这里按 id 读出来
        val content = clipboardRepository.loadContent(item)
        if (content.isNullOrEmpty()) {
            SyncLog.warn(SyncEvent.IGNORED_TYPE, "这条记录的内容已不可读（文件缺失？），跳过上传")
            complete(operation)
            return null
        }

        val payload = SyncClipboardMapper.toPayload(item, content)
        // 超长文本不内联进 JSON：正文先上传到 /file/{dataName}，清单里只留元信息。
        // 否则一个 5MB 的剪贴板会在「拼 JSON / 序列化 / 解析」环节反复分配好几份内存。
        val inline = if (payload.size > SyncClipboardMapper.INLINE_TEXT_MAX_BYTES) {
            val dataName = SyncClipboardMapper.dataNameFor(payload.hash)
            val filePut = client.putText(
                SyncClipboardPaths.file(dataName),
                content,
                CONTENT_TYPE_TEXT,
            )
            if (!filePut.isOk) return failPush(operation, filePut)
            SyncClipboardMapper.toFilePayload(item, content)
        } else {
            payload
        }

        val response = client.putText(
            SyncClipboardPaths.MANIFEST,
            SyncClipboardMapper.payloadToJson(inline),
            CONTENT_TYPE_JSON,
        )
        return when {
            response.isOk -> {
                complete(operation)
                pushCount = 1
                pushedHashThisRun = payload.hash
                // 本机成功上传 → 本地这条就是「服务器当前内容」，立即打标记（不必等下一次 pull）
                clipboardRepository.markRemoteCurrentById(item.id)
                SyncLog.event(
                    SyncEvent.UPLOAD,
                    "hash=${SyncLog.shortHash(payload.hash)} bytes=${payload.size} " +
                        "inline=${inline.text != null}",
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

        // 超长文本：清单里只有元信息，正文要从 /file/{dataName} 单独拉
        val text = if (payload.type == SyncClipboardMapper.TYPE_TEXT && payload.hasData) {
            val name = payload.dataName
            if (name.isNullOrBlank()) {
                SyncLog.warn(SyncEvent.IGNORED_TYPE, "远端标记 hasData 但缺少 dataName，已安全忽略")
                return PullOutcome.Applied(0, 0, 0)
            }
            val file = client.getText(SyncClipboardPaths.file(name))
            if (!file.isOk) return PullOutcome.Failed(fail(file, SyncTrigger.MANUAL))
            SyncLog.event(
                SyncEvent.DOWNLOAD,
                "远端超长文本已下载 chars=${file.value?.length ?: 0}",
            )
            file.value.orEmpty()
        } else {
            payload.text.orEmpty()
        }

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

        val local = clipboardDao.findSummaryByHash(computed)
        if (local != null) {
            SyncLog.event(SyncEvent.SKIP_SAME_HASH, "hash=${SyncLog.shortHash(computed)} 本地已存在")
            // 服务器当前内容就是本地这条（可能是本机复制过、也被上传过）：
            // 不建重复记录、保留原 origin，只把它标成「云端当前内容」。
            clipboardRepository.markRemoteCurrent(computed)
            return PullOutcome.Applied(0, 0, 1)
        }

        val localLatest = clipboardDao.latestSummary()
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
                clipboardRepository.markRemoteCurrentById(applied.id)
                _applied.tryEmit(text)
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
            clipboardRepository.markRemoteCurrentById(applied.id)
            // 通知宿主把它设为系统剪贴板（宿主会先登记抑制，避免回环）
            _applied.tryEmit(text)
        }
        return PullOutcome.Applied(if (applied != null) 1 else 0, 0, 0)
    }

    // ---------------------------------------------------------------- 导入词库（大型文件）

    /**
     * 阶段 2：大型导入词库的文件同步。
     *
     * 与剪贴板阶段**完全独立**：剪贴板早在这一步之前就已经显示「已同步」，
     * 这里只更新「词库」那一行的状态和进度百分比。
     */
    private suspend fun syncDictionarySources(client: WebDavClient, syncDir: String): SyncPart {
        if (!contentGate.isEnabled(SyncContent.IMPORTED_DICTIONARY)) {
            SyncLog.event(SyncEvent.IGNORED_TYPE, "用户未勾选「导入词库」，本次跳过大型词库同步")
            return SyncPart(SyncPartState.SKIPPED, "未勾选")
        }
        val binding = dictionarySyncProvider?.invoke(client) ?: run {
            SyncLog.event(SyncEvent.IGNORED_TYPE, "本次运行没有接入词库数据源，跳过")
            return SyncPart(SyncPartState.SKIPPED, "未接入数据源")
        }
        val dir = binding.dir

        publishTransient(_status.value.copy(dictionary = SyncPart(SyncPartState.SYNCING)))
        val started = System.currentTimeMillis()
        var lastProgressAt = 0L
        val report = try {
            binding.sync.sync(dir, syncDir) { name, fraction ->
                // 节流：最多每 400ms 刷一次 UI（进度条不需要更细）
                val now = System.currentTimeMillis()
                if (now - lastProgressAt >= PROGRESS_THROTTLE_MS) {
                    lastProgressAt = now
                    publishTransient(
                        _status.value.copy(
                            dictionary = SyncPart(SyncPartState.SYNCING, name, fraction),
                        ),
                    )
                }
            }
        } catch (throwable: Throwable) {
            SyncLog.warn(
                SyncEvent.NETWORK_ERROR,
                "词库同步异常：${throwable.javaClass.simpleName}: ${throwable.message}（本地词库保留）",
            )
            val failed = SyncPart(SyncPartState.FAILED, throwable.javaClass.simpleName)
            publish(_status.value.copy(dictionary = failed))
            return failed
        }

        val detail = buildString {
            append("上传 ${report.uploaded} · 下载 ${report.downloaded}")
            if (report.skipped > 0) append(" · 跳过 ${report.skipped}")
            if (report.installed > 0) append(" · Rime ${report.installed}")
            if (report.failed > 0) append(" · 失败 ${report.failed}")
        }
        val part = if (report.failed > 0) {
            SyncPart(SyncPartState.FAILED, report.firstError ?: detail)
        } else {
            SyncPart(SyncPartState.SUCCESS, detail)
        }
        publish(_status.value.copy(dictionary = part))
        SyncLog.event(
            SyncEvent.DONE,
            "词库阶段完成 $detail 上传字节=${report.uploadedBytes} 下载字节=${report.downloadedBytes} " +
                "耗时=${System.currentTimeMillis() - started}ms",
        )
        return part
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

    /** 失败自动重试计数（成功即清零） */
    private val retryAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 上传失败后的**自动重试**（指数退避）。
     *
     * 不依赖 `ConnectivityManager` 回调 —— 真机上实测 Wi-Fi 恢复时系统日志有 `onAvailable`，
     * 但**没有回调到应用**，所以恢复重试必须自己能跑起来。
     * 首次 15s，之后 30/45/60/75s，最多 5 次；WorkManager 仍是更长期的兜底。
     */
    private fun scheduleClipboardRetry() {
        val attempt = retryAttempts.incrementAndGet()
        if (attempt > MAX_CLIPBOARD_RETRY) return
        val waitMs = minOf(RETRY_BASE_MS * attempt, RETRY_MAX_MS)
        engineScope.launch {
            delay(waitMs)
            if (_status.value.clipboardState == ClipboardSyncState.FAILED) {
                SyncLog.event(
                    SyncEvent.RETRY,
                    "剪贴板同步自动重试 attempt=$attempt wait=${waitMs}ms retry=true",
                )
                requestClipboardSync()
            }
        }
    }

    /**
     * 发布剪贴板通道的 5 态。
     *
     * **只有 [ClipboardSyncState.SYNCED] 且带 hash 时才写 lastPushedHash/lastPushedAt**，
     * 这是「服务端已经收到」的唯一凭据；本地入库 / markRemoteCurrent 都不会碰它。
     */
    private fun publishClipboardState(
        state: ClipboardSyncState,
        hash: String? = null,
        error: String? = null,
    ) {
        val current = _status.value
        val synced = state == ClipboardSyncState.SYNCED && !hash.isNullOrBlank()
        if (synced) retryAttempts.set(0)   // 成功就清零，下次失败重新从 15s 开始
        publish(
            current.copy(
                clipboardState = state,
                clipboardError = if (state == ClipboardSyncState.FAILED) error else null,
                lastPushedHash = if (synced) hash else current.lastPushedHash,
                lastPushedAt = if (synced) System.currentTimeMillis() else current.lastPushedAt,
            ),
        )
        SyncLog.event(
            SyncEvent.DONE,
            "clipboard state=${state.name}" +
                (if (synced) " hash=${SyncLog.shortHash(hash!!)}" else "") +
                (if (state == ClipboardSyncState.FAILED) " error=${error ?: "未知"} retry=true" else ""),
        )
    }

    /**
     * **只同步剪贴板**：复制后的自动同步走这条独立通道。
     *
     * 与词库 / 学习词彻底解耦：
     * - 用独立的 [clipboardMutex]，不参与 `syncMutex`（词库上传几十秒也不会阻塞它）
     * - 不做词库、不做学习词、不做 schema 部署
     * - 上传成功（HTTP 2xx）才 → SYNCED 并记录 lastPushedHash
     * - 没有待上传任务 → IDLE（UI 显示「无需同步」，**不显示已同步**）
     */
    suspend fun syncClipboardOnly(
        trigger: SyncTrigger = SyncTrigger.AFTER_CLIPBOARD_CHANGE,
    ): SyncResult {
        val config = loadConfig()
        if (!config.isConfigured) {
            publishClipboardState(ClipboardSyncState.FAILED, error = "未配置 WebDAV")
            return SyncResult(SyncOutcome.SKIPPED, _status.value)
        }
        if (!config.hasValidScheme) {
            publishClipboardState(ClipboardSyncState.FAILED, error = "WebDAV 地址非法")
            return SyncResult(SyncOutcome.CONFIG_ERROR, _status.value)
        }
        return clipboardMutex.withLock {
            publishClipboardState(ClipboardSyncState.SYNCING)
            try {
                val client = clientFactory(config)
                val ensure = client.ensureDirectory(config.normalizedDir)
                if (!ensure.isOk && ensure.error != WebDavError.CONFLICT) {
                    SyncLog.warn(SyncEvent.NETWORK_ERROR, "剪贴板同步：目录不可达（任务保留，稍后重试）")
                    publishClipboardState(
                        ClipboardSyncState.FAILED,
                        error = describe(ensure.error, ensure.message),
                    )
                    scheduleClipboardRetry()
                    return@withLock SyncResult(SyncOutcome.RETRYABLE_ERROR, _status.value)
                }

                val push = pushLocal(client)
                if (push != null) {
                    // 任务没有被 complete，仍是 FAILED/PENDING，下次会重试
                    publishClipboardState(
                        ClipboardSyncState.FAILED,
                        error = push.status.lastError ?: "上传失败",
                    )
                    scheduleClipboardRetry()
                    return@withLock push
                }

                val pull = pullRemote(client)
                if (pull is PullOutcome.Failed) {
                    publishClipboardState(
                        ClipboardSyncState.FAILED,
                        error = pull.result.status.lastError ?: "拉取失败",
                    )
                    return@withLock pull.result
                }

                val pushed = pushedHashThisRun
                if (pushCount > 0 && pushed != null) {
                    publishClipboardState(ClipboardSyncState.SYNCED, hash = pushed)
                    SyncLog.event(
                        SyncEvent.UPLOAD,
                        "clipboard push started=true http=2xx success=true hash=${SyncLog.shortHash(pushed)}",
                    )
                } else {
                    publishClipboardState(ClipboardSyncState.IDLE)
                }
                SyncResult(SyncOutcome.SUCCESS, _status.value, uploaded = pushCount)
            } catch (throwable: Throwable) {
                SyncLog.warn(
                    SyncEvent.NETWORK_ERROR,
                    "剪贴板同步异常：${throwable.javaClass.simpleName}（任务保留，稍后重试）",
                )
                publishClipboardState(ClipboardSyncState.FAILED, error = throwable.javaClass.simpleName)
                SyncResult(SyncOutcome.RETRYABLE_ERROR, _status.value)
            }
        }
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
            // 关键：失败时不能把「还在同步中」的通道原样留下，
            // 否则 UI 上会永远显示「剪贴板 同步中」。
            clipboard = _status.value.clipboard.settledIfRunning("未完成"),
            dictionary = _status.value.dictionary.settledIfRunning("未完成"),
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
        return if (message.isNullOrBlank()) base else "$base · ${shorten(message)}"
    }

    /**
     * 把异常信息压成一行短文本。
     *
     * Room 的迁移校验异常会带一整张表结构 dump（几千字），
     * 原样存进 `lastError` 再显示出来会把设置页整页撑爆（真机上见过）。
     * 详细信息看日志，UI 只需要一句人能读的。
     */
    private fun shorten(message: String): String {
        val singleLine = message.lineSequence().firstOrNull().orEmpty().trim()
        val clean = if (singleLine.isNotEmpty()) singleLine else message.trim()
        return if (clean.length <= MAX_ERROR_CHARS) clean else clean.take(MAX_ERROR_CHARS) + "…"
    }

    private fun buildSummary(
        uploaded: Int,
        downloaded: Int,
        conflicts: Int,
        skipped: Int,
        words: WordOutcome.Applied = WordOutcome.Applied(0, 0, 0),
        dictionaryDetail: String? = null,
    ): String = buildString {
        append("上传 $uploaded · 下载 $downloaded")
        if (words.words > 0) append(" · 词库 ${words.words}")
        if (words.learned > 0) append(" · 学习词 ${words.learned}")
        if (words.phrases > 0) append(" · 常用语 ${words.phrases}")
        if (!dictionaryDetail.isNullOrBlank()) append(" · 导入词库（$dictionaryDetail）")
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
        const val CONTENT_TYPE_TEXT = "text/plain; charset=utf-8"
        const val QUEUE_LIMIT = 20
        const val DEBOUNCE_MS = 3_000L
        /** 剪贴板失败自动重试：首次 15s，之后递增，最多 5 次 */
        private const val RETRY_BASE_MS = 15_000L
        private const val RETRY_MAX_MS = 120_000L
        private const val MAX_CLIPBOARD_RETRY = 5

        /** 进度刷新节流：最多每 400ms 刷一次词库进度 */
        const val PROGRESS_THROTTLE_MS = 400L

        /** 存进 SharedPreferences / 显示在设置页的错误信息上限（防止一整张表结构 dump 撑爆 UI） */
        const val MAX_ERROR_CHARS = 160

        /** 每种实体一个文件，不塞进一个巨大 JSON */
        const val FILE_DICTIONARY = "board_dictionary.json"
        const val FILE_LEARNED = "board_learned.json"
        const val FILE_PHRASES = "board_phrases.json"
        const val LEARNED_EXPORT_FILE = "board_learned_export.txt"

        /** 最近这么久内有输入，就先不导出学习词（避免打断组词） */
        const val INPUT_QUIET_WINDOW_MS = 5_000L
    }
}

/** 一次词库同步所需的绑定：同步器 + 本地词库目录 */
data class DictionarySyncBinding(
    val sync: DictionarySourceSync,
    val dir: File,
)

/** 让默认参数不用直接依赖 OkHttp 的具体类 */
object OkHttpWebDavClientFactory {
    fun create(config: WebDavConfig): WebDavClient =
        com.board.ime.sync.webdav.OkHttpWebDavClient(config)
}
