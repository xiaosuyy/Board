package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.board.ime.ui.KeyboardTheme

/**
 * 工具栏（TOOLBAR 状态）—— 顶部那条很薄的功能区域。
 *
 * ```
 * [更多]                          [收起键盘]  [剪贴板]
 * ```
 * - 高度 = [KeyboardTheme.topPanelHeight]（与其他三种状态完全相同）
 * - 图标 20dp（功能行高 44dp 的 45%），严格垂直居中，不顶上下边缘
 * - 图标全部来自 [PanelIconPainter] 的同一套矢量资源，线宽 / 圆角 / 视觉重量一致
 * - 不显示任何输入状态（中英 / 数字 / Shift 都由键盘表达）
 */
class ToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Action {
        /** 收起键盘（调用系统的隐藏输入法，不模拟返回键） */
        COLLAPSE_KEYBOARD,

        /** 展开「更多」（在同一个 TopPanel 区域内替换工具栏） */
        OPEN_MORE,

        /** 打开剪贴板 */
        CLIPBOARD,
    }

    var onAction: ((Action) -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val painter = PanelIconPainter(resources)
    private var pressed: Action? = null

    private val actions = ArrayList<Action>()
    private val bounds = ArrayList<RectF>()
    private val iconRect = RectF()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.toolbarBackground)
        requestLayout()
        invalidate()
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildBounds()
    }

    private fun rebuildBounds() {
        actions.clear()
        bounds.clear()
        if (width <= 0 || height <= 0) return

        val rowHeight = theme.toolRowHeight.toFloat()
        val hit = theme.toolHitSize.toFloat()
        val top = (height - rowHeight) / 2f
        val side = theme.toolSidePadding.toFloat()
        val hitTop = top + (rowHeight - hit) / 2f
        val hitBottom = top + (rowHeight + hit) / 2f
        val gap = theme.spaceXs.toFloat()

        // 最左：更多
        actions += Action.OPEN_MORE
        bounds += RectF(side, hitTop, side + hit, hitBottom)

        // 最右：剪贴板
        val right = width - side - hit
        actions += Action.CLIPBOARD
        bounds += RectF(right, hitTop, right + hit, hitBottom)

        // 剪贴板左边：收起键盘
        val collapseLeft = right - hit - gap
        actions += Action.COLLAPSE_KEYBOARD
        bounds += RectF(collapseLeft, hitTop, collapseLeft + hit, hitBottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (index in actions.indices) {
            val action = actions[index]
            val rect = bounds[index]
            if (pressed == action) {
                pillPaint.color = theme.panelSurfacePressed
                canvas.drawRoundRect(
                    rect,
                    theme.radiusSmall * 2f,
                    theme.radiusSmall * 2f,
                    pillPaint,
                )
            }
            val icon = when (action) {
                Action.COLLAPSE_KEYBOARD -> PanelIcon.KEYBOARD_HIDE
                Action.OPEN_MORE -> PanelIcon.MORE
                Action.CLIPBOARD -> PanelIcon.CLIPBOARD
            }
            painter.draw(canvas, rect.centerX(), rect.centerY(), theme.toolIconSize, icon, theme.iconColor)
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = findAction(event.x, event.y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val action = findAction(event.x, event.y)
                if (action != pressed) {
                    pressed = action
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val action = findAction(event.x, event.y)
                val wasPressed = pressed
                clearPressed()
                if (action != null && action == wasPressed) {
                    performClick()
                    onAction?.invoke(action)
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> clearPressed()
        }
        return true
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) clearPressed()
    }

    override fun onDetachedFromWindow() {
        clearPressed()
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun clearPressed() {
        if (pressed == null) return
        pressed = null
        invalidate()
    }

    private fun findAction(x: Float, y: Float): Action? {
        for (index in bounds.indices) {
            if (bounds[index].contains(x, y)) return actions[index]
        }
        return null
    }
}
