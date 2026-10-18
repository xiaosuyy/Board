package com.board.ime.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空格键「左右滑动移动光标」的换算测试。
 *
 * 参数取 1080×1920 @480dpi（density 3）下的实测值：
 * 死区 50dp = 150px，步长 33dp ≈ 99px。
 * 微信实测：150px 不移动（算点按）、250px ≈ 1 字符、350px ≈ 2 字符。
 */
class SpaceCursorDragTest {

    private fun drag() = SpaceCursorDrag(deadZonePx = 150f, stepPx = 99f)

    @Test
    fun `tap inside dead zone is not a drag`() {
        val drag = drag()
        assertEquals(0, drag.onMove(-149f, 2f))
        assertFalse(drag.isActive)

        // 刚好等于死区也仍然算点按
        assertEquals(0, drag.onMove(-150f, 0f))
        assertFalse(drag.isActive)
    }

    @Test
    fun `vertical movement is not a cursor drag`() {
        val drag = drag()
        assertEquals(0, drag.onMove(60f, 220f))
        assertFalse(drag.isActive)
    }

    @Test
    fun `crossing dead zone activates drag but may not move yet`() {
        val drag = drag()
        assertEquals(0, drag.onMove(-160f, 3f))
        assertTrue(drag.isActive)
    }

    @Test
    fun `distance maps to character count`() {
        val drag = drag()
        drag.onMove(-160f, 0f)
        // 250px：越过 150px 死区后剩 100px → 1 个字符
        assertEquals(-1, drag.onMove(-250f, 0f))
        // 350px：剩 200px → 共 2 个字符，本次补 1 个
        assertEquals(-1, drag.onMove(-350f, 0f))
        // 450px：剩 300px → 共 3 个字符，本次补 1 个
        assertEquals(-1, drag.onMove(-450f, 0f))
    }

    @Test
    fun `big single move emits all steps at once`() {
        val drag = drag()
        assertEquals(-3, drag.onMove(-450f, 0f))
    }

    @Test
    fun `right swipe moves right with same mapping`() {
        val drag = drag()
        assertEquals(1, drag.onMove(250f, 0f))
        // 往回拖过死区 → 先回到起点，再过死区到左侧
        assertEquals(-1, drag.onMove(0f, 0f))
        assertEquals(-1, drag.onMove(-250f, 0f))
    }

    @Test
    fun `dragging back to start returns cursor to start`() {
        val drag = drag()
        assertEquals(-3, drag.onMove(-450f, 0f))
        // 回到按下点：绝对映射，光标回到起点
        assertEquals(3, drag.onMove(-150f, 0f))
        assertTrue(drag.isActive)
    }

    @Test
    fun `step boundary is exact`() {
        val drag = drag()
        // 死区 150 + 98 = 248 → 还差 1px 不满一格
        assertEquals(0, drag.onMove(-248f, 0f))
        assertEquals(-1, drag.onMove(-249f, 0f))
    }

    @Test
    fun `reset clears emitted steps`() {
        val drag = drag()
        assertEquals(-2, drag.onMove(-350f, 0f))
        drag.reset()
        assertFalse(drag.isActive)
        // 重新开始后同样的位移会重新从 0 算
        assertEquals(-2, drag.onMove(-350f, 0f))
    }
}
