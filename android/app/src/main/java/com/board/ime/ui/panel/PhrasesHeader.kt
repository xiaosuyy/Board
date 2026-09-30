package com.board.ime.ui.panel

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.ui.KeyboardTheme

/**
 * 常用语状态下的顶部固定面板（与其它状态**共用 56dp 高度**）。
 *
 * ```
 * [←]  常用语                              ＋ 新增
 * ```
 */
class PhrasesHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onBack: (() -> Unit)? = null

    /** 右侧「＋ 新增」 */
    var onAdd: (() -> Unit)? = null

    private val backSlot = PanelIconSlot(context, PanelIcon.BACK)
    private val titleView = TextView(context)
    private val addView = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = true
        clipToPadding = true

        addView(backSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        titleView.apply {
            text = "常用语"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        addView(addView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        backSlot.onSlotClick = { onBack?.invoke() }
        addView.setOnClickListener { onAdd?.invoke() }

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        titleView.setTextColor(theme.candidateText)
        addView.apply {
            text = "＋ 新增"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(theme.candidateText)
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
        }
        backSlot.applyTheme(theme)
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
