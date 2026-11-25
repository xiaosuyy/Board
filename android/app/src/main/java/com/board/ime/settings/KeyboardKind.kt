package com.board.ime.settings

/**
 * 键盘布局类型（工具栏「切换键盘」图标循环切换）。
 *
 * - [QWERTY]：全键盘，中文（全拼 / 双拼）与英文共用，由「中/EN」键切换语言
 * - [T9]：九键拼音，数字键 2–9 映射拼音字母组
 * - [JAPANESE]：日语罗马字输入（全键盘），由「日/EN」键切换日语 / 英文
 */
enum class KeyboardKind(
    val label: String,
) {
    QWERTY("全键盘"),
    T9("九键"),
    JAPANESE("日语"),
    ;

    /** 下一个布局（工具栏图标循环） */
    fun next(): KeyboardKind = when (this) {
        QWERTY -> T9
        T9 -> JAPANESE
        JAPANESE -> QWERTY
    }

    companion object {
        fun fromName(value: String?): KeyboardKind =
            entries.firstOrNull { it.name == value } ?: QWERTY
    }
}
