package com.board.ime.keyboard

import android.content.Context
import android.content.res.Configuration
import com.board.ime.R

/**
 * 根据屏幕尺寸把 [KeyboardLayoutConfig] 换算成真正的像素尺寸。
 *
 * 不做「横屏就把竖屏拉宽」这种事：
 * - 竖屏铺满宽度（保持现有观感）；
 * - 横屏 / 大屏使用悬浮布局：宽度按比例 + 上下限夹取、单键高度受限、
 *   整体高度不超过屏幕的一定比例，避免遮挡应用内容。
 *
 * 所有尺寸都来自配置，没有任何硬编码像素。
 */
object KeyboardLayoutCalculator {

    /** 大屏判定：横屏，或最小宽度 ≥ 600dp（平板） */
    fun prefersFloatingLayout(context: Context): Boolean {
        val configuration = context.resources.configuration
        val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val sw = configuration.smallestScreenWidthDp
        return landscape || sw >= 600
    }

    /**
     * 取某个布局作用域的设计基准配置。
     *
     * [geometry] 来自 [com.board.ime.settings.SettingsRepository]：
     * 只有用户真的改过（倍率 ≠ 1、间距/圆角已设置）时才覆盖设计默认值，
     * 因此「没配置」= 与旧版本视觉完全一致。
     *
     * [keyboardLift]（设置里的「键盘抬升」）：
     * - `true`（默认）：底部留白取 `kb_keyboard_padding_bottom`（92px @480dpi，对齐微信输入法）；
     * - `false`：底部留白与顶部留白相同（24px @480dpi），即原来的样子。
     * 两种情况都只动**底部**留白，顶部留白 / 键高 / 行距不受影响。
     */
    fun configFor(
        context: Context,
        floating: Boolean,
        geometry: com.board.ime.settings.KeyboardGeometry =
            com.board.ime.settings.KeyboardGeometry.DEFAULT,
        keyboardLift: Boolean = true,
    ): KeyboardLayoutConfig {
        val res = context.resources
        // 注意：这里要的是 dp（设计值），不是像素；像素由 metrics() 按 density 换算
        fun dp(id: Int): Float = res.getDimension(id) / res.displayMetrics.density

        val base = if (floating) {
            KeyboardLayoutConfig.LANDSCAPE
        } else {
            KeyboardLayoutConfig(
                floating = false,
                fillWidth = true,
                widthFraction = 1f,
                minWidthDp = 0f,
                maxWidthDp = Float.MAX_VALUE,
                maxWidthScreenFraction = 1f,
                maxHeightScreenFraction = 0.62f,
                keyHeightDp = dp(R.dimen.kb_key_height),
                minKeyHeightDp = 34f,
                keyGapHorizontalDp = dp(R.dimen.kb_key_gap_horizontal),
                keyGapVerticalDp = dp(R.dimen.kb_key_gap_vertical),
                paddingHorizontalDp = dp(R.dimen.kb_keyboard_padding_horizontal),
                paddingVerticalDp = dp(R.dimen.kb_keyboard_padding_vertical),
                paddingBottomDp = dp(R.dimen.kb_keyboard_padding_bottom),
                cornerRadiusDp = dp(R.dimen.kb_key_corner_radius),
                edgeMarginDp = 0f,
                alignEnd = false,
            )
        }
        // 「键盘抬升」开关：关掉 = 底部留白退回与顶部留白一致（默认值）
        val lifted = if (keyboardLift) {
            base.copy(paddingBottomDp = dp(R.dimen.kb_keyboard_padding_bottom))
        } else {
            base.copy(paddingBottomDp = base.paddingVerticalDp)
        }
        return lifted.withGeometry(geometry, context)
    }

    /** 把用户设置叠加到设计基准上（不改调用方传进来的对象） */
    private fun KeyboardLayoutConfig.withGeometry(
        geometry: com.board.ime.settings.KeyboardGeometry,
        context: Context,
    ): KeyboardLayoutConfig {
        val fnRatio = geometry.functionKeyWidthRatio.coerceIn(
            com.board.ime.settings.KeyboardGeometry.MIN_FN_RATIO,
            com.board.ime.settings.KeyboardGeometry.MAX_FN_RATIO,
        )
        return if (geometry.hasCustomGap) {
            val gap = geometry.keyGapDp.coerceIn(
                com.board.ime.settings.KeyboardGeometry.MIN_GAP_DP,
                com.board.ime.settings.KeyboardGeometry.MAX_GAP_DP,
            )
            copy(
                keyHeightDp = keyHeightDp * geometry.heightFactor,
                keyGapHorizontalDp = gap,
                keyGapVerticalDp = gap,
                cornerRadiusDp = if (geometry.hasCustomRadius) geometry.keyCornerRadiusDp else cornerRadiusDp,
                functionKeyWidthRatio = fnRatio,
            )
        } else {
            copy(
                keyHeightDp = keyHeightDp * geometry.heightFactor,
                cornerRadiusDp = if (geometry.hasCustomRadius) geometry.keyCornerRadiusDp else cornerRadiusDp,
                functionKeyWidthRatio = fnRatio,
            )
        }
    }

    fun metrics(
        config: KeyboardLayoutConfig,
        screenWidthPx: Int,
        screenHeightPx: Int,
        density: Float,
        rowCount: Int,
    ): KeyboardLayoutMetrics {
        val widthPx = resolveWidth(config, screenWidthPx, density)

        val paddingVertical = config.paddingVerticalDp * density
        val paddingBottom = config.paddingBottomDp * density
        val rowGap = config.keyGapVerticalDp * density
        val rows = rowCount.coerceAtLeast(1)

        var keyHeight = config.keyHeightDp * density
        // 高度受限时压缩键高，保证「顶部面板 + 键盘」不会吃掉整个屏幕
        val maxKeyboardHeight = screenHeightPx * config.maxHeightScreenFraction
        val fixedHeight = paddingVertical + paddingBottom + rowGap * (rows - 1)
        val available = maxKeyboardHeight - fixedHeight
        if (available > 0f) {
            val maxKeyHeight = available / rows
            if (keyHeight > maxKeyHeight) {
                keyHeight = maxKeyHeight.coerceAtLeast(config.minKeyHeightDp * density)
            }
        }

        return KeyboardLayoutMetrics(
            floating = config.floating,
            widthPx = widthPx,
            keyHeightPx = keyHeight,
            keyGapHorizontalPx = config.keyGapHorizontalDp * density,
            keyGapVerticalPx = rowGap,
            paddingHorizontalPx = config.paddingHorizontalDp * density,
            paddingVerticalPx = paddingVertical,
            paddingBottomPx = paddingBottom,
            cornerRadiusPx = config.cornerRadiusDp * density,
            edgeMarginPx = (config.edgeMarginDp * density).toInt(),
            alignEnd = config.alignEnd,
            functionKeyWidthRatio = config.functionKeyWidthRatio,
        )
    }

    private fun resolveWidth(
        config: KeyboardLayoutConfig,
        screenWidthPx: Int,
        density: Float,
    ): Int {
        if (config.fillWidth) return screenWidthPx
        val maxWidth = minOf(config.maxWidthDp * density, screenWidthPx * config.maxWidthScreenFraction)
        val minWidth = minOf(config.minWidthDp * density, maxWidth)
        val target = screenWidthPx * config.widthFraction
        return target.coerceIn(minWidth, maxWidth).toInt()
    }
}
