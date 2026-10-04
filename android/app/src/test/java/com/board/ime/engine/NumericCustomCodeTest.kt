package com.board.ime.engine

import com.board.ime.database.entity.DictionaryEntry
import com.board.ime.dictionary.DictionaryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯数字自定义编码（方案 D）单元测试。
 *
 * 覆盖：
 * - 前缀不命中、全等命中、同 code 多词；
 * - 候选映射与点击提交（原有数字候选行为不变、自定义候选不补空格）；
 * - 刷新后候选消失；
 * - 非数字 / 空 code / 禁用词条不进入 numeric map；
 * - 不影响中文 / 符号模式（`@` 流程走既有路径）。
 *
 * 说明：这些用例只走 NUMBER 路径，不会触及 librime（单测中 RimeRuntime 无会话，
 * withSession 直接返回 fallback）。
 */
class NumericCustomCodeTest {

    private fun engine(
        vararg phrases: Pair<String, List<String>>,
        mode: InputMode = InputMode.NUMBER,
    ): RimeEngine {
        val engine = RimeEngine(BasicEngine())
        engine.mode = mode
        engine.setNumericCustomPhrases(linkedMapOf(*phrases))
        return engine
    }

    private fun RimeEngine.type(text: String): RimeEngine {
        text.forEach { handleKey(EngineKey.Letter(it)) }
        return this
    }

    private fun RimeEngine.texts(): List<String> = candidates().map { it.text }

    // 1
    @Test
    fun `prefix never surfaces numeric custom candidate`() {
        val engine = engine("15823" to listOf("号码A"))
        engine.type("1582")
        assertEquals(listOf("1582"), engine.texts())
    }

    // 2
    @Test
    fun `exact code 15823 appends custom candidate after raw input`() {
        val engine = engine("15823" to listOf("号码A"))
        engine.type("15823")
        assertEquals(listOf("15823", "号码A"), engine.texts())
    }

    // 3
    @Test
    fun `exact code 17717 appends custom candidate after raw input`() {
        val engine = engine("17717" to listOf("号码B"))
        engine.type("17717")
        assertEquals(listOf("17717", "号码B"), engine.texts())
    }

    // 4
    @Test
    fun `plain digits 123 keep original behaviour`() {
        val engine = engine("15823" to listOf("号码A"), "17717" to listOf("号码B"))
        engine.type("123")
        assertEquals(listOf("123"), engine.texts())
    }

    // 5
    @Test
    fun `plain digits 17678 keep original behaviour`() {
        val engine = engine("15823" to listOf("号码A"), "17717" to listOf("号码B"))
        engine.type("17678")
        assertEquals(listOf("17678"), engine.texts())
    }

    // 6 / 7 / 8
    @Test
    fun `same code keeps all words and each is selectable`() {
        val engine = engine("15823" to listOf("号码A", "号码B"))
        engine.type("15823")
        assertEquals(listOf("15823", "号码A", "号码B"), engine.texts())

        val first = engine.pickCandidate(1)
        assertEquals(EngineResult.Commit("号码A"), first)
    }

    @Test
    fun `picking second custom candidate commits second word`() {
        val engine = engine("15823" to listOf("号码A", "号码B"))
        engine.type("15823")
        val second = engine.pickCandidate(2)
        assertEquals(EngineResult.Commit("号码B"), second)
    }

    // 9
    @Test
    fun `custom candidate commit has no trailing space and clears composing`() {
        val engine = engine("15823" to listOf("号码A"))
        engine.type("15823")
        val result = engine.pickCandidate(1)

        assertTrue(result is EngineResult.Commit)
        val committed = (result as EngineResult.Commit).text
        assertEquals("号码A", committed)
        assertFalse(committed.endsWith(" "))
        assertTrue(engine.composing.isEmpty())
        assertEquals(emptyList<String>(), engine.texts())
    }

    // 13（原有数字候选行为不变：BasicEngine 仍补空格）
    @Test
    fun `picking raw digits keeps original commit behaviour`() {
        val engine = engine("15823" to listOf("号码A"))
        engine.type("15823")
        assertEquals(EngineResult.Commit("15823 "), engine.pickCandidate(0))
    }

    // 10（刷新后候选消失）
    @Test
    fun `refreshing with empty map removes custom candidate`() {
        val engine = engine("15823" to listOf("号码A"))
        engine.type("15823")
        assertEquals(listOf("15823", "号码A"), engine.texts())

        engine.setNumericCustomPhrases(emptyMap())
        assertEquals(listOf("15823"), engine.texts())
    }

    // 11 / 12（纯函数过滤）
    @Test
    fun `non numeric and blank codes never enter the numeric map`() {
        val map = DictionaryRepository.buildNumericCodeMap(
            listOf(
                DictionaryEntry(word = "甲", code = "15823"),
                DictionaryEntry(word = "乙", code = "abc"),
                DictionaryEntry(word = "丙", code = "12a3"),
                DictionaryEntry(word = "丁", code = ""),
                DictionaryEntry(word = "戊", code = null),
                DictionaryEntry(word = "己", code = "@"),
                DictionaryEntry(word = "庚", code = "1.2"),
            ),
        )
        assertEquals(mapOf("15823" to listOf("甲")), map)
    }

    @Test
    fun `disabled entries never enter the numeric map`() {
        val map = DictionaryRepository.buildNumericCodeMap(
            listOf(
                DictionaryEntry(word = "甲", code = "15823", enabled = true),
                DictionaryEntry(word = "乙", code = "17717", enabled = false),
            ),
        )
        assertEquals(mapOf("15823" to listOf("甲")), map)
    }

    @Test
    fun `same code keeps every word with stable order`() {
        val map = DictionaryRepository.buildNumericCodeMap(
            listOf(
                DictionaryEntry(word = "甲", code = "15823"),
                DictionaryEntry(word = "乙", code = "15823"),
                DictionaryEntry(word = "丙", code = "17717"),
            ),
        )
        assertEquals(listOf("甲", "乙"), map["15823"])
        assertEquals(listOf("丙"), map["17717"])
        assertEquals(listOf("15823", "17717"), map.keys.toList())
    }

    // 13（不影响其它模式）
    @Test
    fun `chinese mode is not affected by numeric map`() {
        val engine = engine("15823" to listOf("号码A"), mode = InputMode.CHINESE)
        engine.type("15823")
        assertFalse(engine.texts().contains("号码A"))
    }

    @Test
    fun `symbol mode with at sign is not affected by numeric map`() {
        val engine = engine("15823" to listOf("号码A"), mode = InputMode.SYMBOL)
        engine.handleKey(EngineKey.Punctuation("@"))
        assertFalse(engine.texts().contains("号码A"))
    }

    @Test
    fun `english mode is not affected by numeric map`() {
        val engine = engine("15823" to listOf("号码A"), mode = InputMode.ENGLISH)
        engine.type("123")
        assertFalse(engine.texts().contains("号码A"))
    }
}
