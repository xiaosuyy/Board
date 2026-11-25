package com.board.ime.ui.panel

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import com.board.ime.ui.KeyboardTheme

/**
 * v + 空格 打开的快捷面板（TOOLBOX 状态）。
 *
 * 与「更多」共用同一块固定高度的 TopPanel：
 * ```
 * [⌄]   剪贴板   常用语   符号   表情
 * ```
 * 只执行跳转，不放数据：剪贴板 / 常用语 / 表情复用既有面板，符号切到符号键盘层。
 */
class ToolboxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    enum class Action {
        CLOSE,
        CLIPBOARD,
        PHRASES,
        SYMBOLS,
        EMOJI,
        SETTINGS,
        SWITCH_KEYBOARD,
    }

    var onAction: ((Action) -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val closeSlot = PanelIconSlot(context, PanelIcon.CHEVRON_DOWN)
    private val itemViews = ArrayList<ToolItemView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(closeSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        entries.forEach { (action, icon, label) ->
            val item = ToolItemView(context, icon, label)
            item.onItemClick = { onAction?.invoke(action) }
            itemViews += item
            addView(item, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }

        closeSlot.onSlotClick = { onAction?.invoke(Action.CLOSE) }
        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        closeSlot.applyTheme(theme)
        itemViews.forEach { it.applyTheme(theme) }
        (closeSlot.layoutParams as? LayoutParams)?.let {
            it.width = theme.toolHitSize
            closeSlot.layoutParams = it
        }
        requestLayout()
        invalidate()
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val desired = resolveSize(theme.topPanelHeight, heightMeasureSpec)
        if (measuredHeight != desired) setMeasuredDimension(measuredWidth, desired)
    }

    private data class Entry(val action: Action, val icon: PanelIcon, val label: String)

    private companion object {
        val entries = listOf(
            Entry(Action.CLIPBOARD, PanelIcon.CLIPBOARD, "剪贴板"),
            Entry(Action.PHRASES, PanelIcon.PHRASES, "常用语"),
            Entry(Action.SYMBOLS, PanelIcon.EDIT, "符号"),
            Entry(Action.EMOJI, PanelIcon.EMOJI, "表情"),
            Entry(Action.SETTINGS, PanelIcon.SETTINGS, "设置"),
            Entry(Action.SWITCH_KEYBOARD, PanelIcon.SWITCH_KEYBOARD, "切换键盘"),
        )
    }
}
