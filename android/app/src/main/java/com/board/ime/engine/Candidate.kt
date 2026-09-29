package com.board.ime.engine

/**
 * 候选条目。
 *
 * [isHint] 为 true 时表示这不是真正的候选词，只是候选栏里的说明文字
 * （例如“中文引擎未接入”），UI 不应允许点击上屏。
 */
data class Candidate(
    val text: String,
    val label: String = text,
    val enabled: Boolean = true,
    val isHint: Boolean = false,
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
