package com.board.ime.engine

/**
 * 引擎对一次按键的响应。
 */
sealed interface EngineResult {

    /**
     * 引擎已处理按键，但不需要改变输入框内容
     * （例如候选列表发生变化）。
     */
    data object Handled : EngineResult

    /**
     * 引擎不处理该按键，由输入控制器执行默认行为。
     * 例如引擎没有组词时按空格，控制器会直接输入一个空格。
     */
    data object Ignored : EngineResult

    /**
     * 正在组词，需要把 [text] 写入 InputConnection 的 composing 区域。
     * 英文联想、后续的拼音输入都走这条路径。
     */
    data class Composing(val text: String) : EngineResult

    /**
     * 提交文本上屏。
     * [clearComposition] 为 true 时会结束当前 composing 状态。
     * [composing] 非空时，上屏后仍需保留这段未选完的拼音（逐字选词，如「许」之后的 pengcheng）。
     */
    data class Commit(
        val text: String,
        val clearComposition: Boolean = true,
        val composing: String = "",
    ) : EngineResult
}
