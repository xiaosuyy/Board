package com.board.ime.ui.panel

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.board.ime.translation.TranslationLanguage
import com.board.ime.translation.TranslationUiState
import com.board.ime.ui.KeyboardTheme

/**
 * 翻译内容区（占键盘的位置，因此总高度与其它状态一致）。
 *
 * ```
 * 自动检测 → English                 ← 固定
 * ┌──────────────────────────────┐
 * │ 原文                          │  ← 可纵向滚动
 * │ 你好，很高兴认识你             │
 * │ ──────────────               │
 * │ 译文                          │
 * │ Hello, nice to meet you.     │
 * └──────────────────────────────┘
 *         [复制]  [替换]            ← 固定底部操作栏
 * ```
 *
 * 关键约束：
 * - **操作栏固定在底部**：正文怎么滚都不会把它带走；
 * - **字号优先可读**（原文 15sp / 译文 17sp 起，任何情况不低于 14sp），
 *   长文本靠**滚动**解决，不靠无限缩小字号；
 * - 没有有效译文时「复制 / 替换」是 **disabled**，且回调里再挡一次，杜绝空提交。
 */
class TranslationPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onTranslate: (() -> Unit)? = null
    var onCopy: (() -> Unit)? = null
    var onReplace: (() -> Unit)? = null
    var onCycleSource: (() -> Unit)? = null
    var onCycleTarget: (() -> Unit)? = null
    var onOpenSettings: (() -> Unit)? = null

    private val languageRow = LinearLayout(context)
    private val sourceChip = TextView(context)
    private val arrowView = TextView(context)
    private val targetChip = TextView(context)

    private val scroll = ScrollView(context)
    private val content = LinearLayout(context)
    private val sourceHeading = TextView(context)
    private val sourceView = TextView(context)
    private val divider = android.view.View(context)
    private val resultHeading = TextView(context)
    private val resultView = TextView(context)

    private val actionRow = LinearLayout(context)
    private val copyButton = TextView(context)
    private val replaceButton = TextView(context)
    private val primaryButton = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var uiState: TranslationUiState = TranslationUiState.IDLE
    private var currentResult: String = ""

    // 最近一次渲染的入参。**必须在 init 之前声明**：init 末尾的 applyTheme() 会用到它们，
    // 否则字段尚未初始化就被当成 null 交给 render（非空参数收到 null → 直接崩）。
    private var lastState: TranslationUiState = TranslationUiState.IDLE
    private var lastSourceText: String = ""
    private var lastResultText: String = ""
    private var lastSource: TranslationLanguage = TranslationLanguage.AUTO
    private var lastTarget: TranslationLanguage = TranslationLanguage.ENGLISH
    private var lastMessage: String = ""

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(8))

        // ---------------- 语言行（固定） ----------------
        languageRow.orientation = HORIZONTAL
        languageRow.gravity = Gravity.CENTER_VERTICAL
        arrowView.apply {
            text = "→"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        languageRow.addView(sourceChip, wrap())
        languageRow.addView(arrowView, wrap())
        languageRow.addView(targetChip, wrap())
        addView(languageRow, matchWrap())

        // ---------------- 正文（可滚动） ----------------
        content.orientation = VERTICAL
        sourceHeading.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
        }
        resultHeading.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            visibility = GONE
        }
        sourceView.apply {
            includeFontPadding = false
            setLineSpacing(0f, 1.15f)
        }
        resultView.apply {
            includeFontPadding = false
            setLineSpacing(0f, 1.2f)
            setTextIsSelectable(true)
        }
        content.addView(sourceHeading, matchWrap())
        content.addView(sourceView, matchWrap().apply { topMargin = dp(2) })
        content.addView(divider, matchFixed(dp(1)).apply { topMargin = dp(10) })
        content.addView(resultHeading, matchWrap().apply { topMargin = dp(10) })
        content.addView(resultView, matchWrap().apply { topMargin = dp(2) })
        scroll.addView(content, matchWrap())
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = true
        addView(scroll, matchWeight())

        // ---------------- 固定底部操作栏 ----------------
        actionRow.orientation = HORIZONTAL
        actionRow.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        copyButton.text = "复制"
        replaceButton.text = "替换"
        actionRow.addView(copyButton, buttonParams())
        actionRow.addView(replaceButton, buttonParams())
        actionRow.addView(primaryButton, buttonParams())
        addView(
            actionRow,
            matchWrap().apply { topMargin = dp(8) },
        )

        // 监听器只挂一次；是否可点由状态决定（并且回调里再挡一次空提交）
        sourceChip.setOnClickListener { onCycleSource?.invoke() }
        targetChip.setOnClickListener { onCycleTarget?.invoke() }
        copyButton.setOnClickListener { if (canAct()) onCopy?.invoke() }
        replaceButton.setOnClickListener { if (canAct()) onReplace?.invoke() }
        primaryButton.setOnClickListener {
            if (uiState == TranslationUiState.NOT_CONFIGURED) {
                onOpenSettings?.invoke()
            } else {
                onTranslate?.invoke()
            }
        }

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.keyboardBackground)
        arrowView.setTextColor(theme.candidateMutedText)
        sourceHeading.setTextColor(theme.candidateMutedText)
        resultHeading.setTextColor(theme.candidateMutedText)
        sourceView.setTextColor(theme.candidateMutedText)
        resultView.setTextColor(theme.candidateText)
        divider.setBackgroundColor(theme.divider)
        styleChip(sourceChip)
        styleChip(targetChip)
        styleButton(copyButton, primary = false)
        styleButton(replaceButton, primary = false)
        styleButton(primaryButton, primary = false)
        render(lastState, lastSourceText, lastResultText, lastSource, lastTarget, lastMessage)
        invalidate()
    }

    fun render(
        state: TranslationUiState,
        sourceText: String,
        resultText: String,
        source: TranslationLanguage,
        target: TranslationLanguage,
        message: String,
    ) {
        uiState = state
        currentResult = resultText
        lastState = state
        lastSourceText = sourceText
        lastResultText = resultText
        lastSource = source
        lastTarget = target
        lastMessage = message

        sourceChip.text = source.label
        targetChip.text = target.label

        val shownSource = if (sourceText.isBlank()) "（输入框里没有可翻译的文本）" else sourceText
        sourceView.text = shownSource
        sourceView.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySp(SOURCE_BASE_SP, shownSource))

        var primary: String? = null
        when (state) {
            TranslationUiState.IDLE -> {
                resultHeading.visibility = GONE
                resultView.text = ""
                primary = "翻译"
            }

            TranslationUiState.LOADING -> {
                resultHeading.visibility = GONE
                resultView.text = "翻译中…"
            }

            TranslationUiState.RESULT -> {
                resultHeading.visibility = VISIBLE
                resultView.text = resultText
            }

            TranslationUiState.ERROR -> {
                resultHeading.visibility = GONE
                resultView.text = message
                primary = "重试"
            }

            TranslationUiState.NOT_CONFIGURED -> {
                resultHeading.visibility = GONE
                resultView.text = "请先配置翻译服务"
                primary = "去设置"
            }
        }

        sourceHeading.text = "原文"
        resultHeading.text = "译文"
        resultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, bodySp(RESULT_BASE_SP, resultText))

        primaryButton.visibility = if (primary == null) GONE else VISIBLE
        primary?.let { primaryButton.text = it }

        // 没有有效译文：复制 / 替换 一律 disabled
        setButtonEnabled(copyButton, canAct())
        setButtonEnabled(replaceButton, canAct())

        // 新结果 / 新状态都从顶部开始看
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /** 有「有效译文」才允许复制 / 替换 */
    private fun canAct(): Boolean =
        uiState == TranslationUiState.RESULT && currentResult.isNotEmpty()

    /**
     * 正文字号：优先可读。
     *
     * 长文本靠**滚动**解决，不靠无限缩小字号；任何情况都不低于 [MIN_BODY_SP]。
     */
    private fun bodySp(base: Float, text: String): Float = when {
        text.length <= 200 -> base
        text.length <= 600 -> (base - 1f).coerceAtLeast(MIN_BODY_SP)
        else -> (base - 2f).coerceAtLeast(MIN_BODY_SP)
    }

    private fun setButtonEnabled(view: TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.isClickable = enabled
        view.alpha = if (enabled) 1f else DISABLED_ALPHA
        view.setTextColor(if (enabled) theme.candidateText else theme.candidateMutedText)
        view.background = pill(theme.panelSurfacePressed)
    }

    private fun styleChip(view: TextView) {
        view.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme.candidateText)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = pill(theme.panelSurfacePressed)
        }
    }

    private fun styleButton(view: TextView, primary: Boolean) {
        view.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(if (primary) theme.panelBackground else theme.candidateText)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = pill(if (primary) theme.candidateText else theme.panelSurfacePressed)
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun matchWrap() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    private fun matchWeight() = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
    private fun matchFixed(height: Int) = LayoutParams(LayoutParams.MATCH_PARENT, height)
    private fun wrap() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    private fun buttonParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        .apply { marginStart = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 原文 15sp 起、译文 17sp 起；长文本最多降 2 级，**绝不低于 14sp** */
        const val SOURCE_BASE_SP = 15f
        const val RESULT_BASE_SP = 17f
        const val MIN_BODY_SP = 14f
        const val DISABLED_ALPHA = 0.4f
    }
}
