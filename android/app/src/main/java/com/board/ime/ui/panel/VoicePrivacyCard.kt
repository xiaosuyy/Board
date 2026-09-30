package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.ui.KeyboardTheme

/**
 * 语音隐私说明卡片（覆盖在键盘上方）。
 *
 * 直接塞进输入法自己的窗口里，**不依赖系统弹窗权限、不依赖 Toast**
 * （真机上 Board 的 Toast 可能被系统抑制）。
 *
 * 两种用法：
 * - `consent = true`：第一次点语音时的一次性确认（取消 / 继续）
 * - `consent = false`：语音面板底部说明点的“完整说明”（关闭）
 *
 * 只讲实话：系统 SpeechRecognizer 对应的识别服务**可能本地、也可能联网**，
 * 因此不写“完全本地”“绝不上传”“绝不留存”。
 */
class VoicePrivacyCard @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 点「继续」：允许使用系统语音识别 */
    var onAccept: (() -> Unit)? = null

    /** 点「取消」/「关闭」：什么都不做（不启动录音） */
    var onDismiss: (() -> Unit)? = null

    private val card = LinearLayout(context)
    private val titleView = TextView(context)
    private val bodyView = TextView(context)
    private val serviceView = TextView(context)
    private val cancelButton = TextView(context)
    private val acceptButton = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    init {
        setBackgroundColor(SCRIM)
        isClickable = true // 吃掉点击，避免穿透到键盘

        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(14), dp(16), dp(12))

        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        titleView.setTypeface(titleView.typeface, android.graphics.Typeface.BOLD)
        card.addView(titleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        bodyView.setLineSpacing(0f, 1.15f)
        card.addView(
            bodyView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )

        serviceView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        card.addView(
            serviceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(
            cancelButton,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        buttons.addView(
            acceptButton,
            LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            },
        )
        card.addView(
            buttons,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )

        addView(
            card,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
                marginStart = dp(12)
                marginEnd = dp(12)
            },
        )

        cancelButton.setOnClickListener { onDismiss?.invoke() }
        acceptButton.setOnClickListener { onAccept?.invoke() }
        // 点卡片外面 = 取消
        setOnClickListener { onDismiss?.invoke() }
        card.setOnClickListener { /* 吃掉，避免点到卡片就取消 */ }
    }

    /**
     * @param consent true=首次确认（取消 / 继续）；false=完整说明（关闭）
     * @param serviceLabel 能可靠取到识别服务应用名时传入；取不到就传 null（不猜名字）
     */
    fun bind(theme: KeyboardTheme, serviceLabel: String?, consent: Boolean) {
        this.theme = theme

        titleView.text = if (consent) "使用系统语音识别" else "语音隐私说明"
        bodyView.text = buildString {
            append("语音会交给设备提供的系统语音识别服务处理，可能联网发送给该服务。")
            append("Board 不保存语音，也不保存识别结果，不建立识别历史。")
            append("该服务是否在服务端留存数据由服务提供方决定，Board 无法代为保证。")
        }

        if (serviceLabel.isNullOrBlank()) {
            serviceView.visibility = GONE
        } else {
            serviceView.visibility = VISIBLE
            serviceView.text = "处理服务：$serviceLabel"
        }

        cancelButton.text = if (consent) "取消" else "关闭"
        acceptButton.visibility = if (consent) VISIBLE else GONE
        acceptButton.text = "继续"

        card.background = GradientDrawable().apply {
            setColor(theme.panelBackground)
            cornerRadius = dp(14).toFloat()
        }
        titleView.setTextColor(theme.candidateText)
        bodyView.setTextColor(theme.candidateText)
        serviceView.setTextColor(theme.candidateMutedText)
        styleButton(cancelButton, theme.candidateText, theme.panelSurfacePressed)
        styleButton(acceptButton, theme.panelBackground, theme.candidateText)

        serviceView.visibility = if (serviceLabel.isNullOrBlank()) GONE else VISIBLE
    }

    private fun styleButton(view: TextView, textColor: Int, backgroundColor: Int) {
        view.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackground(
                GradientDrawable().apply {
                    setColor(backgroundColor)
                    cornerRadius = dp(18).toFloat()
                },
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 半透明遮罩：让键盘暂时退到后面，但不引入渐变/发光 */
        val SCRIM = Color.argb(0x99, 0, 0, 0)
    }
}
