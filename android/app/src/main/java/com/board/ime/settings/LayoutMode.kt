package com.board.ime.settings

/**
 * 键盘布局模式。
 *
 * - [AUTO]：竖屏铺满宽度，横屏 / 大屏自动切换为悬浮键盘（默认）
 * - [FILL_WIDTH]：始终铺满宽度
 * - [FLOATING]：始终使用悬浮键盘（不铺满、保持键帽比例）
 *
 * 单独暴露出来是因为「悬浮键盘」本身就是用户可能想要的形态，
 * 同时也让竖屏设备能直接体验悬浮布局。
 */
enum class LayoutMode(val label: String) {
    AUTO("自动"),
    FILL_WIDTH("铺满"),
    FLOATING("悬浮"),
    ;

    companion object {
        fun fromName(value: String?): LayoutMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
