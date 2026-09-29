package com.board.ime.settings

import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * 判断 Board 输入法在系统里是否已启用 / 可用。
 *
 * ⚠️ **不要**读 `Settings.Secure.ENABLED_INPUT_METHODS`：
 * 从 Android 14（API 34）起这个 key 只对 `targetSdk ≤ 33` 的应用可读，
 * 直接读会抛 `SecurityException`，而它在 `onResume` 里 → 设置页一打开就闪退。
 * 真机（Android 16 / targetSdk 36）实测过：崩溃栈就是这一行。
 *
 * 这里改用**公开 API**，并且整段用 runCatching 兜底：
 * 任何系统限制 / 空服务都退化成「未启用」，绝不让设置页崩溃。
 */
object ImeStatus {

    fun isBoardEnabled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(InputMethodManager::class.java)
            ?: return@runCatching false
        manager.enabledInputMethodList.any { info -> info.packageName == context.packageName }
    }.getOrDefault(false)

    /** 当前系统里是否有 Board（用于「关于」页展示） */
    fun isBoardInstalled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(InputMethodManager::class.java)
            ?: return@runCatching false
        manager.inputMethodList.any { info -> info.packageName == context.packageName }
    }.getOrDefault(false)
}
