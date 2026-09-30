package com.board.ime.clipboard

import android.util.Log
import java.io.File

/**
 * 图片 PNG 的**应用私有**临时仓库：`<files>/clipboard-images/<hash>.png`
 *
 * 设计要点（与 Phase 1-E 需求一致）：
 * - 只存 PNG；文件名就是**本端编码 hash** → 同一张图天然只存一份；
 * - 这是「当前剪贴板生命周期数据」，**不是永久图片缓存**：清理由 [prune] 负责；
 * - 只写 App 私有目录（绝不写 DCIM / Pictures / Download / 公共外部存储）；
 * - 本类**只用 `java.io`**（不碰 android.*），因此清理逻辑可做 JVM 单测。
 */
class ClipboardImageStore(val rootDir: File) {

    fun fileFor(hash: String): File = File(rootDir, SyncClipboardMapper.dataNameForImage(hash))

    fun write(hash: String, bytes: ByteArray): File? {
        val file = fileFor(hash)
        return try {
            rootDir.mkdirs()
            file.writeBytes(bytes)
            file.setLastModified(System.currentTimeMillis())
            file
        } catch (throwable: Throwable) {
            Log.e(TAG, "写入图片失败（hash=${hash.take(8)}）：${throwable.message}")
            runCatching { file.delete() }
            null
        }
    }

    fun read(hash: String): ByteArray? =
        runCatching { fileFor(hash).takeIf { it.isFile }?.readBytes() }.getOrNull()

    fun exists(hash: String): Boolean = fileFor(hash).isFile

    fun delete(hash: String): Boolean = runCatching { fileFor(hash).delete() }.getOrDefault(false)

    /** 当前目录里的文件名（测试 / 证据用） */
    fun fileNames(): List<String> =
        runCatching {
            rootDir.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        }.getOrDefault(emptyList())

    /**
     * 清理**无引用**的图片文件。
     *
     * 保护规则（关键）：
     * - [referenced]：仍被剪贴板历史条目引用的 hash → **绝不删**；
     * - [currentClipboardHash]：**当前系统剪贴板 URI 正在引用**的图片 → **绝不删**
     *   （否则「剪贴板里还是它、Board 却删了文件」→ 用户稍后粘贴失败）；
     * - 只有「无引用 **且** 已超过 [ttlMs]」的文件才删（失败残留 / 中断残留 / 旧文件）。
     *
     * @return 实际删除的文件数
     */
    fun prune(
        referenced: Set<String>,
        currentClipboardHash: String?,
        now: Long,
        ttlMs: Long,
    ): Int {
        var removed = 0
        runCatching {
            rootDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val hash = file.name.removeSuffix(PNG_SUFFIX)
                if (hash == currentClipboardHash) return@forEach
                if (hash in referenced) return@forEach
                if (now - file.lastModified() < ttlMs) return@forEach
                if (file.delete()) removed++
            }
        }.onFailure { Log.w(TAG, "清理图片文件失败：${it.message}") }
        return removed
    }

    companion object {
        private const val TAG = "BoardImageStore"
        const val PNG_SUFFIX = ".png"
    }
}
