package com.board.ime.ui.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.board.ime.database.entity.PhraseEntry
import com.board.ime.ui.KeyboardTheme

/**
 * 常用语内容区（占键盘位置，总高度与其它状态一致）。
 *
 * ```
 * ≡  您好，我现在不方便接电话，稍后…   ✎  ×
 * ───────────────────────────────────────  ← 1px 分隔线
 * ≡  谢谢您的理解。                     ✎  ×
 * ```
 *
 * 四个交互**完全隔离**（不用长按、不弹浮层）：
 * - `≡`：**只有从这里开始拖动**才进入排序；
 * - 正文：单击 = 直接上屏（用完整原文，不是省略后的显示文本）；
 * - `✎`：编辑（进现有 PhraseEditActivity）；
 * - `×`：删除（软删除，带轻量确认）。
 *
 * 正文**永远一行**（`maxLines=1` + `ellipsize=END`），所以条目高度固定、不会被长文本撑高。
 */
class PhrasesPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onUse: ((PhraseEntry) -> Unit)? = null
    var onEdit: ((PhraseEntry) -> Unit)? = null
    var onDelete: ((PhraseEntry) -> Unit)? = null
    var onReorder: ((List<String>) -> Unit)? = null

    private val scroll = ScrollView(context)
    private val list = LinearLayout(context)
    private val emptyView = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var items: List<PhraseEntry> = emptyList()

    /** 正在被拖动的条目下标（-1 = 没有） */
    private var draggingIndex = -1

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(4), dp(8), dp(4))

        list.orientation = VERTICAL
        scroll.addView(list)
        scroll.isFillViewport = true
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView.apply {
            gravity = Gravity.CENTER
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(24), dp(12), dp(24))
            visibility = GONE
        }
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.keyboardBackground)
        emptyView.setTextColor(theme.candidateMutedText)
        render(items)
    }

    /** 只做列表重建；常用语数量很小，且只在数据真正变化时调用（不是每次按键） */
    fun render(items: List<PhraseEntry>) {
        this.items = items
        list.removeAllViews()
        draggingIndex = -1

        if (items.isEmpty()) {
            emptyView.visibility = VISIBLE
            emptyView.text = "还没有常用语\n点击右上角 ＋ 添加常用语"
            return
        }
        emptyView.visibility = GONE

        items.forEachIndexed { index, entry ->
            list.addView(buildItem(entry, index), LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildItem(entry: PhraseEntry, index: Int): View {
        val wrapper = LinearLayout(context).apply { orientation = VERTICAL }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 固定高度：正文永远一行，条目高度不随文本长度变化
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP))
            setPadding(dp(4), 0, dp(4), 0)
        }

        // ≡ 拖动手柄：**只有**从这里开始触摸才排序
        val handle = TextView(context).apply {
            text = "≡"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(theme.candidateMutedText)
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(dp(HANDLE_WIDTH_DP), LayoutParams.MATCH_PARENT)
        }
        handle.setOnTouchListener(makeDragListener(index))

        // 正文：单击上屏（用完整原文）
        val text = TextView(context).apply {
            this.text = entry.text
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.candidateText)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { onUse?.invoke(entry) }
        }

        val edit = PanelIconSlot(context, PanelIcon.EDIT).apply {
            layoutParams = LayoutParams(dp(SLOT_WIDTH_DP), LayoutParams.MATCH_PARENT)
            applyTheme(theme, theme.candidateMutedText)
            onSlotClick = { onEdit?.invoke(entry) }
        }

        val remove = PanelIconSlot(context, PanelIcon.TRASH).apply {
            layoutParams = LayoutParams(dp(SLOT_WIDTH_DP), LayoutParams.MATCH_PARENT)
            applyTheme(theme, theme.candidateMutedText)
            onSlotClick = { onDelete?.invoke(entry) }
        }

        row.addView(handle)
        row.addView(text)
        row.addView(edit)
        row.addView(remove)
        wrapper.addView(row)

        // 固定 1px 分隔线：让常用语之间清楚分开（不做大卡片）
        wrapper.addView(View(context).apply {
            setBackgroundColor(theme.divider)
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(1)))

        return wrapper
    }

    /**
     * 从手柄开始拖动排序。
     *
     * 不要求长按：**按下手柄即可拖动**，正文/编辑/删除都不受影响。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDragListener(index: Int): OnTouchListener {
        var downRawY = 0f
        var rowView: View? = null

        return OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    draggingIndex = index
                    downRawY = event.rawY
                    // 轻量高亮被拖动的那一条（不发光、不缩放）
                    rowView = list.getChildAt(index)
                    rowView?.setBackgroundColor(theme.panelSurfacePressed)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val view = rowView ?: return@OnTouchListener true
                    view.translationY = event.rawY - downRawY
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val view = rowView
                    view?.translationY = 0f
                    view?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    rowView = null
                    val from = draggingIndex
                    draggingIndex = -1
                    if (from >= 0) {
                        val to = targetIndexFor(event.rawY, from)
                        if (to != from) {
                            onReorder?.invoke(
                                moved(items.map { it.phraseId }.toMutableList(), from, to),
                            )
                        }
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 按手指位置找目标插入下标（用每条的屏幕中心判断，稳定直观） */
    private fun targetIndexFor(rawY: Float, from: Int): Int {
        val location = IntArray(2)
        var target = from
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            child.getLocationOnScreen(location)
            val center = location[1] + child.height / 2f
            if (rawY >= center && i >= target) target = i
            if (rawY < center && i < target) target = i
        }
        return target.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    }

    private fun moved(ids: MutableList<String>, from: Int, to: Int): List<String> {
        if (from == to || from !in ids.indices || to !in ids.indices) return ids
        val id = ids.removeAt(from)
        ids.add(to, id)
        return ids
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ROW_HEIGHT_DP = 46
        const val HANDLE_WIDTH_DP = 36
        const val SLOT_WIDTH_DP = 40
    }
}
