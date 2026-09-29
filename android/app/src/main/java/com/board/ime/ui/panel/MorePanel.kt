package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.board.ime.ui.KeyboardTheme

/**
 * 「更多」（MORE 状态）。
 *
 * 它**替换** TOOLBAR，占用完全相同的 TopPanel 区域与高度：
 * ```
 * [⌃]  主题   翻译   语音   设置   常用语   左移   右移   剪贴板   →
 * ```
 * - 左侧固定的收起按钮，其余功能横向可滑动（不做分页控件、不做巨大卡片）
 * - 每项 = 小图标（18dp）+ 简短文字（10sp），紧凑排布、统一视觉
 * - 未实现的功能弱化显示，点击明确提示尚未开发
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

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val closeSlot = PanelIconSlot(context, PanelIcon.CHEVRON_UP)
    private val row = LinearLayout(context)
    private val scroll = HorizontalScrollView(context)
    private val itemViews = ArrayList<MoreItemView>()

    private val items = listOf(
        MoreItem(Action.THEME, "主题", PanelIcon.THEME, true),
        MoreItem(Action.TRANSLATE, "翻译", PanelIcon.TRANSLATE, false),
        MoreItem(Action.VOICE, "语音", PanelIcon.VOICE, false),
        MoreItem(Action.SETTINGS, "设置", PanelIcon.SETTINGS, true),
        MoreItem(Action.PHRASES, "常用语", PanelIcon.PHRASES, false),
        MoreItem(Action.CURSOR_LEFT, "左移", PanelIcon.CURSOR_LEFT, true),
        MoreItem(Action.CURSOR_RIGHT, "右移", PanelIcon.CURSOR_RIGHT, true),
        MoreItem(Action.CLIPBOARD, "剪贴板", PanelIcon.CLIPBOARD, true),
    )

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

        items.forEach { item ->
            val view = MoreItemView(context, item)
            view.onItemClick = { onAction?.invoke(item.action) }
            itemViews += view
            row.addView(
                view,
                LayoutParams(theme.moreItemWidth, LayoutParams.MATCH_PARENT),
            )
        }
        closeSlot.onSlotClick = { onAction?.invoke(Action.CLOSE) }
        applyTheme(theme)
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
}

/** 「更多」里的一项描述 */
internal data class MoreItem(
    val action: MorePanel.Action,
    val label: String,
    val icon: PanelIcon,
    val available: Boolean,
)

/** 「更多」里的单个功能入口：小图标 + 简短文字，整块垂直居中 */
internal class MoreItemView @JvmOverloads constructor(
    context: Context,
    private val item: MoreItem,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onItemClick: (() -> Unit)? = null

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

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        textPaint.textSize = theme.moreLabelSize
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pressed) {
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

        val color = if (item.available) theme.iconColor else theme.candidateMutedText
        textPaint.color = color

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
            item.icon,
            color,
        )
        canvas.drawText(
            item.label,
            width / 2f,
            blockTop + theme.moreIconSize + gap - metrics.ascent,
            textPaint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val inside = event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height
                if (pressed != inside) {
                    pressed = inside
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val inside = event.x >= 0 && event.y >= 0 && event.x <= width && event.y <= height
                pressed = false
                invalidate()
                if (inside) {
                    performClick()
                    onItemClick?.invoke()
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
