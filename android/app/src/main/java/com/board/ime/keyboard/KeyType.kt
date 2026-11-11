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

    /**
     * 标点键：微信字母层底行那颗「，。」。
     * 点按 = 逗号（中文「，」/ 英文「,」），长按 = 句号（中文「。」/ 英文「.」）。
     */
    PUNCTUATION,

    /** 固定文本：直接上屏，不经过引擎（符号层用） */
    SYMBOL,

    /** 数字层第二页（小键盘）切换：微信数字层底部的「12/34」 */
    NUMBER_PAGE,

    /** 未实现的功能键（如符号层的分类、符号条收起的 ✕），点击提示尚未开发 */
    NOT_IMPLEMENTED,

    /** Shift */
    SHIFT,

    /** 退格 */
    BACKSPACE,

    /** 空格 */
    SPACE,

    /** 回车（短按执行 EditorInfo 声明的动作：搜索 / 发送 / 完成…；长按插入换行） */
    ENTER,

    /** 换行（只插入换行，不触发编辑器动作；微信数字/符号层底部的「换行」） */
    ENTER_NEWLINE,

    /** 中英文切换（微信显示为「中/英」） */
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
            SHIFT, BACKSPACE, ENTER, ENTER_NEWLINE, LANGUAGE, NUMBER_PAGE, NOT_IMPLEMENTED,
            TO_NUMBERS, TO_LETTERS, TO_SYMBOLS, TO_MORE_SYMBOLS, TO_MAIN_SYMBOLS,
            -> true

            CHARACTER, COMMA, PERIOD, PUNCTUATION, SYMBOL, SPACE -> false
        }

    /** 是否需要展开成“多字符标签”的小字号 */
    fun usesCompactLabel(label: String): Boolean = label.length > 1
}
