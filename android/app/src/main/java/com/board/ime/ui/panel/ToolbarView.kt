package com.board.ime.ui.panel

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.board.ime.profile.AvatarStore
import com.board.ime.settings.BoardPreferences
import com.board.ime.ui.KeyboardTheme
import kotlin.math.max

/**
 * 工具栏（TOOLBAR 状态）—— 顶部那条很薄的功能区域。
 *
 * ```
 * [更多]              [工具 n] … [工具 2] [工具 1] [收起键盘]
 * ```
 * - 高度 = [KeyboardTheme.topPanelHeight]，与其他状态完全相同（未改动）
 * - 结构按钮固定：最左「更多」、最右「收起键盘」
 * - [ToolId] 工具**从右往左**排列（列表第 1 个最靠右），**只显示图标**，不显示文字
 * - 图标统一来自 [PanelIconPainter]，20dp、黑/白/灰 + 少量蓝色强调，无渐变/发光
 *
 * 交互：
 * - 点击 → [onToolClick]
 * - **长按后拖动** → 在工具栏内排序（[onReorder]）；拖出工具区（向左越过「更多」或拖到
 *   工具栏外）→ 移入「更多」（[onMoveToMore]）
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
    }

    /** 结构按钮点击 */
    var onAction: ((Action) -> Unit)? = null

    /** 工具点击（行为与「更多」里一致） */
    var onToolClick: ((ToolId) -> Unit)? = null

    /** 工具栏内部排序：把 [from] 位置的工具移动到 [to] */
    var onReorder: ((Int, Int) -> Unit)? = null

    /** 把工具移入「更多」（含被拖出工具栏的情况） */
    var onMoveToMore: ((ToolId) -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val painter = PanelIconPainter(resources)
    private var pressed: Action? = null

    /** 用户头像：设置过就替换最左侧「更多」的图标 */
    private var avatar: Bitmap? = null
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val avatarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        alpha = 48
    }
    private val avatarMatrix = Matrix()

    private val preferences by lazy { BoardPreferences(context.applicationContext) }

    /** 头像换了（设置里选图 / 同步拉取）就重新读文件；未变化时命中 AvatarStore 的缓存 */
    private val avatarListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AvatarStore.KEY_AVATAR_UPDATED_AT) refreshAvatar()
    }

    /** 简洁模式：待机时只显示最右「收起键盘」，隐藏更多 / 切换键盘 / 工具 */
    private var minimal = false

    /** 当前布局（列表顺序 = 从右往左） */
    private var tools: List<ToolId> = emptyList()

    /** 真正排得下的那部分（宽屏放不下时只画前缀，索引与 [tools] 一一对应） */
    private var laidOut: List<ToolId> = emptyList()

    private val fixedActions = ArrayList<Action>()
    private val fixedBounds = ArrayList<RectF>()
    private val toolBounds = ArrayList<RectF>()
    private val iconRect = RectF()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dragPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var pressedTool = -1

    // ---- 拖动状态 ----
    private var dragIndex = -1
    private var dragTarget = -1
    private var dragX = 0f
    private var dragY = 0f
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val longPress = Runnable { beginDrag() }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.toolbarBackground)
        requestLayout()
        invalidate()
    }

    /** 由布局驱动：设置工具栏里的工具（列表第 1 个在最右） */
    fun setTools(list: List<ToolId>) {
        if (tools == list) return
        tools = list
        cancelDrag()
        rebuildBounds()
        invalidate()
    }

    /** 简洁模式：true 时待机只显示最右「收起键盘」 */
    fun setMinimal(value: Boolean) {
        if (minimal == value) return
        minimal = value
        rebuildBounds()
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

    private fun hitTop(): Float {
        val rowHeight = theme.toolRowHeight.toFloat()
        val hit = theme.toolHitSize.toFloat()
        val top = (height - rowHeight) / 2f
        return top + (rowHeight - hit) / 2f
    }

    /** 工具区左边界：左边留给「更多」 */
    private fun toolLeftLimit(): Float =
        theme.toolSidePadding + theme.toolHitSize + theme.spaceXs.toFloat()

    private fun rebuildBounds() {
        fixedActions.clear()
        fixedBounds.clear()
        toolBounds.clear()
        laidOut = emptyList()
        if (width <= 0 || height <= 0) return

        val hit = theme.toolHitSize.toFloat()
        val top = hitTop()
        val bottom = top + hit
        val side = theme.toolSidePadding.toFloat()
        val gap = theme.spaceXs.toFloat()

        // 简洁模式：只保留最右「收起键盘」
        if (minimal) {
            val right = width - side - hit
            fixedActions += Action.COLLAPSE_KEYBOARD
            fixedBounds += RectF(right, top, right + hit, bottom)
            return
        }

        // 最左：更多
        fixedActions += Action.OPEN_MORE
        fixedBounds += RectF(side, top, side + hit, bottom)

        // 最右：收起键盘
        val right = width - side - hit
        fixedActions += Action.COLLAPSE_KEYBOARD
        fixedBounds += RectF(right, top, right + hit, bottom)

        // 工具：从右往左（第 1 个紧挨「收起键盘」左侧；切换键盘也是普通工具，可拖动 / 移入更多）
        val leftLimit = toolLeftLimit()
        val visible = ArrayList<ToolId>(tools.size)
        var x = right - hit - gap
        tools.forEach { tool ->
            if (x >= leftLimit) {
                toolBounds += RectF(x, top, x + hit, bottom)
                visible += tool
                x -= hit + gap
            }
        }
        laidOut = visible
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 结构按钮
        for (index in fixedActions.indices) {
            val action = fixedActions[index]
            val rect = fixedBounds[index]
            if (pressed == action) {
                pillPaint.color = theme.panelSurfacePressed
                canvas.drawRoundRect(rect, theme.radiusSmall * 2f, theme.radiusSmall * 2f, pillPaint)
            }
            val icon = when (action) {
                Action.COLLAPSE_KEYBOARD -> PanelIcon.KEYBOARD_HIDE
                Action.OPEN_MORE -> PanelIcon.MORE
            }
            if (action == Action.OPEN_MORE && avatar != null) {
                drawAvatar(canvas, rect)
            } else {
                painter.draw(canvas, rect.centerX(), rect.centerY(), theme.toolIconSize, icon, theme.iconColor)
            }
        }

        // 工具（只画图标）
        for (index in toolBounds.indices) {
            val rect = toolBounds[index]
            val tool = laidOut.getOrNull(index) ?: continue
            if (dragIndex == index) continue // 被拖起来的那个单独画
            if (pressedTool == index) {
                pillPaint.color = theme.panelSurfacePressed
                canvas.drawRoundRect(rect, theme.radiusSmall * 2f, theme.radiusSmall * 2f, pillPaint)
            }
            painter.draw(canvas, rect.centerX(), rect.centerY(), theme.toolIconSize, tool.icon, theme.iconColor)
        }

        // 拖动反馈：插入位置画一条很轻的竖条 + 手指下画半透明图标
        if (dragIndex >= 0) {
            val slot = toolBounds.getOrNull(dragTarget.coerceIn(0, toolBounds.size - 1))
            if (slot != null) {
                dragPaint.color = theme.iconColor
                dragPaint.alpha = 70
                val markerX = when {
                    dragTarget >= toolBounds.size -> slot.left
                    else -> slot.centerX()
                }
                canvas.drawRoundRect(
                    markerX - 1.5f * resources.displayMetrics.density,
                    slot.top,
                    markerX + 1.5f * resources.displayMetrics.density,
                    slot.bottom,
                    theme.radiusSmall * 2f,
                    theme.radiusSmall * 2f,
                    dragPaint,
                )
            }
            val tool = laidOut.getOrNull(dragIndex)
            if (tool != null) {
                dragPaint.color = theme.iconColor
                dragPaint.alpha = 170
                painter.draw(canvas, dragX, dragY, theme.toolIconSize * 1.1f, tool.icon, dragPaint.color)
            }
        }
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragX = event.x
                dragY = event.y
                val toolIndex = findToolIndex(event.x, event.y)
                if (toolIndex >= 0) {
                    pressedTool = toolIndex
                    pressed = null
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                } else {
                    pressed = findAction(event.x, event.y)
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                dragX = event.x
                dragY = event.y
                if (dragIndex >= 0) {
                    dragTarget = targetIndexFor(event.x)
                    invalidate()
                    return true
                }
                val moved = (event.x - downX) * (event.x - downX) + (event.y - downY) * (event.y - downY)
                if (moved > touchSlop * touchSlop) {
                    handler.removeCallbacks(longPress)
                    if (pressedTool >= 0) {
                        pressedTool = -1
                        invalidate()
                    }
                }
                val action = findAction(event.x, event.y)
                if (action != pressed) {
                    pressed = action
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (dragIndex >= 0) {
                    commitDrag()
                    cancelDrag()
                    invalidate()
                    return true
                }
                val toolIndex = findToolIndex(event.x, event.y)
                val wasTool = pressedTool
                val wasPressed = pressed
                pressedTool = -1
                pressed = null
                invalidate()
                if (toolIndex >= 0 && toolIndex == wasTool) {
                    performClick()
                    laidOut.getOrNull(toolIndex)?.let { onToolClick?.invoke(it) }
                } else {
                    val action = findAction(event.x, event.y)
                    if (action != null && action == wasPressed) {
                        performClick()
                        onAction?.invoke(action)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                cancelDrag()
                pressed = null
                pressedTool = -1
                invalidate()
            }
        }
        return true
    }

    private fun beginDrag() {
        if (pressedTool < 0) return
        dragIndex = pressedTool
        dragTarget = pressedTool
        pressedTool = -1
        dragX = downX
        dragY = downY
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun commitDrag() {
        val tool = laidOut.getOrNull(dragIndex) ?: return
        val inside = dragY >= 0f && dragY <= height && dragX >= toolLeftLimit()
        if (inside) {
            if (dragTarget != dragIndex) onReorder?.invoke(dragIndex, dragTarget)
        } else {
            onMoveToMore?.invoke(tool)
        }
    }

    private fun cancelDrag() {
        handler.removeCallbacks(longPress)
        if (dragIndex >= 0) {
            dragIndex = -1
            dragTarget = -1
            invalidate()
        }
    }

    /** 「更多」按钮：有头像时画成圆形头像（比图标略大，仍在 44dp 热区内） */
    private fun drawAvatar(canvas: Canvas, rect: RectF) {
        val bitmap = avatar ?: return
        val diameter = minOf(theme.toolIconSize * 1.3f, rect.width() * 0.66f)
        if (diameter <= 0f) return
        val radius = diameter / 2f
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(diameter / bitmap.width, diameter / bitmap.height)
        avatarMatrix.reset()
        avatarMatrix.setScale(scale, scale)
        avatarMatrix.postTranslate(
            centerX - bitmap.width * scale / 2f,
            centerY - bitmap.height * scale / 2f,
        )
        shader.setLocalMatrix(avatarMatrix)
        avatarPaint.shader = shader
        canvas.drawCircle(centerX, centerY, radius, avatarPaint)
        avatarRingPaint.color = theme.iconColor
        canvas.drawCircle(centerX, centerY, radius - avatarRingPaint.strokeWidth / 2f, avatarRingPaint)
    }

    private fun refreshAvatar() {
        val bitmap = AvatarStore.loadBitmap(context.applicationContext)
        if (avatar === bitmap) return
        avatar = bitmap
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        preferences.registerListener(avatarListener)
        refreshAvatar()
    }

    override fun onDetachedFromWindow() {
        preferences.unregisterListener(avatarListener)
        cancelDrag()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) {
            pressed = null
            pressedTool = -1
            cancelDrag()
            invalidate()
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findAction(x: Float, y: Float): Action? {
        for (index in fixedBounds.indices) {
            if (fixedBounds[index].contains(x, y)) return fixedActions[index]
        }
        return null
    }

    private fun findToolIndex(x: Float, y: Float): Int {
        for (index in toolBounds.indices) {
            if (toolBounds[index].contains(x, y)) return index
        }
        return -1
    }

    /** 落点对应的插入位置：中心在落点右侧的工具个数（工具栏从右往左） */
    private fun targetIndexFor(x: Float): Int {
        var index = 0
        toolBounds.forEach { rect -> if (rect.centerX() > x) index++ }
        return index
    }

    private companion object {
        const val LONG_PRESS_MS = 350L
    }
}
