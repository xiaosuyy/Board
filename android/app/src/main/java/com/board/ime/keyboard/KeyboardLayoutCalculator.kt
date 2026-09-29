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

    fun configFor(context: Context, floating: Boolean): KeyboardLayoutConfig {
        if (floating) {
            return KeyboardLayoutConfig.LANDSCAPE
        }
        val res = context.resources
        // 注意：这里要的是 dp（设计值），不是像素；像素由 metrics() 按 density 换算
        fun dp(id: Int): Float = res.getDimension(id) / res.displayMetrics.density
        return KeyboardLayoutConfig(
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
            cornerRadiusDp = dp(R.dimen.kb_key_corner_radius),
            edgeMarginDp = 0f,
            alignEnd = false,
        )
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
        val rowGap = config.keyGapVerticalDp * density
        val rows = rowCount.coerceAtLeast(1)

        var keyHeight = config.keyHeightDp * density
        // 高度受限时压缩键高，保证「顶部面板 + 键盘」不会吃掉整个屏幕
        val maxKeyboardHeight = screenHeightPx * config.maxHeightScreenFraction
        val fixedHeight = paddingVertical * 2f + rowGap * (rows - 1)
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
            cornerRadiusPx = config.cornerRadiusDp * density,
            edgeMarginPx = (config.edgeMarginDp * density).toInt(),
            alignEnd = config.alignEnd,
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
