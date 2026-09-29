package com.board.ime.engine

/**
 * 输入引擎抽象。
 *
 * 设计约束（重要）：
 * - 这里不出现任何 librime / 拼音 / 词库格式相关类型，保持与实现无关。
 * - 引擎只负责“组词 → 候选 → 提交”的逻辑，不直接接触 InputConnection，
 *   所有上屏动作由 `InputController` 完成。
 *
 * 后续阶段会新增 `RimeEngine : InputEngine`（通过 JNI 调用 librime），
 * 届时只需替换 `BoardInputMethodService` 中的引擎实例。
 */
interface InputEngine {

    /** 引擎标识，用于日志与设置页展示 */
    val id: String

    /** 引擎展示名 */
    val displayName: String

    /** 当前输入模式。切换模式时引擎应重置内部状态。 */
    var mode: InputMode

    /** 当前正在组词的内容，未组词时为空串 */
    val composing: CharSequence

    /** 当前候选列表，按优先级从高到低 */
    fun candidates(): List<Candidate>

    /** 处理一次按键 */
    fun handleKey(key: EngineKey): EngineResult

    /** 选择第 [index] 个候选词 */
    fun pickCandidate(index: Int): EngineResult

    /** 清空组词状态（切换输入框、失去焦点时调用） */
    fun reset()
}
