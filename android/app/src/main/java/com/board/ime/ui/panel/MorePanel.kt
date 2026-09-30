package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.board.ime.ui.KeyboardTheme

/**
 * 「更多」（MORE 状态）。
 *
 * 它**替换** TOOLBAR，占用完全相同的 TopPanel 区域与高度：
 * ```
 * [⌃]  主题   翻译   语音   设置   常用语   左移   右移        →
 * ```
 * - 左侧固定的收起按钮，其余功能横向可滑动（不做分页控件、不做巨大卡片）
 * - 每项 = 小图标（18dp）+ 简短文字（10sp），紧凑排布、统一视觉（未做视觉改版）
 * - 条目由 [ToolLayout.more] 驱动；未实现的功能弱化显示，点击明确提示尚未开发
 *
 * 交互：点击 = 执行；**长按拖动** = 行内排序；拖到左侧「⌃」（= 回到工具栏）按钮上或拖出
 * 面板上方 → 移入工具栏（[onMoveToToolbar]）。
 */
class MorePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Action {
        CLOSE,
        THEME,
        TRANSLATE,
        VOICE,
        SETTINGS,
        PHRASES,
        CURSOR_LEFT,
        CURSOR_RIGHT,
        CLIPBOARD,
    }

    var onAction: ((Action) -> Unit)? = null

    /** 「更多」内部排序 */
    var onReorder: ((Int, Int) -> Unit)? = null

    /** 移入工具栏 */
    var onMoveToToolbar: ((ToolId) -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val closeSlot = PanelIconSlot(context, PanelIcon.CHEVRON_UP)
    private val row = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val itemViews = ArrayList<MoreItemView>()

    /** 当前布局里的「更多」条目 */
    private var tools: List<ToolId> = emptyList()

    private var draggingIndex = -1
    private var dropOnToolbar = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(closeSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        row.orientation = HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        scroll.isHorizontalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(
            row,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT),
        )
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        closeSlot.onSlotClick = { onAction?.invoke(Action.CLOSE) }
        rebuildItems()
        applyTheme(theme)
    }

    /** 由布局驱动：设置「更多」里的工具 */
    fun setTools(list: List<ToolId>) {
        if (tools == list) return
        tools = list
        rebuildItems()
        applyTheme(theme)
    }

    private fun rebuildItems() {
        row.removeAllViews()
        itemViews.clear()
        tools.forEachIndexed { index, tool ->
            val view = MoreItemView(context, tool)
            view.onItemClick = { onAction?.invoke(tool.action) }
            view.onDragStart = { startDrag(index) }
            view.onDragMove = { _, rawX, rawY -> updateDropTarget(index, rawX, rawY) }
            view.onDragEnd = { _, rawX, rawY -> finishDrag(index, rawX, rawY) }
            itemViews += view
            row.addView(view, LayoutParams(theme.moreItemWidth, LayoutParams.MATCH_PARENT))
        }
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        closeSlot.applyTheme(theme)
        itemViews.forEach { it.applyTheme(theme) }
        (closeSlot.layoutParams as? LayoutParams)?.let {
            it.width = theme.toolHitSize
            closeSlot.layoutParams = it
        }
        itemViews.forEach {
            (it.layoutParams as? LayoutParams)?.let { lp ->
                lp.width = theme.moreItemWidth
                it.layoutParams = lp
            }
        }
        requestLayout()
        invalidate()
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 先让 LinearLayout 正常测量子视图（收起按钮 + 可滑动功能行）
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val desired = resolveSize(theme.topPanelHeight, heightMeasureSpec)
        if (measuredHeight != desired) {
            setMeasuredDimension(measuredWidth, desired)
        }
    }

    // ---------------------------------------------------------------- 拖动

    private fun startDrag(index: Int) {
        if (draggingIndex == index) return
        draggingIndex = index
        dropOnToolbar = false
        itemViews.forEachIndexed { i, view -> view.setDragging(i == index) }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun updateDropTarget(index: Int, rawX: Float, rawY: Float) {
        if (draggingIndex != index) return
        val onToolbar = isOverToolbarTarget(rawX, rawY)
        if (onToolbar != dropOnToolbar) {
            dropOnToolbar = onToolbar
            closeSlot.setHighlighted(onToolbar)
            invalidate()
        }
    }

    /** 拖到左侧「⌃」按钮上，或拖出面板上方 → 视为「移到工具栏」 */
    private fun isOverToolbarTarget(rawX: Float, rawY: Float): Boolean {
        val panel = IntArray(2)
        getLocationOnScreen(panel)
        val slot = IntArray(2)
        closeSlot.getLocationOnScreen(slot)
        val rect = RectF(
            slot[0].toFloat(),
            slot[1].toFloat(),
            slot[0] + closeSlot.width.toFloat(),
            slot[1] + closeSlot.height.toFloat(),
        )
        // 按钮本身给一点额外的容错
        rect.inset(-theme.spaceXs.toFloat(), 0f)
        if (rect.contains(rawX, rawY)) return true
        return rawY < panel[1] // 拖出面板上方
    }

    private fun finishDrag(index: Int, rawX: Float, rawY: Float) {
        val tool = tools.getOrNull(index)
        val toToolbar = dropOnToolbar
        itemViews.forEach { it.setDragging(false) }
        closeSlot.setHighlighted(false)
        draggingIndex = -1
        dropOnToolbar = false
        invalidate()
        if (tool == null) return
        if (toToolbar) {
            onMoveToToolbar?.invoke(tool)
            return
        }
        val panel = IntArray(2)
        getLocationOnScreen(panel)
        if (rawY > panel[1] + height) return // 拖到键盘区：保持不动
        // 行内排序：插入位置 = 中心在落点左侧的条目数（「更多」从左往右）
        var target = 0
        itemViews.forEachIndexed { i, view ->
            if (i == index) return@forEachIndexed
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            val center = loc[0] + view.width / 2f
            if (center < rawX) target++
        }
        if (target != index) onReorder?.invoke(index, target)
    }
}

/** 「更多」里的单个功能入口：小图标 + 简短文字，整块垂直居中 */
internal class MoreItemView @JvmOverloads constructor(
    context: Context,
    private val tool: ToolId,
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
        if (!dragging) {
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

        val available = isToolAvailable(tool)
        val color = if (available) theme.iconColor else theme.candidateMutedText
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
            tool.icon,
            withAlpha(color, alpha),
        )
        textPaint.alpha = alpha
        canvas.drawText(
            tool.label,
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
                handler.postDelayed(longPress, LONG_PRESS_MS)
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

/**
 * 尚未实现引擎的工具在 UI 上弱化显示（灰显）。
 *
 * 语音（Phase 2）与翻译（Phase 3）都已实现，因此不再灰显；只剩「常用语」还没做。
 */
internal fun isToolAvailable(tool: ToolId): Boolean = when (tool) {
    ToolId.PHRASES -> false
    else -> true
}
