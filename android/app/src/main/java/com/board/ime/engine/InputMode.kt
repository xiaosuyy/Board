package com.board.ime.engine

/**
 * 输入状态。
 *
 * 五种状态全部由 `InputController` 管理，`KeyboardView` 只负责按状态显示：
 * - [ENGLISH] / [CHINESE] / [JAPANESE]：字母层，走输入引擎（中文 / 日语由 Rime 提供）
 * - [NUMBER]：数字层
 * - [SYMBOL]：符号层（分「常用符号 / 更多符号」两页）
 */
enum class InputMode(
    /** 候选栏 / 状态标签 */
    val label: String,
    /** 空格键上展示的名称（参考 Gboard 在空格键显示语言） */
    val displayName: String,
) {
    ENGLISH("En", "English"),
    CHINESE("中", "中文"),
    JAPANESE("日", "日本語"),
    NUMBER("123", "123"),
    SYMBOL("#+=", "#+="),
    ;

    /** 是否字母层（可以参与组词 / 联想） */
    val isLetterMode: Boolean
        get() = this == ENGLISH || this == CHINESE || this == JAPANESE

    /** 中英 / 日英互换；非字母层保持不变（由控制器处理“记住的字母层”） */
    fun toggled(): InputMode = when (this) {
        ENGLISH -> CHINESE
        CHINESE -> ENGLISH
        JAPANESE -> ENGLISH
        NUMBER, SYMBOL -> this
    }

    companion object {
        fun fromName(value: String?): InputMode =
            entries.firstOrNull { it.name == value } ?: ENGLISH
    }
}
