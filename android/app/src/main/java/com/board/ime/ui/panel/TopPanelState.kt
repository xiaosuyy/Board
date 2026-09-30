package com.board.ime.ui.panel

/**
 * 顶部区域的状态。四种状态互斥、不叠加，且**共用同一个固定面板高度**，
 * 因此切换时键盘总高度不会跳动。
 *
 * - [TOOLBAR]：没有输入内容时的功能入口
 * - [CANDIDATES]：正在输入（存在 composing）时的候选栏
 * - [MORE]：点了工具栏入口后，在同一块区域里展开的功能网格
 * - [CLIPBOARD]：剪贴板（标题占用固定面板高度，内容区与键盘等高并可滚动）
 * - [VOICE]：语音输入（同样的固定高度：状态文字 + partial 预览 + 结束按钮）
 * - [TRANSLATION]：翻译（同样的固定高度：标题 + 服务商；内容区与键盘等高）
 */
enum class TopPanelState {
    TOOLBAR,
    CANDIDATES,
    MORE,
    CLIPBOARD,
    VOICE,
    TRANSLATION,
}
