package com.board.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 候选上屏的尾随空格规则：
 * 英文选词补空格（Gboard 习惯），数字 / 纯符号不补。
 */
class BasicEngineTest {

    @Test
    fun `number candidate commits without trailing space`() {
        val engine = BasicEngine(InputMode.NUMBER)
        "123".forEach { engine.handleKey(EngineKey.Letter(it)) }
        val result = engine.pickCandidate(0) as EngineResult.Commit
        assertEquals("123", result.text)
    }

    @Test
    fun `english candidate keeps trailing space`() {
        val engine = BasicEngine(InputMode.ENGLISH)
        "hel".forEach { engine.handleKey(EngineKey.Letter(it)) }
        val result = engine.pickCandidate(0) as EngineResult.Commit
        assertEquals("hel ", result.text)
    }
}
