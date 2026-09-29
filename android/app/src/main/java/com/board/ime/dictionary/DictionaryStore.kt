package com.board.ime.dictionary

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 大型词库的**长期存储**层：`imported/<sha256>.dict.yaml.gz`
 *
 * ## 为什么压缩
 * 实测一份 60 万条的 Rime 词典文本 19,444,580 B，gzip 后 3,604,716 B（18.5%，省 15.1 MiB）。
 * 词典是纯文本、长期不动，是压缩收益最高的一块。
 *
 * ## 关键约束
 * - **输入时绝不实时解压**：Rime 输入读的是它自己部署出来的 `table.bin` / `prism.bin`，
 *   跟这里的 gz 无关。gz 只在「导入 / 同步下载 / 部署」这三条离线路径上被解开。
 * - **内容身份 = 解压后内容的 SHA-256**，不是文件名。所以
 *   `A.dict.yaml` 和 `B.dict.yaml` 内容相同 → 只会存在一个 `<hash>.dict.yaml.gz`。
 * - 全程**流式**：写 gz 时一边压缩一边算 hash，读 gz 时一边解压一边算 hash，
 *   不会把 20MB 文本整份读进内存。
 */
object DictionaryStore {

    private const val TAG = "BoardDictStore"

    /** 长期存储的规范文件名 */
    fun fileNameFor(hash: String): String = "$hash.dict.yaml.gz"

    fun fileFor(importedDir: File, hash: String): File = File(importedDir, fileNameFor(hash))

    fun isCompressedStore(file: File): Boolean = file.name.endsWith(".dict.yaml.gz")

    fun isCompressedStore(path: String): Boolean = path.endsWith(".dict.yaml.gz")

    /**
     * 把**未压缩的词典正文**压缩写成规范的 gz 文件。
     *
     * @param hash 期望的内容 SHA-256（解压后内容）；写入时会边写边校验，
     *             不一致就删掉半成品并返回 null —— **绝不留下内容对不上的文件**
     * @return 写好的 gz 文件；失败返回 null
     */
    fun writeCompressed(importedDir: File, hash: String, plain: File): File? {
        if (!plain.isFile) return null
        val target = fileFor(importedDir, hash)
        if (target.isFile) {
            // 去重：同 hash 已经存在 → 直接复用，不写第二份
            Log.i(TAG, "同内容已在库（hash=${hash.take(12)}），复用 ${target.name}")
            return target
        }
        val tmp = File(importedDir, target.name + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            importedDir.mkdirs()
            GZIPOutputStream(FileOutputStream(tmp), GZIP_BUFFER).use { gz ->
                plain.inputStream().use { input ->
                    val buffer = ByteArray(GZIP_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        gz.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(hash, ignoreCase = true)) {
                Log.w(TAG, "写入内容 hash 不符（期望 ${hash.take(12)} 实际 ${actual.take(12)}），丢弃")
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "已压缩存储 ${target.name}（${plain.length()} → ${target.length()} B）")
            target
        } catch (throwable: Throwable) {
            Log.e(TAG, "压缩写入失败：${throwable.message}", throwable)
            tmp.delete()
            null
        }
    }

    /** 直接把一段文本写成规范 gz（导入路径用，文本已经在内存里） */
    fun writeCompressedText(importedDir: File, hash: String, text: String): File? {
        val target = fileFor(importedDir, hash)
        if (target.isFile) return target
        val tmp = File(importedDir, target.name + ".tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        return try {
            importedDir.mkdirs()
            GZIPOutputStream(FileOutputStream(tmp), GZIP_BUFFER).use { raw ->
                BufferedWriter(OutputStreamWriter(raw, Charsets.UTF_8), GZIP_BUFFER).use { writer ->
                    var start = 0
                    while (start < text.length) {
                        val end = minOf(start + CHUNK_CHARS, text.length)
                        val chunk = text.substring(start, end)
                        writer.write(chunk)
                        digest.update(chunk.toByteArray(Charsets.UTF_8))
                        start = end
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(hash, ignoreCase = true)) {
                Log.w(TAG, "文本 hash 不符，丢弃 ${tmp.name}")
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        } catch (throwable: Throwable) {
            Log.e(TAG, "压缩写入文本失败：${throwable.message}", throwable)
            tmp.delete()
            null
        }
    }

    /** 流式计算**未压缩**文件的 SHA-256 */
    fun hashOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(GZIP_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 流式计算 gz 内**解压后**内容的 SHA-256 */
    fun hashOfCompressed(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            GZIPInputStream(BufferedInputStream(FileInputStream(file), GZIP_BUFFER), GZIP_BUFFER).use { gz ->
                val buffer = ByteArray(GZIP_BUFFER)
                while (true) {
                    val read = gz.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (throwable: Throwable) {
            Log.w(TAG, "读取压缩内容失败：${file.name} ${throwable.message}")
            null
        }
    }

    /**
     * 流式解压到目标文件（部署给 Rime 用时调用）。
     *
     * 边解压边算 hash，和期望值不一致就删掉目标文件 —— 保证 Rime 永远拿不到半份词典。
     */
    fun decompressTo(file: File, destination: File, expectedHash: String? = null): Boolean {
        return try {
            destination.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-256")
            GZIPInputStream(BufferedInputStream(FileInputStream(file), GZIP_BUFFER), GZIP_BUFFER).use { gz ->
                FileOutputStream(destination).use { out ->
                    val buffer = ByteArray(GZIP_BUFFER)
                    while (true) {
                        val read = gz.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                    out.flush()
                }
            }
            if (!expectedHash.isNullOrBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(expectedHash, ignoreCase = true)) {
                    Log.w(
                        TAG,
                        "解压内容 hash 不符（期望 ${expectedHash.take(12)} 实际 ${actual.take(12)}），删除临时文件",
                    )
                    destination.delete()
                    return false
                }
            }
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "解压失败：${throwable.message}", throwable)
            runCatching { destination.delete() }
            false
        }
    }

    /** 解压后的文本（只在必须拿到字符串时用，例如小词典/统计） */
    fun readText(file: File): String? = try {
        GZIPInputStream(BufferedInputStream(FileInputStream(file), GZIP_BUFFER), GZIP_BUFFER)
            .bufferedReader()
            .use { it.readText() }
    } catch (throwable: Throwable) {
        Log.w(TAG, "读取文本失败：${file.name} ${throwable.message}")
        null
    }

    /** gz 里的行数（用于统计，不落盘） */
    fun countLines(file: File): Int {
        var lines = 0
        try {
            GZIPInputStream(BufferedInputStream(FileInputStream(file), GZIP_BUFFER), GZIP_BUFFER)
                .bufferedReader()
                .use { reader ->
                    while (reader.readLine() != null) lines++
                }
        } catch (throwable: Throwable) {
            Log.w(TAG, "统计行数失败：${file.name} ${throwable.message}")
        }
        return lines
    }

    private const val GZIP_BUFFER = 64 * 1024
    private const val CHUNK_CHARS = 64 * 1024
}
