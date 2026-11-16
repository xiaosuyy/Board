package com.board.ime.settings

import androidx.annotation.DrawableRes
import com.board.ime.R

/**
 * 设置的一级分类。
 *
 * 首页**只列这些入口**（外加最上方的状态区、最下方的版本号），
 * 一级页面不堆解释文字，详细、低频的设置都放进各自的二级页面。
 *
 * [icon] 用于首页列表左侧的图标底（对齐 iOS 设置那种一眼扫完的列表）。
 */
enum class SettingsSection(
    val title: String,
    val summary: String,
    @param:DrawableRes val icon: Int,
) {
    INPUT("输入", "中文输入、输入引擎", R.drawable.ic_board_edit),
    KEYBOARD("键盘", "布局、位置、按键行为", R.drawable.ic_board_keyboard_hide),
    APPEARANCE("外观", "主题、尺寸、颜色与圆角", R.drawable.ic_board_theme),
    DICTIONARY("词库", "我的词库、学习词、导入导出", R.drawable.ic_board_phrases),
    CLIPBOARD("剪贴板", "历史、敏感内容、清理", R.drawable.ic_board_clipboard),
    LIBRARY("云端资料库", "跨设备的图片与文件", R.drawable.ic_board_cloud),
    ACCOUNT("账号与同步", "WebDAV 登录、头像与同步", R.drawable.ic_board_avatar),
    TRANSLATION("翻译", "翻译服务、隐私说明", R.drawable.ic_board_translate),
    VOICE("语音", "百度实时语音识别、隐私说明", R.drawable.ic_board_voice),
    ADVANCED("高级", "隐私、输入法状态、开发者选项", R.drawable.ic_board_settings),
    ABOUT("关于", "项目介绍、版本与更新", R.drawable.ic_board_info),
    ;

    companion object {
        fun fromName(value: String?): SettingsSection =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INPUT
    }
}
