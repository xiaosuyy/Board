package com.board.ime.clipboard

import android.util.Log
import java.security.MessageDigest

/**
 * 内容哈希。用于剪贴板去重 / 词库文件校验。
 *
 * **流式计算**：不把整段内容再复制一份到 byte[]。
 * 超长剪贴板（MB 级）如果 `content.toByteArray()` 会瞬间再占一份内存，
 * 在真机上很容易触发 OOM，所以这里按块 update。
 */
object ClipboardHasher {

    private const val TAG = "BoardClipboard"
    private const val CHUNK_CHARS = 64 * 1024

    fun hash(content: String): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        updateDigest(digest, content)
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    } catch (throwable: Throwable) {
        Log.w(TAG, "计算哈希失败，退化成内容本身：${throwable.message}")
        content
    }

    /** 分块喂给 digest（UTF-8 边界安全：按字符切，再各自编码） */
    private fun updateDigest(digest: MessageDigest, content: String) {
        if (content.length <= CHUNK_CHARS) {
            digest.update(content.toByteArray(Charsets.UTF_8))
            return
        }
        var start = 0
        val builder = StringBuilder(CHUNK_CHARS)
        while (start < content.length) {
            val end = minOf(start + CHUNK_CHARS, content.length)
            builder.setLength(0)
            builder.append(content, start, end)
            digest.update(builder.toString().toByteArray(Charsets.UTF_8))
            start = end
        }
    }

    /**
     * 任意字节的 SHA-256（小写十六进制）。
     *
     * 图片用它：hash = SHA-256(**PNG 字节**)，与 Windows 的 `ClipboardHasher.HashBytes` 形状一致。
     * 注意：两端对同一图片编出的 PNG 字节可能不同，因此**不做跨平台图片 hash 去重**
     * —— 本端只用**自己实际生成的 PNG 字节**算 hash。
     */
    fun hashBytes(bytes: ByteArray): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    } catch (throwable: Throwable) {
        Log.w(TAG, "计算图片哈希失败：${throwable.message}")
        ""
    }

    /** 与 [hash] 等价的判定，但用于「两个内容是否相同」的快速比较 */
    fun sameContent(a: String, b: String): Boolean = a.length == b.length && a == b

    /**
     * UTF-8 编码后的字节数（**不分配新字符串**）。
     *
     * 判断「这段文本要不要走文件传输」时需要字节数，但 MB 级内容再
     * `toByteArray()` 就是白白多占一份内存，所以直接按码点估算。
     */
    fun utf8Length(content: String): Long {
        var index = 0
        var bytes = 0L
        while (index < content.length) {
            val code = content[index].code
            when {
                code < 0x80 -> bytes += 1
                code < 0x800 -> bytes += 2
                Character.isHighSurrogate(content[index]) && index + 1 < content.length -> {
                    bytes += 4
                    index++
                }

                else -> bytes += 3
            }
            index++
        }
        return bytes
    }

    /**
     * 文件内容的 SHA-256（**流式**）。
     *
     * 大型词库（20~30MB）绝不能用 `readBytes()` 整份读进内存再算，
     * 这里只保留 64KB 缓冲。
     */
    fun hashFile(file: java.io.File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_CHARS)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    } catch (throwable: Throwable) {
        Log.w(TAG, "计算文件哈希失败：${throwable.message}")
        ""
    }
}
