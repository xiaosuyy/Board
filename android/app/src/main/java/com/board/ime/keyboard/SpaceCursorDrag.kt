package com.board.ime.keyboard

import kotlin.math.abs

/**
 * 空格键「左右滑动移动光标」的手势换算。
 *
 * 行为参考微信输入法实测，但 Board 的死区 / 步长更小（更灵敏）：
 * - 位移不超过 [deadZonePx] → 不算滑动，仍然按**点按**处理（输入空格）；
 * - 越过死区后，每累计 [stepPx] 移动 **1 个字符**；
 * - 采用**绝对映射**：光标位置只由「当前手指相对按下点的位移」决定，
 *   所以往回拖会按同样的步长把光标移回去，不会突然跳回起点；
 * - 以竖直为主的滑动不当作移动光标（交给正常换键逻辑）。
 *
 * 纯计算，不依赖 Android 类型，方便单测；View 只负责把 px 与方向喂进来。
 */
class SpaceCursorDrag(
    private val deadZonePx: Float,
    private val stepPx: Float,
) {

    /** 是否已经越过了死区（= 本次手势是「滑动」，不是「点按」） */
    var isActive: Boolean = false
        private set

    /** 已经移动过的字符数（带符号：负 = 左，正 = 右） */
    private var emittedSteps: Int = 0

    fun reset() {
        isActive = false
        emittedSteps = 0
    }

    /**
     * 处理一次手势移动。
     *
     * @param dxPx 相对按下点的水平位移（px，右正左负）
     * @param dyPx 相对按下点的竖直位移（px）
     * @return 本次还需要移动的字符数（带符号）；0 表示不用动
     */
    fun onMove(dxPx: Float, dyPx: Float): Int {
        if (!isActive) {
            if (abs(dxPx) <= deadZonePx) return 0
            // 竖直分量更大：这是「滑出键盘 / 换键」，不是移动光标
            if (abs(dxPx) < abs(dyPx)) return 0
            isActive = true
        }

        // 扣掉死区后的有效位移；仍处在死区内就是 0 格（光标回到起点）
        val offset = abs(dxPx) - deadZonePx
        val steps = if (offset <= 0f || stepPx <= 0f) 0 else (offset / stepPx).toInt()
        val target = if (dxPx < 0f) -steps else steps

        val delta = target - emittedSteps
        emittedSteps = target
        return delta
    }
}
