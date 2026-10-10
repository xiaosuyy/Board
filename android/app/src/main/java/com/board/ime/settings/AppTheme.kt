package com.board.ime.settings

import androidx.appcompat.app.AppCompatDelegate

/**
 * 把「设置 → 外观 → 主题」应用到**设置页自身**（此前只影响键盘，设置页永远跟随系统）。
 *
 * 键盘颜色由 [com.board.ime.ui.KeyboardTheme] 在运行时按 [ThemeMode] 单独解析，
 * 与这里的 AppCompat 夜间模式互不影响；这里只让设置页 / 对话框跟着同一份偏好走。
 */
object AppTheme {

    fun nightModeOf(mode: ThemeMode): Int = when (mode) {
        ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
    }

    /** 幂等：已经是目标模式时不做任何事（避免 Activity 反复重建）。 */
    fun apply(mode: ThemeMode) {
        val target = nightModeOf(mode)
        if (AppCompatDelegate.getDefaultNightMode() != target) {
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }
}
