package com.board.ime.sync

/**
 * 头像与同步引擎之间的最小接口。
 *
 * 同步只关心三件事：本地头像时间戳、上传用的 base64、下载后如何落盘。
 * 具体文件位置与裁剪由实现方决定，引擎不碰图片格式。
 */
interface ProfileSyncStore {
    fun localUpdatedAt(): Long

    /** 没有头像时返回 null */
    fun encodeAvatar(): String?

    /** 远端图片解码失败返回 false（本地头像保持不动） */
    fun applyAvatar(base64: String, updatedAt: Long): Boolean

    /** 云端明确表示「已删除头像」时使用 */
    fun clearAvatar()
}
