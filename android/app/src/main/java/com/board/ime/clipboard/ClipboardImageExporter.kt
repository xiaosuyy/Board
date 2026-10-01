package com.board.ime.clipboard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 把一张**Board 私有图片文件**导出到系统相册（`Pictures/Board/<hash>.png`）。
 *
 * 隐私要求（Phase 1-F）：
 * - **只在用户主动点击「下载」时才会被调用** —— 不点击就绝不写公共存储；
 * - 不产生任何额外的永久缓存 / 缩略图 / 第二份私有副本；
 * - 失败只记类别、返回 false，绝不崩溃。
 *
 * 实现：优先走 `MediaStore`（API 29+ 的 scoped storage，**无需任何权限**）。
 * API 26~28 写公共目录需要 `WRITE_EXTERNAL_STORAGE`，Board 不申请该权限，因此明确返回失败。
 */
object ClipboardImageExporter {

    private const val TAG = "BoardImageExport"
    private const val RELATIVE_DIR = "Pictures/Board"

    fun saveToGallery(context: Context, source: File, hash: String): Boolean {
        if (!source.isFile) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, source, nameFor(hash))
            } else {
                Log.w(TAG, "系统版本 < 29 且未申请存储权限，无法保存到相册")
                false
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "保存到相册失败：${throwable.javaClass.simpleName}")
            false
        }
    }

    private fun nameFor(hash: String): String = (hash.ifEmpty { "board" }) + ".png"

    private fun saveViaMediaStore(context: Context, source: File, name: String): Boolean {
        val resolver = context.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri: Uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, pending)
            ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return false
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            true
        } catch (throwable: Throwable) {
            // 失败时把半成品条目删掉，不留下损坏的相册记录
            runCatching { resolver.delete(uri, null, null) }
            Log.w(TAG, "写入相册失败：${throwable.javaClass.simpleName}")
            false
        }
    }
}
