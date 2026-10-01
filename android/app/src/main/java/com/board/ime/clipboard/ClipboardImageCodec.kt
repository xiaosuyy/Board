package com.board.ime.clipboard

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 一张已按**本端规则**编码好的图片（PNG 字节 + 本端 hash） */
data class ProcessedImage(val bytes: ByteArray, val hash: String)

/**
 * 剪贴板图片的读取 / 解码 / **统一 PNG 编码** / 大小与 hash 计算。
 *
 * 分层（与 Phase 1-E 需求一致，也便于测试）：
 * - **纯逻辑**：[isImageMime] / [isPng] / [withinLimit] / [imageMimeOf] → 可做 JVM 单测；
 * - **Android 部分**：[readRaw] / [toPng] / [processRaw] / [processUri]（`ContentResolver` / `Bitmap`）
 *   → 只能真机 / 仪器化验证。
 *
 * 格式策略：统一 PNG、**保留透明通道**、不做 JPEG/WebP 压缩、不改画质、不引入第三方图片库。
 * 大小策略：**原始输入与最终 PNG 都要 ≤ [MAX_IMAGE_BYTES]**。
 */
object ClipboardImageCodec {

    /** 两端统一上限（与 `SyncClipboardMapper.MAX_IMAGE_BYTES` 同值） */
    const val MAX_IMAGE_BYTES: Int = SyncClipboardMapper.MAX_IMAGE_BYTES

    private const val TAG = "BoardImageCodec"
    private const val READ_CHUNK = 64 * 1024

    /** MIME 是否图片（ClipboardDescription / Intent type 都走这里） */
    fun isImageMime(mime: String?): Boolean =
        mime != null && mime.startsWith("image/", ignoreCase = true)

    /** 从一组 MIME 里挑出图片 MIME；没有返回 null（纯逻辑，可单测） */
    fun imageMimeOf(mimeTypes: Array<String>?): String? =
        mimeTypes?.firstOrNull { isImageMime(it) }

    /** 大小是否在上限内（原始输入与最终 PNG 都要过这一关） */
    fun withinLimit(size: Long): Boolean = size > 0 && size <= MAX_IMAGE_BYTES.toLong()

    /** PNG 魔数：89 50 4E 47 0D 0A 1A 0A（纯逻辑，可单测） */
    fun isPng(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 8) return false
        for (i in PNG_MAGIC.indices) {
            if ((bytes[i].toInt() and 0xFF) != PNG_MAGIC[i]) return false
        }
        return true
    }

    /**
     * 读原始字节。**有界**：一旦超过 10 MiB 立即停止并返回 null
     * （原始数据 >10 MiB 直接拒绝，不把它整份读进内存）。
     */
    fun readRaw(resolver: ContentResolver, uri: Uri): ByteArray? = try {
        resolver.openInputStream(uri)?.use { input -> readBounded(input) }
    } catch (throwable: Throwable) {
        // 只记类别，绝不记图片内容
        Log.w(TAG, "读取图片失败：${throwable.javaClass.simpleName}")
        null
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(READ_CHUNK)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            if (output.size() > MAX_IMAGE_BYTES) return null
        }
        val bytes = output.toByteArray()
        return if (withinLimit(bytes.size.toLong())) bytes else null
    }

    /** Bitmap → PNG 字节（保留透明通道；不降质量） */
    fun encodePng(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        return output.toByteArray()
    }

    /** 任意图片字节（JPEG / WebP / PNG）→ PNG 字节；解码失败返回 null */
    fun toPng(raw: ByteArray): ByteArray? {
        if (raw.isEmpty()) return null
        val bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        return try {
            encodePng(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 原始图片字节 → 本端规范 PNG（解码 → PNG 编码 → 大小检查 → hash）。
     * 解码失败 / 原始或 PNG 超限 → null（**不写任何残留文件**）。
     */
    fun processRaw(raw: ByteArray?): ProcessedImage? {
        if (raw == null || raw.isEmpty()) return null
        if (!withinLimit(raw.size.toLong())) return null
        val png = toPng(raw) ?: return null
        if (!withinLimit(png.size.toLong())) return null
        return ProcessedImage(png, ClipboardHasher.hashBytes(png))
    }

    /** 剪贴板 / 分享 URI → 本端规范 PNG */
    fun processUri(resolver: ContentResolver, uri: Uri): ProcessedImage? =
        processRaw(readRaw(resolver, uri))

    /** 已经是本端规范 PNG 字节时直接确认（远端下载回写用） */
    fun confirmPng(bytes: ByteArray?): ProcessedImage? {
        if (bytes == null || bytes.isEmpty()) return null
        if (!withinLimit(bytes.size.toLong())) return null
        return ProcessedImage(bytes, ClipboardHasher.hashBytes(bytes))
    }

    /** PNG 魔数（供 [isPng] 使用） */
    private val PNG_MAGIC = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
}
