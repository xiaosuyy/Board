package com.board.ime.engine

/**
 * 候选条目。
 *
 * [isHint] 为 true 时表示这不是真正的候选词，只是候选栏里的说明文字
 * （例如“中文引擎未接入”），UI 不应允许点击上屏。
 *
 * [englishGloss] 是 Board 本地词表给出的英文释义（P3），**只用于显示**：
 * - null 表示没有释义，UI 不显示第二行；
 * - 不参与候选下标 / 排序 / 上屏，[text] 仍是唯一上屏内容。
 */
data class Candidate(
    val text: String,
    val label: String = text,
    val enabled: Boolean = true,
    val isHint: Boolean = false,
    val englishGloss: String? = null,
) {
    companion object {
        fun hint(label: String): Candidate = Candidate(
            text = "",
            label = label,
            enabled = false,
            isHint = true,
        )
    }
}
