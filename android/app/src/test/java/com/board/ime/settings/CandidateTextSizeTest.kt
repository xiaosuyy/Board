package com.board.ime.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选字号档位（P3 Stage 2 新增英文释义字号）单元测试。
 *
 * 同时锁定 P2 已封版的 16/19/22/25sp 中文字号不能被改动。
 */
class CandidateTextSizeTest {

    @Test
    fun `p2 chinese sizes are unchanged`() {
        assertEquals(16f, CandidateTextSize.SMALL.candidateSp)
        assertEquals(19f, CandidateTextSize.STANDARD.candidateSp)
        assertEquals(22f, CandidateTextSize.LARGE.candidateSp)
        assertEquals(25f, CandidateTextSize.XLARGE.candidateSp)
    }

    @Test
    fun `p2 pinyin sizes are unchanged`() {
        assertEquals(14f, CandidateTextSize.SMALL.pinyinSp)
        assertEquals(16f, CandidateTextSize.STANDARD.pinyinSp)
        assertEquals(17f, CandidateTextSize.LARGE.pinyinSp)
        assertEquals(18f, CandidateTextSize.XLARGE.pinyinSp)
    }

    @Test
    fun `english gloss sizes follow the design tiers`() {
        assertEquals(10f, CandidateTextSize.SMALL.englishGlossSp)
        assertEquals(11f, CandidateTextSize.STANDARD.englishGlossSp)
        assertEquals(12f, CandidateTextSize.LARGE.englishGlossSp)
        assertEquals(13f, CandidateTextSize.XLARGE.englishGlossSp)
    }

    @Test
    fun `english gloss is always clearly smaller than chinese`() {
        CandidateTextSize.entries.forEach { size ->
            assertTrue(
                "${size.name}: 英文 ${size.englishGlossSp} 必须小于中文 ${size.candidateSp}",
                size.englishGlossSp < size.candidateSp,
            )
        }
    }

    @Test
    fun `english gloss has a readable lower bound`() {
        CandidateTextSize.entries.forEach { size ->
            assertTrue(size.englishGlossSp >= 10f)
        }
    }
}
