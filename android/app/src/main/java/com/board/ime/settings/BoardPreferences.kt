package com.board.ime.settings

import android.content.Context
import android.content.SharedPreferences
import com.board.ime.engine.InputMode
import com.board.ime.sync.SyncContent
import com.board.ime.sync.SyncContentGate

/**
 * 键盘设置。
 *
 * 阶段一只存少量开关；后续的同步账号、主题商城等配置会扩展到这里，
 * 因此对上层只暴露属性，不暴露 SharedPreferences。
 */
class BoardPreferences(context: Context) : SyncContentGate {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** 当前输入模式（中文 / 英文） */
    var mode: InputMode
        get() = InputMode.fromName(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** 键盘外观 */
    var themeMode: ThemeMode
        get() = ThemeMode.fromName(prefs.getString(KEY_THEME_MODE, null))
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    /** 按键音效 */
    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit().putBoolean(KEY_SOUND, value).apply()

    /** 按键震动 */
    var vibrateEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATE, value).apply()

    /** 中文引擎：Rime 或占位引擎 */
    var engineKind: EngineKind
        get() = EngineKind.fromName(prefs.getString(KEY_ENGINE, null))
        set(value) = prefs.edit().putString(KEY_ENGINE, value.name).apply()

    /** 键盘布局：自动 / 铺满 / 悬浮 */
    var layoutMode: LayoutMode
        get() = LayoutMode.fromName(prefs.getString(KEY_LAYOUT, null))
        set(value) = prefs.edit().putString(KEY_LAYOUT, value.name).apply()

    /** 候选词字号 */
    var candidateTextSize: CandidateTextSize
        get() = CandidateTextSize.fromName(prefs.getString(KEY_CANDIDATE_SIZE, null))
        set(value) = prefs.edit().putString(KEY_CANDIDATE_SIZE, value.name).apply()

    /** 敏感剪贴板内容（验证码 / 密码）是否不写入历史 */
    var skipSensitiveClipboard: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SENSITIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_SENSITIVE, value).apply()

    /** 开发者模式：连续点版本号 5 次才打开，日志入口只在这个模式下出现 */
    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVELOPER, value).apply()

    /** 同步内容开关（剪贴板 / 用户词库 / 学习词 / 常用语 / 自定义词 / 主题 / 键盘设置） */
    override fun isEnabled(content: SyncContent): Boolean =
        prefs.getBoolean(content.storageKey, content.defaultEnabled)

    override fun setEnabled(content: SyncContent, enabled: Boolean) {
        prefs.edit().putBoolean(content.storageKey, enabled).apply()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private companion object {
        const val FILE_NAME = "board_settings"
        const val KEY_MODE = "input_mode"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_VIBRATE = "vibrate_enabled"
        const val KEY_ENGINE = "engine_kind"
        const val KEY_LAYOUT = "layout_mode"
        const val KEY_CANDIDATE_SIZE = "candidate_text_size"
        const val KEY_SKIP_SENSITIVE = "skip_sensitive_clipboard"
        const val KEY_DEVELOPER = "developer_mode"
    }
}
