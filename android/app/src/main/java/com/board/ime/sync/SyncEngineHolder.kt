package com.board.ime.sync

import android.content.Context
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.database.BoardDatabase
import com.board.ime.sync.webdav.WebDavConfigStore

/**
 * 单例访问点。
 *
 * 输入法服务、设置页、WorkManager Worker 都在同一个进程里，
 * 共用同一个 [SyncEngine]，避免并发同步 / 状态不一致。
 */
object SyncEngineHolder {

    @Volatile
    private var instance: SyncEngine? = null

    fun get(context: Context): SyncEngine =
        instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

    private fun create(context: Context): SyncEngine {
        val database = BoardDatabase.get(context)
        val preferences = com.board.ime.settings.BoardPreferences(context)
        val clipboardRepository = ClipboardRepository(
            dao = database.clipboardDao(),
            syncOperationDao = database.syncOperationDao(),
            sensitivityDetector = com.board.ime.clipboard.HeuristicClipboardSensitivityDetector {
                preferences.skipSensitiveClipboard
            },
        )
        return SyncEngine(
            configStore = WebDavConfigStore(context),
            clipboardDao = database.clipboardDao(),
            clipboardRepository = clipboardRepository,
            syncOperationDao = database.syncOperationDao(),
            dictionaryRepository = com.board.ime.dictionary.DictionaryRepository(
                database.dictionaryDao(),
                context,
                database.dictionarySourceDao(),
            ),
            contentGate = preferences,
        )
    }
}
