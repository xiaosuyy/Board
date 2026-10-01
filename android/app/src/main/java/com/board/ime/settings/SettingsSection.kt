package com.board.ime.settings

/**
 * 设置的一级分类。
 *
 * 首页**只列这些入口**（外加最上方的状态区、最下方的版本号），
 * 一级页面不堆解释文字，详细、低频的设置都放进各自的二级页面。
 */
enum class SettingsSection(
    val title: String,
    val summary: String,
) {
    INPUT("输入", "中文输入、输入引擎"),
    KEYBOARD("键盘", "高度、间距、圆角、按键行为"),
    APPEARANCE("外观", "主题、强调色、颜色与圆角"),
    DICTIONARY("词库", "我的词库、学习词、导入导出"),
    CLIPBOARD("剪贴板", "历史、敏感内容、清理"),
    LIBRARY("云端资料库", "跨设备的图片与文件"),
    SYNC("同步", "WebDAV、同步内容"),
    TRANSLATION("翻译", "翻译服务、隐私说明"),
    VOICE("语音", "百度实时语音识别、隐私说明"),
    ADVANCED("高级", "隐私、输入法状态、开发者选项"),
    ;

    companion object {
        fun fromName(value: String?): SettingsSection =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INPUT
    }
}
