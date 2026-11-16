package com.board.ime.ui.panel

/**
 * 顶部区域的状态。四种状态互斥、不叠加，且**共用同一个固定面板高度**，
 * 因此切换时键盘总高度不会跳动。
 *
 * - [TOOLBAR]：没有输入内容时的功能入口
 * - [CANDIDATES]：正在输入（存在 composing）时的候选栏
 * - [CANDIDATES_EXPANDED]：点候选栏右侧 ∨ 后，候选栏仍在上方，下方网格铺满候选（占键盘位置）
 * - [MORE]：点了工具栏入口后，在同一块区域里展开的功能网格
 * - [TOOLBOX]：只打一个 v 再按空格打开的快捷面板（剪贴板 / 常用语 / 符号 / 表情）
 * - [CLIPBOARD]：剪贴板（标题占用固定面板高度，内容区与键盘等高并可滚动）
 * - [VOICE]：语音输入（同样的固定高度：状态文字 + partial 预览 + 结束按钮）
 * - [TRANSLATION]：翻译（同样的固定高度：标题 + 服务商；内容区与键盘等高）
 * - [PHRASES]：常用语（同样的固定高度：标题 + ＋新增；内容区与键盘等高）
 * - [EMOJI]：表情（同样的固定高度：标题 + 搜索；内容区与键盘等高，搜索时键盘在下方）
 */
enum class TopPanelState {
    TOOLBAR,
    CANDIDATES,
    CANDIDATES_EXPANDED,
    MORE,
    TOOLBOX,
    CLIPBOARD,
    VOICE,
    TRANSLATION,
    PHRASES,
    EMOJI,
}
