package com.board.ime.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import com.board.ime.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 设置页的小控件工厂（**视觉规范见根目录 `DESIGN.md`**）。
 *
 * 设置页大多是「分组标题 + 说明 + 一个开关 / 一组按钮 / 一个输入框 / 一行入口」这类重复结构，
 * 集中在这里生成，避免每个页面各写一套。
 *
 * 令牌来源：
 * - 颜色：主题属性（`colorSurface` / `colorOnSurface` / `colorOnSurfaceVariant` /
 *   `colorOutline` / `colorPrimary` / `colorPrimaryContainer`），**不写死黑白**；
 * - 尺寸：`dimens.xml` 的 `app_*`（圆角 8/12/16、类型刻度、行高）；
 * - 圆角：卡片 16dp、输入框 12dp、图标底 12dp，按钮 / 分段用 Material 默认 pill。
 *
 * 结构约定：卡片 = `surface` + 1dp `outline`，**无阴影**；分组标题是 11sp 的次级标签；
 * 入口行最小 56dp，带一个 36dp 的强调色图标底。
 */
object SettingsUi {

    // ---------------------------------------------------------------- 布局骨架

    fun screen(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20f), dp(context, 4f), dp(context, 20f), dp(context, 28f))
    }

    /** 分组内容容器（本身不画背景，交给 [cardView] 包一层卡片） */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /** 卡片外壳：surface + 1dp outline，无阴影，16dp 内边距 */
    fun cardView(context: Context, content: View): MaterialCardView = MaterialCardView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 12f) }
        radius = dimen(context, R.dimen.app_card_corner_radius).toFloat()
        cardElevation = 0f
        strokeWidth = dp(context, 1f)
        setStrokeColor(attrColor(context, com.google.android.material.R.attr.colorOutline))
        setCardBackgroundColor(attrColor(context, com.google.android.material.R.attr.colorSurface))
        val pad = dimen(context, R.dimen.app_card_padding)
        setContentPadding(pad, pad, pad, pad)
        addView(content)
    }

    // ---------------------------------------------------------------- 文字

    /** 卡片内的小标题：11sp 次级标签，字距略放开 */
    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_micro).toFloat())
        setTypeface(typeface, Typeface.BOLD)
        letterSpacing = 0.06f
        setTextColor(secondaryColor(context))
        setPadding(0, 0, 0, dp(context, 10f))
    }

    fun label(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_body).toFloat())
        setTextColor(primaryTextColor(context))
    }

    fun hint(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_caption).toFloat())
        setTextColor(secondaryColor(context))
        setLineSpacing(dp(context, 3f).toFloat(), 1f)
        setPadding(0, dp(context, 2f), 0, dp(context, 6f))
    }

    fun value(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_subhead).toFloat())
        setTextColor(secondaryColor(context))
        setLineSpacing(dp(context, 2f).toFloat(), 1f)
        setPadding(0, dp(context, 4f), 0, 0)
    }

    // ---------------------------------------------------------------- 按钮

    /** 次要按钮（描边）。 */
    fun button(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            styleButton(context)
            setOnClickListener { onClick() }
        }

    /** 主要按钮（强调色填充）。用于一组里最重要的那一个动作。 */
    fun primaryButton(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            styleButton(context)
            setOnClickListener { onClick() }
        }

    private fun MaterialButton.styleButton(context: Context) {
        minWidth = 0
        minimumHeight = dimen(context, R.dimen.app_row_min_height) / 4 * 3
        insetTop = 0
        insetBottom = 0
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_subhead).toFloat())
        letterSpacing = 0f
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

    // ---------------------------------------------------------------- 行

    /** 一行开关：整行可点，右侧 MaterialSwitch。 */
    fun switchRow(
        context: Context,
        text: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit,
    ): MaterialSwitch = MaterialSwitch(context).apply {
        this.text = text
        isChecked = checked
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_body).toFloat())
        setTextColor(primaryTextColor(context))
        setPadding(0, dp(context, 6f), 0, dp(context, 6f))
        minimumHeight = dimen(context, R.dimen.app_row_min_height) / 4 * 3
        setOnCheckedChangeListener { _, value -> onChanged(value) }
    }

    /**
     * 一行数值调节：`名称  值` + 下面一根 SeekBar。
     * 拖动过程中就回调 [onChange]（实时预览），不等到松手。
     */
    fun sliderRow(
        context: Context,
        label: String,
        value: Float,
        min: Float,
        max: Float,
        unit: String,
        onChange: (Float) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(context, 10f), 0, 0)
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val valueView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_subhead).toFloat())
            setTextColor(secondaryColor(context))
            text = formatValue(value, unit)
        }
        header.addView(
            TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_body).toFloat())
                setTextColor(primaryTextColor(context))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        header.addView(valueView)
        row.addView(header)

        val steps = 100
        val span = (max - min).takeIf { it > 0f } ?: 1f
        row.addView(
            android.widget.SeekBar(context).apply {
                this.max = steps
                progress = (((value - min) / span) * steps).toInt().coerceIn(0, steps)
                setOnSeekBarChangeListener(
                    object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: android.widget.SeekBar?,
                            progress: Int,
                            fromUser: Boolean,
                        ) {
                            val resolved = min + span * (progress.toFloat() / steps)
                            valueView.text = formatValue(resolved, unit)
                            if (fromUser) onChange(resolved)
                        }

                        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
                    },
                )
            },
        )
        return row
    }

    private fun formatValue(value: Float, unit: String): String = when (unit) {
        "x" -> String.format(java.util.Locale.US, "%.2fx", value)
        "dp" -> String.format(java.util.Locale.US, "%.0fdp", value)
        else -> String.format(java.util.Locale.US, "%.1f%s", value, unit)
    }

    /** 单选分段控件：Material 默认 pill 外观，返回的 group 上按 index 取选中项。 */
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
                minimumHeight = dimen(context, R.dimen.app_row_min_height) / 4 * 3
                insetTop = 0
                insetBottom = 0
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_caption).toFloat())
                letterSpacing = 0f
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
            if (password) {
                // 默认打码（真机上出现过「一打开页面密码就是明文」）。
                //
                // 实测：Material 的密码可见性 delegate 会在**挂载/首次布局**时把「可见」写进
                // EditText，所以我们这里 post 到消息队列尾部，保证打码是最后生效的那次；
                // 再让它以「已打码」的状态重新初始化一次，使内部状态与真实状态一致
                // （否则第一次点眼睛会出现「点了没反应」）。
                // 之后显示/隐藏仍由 Material 自己的 toggle 处理，行为标准。
                edit.post {
                    edit.transformationMethod = PasswordTransformationMethod.getInstance()
                    endIconMode = TextInputLayout.END_ICON_NONE
                    endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                }
            }
        }
        return layout to edit
    }

    /**
     * 首页 / 卡片里的「图标 + 标题 + 一句话说明 + 右侧箭头」入口行。
     *
     * 有 [iconRes] 时左侧是一个 36dp 的强调色图标底（对齐 iOS 设置那种一屏一眼能扫完的列表）。
     */
    fun entryRow(
        context: Context,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ): LinearLayout = entryRow(context, 0, title, summary, onClick)

    fun entryRow(
        context: Context,
        @DrawableRes iconRes: Int,
        title: String,
        summary: String,
        onClick: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        minimumHeight = dimen(context, R.dimen.app_row_min_height)
        setPadding(0, dp(context, 12f), 0, dp(context, 12f))
        val ripple = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        if (ripple.resourceId != 0) setBackgroundResource(ripple.resourceId)
        setOnClickListener { onClick() }

        if (iconRes != 0) addView(iconTile(context, iconRes))

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (iconRes != 0) marginStart = dp(context, 14f) }
                addView(label(context, title))
                addView(
                    TextView(context).apply {
                        text = summary
                        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_caption).toFloat())
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

    /** 36dp 的强调色图标底：圆角方块 + 居中图标。 */
    fun iconTile(context: Context, @DrawableRes iconRes: Int): View {
        val size = dimen(context, R.dimen.app_icon_tile)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dimen(context, R.dimen.app_radius_md).toFloat()
            setColor(attrColor(context, com.google.android.material.R.attr.colorPrimaryContainer))
        }
        return android.widget.FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = bg
            addView(
                ImageView(context).apply {
                    setImageResource(iconRes)
                    setColorFilter(attrColor(context, androidx.appcompat.R.attr.colorPrimary))
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        dimen(context, R.dimen.app_icon_in_tile),
                        dimen(context, R.dimen.app_icon_in_tile),
                        Gravity.CENTER,
                    )
                },
            )
        }
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
        setPadding(0, dp(context, 7f), 0, dp(context, 7f))
        addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        if (ok) {
                            attrColor(context, androidx.appcompat.R.attr.colorPrimary)
                        } else {
                            attrColor(context, com.google.android.material.R.attr.colorOutline)
                        },
                    )
                }
                layoutParams = LinearLayout.LayoutParams(dp(context, 8f), dp(context, 8f))
                    .apply { marginEnd = dp(context, 10f) }
            },
        )
        addView(
            TextView(context).apply {
                text = name
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_callout).toFloat())
                setTextColor(primaryTextColor(context))
                setPadding(0, 0, dp(context, 10f), 0)
            },
        )
        addView(
            TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_callout).toFloat())
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

    /** 卡片内分隔线：1dp、`outlineVariant`，左右不缩进。 */
    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 1f),
        ).apply { topMargin = dp(context, 6f); bottomMargin = dp(context, 6f) }
        setBackgroundColor(attrColor(context, com.google.android.material.R.attr.colorOutlineVariant))
    }

    // ---------------------------------------------------------------- 主题取色

    private fun primaryTextColor(context: Context): Int =
        attrColor(context, com.google.android.material.R.attr.colorOnSurface)

    private fun secondaryColor(context: Context): Int =
        attrColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

    private fun attrColor(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun dimen(context: Context, res: Int): Int = context.resources.getDimensionPixelSize(res)

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- WindowInsets

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
}
