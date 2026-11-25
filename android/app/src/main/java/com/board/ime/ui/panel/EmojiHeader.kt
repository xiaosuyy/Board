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
 * [←]   表情 · 斗图   …            [🔍]
 * ```
 * - 「表情」= emoji 字符，「斗图」= 内置热门斗图表情（可分享到微信 / QQ）
 * - 搜索时标题区显示当前输入的关键字（拼音 / 英文 / 中文），右侧变回「×」= 退出搜索；
 *   斗图模式不提供搜索，右侧搜索图标隐藏。
 */
class EmojiHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onBack: (() -> Unit)? = null

    /** 搜索图标：未搜索时开始搜索，搜索中退出搜索 */
    var onToggleSearch: (() -> Unit)? = null

    /** 切到「表情」（emoji 字符） */
    var onEmojiTabClick: (() -> Unit)? = null

    /** 切到「斗图」（热门斗图表情） */
    var onMemeTabClick: (() -> Unit)? = null

    private val backSlot = PanelIconSlot(context, PanelIcon.BACK)
    private val searchSlot = PanelIconSlot(context, PanelIcon.SEARCH)
    private val titleHost = LinearLayout(context)
    private val tabRow = LinearLayout(context)
    private val emojiTab = TextView(context)
    private val memeTab = TextView(context)
    private val queryView = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var searching = false
    private var query = ""
    private var memeMode = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = true
        clipToPadding = true

        addView(backSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        tabRow.orientation = HORIZONTAL
        tabRow.gravity = Gravity.CENTER
        emojiTab.gravity = Gravity.CENTER
        memeTab.gravity = Gravity.CENTER
        emojiTab.maxLines = 1
        memeTab.maxLines = 1
        emojiTab.includeFontPadding = false
        memeTab.includeFontPadding = false
        val tabSize = TypedValue.COMPLEX_UNIT_SP to 13f
        emojiTab.setTextSize(tabSize.first, tabSize.second)
        memeTab.setTextSize(tabSize.first, tabSize.second)
        emojiTab.text = "表情"
        memeTab.text = "斗图"
        emojiTab.setOnClickListener { if (!memeMode) return@setOnClickListener else onEmojiTabClick?.invoke() }
        memeTab.setOnClickListener { if (memeMode) return@setOnClickListener else onMemeTabClick?.invoke() }
        val tabParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        tabRow.addView(emojiTab, tabParams)
        tabRow.addView(memeTab, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        queryView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            maxLines = 1
            visibility = GONE
        }

        titleHost.orientation = VERTICAL
        titleHost.gravity = Gravity.CENTER
        titleHost.addView(tabRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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
        queryView.setTextColor(theme.candidateText)
        backSlot.applyTheme(theme)
        searchSlot.applyTheme(theme)
        renderTabs()
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

    /** true = 斗图；false = emoji 字符 */
    fun setMemeMode(value: Boolean) {
        if (memeMode == value) return
        memeMode = value
        // 斗图不搜索：隐藏搜索图标
        searchSlot.visibility = if (value) GONE else VISIBLE
        if (value && searching) {
            searching = false
            searchSlot.setIcon(PanelIcon.SEARCH)
        }
        renderTabs()
    }

    private fun renderTabs() {
        emojiTab.setTextColor(if (!memeMode) theme.accent else theme.candidateMutedText)
        memeTab.setTextColor(if (memeMode) theme.accent else theme.candidateMutedText)
        emojiTab.setTypeface(emojiTab.typeface, if (!memeMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        memeTab.setTypeface(memeTab.typeface, if (memeMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun renderText() {
        val searchingNow = searching && !memeMode
        tabRow.visibility = if (searchingNow) GONE else VISIBLE
        queryView.visibility = if (searchingNow) VISIBLE else GONE
        queryView.text = when {
            !searchingNow -> ""
            query.isEmpty() -> "搜索表情或符号"
            else -> query
        }
        queryView.setTextColor(
            if (searchingNow && query.isEmpty()) theme.candidateMutedText else theme.candidateText,
        )
    }

    fun desiredHeight(): Int = theme.topPanelHeight
}
