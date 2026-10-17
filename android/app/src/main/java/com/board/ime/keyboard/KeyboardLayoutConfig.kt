package com.board.ime.keyboard

/**
 * 键盘尺寸配置。
 *
 * 只描述「设计意图」（单位 dp / 比例），不写死像素；
 * 真正的像素值由 [KeyboardLayoutCalculator] 结合屏幕尺寸算出来。
 *
 * - 竖屏：铺满宽度，使用 `dimens.xml` 里的值（见 [KeyboardLayoutCalculator.configFor]）
 * - 横屏 / 大屏：**悬浮键盘**——不铺满宽度、保持键帽比例、尽量少遮挡应用内容
 */
data class KeyboardLayoutConfig(
    /** 是否悬浮（不铺满宽度、有外边距与圆角卡片背景） */
    val floating: Boolean,
    /** 是否铺满可用宽度 */
    val fillWidth: Boolean,
    /** 悬浮时占屏幕宽度的比例 */
    val widthFraction: Float,
    /** 悬浮时的最小 / 最大宽度（dp） */
    val minWidthDp: Float,
    val maxWidthDp: Float,
    /** 宽度不超过屏幕宽度的这个比例（留出两侧空间） */
    val maxWidthScreenFraction: Float,
    /** 键盘整体高度不超过屏幕高度的这个比例（留出候选栏与上方内容） */
    val maxHeightScreenFraction: Float,
    /** 单键高度（dp） */
    val keyHeightDp: Float,
    /** 高度不够时的最小键高（dp），低于它就不再压缩，改为可滚动 */
    val minKeyHeightDp: Float,
    val keyGapHorizontalDp: Float,
    val keyGapVerticalDp: Float,
    val paddingHorizontalDp: Float,
    val paddingVerticalDp: Float,
    /**
     * 键盘**底部**留白（dp）。
     *
     * 默认与 [paddingVerticalDp] 相同；竖屏单独调大，
     * 让「键盘最底一行 → 屏幕底部」的距离贴近微信输入法。
     * 顶部留白仍用 [paddingVerticalDp]，不受影响。
     */
    val paddingBottomDp: Float = paddingVerticalDp,
    val cornerRadiusDp: Float,
    /** 悬浮时距离屏幕边缘的间距（dp） */
    val edgeMarginDp: Float,
    /** 悬浮时靠右对齐（单手操作友好） */
    val alignEnd: Boolean,
    /** 功能键宽度倍率（1.0 = 设计默认） */
    val functionKeyWidthRatio: Float = 1f,
) {

    companion object {

        /**
         * 横屏 / 悬浮预设。
         *
         * 目标：键盘不铺满、键帽不变形、少遮挡。
         * 宽度 = 屏宽的 0.62，夹在 340–720dp 之间；单键 46dp；四周留 12dp。
         *
         * 下限取 340dp 而不是更大值，是为了让竖屏强制悬浮时也能真正变窄
         * （用 480dp 做下限时，450dp 宽的竖屏会被夹到接近满宽，失去悬浮意义）。
         */
        val LANDSCAPE = KeyboardLayoutConfig(
            floating = true,
            fillWidth = false,
            widthFraction = 0.62f,
            minWidthDp = 340f,
            maxWidthDp = 720f,
            maxWidthScreenFraction = 0.92f,
            maxHeightScreenFraction = 0.62f,
            keyHeightDp = 46f,
            minKeyHeightDp = 34f,
            keyGapHorizontalDp = 6f,
            keyGapVerticalDp = 8f,
            paddingHorizontalDp = 6f,
            paddingVerticalDp = 8f,
            cornerRadiusDp = 10f,
            edgeMarginDp = 12f,
            alignEnd = true,
        )
    }
}

/** 计算出来的像素尺寸，交给 [com.board.ime.ui.KeyboardView] 使用 */
data class KeyboardLayoutMetrics(
    val floating: Boolean,
    val widthPx: Int,
    val keyHeightPx: Float,
    val keyGapHorizontalPx: Float,
    val keyGapVerticalPx: Float,
    val paddingHorizontalPx: Float,
    val paddingVerticalPx: Float,
    /** 键盘底部留白（px）；默认与 [paddingVerticalPx] 相同 */
    val paddingBottomPx: Float = paddingVerticalPx,
    val cornerRadiusPx: Float,
    val edgeMarginPx: Int,
    val alignEnd: Boolean,
    /** 功能键宽度倍率（1.0 = 设计默认） */
    val functionKeyWidthRatio: Float = 1f,
) {
    /** 键盘本体高度（不含顶部面板）；顶部留白 + 各按键行 + 行间距 + 底部留白 */
    fun keyboardHeightPx(rowCount: Int): Int {
        val rows = rowCount.coerceAtLeast(1)
        return (paddingVerticalPx + paddingBottomPx +
            keyHeightPx * rows +
            keyGapVerticalPx * (rows - 1)).toInt()
    }
}
