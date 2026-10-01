package com.board.ime.clipboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * 预览 / 缩略图专用的**降采样**解码（Phase 1-F）。
 *
 * 采样按 **contain（等比缩进 maxW×maxH 框）** 计算 —— 因此**超长图也不会被整份解码**。
 * 例：1000×5000 的图在 1097×610 的预览框里只需解出 ~125×625，而不是 1000×5000。
 *
 * 只读既有文件、**不写任何缓存**；返回的 Bitmap 由调用方负责回收。
 * （放在独立文件里，避免改动 Phase 1-E 已冻结的 `ClipboardImageCodec`。）
 */
object ClipboardImagePreviewDecoder {

    fun decodeWithin(path: String?, maxW: Int, maxH: Int): Bitmap? {
        if (path.isNullOrBlank() || maxW <= 0 || maxH <= 0) return null
        if (!File(path).isFile) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxW, maxH)
            BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } catch (throwable: Throwable) {
            null
        }
    }

    /**
     * **纯计算**（可 JVM 单测）：返回最大的 2 的幂采样，使解码结果仍不小于
     * 「等比缩进 maxW×maxH 之后」的目标尺寸。
     *
     * 这样解码出来的位图尺寸与最终显示尺寸同量级 —— 既不会因为太小而糊，
     * 也不会因为超长图而把整张原图读进内存。
     */
    fun sampleSizeFor(width: Int, height: Int, maxW: Int, maxH: Int): Int {
        if (width <= 0 || height <= 0 || maxW <= 0 || maxH <= 0) return 1
        val scale = minOf(maxW.toDouble() / width, maxH.toDouble() / height)
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) {
            sample *= 2
        }
        return sample
    }
}
