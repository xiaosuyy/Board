package com.board.ime.keyboard

import kotlin.math.abs

/**
 * 按键「按住滑动」快捷手势的判定（z / x / c / v 下滑、逗号键上滑）。
 *
 * - 竖直位移超过 [triggerPx]，且明显大于水平位移（[dominanceRatio] 倍）时触发一次；
 * - [upward] = true 时改为向上滑动才触发；
 * - 触发后 [isActive] 保持为 true，直到 [reset]（抬手 / 取消时调用），
 *   期间 View 不再换键、也不再触发长按。
 *
 * 纯计算，不依赖 Android 类型，方便单测；View 只负责喂位移。
 */
class SwipeDownGesture(
    private val triggerPx: Float,
    private val dominanceRatio: Float = 1.3f,
    private val upward: Boolean = false,
) {

    /** 本次手势是否已经触发过下滑快捷操作 */
    var isActive: Boolean = false
        private set

    fun reset() {
        isActive = false
    }

    /** @return true 表示本次移动触发了手势（只触发一次） */
    fun onMove(dxPx: Float, dyPx: Float): Boolean {
        if (isActive) return false
        val travel = if (upward) -dyPx else dyPx
        if (travel <= triggerPx) return false
        // 横向分量太大：更像是在正常换键 / 划动，不当作滑动快捷操作
        if (travel <= abs(dxPx) * dominanceRatio) return false
        isActive = true
        return true
    }
}
