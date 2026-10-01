package com.board.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1-F 预览解码采样**纯逻辑**单测。
 *
 * 重点：**超长图**不能退化成整份解码（否则 1000×20000 会占几十 MB 甚至 OOM）。
 */
class ClipboardImagePreviewDecoderTest {

    private fun decoded(width: Int, height: Int, sample: Int) =
        (width / sample).coerceAtLeast(1) to (height / sample).coerceAtLeast(1)

    // 普通横图：宽受限
    @Test
    fun landscapeUsesWidthBound() {
        val sample = ClipboardImagePreviewDecoder.sampleSizeFor(4000, 3000, 1097, 610)
        val (w, h) = decoded(4000, 3000, sample)
        assertTrue("解码尺寸应接近显示尺寸：$w x $h", w in 813..1700 && h in 610..1300)
    }

    // 普通竖图：高受限
    @Test
    fun portraitUsesHeightBound() {
        val sample = ClipboardImagePreviewDecoder.sampleSizeFor(1500, 3000, 1097, 610)
        val (w, h) = decoded(1500, 3000, sample)
        assertTrue("解码尺寸应接近显示尺寸：$w x $h", h in 610..1300 && w in 300..700)
    }

    // 明显长图 1:5 —— 必须被采样，不能整份解码
    @Test
    fun tallImageIsDownsampled() {
        val sample = ClipboardImagePreviewDecoder.sampleSizeFor(1000, 5000, 1097, 610)
        val (w, h) = decoded(1000, 5000, sample)
        assertTrue("长图必须降采样（sample=$sample）", sample >= 4)
        assertTrue("解码结果不能是原图尺寸：$w x $h", w < 1000 || h < 5000)
        // 仍不小于 contain 后的目标尺寸（约 122 x 610）
        assertTrue("宽度不能小于目标：$w", w >= 122)
        assertTrue("高度不能小于目标：$h", h >= 610)
    }

    // 超长图 1:20 —— 这里是最容易 OOM 的情况
    @Test
    fun veryLongImageIsBoundedHard() {
        val sample = ClipboardImagePreviewDecoder.sampleSizeFor(1000, 20000, 1097, 610)
        val (w, h) = decoded(1000, 20000, sample)
        val pixels = w.toLong() * h
        assertTrue("超长图解码后必须远小于原图（$w x $h）", pixels < 1000L * 20000L / 4)
        assertTrue("高度不能小于目标：$h", h >= 610)
    }

    // 小图不放大采样
    @Test
    fun tinyImageKeepsSampleOne() {
        assertEquals(1, ClipboardImagePreviewDecoder.sampleSizeFor(64, 64, 1097, 610))
    }

    // 非法输入安全
    @Test
    fun invalidInputsAreSafe() {
        assertEquals(1, ClipboardImagePreviewDecoder.sampleSizeFor(0, 100, 10, 10))
        assertEquals(1, ClipboardImagePreviewDecoder.sampleSizeFor(100, 100, 0, 10))
    }
}
