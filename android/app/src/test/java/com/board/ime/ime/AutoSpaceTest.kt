package com.board.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中英文自动空格：只在汉字与半角字母 / 数字的交界处补空格，
 * 字母 / 数字两个开关互不影响。
 */
class AutoSpaceTest {

    private val letters = AutoSpaceConfig(letters = true, digits = false)
    private val digits = AutoSpaceConfig(letters = false, digits = true)

    @Test
    fun `disabled config never adds spaces`() {
        assertFalse(AutoSpaceConfig().enabled)
        assertFalse(AutoSpaceConfig().needsSpace('你', 'L'))
        assertFalse(AutoSpaceConfig().needsSpace('5', '章'))
    }

    @Test
    fun `han and latin letters split when letters enabled`() {
        assertTrue(letters.needsSpace('你', 'L'))
        assertTrue(letters.needsSpace('n', '好'))
        assertTrue(letters.needsSpace('好', 'x'))
    }

    @Test
    fun `han and digits split when digits enabled`() {
        assertTrue(digits.needsSpace('第', '5'))
        assertTrue(digits.needsSpace('5', '章'))
    }

    @Test
    fun `switches are independent`() {
        assertFalse(letters.needsSpace('第', '5'))
        assertFalse(digits.needsSpace('你', 'L'))
    }

    @Test
    fun `same script needs no space`() {
        assertFalse(letters.needsSpace('你', '好'))
        assertFalse(letters.needsSpace('a', 'b'))
        assertFalse(digits.needsSpace('1', '2'))
    }

    @Test
    fun `whitespace and punctuation are left alone`() {
        assertFalse(letters.needsSpace(' ', 'L'))
        assertFalse(letters.needsSpace('你', ' '))
        assertFalse(letters.needsSpace('你', '，'))
        assertFalse(letters.needsSpace('你', '。'))
        assertFalse(AutoSpaceConfig(letters = true, digits = true).needsSpace('-', '5'))
    }
}
