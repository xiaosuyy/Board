package com.board.ime.settings

/**
 * 设置的一级分类。
 *
 * 首页只列这些入口（外加最上方的状态区、最下方的版本号），
 * 详细、低频的设置都放进各自的二级页面 —— 首页不再堆几十个开关。
 */
enum class SettingsSection(
    val title: String,
    val summary: String,
) {
    INPUT("输入", "中文输入、输入引擎"),
    APPEARANCE("外观", "主题、候选词字号"),
    KEYBOARD("键盘", "布局、按键音效与震动"),
    CLIPBOARD("剪贴板", "历史、敏感内容、清理"),
    DICTIONARY("词库", "我的词库、学习词、导入导出"),
    SYNC("同步", "WebDAV、同步内容"),
    PRIVACY("隐私", "敏感剪贴板、同步数据范围"),
    ABOUT("关于", "输入法状态、版本"),
    ;

    companion object {
        fun fromName(value: String?): SettingsSection =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INPUT
    }
}
