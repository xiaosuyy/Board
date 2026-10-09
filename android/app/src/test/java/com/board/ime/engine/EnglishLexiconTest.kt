package com.board.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 英文候选排序（对齐微信英文：常用词优先）回归测试。
 */
class EnglishLexiconTest {

    @Test
    fun `suggest orders by frequency rank not length`() {
        // 传入顺序 = 词频名次：help 最常用，其次 hello，再次 held
        EnglishLexicon.installUnchecked(listOf("help", "hello", "held", "helper", "her"))
        val got = EnglishLexicon.suggest("hel", limit = 10)
        assertEquals(listOf("help", "hello", "held", "helper"), got)
    }

    @Test
    fun `suggest excludes the exact prefix and returns empty for unknown`() {
        EnglishLexicon.installUnchecked(listOf("help", "hello", "held"))
        assertTrue(EnglishLexicon.suggest("he", 10).isNotEmpty())
        assertTrue(EnglishLexicon.suggest("zzz", 10).isEmpty())
        // 精确等于前缀的词不再作为补全返回（由 BasicEngine 的原样输入项负责）
        assertTrue(EnglishLexicon.suggest("help", 10).none { it == "help" })
    }

    @Test
    fun `install ignores tiny lists and keeps fallback`() {
        val before = EnglishLexicon.size
        EnglishLexicon.install(listOf("only", "three"))
        assertEquals(before, EnglishLexicon.size)
    }
}
