package com.board.ime.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.board.ime.clipboard.ClipboardContentStorage
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
    /**
     * 内容是内联在 [content] 里，还是放在 Board 自己的文件里。
     *
     * 超长文本走文件：SQLite 单行超过 `CursorWindow`（约 2MB）就再也读不回来，
     * 而且会连累整个列表 / 搜索查询。放在文件里之后，**列表查询根本不会碰 content**，
     * 多大的文本都只是「一条记录 + 一个文件」。
     */
    @ColumnInfo(defaultValue = "INLINE")
    val contentStorage: ClipboardContentStorage = ClipboardContentStorage.INLINE,
    /** 内容在文件里时的绝对路径；[contentStorage] 为 INLINE 时是空串 */
    @ColumnInfo(defaultValue = "")
    val contentFilePath: String = "",
    /** 完整内容的字节数（展示 + 阈值判断用） */
    @ColumnInfo(defaultValue = "0")
    val contentSize: Long = 0L,
    /**
     * **轻量搜索索引**：内容开头的一小段（约 2KB）。
     *
     * 文件型条目的 `content` 是空的，如果只用 `content LIKE` 搜索，
     * 大文本就会「搜不到」。这里保留一个很短的头部副本，搜索两种存储方式都能命中，
     * 又不会把几十 MB 的正文带进索引。
     */
    @ColumnInfo(defaultValue = "")
    val searchText: String = "",
    /**
     * **当前云端内容标记**（语义严格限定）。
     *
     * = 「服务器上的 `SyncClipboard.json` **此刻**就是这条」。
     * **不是**「历史上曾经同步过」，所以服务器内容换掉后旧记录必须被清标记。
     *
     * 与 [origin] 正交，四种组合都合法：
     * - `LOCAL + true` → 本机复制后成功上传（本机 + 云端）
     * - `REMOTE + true` → 从别的设备同步过来的（远端 + 云端）
     * - `LOCAL + false` / `REMOTE + false` → 服务器当前已经不是它
     *
     * 注意：不要用 [originType] 表达这件事，那是「远端设备名」。
     */
    @ColumnInfo(defaultValue = "0")
    val remoteCurrent: Boolean = false,
    /**
     * **未读**（Phase 1-F）。
     *
     * 语义严格限定：**远端同步到本机、且用户尚未查看过**的内容。
     * - `true`：不参与 1 小时历史过期（保护刚同步过来、用户还没看的东西）；
     * - 用户查看（点按 / 长按 / 全文）后置为 `false` → 立即恢复正常生命周期。
     *
     * 与 [remoteCurrent]（服务器此刻是不是这条）正交，**不要混用**。
     */
    @ColumnInfo(defaultValue = "0")
    val isUnread: Boolean = false,
) {
    /** 固定和收藏都不该被普通清理带走 */
    val isProtected: Boolean get() = isPinned || isFavorite

    /** 内容是否放在文件里（此时 [content] 是空的，要用 ClipboardRepository.loadContent） */
    val isFileBacked: Boolean get() = contentStorage == ClipboardContentStorage.FILE
}
