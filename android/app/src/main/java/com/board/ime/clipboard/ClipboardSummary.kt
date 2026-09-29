package com.board.ime.clipboard

import com.board.ime.database.entity.ClipboardItem

/**
 * 剪贴板条目的**列表投影**：只有列表 / 搜索需要的那几列，**绝不含完整 content**。
 *
 * 这是本轮架构调整的核心：
 * - 列表、搜索、去重判断一律用 [ClipboardSummary]
 * - 只有「全文」和「插到输入框」才按 id 去读真正的内容
 *   （[ClipboardRepository.loadContent]）
 *
 * 这样任何一条超大文本都不可能把列表查询撑爆
 * （过去一条 10MB 记录会让整个查询抛 `Row too big to fit into CursorWindow`）。
 */
data class ClipboardSummary(
    val id: Long,
    val preview: String?,
    val contentType: ClipboardContentType,
    val contentHash: String,
    /** 完整内容的字节数（用于显示大小；INLINE 是 UTF-8 长度，FILE 是文件大小） */
    val contentSize: Long,
    val contentStorage: ClipboardContentStorage,
    /** 内容在文件里时，是否确认文件还在（用于列表上标注「文件缺失」） */
    val contentFilePath: String,
    val origin: ClipboardOrigin,
    val originType: String?,
    val isPinned: Boolean,
    val isFavorite: Boolean,
    val isDeleted: Boolean,
    /** 服务器上的 SyncClipboard.json 此刻就是这条 */
    val remoteCurrent: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** 固定和收藏都不该被普通清理带走 */
    val isProtected: Boolean get() = isPinned || isFavorite

    /** 是否从其它设备同步来的 */
    val isRemote: Boolean get() = origin == ClipboardOrigin.REMOTE

    /** 「12.3 KB」这种展示用大小 */
    val sizeLabel: String
        get() = when {
            contentSize <= 0L -> ""
            contentSize >= 1024L * 1024L -> "%.1f MB".format(contentSize / 1024.0 / 1024.0)
            contentSize >= 1024L -> "%.1f KB".format(contentSize / 1024.0)
            else -> "$contentSize B"
        }

    /** 大文件条目：界面上会多一个「文件」标记 */
    val isFileBacked: Boolean get() = contentStorage == ClipboardContentStorage.FILE
}

/** 把实体转成列表投影（不会读 content，只为兼容旧调用点） */
fun ClipboardItem.toSummary(): ClipboardSummary = ClipboardSummary(
    id = id,
    preview = preview,
    contentType = contentType,
    contentHash = contentHash,
    contentSize = contentSize,
    contentStorage = contentStorage,
    contentFilePath = contentFilePath,
    origin = origin,
    originType = originType,
    isPinned = isPinned,
    isFavorite = isFavorite,
    isDeleted = isDeleted,
    remoteCurrent = remoteCurrent,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
