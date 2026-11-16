package com.board.ime.settings

import android.content.Context
import android.content.SharedPreferences
import com.board.ime.engine.InputMode
import com.board.ime.engine.rime.RimeFuzzyGroup
import com.board.ime.engine.rime.ShuangpinScheme
import com.board.ime.sync.SyncContent
import com.board.ime.sync.SyncContentGate
import com.board.ime.update.UpdateDownloadSource

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

    /**
     * 键盘抬升：键盘整体上抬，加大**底部留白**。
     *
     * - 开启（默认）：底部留白用 `kb_keyboard_padding_bottom`
     *   （30.67dp；1080×1920 @480dpi 实测 = 92px，与微信输入法一致）；
     * - 关闭：底部留白回到与顶部留白相同（8dp = 24px），即「没抬升」的原样。
     *
     * 只影响底部留白，不动键高 / 顶部留白 / 行距。
     */
    var keyboardLiftEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEYBOARD_LIFT, true)
        set(value) = prefs.edit().putBoolean(KEY_KEYBOARD_LIFT, value).apply()

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

    /**
     * 候选栏英文释义（P3）。
     *
     * 默认**关闭**。只影响候选栏是否在中文候选下方显示一行本地英文释义；
     * 不参与候选生成 / 排序 / 上屏，也不读取或保存任何用户输入内容。
     */
    var candidateEnglishGlossEnabled: Boolean
        get() = prefs.getBoolean(KEY_CANDIDATE_ENGLISH_GLOSS, false)
        set(value) = prefs.edit().putBoolean(KEY_CANDIDATE_ENGLISH_GLOSS, value).apply()

    /**
     * 中英文之间自动加空格（默认关闭）：
     * 字母 / 数字**分别**开关，只作用于汉字与半角字符的交界处。
     */
    var autoSpaceLetters: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPACE_LETTERS, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SPACE_LETTERS, value).apply()

    var autoSpaceDigits: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SPACE_DIGITS, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SPACE_DIGITS, value).apply()

    /**
     * 繁体输出（默认关闭 = 简体）。
     *
     * 只切换 Rime 的 simplification 开关：基础词库（luna_pinyin）是繁体词典，
     * 关闭简化即为繁体输出；用户词 / 扩展词保持自身字形。
     */
    var traditionalOutput: Boolean
        get() = prefs.getBoolean(KEY_TRADITIONAL_OUTPUT, false)
        set(value) = prefs.edit().putBoolean(KEY_TRADITIONAL_OUTPUT, value).apply()

    /** 双拼方案（默认全拼）。改方案需要重新部署 Rime 方案。 */
    var shuangpin: ShuangpinScheme
        get() = ShuangpinScheme.fromName(prefs.getString(KEY_SHUANGPIN, null))
        set(value) = prefs.edit().putString(KEY_SHUANGPIN, value.name).apply()

    /**
     * 模糊音 / 简拼 / 纠错分组（12 组单独开关）。
     *
     * 存的是 [RimeFuzzyGroup.key] 集合；从未设置过时返回内置默认（与随包方案一致）。
     */
    var rimeFuzzyGroups: Set<String>
        get() = prefs.getStringSet(KEY_RIME_FUZZY_GROUPS, null)?.toSet()
            ?: RimeFuzzyGroup.defaults()
        set(value) = prefs.edit().putStringSet(KEY_RIME_FUZZY_GROUPS, value.toSet()).apply()

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

    /** 更新包下载通道：GitHub 直连 / gh-proxy 加速（只影响下载地址，检查更新始终直连） */
    var updateDownloadSource: UpdateDownloadSource
        get() = UpdateDownloadSource.fromStorage(prefs.getString(KEY_UPDATE_SOURCE, null))
        set(value) = prefs.edit().putString(KEY_UPDATE_SOURCE, value.storageValue).apply()

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

    /**
     * 表情面板的最近使用（只存 emoji 字符本身）。
     *
     * 用不可见分隔符存成一个字符串，避免 SharedPreferences 的 StringSet 打乱顺序。
     */
    var recentEmojis: List<String>
        get() = prefs.getString(KEY_RECENT_EMOJIS, null)
            .orEmpty()
            .split(EMOJI_SEPARATOR)
            .filter { it.isNotEmpty() }
        set(value) = prefs.edit()
            .putString(KEY_RECENT_EMOJIS, value.joinToString(EMOJI_SEPARATOR.toString()))
            .apply()

    /** 头像更新时间（0 = 没有头像）。文件本体在 `files/profile/avatar.jpg`。 */
    var avatarUpdatedAt: Long
        get() = prefs.getLong(com.board.ime.profile.AvatarStore.KEY_AVATAR_UPDATED_AT, 0L)
        set(value) = prefs.edit()
            .putLong(com.board.ime.profile.AvatarStore.KEY_AVATAR_UPDATED_AT, value)
            .apply()

    /** 同步内容开关（剪贴板 / 用户词库 / 学习词 / 常用语 / 自定义词 / 主题 / 键盘设置） */
    override fun isEnabled(content: SyncContent): Boolean =
        prefs.getBoolean(content.storageKey, content.defaultEnabled)

    override fun setEnabled(content: SyncContent, enabled: Boolean) {
        prefs.edit().putBoolean(content.storageKey, enabled).apply()
    }

    /**
     * Cloud Library 的**本机隐藏**集合（只存 hash）。
     *
     * 语义：**只影响本机 UI 显示**，绝不动云端 ——
     * 不 DELETE binary、不改 index、不改 `uploadedAt`、不影响其他设备、不影响 24h TTL。
     *
     * 只保存 hash 字符串；**绝不保存**正文 / 图片二进制 / 本地路径 / URL / 任何大对象。
     */
    var hiddenLibraryHashes: Set<String>
        get() = runCatching { prefs.getStringSet(KEY_HIDDEN_LIBRARY, emptySet())?.toSet() ?: emptySet() }
            .getOrDefault(emptySet())
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_LIBRARY, value.toSet()).apply()

    /** 本机隐藏一个 Library hash（幂等） */
    fun hideLibraryHash(hash: String) {
        if (hash.isBlank()) return
        hiddenLibraryHashes = hiddenLibraryHashes + hash
    }

    /** 解除本机隐藏（例如**本机主动重新上传**同一 hash 时） */
    fun unhideLibraryHash(hash: String) {
        if (hash.isBlank()) return
        val current = hiddenLibraryHashes
        if (!current.contains(hash)) return
        hiddenLibraryHashes = current - hash
    }

    fun isLibraryHashHidden(hash: String): Boolean =
        hash.isNotBlank() && hiddenLibraryHashes.contains(hash)

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
        /** 键盘抬升（底部留白 92px / 24px） */
        const val KEY_KEYBOARD_LIFT = "keyboard_lift_enabled"
        const val KEY_CANDIDATE_SIZE = "candidate_text_size"
        /** 候选栏英文释义开关（默认关闭） */
        const val KEY_CANDIDATE_ENGLISH_GLOSS = "candidate_english_gloss_enabled"
        /** 中英文自动空格（字母 / 数字分别开关，默认都关） */
        const val KEY_AUTO_SPACE_LETTERS = "auto_space_letters"
        const val KEY_AUTO_SPACE_DIGITS = "auto_space_digits"
        /** 繁体输出 / 双拼方案 / 模糊音分组 */
        const val KEY_TRADITIONAL_OUTPUT = "traditional_output"
        const val KEY_SHUANGPIN = "shuangpin_scheme"
        const val KEY_RIME_FUZZY_GROUPS = "rime_fuzzy_groups"
        const val KEY_SKIP_SENSITIVE = "skip_sensitive_clipboard"
        const val KEY_VOICE_PRIVACY_ACK = "voice_privacy_acknowledged"
        const val KEY_TRANSLATION_SOURCE = "translation_source_language"
        const val KEY_TRANSLATION_TARGET = "translation_target_language"
        const val KEY_DEVELOPER = "developer_mode"
        const val KEY_UPDATE_SOURCE = "update_download_source"
        const val KEY_TOOLBAR_TOOLS = "toolbar_tools"
        const val KEY_MORE_TOOLS = "more_tools"
        const val KEY_RECENT_EMOJIS = "recent_emojis"
        const val EMOJI_SEPARATOR = '\u001F'

        /** Cloud Library 本机隐藏（只存 hash；不存正文/路径/URL） */
        const val KEY_HIDDEN_LIBRARY = "hidden_library_hashes"
    }
}
