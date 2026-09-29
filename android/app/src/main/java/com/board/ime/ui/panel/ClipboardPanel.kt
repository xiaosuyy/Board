package com.board.ime.ui.panel

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import com.board.ime.clipboard.ClipboardContentType
import com.board.ime.clipboard.ClipboardCounts
import com.board.ime.clipboard.ClipboardOrigin
import com.board.ime.database.entity.ClipboardItem
import com.board.ime.ui.KeyboardTheme

/**
 * 剪贴板内容区。
 *
 * 它**只负责内容**：标题 / 返回 / 云端筛选 / 搜索 / 编辑在 [ClipboardHeader]（固定在 TopPanel）；
 * 内容区本身与键盘等高（普通状态）或限定高度放在键盘上面（搜索状态），
 * 因此 TopPanel 在所有状态下都保持同一个高度。
 *
 * 内部是一个很小的状态机：
 * ```
 * LIST   分区列表：固定 / 收藏 / 最近（只看云端时是一个扁平列表）
 * ACTION 长按某个卡片后底部出现的动作条：收藏 / 固定 / 全文 / 删除 / 取消
 * DETAIL 完整内容（长文本在这里看，不把列表撑爆）
 * CLEAR  「清空全部历史」的二次确认（含是否连固定 / 收藏一起删）
 * ```
 *
 * 性能：卡片结构固定（预览 + 固定 / 收藏 / 来源小标记），
 * **数据没变就直接跳过**，只有内容变了才在原 View 上改文本 / 可见性，
 * 不再每次 Room 发一条事件就把整个网格 removeAllViews 重建。
 */
class ClipboardPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onInsert: ((ClipboardItem) -> Unit)? = null
    var onTogglePin: ((ClipboardItem) -> Unit)? = null
    var onToggleFavorite: ((ClipboardItem) -> Unit)? = null
    var onDelete: ((ClipboardItem) -> Unit)? = null
    var onClearHistory: ((Boolean) -> Unit)? = null

    private enum class Mode { LIST, ACTION, DETAIL, CLEAR }

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var items: List<ClipboardItem> = emptyList()
    private var counts: ClipboardCounts = ClipboardCounts(0, 0, 0)
    private var mode = Mode.LIST
    private var editMode = false
    private var searching = false
    private var remoteOnly = false
    private var selected: ClipboardItem? = null

    /** 最近一次真正画出来的「结构 + 内容」指纹，用来跳过无意义的整树重建 */
    private var renderedKey: String? = null

    /** id → 卡片里的固定 View 引用（增量更新用） */
    private val binders = HashMap<Long, CardBinder>()

    private val sectionLabel = TextView(context)
    private val grid = LinearLayout(context)
    private val scroll = ScrollView(context)
    private val emptyView = TextView(context)
    private val footer = LinearLayout(context)

    init {
        orientation = VERTICAL
        addView(sectionLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(buildScroll(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyTheme(theme)
    }

    private fun buildScroll(): View = scroll.apply {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(grid, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        grid.orientation = VERTICAL
    }

    // ---------------------------------------------------------------- 对外

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        // 与键盘同色：内容区取代键盘位置，看起来仍像同一个输入法
        setBackgroundColor(theme.keyboardBackground)

        val padding = theme.clipboardPadding
        setPadding(padding, padding / 2, padding, padding)

        sectionLabel.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardSectionSize)
            setTextColor(theme.candidateMutedText)
            setPadding(0, padding / 2, 0, padding / 2)
        }
        emptyView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardTextSize)
            setTextColor(theme.candidateMutedText)
            setPadding(0, padding * 4, 0, padding * 4)
        }
        renderedKey = null
        render()
    }

    fun submit(newItems: List<ClipboardItem>, counts: ClipboardCounts = this.counts) {
        items = newItems
        this.counts = counts
        // 选中的条目可能已经被删掉
        selected = selected?.let { current -> newItems.firstOrNull { it.id == current.id } }
        if (mode != Mode.LIST && selected == null) mode = Mode.LIST
        render()
    }

    fun setEditMode(value: Boolean) {
        if (editMode == value) return
        editMode = value
        if (value) mode = Mode.LIST
        renderedKey = null
        render()
    }

    fun isEditing(): Boolean = editMode

    /** 搜索状态：同一个内容区，换成「搜索结果」的排版（高度由宿主控制） */
    fun setSearching(value: Boolean) {
        if (searching == value) return
        searching = value
        mode = Mode.LIST
        selected = null
        renderedKey = null
        render()
    }

    fun isSearching(): Boolean = searching

    /** 只看「其他设备同步过来」的剪贴板 */
    fun setRemoteOnly(value: Boolean) {
        if (remoteOnly == value) return
        remoteOnly = value
        mode = Mode.LIST
        selected = null
        renderedKey = null
        render()
    }

    /**
     * 返回键处理：优先退出临时视图，其次退出编辑 / 搜索。
     * @return true 表示已经消费掉这次返回
     */
    fun handleBack(): Boolean = when {
        mode != Mode.LIST -> {
            mode = Mode.LIST
            selected = null
            renderedKey = null
            render()
            true
        }

        editMode -> true
        else -> false
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        val listKey = buildKey()
        if (listKey == renderedKey) return
        val structureUnchanged = renderedKey != null && structurePart(renderedKey!!) == structurePart(listKey)
        renderedKey = listKey

        if (structureUnchanged && mode == Mode.LIST) {
            // 结构没变，只是内容 / 标记变了：原地更新，不重建 View
            items.forEach { item -> binders[item.id]?.let { updateCard(it, item) } }
            sectionLabel.text = listLabel()
            return
        }

        footer.removeAllViews()
        footer.visibility = GONE
        grid.removeAllViews()
        binders.clear()

        when (mode) {
            Mode.LIST -> renderList()
            Mode.ACTION -> renderList().also { renderActionFooter() }
            Mode.DETAIL -> renderDetail()
            Mode.CLEAR -> renderClearConfirm()
        }
    }

    /** 结构指纹：只包含「会影响 View 树形状」的东西（不含文本内容） */
    private fun structurePart(key: String): String = key.substringBefore('#')

    private fun buildKey(): String {
        val structure = buildString {
            append(mode.name).append('|')
            append(if (editMode) '1' else '0').append(if (searching) '1' else '0')
            append(if (remoteOnly) '1' else '0').append('|')
            append(selected?.id ?: -1).append('|')
            items.forEach { append(it.id).append(',').append(if (it.isPinned) 'p' else '-') }
        }
        val content = buildString {
            append('#')
            items.forEach {
                append(it.id).append(':')
                append(it.preview.orEmpty()).append(':')
                append(if (it.isFavorite) 'f' else '-')
                append(it.origin.name.first())
                append(it.originType.orEmpty())
            }
        }
        return structure + content
    }

    private fun renderList() {
        val hasItems = items.isNotEmpty()
        scroll.visibility = if (hasItems) VISIBLE else GONE
        emptyView.visibility = if (hasItems) GONE else VISIBLE
        // 非搜索 / 非筛选状态：分区标题由各分区自己画，顶部不再重复一行
        sectionLabel.visibility = if ((searching || remoteOnly) && hasItems) VISIBLE else GONE
        sectionLabel.text = listLabel()
        if (!hasItems) {
            emptyView.text = when {
                remoteOnly -> "还没有来自其他设备的剪贴板内容"
                searching -> "没有匹配的剪贴板记录"
                else -> "还没有剪贴板记录"
            }
            return
        }

        if (searching || remoteOnly) {
            addRows(items)
        } else {
            val pinned = items.filter { it.isPinned }
            val favorite = items.filter { !it.isPinned && it.isFavorite }
            val recent = items.filter { !it.isPinned && !it.isFavorite }
            addSection("固定", pinned)
            addSection("收藏", favorite)
            addSection("最近", recent)
        }

        if (editMode && !searching && !remoteOnly) {
            grid.addView(buildClearEntry())
        }
    }

    private fun listLabel(): String = when {
        remoteOnly -> "云端剪贴板 · ${items.size} 条"
        searching -> "搜索结果 · ${items.size} 条"
        else -> ""
    }

    private fun addSection(title: String, sectionItems: List<ClipboardItem>) {
        if (sectionItems.isEmpty()) return
        grid.addView(
            TextView(context).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardSectionSize)
                setTextColor(theme.candidateMutedText)
                setPadding(0, theme.spaceS, 0, theme.spaceS)
            },
        )
        addRows(sectionItems)
    }

    private fun addRows(rowItems: List<ClipboardItem>) {
        rowItems.chunked(COLUMNS).forEach { chunk ->
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = theme.clipboardGridGap
                }
            }
            chunk.forEachIndexed { index, item -> row.addView(createCard(item), cardParams(index)) }
            repeat(COLUMNS - chunk.size) { row.addView(View(context), cardParams(chunk.size + it)) }
            grid.addView(row)
        }
    }

    private fun cardParams(index: Int): LayoutParams =
        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            if (index > 0) marginStart = theme.clipboardGridGap
        }

    // ---------------------------------------------------------------- 卡片

    /** 卡片里会被增量更新的几个 View */
    private class CardBinder(
        val card: View,
        val preview: TextView,
        val pinSlot: View,
        val starSlot: View,
        val originLabel: TextView,
    ) {
        var item: ClipboardItem? = null
    }

    private fun createCard(item: ClipboardItem): View {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            minimumHeight = theme.clipboardCardMinHeight
            background = cardBackground(false)
            setPadding(theme.clipboardPadding, theme.clipboardPadding, theme.clipboardPadding, theme.clipboardPadding)
            isClickable = true
            setOnClickListener { insert(item) }
            setOnLongClickListener {
                selected = item
                mode = Mode.ACTION
                renderedKey = null
                render()
                true
            }
        }

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        val preview = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardTextSize)
            setTextColor(theme.candidateText)
            maxLines = CARD_MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
        }
        column.addView(preview)

        // 固定 / 收藏 / 来源：结构固定，靠可见性切换（便于增量更新）
        val badgeRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.spaceXs, 0, 0)
        }
        val pinSlot = PanelIconSlot(context, PanelIcon.PIN).apply {
            applyTheme(theme, theme.accent)
            setOnClickListener(null)
            isClickable = false
        }
        val starSlot = PanelIconSlot(context, PanelIcon.STAR).apply {
            applyTheme(theme, theme.accent)
            setOnClickListener(null)
            isClickable = false
        }
        badgeRow.addView(pinSlot, LayoutParams(theme.clipboardBadgeSize.toInt(), theme.clipboardBadgeSize.toInt()))
        badgeRow.addView(
            starSlot,
            LayoutParams(theme.clipboardBadgeSize.toInt(), theme.clipboardBadgeSize.toInt()).apply {
                marginStart = theme.spaceXs
            },
        )
        val originLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardSectionSize * 0.92f)
            setTextColor(theme.candidateMutedText)
            setPadding(theme.spaceS, 0, 0, 0)
        }
        badgeRow.addView(originLabel)
        column.addView(badgeRow)
        card.addView(column)

        val binder = CardBinder(card, preview, pinSlot, starSlot, originLabel)
        binders[item.id] = binder
        updateCard(binder, item)

        if (editMode && !searching && !remoteOnly) {
            card.addView(
                PanelIconSlot(context, PanelIcon.CLOSE).apply {
                    applyTheme(theme, theme.candidateMutedText)
                    contentDescription = "删除这条记录"
                    onSlotClick = { onDelete?.invoke(item) }
                },
                LayoutParams(theme.toolHitSize, theme.toolHitSize),
            )
        }
        return card
    }

    /** 只在原 View 上改内容（增量路径也走这里） */
    private fun updateCard(binder: CardBinder, item: ClipboardItem) {
        binder.item = item
        binder.preview.text = item.preview ?: item.content
        binder.card.contentDescription = item.preview ?: item.content
        binder.pinSlot.visibility = if (item.isPinned) VISIBLE else GONE
        binder.starSlot.visibility = if (item.isFavorite) VISIBLE else GONE
        binder.originLabel.text = originText(item)
        binder.originLabel.setTextColor(
            if (item.origin == ClipboardOrigin.REMOTE) theme.accent else theme.candidateMutedText,
        )
        val highlight = mode == Mode.ACTION && selected?.id == item.id
        binder.card.background = cardBackground(highlight)
    }

    /**
     * 来源小标记：**很轻**，只有一行小字。
     * - 云端：`云端 · Windows`（设备名读得到就带上，读不到只写「云端」）
     * - 本机：`本机`
     */
    private fun originText(item: ClipboardItem): String {
        if (item.origin == ClipboardOrigin.REMOTE) {
            val device = item.originType?.takeIf { it.isNotBlank() }
            return if (device != null) "云端 · $device" else "云端"
        }
        return if (item.contentType == ClipboardContentType.LINK) "本机 · 链接" else "本机"
    }

    // ---------------------------------------------------------------- 动作条

    private fun renderActionFooter() {
        val item = selected ?: return
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.clipboardPadding / 2, 0, 0)
        }
        row.addView(
            actionButton(if (item.isFavorite) "取消收藏" else "收藏") {
                onToggleFavorite?.invoke(item)
                selected = null
                mode = Mode.LIST
                renderedKey = null
                render()
            },
        )
        row.addView(
            actionButton(if (item.isPinned) "取消固定" else "固定") {
                onTogglePin?.invoke(item)
                selected = null
                mode = Mode.LIST
                renderedKey = null
                render()
            },
        )
        row.addView(actionButton("全文") { mode = Mode.DETAIL; renderedKey = null; render() })
        row.addView(
            actionButton("删除") {
                onDelete?.invoke(item)
                selected = null
                mode = Mode.LIST
                renderedKey = null
                render()
            },
        )
        row.addView(actionButton("取消") { selected = null; mode = Mode.LIST; renderedKey = null; render() })

        footer.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
                addView(row, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        footer.visibility = VISIBLE
    }

    // ---------------------------------------------------------------- 全文

    private fun renderDetail() {
        val item = selected ?: return
        scroll.visibility = VISIBLE
        emptyView.visibility = GONE
        sectionLabel.visibility = VISIBLE
        sectionLabel.text = "完整内容"

        grid.addView(
            TextView(context).apply {
                text = item.content
                setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardTextSize)
                setTextColor(theme.candidateText)
                setTextIsSelectable(true)
                background = cardBackground(false)
                setPadding(
                    theme.clipboardPadding,
                    theme.clipboardPadding,
                    theme.clipboardPadding,
                    theme.clipboardPadding,
                )
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.clipboardPadding / 2, 0, 0)
        }
        row.addView(actionButton("返回") { mode = Mode.LIST; selected = null; renderedKey = null; render() })
        row.addView(actionButton("插到输入框") { insert(item) })
        footer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        footer.visibility = VISIBLE
    }

    // ---------------------------------------------------------------- 清空确认

    private fun renderClearConfirm() {
        scroll.visibility = VISIBLE
        emptyView.visibility = GONE
        sectionLabel.visibility = VISIBLE
        sectionLabel.text = "清空全部历史"

        val checkbox = CheckBox(context).apply {
            setTextColor(theme.candidateText)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardTextSize)
            text = "同时删除固定和收藏（${counts.pinned + counts.favorite} 条）"
            buttonTintList = android.content.res.ColorStateList.valueOf(theme.accent)
        }

        grid.addView(
            LinearLayout(context).apply {
                orientation = VERTICAL
                background = cardBackground(false)
                setPadding(
                    theme.clipboardPadding,
                    theme.clipboardPadding,
                    theme.clipboardPadding,
                    theme.clipboardPadding,
                )
                addView(
                    TextView(context).apply {
                        text = "清空全部历史？"
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardTextSize * 1.15f)
                        setTextColor(theme.candidateText)
                    },
                )
                addView(
                    TextView(context).apply {
                        text = "固定和收藏默认保留，只会删除 ${counts.plain} 条普通记录。此操作不可撤销。"
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardTextSize)
                        setTextColor(theme.candidateMutedText)
                        setPadding(0, theme.spaceS, 0, 0)
                    },
                )
                addView(checkbox)
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, theme.clipboardPadding / 2, 0, 0)
        }
        row.addView(actionButton("取消") { mode = Mode.LIST; renderedKey = null; render() })
        row.addView(
            actionButton("清空") {
                onClearHistory?.invoke(checkbox.isChecked)
                mode = Mode.LIST
                renderedKey = null
                render()
            },
        )
        footer.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        footer.visibility = VISIBLE
    }

    // ---------------------------------------------------------------- 小工具

    private fun insert(item: ClipboardItem) {
        onInsert?.invoke(item)
    }

    private fun buildClearEntry(): View = TextView(context).apply {
        text = "清空全部历史"
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardActionTextSize)
        setTextColor(theme.candidateMutedText)
        background = cardBackground(false)
        isClickable = true
        setPadding(0, theme.clipboardPadding, 0, theme.clipboardPadding)
        setOnClickListener {
            mode = Mode.CLEAR
            renderedKey = null
            render()
        }
    }

    private fun actionButton(label: String, action: () -> Unit): TextView = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardActionTextSize)
        setTextColor(theme.candidateText)
        background = cardBackground(false)
        isClickable = true
        minimumHeight = theme.clipboardActionHeight
        setPadding(
            theme.clipboardPadding,
            theme.clipboardPadding / 2,
            theme.clipboardPadding,
            theme.clipboardPadding / 2,
        )
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = theme.clipboardGridGap
        }
        setOnClickListener { action() }
    }

    private fun cardBackground(highlight: Boolean): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = theme.radiusMedium
            if (highlight) setStroke(theme.dividerHeight * 2, theme.accent)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(theme.panelSurfacePressed))
            addState(intArrayOf(), shape(if (highlight) theme.candidateSelectedBackground else theme.panelSurface))
        }
    }

    private companion object {
        const val COLUMNS = 2
        const val CARD_MAX_LINES = 3
    }
}
