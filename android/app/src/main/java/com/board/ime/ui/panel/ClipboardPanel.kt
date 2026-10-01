package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.TextView
import com.board.ime.clipboard.ClipboardContentType
import com.board.ime.clipboard.ClipboardCounts
import com.board.ime.clipboard.ClipboardOrigin
import com.board.ime.clipboard.ClipboardOriginLabel
import com.board.ime.clipboard.ClipboardSummary
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

    var onInsert: ((ClipboardSummary) -> Unit)? = null
    var onTogglePin: ((ClipboardSummary) -> Unit)? = null
    var onToggleFavorite: ((ClipboardSummary) -> Unit)? = null
    var onDelete: ((ClipboardSummary) -> Unit)? = null
    var onClearHistory: ((Boolean) -> Unit)? = null

    /**
     * 按 id 异步取完整内容（「全文」和「插到输入框」用）。
     *
     * 面板自己不碰数据库 / 文件：列表只有摘要，完整内容可能在几十 MB 的文件里，
     * 必须放到后台线程读，读完再回调回主线程。
     * 回调参数为 null 表示内容已不可读（例如文件被清理了）。
     */
    var onLoadContent: ((Long, (String?) -> Unit) -> Unit)? = null

    /**
     * **图片条目**的异步缩略图（只在 `contentType == IMAGE` 时调用）。
     *
     * 参数：条目、目标像素边长、回调（**主线程**；null = 不可用，不显示任何占位）。
     * 面板自己不碰文件 / 数据库：由宿主在后台按目标尺寸**降采样**解码后回调。
     */
    var onLoadThumbnail: ((ClipboardSummary, Int, (Bitmap?) -> Unit) -> Unit)? = null

    /** 用户**查看**了某条内容（点按 / 长按）→ 取消「未读」保护（Phase 1-F） */
    var onItemSeen: ((Long) -> Unit)? = null

    /** 用户在图片预览里点「下载」→ **只有此时**才写入系统相册（Phase 1-F） */
    var onSaveImage: ((ClipboardSummary) -> Unit)? = null

    private enum class Mode { LIST, ACTION, DETAIL, CLEAR, PREVIEW }

    /**
     * 剪贴板分类页。**只是内部交互状态**，UI 上不显示任何分类名称 / 指示器 / Tab。
     *
     * 顺序即滑动手势的方向：`FILE ← TEXT → IMAGE`
     * - 默认打开 = [TEXT]
     * - 左滑 → 下一项 = [IMAGE]
     * - 右滑 → 上一项 = [FILE]
     */
    private enum class ClipPage { FILE, TEXT, IMAGE }

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var items: List<ClipboardSummary> = emptyList()
    private var counts: ClipboardCounts = ClipboardCounts(0, 0, 0)
    private var mode = Mode.LIST
    private var editMode = false

    /** 当前分类页（默认文字页；顺序见 [ClipPage]） */
    private var pageIndex = ClipPage.TEXT.ordinal

    /** 图片预览用的 Bitmap（与缩略图分开管理；退出预览立即回收，不留缓存） */
    private var previewBitmap: Bitmap? = null
    private var previewToken: String? = null

    /** 底部瞬时提示（浮层，不参与布局高度） */
    private var transientMessage: TextView? = null

    /** 预览解码目标像素：以屏宽为上限，**不加载原图全尺寸** */
    private val previewMaxPx: Int get() = resources.displayMetrics.widthPixels

    /** 水平切换手势：起点 + 判定阈值（只在明确水平拖动时才接管，垂直留给原有滚动） */
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private val touchSlop: Int get() = ViewConfiguration.get(context).scaledTouchSlop
    private val switchThresholdPx: Int
        get() = (SWITCH_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private var searching = false
    private var remoteOnly = false
    private var selected: ClipboardSummary? = null

    /** 最近一次真正画出来的「结构 + 内容」指纹，用来跳过无意义的整树重建 */
    private var renderedKey: String? = null

    /** id → 卡片里的固定 View 引用（增量更新用） */
    private val binders = HashMap<Long, CardBinder>()

    private val sectionLabel = TextView(context)
    private val grid = LinearLayout(context)
    private val scroll = ScrollView(context)

    /**
     * PREVIEW 专用的**不滚动**容器。
     *
     * 关键：`ScrollView` 测量子视图用的是 `UNSPECIFIED` 高度，所以 `MATCH_PARENT` 的 ImageView
     * 会按**图片原始高度**测量（长图就会被拉到几千 px 高 → 可以上下滚、一眼看不全）。
     * 因此预览必须挂在 ScrollView 之外，与 [scroll] 同权重互斥显示，
     * 这样 ImageView 才有**真正受限**的宽高，FIT_CENTER 才能把整张图一次缩进区域内。
     */
    private val previewHost = FrameLayout(context)
    private val emptyView = TextView(context)
    private val footer = LinearLayout(context)

    init {
        orientation = VERTICAL
        addView(sectionLabel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(buildScroll(), LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        // 与 scroll 同权重；同一时刻只有一个可见（GONE 的不参与权重分配）
        previewHost.visibility = GONE
        addView(previewHost, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
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

    fun submit(newItems: List<ClipboardSummary>, counts: ClipboardCounts = this.counts) {
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
        // 预览：用工具栏**现有的**返回键退回图片缩略图列表（不新增返回按钮）
        mode == Mode.PREVIEW -> {
            releasePreview()
            mode = Mode.LIST
            selected = null
            renderedKey = null
            render()
            true
        }

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

    /** 进入图片放大预览（图片成为主要内容；左右分类切换在预览下暂停） */
    private fun openPreview(item: ClipboardSummary) {
        onItemSeen?.invoke(item.id)
        selected = item
        mode = Mode.PREVIEW
        renderedKey = null
        render()
    }

    /** 释放预览（清空不滚动容器 + 回收 Bitmap，**不保留任何图片缓存**） */
    private fun releasePreview() {
        previewHost.removeAllViews()
        transientMessage = null
        previewBitmap?.recycle()
        previewBitmap = null
        previewToken = null
    }

    /**
     * **面板底部**的瞬时提示（下载成功 / 失败）。
     *
     * 以**浮层**方式叠在预览容器上 → **不增加 Panel 高度、不改变图片布局、不影响工具栏与手势**；
     * 约 1.7 秒后自动移除，无需用户关闭。
     * 没有可用浮层容器时（例如未来的列表页下载）退回系统 Toast —— 同样是底部短暂提示，无新依赖。
     */
    fun showTransientMessage(text: String) {
        if (previewHost.visibility != VISIBLE) {
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        transientMessage?.let { previewHost.removeView(it) }
        val message = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardActionTextSize)
            setTextColor(theme.candidateText)
            background = GradientDrawable().apply {
                setColor((theme.panelSurface and 0x00FFFFFF) or (0xD8 shl 24))
                cornerRadius = theme.radiusMedium
            }
            setPadding(theme.spaceL, theme.spaceS, theme.spaceL, theme.spaceS)
        }
        previewHost.addView(
            message,
            FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { bottomMargin = theme.spaceL },
        )
        transientMessage = message
        previewHost.postDelayed({ removeTransientMessage(message) }, TRANSIENT_MESSAGE_MS)
    }

    private fun removeTransientMessage(message: TextView) {
        if (transientMessage !== message) return
        previewHost.removeView(message)
        transientMessage = null
    }

    // ---------------------------------------------------------------- 分类页（纯 UI 状态）

    private val page: ClipPage get() = ClipPage.values()[pageIndex]

    /**
     * 当前分类页要展示的条目。
     *
     * **只在 UI 层过滤**：数据层（Room / 同步）完全不感知分类。
     * 文字页 = TEXT / LINK / UNKNOWN（与 [ClipboardContentType.isTextLike] 一致）。
     */
    private fun visibleItems(): List<ClipboardSummary> = when (page) {
        ClipPage.TEXT -> items.filter { it.contentType.isTextLike }
        ClipPage.IMAGE -> items.filter { it.contentType == ClipboardContentType.IMAGE }
        ClipPage.FILE -> items.filter { it.contentType == ClipboardContentType.FILE }
    }

    /** 左滑 / 右滑切换分类页；越界不动。**UI 上不显示任何分类提示**。 */
    private fun switchPage(step: Int) {
        val next = (pageIndex + step).coerceIn(0, ClipPage.values().size - 1)
        if (next == pageIndex) return
        pageIndex = next
        mode = Mode.LIST
        selected = null
        renderedKey = null   // 触发重建（缩略图随重建释放 / 重新按需生成）
        render()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // 只有普通列表页参与水平切换：动作条 / 全文 / 清空确认保持原样
        if (mode != Mode.LIST) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = ev.x
                gestureDownY = ev.y
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - gestureDownX
                val dy = ev.y - gestureDownY
                // 只接管“明确的水平拖动”（|dx| 明显大于 |dy|）；
                // 垂直拖动 / 点击一律放行给 ScrollView 与卡片的原有行为
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mode != Mode.LIST) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - gestureDownX
                if (abs(dx) >= switchThresholdPx) switchPage(if (dx < 0) 1 else -1)
            }
        }
        return true
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
            sectionLabel.text = listLabel(visibleItems().size)
            return
        }

        footer.removeAllViews()
        footer.visibility = GONE
        // 重建前先释放上一批缩略图 / 预览图（含清空 binders），避免 Bitmap 泄漏
        releaseAllThumbnails()
        releasePreview()
        grid.removeAllViews()

        // 只有 PREVIEW 用不滚动容器；其余模式一律收起它
        if (mode != Mode.PREVIEW) previewHost.visibility = GONE

        when (mode) {
            Mode.LIST -> renderList()
            Mode.ACTION -> renderList().also { renderActionFooter() }
            Mode.DETAIL -> renderDetail()
            Mode.CLEAR -> renderClearConfirm()
            Mode.PREVIEW -> renderPreview()
        }
    }

    /** 结构指纹：只包含「会影响 View 树形状」的东西（不含文本内容） */
    private fun structurePart(key: String): String = key.substringBefore('#')

    private fun buildKey(): String {
        // 指纹只覆盖**当前分类页**的条目：别的分类新增内容不会白重建当前页
        val visible = visibleItems()
        val structure = buildString {
            append(mode.name).append('|')
            append(page.name).append('|')
            append(if (editMode) '1' else '0').append(if (searching) '1' else '0')
            append(if (remoteOnly) '1' else '0').append('|')
            append(selected?.id ?: -1).append('|')
            visible.forEach { append(it.id).append(',').append(if (it.isPinned) 'p' else '-') }
        }
        val content = buildString {
            append('#')
            visible.forEach {
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
        val visible = visibleItems()
        val hasItems = visible.isNotEmpty()
        scroll.visibility = if (hasItems) VISIBLE else GONE
        emptyView.visibility = if (hasItems) GONE else VISIBLE
        // 非搜索 / 非筛选状态：分区标题由各分区自己画，顶部不再重复一行
        sectionLabel.visibility = if ((searching || remoteOnly) && hasItems) VISIBLE else GONE
        sectionLabel.text = listLabel(visible.size)
        if (!hasItems) {
            // 沿用既有空白区文案（不为“暂无文件 / 暂无图片”新增任何类别文字）
            emptyView.text = when {
                remoteOnly -> "还没有来自其他设备的剪贴板内容"
                searching -> "没有匹配的剪贴板记录"
                else -> "还没有剪贴板记录"
            }
            return
        }

        if (searching || remoteOnly) {
            addRows(visible)
        } else {
            val pinned = visible.filter { it.isPinned }
            val favorite = visible.filter { !it.isPinned && it.isFavorite }
            val recent = visible.filter { !it.isPinned && !it.isFavorite }
            addSection("固定", pinned)
            addSection("收藏", favorite)
            addSection("最近", recent)
        }

        if (editMode && !searching && !remoteOnly) {
            grid.addView(buildClearEntry())
        }
    }

    private fun listLabel(count: Int): String = when {
        remoteOnly -> "云端剪贴板 · $count 条"
        searching -> "搜索结果 · $count 条"
        else -> ""
    }

    private fun addSection(title: String, sectionItems: List<ClipboardSummary>) {
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

    private fun addRows(rowItems: List<ClipboardSummary>) {
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
        /** 来源小字（本地 / 云端 · Windows）：**次要文字**，不是分类标题、不带图标 */
        val originLabel: TextView,
        /** 图片条目右侧的小缩略图（非图片条目 GONE，不占空间、不改变文字排版） */
        val thumb: ImageView,
    ) {
        var item: ClipboardSummary? = null

        /** 当前缩略图对应的内容 hash（换条目 / 换图片时据此重新加载） */
        var thumbHash: String? = null

        /** 当前显示的缩略图 Bitmap，由本面板负责回收 */
        var thumbBitmap: Bitmap? = null
    }

    /**
     * 缩略图边长（px）：
     * **不超过「卡片最小内容高度」**（`minHeight - 上下 padding`），因此加图**绝不会把卡片撑高**；
     * 同时不超过 [THUMBNAIL_MAX_DP]。视觉目标是“提示这是哪张图”，不是卡片主体。
     */
    private fun thumbnailSizePx(): Int {
        val byCard = theme.clipboardCardMinHeight - theme.clipboardPadding * 2
        val byMax = (THUMBNAIL_MAX_DP * resources.displayMetrics.density).toInt()
        return minOf(byCard, byMax).coerceAtLeast(0)
    }

    private fun releaseThumbBitmap(binder: CardBinder) {
        binder.thumbBitmap?.recycle()
        binder.thumbBitmap = null
    }

    /** 释放当前所有缩略图（并清空卡片索引），条目被替换 / 面板离开窗口时调用 */
    private fun releaseAllThumbnails() {
        binders.values.forEach { binder ->
            binder.thumb.setImageDrawable(null)
            releaseThumbBitmap(binder)
            binder.thumbHash = null
        }
        binders.clear()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 面板离开窗口：**立即释放**缩略图，不保留任何图片缓存；
        // 下次显示时按需重新生成（renderedKey 置空以触发重建）。
        releaseAllThumbnails()
        releasePreview()
        renderedKey = null
    }

    private fun createCard(item: ClipboardSummary): View {
        val card = LinearLayout(context).apply {
            orientation = HORIZONTAL
            minimumHeight = theme.clipboardCardMinHeight
            background = cardBackground(false)
            setPadding(theme.clipboardPadding, theme.clipboardPadding, theme.clipboardPadding, theme.clipboardPadding)
            isClickable = true
            setOnClickListener { insert(item) }
            setOnLongClickListener {
                // 用户主动打开了这条（动作条）→ 视为「已查看」，取消未读保护
                onItemSeen?.invoke(item.id)
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
        // 来源小字：**每个条目**的小型元信息（本地 / 云端 · Windows）。
        // 字号比正文小、用现有次要文字颜色，没有图标 / badge / 云朵。
        // 它和固定 / 收藏共用一个徽标行，卡片高度由「正文 + 这行」自然决定。
        val originLabel = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, theme.clipboardSectionSize * 0.92f)
            setTextColor(theme.candidateMutedText)
            setPadding(theme.spaceS, 0, 0, 0)
        }
        badgeRow.addView(originLabel)
        column.addView(badgeRow)
        card.addView(column)

        // 图片缩略图：卡片右侧原有的留白区域。默认 GONE → 文字条目尺寸 / 排版完全不变。
        // 固定正方形 + FIT_CENTER = contain（保持比例、不裁切主体、不超出卡片）。
        val thumb = ImageView(context).apply {
            visibility = GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 点缩略图 = 放大预览；卡片本身的点击行为（插入）保持不变
            isClickable = true
            setOnClickListener { openPreview(item) }
        }
        val thumbSize = thumbnailSizePx()
        card.addView(
            thumb,
            LayoutParams(thumbSize, thumbSize).apply { marginStart = theme.spaceS },
        )

        val binder = CardBinder(card, preview, pinSlot, starSlot, originLabel, thumb)
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
    private fun updateCard(binder: CardBinder, item: ClipboardSummary) {
        binder.item = item
        // 列表不再持有完整内容，只有摘要；完整内容点「全文」时按 id 取
        binder.preview.text = item.preview.orEmpty()
        binder.card.contentDescription = item.preview.orEmpty()
        binder.pinSlot.visibility = if (item.isPinned) VISIBLE else GONE
        binder.starSlot.visibility = if (item.isFavorite) VISIBLE else GONE
        // 来源小字：只读 origin（数据层逻辑不变），LOCAL→本地 / CLOUD→云端[ · 设备名]
        // 颜色：本机 = 现有次要灰；云端 = 现有蓝色强调色（不新增颜色、不加图标/背景/字号/间距）
        binder.originLabel.text = ClipboardOriginLabel.of(item.origin, item.originType)
        binder.originLabel.setTextColor(
            if (item.origin == ClipboardOrigin.REMOTE) theme.accent else theme.candidateMutedText,
        )
        // 图片条目：只在卡片右侧原有留白处放一张小缩略图；非图片条目完全不显示（不占空间）
        if (item.contentType == ClipboardContentType.IMAGE) {
            binder.thumb.visibility = VISIBLE
            if (binder.thumbHash != item.contentHash) {
                binder.thumbHash = item.contentHash
                binder.thumb.setImageDrawable(null)
                releaseThumbBitmap(binder)
                val token = item.contentHash
                val callback: (Bitmap?) -> Unit = callback@{ bitmap ->
                    // 回调回来时条目可能已被替换 / 移除：丢弃并回收，绝不挂到错误的卡片上
                    if (binder.thumbHash != token || binder.item?.contentHash != token) {
                        bitmap?.recycle()
                        return@callback
                    }
                    // 先换上新的，再回收旧的（避免“视图还挂着已回收的 Bitmap”）
                    val previous = binder.thumbBitmap
                    binder.thumbBitmap = bitmap
                    binder.thumb.setImageBitmap(bitmap)
                    if (previous != null && previous !== bitmap) previous.recycle()
                }
                onLoadThumbnail?.invoke(item, thumbnailSizePx(), callback)
            }
        } else if (binder.thumb.visibility != GONE) {
            binder.thumb.visibility = GONE
            binder.thumb.setImageDrawable(null)
            binder.thumbHash = null
            releaseThumbBitmap(binder)
        }

        val highlight = mode == Mode.ACTION && selected?.id == item.id
        binder.card.background = cardBackground(highlight)
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

        // 只显示摘要里的信息，完整内容**异步**取（可能在几十 MB 的文件里）
        val body = TextView(context).apply {
            text = "加载中…"
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
        }
        grid.addView(body, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val sizeHint = item.sizeLabel
        onLoadContent?.invoke(item.id) { content ->
            body.text = when {
                content == null -> "内容已不可读（文件可能已被清理）"
                else -> content
            }
            sectionLabel.text = if (sizeHint.isEmpty()) "完整内容" else "完整内容 · $sizeHint"
        } ?: run { body.text = "无法读取内容（未接入内容仓库）" }

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

    // ---------------------------------------------------------------- 图片预览（Phase 1-F）

    /**
     * 图片放大预览。
     *
     * - **保留工具栏**：本面板本来就在工具栏**下方**，高度不变，不新增全屏 Activity；
     * - 预览区域 = 工具栏以下的剩余区域（`MATCH_PARENT`，被 ScrollView 视口约束，不会撑出去）；
     * - `FIT_CENTER` = contain：保持原比例、不裁切、不超出区域；
     * - 右下角一个**很小的**下载按钮 —— 只有用户主动点击才会写系统相册；
     * - **不新增标题 / 文字 / 面包屑 / 指示器**。
     */
    private fun renderPreview() {
        val item = selected ?: return
        // **预览脱离 ScrollView**：隐藏列表滚动容器，改用不滚动的 previewHost，
        // 这样 ImageView 拿到的是“工具栏以下”的**确定宽高**，长图也会被整体缩进区域内。
        scroll.visibility = GONE
        previewHost.visibility = VISIBLE
        previewHost.removeAllViews()
        emptyView.visibility = GONE
        sectionLabel.visibility = GONE

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val container = FrameLayout(context).apply {
            addView(
                image,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
        }
        // 预览只留很小的安全边距：让图片尽可能占满可用区域，又不贴边
        val margin = (PREVIEW_MARGIN_DP * resources.displayMetrics.density).toInt()
        container.setPadding(margin, margin, margin, margin)

        // 下载：图标很小（17dp），点击区域稍大（32dp）；
        // 背景只做 70% 半透明 —— 不遮挡主体，也不会在浅色照片上“消失”，更不像一个大按钮。
        val hit = (DOWNLOAD_HIT_DP * resources.displayMetrics.density).toInt()
        val download = PanelIconSlot(context, PanelIcon.DOWNLOAD).apply {
            applyTheme(theme, theme.candidateText)
            setIconSize(DOWNLOAD_ICON_DP * resources.displayMetrics.density)
            background = GradientDrawable().apply {
                setColor((theme.panelSurface and 0x00FFFFFF) or (0xB0 shl 24))
                cornerRadius = theme.radiusMedium
            }
            contentDescription = "保存到相册"
            onSlotClick = { onSaveImage?.invoke(item) }
        }
        container.addView(
            download,
            FrameLayout.LayoutParams(hit, hit, Gravity.END or Gravity.BOTTOM),
        )
        previewHost.addView(
            container,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )

        // 按目标像素降采样解码（不加载原图全尺寸）；回调回来时若已退出预览则丢弃并回收
        val token = item.contentHash
        previewToken = token
        val callback: (Bitmap?) -> Unit = callback@{ bitmap ->
            if (previewToken != token) {
                bitmap?.recycle()
                return@callback
            }
            val previous = previewBitmap
            previewBitmap = bitmap
            image.setImageBitmap(bitmap)
            if (previous != null && previous !== bitmap) previous.recycle()
        }
        onLoadThumbnail?.invoke(item, previewMaxPx, callback)
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

    private fun insert(item: ClipboardSummary) {
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

        /** 缩略图边长上限（dp）；实际还会被「卡片最小内容高度」再压一次 */
        const val THUMBNAIL_MAX_DP = 48f

        /** 水平切换分类的位移阈值（dp）；达到才切页，避免误触 */
        const val SWITCH_THRESHOLD_DP = 56f

        /** 预览图片四周的安全边距（dp）：很小，既不贴边也不浪费空间 */
        const val PREVIEW_MARGIN_DP = 10f

        /** 预览右下角下载图标的**视觉**尺寸（dp）——很小，只提示“可保存” */
        const val DOWNLOAD_ICON_DP = 17f

        /** 下载图标的**点击**区域（dp）：略大于图标，但不是一个明显的大按钮 */
        const val DOWNLOAD_HIT_DP = 32f

        /** 底部瞬时提示的显示时长（毫秒）：约 1.7s，自动消失 */
        const val TRANSIENT_MESSAGE_MS = 1700L
    }
}
