package com.board.ime.settings

import androidx.annotation.ColorInt

/**
 * 可以单独自定义的颜色项。
 *
 * 默认全部不覆盖 —— 不设置时沿用 `colors.xml` 里的
 * 白 / 黑 / 灰 + 少量蓝，保证默认视觉和现在完全一致。
 */
enum class AppearanceColor(val label: String, val hint: String) {
    ACCENT("强调色", "选中候选、面板高亮"),
    KEYBOARD_BACKGROUND("键盘背景", "键盘最底层"),
    KEY_BACKGROUND("按键背景", "普通按键"),
    KEY_TEXT("按键文字", "字母与数字"),
    CANDIDATE_BAR("候选栏背景", "候选词所在的一条"),
    CANDIDATE_TEXT("候选文字", "候选词本身"),
    CANDIDATE_SELECTED("选中候选", "当前高亮的那一个"),
    TOOLBAR("工具栏", "顶部工具栏背景"),
    ;

    companion object {
        fun fromName(value: String?): AppearanceColor? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * 可以单独调节的圆角项。
 *
 * **每一项都有上下限**，避免用户拖出畸形 UI（例如圆角大过键高）。
 */
enum class AppearanceRadius(val label: String, val minDp: Float, val maxDp: Float) {
    KEYBOARD("键盘圆角", 0f, 32f),
    KEY("按键圆角", 0f, 24f),
    CANDIDATE_CHIP("候选 Chip 圆角", 0f, 16f),
    CLIPBOARD_CARD("剪贴板卡片圆角", 0f, 20f),
    ;

    /** 默认值（dp），与 `dimens.xml` 一致 */
    val defaultDp: Float
        get() = when (this) {
            KEYBOARD -> 14f
            KEY -> 10f
            CANDIDATE_CHIP -> 6f
            CLIPBOARD_CARD -> 10f
        }

    companion object {
        fun fromName(value: String?): AppearanceRadius? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * 外观覆盖配置。
 *
 * - [colors]：只放**用户显式改过**的颜色（没有的项走默认）
 * - [radii]：同上，`-1f` 表示默认
 */
data class AppearanceConfig(
    val colors: Map<AppearanceColor, Int> = emptyMap(),
    val radii: Map<AppearanceRadius, Float> = emptyMap(),
) {
    @ColorInt
    fun colorOr(target: AppearanceColor, fallback: Int): Int = colors[target] ?: fallback

    fun color(target: AppearanceColor): Int? = colors[target]

    /** 取圆角（dp）；没有设置时返回 [fallbackDp]，并夹在允许范围内 */
    fun radiusDp(target: AppearanceRadius, fallbackDp: Float): Float {
        val value = radii[target] ?: return fallbackDp
        return value.coerceIn(target.minDp, target.maxDp)
    }

    val hasAnyOverride: Boolean get() = colors.isNotEmpty() || radii.isNotEmpty()

    companion object {
        val DEFAULT = AppearanceConfig()
        const val UNSET_RADIUS = -1f
    }
}
