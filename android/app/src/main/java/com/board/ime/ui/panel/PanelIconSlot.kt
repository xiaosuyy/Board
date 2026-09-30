package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.board.ime.ui.KeyboardTheme

/**
 * 固定尺寸的单图标按钮（「更多」里的收起按钮、剪贴板卡片的删除按钮）。
 * 图标来自 [PanelIconPainter]，因此与工具栏图标完全同源、同尺寸。
 */
internal class PanelIconSlot @JvmOverloads constructor(
    context: Context,
    private var icon: PanelIcon,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSlotClick: (() -> Unit)? = null

    private val painter = PanelIconPainter(resources)
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var iconColor: Int = 0
    private var pressedColor: Int = 0
    private var iconSizePx: Float = 0f
    private var pillRadiusPx: Float = 0f
    private var pressed = false
    private var highlighted = false

    /** 图标可以中途换（例如收藏 / 取消收藏） */
    fun setIcon(icon: PanelIcon) {
        if (this.icon == icon) return
        this.icon = icon
        invalidate()
    }

    fun setTint(color: Int) {
        iconColor = color
        invalidate()
    }

    /** 拖动经过时的高亮（与按下态同一套视觉，不引入新样式） */
    fun setHighlighted(value: Boolean) {
        if (highlighted == value) return
        highlighted = value
        invalidate()
    }

    fun applyTheme(theme: KeyboardTheme, tint: Int = theme.iconColor) {
        iconColor = tint
        pressedColor = theme.panelSurfacePressed
        iconSizePx = theme.toolIconSize
        pillRadiusPx = theme.panelPillCornerRadius * 2f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pressed || highlighted) {
            pillPaint.color = pressedColor
            canvas.drawRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                pillRadiusPx,
                pillRadiusPx,
                pillPaint,
            )
        }
        painter.draw(canvas, width / 2f, height / 2f, iconSizePx, icon, iconColor)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                pressed = false
                invalidate()
                if (event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height) {
                    performClick()
                    onSlotClick?.invoke()
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                pressed = false
                invalidate()
            }
        }
        return true
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE && pressed) {
            pressed = false
            invalidate()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
