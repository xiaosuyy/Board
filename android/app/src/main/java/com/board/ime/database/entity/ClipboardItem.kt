package com.board.ime.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.board.ime.clipboard.ClipboardContentType
import com.board.ime.clipboard.ClipboardOrigin

/**
 * 剪贴板条目。
 *
 * 本轮仍是**纯本地**：不做跨设备同步，但字段已按将来同步的需要留好：
 * - [contentHash]：内容哈希，用来做去重（同一段文本只留一条）
 * - [isPinned] / [isFavorite]：固定 / 收藏；固定项目不会被「清空全部历史」带走
 * - [isDeleted] + [deletedAt]：软删除，便于将来做同步冲突合并
 * - [sourceDeviceId]：null 表示本机产生；[SOURCE_REMOTE] 之类表示来自其它设备
 * - [updatedAt]：最近一次复制 / 使用时间，列表排序与去重都靠它
 */
@Entity(
    tableName = "clipboard_item",
    indices = [
        Index(value = ["contentHash"]),
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"]),
        Index(value = ["sourceDeviceId"]),
        Index(value = ["deletedAt"]),
        Index(value = ["isDeleted"]),
        Index(value = ["origin"]),
    ],
)
data class ClipboardItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 完整内容；插入输入框时用的就是它 */
    val content: String,
    val contentType: ClipboardContentType = ClipboardContentType.TEXT,
    /** 列表展示用的一行摘要（**不是**完整内容） */
    val preview: String? = null,
    @ColumnInfo(defaultValue = "")
    val contentHash: String = "",
    val sourceDeviceId: String? = null,
    /** 这条内容来自本机还是其他设备（同步来的） */
    @ColumnInfo(defaultValue = "LOCAL")
    val origin: ClipboardOrigin = ClipboardOrigin.LOCAL,
    /** 远端设备标识（协议里可能没有，能拿到就记下来） */
    val originDeviceId: String? = null,
    /** 远端设备类型 / 名称，例如 Windows；拿不到就为 null，展示成「云端」 */
    val originType: String? = null,
    /** 落到本机的时间（只有 [ClipboardOrigin.REMOTE] 才有意义） */
    val receivedAt: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val isPinned: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null,
) {
    /** 固定和收藏都不该被普通清理带走 */
    val isProtected: Boolean get() = isPinned || isFavorite
}
