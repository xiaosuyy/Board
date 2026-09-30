package com.board.ime.clipboard

import android.util.Log
import java.io.File

/**
 * 超长剪贴板内容的文件仓库。
 *
 * 目录：`<app files>/clipboard/<前 2 位>/<sha256>.txt`
 * （按 hash 分桶，避免所有文件挤在一个目录里）
 *
 * 设计要点：
 * - 文件名就是内容哈希 → **同一段内容天然只存一份**
 * - 写入是**分块**的：不把整段内容再复制成一份 byte[]（10MB 文本会瞬间多占 30MB）
 * - 删记录时同步删文件；文件缺失时读取返回 null，由上层给出「文件缺失」而不是崩
 */
class ClipboardContentStore(val rootDir: File) {

    /** 一条内容对应的文件（不保证存在） */
    fun fileFor(hash: String): File {
        val bucket = hash.take(2).ifBlank { "00" }
        return File(File(rootDir, bucket), "$hash.txt")
    }

    /**
     * 写入内容（分块，避免整段复制）。
     * @return 文件路径；写入失败返回 null（调用方应退回 INLINE 或放弃）
     */
    fun write(hash: String, content: String): File? {
        val file = fileFor(hash)
        return try {
            file.parentFile?.mkdirs()
            file.bufferedWriter().use { writer ->
                var start = 0
                while (start < content.length) {
                    val end = minOf(start + CHUNK_CHARS, content.length)
                    writer.write(content, start, end - start)
                    start = end
                }
            }
            file
        } catch (throwable: Throwable) {
            Log.e(TAG, "写入剪贴板文件失败（hash=${hash.take(8)}）：${throwable.message}")
            runCatching { file.delete() }
            null
        }
    }

    /** 读回完整内容；文件不存在 / 读失败返回 null */
    fun read(path: String?): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching { file.readText() }
            .onFailure { Log.w(TAG, "读取剪贴板文件失败：${it.message}") }
            .getOrNull()
    }

    fun readByHash(hash: String): String? = read(fileFor(hash).absolutePath)

    /**
     * 只读文件开头一小段（用于给文件型条目补搜索索引）。
     *
     * **不会**把整份内容读进内存 —— 这正是「轻量索引」的意义。
     */
    fun readHead(path: String?, chars: Int): String? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            file.bufferedReader().use { reader ->
                val buffer = CharArray(chars)
                var filled = 0
                while (filled < chars) {
                    val read = reader.read(buffer, filled, chars - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled <= 0) "" else String(buffer, 0, filled)
            }
        }.onFailure { Log.w(TAG, "读取剪贴板文件头部失败：${it.message}") }.getOrNull()
    }

    fun exists(path: String?): Boolean = !path.isNullOrBlank() && File(path).isFile

    fun sizeOf(path: String?): Long = if (path.isNullOrBlank()) 0L else File(path).length()

    /** 删除某个 hash 对应的文件（不存在的文件不算失败） */
    fun deleteByHash(hash: String): Boolean =
        runCatching { fileFor(hash).delete() || !fileFor(hash).exists() }.getOrDefault(false)

    fun delete(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return runCatching { File(path).delete() }.getOrDefault(false)
    }

    /**
     * 回收没有任何记录引用的文件（历史遗留 / 删除失败留下的孤儿）。
     * @return 删除的文件数
     */
    fun collectOrphans(referenced: Set<String>): Int {
        var removed = 0
        runCatching {
            rootDir.listFiles()?.forEach { bucket ->
                if (!bucket.isDirectory) return@forEach
                bucket.listFiles()?.forEach { file ->
                    if (file.name !in referenced) {
                        if (file.delete()) removed++
                    }
                }
            }
        }.onFailure { Log.w(TAG, "回收孤儿剪贴板文件失败：${it.message}") }
        return removed
    }

    private companion object {
        const val TAG = "BoardClipboardStore"

        /** 分块写入的字符数（与读取块保持一致） */
        const val CHUNK_CHARS = 64 * 1024
    }
}
