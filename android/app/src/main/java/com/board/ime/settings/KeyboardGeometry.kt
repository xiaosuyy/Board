package com.board.ime.settings

/**
 * 键盘尺寸的**作用域**。
 *
 * 竖屏、横屏、悬浮键盘各自保存一套参数：横屏不是把竖屏拉宽，
 * 悬浮键盘也继续保持独立布局。
 */
enum class KeyboardScope(val label: String) {
    PORTRAIT("竖屏"),
    LANDSCAPE("横屏"),
    FLOATING("悬浮键盘"),
    ;

    companion object {
        fun fromName(value: String?): KeyboardScope =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PORTRAIT
    }
}

/**
 * 键盘整体高度档位。
 *
 * [factor] 是相对设计基准高度（`kb_key_height` = 50dp）的倍率。
 * [CUSTOM] 时用用户自己拖出来的 [KeyboardGeometry.customHeightFactor]。
 */
enum class KeyboardHeightLevel(val label: String, val factor: Float) {
    SMALL("小", 0.80f),
    STANDARD("标准", 1.00f),
    LARGE("大", 1.18f),
    EXTRA_LARGE("特大", 1.36f),
    CUSTOM("自定义", 1.00f),
    ;

    companion object {
        fun fromName(value: String?): KeyboardHeightLevel =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: STANDARD
    }
}

/**
 * 一个作用域下的键盘几何参数。
 *
 * 用 `-1f` 表示「沿用设计默认值」，这样升级上来的用户不写任何设置时
 * 视觉与旧版本完全一致。
 */
data class KeyboardGeometry(
    val level: KeyboardHeightLevel = KeyboardHeightLevel.STANDARD,
    /** 仅在 [KeyboardHeightLevel.CUSTOM] 下生效（0.6x ~ 1.6x） */
    val customHeightFactor: Float = 1f,
    /** 按键间距（dp）；[UNSET] 表示默认 6/8dp */
    val keyGapDp: Float = UNSET,
    /** 按键圆角（dp）；[UNSET] 表示默认 10dp */
    val keyCornerRadiusDp: Float = UNSET,
    /** 功能键宽度倍率（1.0 = 默认，0.8~1.4） */
    val functionKeyWidthRatio: Float = 1f,
) {
    /** 真正用于换算的键高倍率 */
    val heightFactor: Float
        get() = if (level == KeyboardHeightLevel.CUSTOM) customHeightFactor else level.factor

    val hasCustomGap: Boolean get() = keyGapDp >= 0f
    val hasCustomRadius: Boolean get() = keyCornerRadiusDp >= 0f

    companion object {
        const val UNSET = -1f

        /** 自定义高度的可调范围（避免把键盘拖成畸形） */
        const val MIN_CUSTOM_FACTOR = 0.60f
        const val MAX_CUSTOM_FACTOR = 1.60f

        /** 按键间距范围（dp） */
        const val MIN_GAP_DP = 0f
        const val MAX_GAP_DP = 16f

        /** 功能键宽度倍率范围 */
        const val MIN_FN_RATIO = 0.80f
        const val MAX_FN_RATIO = 1.40f

        val DEFAULT = KeyboardGeometry()
    }
}
