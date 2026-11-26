package com.board.ime.ui.panel

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.ui.KeyboardTheme

/**
 * 表情面板的顶部固定行（与其它状态共用 56dp 高度）。
 *
 * ```
 * [←]      表情              [🔍]
 * ```
 * - 非搜索时中间显示「表情」标题；
 * - 搜索时标题区显示当前输入的关键字（拼音 / 英文 / 中文），右侧变回「×」= 退出搜索。
 */
class EmojiHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onBack: (() -> Unit)? = null

    /** 搜索图标：未搜索时开始搜索，搜索中退出搜索 */
    var onToggleSearch: (() -> Unit)? = null

    private val backSlot = PanelIconSlot(context, PanelIcon.BACK)
    private val searchSlot = PanelIconSlot(context, PanelIcon.SEARCH)
    private val titleHost = LinearLayout(context)
    private val titleView = TextView(context)
    private val queryView = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var searching = false
    private var query = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = true
        clipToPadding = true

        addView(backSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        titleView.apply {
            gravity = Gravity.CENTER
            text = "表情"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            maxLines = 1
        }

        queryView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            maxLines = 1
            visibility = GONE
        }

        titleHost.orientation = VERTICAL
        titleHost.gravity = Gravity.CENTER
        titleHost.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        titleHost.addView(queryView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(titleHost, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(searchSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        backSlot.onSlotClick = { onBack?.invoke() }
        searchSlot.onSlotClick = { onToggleSearch?.invoke() }

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        setPadding(theme.panelHorizontalPadding, 0, theme.panelHorizontalPadding, 0)
        titleView.setTextColor(theme.accent)
        queryView.setTextColor(theme.candidateText)
        backSlot.applyTheme(theme)
        searchSlot.applyTheme(theme)
        renderText()
    }

    fun setSearching(value: Boolean) {
        if (searching == value) return
        searching = value
        searchSlot.setIcon(if (value) PanelIcon.CLOSE else PanelIcon.SEARCH)
        renderText()
    }

    fun setQuery(value: String) {
        if (query == value) return
        query = value
        renderText()
    }

    private fun renderText() {
        titleView.visibility = if (searching) GONE else VISIBLE
        queryView.visibility = if (searching) VISIBLE else GONE
        queryView.text = when {
            !searching -> ""
            query.isEmpty() -> "搜索表情或符号"
            else -> query
        }
        queryView.setTextColor(
            if (searching && query.isEmpty()) theme.candidateMutedText else theme.candidateText,
        )
    }

    fun desiredHeight(): Int = theme.topPanelHeight
}
