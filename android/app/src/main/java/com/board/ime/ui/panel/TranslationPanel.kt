package com.board.ime.ui.panel

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.translation.TranslationLanguage
import com.board.ime.translation.TranslationUiState
import com.board.ime.ui.KeyboardTheme

/**
 * 翻译内容区（占键盘的位置，因此有足够高度；总高度因此与其它状态一致）。
 *
 * ```
 * 中文 → English
 * ─────────────────────────
 * 原文（来自输入框，只读，最多 3 行）
 * ─────────────────────────
 * 译文 / 翻译中… / 原因
 *                    [复制] [替换]
 * ```
 *
 * 视觉沿用 Board 的黑白灰 + 少量强调色：无渐变、无发光、无大面积蓝色。
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
    private val sourceLabel = TextView(context)
    private val sourceView = TextView(context)
    private val divider = android.view.View(context)
    private val resultView = TextView(context)
    private val actionRow = LinearLayout(context)
    private val primaryButton = TextView(context)
    private val copyButton = TextView(context)
    private val replaceButton = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    init {
        orientation = VERTICAL
        setPadding(dp(14), dp(10), dp(14), dp(10))

        languageRow.orientation = HORIZONTAL
        languageRow.gravity = Gravity.CENTER_VERTICAL
        styleChip(sourceChip)
        styleChip(targetChip)
        arrowView.apply {
            text = "→"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        languageRow.addView(sourceChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        languageRow.addView(arrowView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        languageRow.addView(targetChip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(languageRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        sourceView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(
            sourceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )

        divider.setBackgroundColor(theme.divider)
        addView(
            divider,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(8) },
        )

        resultView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.1f)
            isVerticalScrollBarEnabled = true
            setTextIsSelectable(true)
        }
        addView(
            resultView,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) },
        )

        actionRow.orientation = HORIZONTAL
        actionRow.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        styleButton(primaryButton)
        styleButton(copyButton)
        styleButton(replaceButton)
        actionRow.addView(copyButton, buttonParams())
        actionRow.addView(replaceButton, buttonParams())
        actionRow.addView(primaryButton, buttonParams())
        addView(actionRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        sourceChip.setOnClickListener { onCycleSource?.invoke() }
        targetChip.setOnClickListener { onCycleTarget?.invoke() }
        primaryButton.setOnClickListener { onTranslate?.invoke() }
        copyButton.setOnClickListener { onCopy?.invoke() }
        replaceButton.setOnClickListener { onReplace?.invoke() }
        sourceLabel.setOnClickListener { onCycleSource?.invoke() }

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.keyboardBackground)
        arrowView.setTextColor(theme.candidateMutedText)
        sourceLabel.setTextColor(theme.candidateMutedText)
        divider.setBackgroundColor(theme.divider)
        sourceView.setTextColor(theme.candidateMutedText)
        styleChip(sourceChip)
        styleChip(targetChip)
        styleButton(primaryButton)
        styleButton(copyButton)
        styleButton(replaceButton)
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
        sourceChip.text = source.label
        targetChip.text = target.label

        sourceView.text = if (sourceText.isBlank()) "（输入框里没有可翻译的文本）" else sourceText

        when (state) {
            TranslationUiState.IDLE -> {
                resultView.text = ""
                showActions(primary = "翻译", copy = false, replace = false)
            }

            TranslationUiState.LOADING -> {
                resultView.text = "翻译中…"
                showActions(primary = null, copy = false, replace = false)
            }

            TranslationUiState.RESULT -> {
                resultView.text = resultText
                showActions(primary = null, copy = true, replace = true)
            }

            TranslationUiState.ERROR -> {
                resultView.text = message
                showActions(primary = "重试", copy = false, replace = false)
            }

            TranslationUiState.NOT_CONFIGURED -> {
                resultView.text = "请先配置翻译服务"
                showActions(primary = "去设置", copy = false, replace = false)
            }
        }
        resultView.setTextColor(
            when (state) {
                TranslationUiState.RESULT -> theme.candidateText
                TranslationUiState.ERROR -> theme.candidateMutedText
                else -> theme.candidateMutedText
            },
        )
        // 「去设置」与「翻译/重试」共用主按钮：按状态改语义
        primaryButton.setOnClickListener {
            if (state == TranslationUiState.NOT_CONFIGURED) {
                onOpenSettings?.invoke()
            } else {
                onTranslate?.invoke()
            }
        }
    }

    private fun showActions(primary: String?, copy: Boolean, replace: Boolean) {
        primaryButton.visibility = if (primary == null) GONE else VISIBLE
        primary?.let { primaryButton.text = it }
        copyButton.visibility = if (copy) VISIBLE else GONE
        replaceButton.visibility = if (replace) VISIBLE else GONE
    }

    private fun styleChip(view: TextView) {
        view.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme.candidateText)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = pill(theme.panelSurfacePressed)
        }
    }

    private fun styleButton(view: TextView) {
        view.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme.candidateText)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = pill(theme.panelSurfacePressed)
        }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
    }

    private fun buttonParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        .apply { marginStart = dp(8) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
