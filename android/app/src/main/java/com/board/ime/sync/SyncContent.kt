package com.board.ime.sync

/**
 * 用户可以**单独勾选**的同步内容。
 *
 * 只有勾选的内容才会进入同步范围（隐私要求：账号密码、日志、调试信息、
 * 无关设备信息永远不会被同步）。
 */
enum class SyncContent(val label: String, val defaultEnabled: Boolean) {
    /** 当前剪贴板（SyncClipboard.json，沿用官方协议） */
    CLIPBOARD("剪贴板", true),

    /** 用户词库：自定义词 + 固定词 + 屏蔽词 */
    USER_DICTIONARY("用户词库", true),

    /** 学习词：librime 用户词典的合并结果（会影响候选排序） */
    LEARNED_WORD("学习词", true),

    /** 常用语 */
    PHRASE("常用语", true),

    /** 自定义词（用户词库里的一个子集，单独控制） */
    CUSTOM_WORD("自定义词", true),

    /** 主题（外观设置） */
    THEME("主题", false),

    /** 键盘设置 */
    KEYBOARD_SETTINGS("键盘设置", false),
    ;

    val storageKey: String get() = "sync_content_" + name.lowercase()
}

/** 同步开关的读写抽象（由 BoardPreferences 实现，避免这里依赖 Context） */
interface SyncContentGate {
    fun isEnabled(content: SyncContent): Boolean
    fun setEnabled(content: SyncContent, enabled: Boolean)
}

/** 默认全开（主题 / 键盘设置默认关，避免覆盖另一端的外观选择） */
class MemorySyncContentGate(
    private val values: MutableMap<SyncContent, Boolean> = HashMap(),
) : SyncContentGate {
    override fun isEnabled(content: SyncContent): Boolean =
        values[content] ?: content.defaultEnabled

    override fun setEnabled(content: SyncContent, enabled: Boolean) {
        values[content] = enabled
    }
}
