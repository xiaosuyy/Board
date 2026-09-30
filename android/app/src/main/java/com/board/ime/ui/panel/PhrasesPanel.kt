package com.board.ime.ui.panel

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
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
 * ┌────────────────────────────┐
 * │ 您好，我现在不方便接电话…   │  ← 最多 3 行，超出省略；点击=直接上屏
 * │ 谢谢您的理解。              │
 * │ 我稍后回复您。              │
 * └────────────────────────────┘
 * ```
 *
 * - **单击**：直接上屏（不写剪贴板、不弹确认）；
 * - **长按**：选中该条 → 显示 [编辑] [删除]，并且**可以继续拖动排序**；
 * - 空状态：`还没有常用语` + `点击右上角 ＋ 添加常用语`；
 * - 只有顺序**真的变了**才回调 [onReorder]（拖动中间帧不写库）。
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
    private var selectedPhraseId: String? = null
    private var draggingIndex = -1

    init {
        orientation = VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))

        list.orientation = VERTICAL
        scroll.addView(list)
        scroll.isFillViewport = true
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        emptyView.apply {
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
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

    /** 只做列表重建；常用语数量很小，且只在一行数据变化时调用（不是每次按键） */
    fun render(items: List<PhraseEntry>) {
        this.items = items
        list.removeAllViews()

        if (items.isEmpty()) {
            emptyView.visibility = VISIBLE
            emptyView.text = "还没有常用语\n点击右上角 ＋ 添加常用语"
            return
        }
        emptyView.visibility = GONE
        items.forEachIndexed { index, entry ->
            list.addView(buildRow(entry, index), LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildRow(entry: PhraseEntry, index: Int): View {
        val selected = entry.phraseId == selectedPhraseId

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = pill(
                if (selected) theme.panelSurfacePressed else theme.keyboardBackground,
            )
        }

        val textView = TextView(context).apply {
            text = entry.text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(theme.candidateText)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        column.addView(textView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // 长按后出现：编辑 / 删除（极简，不做弹层）
        if (selected) {
            val actions = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(6), 0, 0)
            }
            val edit = actionButton("编辑")
            val del = actionButton("删除")
            edit.setOnClickListener { onEdit?.invoke(entry) }
            del.setOnClickListener { onDelete?.invoke(entry) }
            actions.addView(edit, actionParams())
            actions.addView(del, actionParams())
            column.addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        // 单击上屏；长按选中（并可继续拖动排序）
        column.setOnTouchListener(makeTouchListener(entry, index, column))
        return column
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeTouchListener(entry: PhraseEntry, index: Int, row: View): OnTouchListener {
        var startY = 0f
        var longPressed = false
        var moved = false
        val longPress = Runnable { onLongPress(entry) }

        return OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    longPressed = false
                    moved = false
                    view.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!longPressed) {
                        if (kotlin.math.abs(event.rawY - startY) > dp(8)) {
                            // 手指移动了：取消长按，避免拖动时误触发
                            view.removeCallbacks(longPress)
                        }
                        return@OnTouchListener true
                    }
                    moved = true
                    handleDragOffsets(view, event.rawY)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.removeCallbacks(longPress)
                    if (longPressed) {
                        if (moved) persistOrder()
                        draggingIndex = -1
                    } else {
                        // 未长按 = 单击：直接上屏
                        onUse?.invoke(entry)
                    }
                    view.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun onLongPress(entry: PhraseEntry) {
        // 选中该条并显示编辑 / 删除
        if (selectedPhraseId != entry.phraseId) {
            selectedPhraseId = entry.phraseId
            render(items)
        }
    }

    /** 拖动时把被拖的行做纵向位移，并记录目标位置（不写库） */
    private fun handleDragOffsets(row: View, rawY: Float) {
        val dragged = list.indexOfChild(row)
        if (dragged < 0) return
        draggingIndex = dragged

        // 轻微跟手：整体位移按行高的一半为步进
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child === row) {
                child.translationY = rawY - row.top - row.height / 2f
            } else {
                child.translationY = 0f
            }
        }
    }

    /** 拖动结束时按 translationY 排序并回调（只有真的变了才回调） */
    private fun persistOrder() {
        val order = ArrayList<Pair<String, Float>>(list.childCount)
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val phraseId = items.getOrNull(i)?.phraseId ?: continue
            order += phraseId to child.translationY
        }
        for (i in 0 until list.childCount) list.getChildAt(i).translationY = 0f
        val sorted = order.sortedBy { it.second }.map { it.first }
        if (sorted != items.map { it.phraseId }) onReorder?.invoke(sorted)
    }

    private fun actionButton(label: String): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        setTextColor(theme.candidateText)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = pill(theme.panelSurfacePressed)
    }

    private fun actionParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        .apply { marginStart = dp(8) }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(10).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LONG_PRESS_MS = 350L
    }
}
