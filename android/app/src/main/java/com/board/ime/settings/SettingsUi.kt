package com.board.ime.settings

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.board.ime.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 设置页的小控件工厂。
 *
 * 设置页大多是「标题 + 说明 + 一个开关 / 一组按钮 / 一个输入框」这类重复结构，
 * 集中在这里生成，避免每个页面各写一套，也避免再堆一个巨大的 XML。
 * 间距 / 圆角都取统一的设计令牌（`dimens.xml` 里的 `kb_radius_*`、`kb_space_*`）。
 */
object SettingsUi {

    fun screen(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20f), dp(context, 8f), dp(context, 20f), dp(context, 24f))
    }

    /** 分组卡片 */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 16f) }
    }

    fun cardView(context: Context, content: View): MaterialCardView = MaterialCardView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 12f) }
        radius = dp(context, 14f).toFloat()
        cardElevation = 0f
        strokeWidth = dp(context, 1f)
        setContentPadding(dp(context, 16f), dp(context, 16f), dp(context, 16f), dp(context, 16f))
        addView(content)
    }

    /** 卡片内的小标题 */
    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(context, 4f))
        setTextColor(secondaryColor(context))
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    }

    fun hint(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(secondaryColor(context))
        setPadding(0, dp(context, 2f), 0, dp(context, 8f))
    }

    fun value(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(secondaryColor(context))
        setPadding(0, dp(context, 4f), 0, 0)
    }

    fun button(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            minWidth = 0
            setOnClickListener { onClick() }
        }

    fun buttonRow(context: Context, buttons: List<MaterialButton>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            for ((index, button) in buttons.withIndex()) {
                button.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index > 0) marginStart = dp(context, 8f) }
                addView(button)
            }
        }

    fun switchRow(
        context: Context,
        text: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): MaterialSwitch = MaterialSwitch(context).apply {
        this.text = text
        isChecked = checked
        setPadding(0, dp(context, 8f), 0, dp(context, 8f))
        setOnCheckedChangeListener { _, value -> onChanged(value) }
    }

    /** 单选按钮组：返回的 group 上按 index 取选中项 */
    fun toggleGroup(
        context: Context,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ): MaterialButtonToggleGroup = MaterialButtonToggleGroup(context).apply {
        isSingleSelection = true
        for ((index, option) in options.withIndex()) {
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = option
                minWidth = 0
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isCheckable = true
            }
            addView(button)
            if (index == selectedIndex) check(button.id)
        }
        addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val index = (0 until group.childCount).firstOrNull { group.getChildAt(it).id == checkedId }
            if (index != null) onSelect(index)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 输入框（密码框默认打码，且永远不回显明文以外的信息） */
    fun textField(
        context: Context,
        hintText: String,
        initial: String,
        password: Boolean = false,
    ): Pair<TextInputLayout, TextInputEditText> {
        val edit = TextInputEditText(context).apply {
            setText(initial)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            setSingleLine(true)
        }
        val layout = TextInputLayout(
            context,
            null,
            com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox,
        ).apply {
            hint = hintText
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 8f) }
            addView(edit)
        }
        return layout to edit
    }

    /** 首页那种「标题 + 一句话说明 + 右侧箭头」的入口行 */
    fun entryRow(
        context: Context,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        setPadding(0, dp(context, 14f), 0, dp(context, 14f))
        setOnClickListener { onClick() }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(label(context, title))
                addView(
                    TextView(context).apply {
                        text = summary
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(secondaryColor(context))
                        setPadding(0, dp(context, 2f), 0, 0)
                    },
                )
            },
        )
        addView(
            TextView(context).apply {
                text = "›"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(secondaryColor(context))
            },
        )
    }

    /** 一条状态：`● 名称：值`，只显示最重要的状态，不带日志/HTTP 码 */
    fun statusRow(
        context: Context,
        name: String,
        value: String,
        ok: Boolean,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(context, 6f), 0, dp(context, 6f))
        addView(
            TextView(context).apply {
                text = "●"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(if (ok) accentColor(context) else secondaryColor(context))
                setPadding(0, 0, dp(context, 8f), 0)
            },
        )
        addView(
            TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 0, dp(context, 8f), 0)
            },
        )
        addView(
            TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(secondaryColor(context))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
    }

    fun spacer(context: Context, heightDp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, heightDp),
        )
    }

    /**
     * 让根布局避开状态栏 / 挖孔。
     *
     * Android 15（targetSdk 35+）强制 edge-to-edge：不加这一步，标题会画到状态栏底下。
     *
     * 关键：设置页会**重建内容视图**（例如点「立即同步」后重新 render），
     * 新视图 padding 从 0 开始，而 WindowInsets 是**异步**重发的 ——
     * 中间那一瞬间标题就会跳到状态栏下面。所以这里缓存上次的 inset，重建时立刻套用。
     */
    @Volatile
    private var cachedTopInset = 0

    @Volatile
    private var cachedBottomInset = 0

    fun applySystemBarInset(view: View) {
        val originalTop = view.paddingTop
        val originalBottom = view.paddingBottom
        val extraTop = (8 * view.resources.displayMetrics.density).toInt()

        // 先用缓存值立即撑开，避免重建瞬间 padding 归零
        view.setPadding(
            view.paddingLeft,
            cachedTopInset + originalTop + extraTop,
            view.paddingRight,
            cachedBottomInset + originalBottom,
        )

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            cachedTopInset = bars.top
            cachedBottomInset = bars.bottom
            target.setPadding(
                target.paddingLeft,
                bars.top + originalTop + extraTop,
                target.paddingRight,
                bars.bottom + originalBottom,
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(view)
    }

    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            1,
        ).apply { topMargin = dp(context, 6f); bottomMargin = dp(context, 6f) }
        setBackgroundColor(secondaryColor(context))
        alpha = 0.25f
    }

    // 颜色统一从主题取，避免这里写死黑白
    private fun secondaryColor(context: Context): Int =
        resolve(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

    private fun accentColor(context: Context): Int =
        resolve(context, androidx.appcompat.R.attr.colorPrimary)

    private fun resolve(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
