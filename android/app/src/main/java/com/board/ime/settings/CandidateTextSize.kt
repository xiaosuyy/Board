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
    /** 候选英文释义字号（sp）：辅助信息，明显小于中文 */
    val englishGlossSp: Float,
) {
    SMALL("小", 16f, 14f, 12f, 10f),
    STANDARD("标准", 19f, 16f, 14f, 11f),
    LARGE("大", 22f, 17f, 16f, 12f),
    XLARGE("特大", 25f, 18f, 18f, 13f),
    ;

    companion object {
        fun fromName(value: String?): CandidateTextSize =
            entries.firstOrNull { it.name == value } ?: STANDARD
    }
}
