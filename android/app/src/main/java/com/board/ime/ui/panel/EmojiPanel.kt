package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.board.ime.ui.KeyboardTheme

/**
 * 表情内容区（占键盘位置，总高度与其它状态一致）。
 *
 * 一个可滚动的 emoji 网格。搜索时只占上半部分，键盘继续显示在下方
 * （由 [PanelLayoutMode.EMOJI_WITH_KEYBOARD] 决定）。
 *
 * 网格用自绘 View 而不是几百个 TextView：一屏只画可见行，
 * 滚动和搜索结果更新都不会重新创建 View。
 */
class EmojiPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 点选一个 emoji / 符号 */
    var onPick: ((String) -> Unit)? = null

    private val scroll = ScrollView(context)
    private val grid = EmojiGridView(context)
    private val emptyView = TextView(context)

    private var items: List<String> = emptyList()

    init {
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(
            grid,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        emptyView.apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(24))
            visibility = GONE
        }
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        grid.onPick = { emoji -> onPick?.invoke(emoji) }
        applyTheme(KeyboardTheme.from(context, com.board.ime.settings.ThemeMode.SYSTEM))
    }

    fun applyTheme(theme: KeyboardTheme) {
        setBackgroundColor(theme.keyboardBackground)
        scroll.setBackgroundColor(theme.keyboardBackground)
        emptyView.setTextColor(theme.candidateMutedText)
        emptyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        grid.applyTheme(theme)
    }

    fun render(items: List<String>, emptyText: String = "没有找到相关表情") {
        val changed = this.items != items
        this.items = items
        grid.setItems(items)
        emptyView.text = emptyText
        emptyView.visibility = if (items.isEmpty()) VISIBLE else GONE
        if (changed) scroll.scrollTo(0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * 8 列 emoji 网格。只绘制当前可见的几行，点按命中最接近的格子。
 */
private class EmojiGridView(context: Context) : View(context) {

    var onPick: ((String) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
    }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )
    private var items: List<String> = emptyList()
    private var pressedIndex = -1

    private val cellHeight = (CELL_HEIGHT_DP * resources.displayMetrics.density).toInt()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        textPaint.color = theme.candidateText
        textPaint.textSize = theme.candidateTextSize * EMOJI_TEXT_SCALE
        pressedPaint.color = theme.panelSurfacePressed
        invalidate()
    }

    fun setItems(list: List<String>) {
        if (items == list) return
        items = list
        pressedIndex = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = (items.size + COLUMNS - 1) / COLUMNS
        setMeasuredDimension(
            width,
            resolveSize(rows * cellHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty() || width <= 0) return

        val cellWidth = width.toFloat() / COLUMNS
        val clip = canvas.clipBounds
        val rows = (items.size + COLUMNS - 1) / COLUMNS
        val firstRow = (clip.top / cellHeight).coerceAtLeast(0)
        val lastRow = ((clip.bottom + cellHeight - 1) / cellHeight).coerceAtMost(rows)
        val radius = theme.radiusSmall

        for (row in firstRow until lastRow) {
            val top = (row * cellHeight).toFloat()
            for (column in 0 until COLUMNS) {
                val index = row * COLUMNS + column
                if (index >= items.size) break
                if (index == pressedIndex) {
                    canvas.drawRoundRect(
                        column * cellWidth,
                        top,
                        (column + 1) * cellWidth,
                        top + cellHeight,
                        radius,
                        radius,
                        pressedPaint,
                    )
                }
                val centerX = column * cellWidth + cellWidth / 2f
                val baseline = top + cellHeight / 2f -
                    (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(items[index], centerX, baseline, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressedIndex = indexAt(event.x, event.y)
                invalidate()
                return pressedIndex >= 0
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > touchSlop * touchSlop && pressedIndex >= 0) {
                    pressedIndex = -1
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = indexAt(event.x, event.y)
                val wasPressed = pressedIndex
                pressedIndex = -1
                invalidate()
                if (index >= 0 && index == wasPressed) {
                    performClick()
                    onPick?.invoke(items[index])
                }
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1
        val cellWidth = width.toFloat() / COLUMNS
        val column = (x / cellWidth).toInt()
        val row = (y / cellHeight).toInt()
        val index = row * COLUMNS + column
        return if (index in items.indices) index else -1
    }

    private companion object {
        const val COLUMNS = 8
        const val CELL_HEIGHT_DP = 46
        const val EMOJI_TEXT_SCALE = 1.35f
    }
}
