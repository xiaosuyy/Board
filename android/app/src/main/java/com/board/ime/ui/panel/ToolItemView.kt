package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.board.ime.ui.KeyboardTheme

/**
 * 「更多」/ v+空格 快捷面板里的单个功能入口：小图标 + 简短文字，整块垂直居中。
 *
 * 支持长按拖动（只有「更多」会用到；快捷面板不注入拖动回调即可）。
 */
internal class ToolItemView @JvmOverloads constructor(
    context: Context,
    private val icon: PanelIcon,
    private val label: String,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onItemClick: (() -> Unit)? = null
    var onDragStart: ((Int) -> Unit)? = null
    var onDragMove: ((Int, Float, Float) -> Unit)? = null
    var onDragEnd: ((Int, Float, Float) -> Unit)? = null

    private val painter = PanelIconPainter(resources)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )
    private var pressed = false
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPress = Runnable {
        if (!dragging && onDragStart != null) {
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            onDragStart?.invoke(0)
            invalidate()
        }
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        textPaint.textSize = theme.moreLabelSize
        invalidate()
    }

    fun setDragging(value: Boolean) {
        if (dragging == value) return
        dragging = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pressed && !dragging) {
            pressedPaint.color = theme.panelSurfacePressed
            canvas.drawRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                theme.panelPillCornerRadius * 2f,
                theme.panelPillCornerRadius * 2f,
                pressedPaint,
            )
        }

        val color = theme.iconColor
        textPaint.color = color
        val alpha = if (dragging) 120 else 255

        val metrics = textPaint.fontMetrics
        val labelHeight = metrics.descent - metrics.ascent
        val gap = 2f * resources.displayMetrics.density
        val blockHeight = theme.moreIconSize + gap + labelHeight
        val blockTop = height / 2f - blockHeight / 2f

        painter.draw(
            canvas,
            width / 2f,
            blockTop + theme.moreIconSize / 2f,
            theme.moreIconSize,
            icon,
            withAlpha(color, alpha),
        )
        textPaint.alpha = alpha
        canvas.drawText(
            label,
            width / 2f,
            blockTop + theme.moreIconSize + gap - metrics.ascent,
            textPaint,
        )
        textPaint.alpha = 255
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressed = true
                invalidate()
                if (onDragStart != null) handler.postDelayed(longPress, LONG_PRESS_MS)
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    onDragMove?.invoke(0, event.rawX, event.rawY)
                    return true
                }
                val moved = (event.x - downX) * (event.x - downX) + (event.y - downY) * (event.y - downY)
                if (moved > touchSlop * touchSlop) {
                    handler.removeCallbacks(longPress)
                    pressed = false
                    invalidate()
                    return true
                }
                val inside = event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height
                if (pressed != inside) {
                    pressed = inside
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    onDragEnd?.invoke(0, event.rawX, event.rawY)
                    return true
                }
                val inside = event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height
                pressed = false
                invalidate()
                if (inside) {
                    performClick()
                    onItemClick?.invoke()
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                handler.removeCallbacks(longPress)
                if (dragging) {
                    onDragEnd?.invoke(0, event.rawX, event.rawY)
                    return true
                }
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

    private companion object {
        const val LONG_PRESS_MS = 350L
    }
}
