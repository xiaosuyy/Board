package com.board.ime.ui.panel

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.ui.KeyboardTheme

/**
 * 翻译状态的顶部固定面板（与其它状态**共用同一个 56dp 高度**）。
 *
 * ```
 * [←]  翻译            百度翻译 · 联网
 * ```
 * - 左侧返回（与语音面板保持一致：Board 的返回键统一在左）
 * - 右侧是**服务商透明**入口：写清用的是哪家服务、并说明需要联网；点它看完整说明
 */
class TranslationHeader @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    var onBack: (() -> Unit)? = null

    /** 点右侧服务名：查看完整隐私说明 */
    var onInfo: (() -> Unit)? = null

    private val backSlot = PanelIconSlot(context, PanelIcon.BACK)
    private val titleView = TextView(context)
    private val serviceView = TextView(context)

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )

    private var serviceLabel: String = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = true
        clipToPadding = true

        addView(backSlot, LayoutParams(theme.toolHitSize, LayoutParams.MATCH_PARENT))

        titleView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
        }
        addView(titleView, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        serviceView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            includeFontPadding = false
            // 提示可点：查看完整隐私说明
            paint.isUnderlineText = true
            setPadding(dp(8), dp(8), dp(12), dp(8))
        }
        addView(serviceView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        backSlot.onSlotClick = { onBack?.invoke() }
        serviceView.setOnClickListener { onInfo?.invoke() }

        applyTheme(theme)
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        setBackgroundColor(theme.panelBackground)
        titleView.text = "翻译"
        titleView.setTextColor(theme.candidateText)
        serviceView.setTextColor(theme.candidateMutedText)
        backSlot.applyTheme(theme)
        renderService()
    }

    /** 服务商透明：例如「百度翻译 · 联网」 */
    fun setServiceName(name: String) {
        serviceLabel = name
        renderService()
    }

    fun desiredHeight(): Int = theme.topPanelHeight

    private fun renderService() {
        serviceView.text = if (serviceLabel.isBlank()) "" else "$serviceLabel · 联网"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
