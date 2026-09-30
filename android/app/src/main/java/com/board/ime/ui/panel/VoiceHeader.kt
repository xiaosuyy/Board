package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.ui.KeyboardTheme
import com.board.ime.voice.VoiceState

/**
 * 语音状态下的顶部面板（与其它状态**共用同一个固定高度** [KeyboardTheme.topPanelHeight]）。
 *
 * ```
 * [←]  正在听……                    [✓]
 *      今天上海天气怎么样
 * ```
 * - 只用 Board 现有的黑白灰 + 现有图标体系，无渐变/发光/大面积蓝色
 * - 文字单行、末尾省略，**不会撑高面板**，因此键盘总高度不变
 * - 只在 LISTENING 时把标题染成强调（少量蓝色感）由主题色决定
 */
class VoiceHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onBack: (() -> Unit)? = null

    /** 结束收音（已返回的结果仍会正常回调） */
    var onFinish: (() -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val backSlot = PanelIconSlot(context, PanelIcon.BACK)
    private val finishSlot = PanelIconSlot(context, PanelIcon.CHECK)
    private val titleView = TextView(context)
    private val previewView = TextView(context)

    // 必须在 init 之前声明：init 里的 applyTheme() 会用到它们
    private var lastState: VoiceState = VoiceState.IDLE
    private var lastText: String = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(backSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleView.apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        previewView.apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        column.addView(titleView)
        column.addView(previewView)
        addView(column, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        addView(finishSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        backSlot.onSlotClick = { onBack?.invoke() }
        finishSlot.onSlotClick = { onFinish?.invoke() }
        applyTheme(theme)
        render(VoiceState.IDLE, "")
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        // 显式用 sp：直接赋值 textSize 是 px，会让文字远大于预期并溢出 56dp 面板
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
        titleView.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
        previewView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        // 面板内裁切：任何排版意外都只影响面板内部，绝不压到键盘上
        clipChildren = true
        clipToPadding = true
        backSlot.applyTheme(theme)
        finishSlot.applyTheme(theme)
        render(lastState, lastText)
        requestLayout()
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    fun render(state: VoiceState, text: String) {
        lastState = state
        lastText = text
        val listening = state == VoiceState.LISTENING || state == VoiceState.PROCESSING
        titleView.text = when (state) {
            VoiceState.LISTENING -> "正在听……"
            VoiceState.PROCESSING -> "识别中……"
            VoiceState.SUCCESS -> "已识别"
            VoiceState.ERROR -> "语音不可用"
            VoiceState.IDLE -> ""
        }
        titleView.setTextColor(if (listening) theme.iconColor else theme.candidateMutedText)
        previewView.text = text
        previewView.setTextColor(theme.candidateText)
        previewView.visibility = if (text.isEmpty()) GONE else VISIBLE
        // 结束按钮在“正在识别”时才可点（避免 IDLE 下误触）
        finishSlot.setTint(if (listening) theme.iconColor else theme.candidateMutedText)
    }

    private companion object {
        /** 面板只有 56dp 高：字号必须足够克制，标题/正文都按 sp 明确指定 */
        const val TITLE_SP = 11f
        const val BODY_SP = 10f
    }
}
