package com.board.ime.engine.rime

import android.util.Log

/**
 * librime 的 JNI 声明。
 *
 * 对应 `src/main/cpp/board_rime.cpp`，只暴露 librime 官方 C API 的调用，
 * 不含任何输入法逻辑。所有方法都是阻塞调用，由 [RimeRuntime] 负责串行化。
 */
object RimeNative {

    private const val TAG = "BoardRime"

    /** 原生库是否加载成功（缺少对应 ABI 的 .so 时为 false） */
    val isAvailable: Boolean = try {
        System.loadLibrary("board_rime")
        true
    } catch (throwable: Throwable) {
        Log.e(TAG, "board_rime.so 加载失败", throwable)
        false
    }

    external fun nativeSetup(
        sharedDataDir: String,
        userDataDir: String,
        appName: String,
        version: String,
        logDir: String,
    ): Boolean

    external fun nativeFinalize()

    external fun nativeVersion(): String?

    external fun nativeStartMaintenance(fullCheck: Boolean): Boolean

    external fun nativeIsMaintenanceMode(): Boolean

    external fun nativeJoinMaintenance()

    external fun nativeCreateSession(): Long

    external fun nativeDestroySession(session: Long)

    external fun nativeProcessKey(session: Long, keycode: Int, mask: Int): Boolean

    external fun nativeSelectCandidate(session: Long, index: Int): Boolean

    external fun nativeChangePage(session: Long, backward: Boolean): Boolean

    external fun nativeCommitComposition(session: Long): Boolean

    external fun nativeClearComposition(session: Long)

    external fun nativeGetCommit(session: Long): String?

    external fun nativeGetInput(session: Long): String?

    external fun nativeGetCandidates(session: Long): Array<String>?

    external fun nativeSetOption(session: Long, option: String, value: Boolean): Boolean

    external fun nativeGetOption(session: Long, option: String): Boolean

    external fun nativeSelectSchema(session: Long, schemaId: String): Boolean

    external fun nativeGetCurrentSchema(session: Long): String?

    /** 导出用户词典（学习记录）到文本文件，返回条目数；失败返回 -1 */
    external fun nativeExportUserDict(dictName: String, textFile: String): Int

    /** 从文本文件导入用户词典，返回条目数；失败返回 -1 */
    external fun nativeImportUserDict(dictName: String, textFile: String): Int
}
