package com.board.ime.sync

import android.os.Build
import com.board.ime.clipboard.ClipboardContentStorage
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
import org.json.JSONArray
import org.json.JSONObject

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
    /**
     * B1：本机稳定设备 id（用于 `/clipboard-log/<deviceId>.json` 命名）。
     * 为 null（未注入）时，增量历史整体关闭，行为与以前**完全一致**。
     */
    private val clipboardLogDeviceId: String? = null,
    /** 头像同步（可选：老调用点 / 测试不传也能工作） */
    private val profileStore: ProfileSyncStore? = null,
) {

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** 同步把内容写进本地后，宿主（输入法服务）据此更新系统剪贴板 */
    private val _applied = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val applied: SharedFlow<String> = _applied.asSharedFlow()

    /**
     * 图片落点通道（Phase 1-E）。
     *
     * **与文字完全独立**：不把 `SharedFlow<String>` 重构成 sealed type，
     * 文字链路一行不动。载荷是**本端 hash**，宿主据此找到 PNG 文件并写入图片剪贴板。
     */
    private val _appliedImage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val appliedImage: SharedFlow<String> = _appliedImage.asSharedFlow()

    private val syncMutex = Mutex()

    // ---------------------------------------------------------------- 剪贴板专用通道

    /**
     * 剪贴板**独立**锁：复制触发的自动同步走这条通道，
     * **不等待**词库上传 / 学习词合并，也不会被它们阻塞。
     */
    private val clipboardMutex = Mutex()

    // ---------------------------------------------------------------- B1 增量历史

    /** B1：设备本地 seq 分配器（跨重启单调）；首次使用时从持久化恢复 */
    private var historySequence = ClipboardLogSequence(0L)

    /** B1：待发布的增量历史事件 —— **只在内存**（绝不把正文落盘） */
    private val historyPending = ArrayList<ClipboardLogEvent>()

    /** B1：追加 / 发布 / 水位的串行化锁（同一 device log 绝不并发 GET→modify→PUT） */
    private val historyGate = Any()

    /**
     * B1：**发布 / 消费的协程级串行化**。
     *
     * `syncNow`（Worker）走 `syncMutex`，复制触发的 `syncClipboardOnly` 走 `clipboardMutex`，
     * 两者**互不排斥**；没有这把锁，它们就可能同时对同一个 device log 做
     * GET→modify→PUT，造成丢事件。
     */
    private val historyMutex = Mutex()

    private var historyLoaded = false

    /** B1：各远端设备的 lastSeenSeq（只存 metadata，不存远端正文） */
    private var lastSeenSeq: Map<String, Long> = emptyMap()

    /**
     * 剪贴板同步请求。用 `conflate()` 实现 **trailing-edge 合并**：
     * 窗口内来多少次都只保留一次，且**窗口结束一定执行**（不会像前沿去抖那样永久 SKIPPED）。
     */
    private val clipboardRequests = MutableSharedFlow<Unit>(
        // replay = 1 很关键：**订阅之前**发出的请求也必须能送达。
        // 冷启动时 SyncEngineHolder 刚构造完、collector 还没订阅，没有 replay 的话
        // tryEmit 会被直接丢弃 —— 真机-模拟器实测中，模拟器侧第一条触发就是这么丢的。
        replay = 1,
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
        // 常用语同步通道：**独立分组**，不会被剪贴板 / 大型词库任务长期阻塞
        engineScope.launch {
            phraseRequests.conflate().collect {
                delay(PHRASE_DEBOUNCE_MS)
                runCatching { syncPhrasesOnly() }
            }
        }
    }

    // ---------------------------------------------------------------- 常用语同步

    /**
     * 常用语同步请求通道。
     *
     * **只有用户明确改动**（新增/编辑/删除/拖动排序）才会触发；
     * Pull 合并后的 Room 变化**不会**再自动触发 Push，避免
     * A Push → B Pull → B Push → A Pull 的无限回环。
     */
    private val phraseRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    private var phraseRepository: com.board.ime.phrase.PhraseRepository? = null

    /** 宿主在拿到仓库后接进来（不改构造函数，避免影响其它调用点） */
    fun attachPhraseRepository(repository: com.board.ime.phrase.PhraseRepository) {
        phraseRepository = repository
    }

    /**
     * Pull + merge 落库完成后的回调。
     *
     * 有了它就不必"依赖 Room Flow 一定会帮我刷新"：宿主收到回调后**显式重读数据库并刷新面板**，
     * 这样第一次打开面板就能看到刚同步下来的内容（一次 Pull，不重复请求）。
     */
    var onPhrasesApplied: (() -> Unit)? = null

    /** 用户改动常用语后调用：登记一次 Push（trailing 去抖，快速连续操作只同步一次） */
    fun requestPhraseSync() {
        phraseRequests.tryEmit(Unit)
    }

    /**
     * 常用语：拉取 → 按 **phraseId** 合并 → 仅在确实有新内容时回写 → 落库。
     *
     * - 不回写的情形（`merged` 与远端等价）⇒ 天然防止 Pull→Push 回环；
     * - 合并算法**只用** [SyncPhraseMerge]，这里不再复制一套；
     * - 失败只记日志，**不动本地数据**（本地用户操作永不因同步失败而回滚）。
     */
    private suspend fun syncPhrasesOnly() {
        val repository = phraseRepository ?: return
        if (!contentGate.isEnabled(SyncContent.PHRASE)) return
        val config = loadConfig()
        if (!config.isConfigured) return
        config.securityProblem()?.let { return }

        syncMutex.withLock {
            val started = System.currentTimeMillis()
            val client = clientFactory(config)
            client.ensureDirectory(config.normalizedDir)

            val localRecords = repository.all().map { it.toSyncRecord() }
            val response = client.getText(FILE_PHRASES)
            val remote = when {
                response.isOk -> SyncPhraseCodec.fromJson(response.value.orEmpty())
                    ?: run {
                        SyncLog.warn(SyncEvent.IGNORED_TYPE, "常用语远端文件解析失败，已安全忽略")
                        return@withLock
                    }

                response.error == WebDavError.NOT_FOUND -> emptyList()
                else -> {
                    SyncLog.warn(SyncEvent.NETWORK_ERROR, "常用语拉取失败，本地数据保持不变")
                    return@withLock
                }
            }

            val merged = SyncPhraseMerge.merge(localRecords, remote)

            // 只在"远端不等于合并结果"时回写 → 等价内容不会反复上传
            if (SyncPhraseMerge.needsPush(localRecords, remote)) {
                val put = client.putText(
                    FILE_PHRASES,
                    SyncPhraseCodec.toJson(localDeviceLabel(), merged),
                    CONTENT_TYPE_JSON,
                )
                if (!put.isOk) {
                    SyncLog.warn(SyncEvent.NETWORK_ERROR, "常用语上传失败，本地数据保持不变")
                    return@withLock
                }
            }

            repository.mergeFromSync(merged.map { it.toEntry() })
            // 显式通知宿主刷新（不依赖 Flow 时序）：第一次进入面板就能看到最新内容
            onPhrasesApplied?.invoke()
            // 只记数量与耗时，绝不打正文
            SyncLog.event(
                SyncEvent.UPLOAD,
                "常用语同步：本地=${localRecords.size} 远端=${remote.size} " +
                    "合并=${merged.size} 耗时=${System.currentTimeMillis() - started}ms",
            )
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
        // 传输安全：HTTPS 直接放行；HTTP 需用户明确开启且仅限可信局域网（公网 HTTP 一律拒绝）
        config.securityProblem()?.let { message ->
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

        // 地址 scheme 拼错（例如 htpps://）或传输安全不允许（HTTP 未开启 / 公网 HTTP）时，
        // 不要走到 OkHttp —— 它会抛 IllegalArgumentException，用户只会看到看不懂的异常名。
        config.securityProblem()?.let { message ->
            SyncLog.warn(SyncEvent.NETWORK_ERROR, "同步被拒绝：$message")
            val failed = _status.value.copy(
                kind = SyncStatusKind.FAILED,
                lastError = message,
                clipboard = SyncPart(SyncPartState.FAILED, "传输安全策略拒绝"),
                dictionary = SyncPart(SyncPartState.SKIPPED, "传输安全策略拒绝"),
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

            // B1：发布本机增量历史（与 latest-state 完全独立）
            publishClipboardLog(client)

            val pull = pullRemote(client)
            val tPull = System.currentTimeMillis()
            if (pull is PullOutcome.Failed) {
                publishTransient(_status.value.copy(clipboard = SyncPart(SyncPartState.FAILED, pull.result.status.lastError)))
                return@withLock pull.result
            }

            // B1：消费远端增量历史（只入 History，不写系统剪贴板）
            pullClipboardLogs(client)

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

            // ---------- 阶段 4：账号资料（头像） ----------
            // 失败只记日志，不拖垮整次同步：词库 / 剪贴板已经同步成功。
            val profileSynced = syncProfile(client)
            val tProfile = System.currentTimeMillis()

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
                    "头像=${if (profileSynced) "已处理" else "失败（不影响其它数据）"} " +
                    "耗时[目录=${tEnsure - tStart} 剪贴板=${tPull - tEnsure} 导入词库=${tDict - tPull} " +
                    "词库学习词=${tWords - tDict} 头像=${tProfile - tWords} 合计=${tProfile - tStart}]ms",
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
        if (!isSyncableType(item)) {
            SyncLog.warn(
                SyncEvent.IGNORED_TYPE,
                "本地类型 ${item.contentType} 暂不支持上传，已忽略（不伪装成已同步）",
            )
            complete(operation)
            return null
        }

        // Phase 1-E：图片走二进制 /file/{hash}.png（清单 / 任务队列与文字完全同一套）
        if (item.contentType == ClipboardContentType.IMAGE) {
            return pushImageLocal(client, item, operation)
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

    /**
     * 图片上传（Phase 1-E）。
     *
     * 与文字**共用同一套**「清单 + 任务队列 + 完成标记」；唯一区别是正文以**二进制**
     * PUT 到 `/file/{hash}.png`，**不进 JSON**。
     */
    private suspend fun pushImageLocal(
        client: WebDavClient,
        item: ClipboardItem,
        operation: SyncOperation,
    ): SyncResult? {
        val hash = item.contentHash
        val bytes = if (hash.isNotEmpty()) clipboardRepository.loadImageBytes(hash) else null
        if (bytes == null || bytes.isEmpty()) {
            SyncLog.warn(SyncEvent.IGNORED_TYPE, "这条图片的内容已不可读（文件缺失？），跳过上传")
            complete(operation)
            return null
        }
        if (bytes.size > SyncClipboardMapper.MAX_IMAGE_BYTES) {
            SyncLog.warn(
                SyncEvent.IGNORED_TYPE,
                "图片超过上限（bytes=${bytes.size} limit=${SyncClipboardMapper.MAX_IMAGE_BYTES}），不上传",
            )
            complete(operation)
            return null
        }

        val dataName = SyncClipboardMapper.dataNameForImage(hash)
        val filePut = client.putBytes(SyncClipboardPaths.file(dataName), bytes, CONTENT_TYPE_PNG)
        if (!filePut.isOk) return failPush(operation, filePut)

        val payload = SyncClipboardMapper.toImagePayload(hash, bytes.size.toLong())
        val response = client.putText(
            SyncClipboardPaths.MANIFEST,
            SyncClipboardMapper.payloadToJson(payload),
            CONTENT_TYPE_JSON,
        )
        return when {
            response.isOk -> {
                complete(operation)
                pushCount = 1
                pushedHashThisRun = hash
                clipboardRepository.markRemoteCurrentById(item.id)
                SyncLog.event(
                    SyncEvent.UPLOAD,
                    "图片已上传 hash=${SyncLog.shortHash(hash)} bytes=${bytes.size}",
                )
                null
            }

            else -> failPush(operation, response)
        }
    }

    // ---------------------------------------------------------------- B1 增量历史

    /**
     * B1：本机**真实**产生了一条新的历史条目 ⇒ 记一条增量事件。
     *
     * **只入内存，不联网、不落盘**（隐私：绝不把正文持久化到本地磁盘）。
     * 与 latest-state 去抖**完全无关** —— 即使 A/B 连续复制、去抖最终只 PUT B，
     * 这里仍然留下 A、B 两条事件。
     */
    fun noteLocalClipboardHistoryItem(item: ClipboardItem) {
        val deviceId = clipboardLogDeviceId ?: return
        if (item.contentType != ClipboardContentType.TEXT && item.contentType != ClipboardContentType.LINK) return
        if (item.contentStorage != ClipboardContentStorage.INLINE) return
        val text = item.content
        if (text.isEmpty()) return
        val size = ClipboardHasher.utf8Length(text)
        if (!ClipboardLogRecords.shouldRecord(ClipboardLogRecords.TYPE_TEXT, size)) return

        ensureHistoryLoaded()
        val hash = item.contentHash.ifEmpty { ClipboardHasher.hash(text) }
        var seq = 0L
        synchronized(historyGate) {
            seq = historySequence.next()
            historyPending.add(
                ClipboardLogEvent(
                    seq = seq,
                    itemId = ClipboardLogRecords.itemIdFor(deviceId, seq),
                    hash = hash,
                    createdAt = System.currentTimeMillis(),
                    size = size,
                    text = text,
                ),
            )
        }
        SyncLog.event(SyncEvent.DONE, "clipboard history queued seq=$seq bytes=$size")
    }

    private fun ensureHistoryLoaded() {
        if (historyLoaded) return
        synchronized(historyGate) {
            if (historyLoaded) return
            historySequence = ClipboardLogSequence(configStore.loadLogSeq())
            lastSeenSeq = configStore.loadLastSeenSeq()
            historyLoaded = true
        }
    }

    /**
     * B1：把内存 pending 发布到 `/clipboard-log/<me>.json`。
     *
     * 顺序：GET 自己的日志 → 合并（按 itemId 去重）→ 裁剪 → PUT。
     * 失败 ⇒ **保留 pending**（内存内重试）；seq **绝不回收**。
     * 只写**本设备自己的**文件，绝不触碰别人的 log。
     */
    private suspend fun publishClipboardLog(client: WebDavClient) {
        historyMutex.withLock { publishClipboardLogLocked(client) }
    }

    private suspend fun publishClipboardLogLocked(client: WebDavClient) {
        val deviceId = clipboardLogDeviceId ?: return
        ensureHistoryLoaded()
        var batch: List<ClipboardLogEvent> = emptyList()
        synchronized(historyGate) { batch = ArrayList(historyPending) }
        if (batch.isEmpty()) return

        val path = ClipboardLogRecords.pathFor(deviceId)
        val response = client.getText(path)
        val existing = when {
            response.isOk -> ClipboardLogRecords.parse(response.value) ?: run {
                SyncLog.warn(SyncEvent.IGNORED_TYPE, "增量历史日志解析失败，保留待发事件")
                return
            }

            response.error == WebDavError.NOT_FOUND ->
                ClipboardLogFile(deviceId = ClipboardLogRecords.sanitizeToken(deviceId), deviceType = "Android")

            else -> {
                SyncLog.warn(SyncEvent.NETWORK_ERROR, "增量历史读取失败，保留待发事件")
                return
            }
        }

        // 崩溃窗口防御（seq 复用）：远端水位更高时先抬内存水位；
        // 再对「同 itemId 但内容不同」的待发事件换全新 seq。
        val remoteMax = existing.events.maxOfOrNull { it.seq } ?: 0L
        if (remoteMax > historySequence.value) {
            historySequence = ClipboardLogSequence(remoteMax)
            SyncLog.warn(SyncEvent.IGNORED_TYPE, "clipboard history seq bumped to remote max=$remoteMax")
        }
        val publish = ClipboardLogRecords.reassignReusedSequences(
            batch = batch,
            remoteEvents = existing.events,
            deviceId = deviceId,
            sequence = historySequence,
        )

        // **先落盘 seq，再做 PUT**：只要 PUT 成功过，重启后绝不可能再分配同一个 seq。
        configStore.saveLogSeq(historySequence.value)

        val known = HashSet<String>()
        for (event in existing.events) if (event.itemId.isNotEmpty()) known.add(event.itemId)
        val merged = ArrayList(existing.events)
        var added = 0
        for (event in publish) {
            if (known.add(event.itemId)) {
                merged.add(event)
                added++
            }
        }
        val now = System.currentTimeMillis()
        val compacted = ClipboardLogRecords.compact(existing.copy(events = merged), now)

        val put = client.putText(path, ClipboardLogRecords.serialize(compacted), CONTENT_TYPE_JSON)
        if (!put.isOk) {
            SyncLog.warn(SyncEvent.NETWORK_ERROR, "增量历史发布失败，保留待发事件")
            return
        }
        synchronized(historyGate) {
            // 用**原始** id 清理 pending（重新分配过的条目也一并清掉）
            val published = batch.map { it.itemId }.toHashSet()
            historyPending.removeAll { published.contains(it.itemId) }
        }
        SyncLog.event(SyncEvent.UPLOAD, "clipboard history published count=$added seq=${historySequence.value}")
    }

    /**
     * B1：消费远端各设备的增量历史。
     *
     * **只写本地 History，不写系统剪贴板** ⇒ 不触发系统回调 ⇒ **零回环**。
     * 水位 `lastSeenSeq` 只在「本轮全部成功」后才一次性推进；404 / 坏 JSON 一律不推进。
     */
    private suspend fun pullClipboardLogs(client: WebDavClient) {
        historyMutex.withLock { pullClipboardLogsLocked(client) }
    }

    private suspend fun pullClipboardLogsLocked(client: WebDavClient) {
        val self = clipboardLogDeviceId ?: return
        ensureHistoryLoaded()
        if (!registerInDeviceRegistry(client, self)) return

        val response = client.getText(DEVICES_PATH)
        if (!response.isOk) {
            if (response.error != WebDavError.NOT_FOUND) {
                SyncLog.warn(SyncEvent.IGNORED_TYPE, "远端设备注册表不可读，本轮跳过增量历史")
            }
            return
        }
        val deviceIds = parseDeviceIds(response.value)
        if (deviceIds.isEmpty()) return

        val me = ClipboardLogRecords.sanitizeToken(self)
        val advanced = HashMap<String, Long>()
        for (deviceId in deviceIds) {
            if (deviceId == me) continue
            val seen = lastSeenSeq[deviceId] ?: 0L
            val logResponse = client.getText(ClipboardLogRecords.pathFor(deviceId))
            // 404：不报错、不推进水位
            if (logResponse.error == WebDavError.NOT_FOUND) continue
            if (!logResponse.isOk) {
                SyncLog.warn(SyncEvent.NETWORK_ERROR, "增量历史读取失败，本轮不推进水位")
                return
            }
            val file = ClipboardLogRecords.parse(logResponse.value)
            if (file == null) {
                SyncLog.warn(SyncEvent.IGNORED_TYPE, "增量历史日志损坏，本轮跳过且不推进水位")
                return
            }
            if (ClipboardLogRecords.gapDetected(seen, file)) {
                // 不猜测缺失内容、不伪造 event、不用 latest-state 冒充历史
                SyncLog.warn(
                    SyncEvent.IGNORED_TYPE,
                    "clipboard history log gap detected minSeq=${file.minSeq} seen=$seen",
                )
            }

            val candidates = ClipboardLogRecords.candidatesFor(seen, file)
            if (candidates.isEmpty()) continue

            val processed = ArrayList<Long>(candidates.size)
            for (event in candidates) {
                val text = event.text
                if (text.isNullOrEmpty()) {
                    processed.add(event.seq)
                    continue
                }
                val computed = ClipboardHasher.hash(text)
                if (event.hash.isNotEmpty() && event.hash != computed) {
                    SyncLog.warn(SyncEvent.CONFLICT, "增量历史 hash 与内容不一致，跳过该条")
                    processed.add(event.seq)
                    continue
                }
                // **只入 History，不写系统剪贴板** —— 回环抑制的关键
                clipboardRepository.applyRemote(
                    content = text,
                    contentType = ClipboardContentType.TEXT,
                    remoteHash = computed,
                    originDeviceId = deviceId,
                    originType = deviceId,
                )
                processed.add(event.seq)
            }
            advanced[deviceId] = ClipboardLogRecords.advanceWatermark(seen, processed, true)
        }

        if (advanced.isEmpty()) return
        val updated = HashMap(lastSeenSeq)
        updated.putAll(advanced)
        lastSeenSeq = updated
        configStore.saveLastSeenSeq(updated)
        SyncLog.event(SyncEvent.DOWNLOAD, "clipboard history consumed devices=${advanced.size}")
    }

    /**
     * B1：把自己登记进共享设备注册表 `/library/devices.json`（读改写取并集）。
     * 复用 1-H 同一份注册表，**不新增第二套**。
     */
    private suspend fun registerInDeviceRegistry(client: WebDavClient, self: String): Boolean {
        val me = ClipboardLogRecords.sanitizeToken(self)
        val response = client.getText(DEVICES_PATH)
        val ids = when {
            response.isOk -> parseDeviceIds(response.value)
            response.error == WebDavError.NOT_FOUND -> ArrayList()
            else -> {
                SyncLog.warn(SyncEvent.IGNORED_TYPE, "设备注册表不可读，本轮跳过增量历史")
                return false
            }
        }
        if (ids.contains(me)) return true
        ids.add(me)
        val json = JSONObject().apply {
            put("schema", 1)
            put("devices", JSONArray(ids))
        }.toString()
        val put = client.putText(DEVICES_PATH, json, CONTENT_TYPE_JSON)
        return put.isOk
    }

    /** 只解析设备 id；坏 JSON 一律当作空（安全忽略） */
    private fun parseDeviceIds(json: String?): MutableList<String> {
        val result = ArrayList<String>()
        if (json.isNullOrEmpty()) return result
        return try {
            val array = JSONObject(json).optJSONArray("devices") ?: JSONArray()
            for (index in 0 until array.length()) {
                val raw = array.optString(index)
                if (raw.isBlank()) continue
                val id = ClipboardLogRecords.sanitizeToken(raw)
                if (!result.contains(id)) result.add(id)
            }
            result
        } catch (throwable: Throwable) {
            result
        }
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

        // Phase 1-E：远端图片（二进制），与文字分支完全分开
        if (payload.type == SyncClipboardMapper.TYPE_IMAGE) {
            return pullImage(client, payload)
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

        // 远端是「服务器当前剪贴板」= 最新状态，**无条件写入本地**。
        //
        // 这里以前有一段「本地 updatedAt > 服务器 Last-Modified 就保留本地、丢弃远端」的逻辑，
        // 那是**错的**，而且正是「另一端复制的看不到」的根因：
        //   · 两台设备的时钟不同步（服务器 Last-Modified 只有秒级，还可能跨时区）
        //   · 用户刚在本机复制过内容，updatedAt 就是"刚刚" → 本地几乎永远"更新"
        //   · 于是远端内容被静默丢弃，剪贴板面板里自然什么都看不到
        // SyncClipboard 只有一条当前剪贴板；而我们的顺序是「先 push 再 pull」，
        // 所以 pull 拿到的一定是 push 之后的最新状态，直接应用即可。
        SyncLog.event(
            SyncEvent.DOWNLOAD,
            "远端=${SyncLog.shortHash(payload.hash)} 作为新记录写入本地（不覆盖、不删除本地任何条目）",
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
            // 通知宿主把它设为系统剪贴板（宿主会先登记抑制，避免回环）
            _applied.tryEmit(text)
        }
        return PullOutcome.Applied(if (applied != null) 1 else 0, 1, 0)
    }

    /**
     * 拉取远端图片（Phase 1-E）：**二进制**下载 → 大小/hash 校验 → 落库 → 通知宿主写图片剪贴板。
     *
     * 去重按**远端 hash**（下次 pull 才能短路，不重复下载）。
     * `autoSync = OFF` 视为「暂停」：**不下载、不写剪贴板**。
     */
    private suspend fun pullImage(client: WebDavClient, payload: SyncClipboardPayload): PullOutcome {
        if (!loadConfig().autoSync) {
            SyncLog.event(SyncEvent.IGNORED_TYPE, "autoSync=OFF（暂停），本次不拉取图片")
            return PullOutcome.Applied(0, 0, 0)
        }
        val name = payload.dataName
        if (name.isNullOrBlank()) {
            SyncLog.warn(SyncEvent.IGNORED_TYPE, "远端图片标记 hasData 但缺少 dataName，已安全忽略")
            return PullOutcome.Applied(0, 0, 0)
        }
        val file = client.getBytes(SyncClipboardPaths.file(name))
        if (!file.isOk) return PullOutcome.Failed(fail(file, SyncTrigger.MANUAL))

        val bytes = file.value ?: ByteArray(0)
        if (bytes.isEmpty() || bytes.size > SyncClipboardMapper.MAX_IMAGE_BYTES) {
            SyncLog.warn(
                SyncEvent.IGNORED_TYPE,
                "远端图片为空或超过上限（bytes=${bytes.size}），已忽略",
            )
            return PullOutcome.Applied(0, 0, 0)
        }
        val computed = ClipboardHasher.hashBytes(bytes)
        if (payload.hash.isNotEmpty() && payload.hash != computed) {
            SyncLog.warn(
                SyncEvent.CONFLICT,
                "远端图片 hash 与内容不一致（远端=${SyncLog.shortHash(payload.hash)} " +
                    "本地算=${SyncLog.shortHash(computed)}），拒绝写入",
            )
            return PullOutcome.Applied(0, 1, 0)
        }

        val local = clipboardDao.findSummaryByHash(computed)
        if (local != null) {
            SyncLog.event(SyncEvent.SKIP_SAME_HASH, "图片 hash=${SyncLog.shortHash(computed)} 本地已存在")
            clipboardRepository.markRemoteCurrent(computed)
            return PullOutcome.Applied(0, 0, 1)
        }

        val applied = clipboardRepository.applyRemoteImage(
            remoteBytes = bytes,
            remoteHash = computed,
            originDeviceId = payload.device,
            originType = payload.device,
        ) ?: return PullOutcome.Applied(0, 0, 0)

        val summary = applied.summary
        if (summary != null) {
            SyncLog.event(
                SyncEvent.DOWNLOAD,
                "远端图片已保存 hash=${SyncLog.shortHash(computed)} bytes=${bytes.size}",
            )
            clipboardRepository.markRemoteCurrentById(summary.id)
            // 通知宿主写入系统图片剪贴板（宿主先登记抑制，避免回环）
            _appliedImage.tryEmit(computed)
        }
        return PullOutcome.Applied(if (summary != null) 1 else 0, 1, 0)
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
            // 常用语**不再**走词库分组：它有自己的表 PhraseEntry 与同步通道
            // （requestPhraseSync / syncPhrasesOnly），否则同一文件会有两个写入者。
            // 词库里既有的 PHRASE 行保留在库中不动，只是不再参与本条同步。
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

                // B1：发布本机增量历史（与 latest-state 完全独立）
                publishClipboardLog(client)

                val pull = pullRemote(client)
                if (pull is PullOutcome.Failed) {
                    publishClipboardState(
                        ClipboardSyncState.FAILED,
                        error = pull.result.status.lastError ?: "拉取失败",
                    )
                    return@withLock pull.result
                }

                // B1：消费远端增量历史（只入 History，不写系统剪贴板）
                pullClipboardLogs(client)

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

    private fun isSyncableType(item: ClipboardItem): Boolean = when (item.contentType) {
        ClipboardContentType.TEXT,
        ClipboardContentType.LINK,
        ClipboardContentType.IMAGE,
        -> true

        ClipboardContentType.FILE, ClipboardContentType.UNKNOWN -> false
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

    // ---------------------------------------------------------------- 头像

    /**
     * 只同步头像（登录后拉一次、换头像后推一次）。
     *
     * 和完整同步共用 [syncMutex]，避免与正在跑的词库同步并发改远端文件。
     */
    suspend fun syncProfileOnly(): Boolean {
        val config = loadConfig()
        if (!config.isConfigured) return false
        config.securityProblem()?.let { message ->
            SyncLog.warn(SyncEvent.NETWORK_ERROR, "头像同步被拒绝：$message")
            return false
        }
        return syncMutex.withLock {
            runCatching {
                val client = clientFactory(config)
                val ensure = client.ensureDirectory(config.normalizedDir)
                if (!ensure.isOk && ensure.error != WebDavError.CONFLICT) return@runCatching false
                syncProfile(client)
            }.getOrElse { throwable ->
                SyncLog.warn(
                    SyncEvent.NETWORK_ERROR,
                    "头像同步异常：${throwable.javaClass.simpleName}: ${throwable.message}",
                )
                false
            }
        }
    }

    /**
     * 以 `updatedAt` 后写胜出：远端更新则落盘，本地更新则上传；两边都没有就什么都不做。
     * 头像属于账号资料，不受「同步内容」开关影响。
     */
    private suspend fun syncProfile(client: WebDavClient): Boolean {
        val store = profileStore ?: return true
        val localAt = store.localUpdatedAt()
        val remote = client.getText(FILE_PROFILE)
        if (remote.isOk) {
            val json = runCatching { JSONObject(remote.value.orEmpty()) }.getOrNull()
            val remoteAt = json?.optLong("updatedAt", 0L) ?: 0L
            val avatar = json?.optString("avatar").orEmpty()
            if (remoteAt > localAt) {
                if (avatar.isBlank()) {
                    store.clearAvatar()
                    SyncLog.event(SyncEvent.DONE, "头像：云端已删除，本地同步清除")
                    return true
                }
                if (store.applyAvatar(avatar, remoteAt)) {
                    SyncLog.event(SyncEvent.DONE, "头像：已从云端更新")
                    return true
                }
                SyncLog.warn(SyncEvent.IGNORED_TYPE, "头像：云端图片无法解码，保留本地头像")
            }
            if (localAt > remoteAt) return uploadProfile(client, store, localAt)
            return true
        }
        if (remote.error == WebDavError.NOT_FOUND) {
            return if (localAt > 0L) uploadProfile(client, store, localAt) else true
        }
        SyncLog.warn(SyncEvent.NETWORK_ERROR, "头像同步失败：${describe(remote.error, remote.message)}")
        return false
    }

    private suspend fun uploadProfile(
        client: WebDavClient,
        store: ProfileSyncStore,
        updatedAt: Long,
    ): Boolean {
        // 没有图片（例如用户刚清除头像）也要上传，让其它设备知道「已删除」，
        // 否则云端旧头像会在下次拉取时又回来。
        val avatar = store.encodeAvatar().orEmpty()
        val body = JSONObject()
            .put("updatedAt", updatedAt)
            .put("avatar", avatar)
            .toString()
        val put = client.putText(FILE_PROFILE, body, CONTENT_TYPE_JSON)
        if (!put.isOk) {
            SyncLog.warn(SyncEvent.NETWORK_ERROR, "头像上传失败：${describe(put.error, put.message)}")
            return false
        }
        SyncLog.event(SyncEvent.DONE, if (avatar.isEmpty()) "头像：已上传删除状态" else "头像：已上传")
        return true
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
        const val CONTENT_TYPE_PNG = "image/png"
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

        /** B1：共享设备注册表（1-H 已存在，只读复用，不新增第二套） */
        const val DEVICES_PATH = "library/devices.json"

        /** 账号资料：头像（base64）与更新时间，单独一个文件 */
        const val FILE_PROFILE = "library/profile.json"

        /** 常用语去抖窗口：连续拖动/连续编辑只同步一次 */
        const val PHRASE_DEBOUNCE_MS = 1500L
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
