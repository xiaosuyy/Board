package com.board.ime.engine

/**
 * 交给引擎处理的按键。
 *
 * 刻意只暴露“语义按键”，不暴露 Android 的 KeyEvent，
 * 这样后续接入 librime 时只需实现这个接口即可。
 */
sealed interface EngineKey {

    /** 字母按键，[char] 已经由输入控制器根据 Shift 状态决定好大小写 */
    data class Letter(val char: Char) : EngineKey

    /** 数字按键（九键 T9 输入） */
    data class Digit(val char: Char) : EngineKey

    /** 退格 */
    data object Backspace : EngineKey

    /** 空格 */
    data object Space : EngineKey

    /** 回车 */
    data object Enter : EngineKey

    /** 标点（已根据中/英模式转换好全角 / 半角） */
    data class Punctuation(val text: String) : EngineKey
}
