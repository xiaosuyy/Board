package com.board.ime.settings

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * 一套配色在某个深浅模式下解析出的完整键盘颜色表。
 *
 * 每一项都对应 [com.board.ime.ui.KeyboardTheme] 的同名颜色字段，
 * View 层不需要知道颜色是「主题给的」还是「用户手改的」。
 */
data class PaletteColors(
    @param:ColorInt val keyboardBackground: Int,
    @param:ColorInt val keyBackground: Int,
    @param:ColorInt val keyPressedBackground: Int,
    @param:ColorInt val functionKeyBackground: Int,
    @param:ColorInt val functionKeyPressedBackground: Int,
    @param:ColorInt val keyText: Int,
    @param:ColorInt val functionKeyText: Int,
    @param:ColorInt val accent: Int,
    @param:ColorInt val accentText: Int,
    @param:ColorInt val divider: Int,
    @param:ColorInt val panelBackground: Int,
    @param:ColorInt val toolbarBackground: Int,
    @param:ColorInt val candidateText: Int,
    @param:ColorInt val candidateMutedText: Int,
    @param:ColorInt val candidateSelectedBackground: Int,
    @param:ColorInt val candidateSelectedText: Int,
    @param:ColorInt val panelSurface: Int,
    @param:ColorInt val panelSurfacePressed: Int,
    @param:ColorInt val pinyinBackground: Int,
    @param:ColorInt val iconColor: Int,
)

/**
 * 键盘配色主题（外观 → 配色主题）。
 *
 * 每套主题都**同时**带浅色与深色两版；实际用哪版由「主题：跟随系统 / 浅色 / 深色」
 * 解析出的 isDark 决定，因此系统切到深色时键盘会自己跟着变。
 *
 * [STANDARD] 返回 null，表示继续用 `colors.xml` 里的默认配色（白 / 黑 / 灰 + 蓝），
 * 保证老用户升级后视觉不变。
 *
 * 每套主题只定义 5 个锚点（底色 / 按键 / 强调 / 文字 / 面板），
 * 按下态、功能键、分隔线、候选高亮等全部由锚点推导，避免手写色值互相打架。
 */
enum class KeyboardPalette(val label: String, val caption: String) {
    STANDARD("标准", "白 / 黑 / 灰 + 蓝"),
    CELADON("青瓷", "青绿 · 沉静"),
    SEASALT("海盐", "湛蓝 · 清爽"),
    TERRACOTTA("暖陶", "陶土 · 温暖"),
    SAKURA("樱雪", "樱粉 · 柔和"),
    WISTERIA("紫藤", "雾紫 · 雅致"),
    INKGOLD("墨金", "炭灰 · 琥珀"),
    ;

    /** 当前深浅模式下的完整颜色表；[STANDARD] 返回 null（沿用资源默认） */
    fun colors(isDark: Boolean): PaletteColors? {
        val table = when (this) {
            STANDARD -> return null
            CELADON -> if (isDark) DARK_CELADON else LIGHT_CELADON
            SEASALT -> if (isDark) DARK_SEASALT else LIGHT_SEASALT
            TERRACOTTA -> if (isDark) DARK_TERRACOTTA else LIGHT_TERRACOTTA
            SAKURA -> if (isDark) DARK_SAKURA else LIGHT_SAKURA
            WISTERIA -> if (isDark) DARK_WISTERIA else LIGHT_WISTERIA
            INKGOLD -> if (isDark) DARK_INKGOLD else LIGHT_INKGOLD
        }
        return table.resolve(isDark)
    }

    companion object {
        fun fromName(value: String?): KeyboardPalette =
            entries.firstOrNull { it.name == value } ?: STANDARD

        // 锚点：底色 / 按键 / 强调 / 文字 / 面板
        private val LIGHT_CELADON = Anchors(c(0xFFE8F0EC), c(0xFFFAFDFB), c(0xFF2E7D5B), c(0xFF16241D), c(0xFFF3F8F5))
        private val DARK_CELADON = Anchors(c(0xFF0F1714), c(0xFF1D2823), c(0xFF63C89B), c(0xFFE8F3ED), c(0xFF151F1A))
        private val LIGHT_SEASALT = Anchors(c(0xFFE8EFF8), c(0xFFFBFDFF), c(0xFF245FB8), c(0xFF141E2C), c(0xFFF1F6FC))
        private val DARK_SEASALT = Anchors(c(0xFF0C1420), c(0xFF18232F), c(0xFF5A9BFF), c(0xFFE4EEFB), c(0xFF111B28))
        private val LIGHT_TERRACOTTA = Anchors(c(0xFFF5ECE2), c(0xFFFFFCF8), c(0xFFB45A36), c(0xFF2B1C13), c(0xFFFAF3EB))
        private val DARK_TERRACOTTA = Anchors(c(0xFF1B1310), c(0xFF2B211A), c(0xFFE08A5F), c(0xFFF5E9DE), c(0xFF231913))
        private val LIGHT_SAKURA = Anchors(c(0xFFF9EDF1), c(0xFFFFFBFD), c(0xFFB94E76), c(0xFF2F1A22), c(0xFFFDF4F7))
        private val DARK_SAKURA = Anchors(c(0xFF1C1216), c(0xFF2C1E24), c(0xFFEC7FA2), c(0xFFF8EAF0), c(0xFF241820))
        private val LIGHT_WISTERIA = Anchors(c(0xFFF1EEF9), c(0xFFFEFCFF), c(0xFF6349BF), c(0xFF221B33), c(0xFFF7F4FD))
        private val DARK_WISTERIA = Anchors(c(0xFF13101D), c(0xFF221C2E), c(0xFFA78BF0), c(0xFFEDE8FA), c(0xFF1B1626))
        private val LIGHT_INKGOLD = Anchors(c(0xFFF0F0EB), c(0xFFFFFFFF), c(0xFFA06A18), c(0xFF201F1B), c(0xFFF7F6F1))
        private val DARK_INKGOLD = Anchors(c(0xFF121417), c(0xFF1F2328), c(0xFFD9A443), c(0xFFECEEF1), c(0xFF1A1D21))

        private fun c(value: Long): Int = value.toInt()
    }
}

/** 一套主题的 5 个锚点颜色 */
private data class Anchors(
    val background: Int,
    val key: Int,
    val accent: Int,
    val text: Int,
    val panel: Int,
)

/** 按锚点推导完整色表：按下态/功能键/分隔线/候选高亮都与底色同色相，不会花 */
private fun Anchors.resolve(isDark: Boolean): PaletteColors {
    val functionKey = blend(key, text, if (isDark) 0.10f else 0.13f)
    val keyPressed = blend(key, text, if (isDark) 0.20f else 0.16f)
    val functionPressed = blend(functionKey, text, if (isDark) 0.26f else 0.20f)
    val softAccent = blend(panel, accent, if (isDark) 0.28f else 0.16f)
    val surface = blend(panel, key, 0.55f)
    return PaletteColors(
        keyboardBackground = background,
        keyBackground = key,
        keyPressedBackground = keyPressed,
        functionKeyBackground = functionKey,
        functionKeyPressedBackground = functionPressed,
        keyText = text,
        functionKeyText = blend(text, key, 0.16f),
        accent = accent,
        accentText = autoTextColor(accent),
        divider = blend(background, text, if (isDark) 0.10f else 0.18f),
        panelBackground = panel,
        toolbarBackground = panel,
        candidateText = text,
        candidateMutedText = blend(text, panel, 0.55f),
        candidateSelectedBackground = softAccent,
        candidateSelectedText = if (isDark) blend(accent, text, 0.30f) else accent,
        panelSurface = surface,
        panelSurfacePressed = blend(surface, text, 0.10f),
        pinyinBackground = blend(panel, text, if (isDark) 0.14f else 0.08f),
        iconColor = blend(text, panel, 0.24f),
    )
}

/** 从 [from] 向 [to] 混色；只用于同色系推导，alpha 一律不透明 */
private fun blend(from: Int, to: Int, amount: Float): Int {
    val t = amount.coerceIn(0f, 1f)
    fun channel(a: Int, b: Int): Int = (a + (b - a) * t).roundToInt().coerceIn(0, 255)
    return Color.rgb(
        channel(Color.red(from), Color.red(to)),
        channel(Color.green(from), Color.green(to)),
        channel(Color.blue(from), Color.blue(to)),
    )
}

/** 按背景亮度自动挑黑 / 白文字（强调色上要用） */
private fun autoTextColor(background: Int): Int =
    if (ColorUtils.calculateLuminance(background) > 0.55) {
        Color.rgb(0x22, 0x22, 0x24)
    } else {
        Color.rgb(0xF7, 0xF8, 0xFA)
    }
