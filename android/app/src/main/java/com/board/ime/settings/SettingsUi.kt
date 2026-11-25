package com.board.ime.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
import androidx.core.graphics.ColorUtils
import com.board.ime.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 设置页的小控件工厂（视觉参考 Lyra：大标题、卡片外分组标签、无描边卡片、蓝色图标）。
 *
 * 设置页大多是「分组标题 + 说明 + 一个开关 / 一组按钮 / 一个输入框 / 一行入口」这类重复结构，
 * 集中在这里生成，避免每个页面各写一套。
 *
 * 令牌来源：
 * - 颜色：主题属性（`colorSurface` / `colorOnSurface` / `colorOnSurfaceVariant` /
 *   `colorOutline` / `colorPrimary`），**不写死黑白**；
 * - 尺寸：`dimens.xml` 的 `app_*`（圆角 10/16/28、类型刻度、行高）；
 *
 * 结构约定（参考 Lyra）：卡片 = `surface` 色、28dp 圆角、**无描边无阴影**；
 * 分组标题是卡片外的一行 12sp 淡色标签；入口行最小 60dp，左侧是 22dp 蓝色图标本体
 * （不再加色块底），右侧是蓝色箭头。
 */
object SettingsUi {

    // ---------------------------------------------------------------- 布局骨架

    fun screen(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            dimen(context, R.dimen.app_screen_padding),
            dp(context, 6f),
            dimen(context, R.dimen.app_screen_padding),
            dp(context, 32f),
        )
    }

    /** 分组内容容器（本身不画背景，交给 [cardView] 包一层卡片） */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    /**
     * 卡片外壳：surface、28dp 圆角、**无描边无阴影**。
     *
     * 内容第一行如果是 [heading]，会被提到卡片**外面**（Lyra 的分组标签就挂在卡片上方）。
     * 这样所有现有页面不用改调用方式就能得到「标签在卡外」的排版。
     */
    fun cardView(context: Context, content: View): LinearLayout {
        val card = MaterialCardView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            radius = dimen(context, R.dimen.app_card_corner_radius).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(attrColor(context, com.google.android.material.R.attr.colorSurface))
            val pad = dimen(context, R.dimen.app_card_padding)
            setContentPadding(pad, dp(context, 12f), pad, dp(context, 14f))
            addView(content)
        }

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 16f) }
        }
        if (content is LinearLayout && content.childCount > 0) {
            val first = content.getChildAt(0)
            if (first is TextView && first.tag == HEADING_TAG) {
                content.removeViewAt(0)
                first.setPadding(dp(context, 8f), dp(context, 22f), 0, dp(context, 8f))
                (wrapper.layoutParams as LinearLayout.LayoutParams).topMargin = 0
                wrapper.addView(first)
            }
        }
        wrapper.addView(card)
        return wrapper
    }

    private const val HEADING_TAG = "settings_heading"

    // ---------------------------------------------------------------- 文字

    /** 分组标签：12sp、46% 主文字色（Lyra 的 "应用 / 播放" 式标签） */
    fun heading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_micro).toFloat())
        setTypeface(typeface, Typeface.NORMAL)
        letterSpacing = 0f
        setTextColor(ColorUtils.setAlphaComponent(primaryTextColor(context), (0.46f * 255).toInt()))
        setPadding(0, 0, 0, dp(context, 8f))
        tag = HEADING_TAG
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

    /** 次要按钮：淡灰填充、无描边、16dp 圆角（Lyra 的 secondary 按钮） */
    fun button(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            this.text = text
            styleButton(context)
            strokeWidth = 0
            backgroundTintList = ColorStateList.valueOf(translucent(primaryTextColor(context), 0.08f))
            setTextColor(primaryTextColor(context))
            setOnClickListener { onClick() }
        }

    /** 主要按钮（强调色填充）。用于一组里最重要的那一个动作。 */
    fun primaryButton(context: Context, text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            styleButton(context)
            backgroundTintList = ColorStateList.valueOf(attrColor(context, androidx.appcompat.R.attr.colorPrimary))
            setTextColor(attrColor(context, com.google.android.material.R.attr.colorOnPrimary))
            setOnClickListener { onClick() }
        }

    private fun MaterialButton.styleButton(context: Context) {
        minWidth = 0
        minimumHeight = dp(context, 40f)
        insetTop = 0
        insetBottom = 0
        insetLeft = 0
        insetRight = 0
        isAllCaps = false
        cornerRadius = dimen(context, R.dimen.app_radius_md)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_subhead).toFloat())
        letterSpacing = 0f
        setPadding(dp(context, 20f), paddingTop, dp(context, 20f), paddingBottom)
        // 单独放进卡片时按内容收窄，别铺成一大块；连续按钮之间留出间距。
        // buttonRow 会重新指定 layoutParams（等比平分），不受这里影响。
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(context, 8f) }
    }

    fun buttonRow(context: Context, buttons: List<MaterialButton>): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 4f), 0, dp(context, 4f))
            for ((index, button) in buttons.withIndex()) {
                button.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (index > 0) marginStart = dp(context, 12f) }
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
        setPadding(0, dp(context, 8f), 0, dp(context, 8f))
        minimumHeight = dimen(context, R.dimen.app_row_min_height)
        // Lyra / iOS 的开关：打开 = 绿色轨道 + 白色滑块；关闭 = 淡灰轨道
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.app_green),
                translucent(primaryTextColor(context), 0.22f),
            ),
        )
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
        "%" -> String.format(java.util.Locale.US, "%.0f%%", value)
        else -> String.format(java.util.Locale.US, "%.1f%s", value, unit)
    }

    /** 单选分段控件：淡灰轨道 + 选中项浅蓝填充（无描边），返回的 group 上按 index 取选中项。 */
    fun toggleGroup(
        context: Context,
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit,
    ): MaterialButtonToggleGroup = MaterialButtonToggleGroup(context).apply {
        isSingleSelection = true
        val pad = dp(context, 3f)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dimen(context, R.dimen.app_radius_md).toFloat()
            setColor(translucent(primaryTextColor(context), 0.06f))
        }
        val checkedStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        val trackColors = ColorStateList(
            checkedStates,
            intArrayOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.app_accent_container),
                Color.TRANSPARENT,
            ),
        )
        val textColors = ColorStateList(
            checkedStates,
            intArrayOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.app_accent),
                primaryTextColor(context),
            ),
        )
        for ((index, option) in options.withIndex()) {
            val button = MaterialButton(
                context,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = option
                minWidth = 0
                minimumHeight = dp(context, 40f)
                insetTop = 0
                insetBottom = 0
                insetLeft = 0
                insetRight = 0
                strokeWidth = 0
                cornerRadius = dimen(context, R.dimen.app_radius_md) - pad
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_PX, dimen(context, R.dimen.app_text_caption).toFloat())
                letterSpacing = 0f
                // 选项多时（键盘高度 5 档）默认 16dp 内边距会把
                // 「标准 / 特大 / 自定义」截成「标… / 特… / 自…」，这里收紧为 4dp。
                setPadding(dp(context, 4f), paddingTop, dp(context, 4f), paddingBottom)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isCheckable = true
                backgroundTintList = trackColors
                setTextColor(textColors)
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
            // 去掉 Material 自带的底部横线（Lyra 的输入框只有底色）
            background = null
            setPadding(dp(context, 14f), dp(context, 13f), dp(context, 14f), dp(context, 13f))
        }
        val radius = dimen(context, R.dimen.app_radius_md)
        val layout = TextInputLayout(
            context,
            null,
            com.google.android.material.R.style.Widget_Material3_TextInputLayout_OutlinedBox,
        ).apply {
            hint = hintText
            // 无描边、淡灰填充（Lyra 的输入框）：只留底色和 16dp 圆角
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            setBoxBackgroundColor(translucent(primaryTextColor(context), 0.08f))
            setBoxCornerRadii(radius.toFloat(), radius.toFloat(), radius.toFloat(), radius.toFloat())
            boxStrokeWidth = 0
            boxStrokeWidthFocused = 0
            if (password) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(context, 10f) }
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
                    .apply { if (iconRes != 0) marginStart = dp(context, 16f) }
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
                setTextColor(attrColor(context, androidx.appcompat.R.attr.colorPrimary))
            },
        )
    }

    /** 入口行左侧：22dp 的强调色图标本体（Lyra 那种不带色块底的写法）。 */
    fun iconTile(context: Context, @DrawableRes iconRes: Int): View {
        val size = dimen(context, R.dimen.app_icon_in_tile)
        return ImageView(context).apply {
            setImageResource(iconRes)
            setColorFilter(attrColor(context, androidx.appcompat.R.attr.colorPrimary))
            layoutParams = LinearLayout.LayoutParams(size, size)
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
        setPadding(0, dp(context, 10f), 0, dp(context, 10f))
        addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        if (ok) {
                            androidx.core.content.ContextCompat.getColor(context, R.color.app_green)
                        } else {
                            translucent(primaryTextColor(context), 0.3f)
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

    /** 行间分隔线：1dp、主文字色 10%，左侧让过图标（Lyra 的 inset separator）。 */
    fun divider(context: Context): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(context, 1f),
        ).apply { marginStart = dimen(context, R.dimen.app_divider_inset) }
        setBackgroundColor(translucent(primaryTextColor(context), 0.10f))
    }

    // ---------------------------------------------------------------- 主题取色

    private fun primaryTextColor(context: Context): Int =
        attrColor(context, com.google.android.material.R.attr.colorOnSurface)

    private fun secondaryColor(context: Context): Int =
        attrColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant)

    /** 把主题色压成淡色（铺在卡片底色上做填充，不用写死透明灰）。 */
    private fun translucent(color: Int, fraction: Float): Int =
        ColorUtils.setAlphaComponent(color, (fraction * 255).toInt().coerceIn(0, 255))

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
