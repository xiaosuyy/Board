package com.board.ime.clipboard

/**
 * 剪贴板条目的内容类型。
 *
 * 本轮只有 [TEXT] / [LINK] 会被真正写入；[IMAGE] / [FILE] 先占位，
 * 等以后做图片 / 文件同步时直接复用这个枚举，不需要再迁移数据库。
 * 从旧版本迁移过来的 `IMAGE_REFERENCE` / `FILE_REFERENCE` 会落到 [UNKNOWN]。
 */
enum class ClipboardContentType {
    TEXT,
    LINK,
    IMAGE,
    FILE,
    UNKNOWN,
    ;

    /** 是否能当作文本直接插入输入框 */
    val isTextLike: Boolean
        get() = this == TEXT || this == LINK || this == UNKNOWN
}
