package com.board.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
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
import com.board.ime.keyboard.SpaceCursorDrag
import com.board.ime.keyboard.SwipeDownGesture
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

        /** 长按（语言键打开输入法选择器 / 标点键出句号 / 回车键换行） */
        fun onKeyLongPress(key: KeySpec)

        /** 连续触发（目前用于退格的连续删除） */
        fun onKeyRepeat(key: KeySpec)

        /** 字母键按住下滑（z / x / c / v）：全选 / 剪切 / 复制 / 粘贴 */
        fun onKeySwipeDown(key: KeySpec)

        /** 标点键按住上滑：输入句号（中文「。」/ 英文「.」） */
        fun onKeySwipeUp(key: KeySpec)

        /**
         * 空格键左右滑动移动光标。
         *
         * @param direction -1 = 左，+1 = 右
         * @param steps 本次要移动的字符数（≥1）
         */
        fun onCursorMove(direction: Int, steps: Int)
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

    /**
     * 底层语言是否为中文。「中/EN」键的深浅按它显示：
     * 符号层里当前层不是字母，但语言键仍要反映中 / 英状态。
     */
    var languageIsChinese: Boolean = true
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

    /** 合并块的外接矩形：stackId → 整块范围（数字层左侧 @ % ？ 连成一块） */
    private val stackRects = HashMap<Int, RectF>()

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

    /**
     * 空格键横向滑动 → 移动光标。
     *
     * 参考微信输入法实测（死区 50dp / 每 33dp 一字符），但实测手感偏钝：
     * 要滑很长一段才开始移动。Board 收紧为死区 24dp / 每 20dp 一字符，
     * 激活更快、每个字符需要的位移更短。
     */
    private val spaceCursorDrag = SpaceCursorDrag(
        deadZonePx = dp(SPACE_CURSOR_DEAD_ZONE_DP),
        stepPx = dp(SPACE_CURSOR_STEP_DP),
    )

    /**
     * 字母键「按住下滑」手势：z 全选、x 剪切、c 复制、v 粘贴。
     * 判定成立后锁定当前键，不再换键、不再长按，抬手也不输入字母。
     */
    private val letterSwipeDown = SwipeDownGesture(triggerPx = dp(SWIPE_DOWN_TRIGGER_DP))

    /** 标点键「按住上滑」：输入句号 */
    private val punctuationSwipeUp = SwipeDownGesture(
        triggerPx = dp(SWIPE_UP_TRIGGER_DP),
        upward = true,
    )

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
        stackRects.clear()

        val width = width
        if (width <= 0) return

        val metrics = resolvedMetrics()
        val padH = metrics.paddingHorizontalPx
        val contentWidth = width - padH * 2f
        if (contentWidth <= 0f) return

        // 命中区域向外扩半个间隙 → 键与键之间不再有「点了没反应」的死区
        val expandX = metrics.keyGapHorizontalPx / 2f
        val expandY = metrics.keyGapVerticalPx / 2f
        // 功能键宽度倍率：只影响字母 / 符号层；数字九宫格固定列宽，必须上下对齐
        val fnRatio = if (keyboardLayout.ignoresFunctionKeyRatio) 1f else metrics.functionKeyWidthRatio

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

        // 竖列合并块：同一 stackId 的键合成一个外接矩形
        for (index in keys.indices) {
            val id = keys[index].stackId
            if (id == 0) continue
            val rect = bounds[index]
            val existing = stackRects[id]
            if (existing == null) stackRects[id] = RectF(rect) else existing.union(rect)
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

        // 合并块先画整块底色 / 分隔线，再按普通流程画每格的文字
        if (stackRects.isNotEmpty()) drawStackBackgrounds(canvas, radius)

        for (index in keys.indices) {
            val key = keys[index]
            val rect = bounds[index]
            if (rect.width() <= 0f) continue

            if (key.stackId == 0) {
                keyPaint.color = backgroundColorFor(key, pressed = index == pressedIndex)
                canvas.drawRoundRect(rect, radius, radius, keyPaint)
            }
            drawKeyContent(canvas, key, rect)
        }
    }

    /**
     * 合并块：整块画成一张圆角卡片，格与格之间只留一条细分隔线。
     * 按下某一格时只填充那一格，并裁剪到整块圆角内，避免盖住外圈圆角。
     */
    private fun drawStackBackgrounds(canvas: Canvas, radius: Float) {
        val dividerHeight = theme.dividerHeight.coerceAtLeast(1).toFloat()

        for (id in stackRects.keys) {
            val union = stackRects[id] ?: continue
            keyPaint.color = theme.keyBackground
            canvas.drawRoundRect(union, radius, radius, keyPaint)

            keys.forEachIndexed { index, key ->
                if (key.stackId != id || index != pressedIndex) return@forEachIndexed
                val rect = bounds[index]
                glyphPath.reset()
                glyphPath.addRoundRect(union, radius, radius, Path.Direction.CW)
                canvas.save()
                canvas.clipPath(glyphPath)
                keyPaint.color = theme.keyPressedBackground
                canvas.drawRect(rect, keyPaint)
                canvas.restore()
            }

            var previousBottom = Float.NaN
            keys.forEachIndexed { index, key ->
                if (key.stackId != id) return@forEachIndexed
                val rect = bounds[index]
                if (!previousBottom.isNaN()) {
                    val mid = (previousBottom + rect.top) / 2f
                    keyPaint.color = theme.divider
                    canvas.drawRect(
                        rect.left,
                        mid - dividerHeight / 2f,
                        rect.right,
                        mid + dividerHeight / 2f,
                        keyPaint,
                    )
                }
                previousBottom = rect.bottom
            }
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

            // 微信字母层底行的「，。」：主标点是逗号，上方小号浅色句号提示上滑输入
            KeyType.PUNCTUATION -> {
                val chinese = keyboardMode == InputMode.CHINESE
                val cx = rect.centerX()
                drawCenteredText(
                    canvas = canvas,
                    cx = cx,
                    cy = rect.centerY() - rect.height() * 0.17f,
                    text = if (chinese) "。" else ".",
                    color = dimmedColor(theme.keyText, PERIOD_HINT_ALPHA),
                    size = theme.keyTextSize * 0.5f,
                )
                drawCenteredText(
                    canvas = canvas,
                    cx = cx,
                    cy = rect.centerY() + rect.height() * 0.15f,
                    text = if (chinese) "，" else ",",
                    color = theme.keyText,
                    size = theme.keyTextSize,
                )
            }

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

            // 「中/EN」：当前语言的半区用正常文字色，另一半用同一颜色降透明度
            KeyType.LANGUAGE -> drawLanguageLabel(canvas, rect)

            KeyType.TO_NUMBERS -> drawFunctionLabel(canvas, rect, key.displayText.ifEmpty { "123" })
            KeyType.TO_LETTERS -> drawFunctionLabel(canvas, rect, key.displayText.ifEmpty { "ABC" })
            KeyType.TO_SYMBOLS -> drawFunctionLabel(canvas, rect, key.displayText.ifEmpty { "符号" })
            KeyType.TO_MORE_SYMBOLS -> drawFunctionLabel(canvas, rect, "符号")
            KeyType.TO_EXTRA_SYMBOLS -> drawFunctionLabel(canvas, rect, "符号")
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

    /** 以 (cx, cy) 为中心绘制一段文字（保持 textPaint 的居中对齐） */
    private fun drawCenteredText(canvas: Canvas, cx: Float, cy: Float, text: String, color: Int, size: Float) {
        textPaint.color = color
        textPaint.textSize = size
        val metrics = textPaint.fontMetrics
        canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2f, textPaint)
    }

    /**
     * 「中/EN」键：当前语言的半区用正常功能键文字色，另一半同色降透明度。
     * 透明度变化只会让颜色更浅，不会比整体文字更深；[LANGUAGE_WEAK_ALPHA]
     * 保留一半不透明度，深色 / 浅色主题下都还能看清。
     */
    private fun drawLanguageLabel(canvas: Canvas, rect: RectF) {
        val size = theme.functionKeyTextSize * 0.8f
        val strong = theme.functionKeyText
        val weak = dimmedColor(strong, LANGUAGE_WEAK_ALPHA)
        val zh = "中"
        val slash = "/"
        val en = "EN"

        textPaint.textSize = size
        textPaint.textAlign = Paint.Align.LEFT
        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        val wZh = textPaint.measureText(zh)
        val wSlash = textPaint.measureText(slash)
        val wEn = textPaint.measureText(en)
        var x = rect.centerX() - (wZh + wSlash + wEn) / 2f

        textPaint.color = if (languageIsChinese) strong else weak
        canvas.drawText(zh, x, baseline, textPaint)
        x += wZh
        textPaint.color = weak
        canvas.drawText(slash, x, baseline, textPaint)
        x += wSlash
        textPaint.color = if (languageIsChinese) weak else strong
        canvas.drawText(en, x, baseline, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
    }

    /** 同色降低不透明度（只会更浅，不会比原色更深） */
    private fun dimmedColor(color: Int, alpha: Float): Int = Color.argb(
        (Color.alpha(color) * alpha).toInt(),
        Color.red(color),
        Color.green(color),
        Color.blue(color),
    )

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
                spaceCursorDrag.reset()
                letterSwipeDown.reset()
                punctuationSwipeUp.reset()
                val index = findKeyIndex(event.x, event.y)
                if (index >= 0) {
                    longPressFired = false
                    setPressedIndex(index)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_DELAY_MS)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                // 空格键横向滑动：移动光标（判定成立后锁定空格键，不再换键、不再长按）
                if (keys.getOrNull(pressedIndex)?.type == KeyType.SPACE) {
                    val move = spaceCursorDrag.onMove(event.x - downX, event.y - downY)
                    if (spaceCursorDrag.isActive) {
                        cancelPendingTouch()
                        emitCursorMove(move)
                        return true
                    }
                }

                // 标点键按住上滑：句号（判定成立后锁定，抬手不再输入逗号）
                if (punctuationSwipeUp.isActive) return true
                val punctuationKey = keys.getOrNull(pressedIndex)
                if (punctuationKey != null && punctuationKey.type == KeyType.PUNCTUATION) {
                    if (punctuationSwipeUp.onMove(event.x - downX, event.y - downY)) {
                        cancelPendingTouch()
                        listener?.onKeySwipeUp(punctuationKey)
                        return true
                    }
                }

                // 字母键按住下滑：z / x / c / v（全选 / 剪切 / 复制 / 粘贴）
                if (letterSwipeDown.isActive) return true
                val downKey = keys.getOrNull(pressedIndex)
                if (downKey != null && downKey.type == KeyType.CHARACTER &&
                    downKey.text in SWIPE_DOWN_LETTERS
                ) {
                    if (letterSwipeDown.onMove(event.x - downX, event.y - downY)) {
                        cancelPendingTouch()
                        listener?.onKeySwipeDown(downKey)
                        return true
                    }
                }

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
                // 滑动移动光标后抬手：不能再输入一个空格
                val wasCursorDrag = spaceCursorDrag.isActive
                // 下滑快捷操作触发后抬手：不能再输入这个字母
                val wasSwipeDown = letterSwipeDown.isActive
                // 上滑句号触发后抬手：不能再输入逗号
                val wasSwipeUp = punctuationSwipeUp.isActive
                // 抬手位置也算进去：最后一个 MOVE 与终点差几像素时不会少走一格
                if (wasCursorDrag) {
                    emitCursorMove(spaceCursorDrag.onMove(event.x - downX, event.y - downY))
                }
                cancelPendingTouch()
                setPressedIndex(-1)
                spaceCursorDrag.reset()
                letterSwipeDown.reset()
                punctuationSwipeUp.reset()
                if (index >= 0 && !wasLongPress && !wasCursorDrag && !wasSwipeDown && !wasSwipeUp) {
                    listener?.onKey(keys[index])
                }
                performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingTouch()
                setPressedIndex(-1)
                spaceCursorDrag.reset()
                letterSwipeDown.reset()
                punctuationSwipeUp.reset()
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

    /** 把「本次要移动的字符数」转成回调（0 表示不用动） */
    private fun emitCursorMove(steps: Int) {
        if (steps == 0) return
        listener?.onCursorMove(if (steps > 0) 1 else -1, if (steps > 0) steps else -steps)
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

        /**
         * 空格键横向滑动移动光标：死区 24dp（以内算点按 → 输入空格）。
         * 微信是 50dp；这里调小，让滑动更快进入移动状态。
         */
        const val SPACE_CURSOR_DEAD_ZONE_DP = 24f

        /** 越过死区后每 20dp 移动 1 个字符（微信是 33dp；这里调小以提高灵敏度） */
        const val SPACE_CURSOR_STEP_DP = 20f

        /** 字母键按住下滑的触发距离：竖直位移超过 28dp 才触发（避免打字时误触） */
        const val SWIPE_DOWN_TRIGGER_DP = 28f

        /** 标点键按住上滑的触发距离：24dp（比下滑略短，句号是高频操作） */
        const val SWIPE_UP_TRIGGER_DP = 24f

        /** 逗号键上方的句号提示：同色半透明，浅一点但仍可辨认 */
        const val PERIOD_HINT_ALPHA = 0.55f

        /** 「中/EN」未激活的一半：保留 50% 不透明度，浅但不至于看不见 */
        const val LANGUAGE_WEAK_ALPHA = 0.5f

        /** 支持下滑快捷操作的字母键：z 全选 / x 剪切 / c 复制 / v 粘贴 */
        val SWIPE_DOWN_LETTERS = setOf("z", "x", "c", "v")
    }
}
