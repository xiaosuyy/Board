package com.board.ime.keyboard

/**
 * 按键语义类型。
 *
 * 键盘只负责“这个键表示什么”，具体行为由 `InputController` 决定，
 * 这样同一套 View 可以渲染字母层、数字层、符号层。
 */
enum class KeyType {
    /** 字母 / 数字：走输入引擎（受 Shift 影响，参与组词） */
    CHARACTER,

    /** 逗号：跟随当前语言（中文全角「，」，英文半角「,」） */
    COMMA,

    /** 句号：跟随当前语言（中文全角「。」，英文半角「.」） */
    PERIOD,

    /** 固定文本：直接上屏，不经过引擎（符号层用） */
    SYMBOL,

    /** Shift */
    SHIFT,

    /** 退格 */
    BACKSPACE,

    /** 空格 */
    SPACE,

    /** 回车 */
    ENTER,

    /** 中英文切换 */
    LANGUAGE,

    /** 切到数字层（?123） */
    TO_NUMBERS,

    /** 回到字母层（ABC） */
    TO_LETTERS,

    /** 切到符号层（#+=） */
    TO_SYMBOLS,

    /** 切到更多符号（=\< / 更多） */
    TO_MORE_SYMBOLS,

    /** 回到符号第一页（1/2） */
    TO_MAIN_SYMBOLS,
    ;

    /**
     * 是否为功能键（使用深一档底色）。
     * 空格 / 符号键使用普通底色，视觉上更接近 Gboard 的分区。
     */
    val isFunctional: Boolean
        get() = when (this) {
            SHIFT, BACKSPACE, ENTER, LANGUAGE,
            TO_NUMBERS, TO_LETTERS, TO_SYMBOLS, TO_MORE_SYMBOLS, TO_MAIN_SYMBOLS,
            -> true

            CHARACTER, COMMA, PERIOD, SYMBOL, SPACE -> false
        }

    /** 是否需要展开成“多字符标签”的小字号 */
    fun usesCompactLabel(label: String): Boolean = label.length > 1
}
