package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.board.ime.engine.Candidate
import com.board.ime.ui.KeyboardTheme

/**
 * 候选「全部展开」内容区（占键盘位置，总高度与其它状态一致）。
 *
 * 对齐微信候选栏右侧 `∨` 展开后的整页候选面板：把当前这一页候选（最多
 * [CandidateBar.MAX_CANDIDATES] 个）平铺成网格，长词 / 单字都能直接点到，
 * 不用在候选栏里横向滑很远去找「鹏 / 程」这种排在后面的单字。
 *
 * ```
 * ┌──────────┬──────────┬──────────┬──────────┐
 * │ 需彭城   │ 需       │ 须       │ 徐       │
 * ├──────────┼──────────┼──────────┼──────────┤
 * │ 许       │ …        │          │          │
 * └──────────┴──────────┴──────────┴──────────┘
 * ```
 *
 * - 点击任意格 = 选中该候选（下标与候选栏**完全一致**，走同一条 [onCandidateSelected]）；
 * - 第 0 个候选沿用「当前选中」的高亮，和候选栏保持一致的视觉；
 * - 收起由上方候选栏的 `∧` 负责（这里不重复放按钮）；
 * - 数量不大，`render` 直接重建网格，但用 key 早退，避免相同内容重复重建。
 */
class CandidateGridPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onCandidateSelected: ((index: Int) -> Unit)? = null

    private val scroll = ScrollView(context)
    private val grid = LinearLayout(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var lastKey: String? = null

    /** 最近一次渲染的候选（[applyTheme] 重绘时复用；必须在 init 之前声明） */
    private var lastCandidates: List<Candidate> = emptyList()

    init {
        orientation = VERTICAL
        grid.orientation = VERTICAL
        grid.setPadding(dp(6), dp(6), dp(6), dp(6))
        scroll.addView(grid)
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.keyboardBackground)
        lastKey = null
        renderInternal(lastCandidates)
    }

    fun render(candidates: List<Candidate>) {
        lastCandidates = candidates
        renderInternal(candidates)
    }

    private fun renderInternal(candidates: List<Candidate>) {
        val key = candidates.joinToString("|") { it.label + "\u0001" + it.englishGloss.orEmpty() }
        if (key == lastKey) return
        lastKey = key

        grid.removeAllViews()
        if (candidates.isEmpty()) return

        var row: LinearLayout? = null
        candidates.forEachIndexed { index, candidate ->
            if (index % COLUMNS == 0) {
                row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                grid.addView(
                    row,
                    LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
                )
            }
            row!!.addView(cell(index, candidate), cellParams())
        }
        // 最后一行补空位，保证每列等宽、文字左对齐位置一致
        val remainder = candidates.size % COLUMNS
        if (remainder != 0) {
            repeat(COLUMNS - remainder) {
                row!!.addView(emptyCell(), cellParams())
            }
        }
    }

    private fun cell(index: Int, candidate: Candidate): TextView = TextView(context).apply {
        text = candidate.label
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.candidateTextSize)
        setTextColor(if (index == 0) theme.candidateSelectedText else theme.candidateText)
        typeface = Typeface.DEFAULT
        includeFontPadding = false
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(theme.candidatePaddingHorizontal, 0, theme.candidatePaddingHorizontal, 0)
        minHeight = dp(44)
        background = if (index == 0) {
            GradientDrawable().apply {
                setColor(theme.candidateSelectedBackground)
                cornerRadius = theme.radiusSmall
            }
        } else {
            null
        }
        isClickable = candidate.enabled
        setOnClickListener { onCandidateSelected?.invoke(index) }
    }

    private fun emptyCell(): TextView = TextView(context).apply {
        text = ""
        isClickable = false
    }

    private fun cellParams(): LayoutParams =
        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            val gap = theme.spaceXs / 2
            setMargins(gap, gap, gap, gap)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 每行格数：900×1600 下每格约 80dp，2–4 字词 / 单字都放得下 */
        const val COLUMNS = 4
    }
}
