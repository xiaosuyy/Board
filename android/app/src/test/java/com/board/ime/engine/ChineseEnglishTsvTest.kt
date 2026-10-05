package com.board.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地中英释义 TSV 解析（P3）单元测试。
 *
 * 解析器只做精确建表，不做任何猜测/翻译；坏行一律忽略。
 */
class ChineseEnglishTsvTest {

    @Test
    fun `parses simple chinese tab english lines`() {
        val map = ChineseEnglishTsv.parse("你好\thello\n苹果\tapple")
        assertEquals(mapOf("你好" to "hello", "苹果" to "apple"), map)
    }

    @Test
    fun `values may contain spaces`() {
        val map = ChineseEnglishTsv.parse("谢谢\tthank you\n多少\thow many")
        assertEquals("thank you", map["谢谢"])
        assertEquals("how many", map["多少"])
    }

    @Test
    fun `ignores comments and blank lines`() {
        val map = ChineseEnglishTsv.parse(
            """
            # 这是注释

            你好	hello
            # 又一行注释

            """.trimIndent(),
        )
        assertEquals(mapOf("你好" to "hello"), map)
    }

    @Test
    fun `ignores lines without tab`() {
        val map = ChineseEnglishTsv.parse("你好 hello\n你好\thello")
        assertEquals(mapOf("你好" to "hello"), map)
    }

    @Test
    fun `ignores lines with empty side`() {
        val map = ChineseEnglishTsv.parse("\thello\n你好\t\n   \t   \n你好\thello")
        assertEquals(mapOf("你好" to "hello"), map)
    }

    @Test
    fun `trims surrounding whitespace`() {
        val map = ChineseEnglishTsv.parse("  你好  \t  hello  ")
        assertEquals(mapOf("你好" to "hello"), map)
    }

    @Test
    fun `handles crlf line endings`() {
        val map = ChineseEnglishTsv.parse("你好\thello\r\n苹果\tapple\r\n")
        assertEquals(mapOf("你好" to "hello", "苹果" to "apple"), map)
    }

    @Test
    fun `duplicate chinese keeps the first entry`() {
        val map = ChineseEnglishTsv.parse("苹果\tapple\n苹果\tpear")
        assertEquals("apple", map["苹果"])
    }

    @Test
    fun `empty input yields empty map`() {
        assertTrue(ChineseEnglishTsv.parse("").isEmpty())
        assertTrue(ChineseEnglishTsv.parse("\n\n#c\n").isEmpty())
    }
}
