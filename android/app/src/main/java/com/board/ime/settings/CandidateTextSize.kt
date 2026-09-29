package com.board.ime.settings

/**
 * 候选词字号档位（设置 → 外观 → 候选词字号）。
 *
 * 只影响顶部面板里的候选词 / 拼音字号与左右间距，不影响底部键盘。
 * 数值以 sp 计，取用时按 scaledDensity 换算，因此跟随系统字体缩放。
 */
enum class CandidateTextSize(
    val label: String,
    /** 候选词字号（sp） */
    val candidateSp: Float,
    /** 组词（拼音）字号（sp） */
    val pinyinSp: Float,
    /** 候选词左右内边距（dp） */
    val horizontalPaddingDp: Float,
) {
    SMALL("小", 16f, 11f, 12f),
    STANDARD("标准", 19f, 13f, 14f),
    LARGE("大", 22f, 14f, 16f),
    XLARGE("特大", 25f, 15f, 18f),
    ;

    companion object {
        fun fromName(value: String?): CandidateTextSize =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}
