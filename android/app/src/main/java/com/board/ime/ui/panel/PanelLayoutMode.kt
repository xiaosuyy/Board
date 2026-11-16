package com.board.ime.ui.panel

/**
 * 面板 + 键盘的布局组合。TopPanel 的高度永远是固定的 56dp，
 * 这里只描述「TopPanel 下面那块区域」怎么摆：
 *
 * - [KEYBOARD_ONLY]：TOOLBAR / CANDIDATES / MORE —— 只有键盘
 * - [CLIPBOARD_ONLY]：普通剪贴板 —— 内容区占用键盘的位置（总高度不变）
 * - [CLIPBOARD_WITH_KEYBOARD]：剪贴板搜索 —— 结果区在上、键盘在下，
 *   结果区高度有上限，避免把输入法顶得过高
 * - [TRANSLATION_ONLY]：翻译 —— 内容区占用键盘的位置（与剪贴板同理，总高度不变）
 * - [CANDIDATES_ONLY]：候选全部展开 —— 网格占用键盘的位置（与剪贴板同理，总高度不变）
 * - [EMOJI_ONLY]：表情网格 —— 内容区占用键盘的位置（总高度不变）
 * - [EMOJI_WITH_KEYBOARD]：表情搜索 —— 结果区在上、键盘在下（与剪贴板搜索同理）
 */
enum class PanelLayoutMode {
    KEYBOARD_ONLY,
    CLIPBOARD_ONLY,
    CLIPBOARD_WITH_KEYBOARD,
    TRANSLATION_ONLY,
    PHRASES_ONLY,
    CANDIDATES_ONLY,
    EMOJI_ONLY,
    EMOJI_WITH_KEYBOARD,
}
