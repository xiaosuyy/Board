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
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.R
import com.board.ime.engine.Candidate
import com.board.ime.engine.InputMode
import com.board.ime.ui.KeyboardTheme
import kotlin.math.roundToInt

/**
 * 候选栏（CANDIDATES 状态）。
 *
 * **只有一行**，中英文完全同构：
 * ```
 * 中文：  [nihao] │ 你好 │ 你还 │ 你 │ 尼 │ 泥 │ 逆 …
 * 英文：  [hel]   │ help │ hello …
 * ```
 * - 正在输入的拼音 / 单词就是这一行的**第一项**（小 pill，内容自适应宽度）
 * - 整行横向可滑动；滑动不改变键盘与 TopPanel 高度
 *
 * 性能：整行子 View 只在 `init` 里创建一次，之后每次候选更新只改
 * 文本 / 颜色 / 可见性（**不再 removeAllViews + 重新创建**），
 * 因此连续快速输入时不会产生大量 View 分配与 GC。
 */
class CandidateBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : HorizontalScrollView(context, attrs, defStyleAttr) {

    var onCandidateSelected: ((index: Int) -> Unit)? = null
    var onComposingClicked: (() -> Unit)? = null

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
    private val candidateViews = ArrayList<TextView>(MAX_CANDIDATES)
    private val candidateDividers = ArrayList<View>(MAX_CANDIDATES)
    private val hintView = TextView(context)

    /** 选中背景：一个实例复用给所有候选（切换选中态时只是换引用，不新建 drawable） */
    private var selectedBackground: GradientDrawable? = null

    /**
     * 选中背景的「内缩版」：chip 视觉高度保持 32dp，但候选 View 撑满 56dp 面板，
     * 这样**点击区域 = 面板高度（56dp）**，而视觉仍是原来的小圆角块。
     */
    private var selectedBackgroundInset: InsetDrawable? = null

    /**
     * 右侧「还有内容」提示：不新增 View、不使用渐变。
     *
     * 在行尾预留 [hintWidthPx] 宽的空带，仅当还能向右滚动时画一个极简的 `›`，
     * 提示落在空带里（不遮挡候选文字、不占用候选点击区域）。
     */
    private val hintWidthPx = resources.getDimensionPixelSize(R.dimen.kb_candidate_hint_width)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = resources.displayMetrics.density * 1.6f
        alpha = 150
    }

    /** 提示底色：与面板同色的极窄平铺遮罩（无渐变），保证提示干净、不压字 */
    private val hintMaskPaint = Paint().apply { style = Paint.Style.FILL }

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        row.setPadding(
            theme.panelHorizontalPadding,
            0,
            theme.panelHorizontalPadding + hintWidthPx,
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
            val view = TextView(context)
            candidateViews += view
            row.addView(
                view,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, theme.candidateChipHeight),
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
            theme.panelHorizontalPadding + hintWidthPx,
            0,
        )

        selectedBackground = GradientDrawable().apply {
            setColor(theme.candidateSelectedBackground)
            cornerRadius = theme.radiusSmall
        }
        selectedBackgroundInset = insetChip(selectedBackground!!)
        hintPaint.color = theme.candidateMutedText
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
                    cornerRadius = theme.radiusSmall
                },
            )
            isClickable = true
            setOnClickListener { onComposingClicked?.invoke() }
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                // 视觉仍是 32dp pill（背景内缩），点击区域撑满 56dp 面板
                height = LinearLayout.LayoutParams.MATCH_PARENT
            }
        }

        candidateViews.forEach { view ->
            view.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.candidateTextSize)
                includeFontPadding = false
                gravity = Gravity.CENTER
                minWidth = theme.candidateMinWidth
                maxLines = 1
                setPadding(theme.candidatePaddingHorizontal, 0, theme.candidatePaddingHorizontal, 0)
                layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                    // 点击区域撑满面板；视觉高度由选中背景的内缩控制（仍为 32dp chip）
                    height = LinearLayout.LayoutParams.MATCH_PARENT
                }
                setOnClickListener { onCandidateSelected?.invoke(tag as? Int ?: -1) }
            }
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

        val key = (pill ?: "") + "|" + list.joinToString("|") { it.label } + "#" + hint.orEmpty()
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
            view.tag = index
            view.text = candidate.label
            val selected = index == 0 && !candidate.isHint
            view.setTextColor(
                when {
                    candidate.isHint -> theme.candidateMutedText
                    selected -> theme.candidateSelectedText
                    else -> theme.candidateText
                },
            )
            view.background = if (selected) selectedBackgroundInset else null
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
            // 候选间距沿用设计值（不再收紧）：单屏可见数交给字号与滑动，
            // 总量提升靠“一页更多候选 + 横向滑动”，而不是把间距压小
            val gap = theme.spaceS
            setMargins(gap, 0, gap, 0)
        }

    /** 把 chip 背景上下内缩：视觉高度仍是 chip 高度，但 View 本身撑满面板（点击区域更大） */
    private fun insetChip(shape: GradientDrawable): InsetDrawable {
        val inset = ((theme.topPanelHeight - theme.candidateChipHeight) / 2).coerceAtLeast(0)
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
        if (!canScrollHorizontally(1)) return
        // 极窄同色遮罩 + 一个极简 `›`：状态每次绘制现算（滑到最右即自动消失）
        val stripRight = (scrollX + width).toFloat()
        val stripLeft = stripRight - theme.panelHorizontalPadding - hintWidthPx
        canvas.drawRect(stripLeft, 0f, stripRight, height.toFloat(), hintMaskPaint)
        val cx = stripLeft + hintWidthPx / 2f
        val cy = height / 2f
        val dx = hintWidthPx * 0.16f
        val dy = height * 0.13f
        canvas.drawLine(cx - dx, cy - dy, cx + dx, cy, hintPaint)
        canvas.drawLine(cx - dx, cy + dy, cx + dx, cy, hintPaint)
    }

    private fun dividerWidth(): Int =
        resources.displayMetrics.density.roundToInt().coerceAtLeast(1)

    private fun dividerHeight(): Int = (theme.candidateChipHeight * 0.55f).roundToInt()

    private companion object {
        /** librime 一页最多 9 个，这里留足余量；多出来的隐藏复用 */
        /**
         * 候选栏**最多渲染多少个候选**（可横向滑动的总数，不是屏幕上同时可见的数量）。
         *
         * 可见数量由屏幕宽度决定（约 6～8 个），这里决定的是"滑一滑还能看到多少"。
         * 底层由 Rime 的 `menu/page_size` 提供候选（App 生成的 patch 里设为 50），
         * 这里保持一致：把整页候选都渲染出来，用户横向滑动即可看全。
         */
        const val MAX_CANDIDATES = 50
    }
}
