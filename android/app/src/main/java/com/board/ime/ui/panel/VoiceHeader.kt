package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.ui.KeyboardTheme
import com.board.ime.voice.VoiceState

/**
 * 语音状态下的顶部面板（与其它状态**共用同一个固定高度** [KeyboardTheme.topPanelHeight]）。
 *
 * ```
 * [←]  今天天气怎么样
 * ```
 * - **左侧固定返回**；右侧**没有**确认按钮（避免"确认/提交"语义）
 * - 中间整块区域就是状态/识别文本，**整块可点**（不是只有文字像素）
 * - 成功时直接显示识别文本（不显示"识别成功/已识别/完成"等多余文字）
 * - 字号**自适应**：10sp～19sp 之间自动缩放，最多两行，任何情况下不超出 56dp、不遮挡返回键
 * - 视觉沿用 Board 现有黑白灰 + 轻量按压反馈，无渐变/发光/大色块
 */
class VoiceHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    /** 左侧返回：退出语音状态（成功结果也会被丢弃，不提交） */
    var onBack: (() -> Unit)? = null

    /**
     * 点击中间文字。
     * 语义交给会话决定：LISTENING = 结束收音；RESULT = 提交识别文本。
     */
    var onPrimary: (() -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private val backSlot = PanelIconSlot(context, PanelIcon.BACK)
    private val textView = TextView(context)
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var pressed = false
    private var primaryEnabled = false

    // 必须在 init 之前声明：init 里的 applyTheme() 会用到
    private var lastState: VoiceState = VoiceState.IDLE
    private var lastText: String = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // 面板内裁切：排版意外绝不允许压到键盘上
        clipChildren = true
        clipToPadding = true

        addView(backSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        textView.apply {
            // 关键：setSingleLine(false) 内部会调用 setMaxLines(MAX_VALUE)，会覆盖前面设的 maxLines，
            // 所以必须先设 SingleLine 再设 maxLines，否则两行限制失效，长文本无限换行溢出 56dp。
            isSingleLine = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = false
            // Android 原生自适应字号：在 56dp 内自动缩放到刚好放得下
            setAutoSizeTextTypeUniformWithConfiguration(
                MIN_TEXT_SP.toInt(),
                MAX_TEXT_SP.toInt(),
                1,
                TypedValue.COMPLEX_UNIT_SP,
            )
        }
        addView(textView, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))

        backSlot.onSlotClick = { onBack?.invoke() }

        textView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (primaryEnabled) {
                        pressed = true
                        invalidate()
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val inside = event.x >= 0f && event.y >= 0f &&
                        event.x <= textView.width && event.y <= textView.height
                    if (pressed != (inside && primaryEnabled)) {
                        pressed = inside && primaryEnabled
                        invalidate()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val shouldFire = pressed && primaryEnabled &&
                        event.x >= 0f && event.y >= 0f &&
                        event.x <= textView.width && event.y <= textView.height
                    pressed = false
                    invalidate()
                    if (shouldFire) {
                        performClick()
                        onPrimary?.invoke()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    invalidate()
                    true
                }

                else -> false
            }
        }

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        backSlot.applyTheme(theme)
        render(lastState, lastText)
        requestLayout()
        invalidate()
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 整块文字区域的轻量按压反馈（复用现有按压色，不引入新样式）
        if (pressed) {
            pressPaint.color = theme.panelSurfacePressed
            canvas.drawRoundRect(
                textView.left.toFloat(),
                0f,
                textView.right.toFloat(),
                height.toFloat(),
                theme.radiusSmall * 2f,
                theme.radiusSmall * 2f,
                pressPaint,
            )
        }
    }

    /** 渲染：LISTENING/PROCESSING/RESULT 显示文本；ERROR 显示原因；都不带额外前缀 */
    fun render(state: VoiceState, text: String) {
        lastState = state
        lastText = text
        textView.text = text
        primaryEnabled = (state == VoiceState.LISTENING || state == VoiceState.PROCESSING ||
            state == VoiceState.RESULT) && text.isNotEmpty()
        textView.setTextColor(
            when (state) {
                VoiceState.RESULT, VoiceState.LISTENING, VoiceState.PROCESSING -> theme.candidateText
                else -> theme.candidateMutedText
            },
        )
        if (!primaryEnabled && pressed) pressed = false
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        /** 自适应字号范围：短文本明显更大，长文本自动缩小 */
        const val MIN_TEXT_SP = 10f
        const val MAX_TEXT_SP = 19f
    }
}
