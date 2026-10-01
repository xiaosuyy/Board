package com.board.ime.cloudlibrary

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * 扩展名 → MIME（**纯函数，无 Android 依赖**，可 JVM 单测）。
 *
 * MIME 优先由调用方提供；这里只做扩展名兜底。
 */
object LibraryMime {

    fun forName(name: String?, isImageHint: Boolean = false): String {
        return when (CloudLibrary.normalizeExtension(name?.substringAfterLast('.', ""))) {
            ".png" -> "image/png"
            ".jpg", ".jpeg" -> "image/jpeg"
            ".gif" -> "image/gif"
            ".webp" -> "image/webp"
            ".bmp" -> "image/bmp"
            ".pdf" -> "application/pdf"
            ".zip" -> "application/zip"
            ".txt" -> "text/plain"
            ".json" -> "application/json"
            else -> if (isImageHint) "image/*" else "application/octet-stream"
        }
    }
}

/**
 * SAF 单文件读取（`ContentResolver`）。
 *
 * - **单个 Uri**，文件夹/不可读 ⇒ null
 * - **不复制到临时文件**：全程只读进内存
 * - **有界读取**：一旦累计超过 [limit] **立即停止**并返回 null
 * - 全程在 [Dispatchers.IO]
 *
 * 返回的是**原始 bytes**（不改写）；hash/扩展名由上层计算。
 */
class AndroidLibraryUploadSource(
    private val resolver: ContentResolver,
) : LibraryUploadSource {

    override suspend fun read(uri: String, limit: Long): ByteArray? = withContext(Dispatchers.IO) {
        if (uri.isBlank()) return@withContext null
        val parsed = runCatching { Uri.parse(uri) }.getOrNull() ?: return@withContext null

        // 已知大小就直接挡住（避免白读一遍大文件）
        val known = querySize(parsed)
        if (known != null && (known <= 0L || known > limit)) return@withContext null

        val input = runCatching { resolver.openInputStream(parsed) }.getOrNull() ?: return@withContext null
        try {
            val initial = if (known != null && known in 1..limit) known.toInt() else 64 * 1024
            val out = ByteArrayOutputStream(initial)
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read <= 0) break
                total += read
                if (total > limit) return@withContext null   // 超限立即停止
                out.write(chunk, 0, read)
            }
            if (total <= 0L) null else out.toByteArray()
        } catch (throwable: Throwable) {
            null
        } finally {
            runCatching { input.close() }
        }
    }

    /** `OpenableColumns.SIZE`（拿不到返回 null，不代表失败） */
    private fun querySize(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else {
                null
            }
        }
    }.getOrNull()
}

/**
 * 下载落盘（MediaStore）。
 *
 * - 图片 → `Pictures/Board`（相册可见）
 * - 文件 → `Downloads/Board`
 * - **API 29+** 走 MediaStore（不申请不必要的存储权限；低版本返回 null → 上层报「下载失败」）
 * - 写入期间 `IS_PENDING=1`，**成功才 finalize（`IS_PENDING=0`）**
 * - 任何失败都**删除半成品 entry**
 * - 全程在 [Dispatchers.IO]；**不写系统剪贴板、不进 Clipboard History**
 */
class AndroidLibraryFileSink(
    private val context: Context,
) : LibraryFileSink {

    override suspend fun saveImage(bytes: ByteArray, name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return save(
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativePath = Environment.DIRECTORY_PICTURES + "/Board",
            mime = LibraryMime.forName(name, isImageHint = true),
            bytes = bytes,
            name = CloudLibrary.sanitizeFileName(name),
        )
    }

    override suspend fun saveFile(bytes: ByteArray, name: String): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return save(
            collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            relativePath = Environment.DIRECTORY_DOWNLOADS + "/Board",
            mime = LibraryMime.forName(name),
            bytes = bytes,
            name = CloudLibrary.sanitizeFileName(name),
        )
    }

    private suspend fun save(
        collection: Uri,
        relativePath: String,
        mime: String,
        bytes: ByteArray,
        name: String,
    ): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return@withContext null

        try {
            val stream = resolver.openOutputStream(uri) ?: throw IllegalStateException("openOutputStream failed")
            stream.use { it.write(bytes) }

            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri.toString()
        } catch (throwable: Throwable) {
            // 失败：删除半成品（绝不留下 0 字节 / 半截文件）
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
