package com.board.ime.ui

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import com.board.ime.R
import com.board.ime.settings.CandidateTextSize
import com.board.ime.settings.KeyboardPalette
import com.board.ime.settings.ThemeMode
import kotlin.math.roundToInt

/**
 * 键盘渲染参数：颜色 + 尺寸（一次性解析好，View 层不再读资源）。
 *
 * - 颜色全部来自 res/values/colors.xml（kb_light_* / kb_dark_*）
 * - 尺寸来自 dimens.xml；候选词字号来自设置（[CandidateTextSize]），可即时生效
 * - 顶部面板高度是**唯一固定值**（[topPanelHeight]），四种状态共用
 */
data class KeyboardTheme(
    val isDark: Boolean,

    @param:ColorInt val keyboardBackground: Int,
    @param:ColorInt val keyBackground: Int,
    @param:ColorInt val keyPressedBackground: Int,
    @param:ColorInt val functionKeyBackground: Int,
    @param:ColorInt val functionKeyPressedBackground: Int,
    @param:ColorInt val keyText: Int,
    @param:ColorInt val functionKeyText: Int,
    @param:ColorInt val accent: Int,
    @param:ColorInt val accentText: Int,
    @param:ColorInt val divider: Int,

    /** 顶部面板背景：TOOLBAR / CANDIDATES / MORE / CLIPBOARD 全部使用同一个颜色 */
    @param:ColorInt val panelBackground: Int,
    /** 工具栏背景（默认与 [panelBackground] 相同，可单独自定义） */
    @param:ColorInt val toolbarBackground: Int,
    @param:ColorInt val candidateText: Int,
    @param:ColorInt val candidateMutedText: Int,
    @param:ColorInt val candidateSelectedBackground: Int,
    @param:ColorInt val candidateSelectedText: Int,
    @param:ColorInt val panelSurface: Int,
    @param:ColorInt val panelSurfacePressed: Int,
    /** 组词（拼音）pill 的背景：要比面板底色明显，才看得出「只包住输入的拼音」 */
    @param:ColorInt val pinyinBackground: Int,
    @param:ColorInt val iconColor: Int,

    // ---- 键盘尺寸（竖屏默认值；横屏悬浮尺寸由 KeyboardLayoutCalculator 计算）----
    val keyCornerRadius: Float,
    val keyGapHorizontal: Float,
    val keyGapVertical: Float,
    val keyHeight: Float,
    val keyboardPaddingHorizontal: Float,
    val keyboardPaddingVertical: Float,
    val keyTextSize: Float,
    val functionKeyTextSize: Float,

    // ---- 顶部面板：固定高度 + 各状态内部尺寸 ----
    val topPanelHeight: Int,
    val panelHorizontalPadding: Int,
    val panelCornerRadius: Float,
    val panelPillCornerRadius: Float,
    val dividerHeight: Int,
    val toolRowHeight: Int,
    val toolIconSize: Float,
    val toolHitSize: Int,
    val toolSidePadding: Int,
    val moreItemWidth: Int,
    val moreIconSize: Float,
    val moreLabelSize: Float,

    // ---- 候选词 ----
    val candidateTextSize: Float,
    val pinyinTextSize: Float,
    /** 候选英文释义字号（辅助信息，明显小于 [candidateTextSize]） */
    val candidateEnglishTextSize: Float,
    /** 候选 / 拼音 pill 的紧凑高度：无论面板多高，背景都只是一小块圆角 */
    val candidateChipHeight: Int,
    val candidateCornerRadius: Float,
    val candidateMinWidth: Int,
    val candidatePaddingHorizontal: Int,

    // ---- 剪贴板内容区 ----
    val clipboardCardMinHeight: Int,
    val clipboardCardRadius: Float,
    val clipboardGridGap: Int,
    val clipboardPadding: Int,
    val clipboardTextSize: Float,
    val clipboardTitleSize: Float,
    val clipboardSectionSize: Float,
    /** 搜索模式下结果区的高度上限：既要看得见几条，又不能把输入法顶得很高 */
    val clipboardSearchMaxHeight: Int,
    /** 卡片底部动作条（收藏 / 固定 / 删除…）按钮高度 */
    val clipboardActionHeight: Int,
    val clipboardActionTextSize: Float,
    /** 卡片右上角「固定 / 收藏」小标记尺寸 */
    val clipboardBadgeSize: Float,
    /** 搜索框里的光标宽度 */
    val clipboardCaretWidth: Float,

    // ---- 统一设计令牌（只有三档圆角 / 一档间距梯度 / 三档图标尺寸）----
    val radiusSmall: Float,
    val radiusMedium: Float,
    val radiusLarge: Float,
    val spaceXs: Int,
    val spaceS: Int,
    val spaceM: Int,
    val spaceL: Int,
    val spaceXl: Int,
    val iconSmall: Float,
    val iconMedium: Float,
    val iconLarge: Float,
) {
    companion object {

        fun from(
            context: Context,
            mode: ThemeMode,
            candidateSize: CandidateTextSize = CandidateTextSize.STANDARD,
            geometry: com.board.ime.settings.KeyboardGeometry =
                com.board.ime.settings.KeyboardGeometry.DEFAULT,
            appearance: com.board.ime.settings.AppearanceConfig =
                com.board.ime.settings.AppearanceConfig.DEFAULT,
            palette: KeyboardPalette = KeyboardPalette.STANDARD,
        ): KeyboardTheme {
            val isDark = when (mode) {
                ThemeMode.SYSTEM -> isSystemNight(context)
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            return create(context, isDark, candidateSize, geometry, appearance, palette)
        }

        fun isSystemNight(context: Context): Boolean {
            val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            return uiMode == Configuration.UI_MODE_NIGHT_YES
        }

        private fun create(
            context: Context,
            isDark: Boolean,
            candidateSize: CandidateTextSize,
            geometry: com.board.ime.settings.KeyboardGeometry,
            appearance: com.board.ime.settings.AppearanceConfig,
            palette: KeyboardPalette,
        ): KeyboardTheme {
            val res = context.resources
            // 配色主题只覆盖「默认值」；手动改过的颜色仍由 withOverrides 优先
            val paletteColors = palette.colors(isDark)

            fun color(@ColorRes light: Int, @ColorRes dark: Int): Int =
                res.getColor(if (isDark) dark else light, null)

            fun color(paletteValue: Int?, @ColorRes light: Int, @ColorRes dark: Int): Int =
                paletteValue ?: color(light, dark)

            fun dimen(@DimenRes id: Int): Float = res.getDimensionPixelSize(id).toFloat()

            fun sp(value: Float): Float = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                value,
                res.displayMetrics,
            )

            val density = res.displayMetrics.density

            val base = KeyboardTheme(
                isDark = isDark,

                keyboardBackground = color(
                    paletteColors?.keyboardBackground,
                    R.color.kb_light_background,
                    R.color.kb_dark_background,
                ),
                keyBackground = color(
                    paletteColors?.keyBackground,
                    R.color.kb_light_key,
                    R.color.kb_dark_key,
                ),
                keyPressedBackground = color(
                    paletteColors?.keyPressedBackground,
                    R.color.kb_light_key_pressed,
                    R.color.kb_dark_key_pressed,
                ),
                functionKeyBackground = color(
                    paletteColors?.functionKeyBackground,
                    R.color.kb_light_key_functional,
                    R.color.kb_dark_key_functional,
                ),
                functionKeyPressedBackground = color(
                    paletteColors?.functionKeyPressedBackground,
                    R.color.kb_light_key_functional_pressed,
                    R.color.kb_dark_key_functional_pressed,
                ),
                keyText = color(
                    paletteColors?.keyText,
                    R.color.kb_light_key_text,
                    R.color.kb_dark_key_text,
                ),
                functionKeyText = color(
                    paletteColors?.functionKeyText,
                    R.color.kb_light_key_text_functional,
                    R.color.kb_dark_key_text_functional,
                ),
                accent = color(paletteColors?.accent, R.color.kb_light_accent, R.color.kb_dark_accent),
                accentText = color(
                    paletteColors?.accentText,
                    R.color.kb_light_accent_text,
                    R.color.kb_dark_accent_text,
                ),
                divider = color(
                    paletteColors?.divider,
                    R.color.kb_light_divider,
                    R.color.kb_dark_divider,
                ),

                panelBackground = color(
                    paletteColors?.panelBackground,
                    R.color.kb_light_candidate_bar,
                    R.color.kb_dark_candidate_bar,
                ),
                toolbarBackground = color(
                    paletteColors?.toolbarBackground,
                    R.color.kb_light_candidate_bar,
                    R.color.kb_dark_candidate_bar,
                ),
                candidateText = color(
                    paletteColors?.candidateText,
                    R.color.kb_light_candidate_text,
                    R.color.kb_dark_candidate_text,
                ),
                candidateMutedText = color(
                    paletteColors?.candidateMutedText,
                    R.color.kb_light_candidate_text_muted,
                    R.color.kb_dark_candidate_text_muted,
                ),
                candidateSelectedBackground = color(
                    paletteColors?.candidateSelectedBackground,
                    R.color.kb_light_candidate_selected_bg,
                    R.color.kb_dark_candidate_selected_bg,
                ),
                candidateSelectedText = color(
                    paletteColors?.candidateSelectedText,
                    R.color.kb_light_candidate_selected_text,
                    R.color.kb_dark_candidate_selected_text,
                ),
                panelSurface = color(
                    paletteColors?.panelSurface,
                    R.color.kb_light_panel_surface,
                    R.color.kb_dark_panel_surface,
                ),
                panelSurfacePressed = color(
                    paletteColors?.panelSurfacePressed,
                    R.color.kb_light_panel_surface_pressed,
                    R.color.kb_dark_panel_surface_pressed,
                ),
                pinyinBackground = color(
                    paletteColors?.pinyinBackground,
                    R.color.kb_light_pinyin_bg,
                    R.color.kb_dark_pinyin_bg,
                ),
                iconColor = color(paletteColors?.iconColor, R.color.kb_light_icon, R.color.kb_dark_icon),

                keyCornerRadius = dimen(R.dimen.kb_key_corner_radius),
                keyGapHorizontal = dimen(R.dimen.kb_key_gap_horizontal),
                keyGapVertical = dimen(R.dimen.kb_key_gap_vertical),
                keyHeight = dimen(R.dimen.kb_key_height),
                keyboardPaddingHorizontal = dimen(R.dimen.kb_keyboard_padding_horizontal),
                keyboardPaddingVertical = dimen(R.dimen.kb_keyboard_padding_vertical),
                keyTextSize = dimen(R.dimen.kb_key_text_size),
                functionKeyTextSize = dimen(R.dimen.kb_key_text_size_function),

                topPanelHeight = dimen(R.dimen.kb_top_panel_height).toInt(),
                panelHorizontalPadding = dimen(R.dimen.kb_panel_horizontal_padding).toInt(),
                panelCornerRadius = dimen(R.dimen.kb_panel_corner_radius),
                panelPillCornerRadius = dimen(R.dimen.kb_panel_pill_corner_radius),
                dividerHeight = dimen(R.dimen.kb_divider_height).toInt().coerceAtLeast(1),
                toolRowHeight = dimen(R.dimen.kb_tool_row_height).toInt(),
                toolIconSize = dimen(R.dimen.kb_tool_icon_size),
                toolHitSize = dimen(R.dimen.kb_tool_hit_size).toInt(),
                toolSidePadding = dimen(R.dimen.kb_tool_side_padding).toInt(),
                moreItemWidth = dimen(R.dimen.kb_more_item_width).toInt(),
                moreIconSize = dimen(R.dimen.kb_more_icon_size),
                moreLabelSize = dimen(R.dimen.kb_more_label_size),

                candidateTextSize = sp(candidateSize.candidateSp),
                pinyinTextSize = sp(candidateSize.pinyinSp),
                candidateEnglishTextSize = sp(candidateSize.englishGlossSp),
                candidateChipHeight = dimen(R.dimen.kb_candidate_chip_height).toInt(),
                candidateCornerRadius = dimen(R.dimen.kb_panel_pill_corner_radius),
                candidateMinWidth = dimen(R.dimen.kb_candidate_min_width).toInt(),
                candidatePaddingHorizontal = (candidateSize.horizontalPaddingDp * density).toInt(),

                clipboardCardMinHeight = dimen(R.dimen.kb_clipboard_card_min_height).toInt(),
                clipboardCardRadius = dimen(R.dimen.kb_clipboard_card_radius),
                clipboardGridGap = dimen(R.dimen.kb_clipboard_grid_gap).toInt(),
                clipboardPadding = dimen(R.dimen.kb_clipboard_padding).toInt(),
                clipboardTextSize = dimen(R.dimen.kb_clipboard_text_size),
                clipboardTitleSize = dimen(R.dimen.kb_clipboard_title_size),
                clipboardSectionSize = dimen(R.dimen.kb_clipboard_section_size),
                clipboardSearchMaxHeight = dimen(R.dimen.kb_clipboard_search_max_height).toInt(),
                clipboardActionHeight = dimen(R.dimen.kb_clipboard_action_height).toInt(),
                clipboardActionTextSize = dimen(R.dimen.kb_clipboard_action_text_size),
                clipboardBadgeSize = dimen(R.dimen.kb_clipboard_badge_size),
                clipboardCaretWidth = dimen(R.dimen.kb_clipboard_caret_width),
                radiusSmall = dimen(R.dimen.kb_radius_small),
                radiusMedium = dimen(R.dimen.kb_radius_medium),
                radiusLarge = dimen(R.dimen.kb_radius_large),
                spaceXs = dimen(R.dimen.kb_space_xs).toInt(),
                spaceS = dimen(R.dimen.kb_space_s).toInt(),
                spaceM = dimen(R.dimen.kb_space_m).toInt(),
                spaceL = dimen(R.dimen.kb_space_l).toInt(),
                spaceXl = dimen(R.dimen.kb_space_xl).toInt(),
                iconSmall = dimen(R.dimen.kb_icon_small),
                iconMedium = dimen(R.dimen.kb_icon_medium),
                iconLarge = dimen(R.dimen.kb_icon_large),
            )
            return base.withOverrides(geometry, appearance, density)
        }

        /**
         * 叠加用户自定义的尺寸 / 颜色 / 圆角。
         *
         * 原则：**没配置的项一律保持设计默认**，所以默认视觉与旧版本一致。
         * 另外还会做两件防御：
         * 1. 用户改了底色但没改文字色时，自动挑黑 / 白保证对比度（不会出现「白底白字」）
         * 2. 所有圆角都夹在 `AppearanceRadius` 的上下限内，拖不出畸形 UI
         */
        private fun KeyboardTheme.withOverrides(
            geometry: com.board.ime.settings.KeyboardGeometry,
            appearance: com.board.ime.settings.AppearanceConfig,
            density: Float,
        ): KeyboardTheme {
            val keyRadius = appearance.radiusDp(
                com.board.ime.settings.AppearanceRadius.KEY,
                keyCornerRadius / density,
            ) * density
            val chipRadius = appearance.radiusDp(
                com.board.ime.settings.AppearanceRadius.CANDIDATE_CHIP,
                candidateCornerRadius / density,
            ) * density
            val keyboardRadius = appearance.radiusDp(
                com.board.ime.settings.AppearanceRadius.KEYBOARD,
                radiusLarge / density,
            ) * density
            val cardRadius = appearance.radiusDp(
                com.board.ime.settings.AppearanceRadius.CLIPBOARD_CARD,
                clipboardCardRadius / density,
            ) * density

            val keyBackgroundOverride = appearance.color(
                com.board.ime.settings.AppearanceColor.KEY_BACKGROUND,
            )
            val selectedBackgroundOverride = appearance.color(
                com.board.ime.settings.AppearanceColor.CANDIDATE_SELECTED,
            )
            val accentOverride = appearance.color(com.board.ime.settings.AppearanceColor.ACCENT)
            val keyboardBackgroundOverride = appearance.color(
                com.board.ime.settings.AppearanceColor.KEYBOARD_BACKGROUND,
            )

            val resolvedKeyText = appearance.color(com.board.ime.settings.AppearanceColor.KEY_TEXT)
                ?: keyBackgroundOverride?.let(::autoTextColor)
                ?: keyText
            val resolvedCandidateText = appearance.color(
                com.board.ime.settings.AppearanceColor.CANDIDATE_TEXT,
            )
                ?: keyboardBackgroundOverride?.let(::autoTextColor)
                ?: candidateText

            // 键盘背景 / 工具栏背景 / 候选栏背景合并为「整体键盘背景」：
            // 三个区域同色、同透明度，中间不再有分界线。
            val resolvedKeyboardBackground = applyBackgroundAlpha(
                keyboardBackgroundOverride ?: keyboardBackground,
                appearance.keyboardBackgroundAlpha,
            )

            return copy(
                keyboardBackground = resolvedKeyboardBackground,
                keyBackground = keyBackgroundOverride ?: keyBackground,
                keyPressedBackground = keyBackgroundOverride?.let { shade(it, 0.90f) }
                    ?: keyPressedBackground,
                functionKeyBackground = keyBackgroundOverride?.let { shade(it, 0.960f) }
                    ?: functionKeyBackground,
                functionKeyPressedBackground = keyBackgroundOverride?.let { shade(it, 0.88f) }
                    ?: functionKeyPressedBackground,
                keyText = resolvedKeyText,
                functionKeyText = resolvedKeyText,
                accent = accentOverride ?: accent,
                accentText = accentOverride?.let(::autoTextColor) ?: accentText,
                panelBackground = resolvedKeyboardBackground,
                toolbarBackground = resolvedKeyboardBackground,
                candidateText = resolvedCandidateText,
                candidateSelectedBackground = selectedBackgroundOverride ?: candidateSelectedBackground,
                candidateSelectedText = selectedBackgroundOverride?.let(::autoTextColor)
                    ?: candidateSelectedText,
                keyHeight = keyHeight * geometry.heightFactor,
                keyGapHorizontal = if (geometry.hasCustomGap) {
                    geometry.keyGapDp * density
                } else {
                    keyGapHorizontal
                },
                keyGapVertical = if (geometry.hasCustomGap) {
                    geometry.keyGapDp * density
                } else {
                    keyGapVertical
                },
                keyCornerRadius = keyRadius,
                candidateCornerRadius = chipRadius,
                panelPillCornerRadius = chipRadius,
                clipboardCardRadius = cardRadius,
                radiusLarge = keyboardRadius,
            )
        }

        /** 按背景亮度自动挑黑 / 白文字，避免用户改色后文字看不见 */
        private fun autoTextColor(background: Int): Int {
            val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(background)
            return if (luminance > 0.55) {
                android.graphics.Color.rgb(0x22, 0x22, 0x24)
            } else {
                android.graphics.Color.rgb(0xF2, 0xF2, 0xF4)
            }
        }

        /** 按键按下 / 功能键的深浅变化：只改亮度，不改变色相 */
        private fun shade(color: Int, factor: Float): Int = android.graphics.Color.rgb(
            (android.graphics.Color.red(color) * factor).toInt().coerceIn(0, 255),
            (android.graphics.Color.green(color) * factor).toInt().coerceIn(0, 255),
            (android.graphics.Color.blue(color) * factor).toInt().coerceIn(0, 255),
        )

        /** 键盘背景不透明度：只改 alpha，不改变底色 RGB */
        private fun applyBackgroundAlpha(color: Int, alpha: Float): Int =
            androidx.core.graphics.ColorUtils.setAlphaComponent(
                color,
                (alpha.coerceIn(0f, 1f) * 255).roundToInt(),
            )
    }
}
