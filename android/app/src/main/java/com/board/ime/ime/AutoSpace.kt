package com.board.ime.ime

/**
 * 中英文自动空格（默认关闭，字母 / 数字分开控制）。
 *
 * 只在汉字与半角拉丁字母 / 数字的**交界处**补一个空格：
 * 「你好Linux」→「你好 Linux」、「第5章」→「第 5 章」。
 * 不碰标点、全角字符与空白；已经存在空白时不重复补。
 */
data class AutoSpaceConfig(
    val letters: Boolean = false,
    val digits: Boolean = false,
) {

    val enabled: Boolean
        get() = letters || digits

    /** [left] / [right] 相邻两个字符之间是否需要补空格 */
    fun needsSpace(left: Char?, right: Char?): Boolean {
        if (!enabled || left == null || right == null) return false
        if (left.isWhitespace() || right.isWhitespace()) return false
        val leftHan = isHan(left)
        val rightHan = isHan(right)
        if (leftHan == rightHan) return false
        val other = if (leftHan) right else left
        return when (other) {
            in 'a'..'z', in 'A'..'Z' -> letters
            in '0'..'9' -> digits
            else -> false
        }
    }

    companion object {
        fun isHan(char: Char): Boolean =
            char in '\u4E00'..'\u9FFF' ||
                char in '\u3400'..'\u4DBF' ||
                char in '\uF900'..'\uFAFF'
    }
}
