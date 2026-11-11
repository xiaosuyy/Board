package com.board.ime.keyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字母键按住下滑（z/x/c/v 快捷操作）的手势判定 */
class SwipeDownGestureTest {

    @Test
    fun `straight down past threshold triggers once`() {
        val gesture = SwipeDownGesture(triggerPx = 70f)
        assertFalse(gesture.onMove(dxPx = 0f, dyPx = 40f))
        assertTrue(gesture.onMove(dxPx = 0f, dyPx = 80f))
        // 已触发后不重复触发
        assertFalse(gesture.onMove(dxPx = 0f, dyPx = 150f))
        assertTrue(gesture.isActive)
    }

    @Test
    fun `horizontal drag does not trigger`() {
        val gesture = SwipeDownGesture(triggerPx = 70f)
        assertFalse(gesture.onMove(dxPx = 120f, dyPx = 80f))
        assertFalse(gesture.isActive)
    }

    @Test
    fun `reset allows the gesture again`() {
        val gesture = SwipeDownGesture(triggerPx = 70f)
        assertTrue(gesture.onMove(dxPx = 0f, dyPx = 90f))
        gesture.reset()
        assertFalse(gesture.isActive)
        assertTrue(gesture.onMove(dxPx = 5f, dyPx = 90f))
    }

    @Test
    fun `diagonal move needs clear vertical dominance`() {
        val gesture = SwipeDownGesture(triggerPx = 70f, dominanceRatio = 1.3f)
        // dy = 80, dx = 70 → 80 <= 91，不算下滑
        assertFalse(gesture.onMove(dxPx = 70f, dyPx = 80f))
        // dy = 100, dx = 70 → 100 > 91，算下滑
        assertTrue(gesture.onMove(dxPx = 70f, dyPx = 100f))
    }
}
