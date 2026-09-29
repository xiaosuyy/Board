package com.board.ime.clipboard

import java.security.MessageDigest

/**
 * 内容哈希。用于剪贴板去重：
 * 同一段文本反复复制时只保留一条记录，而不是无限堆历史。
 *
 * 用 SHA-256（本地计算，不联网）。哈希值同时也能直接喂给 SyncClipboard 的 `hash` 字段。
 */
object ClipboardHasher {

    fun hash(content: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.digest(content.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    } catch (throwable: Throwable) {
        // 理论上不会失败；退化成内容本身，至少保证同内容同键
        content
    }
}
