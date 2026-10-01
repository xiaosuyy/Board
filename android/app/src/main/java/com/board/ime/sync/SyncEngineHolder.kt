package com.board.ime.sync

import android.content.Context
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.clipboard.HeuristicClipboardSensitivityDetector
import com.board.ime.database.BoardDatabase
import com.board.ime.settings.BoardPreferences
import com.board.ime.sync.webdav.WebDavConfigStore

/**
 * 单例访问点。
 *
 * 输入法服务、设置页、分享页、WorkManager Worker 都在同一个进程里，
 * 共用同一个 [SyncEngine]，避免并发同步 / 状态不一致。
 *
 * **B1 修正（D1）**：[ClipboardRepository] 也必须是**同一个实例**。
 * 真机实测发现：`BoardInputMethodService` 自己 `new` 了一个 repository，
 * 而 B1 的「本机新增历史」回调挂在 [SyncEngineHolder] 创建的那个上 ⇒ 回调永远不触发，
 * Android 端永远不产生 `clipboard_log_seq`。现在所有捕获入口统一走 [clipboardRepository]。
 */
object SyncEngineHolder {

    @Volatile
    private var instance: SyncEngine? = null

    @Volatile
    private var repositorySlot: SingleInstance<ClipboardRepository>? = null

    /**
     * **唯一共享的** [ClipboardRepository]。
     *
     * 所有会产生「本机剪贴板历史」的入口（输入法服务、设置页补抓、分享页、Worker）
     * 都必须从这里取，**不允许再 `ClipboardRepository(...)` 直接 new**。
     */
    fun clipboardRepository(context: Context): ClipboardRepository {
        val slot = repositorySlot ?: synchronized(this) {
            repositorySlot ?: SingleInstance { buildRepository(context.applicationContext) }
                .also { repositorySlot = it }
        }
        return slot.get()
    }

    /** 已经装配好的实例（未装配时为 null） */
    val sharedClipboardRepository: ClipboardRepository?
        get() = repositorySlot?.installed

    private fun buildRepository(context: Context): ClipboardRepository {
        val preferences = BoardPreferences(context)
        return ClipboardRepository(
            dao = BoardDatabase.get(context).clipboardDao(),
            syncOperationDao = BoardDatabase.get(context).syncOperationDao(),
            sensitivityDetector = HeuristicClipboardSensitivityDetector {
                preferences.skipSensitiveClipboard
            },
            contentStore = ClipboardRepository.contentStoreFor(context),
        )
    }

    fun get(context: Context): SyncEngine =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): SyncEngine {
        val database = BoardDatabase.get(context)
        val preferences = BoardPreferences(context)
        val clipboardRepository = clipboardRepository(context)
        val dictionaryRepository = com.board.ime.dictionary.DictionaryRepository(
            database.dictionaryDao(),
            context,
            database.dictionarySourceDao(),
            database,
        )
        // 导入的大词库文件目录（与本地导入共用同一个目录）
        val importedDir = java.io.File(
            com.board.ime.engine.rime.RimeDataInstaller(context).userDir(),
            "imported",
        )
        val engine = SyncEngine(
            configStore = WebDavConfigStore(context),
            clipboardDao = database.clipboardDao(),
            clipboardRepository = clipboardRepository,
            syncOperationDao = database.syncOperationDao(),
            dictionaryRepository = dictionaryRepository,
            dictionarySyncProvider = { client ->
                DictionarySyncBinding(
                    sync = DictionarySourceSync(
                        client = client,
                        sources = database.dictionarySourceDao(),
                        install = { source -> dictionaryRepository.installSourceIntoRime(source) },
                    ),
                    dir = importedDir,
                )
            },
            contentGate = preferences,
            clipboardLogDeviceId = androidDeviceId(context),
        )
        // B1：本机真实新增历史条目时，记一条增量事件（只入内存，不联网、不落盘）。
        // 关键：挂在**同一个** clipboardRepository 上 —— 即 IME / 分享页 / 设置页共用的那一个。
        clipboardRepository.onLocalItemProduced = { item ->
            engine.noteLocalClipboardHistoryItem(item)
        }
        return engine
    }

    /** B1：稳定的本机设备 id（与 DeviceRepository 一致：系统 ANDROID_ID） */
    private fun androidDeviceId(context: Context): String? = runCatching {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        )
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
