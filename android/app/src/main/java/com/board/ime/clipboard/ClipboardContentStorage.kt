package com.board.ime.clipboard

/**
 * 一条剪贴板内容的存放方式。
 *
 * 背景：Android 的 SQLite 通过 `CursorWindow`（约 2MB）读数据，
 * **单行内容超过它就会 `Row too big to fit into CursorWindow`**，
 * 而且会让整个列表 / 搜索查询一起失败。
 *
 * 所以超长文本不再塞进 `clipboard_item.content`，而是落到 Board 自己的文件里：
 * - [INLINE]：[ClipboardItem.content] 里就是完整内容
 * - [FILE]：[ClipboardItem.content] 为空，真正内容在 [ClipboardItem.contentFilePath]
 *
 * 列表 / 搜索永远只读 [ClipboardSummary]，不碰 content，因此多大的文本都不会拖垮列表。
 */
enum class ClipboardContentStorage {
    /** 直接存在 SQLite 里 */
    INLINE,

    /** 存在 Board 私有目录的文件里 */
    FILE,
}
