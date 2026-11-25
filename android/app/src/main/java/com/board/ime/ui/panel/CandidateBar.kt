package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.R
import com.board.ime.engine.Candidate
import com.board.ime.engine.InputMode
import com.board.ime.ui.KeyboardTheme
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 候选栏（CANDIDATES 状态）。
 *
 * **默认只有一行**，中英文完全同构：
 * ```
 * 中文：  [nihao] │ 你好 │ 你还 │ 你 │ 尼 │ 泥 │ 逆 …
 * 英文：  [hel]   │ help │ hello …
 * ```
 * - 正在输入的拼音 / 单词就是这一行的**第一项**（小 pill，内容自适应宽度）
 * - 整行横向可滑动；滑动不改变键盘与 TopPanel 高度
 *
 * **P3：候选可带英文释义**（设置 → 外观 → 候选英文释义）。开启后每个候选
 * 在自己的格子里显示两行：中文（主）+ 英文（辅）：
 * ```
 * [ 苹果 ]  [ 工作 ]  [ 银行 ]
 * [ apple]  [ work ]  [ bank ]
 * ```
 * - 中文与英文属于**同一个候选 View**，一起滚动、一起点击、共享同一个 index；
 * - 没有释义的候选保持单行（不显示空白第二行）；
 * - 候选格宽 = max(中文宽, 英文宽) + 左右内边距；英文不换行 / 不省略，靠横向滚动看全；
 * - 面板总高度不变：两行仍在固定的 56dp 面板内完成。
 *
 * 性能：整行子 View 只在 `init` 里创建一次，之后每次候选更新只改
 * 文本 / 颜色 / 可见性（**不再 removeAllViews + 重新创建**），
 * 因此连续快速输入时不会产生大量 View 分配与 GC。
 * 候选格宽度只在 `onMeasure`（内容 / 字号 / 开关变化）时计算，滚动与每帧绘制都不重算。
 */
class CandidateBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var onCandidateSelected: ((index: Int) -> Unit)? = null
    var onComposingClicked: (() -> Unit)? = null

    /** 点右侧「∨ / ∧」：展开 / 收起全部候选（对齐微信候选栏的展开按钮） */
    var onExpandToggled: (() -> Unit)? = null

    /** 是否处于「展开全部候选」状态（决定右侧按钮画 ∨ 还是 ∧） */
    var expanded: Boolean = false
        private set

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        invalidate()
    }

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    // 必须先于 init 声明：init 会调用 applyTheme() -> render()
    private var candidates: List<Candidate> = emptyList()
    private var composing: CharSequence = ""
    private var statusText: String? = null
    private var lastRenderedKey: String? = null
    private var lastPill: String? = null

    /** 预创建并复用的子 View（顺序固定：pill │ 候选0 │ 候选1 …） */
    private val pillView = TextView(context)
    private val firstDivider = View(context)
    private val candidateViews = ArrayList<CandidateItemView>(MAX_CANDIDATES)
    private val candidateDividers = ArrayList<View>(MAX_CANDIDATES)
    private val hintView = TextView(context)

    /** 中文候选 View：一个候选 = 一个整体 View（可含英文第二行），点击 / 滚动都不可拆分 */
    private class CandidateItemView(context: Context) : View(context) {

        private val chinesePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        private val englishPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }

        private var chineseText: String = ""
        private var englishText: String? = null
        private var horizontalPadding: Int = 0
        private var minWidthPx: Int = 0
        private var panelHeightPx: Int = 0

        fun applyStyle(
            textSizePx: Float,
            englishTextSizePx: Float,
            minWidth: Int,
            padding: Int,
            panelHeight: Int,
        ) {
            var changed = false
            if (chinesePaint.textSize != textSizePx) {
                chinesePaint.textSize = textSizePx
                changed = true
            }
            if (englishPaint.textSize != englishTextSizePx) {
                englishPaint.textSize = englishTextSizePx
                changed = true
            }
            if (minWidthPx != minWidth) {
                minWidthPx = minWidth
                changed = true
            }
            if (horizontalPadding != padding) {
                horizontalPadding = padding
                changed = true
            }
            if (panelHeightPx != panelHeight) {
                panelHeightPx = panelHeight
                changed = true
            }
            if (changed) requestLayout()
        }

        /** 更新两行文本与颜色；文本变化才 requestLayout（颜色变化只 invalidate） */
        fun setContent(
            chinese: String,
            english: String?,
            chineseColor: Int,
            englishColor: Int,
        ) {
            val normalizedEnglish = english?.takeIf { CandidateItemMetrics.showsEnglishLine(it) }
            if (chineseText != chinese || englishText != normalizedEnglish) {
                chineseText = chinese
                englishText = normalizedEnglish
                requestLayout()
            }
            chinesePaint.color = chineseColor
            englishPaint.color = englishColor
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val chineseWidth = ceil(chinesePaint.measureText(chineseText).toDouble()).toInt()
            val englishWidth = if (CandidateItemMetrics.showsEnglishLine(englishText)) {
                ceil(englishPaint.measureText(englishText!!).toDouble()).toInt()
            } else {
                0
            }
            val desiredWidth = CandidateItemMetrics.requiredWidth(
                chineseWidth = chineseWidth,
                englishWidth = englishWidth,
                horizontalPadding = horizontalPadding,
                minWidth = minWidthPx,
            )
            setMeasuredDimension(
                desiredWidth,
                resolveSize(panelHeightPx, heightMeasureSpec),
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (chineseText.isEmpty()) return

            val chineseMetrics = chinesePaint.fontMetrics
            val chineseHeight = chineseMetrics.descent - chineseMetrics.ascent
            val showEnglish = CandidateItemMetrics.showsEnglishLine(englishText)
            val englishMetrics = englishPaint.fontMetrics
            val englishHeight = if (showEnglish) {
                englishMetrics.descent - englishMetrics.ascent
            } else {
                0f
            }
            val totalHeight = chineseHeight + englishHeight
            val top = (height - totalHeight) / 2f
            val centerX = width / 2f

            canvas.drawText(chineseText, centerX, top - chineseMetrics.ascent, chinesePaint)
            if (showEnglish) {
                canvas.drawText(
                    englishText!!,
                    centerX,
                    top + chineseHeight - englishMetrics.ascent,
                    englishPaint,
                )
            }
        }
    }

    /** 选中背景：一个实例复用给所有候选（切换选中态时只是换引用，不新建 drawable） */
    private var selectedBackground: GradientDrawable? = null

    /**
     * 选中背景的「内缩版」：chip 视觉高度保持 32dp，但候选 View 撑满 56dp 面板，
     * 这样**点击区域 = 面板高度（56dp）**，而视觉仍是原来的小圆角块。
     */
    private var selectedBackgroundInset: InsetDrawable? = null

    /**
     * 两行候选的选中背景：内缩更小，圆角块包住「中文 + 英文」两行（仍不超出固定面板高度）。
     */
    private var selectedBackgroundTwoLine: InsetDrawable? = null

    /**
     * 右侧「展开全部候选」按钮：不新增 View、不使用渐变。
     *
     * 在行尾预留 [actionWidthPx] 宽的空带，在里面画一个 `∨`（展开）/ `∧`（收起）；
     * 按钮钉在**可视区右端**，点击判定见 [onTouchEvent]。不遮挡候选文字、
     * 不占用候选点击区域（空带本身不是候选）。
     */
    private val actionWidthPx = resources.getDimensionPixelSize(R.dimen.kb_candidate_action_width)
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 1.6f
        alpha = 170
    }

    /** 右侧展开按钮这一条是否正在被按下（只用于 tap 判定，不影响横向滑动） */
    private var actionTouching = false

    /** 提示底色：与面板同色的极窄平铺遮罩（无渐变），保证提示干净、不压字 */
    private val hintMaskPaint = Paint().apply { style = Paint.Style.FILL }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        row.setPadding(
            theme.panelHorizontalPadding,
            0,
            theme.panelHorizontalPadding + actionWidthPx,
            0,
        )
        addView(
            row,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        row.addView(pillView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, theme.candidateChipHeight))
        row.addView(firstDivider, dividerParams())
        repeat(MAX_CANDIDATES) { index ->
            if (index > 0) {
                val divider = View(context)
                candidateDividers += divider
                row.addView(divider, dividerParams())
            }
            val view = CandidateItemView(context)
            candidateViews += view
            row.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT),
            )
        }
        row.addView(
            hintView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT),
        )

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        row.setPadding(
            theme.panelHorizontalPadding,
            0,
            theme.panelHorizontalPadding + actionWidthPx,
            0,
        )

        selectedBackground = GradientDrawable().apply {
            setColor(theme.candidateSelectedBackground)
            // 「候选 Chip 圆角」是外观设置里的独立项；之前写死 radiusSmall 导致调了没反应
            cornerRadius = theme.candidateCornerRadius
        }
        selectedBackgroundInset = insetChip(selectedBackground!!, theme.candidateChipHeight)
        // 两行模式：内缩更小，圆角块包住两行；仍受固定面板高度约束（不会撑高）
        val twoLineHeight = (theme.topPanelHeight - theme.spaceXs * 2)
            .coerceAtLeast(theme.candidateChipHeight)
        selectedBackgroundTwoLine = insetChip(selectedBackground!!, twoLineHeight)
        actionPaint.color = theme.candidateMutedText
        hintMaskPaint.color = theme.panelBackground

        pillView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.pinyinTextSize)
            setTextColor(theme.candidateText)
            typeface = Typeface.DEFAULT
            includeFontPadding = false
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(theme.candidatePaddingHorizontal, 0, theme.candidatePaddingHorizontal, 0)
            background = insetChip(
                GradientDrawable().apply {
                    setColor(theme.pinyinBackground)
                    cornerRadius = theme.candidateCornerRadius
                },
                theme.candidateChipHeight,
            )
            isClickable = true
            setOnClickListener { onComposingClicked?.invoke() }
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                // 视觉仍是 32dp pill（背景内缩），点击区域撑满 56dp 面板
                height = LinearLayout.LayoutParams.MATCH_PARENT
            }
        }

        candidateViews.forEach { view ->
            view.applyStyle(
                textSizePx = theme.candidateTextSize,
                englishTextSizePx = theme.candidateEnglishTextSize,
                minWidth = theme.candidateMinWidth,
                padding = theme.candidatePaddingHorizontal,
                panelHeight = theme.topPanelHeight,
            )
            view.layoutParams = (view.layoutParams as LinearLayout.LayoutParams).apply {
                // 点击区域撑满面板；视觉高度由选中背景的内缩控制
                height = LinearLayout.LayoutParams.MATCH_PARENT
            }
            view.isClickable = true
            view.setOnClickListener { onCandidateSelected?.invoke(view.tag as? Int ?: -1) }
        }
        hintView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.functionKeyTextSize)
            setTextColor(theme.candidateMutedText)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        candidateDividers.forEach { divider ->
            divider.setBackgroundColor(theme.divider)
            val params = divider.layoutParams as LinearLayout.LayoutParams
            params.width = dividerWidth()
            params.height = dividerHeight()
        }
        firstDivider.setBackgroundColor(theme.divider)
        val firstParams = firstDivider.layoutParams as LinearLayout.LayoutParams
        firstParams.width = dividerWidth()
        firstParams.height = dividerHeight()

        lastRenderedKey = null
        render(candidates, InputMode.ENGLISH, composing, statusText)
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val desired = resolveSize(theme.topPanelHeight, heightMeasureSpec)
        if (measuredHeight != desired) {
            setMeasuredDimension(measuredWidth, desired)
        }
    }

    /**
     * @param candidates 候选词（第一个视为「当前选中」）
     * @param mode 当前输入状态（保留参数：中英文现在共用同一行布局）
     * @param composing 正在输入的内容（拼音 / 单词）
     * @param statusText 引擎状态提示，仅在没有候选时显示
     */
    fun render(
        candidates: List<Candidate>,
        mode: InputMode,
        composing: CharSequence,
        statusText: String?,
    ) {
        this.candidates = candidates
        this.composing = composing
        this.statusText = statusText
        renderInternal()
    }

    fun clear() {
        candidates = emptyList()
        composing = ""
        statusText = null
        lastRenderedKey = null
        renderInternal()
    }

    private fun renderInternal() {
        val list = candidates.orEmpty()
        val hint = statusText

        // 正在输入的内容作为第一项；如果它已经等于第一个候选（英文模式）就不再重复
        val composingValue = composing.toString()
        val pill = composingValue.takeIf {
            it.isNotEmpty() && list.firstOrNull()?.text != it
        }

        // 释义也进入 key：开关 / 释义变化时才会重绘，普通刷新（相同内容）仍然早退
        val key = (pill ?: "") + "|" +
            list.joinToString("|") { it.label + "\u0001" + it.englishGloss.orEmpty() } +
            "#" + hint.orEmpty()
        if (key == lastRenderedKey) return
        lastRenderedKey = key

        // ---- 拼音 pill ----
        val showPill = pill != null
        pillView.visibility = if (showPill) VISIBLE else GONE
        if (pill != null) pillView.text = pill
        firstDivider.visibility = if (showPill && list.isNotEmpty()) VISIBLE else GONE

        // ---- 候选 ----
        val shown = list.take(MAX_CANDIDATES)
        for (index in candidateViews.indices) {
            val view = candidateViews[index]
            val candidate = shown.getOrNull(index)
            val divider = candidateDividers.getOrNull(index - 1)
            if (candidate == null) {
                view.visibility = GONE
                view.tag = null
                divider?.visibility = GONE
                continue
            }
            if (divider != null) divider.visibility = VISIBLE
            view.visibility = VISIBLE
            // 候选 index：与引擎给出的候选列表下标完全一致（英文释义不参与）
            view.tag = index
            val selected = index == 0 && !candidate.isHint
            val chineseColor = when {
                candidate.isHint -> theme.candidateMutedText
                selected -> theme.candidateSelectedText
                else -> theme.candidateText
            }
            // 中文（label）与英文（englishGloss）属于同一个 View：一起绘制 / 一起点击
            view.setContent(
                chinese = candidate.label,
                english = candidate.englishGloss,
                chineseColor = chineseColor,
                englishColor = theme.candidateMutedText,
            )
            val twoLine = CandidateItemMetrics.showsEnglishLine(candidate.englishGloss)
            view.background = when {
                selected && twoLine -> selectedBackgroundTwoLine
                selected -> selectedBackgroundInset
                else -> null
            }
            view.isClickable = candidate.enabled
        }

        // ---- 状态提示（只在没有候选时）----
        hintView.visibility = if (list.isEmpty() && !hint.isNullOrBlank()) VISIBLE else GONE
        if (!hint.isNullOrBlank()) hintView.text = hint

        // 只有「正在输入的内容」变化时才回到行首，避免每次候选更新都触发滚动
        if (pill != lastPill) {
            lastPill = pill
            scrollTo(0, 0)
        }
    }

    private fun dividerParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(dividerWidth(), dividerHeight()).apply {
            gravity = Gravity.CENTER_VERTICAL
            // 候选间距收紧到 spaceXs：一屏尽量多显示几个候选（对齐搜狗/讯飞
            // 一屏 9 个候选的密度），总量提升仍靠“一页更多候选 + 横向滑动”。
            val gap = theme.spaceXs
            setMargins(gap, 0, gap, 0)
        }

    /** 把 chip 背景上下内缩到 [visualHeight]：视觉高度受控，但 View 本身撑满面板（点击区域更大） */
    private fun insetChip(shape: GradientDrawable, visualHeight: Int): InsetDrawable {
        val inset = ((theme.topPanelHeight - visualHeight) / 2).coerceAtLeast(0)
        return InsetDrawable(shape, 0, inset, 0, inset)
    }

    /**
     * 右侧滚动提示：只有「还能向右滚」时才画一个极简 `›`。
     *
     * 状态每次绘制时从 [canScrollHorizontally] 现算，因此：滑到最右自动消失、
     * 回到最左自动重现、候选内容变化后也自动正确，无需额外状态同步。
     * 提示画在行尾预留的空带里，不覆盖候选文字、不占用候选点击区域。
     */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (candidates.isEmpty()) return
        // 右侧固定「展开全部候选」按钮：同色窄遮罩 + 一个 ∨（展开）/ ∧（收起）。
        // 位置钉在**可视区右端**（不随横向滚动移动），点击判定见 onTouchEvent。
        val stripRight = (scrollX + width).toFloat()
        val stripLeft = stripRight - theme.panelHorizontalPadding - actionWidthPx
        canvas.drawRect(stripLeft, 0f, stripRight, height.toFloat(), hintMaskPaint)
        val cx = stripLeft + actionWidthPx / 2f
        val cy = height / 2f
        val dx = actionWidthPx * 0.18f
        val dy = height * 0.11f
        if (expanded) {
            canvas.drawLine(cx - dx, cy + dy, cx, cy - dy, actionPaint)
            canvas.drawLine(cx, cy - dy, cx + dx, cy + dy, actionPaint)
        } else {
            canvas.drawLine(cx - dx, cy - dy, cx, cy + dy, actionPaint)
            canvas.drawLine(cx, cy + dy, cx + dx, cy - dy, actionPaint)
        }
    }

    /**
     * 右侧按钮的点击判定。
     *
     * 按钮画在 [dispatchDraw] 的保留条里、不是子 View；而候选格子在 `scrollX = 0` 时会一直
     * 铺到右端（行尾 padding 在**可滚动内容**的末尾，不在可视区右侧），所以必须在这里
     * **先于子 View** 拦截：落在条内 → 展开 / 收起；其余位置 → 原样交给
     * [HorizontalScrollView]（横向滑动不受影响）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val inActionStrip = candidates.isNotEmpty() &&
            ev.x >= width - theme.panelHorizontalPadding - actionWidthPx
        if (inActionStrip) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    actionTouching = true
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (actionTouching) {
                        actionTouching = false
                        onExpandToggled?.invoke()
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> actionTouching = false
            }
            return true
        }
        actionTouching = false
        return super.dispatchTouchEvent(ev)
    }

    private fun dividerWidth(): Int =
        resources.displayMetrics.density.roundToInt().coerceAtLeast(1)

    private fun dividerHeight(): Int = (theme.candidateChipHeight * 0.55f).roundToInt()

    private companion object {
        /**
         * 候选栏**单行**最多渲染多少个候选（横向滑动的上限，不是屏幕同时可见的数量）。
         *
         * 可见数量由屏幕宽度决定（约 6～8 个），这里决定的是"横向滑一滑还能看到多少"。
         * 底层 Rime 的 `menu/page_size` 已设为 200（「全部展开」面板一次性铺出更多候选），
         * 但横向单行铺 200 个既不现实也没必要，这里取 50：前 50 个横向滑动，
         * 想要看更多就点右侧 ∨ 展开成网格（CandidateGridPanel 渲染全部 200 个）。
         */
        const val MAX_CANDIDATES = 50
    }
}
