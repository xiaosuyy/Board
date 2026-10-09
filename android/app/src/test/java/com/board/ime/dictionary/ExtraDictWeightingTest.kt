package com.board.ime.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入词库权重策略单元测试（P3 候选排序修复）。
 *
 * 目标：让导入的“额外词源”永远排在基础常用词之后，只在扩展词内部按音节数排序。
 */
class ExtraDictWeightingTest {

    @Test
    fun `initial quality is strongly negative`() {
        assertTrue(ExtraDictWeighting.INITIAL_QUALITY < 0)
    }

    @Test
    fun `weight is small positive and bounded`() {
        assertTrue(ExtraDictWeighting.weightForSyllables(1) in 1..16)
        assertTrue(ExtraDictWeighting.weightForSyllables(30) in 1..16)
    }

    @Test
    fun `shorter words rank higher within extras`() {
        assertTrue(
            ExtraDictWeighting.weightForSyllables(2) > ExtraDictWeighting.weightForSyllables(3),
        )
        assertTrue(
            ExtraDictWeighting.weightForSyllables(3) > ExtraDictWeighting.weightForSyllables(4),
        )
    }

    @Test
    fun `weight never drops below one`() {
        assertEquals(1, ExtraDictWeighting.weightForSyllables(100))
    }
}
