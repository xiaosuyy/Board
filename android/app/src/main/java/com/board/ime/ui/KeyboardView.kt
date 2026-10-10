package com.board.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.board.ime.engine.InputMode
import com.board.ime.keyboard.KeyRow
import com.board.ime.keyboard.KeySpec
import com.board.ime.keyboard.KeyType
import com.board.ime.keyboard.KeyboardLayout
import com.board.ime.keyboard.KeyboardLayoutMetrics
import com.board.ime.keyboard.KeyboardLayouts
import kotlin.math.min

/**
 * Board 自绘键盘。
 *
 * 只做三件事：按布局算矩形、画按键、处理触摸。
 * 具体“按键按下后做什么”由 [OnKeyboardActionListener] 交给 `InputController`，
 * 因此键盘 UI 与输入逻辑完全解耦。
 *
 * 布局宽度全部由单位换算，天然自适应任意屏幕宽度。
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 按键回调，只在键盘内部做“手势 → 语义按键”的转换 */
    interface OnKeyboardActionListener {
        /** 正常按下并抬起 */
        fun onKey(key: KeySpec)

        /** 长按（目前用于语言键：打开系统输入法选择器） */
        fun onKeyLongPress(key: KeySpec)

        /** 连续触发（目前用于退格的连续删除） */
        fun onKeyRepeat(key: KeySpec)
    }

    var listener: OnKeyboardActionListener? = null

    var keyboardLayout: KeyboardLayout = KeyboardLayouts.letters
        set(value) {
            if (field === value) return
            field = value
            requestLayout()
            rebuildKeyBounds()
        }

    var keyboardMode: InputMode = InputMode.ENGLISH
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var shiftActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var capsLock: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private var theme: KeyboardTheme = KeyboardTheme.from(context, com.board.ime.settings.ThemeMode.SYSTEM)

    /**
     * 键盘尺寸。由 `KeyboardLayoutCalculator` 按屏幕尺寸算好后注入，
     * View 自己不做任何屏幕判断，也不写死像素。
     */
    private var metrics: KeyboardLayoutMetrics? = null

    private val keys = ArrayList<KeySpec>()
    private val bounds = ArrayList<RectF>()

    /**
     * 实际可点击区域。
     *
     * 视觉键帽之间留有 6dp/8dp 间隙，如果按视觉矩形做命中判定，手指落在间隙里就会
     * 「点了没反应」（真机上手感就是「要按得很准、很用力」）。这里把命中矩形向四周
     * 各扩半个间隙，间隙被完全覆盖、键盘没有死区，**视觉尺寸一点不变**。
     */
    private val hitBounds = ArrayList<RectF>()

    private var pressedIndex = -1
    private var longPressFired = false
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    private val handler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // 绘制阶段复用这些对象：onDraw 里不再 new Path / new RectF（避免每帧分配 → GC 抖动）
    private val glyphPath = Path()
    private val glyphRect = RectF()

    /** 缓存解析后的尺寸，避免 onDraw / onMeasure 每次都构造一个 KeyboardLayoutMetrics */
    private var cachedMetrics: KeyboardLayoutMetrics? = null
    private var metricsDirty = true

    private val longPressRunnable = Runnable {
        val key = keys.getOrNull(pressedIndex) ?: return@Runnable
        longPressFired = true
        if (key.type == KeyType.BACKSPACE) {
            startBackspaceRepeat(key)
        } else {
            listener?.onKeyLongPress(key)
        }
    }

    init {
        isClickable = true
        setBackgroundColor(theme.keyboardBackground)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.keyboardBackground)
        metricsDirty = true
        rebuildKeyBounds()
        requestLayout()
        invalidate()
    }

    /** 注入当前屏幕算出来的尺寸（横屏悬浮布局会用到） */
    fun applyMetrics(metrics: KeyboardLayoutMetrics) {
        this.metrics = metrics
        metricsDirty = true
        rebuildKeyBounds()
        requestLayout()
        invalidate()
    }

    private fun resolvedMetrics(): KeyboardLayoutMetrics {
        cachedMetrics?.let { if (!metricsDirty) return it }
        val resolved = metrics ?: KeyboardLayoutMetrics(
            floating = false,
            widthPx = width,
            keyHeightPx = theme.keyHeight,
            keyGapHorizontalPx = theme.keyGapHorizontal,
            keyGapVerticalPx = theme.keyGapVertical,
            paddingHorizontalPx = theme.keyboardPaddingHorizontal,
            paddingVerticalPx = theme.keyboardPaddingVertical,
            cornerRadiusPx = theme.keyCornerRadius,
            edgeMarginPx = 0,
            alignEnd = false,
        )
        cachedMetrics = resolved
        metricsDirty = false
        return resolved
    }

    fun setShiftState(active: Boolean, locked: Boolean) {
        shiftActive = active
        capsLock = locked
    }

    // ---------------------------------------------------------------- 测量 / 布局

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowCount = keyboardLayout.rowCount.coerceAtLeast(0)
        val metrics = resolvedMetrics()
        val desiredHeight = metrics.keyboardHeightPx(rowCount)

        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildKeyBounds()
    }

    private fun rebuildKeyBounds() {
        keys.clear()
        bounds.clear()
        hitBounds.clear()

        val width = width
        if (width <= 0) return

        val metrics = resolvedMetrics()
        val padH = metrics.paddingHorizontalPx
        val contentWidth = width - padH * 2f
        if (contentWidth <= 0f) return

        // 命中区域向外扩半个间隙 → 键与键之间不再有「点了没反应」的死区
        val expandX = metrics.keyGapHorizontalPx / 2f
        val expandY = metrics.keyGapVerticalPx / 2f
        // 功能键宽度倍率：只影响功能键（Shift / 退格 / 回车 / 切层…），字母区不变
        val fnRatio = metrics.functionKeyWidthRatio

        keyboardLayout.rows.forEachIndexed { rowIndex, row ->
            val unit = contentWidth / row.unitsWith(fnRatio)
            if (unit <= 0f) return@forEachIndexed

            val top = metrics.paddingVerticalPx +
                rowIndex * (metrics.keyHeightPx + metrics.keyGapVerticalPx)
            var cursor = padH + row.leadingPadUnits * unit

            row.keys.forEach { key ->
                val width = key.unitsFor(fnRatio) * unit
                val left = cursor + metrics.keyGapHorizontalPx / 2f
                val right = cursor + width - metrics.keyGapHorizontalPx / 2f
                val rect = RectF(left, top, right, top + metrics.keyHeightPx)
                keys += key
                bounds += rect
                hitBounds += RectF(
                    rect.left - expandX,
                    rect.top - expandY,
                    rect.right + expandX,
                    rect.bottom + expandY,
                )
                cursor += width
            }
        }
    }

    /** 某个键在给定功能键倍率下的实际宽度单位 */
    private fun KeySpec.unitsFor(functionKeyRatio: Float): Float =
        if (type.isFunctional && functionKeyRatio != 1f) units * functionKeyRatio else units

    /** 一行的总单位数（考虑功能键倍率） */
    private fun KeyRow.unitsWith(functionKeyRatio: Float): Float {
        var sum = leadingPadUnits + trailingPadUnits
        keys.forEach { sum += it.unitsFor(functionKeyRatio) }
        return sum
    }

    // ---------------------------------------------------------------- 绘制

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = resolvedMetrics().cornerRadiusPx

        for (index in keys.indices) {
            val key = keys[index]
            val rect = bounds[index]
            if (rect.width() <= 0f) continue

            keyPaint.color = backgroundColorFor(key, pressed = index == pressedIndex)
            canvas.drawRoundRect(rect, radius, radius, keyPaint)
            drawKeyContent(canvas, key, rect)
        }
    }

    private fun backgroundColorFor(key: KeySpec, pressed: Boolean): Int = when {
        // 强调色只出现在“按键状态”这种少量场景
        key.type == KeyType.SHIFT && (shiftActive || capsLock) -> theme.accent
        pressed && key.type.isFunctional -> theme.functionKeyPressedBackground
        pressed -> theme.keyPressedBackground
        key.type.isFunctional -> theme.functionKeyBackground
        else -> theme.keyBackground
    }

    private fun drawKeyContent(canvas: Canvas, key: KeySpec, rect: RectF) {
        val shiftOn = key.type == KeyType.SHIFT && (shiftActive || capsLock)
        val onAccentColor = if (shiftOn) theme.accentText else theme.functionKeyText

        when (key.type) {
            KeyType.CHARACTER -> drawAdaptiveLabel(
                canvas = canvas,
                rect = rect,
                text = characterLabel(key),
                color = theme.keyText,
            )

            KeyType.SYMBOL -> drawAdaptiveLabel(
                canvas = canvas,
                rect = rect,
                text = key.displayText,
                color = theme.keyText,
            )

            KeyType.COMMA -> drawLabel(
                canvas = canvas,
                rect = rect,
                text = if (keyboardMode == InputMode.CHINESE) "，" else ",",
                color = theme.keyText,
                size = theme.keyTextSize,
            )

            KeyType.PERIOD -> drawLabel(
                canvas = canvas,
                rect = rect,
                text = if (keyboardMode == InputMode.CHINESE) "。" else ".",
                color = theme.keyText,
                size = theme.keyTextSize,
            )

            // 微信字母层底行的「，。」：显示随语言的标点
            KeyType.PUNCTUATION -> drawLabel(
                canvas = canvas,
                rect = rect,
                text = if (keyboardMode == InputMode.CHINESE) "，" else ",",
                color = theme.keyText,
                size = theme.keyTextSize,
            )

            KeyType.NUMBER_PAGE -> drawFunctionLabel(
                canvas,
                rect,
                key.displayText.ifEmpty { "12/34" },
            )

            KeyType.NOT_IMPLEMENTED -> drawLabel(
                canvas = canvas,
                rect = rect,
                text = key.displayText,
                color = theme.functionKeyText,
                size = theme.functionKeyTextSize,
            )

            KeyType.SPACE -> drawLabel(
                canvas = canvas,
                rect = rect,
                text = when (keyboardMode) {
                    InputMode.NUMBER, InputMode.SYMBOL -> "空格"
                    else -> keyboardMode.displayName
                },
                color = theme.functionKeyText,
                size = theme.functionKeyTextSize,
            )

            KeyType.SHIFT -> drawShiftGlyph(canvas, rect, onAccentColor)
            KeyType.BACKSPACE -> drawBackspaceGlyph(canvas, rect, theme.functionKeyText)
            KeyType.ENTER -> drawEnterGlyph(canvas, rect, theme.functionKeyText)
            KeyType.ENTER_NEWLINE -> drawFunctionLabel(
                canvas,
                rect,
                key.displayText.ifEmpty { "换行" },
            )

            // 微信的「中/英」键：不再画地球，直接显示文字
            KeyType.LANGUAGE -> drawLabel(
                canvas = canvas,
                rect = rect,
                text = "中/英",
                color = theme.functionKeyText,
                size = theme.functionKeyTextSize * 0.8f,
            )

            KeyType.TO_NUMBERS -> drawFunctionLabel(canvas, rect, key.displayText.ifEmpty { "123" })
            KeyType.TO_LETTERS -> drawFunctionLabel(canvas, rect, key.displayText.ifEmpty { "ABC" })
            KeyType.TO_SYMBOLS -> drawFunctionLabel(canvas, rect, key.displayText.ifEmpty { "符号" })
            KeyType.TO_MORE_SYMBOLS -> drawFunctionLabel(canvas, rect, "符号")
            KeyType.TO_MAIN_SYMBOLS -> drawFunctionLabel(canvas, rect, "符号")
        }
    }

    /** 单字符用正常字号，多字符（如「——」，"…"）自动降一号，避免超出按键 */
    private fun drawAdaptiveLabel(canvas: Canvas, rect: RectF, text: String, color: Int) {
        if (text.isEmpty()) return
        val size = if (text.length > 1) theme.functionKeyTextSize else theme.keyTextSize
        drawLabel(canvas, rect, text, color, size)
    }

    private fun drawFunctionLabel(canvas: Canvas, rect: RectF, text: String) {
        drawLabel(canvas, rect, text, theme.functionKeyText, theme.functionKeyTextSize)
    }

    private fun characterLabel(key: KeySpec): String {
        val text = key.text ?: return ""
        return if (text.length == 1 && text[0].isLetter()) text.uppercase() else text
    }

    private fun drawLabel(canvas: Canvas, rect: RectF, text: String, color: Int, size: Float) {
        if (text.isEmpty()) return
        textPaint.color = color
        textPaint.textSize = size
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, rect.centerX(), baseline, textPaint)
    }

    // 下面四个功能键全部用矢量绘制，避免依赖字体里是否存在 ⇧ ⌫ ↵ 🌐 这些符号
    // （Path / RectF 全部复用字段，绘制阶段零分配）
    private fun drawShiftGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfHeight = rect.height() * 0.15f
        val halfWidth = rect.width() * 0.16f

        glyphFill.color = color
        glyphStroke.color = color
        glyphStroke.strokeWidth = halfWidth * 0.62f

        val topY = cy - halfHeight
        val shoulderY = cy - halfHeight * 0.28f

        glyphPath.reset()
        glyphPath.moveTo(cx, topY)
        glyphPath.lineTo(cx - halfWidth, shoulderY)
        glyphPath.lineTo(cx + halfWidth, shoulderY)
        glyphPath.close()
        canvas.drawPath(glyphPath, glyphFill)
        canvas.drawLine(cx, shoulderY, cx, cy + halfHeight, glyphStroke)

        if (capsLock) {
            val barTop = cy + halfHeight * 1.35f
            val barBottom = barTop + glyphStroke.strokeWidth
            canvas.drawRoundRect(
                cx - halfWidth,
                barTop,
                cx + halfWidth,
                barBottom,
                glyphStroke.strokeWidth / 2f,
                glyphStroke.strokeWidth / 2f,
                glyphFill,
            )
        }
    }

    private fun drawBackspaceGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfHeight = rect.height() * 0.20f
        val halfWidth = rect.width() * 0.22f
        val notch = halfWidth * 0.62f

        glyphStroke.color = color
        glyphStroke.strokeWidth = dp(1.8f)

        glyphPath.reset()
        glyphPath.moveTo(cx - halfWidth + notch, cy - halfHeight)
        glyphPath.lineTo(cx + halfWidth, cy - halfHeight)
        glyphPath.lineTo(cx + halfWidth, cy + halfHeight)
        glyphPath.lineTo(cx - halfWidth + notch, cy + halfHeight)
        glyphPath.lineTo(cx - halfWidth, cy)
        glyphPath.close()
        canvas.drawPath(glyphPath, glyphStroke)

        val crossX = halfWidth * 0.30f
        val crossY = halfHeight * 0.42f
        canvas.drawLine(cx - crossX, cy - crossY, cx + crossX, cy + crossY, glyphStroke)
        canvas.drawLine(cx - crossX, cy + crossY, cx + crossX, cy - crossY, glyphStroke)
    }

    private fun drawEnterGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val halfHeight = rect.height() * 0.20f
        val halfWidth = rect.width() * 0.20f

        glyphStroke.color = color
        glyphStroke.strokeWidth = dp(1.8f)
        glyphFill.color = color

        val stemX = cx + halfWidth * 0.55f
        val tipX = cx - halfWidth * 0.55f
        val topY = cy - halfHeight * 0.95f
        val baseY = cy + halfHeight * 0.5f

        glyphPath.reset()
        glyphPath.moveTo(stemX, topY)
        glyphPath.lineTo(stemX, baseY)
        glyphPath.lineTo(tipX, baseY)
        canvas.drawPath(glyphPath, glyphStroke)

        glyphPath.reset()
        glyphPath.moveTo(tipX - halfHeight * 0.55f, baseY)
        glyphPath.lineTo(tipX, baseY - halfHeight * 0.55f)
        glyphPath.lineTo(tipX, baseY + halfHeight * 0.55f)
        glyphPath.close()
        canvas.drawPath(glyphPath, glyphFill)
    }

    private fun drawGlobeGlyph(canvas: Canvas, rect: RectF, color: Int) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        val radius = min(rect.width(), rect.height()) * 0.26f

        glyphStroke.color = color
        glyphStroke.strokeWidth = dp(1.7f)

        canvas.drawCircle(cx, cy, radius, glyphStroke)
        glyphRect.set(
            cx - radius * 0.46f,
            cy - radius,
            cx + radius * 0.46f,
            cy + radius,
        )
        canvas.drawOval(glyphRect, glyphStroke)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, glyphStroke)

        val offsetY = radius * 0.5f
        val halfChord = radius * 0.866f
        canvas.drawLine(cx - halfChord, cy - offsetY, cx + halfChord, cy - offsetY, glyphStroke)
        canvas.drawLine(cx - halfChord, cy + offsetY, cx + halfChord, cy + offsetY, glyphStroke)
    }

    // ---------------------------------------------------------------- 触摸

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                val index = findKeyIndex(event.x, event.y)
                if (index >= 0) {
                    longPressFired = false
                    setPressedIndex(index)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 轻微的抖动 / 滑动不换键：只有离开按下点超过 touch slop 才重新判定
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy < touchSlop * touchSlop) {
                    if (pressedIndex >= 0) return true
                }
                val index = findKeyIndex(event.x, event.y)
                if (index != pressedIndex) {
                    cancelPendingTouch()
                    longPressFired = false
                    setPressedIndex(index)
                    if (index >= 0) {
                        handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val index = pressedIndex
                val wasLongPress = longPressFired
                cancelPendingTouch()
                setPressedIndex(-1)
                if (index >= 0 && !wasLongPress) {
                    listener?.onKey(keys[index])
                }
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingTouch()
                setPressedIndex(-1)
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startBackspaceRepeat(key: KeySpec) {
        repeatRunnable?.let(handler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                listener?.onKeyRepeat(key)
                handler.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        repeatRunnable = runnable
        // 长按判定成立时先删一个，避免长按后立刻抬手漏掉一次删除
        handler.post(runnable)
    }

    private fun cancelPendingTouch() {
        handler.removeCallbacks(longPressRunnable)
        repeatRunnable?.let(handler::removeCallbacks)
        repeatRunnable = null
    }

    private fun setPressedIndex(index: Int) {
        if (pressedIndex == index) return
        pressedIndex = index
        invalidate()
    }

    /**
     * 命中判定：在**扩大后的可点区域**里挑「离手指最近的那颗键」。
     *
     * 因为相邻键的命中矩形会互相重叠，直接取第一个匹配会在边界上忽左忽右；
     * 取最近中心点既消除死区，又不会在缝里乱跳。
     */
    private fun findKeyIndex(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = Float.MAX_VALUE
        for (index in hitBounds.indices) {
            val rect = hitBounds[index]
            if (!rect.contains(x, y)) continue
            val dx = x - rect.centerX()
            val dy = y - rect.centerY()
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = index
            }
        }
        return best
    }

    override fun onDetachedFromWindow() {
        cancelPendingTouch()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val LONG_PRESS_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 55L
    }
}
