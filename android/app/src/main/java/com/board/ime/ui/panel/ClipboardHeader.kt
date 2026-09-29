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
 * 剪贴板的顶部条（CLIPBOARD 状态），占用与 TOOLBAR 完全相同的 TopPanel 区域。
 *
 * ```
 * ←        剪贴板                    ☁   🔍   ✎
 * ```
 * - 返回 / 标题 / 云端筛选 / 搜索 / 编辑 全部垂直居中，图标同源同尺寸
 * - 云端筛选：只看「其他设备同步过来」的剪贴板（[ClipboardOrigin.REMOTE]）；
 *   再次点击恢复全部。激活时云朵变成强调色，不额外加文字
 * 搜索状态（点 🔍 后，同一块 56dp 区域原地变成搜索框，**不新增 Activity、不增高**）：
 * ```
 * ←   ⌕ 输入关键字…                    ✕
 * ```
 * 任何状态下高度都不变。
 */
class ClipboardHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var onBack: (() -> Unit)? = null
    var onToggleEdit: (() -> Unit)? = null
    var onToggleSearch: (() -> Unit)? = null
    var onClearSearch: (() -> Unit)? = null

    /** 云端筛选开关 */
    var onToggleRemoteFilter: (() -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val painter = PanelIconPainter(resources)
    private var editing = false
    private var searching = false
    private var remoteFilter = false
    private var query: String = ""
    private var pressed: Slot = Slot.NONE

    private enum class Slot { NONE, BACK, EDIT, SEARCH, CLEAR, CLOUD }

    private val backBounds = RectF()
    private val editBounds = RectF()
    private val searchBounds = RectF()
    private val cloudBounds = RectF()
    private val clearBounds = RectF()
    private val fieldBounds = RectF()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        textPaint.textSize = theme.clipboardTitleSize
        caretPaint.strokeWidth = theme.clipboardCaretWidth
        requestLayout()
        invalidate()
    }

    fun setEditing(value: Boolean) {
        if (editing == value) return
        editing = value
        invalidate()
    }

    /** 进入 / 退出搜索：同一个 View，只换内部画法 */
    fun setSearching(value: Boolean) {
        if (searching == value) return
        searching = value
        rebuildBounds()
        invalidate()
    }

    /** 云端筛选是否激活（云朵用强调色） */
    fun setRemoteFilter(value: Boolean) {
        if (remoteFilter == value) return
        remoteFilter = value
        invalidate()
    }

    fun setQuery(value: String) {
        if (query == value) return
        query = value
        invalidate()
    }

    fun isSearching(): Boolean = searching

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
        if (width <= 0 || height <= 0) return
        val hit = theme.toolHitSize.toFloat()
        val side = theme.toolSidePadding.toFloat()
        val centerY = height / 2f
        val top = centerY - hit / 2f
        val bottom = centerY + hit / 2f

        backBounds.set(side, top, side + hit, bottom)

        val gap = theme.spaceXs.toFloat()
        val right = width - side - hit
        editBounds.set(right, top, right + hit, bottom)
        val searchLeft = right - hit - gap
        searchBounds.set(searchLeft, top, searchLeft + hit, bottom)
        val cloudLeft = searchLeft - hit - gap
        cloudBounds.set(cloudLeft, top, cloudLeft + hit, bottom)
        clearBounds.set(right, top, right + hit, bottom)

        val fieldLeft = backBounds.right + theme.panelHorizontalPadding
        val fieldRight = (if (searching) clearBounds.left else cloudBounds.left) -
            theme.panelHorizontalPadding / 2f
        val fieldHeight = hit * FIELD_HEIGHT_RATIO
        fieldBounds.set(fieldLeft, centerY - fieldHeight / 2f, fieldRight, centerY + fieldHeight / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawSlot(canvas, backBounds, Slot.BACK, PanelIcon.BACK)

        if (searching) {
            drawSearchField(canvas)
            drawSlot(canvas, clearBounds, Slot.CLEAR, PanelIcon.CLOSE)
            return
        }

        val editColor = if (editing) theme.accent else theme.iconColor
        drawSlot(canvas, editBounds, Slot.EDIT, if (editing) PanelIcon.CHECK else PanelIcon.EDIT, editColor)
        drawSlot(canvas, searchBounds, Slot.SEARCH, PanelIcon.SEARCH)
        val cloudColor = if (remoteFilter) theme.accent else theme.iconColor
        drawSlot(canvas, cloudBounds, Slot.CLOUD, PanelIcon.CLOUD, cloudColor)

        textPaint.color = theme.candidateText
        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(TITLE, backBounds.right + theme.panelHorizontalPadding, baseline, textPaint)
    }

    /** 搜索框：浅色 pill + 放大镜 + 查询文本（过长时截左边，保证光标处可见） */
    private fun drawSearchField(canvas: Canvas) {
        pillPaint.color = theme.panelSurface
        canvas.drawRoundRect(fieldBounds, theme.radiusMedium, theme.radiusMedium, pillPaint)

        val iconSize = theme.iconMedium
        val iconX = fieldBounds.left + theme.panelHorizontalPadding + iconSize / 2f
        painter.draw(canvas, iconX, fieldBounds.centerY(), iconSize, PanelIcon.SEARCH, theme.candidateMutedText)

        val textLeft = iconX + iconSize / 2f + theme.panelHorizontalPadding * 0.6f
        val textRight = fieldBounds.right - theme.panelHorizontalPadding * 0.6f
        val metrics = textPaint.fontMetrics
        val baseline = fieldBounds.centerY() - (metrics.ascent + metrics.descent) / 2f

        if (query.isEmpty()) {
            textPaint.color = theme.candidateMutedText
            canvas.drawText(HINT, textLeft, baseline, textPaint)
            caretPaint.color = theme.accent
            canvas.drawRect(
                textLeft,
                baseline + metrics.ascent,
                textLeft + theme.clipboardCaretWidth,
                baseline + metrics.descent,
                caretPaint,
            )
            return
        }

        textPaint.color = theme.candidateText
        val available = (textRight - textLeft).coerceAtLeast(0f)
        val measured = textPaint.measureText(query)
        caretPaint.color = theme.accent
        if (measured <= available) {
            canvas.drawText(query, textLeft, baseline, textPaint)
            canvas.drawRect(
                textLeft + measured + theme.clipboardCaretWidth,
                baseline + metrics.ascent,
                textLeft + measured + theme.clipboardCaretWidth * 2f,
                baseline + metrics.descent,
                caretPaint,
            )
        } else {
            // 只显示尾部，前面加省略号
            val dots = "…"
            val dotsWidth = textPaint.measureText(dots)
            val keep = available - dotsWidth
            val start = findTailStart(query, keep)
            val tail = query.substring(start)
            canvas.drawText(dots, textLeft, baseline, textPaint)
            canvas.drawText(tail, textLeft + dotsWidth, baseline, textPaint)
            val tailWidth = textPaint.measureText(tail)
            canvas.drawRect(
                textLeft + dotsWidth + tailWidth + theme.clipboardCaretWidth,
                baseline + metrics.ascent,
                textLeft + dotsWidth + tailWidth + theme.clipboardCaretWidth * 2f,
                baseline + metrics.descent,
                caretPaint,
            )
        }
    }

    /** 二分（足够快）找出「尾部能塞进 keep 宽度」的起点 */
    private fun findTailStart(text: String, keep: Float): Int {
        if (keep <= 0f) return text.length
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high) / 2
            if (textPaint.measureText(text, mid, text.length) > keep) low = mid + 1 else high = mid
        }
        return low
    }

    private fun drawSlot(
        canvas: Canvas,
        bounds: RectF,
        slot: Slot,
        icon: PanelIcon,
        tint: Int = theme.iconColor,
    ) {
        if (pressed == slot) {
            pillPaint.color = theme.panelSurfacePressed
            canvas.drawRoundRect(
                bounds,
                theme.radiusSmall * 2f,
                theme.radiusSmall * 2f,
                pillPaint,
            )
        }
        painter.draw(canvas, bounds.centerX(), bounds.centerY(), theme.toolIconSize, icon, tint)
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = findSlot(event.x, event.y)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val slot = findSlot(event.x, event.y)
                if (slot != pressed) {
                    pressed = slot
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                val slot = findSlot(event.x, event.y)
                val wasPressed = pressed
                clearPressed()
                if (slot != Slot.NONE && slot == wasPressed) {
                    performClick()
                    when (slot) {
                        Slot.BACK -> onBack?.invoke()
                        Slot.EDIT -> onToggleEdit?.invoke()
                        Slot.SEARCH -> onToggleSearch?.invoke()
                        Slot.CLOUD -> onToggleRemoteFilter?.invoke()
                        Slot.CLEAR -> onClearSearch?.invoke()
                        Slot.NONE -> Unit
                    }
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
        if (pressed == Slot.NONE) return
        pressed = Slot.NONE
        invalidate()
    }

    private fun findSlot(x: Float, y: Float): Slot = when {
        backBounds.contains(x, y) -> Slot.BACK
        searching -> if (clearBounds.contains(x, y)) Slot.CLEAR else Slot.NONE
        editBounds.contains(x, y) -> Slot.EDIT
        searchBounds.contains(x, y) -> Slot.SEARCH
        cloudBounds.contains(x, y) -> Slot.CLOUD
        else -> Slot.NONE
    }

    private companion object {
        const val TITLE = "剪贴板"
        const val HINT = "输入关键字"
        const val FIELD_HEIGHT_RATIO = 0.68f
    }
}
