package com.board.ime.ui.panel

/**
 * 可管理的工具（在「工具栏」和「更多」之间二选一，**全局唯一**）。
 *
 * - [storageKey] 是写进配置的稳定字符串，刻意与枚举名解耦，
 *   以后重命名枚举也不会破坏老用户的布局。
 * - [action] 复用现有的 [MorePanel.Action]，点击行为完全不变
 *   （语音 / 翻译 仍是「尚未实现」的占位，本阶段不实现引擎）。
 */
enum class ToolId(
    val storageKey: String,
    val label: String,
    val icon: PanelIcon,
    val action: MorePanel.Action,
) {
    CLIPBOARD("clipboard", "剪贴板", PanelIcon.CLIPBOARD, MorePanel.Action.CLIPBOARD),
    VOICE("voice", "语音", PanelIcon.VOICE, MorePanel.Action.VOICE),
    TRANSLATE("translate", "翻译", PanelIcon.TRANSLATE, MorePanel.Action.TRANSLATE),
    THEME("theme", "主题", PanelIcon.THEME, MorePanel.Action.THEME),
    SETTINGS("settings", "设置", PanelIcon.SETTINGS, MorePanel.Action.SETTINGS),
    PHRASES("phrases", "常用语", PanelIcon.PHRASES, MorePanel.Action.PHRASES),
    EMOJI("emoji", "表情", PanelIcon.EMOJI, MorePanel.Action.EMOJI),
    ;

    companion object {
        fun fromKey(key: String?): ToolId? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * 工具栏 / 更多 的布局。
 *
 * 不变式（见 [normalize]）：
 * 1. `toolbar ∩ more = ∅`，且两者并集覆盖**全部**工具（不丢工具）
 * 2. `toolbar.size <= [MAX_TOOLBAR]`
 *
 * 顺序语义：[toolbar] 的第 1 个元素渲染在**最右**（工具栏从右往左排列），
 * [more] 的第 1 个元素渲染在最左。
 */
data class ToolLayout(
    val toolbar: List<ToolId>,
    val more: List<ToolId>,
) {

    /** 把工具从「更多」移到工具栏（默认追加到最右 = 列表末尾）；满了则原样返回 null */
    fun moveToToolbar(tool: ToolId, index: Int = toolbar.size): ToolLayout? {
        if (toolbar.size >= MAX_TOOLBAR) return null
        val nextToolbar = toolbar.toMutableList().apply { add(index.coerceIn(0, size), tool) }
        return ToolLayout(nextToolbar, more - tool)
    }

    /** 把工具从工具栏移到「更多」（追加到末尾） */
    fun moveToMore(tool: ToolId, index: Int = more.size): ToolLayout {
        if (tool !in toolbar) return this
        val nextMore = more.toMutableList().apply { add(index.coerceIn(0, size), tool) }
        return ToolLayout(toolbar - tool, nextMore)
    }

    /** 工具栏内部排序 */
    fun reorderToolbar(from: Int, to: Int): ToolLayout {
        if (from !in toolbar.indices) return this
        val list = toolbar.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return ToolLayout(list, more)
    }

    /** 「更多」内部排序 */
    fun reorderMore(from: Int, to: Int): ToolLayout {
        if (from !in more.indices) return this
        val list = more.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return ToolLayout(toolbar, list)
    }

    fun encodeToolbar(): String = toolbar.joinToString(",") { it.storageKey }

    fun encodeMore(): String = more.joinToString(",") { it.storageKey }

    val isFull: Boolean
        get() = toolbar.size >= MAX_TOOLBAR

    companion object {
        /** 工具栏最多展示 6 个 */
        const val MAX_TOOLBAR = 6

        /**
         * 默认布局 = **当前线上默认**：
         * 工具栏：剪贴板 / 表情；「更多」依次是 主题 / 翻译 / 语音 / 设置 / 常用语。
         * （剪贴板不再重复出现在「更多」里 —— 它已经常驻工具栏。）
         */
        val DEFAULT = ToolLayout(
            toolbar = listOf(ToolId.CLIPBOARD, ToolId.EMOJI),
            more = listOf(
                ToolId.THEME,
                ToolId.TRANSLATE,
                ToolId.VOICE,
                ToolId.SETTINGS,
                ToolId.PHRASES,
            ),
        )

        /**
         * 读取配置：两个 key **都为 null** 表示从未自定义过 → 用默认布局
         * （所以 v1.1 升级到 v1.2 不会重置任何东西）。
         * 任何一个存在就按它解析，缺失的工具补进「更多」。
         */
        fun parse(toolbarRaw: String?, moreRaw: String?): ToolLayout {
            if (toolbarRaw == null && moreRaw == null) return DEFAULT
            val layout = normalize(
                decode(toolbarRaw),
                decode(moreRaw),
            )
            // 老用户的自定义布局里还没有「表情」：它这次是新增的工具，
            // 直接放进工具栏（用户明确要求 emoji 常驻工具栏）；工具栏满了就留在「更多」。
            if (layout.toolbar.contains(ToolId.EMOJI)) return layout
            if (layout.toolbar.size < MAX_TOOLBAR) {
                return layout.copy(
                    toolbar = layout.toolbar + ToolId.EMOJI,
                    more = layout.more - ToolId.EMOJI,
                )
            }
            return layout
        }

        private fun decode(raw: String?): List<ToolId> =
            raw.orEmpty()
                .split(',')
                .mapNotNull { ToolId.fromKey(it.trim().ifEmpty { null }) }
                .distinct()

        /**
         * 安全校验（加载配置时兜底，异常数据不会让工具消失）：
         * - 重复：**保留工具栏里的那份**，从「更多」移除
         * - 工具栏超过 6 个：保留前 6 个，其余放回「更多」
         * - 未出现在任何一侧的工具：补到「更多」末尾
         */
        fun normalize(toolbar: List<ToolId>, more: List<ToolId>): ToolLayout {
            val seen = HashSet<ToolId>()
            val cleanToolbar = ArrayList<ToolId>(MAX_TOOLBAR)
            val overflow = ArrayList<ToolId>()
            toolbar.distinct().forEach { tool ->
                if (cleanToolbar.size < MAX_TOOLBAR && seen.add(tool)) {
                    cleanToolbar += tool
                } else if (seen.add(tool)) {
                    overflow += tool
                }
            }
            val cleanMore = ArrayList<ToolId>()
            (more + overflow).distinct().forEach { tool ->
                if (seen.add(tool)) cleanMore += tool
            }
            ToolId.entries.forEach { tool ->
                if (seen.add(tool)) cleanMore += tool
            }
            return ToolLayout(cleanToolbar, cleanMore)
        }
    }
}
