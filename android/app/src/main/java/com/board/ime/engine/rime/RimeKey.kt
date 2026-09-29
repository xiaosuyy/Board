package com.board.ime.engine.rime

/**
 * librime 使用的按键编码。
 *
 * librime 沿用 X11 keysym：可打印 ASCII 的 keysym 就等于其 ASCII 码
 * （'a' = 0x61，'1' = 0x31，'-' = 0x2D …），因此字母 / 数字 / 常用符号
 * 可以直接用字符码发送，功能键使用 X11 定义的特殊值。
 */
object RimeKey {

    const val SPACE = 0x20
    const val BACKSPACE = 0xFF08
    const val RETURN = 0xFF0D
    const val ESCAPE = 0xFF1B
    const val DELETE = 0xFFFF

    /** Shift 修饰位（librime 的 RimeModifier） */
    const val MASK_SHIFT = 1

    /** Ctrl 修饰位 */
    const val MASK_CONTROL = 1 shl 2

    fun of(character: Char): Int = character.code
}
