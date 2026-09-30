package com.board.ime.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语音文本组装规则的单测（离线、不联网）。
 *
 * 覆盖 Phase 2.5 要求的三类语义：
 * - A：partial 是「当前这句的累计文本」→ 必须覆盖；
 * - B：final 是「本段全文」→ 每段只追加一次，多段按顺序累加；
 * - C：final 若返回「整段会话全文」→ 覆盖而不是追加。
 */
class VoiceTranscriptAssemblerTest {

    @Test
    fun partialReplacesInsteadOfAppending() {
        val a = VoiceTranscriptAssembler()
        a.onPartial("今天天气")
        assertEquals("今天天气", a.displayText)
        a.onPartial("今天天气很好")
        assertEquals("今天天气很好", a.displayText)
        a.onPartial("今天天气很好，")
        assertEquals("今天天气很好，", a.displayText)
        // 绝不出现重复拼接
        assertFalse(a.displayText.contains("今天天气很好今天天气很好"))
    }

    @Test
    fun segmentsAccumulateOnceAndPartialContinuesAfterFinal() {
        val a = VoiceTranscriptAssembler()
        a.onPartial("今天天气很好")
        a.onSegmentFinal("今天天气很好", "ws_1")
        assertEquals("今天天气很好", a.displayText)

        // 服务端给完这一段 final 后，用户可以继续说下一段
        a.onPartial("我准备明天去上海")
        assertEquals("今天天气很好我准备明天去上海", a.displayText)

        a.onSegmentFinal("我准备明天去上海", "ws_2")
        assertEquals("今天天气很好我准备明天去上海", a.displayText)
        // 两段都只出现一次
        assertEquals(1, a.displayText.split("今天天气很好").size - 1)
        assertEquals(1, a.displayText.split("我准备明天去上海").size - 1)
    }

    @Test
    fun repeatedFinalForSameSegmentIsIgnored() {
        val a = VoiceTranscriptAssembler()
        a.onSegmentFinal("你好", "ws_1")
        a.onSegmentFinal("你好", "ws_1")
        a.onSegmentFinal("你好", "ws_1")
        assertEquals("你好", a.displayText)
    }

    @Test
    fun wholeSessionFinalOverwrites() {
        val a = VoiceTranscriptAssembler()
        a.onSegmentFinal("你好", "ws_1")
        // 服务端这一条是整会话全文（以已确定文本为前缀）→ 覆盖，不追加
        a.onSegmentFinal("你好今天", "ws_2")
        assertEquals("你好今天", a.displayText)
        assertEquals(1, a.settledText.split("你好").size - 1)
    }

    @Test
    fun continuousShortUtterancesNeverDuplicate() {
        // Phase 2.5 §13：快速说 你好 / 你好呀 / 今天 / 今天天气 / 今天天气很好
        val a = VoiceTranscriptAssembler()
        val growing = listOf("你好", "你好呀", "今天", "今天天气", "今天天气很好")
        growing.forEach { a.onPartial(it) }
        assertEquals("今天天气很好", a.displayText)
        assertFalse(a.displayText.contains("你好你好"))
        assertFalse(a.displayText.contains("今天今天"))
    }

    @Test
    fun settledTextIsWhatGetsCommittedAndResetClearsSession() {
        val a = VoiceTranscriptAssembler()
        a.onPartial("今天上海天气")
        a.onSegmentFinal("今天上海天气", "ws_1")
        assertEquals("今天上海天气", a.settledText)

        // 新一次语音会话不得带入旧文本
        a.reset()
        assertFalse(a.hasText)
        assertEquals("", a.displayText)
        a.onPartial("你好")
        assertEquals("你好", a.displayText)
    }

    @Test
    fun segmentKeyPrefersProtocolSnOverStartTime() {
        assertEquals("ws_2", VoiceTranscriptAssembler.segmentKeyOf("abc_ws_2", 1234L))
        assertEquals("t_1234", VoiceTranscriptAssembler.segmentKeyOf("abc", 1234L))
        assertEquals(null, VoiceTranscriptAssembler.segmentKeyOf(null, null))
    }

    @Test
    fun emptyFinalDoesNotPolluteText() {
        val a = VoiceTranscriptAssembler()
        a.onPartial("你好")
        a.onSegmentFinal("", "ws_1")
        assertTrue(a.displayText.isEmpty() || a.displayText == "你好")
    }
}
