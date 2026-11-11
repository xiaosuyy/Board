package com.board.ime.keyboard

import kotlin.math.abs

/**
 * 字母键「按住下滑」快捷手势的判定（z / x / c / v）。
 *
 * - 竖直位移超过 [triggerPx]，且明显大于水平位移（[dominanceRatio] 倍）时触发一次；
 * - 触发后 [isActive] 保持为 true，直到 [reset]（抬手 / 取消时调用），
 *   期间 View 不再换键、也不再触发长按。
 *
 * 纯计算，不依赖 Android 类型，方便单测；View 只负责喂位移。
 */
class SwipeDownGesture(
    private val triggerPx: Float,
    private val dominanceRatio: Float = 1.3f,
) {

    /** 本次手势是否已经触发过下滑快捷操作 */
    var isActive: Boolean = false
        private set

    fun reset() {
        isActive = false
    }

    /** @return true 表示本次移动触发了下滑（只触发一次） */
    fun onMove(dxPx: Float, dyPx: Float): Boolean {
        if (isActive) return false
        if (dyPx <= triggerPx) return false
        // 横向分量太大：更像是在正常换键 / 划动，不当作下滑
        if (dyPx <= abs(dxPx) * dominanceRatio) return false
        isActive = true
        return true
    }
}
