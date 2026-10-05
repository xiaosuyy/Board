package com.board.ime.ui.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选格宽度 / 第二行显示逻辑（P3 Stage 2）的纯计算单元测试。
 *
 * 说明：候选栏是 Android View，普通 JVM 单测无法真正布局 / 绘制，
 * 因此这里覆盖的是 [CandidateItemMetrics] 这段**被 View 真实调用**的计算逻辑；
 * 真机像素级表现（不裁切 / 不换行 / 横向滚动 / 点击）由真机验证。
 */
class CandidateItemMetricsTest {

    // 3 / 9 / 15：无释义 / 空白释义都不显示第二行（回到单行，不显示空白占位）
    @Test
    fun `null gloss shows single line`() {
        assertFalse(CandidateItemMetrics.showsEnglishLine(null))
    }

    @Test
    fun `blank gloss shows single line`() {
        assertFalse(CandidateItemMetrics.showsEnglishLine(""))
        assertFalse(CandidateItemMetrics.showsEnglishLine("   "))
        assertFalse(CandidateItemMetrics.showsEnglishLine("\t"))
    }

    @Test
    fun `non blank gloss shows second line`() {
        assertTrue(CandidateItemMetrics.showsEnglishLine("apple"))
        assertTrue(CandidateItemMetrics.showsEnglishLine("People's Republic of China"))
    }

    // 4：中文比英文长 → 宽度由中文决定
    @Test
    fun `chinese longer than english width follows chinese`() {
        assertEquals(200, CandidateItemMetrics.contentWidth(chineseWidth = 200, englishWidth = 80))
        assertEquals(
            200 + 12 * 2,
            CandidateItemMetrics.requiredWidth(200, 80, horizontalPadding = 12, minWidth = 0),
        )
    }

    // 5：英文比中文长 → 宽度由英文决定
    @Test
    fun `english longer than chinese width follows english`() {
        assertEquals(300, CandidateItemMetrics.contentWidth(chineseWidth = 120, englishWidth = 300))
        assertEquals(
            300 + 14 * 2,
            CandidateItemMetrics.requiredWidth(120, 300, horizontalPadding = 14, minWidth = 0),
        )
    }

    @Test
    fun `equal widths keep that width`() {
        assertEquals(150, CandidateItemMetrics.contentWidth(150, 150))
    }

    // 单行（英文宽传 0）时只按中文算
    @Test
    fun `single line uses chinese width only`() {
        assertEquals(
            100 + 10 * 2,
            CandidateItemMetrics.requiredWidth(100, 0, horizontalPadding = 10, minWidth = 0),
        )
    }

    // 候选格不能小于最小宽度
    @Test
    fun `min width is respected`() {
        assertEquals(
            160,
            CandidateItemMetrics.requiredWidth(10, 0, horizontalPadding = 4, minWidth = 160),
        )
    }

    // 具体例子：北京 / Beijing（英文更长）
    @Test
    fun `beijing width driven by its english gloss`() {
        assertEquals(
            CandidateItemMetrics.requiredWidth(90, 240, 14, 40),
            CandidateItemMetrics.requiredWidth(chineseWidth = 90, englishWidth = 240, horizontalPadding = 14, minWidth = 40),
        )
        assertTrue(
            CandidateItemMetrics.requiredWidth(90, 240, 14, 40) >
                CandidateItemMetrics.requiredWidth(90, 0, 14, 40),
        )
    }

    // 具体例子：苹果 / apple（中文更长）
    @Test
    fun `apple width driven by chinese`() {
        assertTrue(
            CandidateItemMetrics.contentWidth(chineseWidth = 120, englishWidth = 90) == 120,
        )
    }

    @Test
    fun `negative or zero values are clamped`() {
        assertEquals(0, CandidateItemMetrics.contentWidth(-5, -5))
        assertEquals(0, CandidateItemMetrics.requiredWidth(-1, -1, horizontalPadding = -3, minWidth = -9))
    }
}
