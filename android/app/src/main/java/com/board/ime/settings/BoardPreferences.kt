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

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /**
     * 当前输入模式（中文 / 英文）。
     *
     * 取值优先级：
     * 1. **用户显式选择过**（`input_mode` 已落盘）→ 就用用户的（升级、重启都不会被覆盖）；
     * 2. 没有任何偏好 + **全新安装** → 默认**中文**（老版本默认是英文，正是这次要修的体验问题）；
     * 3. 没有任何偏好 + **覆盖升级** → 沿用老默认英文，避免把老用户的语言悄悄切走。
     *
     * 注意：只在「读取默认值」时判断，写入永远只发生在用户主动切换时
     * （见 `BoardInputMethodService.persistLetterMode`）。
     */
    var mode: InputMode
        get() {
            val saved = prefs.getString(KEY_MODE, null)
            if (saved != null) return InputMode.fromName(saved)
            return if (isUpgradeInstall()) InputMode.ENGLISH else DEFAULT_MODE_FRESH_INSTALL
        }
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /**
     * 是否属于「覆盖升级」安装（这台设备上装过更早的 Board）。
     *
     * 只用于上面那个默认值判断：`firstInstallTime == lastUpdateTime` 表示全新安装。
     * 读不到包信息时按全新安装处理（宁可给中文默认，也不给老用户乱切）。
     */
    @Suppress("DEPRECATION")
    private fun isUpgradeInstall(): Boolean = runCatching {
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        info.lastUpdateTime > info.firstInstallTime
    }.getOrDefault(false)

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

    /**
     * 语音隐私说明是否已被用户确认（第一次点语音时弹一次）。
     *
     * **只存这一个布尔值**；绝不保存语音内容、识别文本或识别历史。
     */
    var voicePrivacyAcknowledged: Boolean
        get() = prefs.getBoolean(KEY_VOICE_PRIVACY_ACK, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_PRIVACY_ACK, value).apply()

    /**
     * 翻译语言选择（只记住选过哪个语言）。
     *
     * 存的是语言 code（如 `auto` / `zh` / `en`）；
     * **绝不保存翻译原文或译文**，也不保存任何翻译历史。
     */
    var translationSourceLanguage: String?
        get() = prefs.getString(KEY_TRANSLATION_SOURCE, null)
        set(value) = prefs.edit().putString(KEY_TRANSLATION_SOURCE, value).apply()

    var translationTargetLanguage: String?
        get() = prefs.getString(KEY_TRANSLATION_TARGET, null)
        set(value) = prefs.edit().putString(KEY_TRANSLATION_TARGET, value).apply()

    /** 开发者模式：连续点版本号 5 次才打开，日志入口只在这个模式下出现 */
    var developerMode: Boolean
        get() = prefs.getBoolean(KEY_DEVELOPER, false)
        set(value) = prefs.edit().putBoolean(KEY_DEVELOPER, value).apply()

    /**
     * 工具栏 / 更多 的工具布局（逗号分隔的 storageKey）。
     *
     * **两个 key 都为 null 表示从未自定义过** → 上层用默认布局；
     * 因此 v1.1 升级到 v1.2 不会重置任何东西（不写就不覆盖）。
     */
    var toolbarTools: String?
        get() = prefs.getString(KEY_TOOLBAR_TOOLS, null)
        set(value) = prefs.edit().putString(KEY_TOOLBAR_TOOLS, value).apply()

    var moreTools: String?
        get() = prefs.getString(KEY_MORE_TOOLS, null)
        set(value) = prefs.edit().putString(KEY_MORE_TOOLS, value).apply()

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

        /** 全新安装、且用户还没有任何偏好时的默认语言：中文 */
        val DEFAULT_MODE_FRESH_INSTALL = InputMode.CHINESE
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_SOUND = "sound_enabled"
        const val KEY_VIBRATE = "vibrate_enabled"
        const val KEY_ENGINE = "engine_kind"
        const val KEY_LAYOUT = "layout_mode"
        const val KEY_CANDIDATE_SIZE = "candidate_text_size"
        const val KEY_SKIP_SENSITIVE = "skip_sensitive_clipboard"
        const val KEY_VOICE_PRIVACY_ACK = "voice_privacy_acknowledged"
        const val KEY_TRANSLATION_SOURCE = "translation_source_language"
        const val KEY_TRANSLATION_TARGET = "translation_target_language"
        const val KEY_DEVELOPER = "developer_mode"
        const val KEY_TOOLBAR_TOOLS = "toolbar_tools"
        const val KEY_MORE_TOOLS = "more_tools"
    }
}
