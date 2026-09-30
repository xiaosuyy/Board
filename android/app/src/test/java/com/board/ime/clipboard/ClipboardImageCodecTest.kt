package com.board.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1-E 图片**纯逻辑**单测（不触碰 Bitmap / ContentResolver / Clipboard，
 * 因此无需 Robolectric，也不新增任何依赖）：
 * MIME 判定、10 MiB 边界、PNG 魔数、两端统一常量。
 *
 * 依赖 android.graphics / ContentResolver 的部分（真正的解码 / 编码 / 读 URI）
 * 只能真机验证，见最终报告的 NOT VERIFIED 项。
 */
class ClipboardImageCodecTest {

    // ---- MIME ----

    @Test
    fun imageMimeDetectsCommonTypes() {
        assertTrue(ClipboardImageCodec.isImageMime("image/png"))
        assertTrue(ClipboardImageCodec.isImageMime("image/jpeg"))
        assertTrue(ClipboardImageCodec.isImageMime("image/webp"))
        assertTrue("MIME 判定必须大小写不敏感", ClipboardImageCodec.isImageMime("IMAGE/PNG"))
    }

    @Test
    fun nonImageMimeRejected() {
        assertFalse(ClipboardImageCodec.isImageMime("text/plain"))
        assertFalse(ClipboardImageCodec.isImageMime("application/pdf"))
        assertFalse(ClipboardImageCodec.isImageMime(""))
        assertFalse(ClipboardImageCodec.isImageMime(null))
    }

    @Test
    fun imageMimeOfPicksImageFromList() {
        assertEquals("image/webp", ClipboardImageCodec.imageMimeOf(arrayOf("text/plain", "image/webp")))
        assertNull(ClipboardImageCodec.imageMimeOf(arrayOf("text/plain")))
        assertNull(ClipboardImageCodec.imageMimeOf(null))
        assertNull(ClipboardImageCodec.imageMimeOf(emptyArray()))
    }

    // ---- 10 MiB 边界（原始输入与最终 PNG 共用同一判定）----

    @Test
    fun unifiedLimitIsTenMiB() {
        assertEquals(10 * 1024 * 1024, SyncClipboardMapper.MAX_IMAGE_BYTES)
        assertEquals(SyncClipboardMapper.MAX_IMAGE_BYTES, ClipboardImageCodec.MAX_IMAGE_BYTES)
    }

    @Test
    fun withinLimitBoundaries() {
        val max = SyncClipboardMapper.MAX_IMAGE_BYTES.toLong()
        assertTrue("10 MiB - 1 应通过", ClipboardImageCodec.withinLimit(max - 1))
        assertTrue("恰为 10 MiB 应通过", ClipboardImageCodec.withinLimit(max))
        assertFalse("10 MiB + 1 应拒绝", ClipboardImageCodec.withinLimit(max + 1))
        assertFalse("0 应拒绝", ClipboardImageCodec.withinLimit(0))
        assertFalse("负数应拒绝", ClipboardImageCodec.withinLimit(-1))
    }

    // ---- PNG 魔数（纯逻辑）----

    @Test
    fun pngMagicDetection() {
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0,
        )
        assertTrue(ClipboardImageCodec.isPng(png))
        // JPEG 头必须不认
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0)
        assertFalse(ClipboardImageCodec.isPng(jpeg))
        assertFalse("太短应拒绝", ClipboardImageCodec.isPng(byteArrayOf(0x89.toByte(), 0x50)))
        assertFalse(ClipboardImageCodec.isPng(null))
    }

    // ---- dataName ----

    @Test
    fun imageDataNameUsesPngSuffix() {
        assertEquals("abc.png", SyncClipboardMapper.dataNameForImage("abc"))
        assertEquals("board.png", SyncClipboardMapper.dataNameForImage(""))
        // 文本仍是 .txt（回归）
        assertEquals("abc.txt", SyncClipboardMapper.dataNameFor("abc"))
    }
}
