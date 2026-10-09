package com.board.ime.ime

import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputConnection
import com.board.ime.voice.VoiceInputSession
import com.board.ime.voice.VoiceState

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import com.board.ime.BuildConfig
import com.board.ime.clipboard.ClipboardCaptureResult
import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.clipboard.ClipboardSummary
import com.board.ime.database.BoardDatabase
import com.board.ime.databinding.BoardKeyboardBinding
import com.board.ime.dictionary.DictionaryChangeNotifier
import com.board.ime.dictionary.DictionaryRepository
import com.board.ime.engine.BasicEngine
import com.board.ime.engine.ChineseEnglishLexiconLoader
import com.board.ime.engine.EnglishLexiconLoader
import com.board.ime.engine.InputEngine
import com.board.ime.engine.InputMode
import com.board.ime.engine.RimeEngine
import com.board.ime.engine.withEnglishGloss
import com.board.ime.engine.rime.RimeRuntime
import com.board.ime.keyboard.KeySpec
import com.board.ime.keyboard.KeyType
import com.board.ime.keyboard.KeyboardLayoutCalculator
import com.board.ime.keyboard.KeyboardLayoutMetrics
import com.board.ime.settings.BoardPreferences
import com.board.ime.settings.BoardSettingsActivity
import com.board.ime.settings.EngineKind
import com.board.ime.settings.CandidateTextSize
import com.board.ime.settings.KeyboardScope
import com.board.ime.settings.LayoutMode
import com.board.ime.settings.SettingsRepository
import com.board.ime.settings.ThemeMode
import com.board.ime.sync.ClipboardSyncState
import com.board.ime.sync.SyncEngine
import com.board.ime.sync.SyncEngineHolder
import com.board.ime.sync.SyncScheduler
import com.board.ime.sync.SyncTrigger
import com.board.ime.sync.DeviceRepository
import com.board.ime.ui.KeyboardTheme
import com.board.ime.ui.panel.MorePanel
import com.board.ime.ui.panel.PanelLayoutMode
import com.board.ime.ui.panel.TopPanelController
import com.board.ime.ui.panel.TopPanelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Board 输入法服务。
 *
 * 负责把三块东西接起来：
 * - [InputController] / [InputEngine]：输入逻辑（中文交给 librime）
 * - [KeyboardView]：只负责按键
 * - [TopPanelController]：顶部动态面板（Toolbar / Candidate / Clipboard）
 *
 * 面板状态由 `TopPanelController` 推导，这里只分发事件（工具栏动作、剪贴板插入等）。
 */
class BoardInputMethodService : InputMethodService(), com.board.ime.ui.KeyboardView.OnKeyboardActionListener {

    private lateinit var preferences: BoardPreferences

    /**
     * 外观 / 尺寸仓库。
     *
     * 用 `lazy` 而不是 `lateinit`：即使某个回调（例如主题刷新）在 onCreate 之外
     * 被触发，也不会因为「未初始化」把输入法整个弄挂。
     */
    private val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    private var engine: InputEngine = BasicEngine(InputMode.ENGLISH)

    // ---------------------------------------------------------------- 语音输入（v1.2 Phase 2）

    private var voiceSession: VoiceInputSession? = null

    /**
     * 语音识别凭据（百度智能云语音服务的 AppID + API Key）。
     * **与翻译凭据完全分开存**（官方两套服务不是同一套凭据，混用会鉴权失败）。
     */
    private val voiceCredentials by lazy {
        com.board.ime.voice.VoiceCredentialStore(this)
    }

    /**
     * 当前语音引擎：**百度实时语音识别（realtime ASR）**。
     *
     * 🎙 的语义永远是「语音 → 原语言文字」，**不接语音翻译**。
     * 需要语音智能云的 **AppID + API Key**（与翻译平台的 APPID/密钥不是同一套）。
     *
     * 备选（如确实要做"边说边译"）：`BaiduVoiceTranslateEngine`，它复用翻译凭据，
     * 但是整段上传、且输出的是译文 —— 与 🎙 的定位不符，默认不启用。
     */
    private val voiceEngine: com.board.ime.voice.VoiceRecognitionEngine by lazy {
        com.board.ime.voice.BaiduRealtimeVoiceRecognitionProvider(
            credentialsProvider = { voiceCredentials.load() },
        )
    }

    /**
     * 语音流程与输入法之间的桥。只暴露最小接口，
     * 不把 SpeechRecognizer 细节泄漏进服务，也不碰 Rime / Clipboard / 存储。
     */
    private val voiceHost = object : VoiceInputSession.Host {
        override val isChineseMode: Boolean
            get() = controller.letterMode == InputMode.CHINESE

        override val inputConnection: InputConnection?
            get() = currentInputConnection

        override fun discardComposition() = controller.discardComposition()

        override fun showVoicePanel() {
            panelController?.showVoice()
        }

        override fun hideVoicePanel() {
            panelController?.closeVoice()
        }

        override fun updateVoicePanel(state: VoiceState, text: String) {
            panelController?.updateVoice(state, text)
        }

        /** 权限被拒：弹系统授权框（输入法没有弹权限的能力，交给透明 Activity 代劳） */
        override fun requestMicPermission() {
            runCatching {
                startActivity(
                    Intent(this@BoardInputMethodService, MicPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                Log.w(TAG, "申请麦克风权限失败：${it.message}")
                // 兜底：至少把用户带到应用详情页，能手动打开
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure { inner -> Log.w(TAG, "打开系统设置失败：${inner.message}") }
            }
        }

        override fun notify(message: String) {
            Toast.makeText(this@BoardInputMethodService, message, Toast.LENGTH_SHORT).show()
        }

        /** 是否已确认过语音隐私说明（只存一个布尔，不存任何语音内容） */
        override val voicePrivacyAcknowledged: Boolean
            get() = preferences.voicePrivacyAcknowledged

        /** 第一次点语音：先把"这是系统语音识别（可能联网）"讲清楚，点继续才真正开始 */
        override fun requestVoicePrivacyConsent() {
            showVoicePrivacyCard(consent = true)
        }

        /** 面板底部说明的入口：看完整说明，不影响当前识别 */
        override fun showVoicePrivacyInfo() {
            showVoicePrivacyCard(consent = false)
        }
    }

    /** 语音工具入口：点一次开始；识别中再点一次 = 结束收音（结果照常上屏） */
    private fun onVoiceTool() {
        val session = voiceSession
            ?: VoiceInputSession(this, voiceHost, voiceEngine).also { voiceSession = it }
        session.toggle()
    }

    // ---------------------------------------------------------------- 语音隐私说明

    /** 隐私说明卡片：直接盖在输入法自己的窗口上，不依赖弹窗权限、不依赖 Toast */
    private var voicePrivacyCard: com.board.ime.ui.panel.VoicePrivacyCard? = null

    private fun showVoicePrivacyCard(consent: Boolean) {
        if (voicePrivacyCard != null) return
        val root = window?.window?.decorView as? android.view.ViewGroup ?: return
        val card = com.board.ime.ui.panel.VoicePrivacyCard(this)
        val cloud = voiceEngine.isConfigured()
        card.bind(
            KeyboardTheme.from(this, preferences.themeMode, preferences.candidateTextSize),
            resolveVoiceServiceLabel(),
            consent,
            title = if (cloud) {
                "使用百度实时语音识别"
            } else {
                "使用系统语音识别"
            },
            body = if (cloud) {
                "语音会通过加密连接发送给百度语音服务处理。Board 不保存录音或识别结果。" +
                    "数据处理及留存以百度相关服务协议和隐私政策为准。"
            } else {
                "语音会交给设备提供的系统语音识别服务处理，可能联网发送给该服务。" +
                    "Board 不保存录音或识别结果。数据处理及留存以该服务提供方的政策为准。"
            },
        )
        if (consent) {
            card.onAccept = {
                // 用户明确点「继续」才写入确认标志，然后才真正开始识别
                preferences.voicePrivacyAcknowledged = true
                dismissVoicePrivacyCard()
                voiceSession?.onPrivacyAccepted()
            }
        }
        card.onDismiss = { dismissVoicePrivacyCard() }
        root.addView(
            card,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        voicePrivacyCard = card
    }

    /** 通用：只告知、不确认的说明卡片（翻译隐私说明用） */
    private fun showPrivacyCard(title: String, body: String, serviceLabel: String?) {
        if (voicePrivacyCard != null) return
        val root = window?.window?.decorView as? android.view.ViewGroup ?: return
        val card = com.board.ime.ui.panel.VoicePrivacyCard(this)
        card.bindInfo(
            KeyboardTheme.from(this, preferences.themeMode, preferences.candidateTextSize),
            title,
            body,
            serviceLabel,
        )
        card.onDismiss = { dismissVoicePrivacyCard() }
        root.addView(
            card,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        voicePrivacyCard = card
    }

    private fun dismissVoicePrivacyCard() {
        voicePrivacyCard?.let { card -> (card.parent as? android.view.ViewGroup)?.removeView(card) }
        voicePrivacyCard = null
    }

    /**
     * 尝试取「系统语音识别」对应的应用名。
     *
     * 取不到（不少 ROM 都取不到）就返回 `null` —— **不猜名字**，UI 直接不显示这一行。
     */
    private fun resolveVoiceServiceLabel(): String? {
        // 用了百度实时语音识别时，服务方是确定的，直接如实标注
        if (voiceEngine.isConfigured()) return voiceEngine.displayName
        return runCatching {
        val component = android.provider.Settings.Secure.getString(
            contentResolver,
            "voice_recognition_service",
        )
        val servicePackage = component?.substringBefore('/')?.takeIf { it.isNotBlank() }
        if (servicePackage == null) return null
        val info = packageManager.getApplicationInfo(servicePackage, 0)
        packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 常用语（v1.2 Phase 4）

    private var phraseRepository: com.board.ime.phrase.PhraseRepository? = null
    private var phraseCollector: kotlinx.coroutines.Job? = null

    /** 当前常用语列表（面板创建时立即渲染，避免闪空） */
    private var phrases: List<com.board.ime.database.entity.PhraseEntry> = emptyList()

    /** 只在一行数据真正变化时刷新面板（不是每次按键） */
    private fun startPhraseObservation() {
        if (phraseCollector != null) return
        val repository = phraseRepository ?: return
        phraseCollector = serviceScope.launch {
            repository.observe().collect { list ->
                phrases = list
                mainHandler.post { panelController?.updatePhrases(list) }
            }
        }
    }

    /** 打开常用语面板：① 立刻显示本地快照 ② 发起一次 Pull ③ Pull 完成后再刷新一次 */
    private fun openPhrases() {
        panelController?.showPhrases()
        panelController?.updatePhrases(phrases)
        syncEngine?.requestPhraseSync()
    }

    /**
     * Pull + merge 之后**显式**重读数据库并刷新面板。
     *
     * 不再"假设 Room Flow 一定会刷新"：面板可能是刚打开、collector 刚启动，
     * 或者 Flow 的首个快照早于 merge —— 都会造成"第一次进入看不到最新内容，退出再进才有"。
     */
    private fun refreshPhrasesFromDatabase() {
        val repository = phraseRepository ?: return
        serviceScope.launch {
            val list = repository.all()
                .filter { it.deletedAt == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.updatedAt }, { it.phraseId }))
            phrases = list
            mainHandler.post { panelController?.updatePhrases(list) }
        }
    }

    /**
     * 点击常用语：**直接上屏**。
     *
     * 先 `discardComposition()`（不残留未提交拼音），再按当前光标位置 commitText。
     * **全程不写系统剪贴板**，因此不会触发 Clipboard Capture。
     */
    private fun usePhrase(entry: com.board.ime.database.entity.PhraseEntry) {
        controller.discardComposition()
        currentInputConnection?.commitText(entry.text, 1)
        // 只记长度，绝不打正文
        Log.i(TAG, "phrase inserted chars=${entry.text.length}")
    }

    private fun editPhrase(entry: com.board.ime.database.entity.PhraseEntry) {
        runCatching {
            startActivity(
                com.board.ime.phrase.PhraseEditActivity
                    .editIntent(this, entry.phraseId, entry.text)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "打开常用语编辑失败：${it.message}") }
    }

    private fun addPhrase() {
        runCatching {
            startActivity(
                com.board.ime.phrase.PhraseEditActivity
                    .addIntent(this)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "打开常用语新增失败：${it.message}") }
    }

    /** 软删除：只留墓碑，删除才能同步传播，不会被别的设备复活 */
    private fun deletePhrase(entry: com.board.ime.database.entity.PhraseEntry) {
        serviceScope.launch {
            if (phraseRepository?.delete(entry.phraseId) == true) {
                Log.i(TAG, "phrase deleted")
                syncEngine?.requestPhraseSync()
            }
        }
    }

    private fun reorderPhrases(order: List<String>) {
        serviceScope.launch {
            // 只有顺序真的变了才触发同步（拖动中间帧不会走到这里）
            if (phraseRepository?.reorder(order) == true) {
                syncEngine?.requestPhraseSync()
            }
        }
    }

    // ---------------------------------------------------------------- 翻译（v1.2 Phase 3）

    /** 翻译协程作用域：跟随 IME 生命周期，onDestroy 一并取消（不留后台请求） */
    private val translationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var translation: com.board.ime.translation.TranslationSession? = null

    /** 读取输入框内容的上限：足够覆盖正常使用，同时避免超长文本把 IPC 撑爆 */
    private val translateReadChars = 10_000

    private val translationCredentials by lazy {
        com.board.ime.translation.TranslationCredentialStore(this)
    }

    private val translationProvider: com.board.ime.translation.TranslationProvider by lazy {
        com.board.ime.translation.BaiduTranslationProvider(
            credentialsProvider = { translationCredentials.load() },
        )
    }

    private val translationHost = object : com.board.ime.translation.TranslationSession.Host {
        override fun showTranslationPanel() {
            panelController?.setTranslationServiceName(translationProvider.displayName)
            panelController?.showTranslation()
        }

        override fun hideTranslationPanel() {
            panelController?.closeTranslation()
        }

        override fun renderTranslation(
            state: com.board.ime.translation.TranslationUiState,
            sourceText: String,
            resultText: String,
            source: com.board.ime.translation.TranslationLanguage,
            target: com.board.ime.translation.TranslationLanguage,
            message: String,
        ) {
            panelController?.updateTranslation(state, sourceText, resultText, source, target, message)
        }

        override fun currentInputText(): String? = currentCommittedText()

        override fun replaceCurrentInput(text: String) = applyTextReplace(text)

        /**
         * 只有用户点 [复制] 才写系统剪贴板。
         *
         * 同时**抑制 Board 自己的剪贴板历史**：否则译文会被剪贴板监听抓进 Room，
         * 那就等于把翻译结果落盘了（隐私基线禁止）。
         */
        override fun copyToClipboard(text: String) {
            val manager = clipboardManager ?: return
            clipboardRepository?.suppressCapture(ClipboardHasher.hash(text))
            runCatching {
                manager.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, text))
            }.onFailure { Log.w(TAG, "翻译复制失败（后台限制）：${it.message}") }
        }
    }

    private fun translationSession(): com.board.ime.translation.TranslationSession =
        translation ?: com.board.ime.translation.TranslationSession(
            host = translationHost,
            provider = { translationProvider },
            scope = translationScope,
            loadSourceLanguage = {
                com.board.ime.translation.TranslationLanguage.fromCode(
                    preferences.translationSourceLanguage,
                ) ?: com.board.ime.translation.TranslationLanguage.AUTO
            },
            saveSourceLanguage = { preferences.translationSourceLanguage = it.code },
            loadTargetLanguage = {
                com.board.ime.translation.TranslationLanguage.fromCode(
                    preferences.translationTargetLanguage,
                ) ?: com.board.ime.translation.TranslationLanguage.ENGLISH
            },
            saveTargetLanguage = { preferences.translationTargetLanguage = it.code },
        ).also { translation = it }

    private fun openTranslation() {
        translationSession().open()
    }

    /** 翻译隐私说明（服务商透明 + 数据去向），与语音用同一套卡片 */
    private fun showTranslationInfoCard() {
        showPrivacyCard(
            title = "翻译隐私说明",
            body = buildString {
                append("翻译需要联网，原文会发送给当前的翻译服务商处理。")
                append("Board 不在本地保存原文或译文，也不建立翻译历史与缓存。")
                append("服务商的数据处理与留存以其服务协议和隐私政策为准。")
            },
            serviceLabel = translationProvider.displayName,
        )
    }

    /**
     * 读取当前输入框里**已上屏**的文本（不含尚未提交的 Rime 组词）。
     *
     * 只有用户点「翻译」时才交给网络；读完只存在于翻译会话的内存里，
     * 不写任何本地存储、不进日志。
     */
    private fun currentCommittedText(): String? {
        val connection = currentInputConnection ?: return null
        val selected = runCatching { connection.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) return selected.toString()
        val before = runCatching { connection.getTextBeforeCursor(translateReadChars, 0) }
            .getOrNull()?.toString().orEmpty()
        val after = runCatching { connection.getTextAfterCursor(translateReadChars, 0) }
            .getOrNull()?.toString().orEmpty()
        // 未提交的拼音不算「可翻译文本」，也不去动它
        var head = before
        val composing = controller.composing
        if (composing.isNotEmpty() && head.endsWith(composing)) {
            head = head.dropLast(composing.length)
        }
        return (head + after).takeIf { it.isNotBlank() }
    }

    /**
     * 用译文替换输入框内容。
     *
     * 先 `discardComposition()`（只用引擎公开 API，不销毁 Rime 会话），
     * 避免出现「未提交拼音 + 译文」的混合状态；再按当前光标位置整体替换。
     */
    private fun applyTextReplace(text: String) {
        val connection = currentInputConnection ?: return
        controller.discardComposition()
        val selected = runCatching { connection.getSelectedText(0) }.getOrNull()
        if (!selected.isNullOrEmpty()) {
            connection.commitText(text, 1)
            return
        }
        val before = runCatching { connection.getTextBeforeCursor(translateReadChars, 0) }
            .getOrNull()?.length ?: 0
        val after = runCatching { connection.getTextAfterCursor(translateReadChars, 0) }
            .getOrNull()?.length ?: 0
        if (before > 0 || after > 0) connection.deleteSurroundingText(before, after)
        connection.commitText(text, 1)
    }
    private var rimeEngine: RimeEngine? = null
    private var controller = InputController(engine)
    private var appliedEngineKind: EngineKind? = null

    private lateinit var dictionaryRepository: DictionaryRepository

    /**
     * 自定义词数据变化（`custom_phrase` 已同步）时的刷新回调。
     *
     * 固定成字段是为了 [DictionaryChangeNotifier.unregister] 能拿到同一实例；
     * 服务在 `onCreate` 注册、`onDestroy` 注销，随服务生命周期存在，不持有 Activity，不泄漏。
     */
    private val dictionaryChangeListener: () -> Unit = { refreshNumericCustomPhrases() }

    private var binding: BoardKeyboardBinding? = null
    private var panelController: TopPanelController? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var rimePrepareStarted = false
    private var metrics: KeyboardLayoutMetrics? = null

    /** 本次 onFinishInputView 是否由「收起键盘」触发（触发时保留组词状态） */
    private var hidingForCollapse = false

    private var clipboardRepository: ClipboardRepository? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipboardObserver: Job? = null
    private var syncEngine: SyncEngine? = null

    /** 网络恢复回调：Push 失败后自动补一次剪贴板同步（不必用户进设置页） */
    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> applyPreferences() }

    /**
     * 外观 / 尺寸参数（独立存储）的变更监听。
     *
     * 拖动 SeekBar 时事件很密，这里做 60ms 合并：**只重绘真正受影响的 View**
     * （键盘 + 面板 + 尺寸），不重启输入法、不重新部署 Rime、不动数据库。
     */
    private val uiSettingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleThemeRefresh() }

    private val themeRefresh = Runnable { applyTheme() }

    private fun scheduleThemeRefresh() {
        mainHandler.removeCallbacks(themeRefresh)
        mainHandler.postDelayed(themeRefresh, THEME_REFRESH_DELAY_MS)
    }

    /** 当前该用哪一套键盘尺寸参数（竖屏 / 横屏 / 悬浮各一套） */
    private fun currentKeyboardScope(): KeyboardScope {
        val floating = when (preferences.layoutMode) {
            LayoutMode.AUTO -> KeyboardLayoutCalculator.prefersFloatingLayout(this)
            LayoutMode.FILL_WIDTH -> false
            LayoutMode.FLOATING -> true
        }
        return when {
            floating -> KeyboardScope.FLOATING
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ->
                KeyboardScope.LANDSCAPE

            else -> KeyboardScope.PORTRAIT
        }
    }

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        captureClipboard()
    }

    // ------------------------------------------------------------ 生命周期

    override fun onCreate() {
        super.onCreate()
        active = this
        preferences = BoardPreferences(this)
        // P3：本地中英释义表（纯本地 assets 小文件，同步读一次；loadOnce 幂等，
        // 读取失败也只按“无释义”处理，不影响输入、不联网、不访问数据库）。
        ChineseEnglishLexiconLoader.loadOnce(this)
        // 英文词频表（纯本地 assets 小文件，同步读一次；loadOnce 幂等，
        // 失败时保留内置兜底词表，不影响输入、不联网）。
        EnglishLexiconLoader.loadOnce(this)
        dictionaryRepository = DictionaryRepository(BoardDatabase.get(this).dictionaryDao(), this)
        buildController(preferences.engineKind, preferences.mode)

        // B1（D1）：必须与 SyncEngine/SyncEngineHolder 使用**同一个** repository 实例。
        // 之前这里自己 new 了一个，导致「本机新增历史」回调挂在不被使用的实例上
        // （真机已复现：Android 端永远不产生 clipboard_log_seq）。
        clipboardRepository = com.board.ime.sync.SyncEngineHolder.clipboardRepository(this)
        phraseRepository = com.board.ime.phrase.PhraseRepository(
            BoardDatabase.get(this).phraseDao(),
        )
        startPhraseObservation()
        registerClipboardListener()
        startSync()
        // Phase 1-F：启动时做一次历史维护（过期清理）
        maintainClipboardHistory()
        // 常用语同步：把仓库接给 SyncEngine（独立通道，不经过剪贴板）
        phraseRepository?.let { repository ->
            syncEngine?.attachPhraseRepository(repository)
            // Pull 完成 → 显式刷新面板（解决"第一次进入不刷新"）
            syncEngine?.onPhrasesApplied = { refreshPhrasesFromDatabase() }
        }

        preferences.registerListener(preferenceListener)
        settingsRepository.registerListener(uiSettingsListener)
        bootstrapDatabase()
        prepareRimeIfNeeded()
        // 设置页 / 同步改完自定义词后，立即刷新当前会话的 Board 侧映射（不必重进输入框）
        DictionaryChangeNotifier.register(dictionaryChangeListener)
    }

    override fun onCreateInputView(): View {
        val binding = BoardKeyboardBinding.inflate(layoutInflater)
        this.binding = binding

        binding.keyboardView.listener = this

        val panel = TopPanelController(
            toolbar = binding.toolbarView,
            candidateBar = binding.candidateBar,
            morePanel = binding.morePanel,
            clipboardHeader = binding.clipboardHeader,
            clipboardPanel = binding.clipboardPanel,
            voiceHeader = binding.voiceHeader,
            translationHeader = binding.translationHeader,
            translationPanel = binding.translationPanel,
            phrasesHeader = binding.phrasesHeader,
            phrasesPanel = binding.phrasesPanel,
        )
        panel.onCandidateSelected = { index -> controller.pickCandidate(index) }
        panel.onComposingClicked = { controller.commitCompositionAsIs() }
        panel.onMoreAction = { action -> onMoreAction(action) }
        panel.onClipboardInsert = { item -> insertClipboardItem(item) }
        panel.onClipboardTogglePin = { item -> setClipboardPinned(item, !item.isPinned) }
        panel.onClipboardToggleFavorite = { item -> setClipboardFavorite(item, !item.isFavorite) }
        panel.onClipboardDelete = { item -> deleteClipboardItem(item) }
        panel.onClipboardClearHistory = { includeProtected -> clearClipboardHistory(includeProtected) }
        panel.onClipboardLoadContent = { id, onLoaded -> loadClipboardContent(id, onLoaded) }
        panel.onClipboardLoadThumbnail = { item, maxPx, onLoaded ->
            loadClipboardThumbnail(item, maxPx, onLoaded)
        }
        panel.onClipboardItemSeen = { id -> markClipboardSeen(id) }
        panel.onClipboardSaveImage = { item -> saveClipboardImageToGallery(item) }
        panel.onPanelLayoutChanged = { mode -> applyPanelLayout(mode) }
        panel.onClipboardOpened = { onClipboardOpened() }
        panel.onSearchQueryChanged = { query -> observeClipboard(query, panel.isRemoteOnly) }
        panel.onClipboardFilterChanged = { remoteOnly ->
            observeClipboard(panel.searchQuery, remoteOnly)
        }
        panel.onCollapseKeyboard = { collapseKeyboard() }
        panel.onVoiceCancel = { voiceSession?.cancel() }
        panel.onVoiceStop = { voiceSession?.toggle() }
        panel.onVoiceMicStop = { voiceSession?.toggle() }
        panel.onVoiceInfo = { showVoicePrivacyCard(consent = false) }
        panel.onTranslationClose = { translationSession().close() }
        panel.onTranslationRun = { translationSession().translate() }
        panel.onTranslationCopy = { translationSession().copyResult() }
        panel.onTranslationReplace = { translationSession().replaceInput() }
        panel.onTranslationCycleSource = { translationSession().cycleSourceLanguage() }
        panel.onTranslationCycleTarget = { translationSession().cycleTargetLanguage() }
        panel.onTranslationOpenSettings = { openSettings() }

        // 剪贴板面板的云朵图标 -> 直接打开云端资料库（最小导航，不新增导航层）
        panel.onOpenLibrary = {
            runCatching {
                startActivity(
                    Intent(this, com.board.ime.settings.SettingsSectionActivity::class.java)
                        .putExtra(
                            com.board.ime.settings.SettingsSectionActivity.EXTRA_SECTION,
                            com.board.ime.settings.SettingsSection.LIBRARY.name,
                        )
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        panel.onTranslationInfo = { showTranslationInfoCard() }
        panel.onPhrasesClose = { panelController?.closePhrases() }
        panel.onPhrasesAdd = { addPhrase() }
        panel.onPhrasesUse = { entry -> usePhrase(entry) }
        panel.onPhrasesEdit = { entry -> editPhrase(entry) }
        panel.onPhrasesDelete = { entry -> deletePhrase(entry) }
        panel.onPhrasesReorder = { order -> reorderPhrases(order) }
        panel.onToolLayoutChanged = { layout ->
            // 复用现有 SharedPreferences（不新增数据库 / 表）
            preferences.toolbarTools = layout.encodeToolbar()
            preferences.moreTools = layout.encodeMore()
        }
        panel.onToolbarFull = {
            Toast.makeText(this, "工具栏已满（最多 6 个）", Toast.LENGTH_SHORT).show()
        }
        // 两个 key 都为 null = 从未自定义 → 默认布局；v1.1 升级不重置
        panel.toolLayout = com.board.ime.ui.panel.ToolLayout.parse(
            preferences.toolbarTools,
            preferences.moreTools,
        )
        panelController = panel
        // 面板刚建好：立刻把当前常用语渲染进去
        panel.updatePhrases(phrases)

        // 主题 / 尺寸只影响外观：即使失败也必须把键盘交出去
        runCatching { applyTheme() }.onFailure { throwable ->
            Log.e(TAG, "应用外观失败，键盘继续使用默认外观：${throwable.message}", throwable)
        }
        refreshInputView()
        return binding.root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 新的一次输入一定不是「收起键盘」造成的中断，清掉标记，避免残留
        hidingForCollapse = false
        controller.onStartInput(currentInputConnection, info, keepLayer = restarting)
        panelController?.resetToIdle()
        stopClipboardObservation()
        refreshBlockedWords()
        refreshNumericCustomPhrases()
        applyTheme()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // 「收起键盘」是用户主动隐藏，不算结束输入：保留组词与面板状态，
        // 这样键盘再出来时还能接着刚才的输入（不清空 composing）。
        val collapseOnly = hidingForCollapse
        hidingForCollapse = false
        Log.d(TAG, "onFinishInputView finishing=$finishingInput collapse=$collapseOnly")
        if (!collapseOnly) {
            controller.onFinishInput()
            panelController?.resetToIdle()
        }
        stopClipboardObservation()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.onSelectionChanged(candidatesStart, candidatesEnd)
    }

    /** 旋转屏幕 / 分屏：重新算尺寸，不改动输入状态 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
    }

    override fun onDestroy() {
        if (active === this) active = null
        DictionaryChangeNotifier.unregister(dictionaryChangeListener)
        networkCallback?.let { cb ->
            runCatching {
                getSystemService(android.net.ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        runCatching {
            preferences.unregisterListener(preferenceListener)
            settingsRepository.unregisterListener(uiSettingsListener)
        }.onFailure { Log.w(TAG, "注销监听失败：${it.message}") }
        clipboardManager?.removePrimaryClipChangedListener(clipboardListener)
        stopClipboardObservation()
        serviceScope.cancel()
        // 语音识别必须先于其它资源释放：停止收音 → destroy → 置空（避免麦克风/监听器泄漏）
        dismissVoicePrivacyCard()
        voiceSession?.release()
        voiceSession = null
        translation?.release()
        translation = null
        translationScope.cancel()
        phraseCollector?.cancel()
        phraseCollector = null
        RimeRuntime.close()
        binding = null
        panelController = null
        super.onDestroy()
    }

    /** 键盘不需要全屏编辑模式 */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = true

    // ------------------------------------------------------------ 键盘回调

    override fun onKey(key: KeySpec) {
        // 剪贴板搜索：按键先给搜索框，不打断、也不污染正在编辑的输入框
        val panel = panelController
        if (panel != null && panel.handleSearchKey(key)) {
            playKeyFeedback()
            return
        }
        playKeyFeedback()
        controller.onKey(key)
        if (key.type == KeyType.LANGUAGE) {
            persistLetterMode()
        }
    }

    override fun onKeyLongPress(key: KeySpec) {
        if (key.type == KeyType.LANGUAGE) {
            getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    override fun onKeyRepeat(key: KeySpec) {
        playKeyFeedback()
        controller.onKeyRepeat(key)
    }

    // ------------------------------------------------------------ 顶部面板

    private fun onMoreAction(action: MorePanel.Action) {
        when (action) {
            MorePanel.Action.CURSOR_LEFT -> controller.moveCursor(-1)
            MorePanel.Action.CURSOR_RIGHT -> controller.moveCursor(1)
            // 「主题」与「设置」都指向设置页（外观 / 主题在同一处）
            MorePanel.Action.THEME, MorePanel.Action.SETTINGS -> openSettings()
            // 尚未开发的功能：明确告知，不伪装成可用
            MorePanel.Action.TRANSLATE -> openTranslation()
            MorePanel.Action.VOICE -> onVoiceTool()
            MorePanel.Action.PHRASES -> openPhrases()
            // CLOSE / CLIPBOARD 由 TopPanelController 内部处理
            MorePanel.Action.CLOSE, MorePanel.Action.CLIPBOARD -> Unit
        }
    }

    private fun notifyNotImplemented(name: String) {
        Toast.makeText(this, "$name 功能尚未开发", Toast.LENGTH_SHORT).show()
    }

    /** 打开剪贴板：同步一次系统剪贴板并开始观察 Room 数据 */
    private fun onClipboardOpened() {
        // 0) 打开面板时做一次轻量历史维护（过期清理），不阻塞列表显示
        maintainClipboardHistory()
        // 1) 立即抓一次系统剪贴板（listener 之前不在时也能补抓）
        //    pushToCloud=false：补抓只入库，**不能**因此上传，否则会把云端覆盖成自己的内容
        captureClipboard(pushToCloud = false)
        // 2) 后台自动 Pull 当前云端内容（不阻塞本地列表显示）
        syncEngine?.requestClipboardSync()
        observeClipboard(panelController?.searchQuery.orEmpty(), panelController?.isRemoteOnly == true)
    }

    /**
     * 收起键盘。
     *
     * 用 InputMethodService 自己的隐藏能力（`requestHideSelf`），
     * **不模拟返回键、不 finish Activity**：输入法与输入框的连接保持原样，
     * 组词内容不会被清掉，下次点回输入框仍然正常使用 Board。
     */
    private fun collapseKeyboard() {
        hidingForCollapse = true
        requestHideSelf(0)
    }

    private fun insertClipboardItem(item: ClipboardSummary) {
        // 组词中的话先按原样结束组词，插入内容不会覆盖拼音
        if (controller.hasComposition) {
            controller.commitCompositionAsIs()
        }
        // 完整内容可能在几十 MB 的文件里：后台读，读完回主线程上屏
        serviceScope.launch {
            val content = clipboardRepository?.loadContent(item.id)
            mainHandler.post {
                if (content.isNullOrEmpty()) {
                    Toast.makeText(
                        this@BoardInputMethodService,
                        "这条内容已不可读（文件可能已被清理）",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    controller.commitText(content)
                }
            }
            clipboardRepository?.markUsed(item.id)
            // 用户点开/使用了这条 → 取消「未读」保护（Phase 1-F）
            clipboardRepository?.markRead(item.id)
        }
    }

    /** 用户查看了某条内容（点按 / 长按 / 预览）→ 取消未读保护，恢复正常 1 小时生命周期 */
    private fun markClipboardSeen(id: Long) {
        serviceScope.launch { clipboardRepository?.markRead(id) }
    }

    /**
     * 剪贴板历史维护（Phase 1-F）：删除超过 1 小时、非固定、非未读的普通条目。
     *
     * 触发时机：输入法启动 / 打开剪贴板面板 / 新条目插入后 / 后台周期任务。
     * 轻量：一条 SQL 判定，不做逐条扫描。
     */
    private fun maintainClipboardHistory() {
        serviceScope.launch {
            runCatching { clipboardRepository?.expireHistory(currentClipboardImageHash()) }
        }
    }

    /**
     * 用户**主动点击**预览里的下载图标 → 把当前 PNG 写入系统相册（`Pictures/Board`）。
     *
     * 这是用户明确发起的持久化行为；不点击时绝不写公共存储。
     */
    private fun saveClipboardImageToGallery(item: ClipboardSummary) {
        serviceScope.launch {
            val store = ClipboardRepository.imageStoreFor(this@BoardInputMethodService)
            val file = store.fileFor(item.contentHash)
            val saved = com.board.ime.clipboard.ClipboardImageExporter
                .saveToGallery(this@BoardInputMethodService, file, item.contentHash)
            mainHandler.post {
                // 面板**底部**瞬时提示（浮层，不改面板高度）；**只有真正写入成功才提示成功**
                panelController?.showClipboardMessage(
                    if (saved) "✓ 已下载到相册" else "× 下载失败",
                )
            }
        }
    }

    /** 「全文」按 id 取内容：IO 线程读，回主线程回调 */
    private fun loadClipboardContent(id: Long, onLoaded: (String?) -> Unit) {
        serviceScope.launch {
            val content = clipboardRepository?.loadContent(id)
            mainHandler.post { onLoaded(content) }
        }
    }

    /**
     * 图片缩略图：**从当前受控图片文件**按目标像素**降采样**解码（IO 线程），回主线程回调。
     *
     * - 不加载原图全尺寸 Bitmap；
     * - **不写任何磁盘缓存**（不生成缩略图文件）；
     * - 返回的 Bitmap 由面板负责回收（条目替换 / 面板离开窗口时）。
     */
    private fun loadClipboardThumbnail(
        item: ClipboardSummary,
        maxPx: Int,
        onLoaded: (android.graphics.Bitmap?) -> Unit,
    ) {
        serviceScope.launch {
            val bitmap = runCatching {
                // 按 contain 采样：超长图不会被整份解码（避免 OOM），尺寸与显示量级一致
                com.board.ime.clipboard.ClipboardImagePreviewDecoder
                    .decodeWithin(item.contentFilePath, maxPx, maxPx)
            }.getOrNull()
            mainHandler.post { onLoaded(bitmap) }
        }
    }

    private fun setClipboardPinned(item: ClipboardSummary, pinned: Boolean) {
        serviceScope.launch { clipboardRepository?.setPinned(item.id, pinned) }
    }

    private fun setClipboardFavorite(item: ClipboardSummary, favorite: Boolean) {
        serviceScope.launch { clipboardRepository?.setFavorite(item.id, favorite) }
    }

    private fun deleteClipboardItem(item: ClipboardSummary) {
        serviceScope.launch { clipboardRepository?.remove(item.id) }
    }

    /** 清空历史；默认保留固定 / 收藏，只有勾选后才一起删 */
    private fun clearClipboardHistory(includeProtected: Boolean) {
        serviceScope.launch {
            val removed = clipboardRepository?.clearHistory(includeProtected) ?: 0
            val message = if (includeProtected) {
                "已清空全部剪贴板历史（含固定 / 收藏）"
            } else {
                "已清空 $removed 条普通历史，固定 / 收藏保留"
            }
            mainHandler.post { Toast.makeText(this@BoardInputMethodService, message, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun observeClipboard(query: String = "", remoteOnly: Boolean = false) {
        val repository = clipboardRepository ?: return
        clipboardObserver?.cancel()
        clipboardObserver = serviceScope.launch {
            repository.search(query, CLIPBOARD_LIMIT, remoteOnly).collect { items ->
                val counts = repository.counts()
                mainHandler.post { panelController?.submitClipboard(items, counts) }
            }
        }
    }

    private fun stopClipboardObservation() {
        clipboardObserver?.cancel()
        clipboardObserver = null
    }

    private fun registerClipboardListener() {
        val manager = getSystemService(ClipboardManager::class.java) ?: return
        clipboardManager = manager
        manager.addPrimaryClipChangedListener(clipboardListener)
    }

    /** 把系统剪贴板里的文本记录到 Room（按内容哈希去重、敏感内容不入库） */
    /** @param pushToCloud 打开面板时的补抓要传 false：只入库，不产生上传 */
    private fun captureClipboard(pushToCloud: Boolean = true) {
        val manager = clipboardManager ?: return
        val clip = runCatching { manager.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return

        // Phase 1-E：图片**优先按 MIME 判定**，绝不把图片 URI 当普通文本处理
        val imageUri = clipImageUri(clip)
        if (imageUri != null) {
            captureClipboardImage(imageUri, pushToCloud)
            return
        }

        // ⚠️ **不 trim**：hash 必须与协议 / `applyRemote` 对齐（Board 自己写回的是**原样正文**）。
        // 这里一旦 trim，回调算出的 hash 就与 CLOUD 行不一致 → 找不到原条目 → 多一条 LOCAL。
        val text = runCatching { clip.getItemAt(0).coerceToText(this)?.toString() }
            .getOrNull()
            .orEmpty()
        if (text.isBlank()) return
        serviceScope.launch {
            val result = clipboardRepository?.capture(text, enqueuePush = pushToCloud)
            if (result == ClipboardCaptureResult.SKIPPED_SENSITIVE) {
                mainHandler.post {
                    Toast.makeText(
                        this@BoardInputMethodService,
                        "疑似验证码 / 密码，按策略不写入剪贴板历史",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } else if (result == ClipboardCaptureResult.INSERTED) {
                requestAutoSync()
                // 新条目插入后顺手做一次轻量过期维护
                maintainClipboardHistory()
            }
        }
    }

    // ------------------------------------------------------------ 图片剪贴板（Phase 1-E）

    /** autoSync=OFF 即「暂停」语义（不新增 paused 状态；只作用于图片路径） */
    private fun isAutoSyncOn(): Boolean =
        runCatching { syncEngine?.loadConfig()?.autoSync == true }.getOrDefault(false)

    /**
     * 剪贴板里若是图片，返回它的 URI。
     *
     * 判定顺序：`ClipDescription` 的 MIME 优先（`image` 开头），其次看各 item 的 URI MIME。
     */
    private fun clipImageUri(clip: ClipData): Uri? {
        val description = clip.description
        if (description != null) {
            for (i in 0 until description.mimeTypeCount) {
                if (com.board.ime.clipboard.ClipboardImageCodec.isImageMime(description.getMimeType(i))) {
                    return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.firstOrNull()
                }
            }
        }
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val type = runCatching { contentResolver.getType(uri) }.getOrNull()
            if (com.board.ime.clipboard.ClipboardImageCodec.isImageMime(type)) return uri
        }
        return null
    }

    /**
     * 系统剪贴板图片 → 与 `ACTION_SEND` 分享入口**完全同一条**链路
     * （`ClipboardRepository.captureImageFromUri`），不存在第二套图片上传代码。
     *
     * autoSync=OFF 时作为暂停：**不 Capture、不上传**。
     */
    private fun captureClipboardImage(uri: Uri, pushToCloud: Boolean) {
        if (!isAutoSyncOn()) {
            com.board.ime.sync.SyncLog.event(
                com.board.ime.sync.SyncEvent.IGNORED_TYPE,
                "autoSync=OFF（暂停），不抓取剪贴板图片",
            )
            return
        }
        serviceScope.launch {
            val result = clipboardRepository?.captureImageFromUri(
                contentResolver,
                uri,
                enqueuePush = pushToCloud,
            )
            if (result == ClipboardCaptureResult.INSERTED) requestAutoSync()
        }
    }

    /**
     * 远端图片写进系统剪贴板。
     *
     * **先按本端重新编码算出本端 hash 并登记抑制**，再写剪贴板 ——
     * 否则系统回调会把它当本机复制再上传（回环）。跨平台 PNG 编码不同，所以不用远端 hash。
     */
    private fun applyRemoteImageToClipboard(hash: String) {
        if (!isAutoSyncOn()) {
            Log.w(TAG, "autoSync=OFF（暂停），不写图片剪贴板")
            return
        }
        val manager = clipboardManager ?: return
        serviceScope.launch {
            val store = ClipboardRepository.imageStoreFor(this@BoardInputMethodService)
            val file = store.fileFor(hash)
            if (!file.isFile) return@launch
            val processed = com.board.ime.clipboard.ClipboardImageCodec
                .processRaw(file.readBytes()) ?: return@launch
            clipboardRepository?.suppressCapture(processed.hash)
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    this@BoardInputMethodService,
                    "$packageName.fileprovider",
                    file,
                )
            }.getOrNull()
            if (uri == null) {
                Log.w(TAG, "图片 FileProvider URI 生成失败（authority / paths 配置？）")
                return@launch
            }
            // 关键：**显式声明 image/png**，不依赖 FileProvider 的 getType 一定返回非空。
            // 若 ClipDescription 没有 MIME，支持图片粘贴的 App 会用 hasMimeType("image/*")
            // 判定“剪贴板里没有图片”，于是长按粘贴什么都不出。
            val mime = contentResolver.getType(uri) ?: "image/png"
            val clip = ClipData(
                ClipDescription(CLIPBOARD_LABEL, arrayOf(mime)),
                ClipData.Item(uri),
            )
            val set = runCatching { manager.setPrimaryClip(clip) }
            if (set.isFailure) {
                // Android 10+ 后台写剪贴板可能被忽略；本地历史里仍然有这条
                Log.w(TAG, "写入图片剪贴板被拒绝（后台限制）：${set.exceptionOrNull()?.message}")
                return@launch
            }
            // 回读确认系统真的接受了这个 clip（只记元数据，绝不记图片内容）
            val back = runCatching { manager.primaryClip }.getOrNull()
            val backMime = back?.description?.let { d ->
                if (d.mimeTypeCount > 0) d.getMimeType(0) else null
            }
            Log.i(
                TAG,
                "图片剪贴板已写入 mime=$mime items=${back?.itemCount ?: 0} readbackMime=$backMime",
            )
        }
    }

    /** **当前系统剪贴板正在引用**的图片 hash（清理想保护它，避免用户稍后粘贴失败） */
    private fun currentClipboardImageHash(): String? {
        val clip = runCatching { clipboardManager?.primaryClip }.getOrNull() ?: return null
        for (i in 0 until clip.itemCount) {
            val path = clip.getItemAt(i).uri?.path ?: continue
            val name = path.substringAfterLast('/')
            if (name.endsWith(com.board.ime.clipboard.ClipboardImageStore.PNG_SUFFIX)) {
                return name.removeSuffix(com.board.ime.clipboard.ClipboardImageStore.PNG_SUFFIX)
            }
        }
        return null
    }

    // ------------------------------------------------------------ 同步

    /**
     * 起同步：注册「远端内容 → 系统剪贴板」的落点，并按需触发一次同步。
     *
     * 明确一点：这里的自动同步是**启动检查 + 复制后去抖上传 + WorkManager 周期兜底**，
     * 不是实时推送（实时留给后续的局域网方案）。
     */
    private fun startSync() {
        val engine: SyncEngine = SyncEngineHolder.get(this)
        syncEngine = engine
        val config = engine.loadConfig()
        SyncScheduler.applyAutoSync(this, config.autoSync)

        // 网络恢复 → 若上次剪贴板同步失败，自动重试（WorkManager 只是兜底，不做实时触发源）
        runCatching {
            if (networkCallback != null) return@runCatching
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val engine = syncEngine ?: return
                    if (engine.status.value.clipboardState == ClipboardSyncState.FAILED) {
                        com.board.ime.sync.SyncLog.event(com.board.ime.sync.SyncEvent.RETRY, "网络恢复，自动重试剪贴板同步 retry=true")
                        engine.requestClipboardSync()
                    }
                }
            }
            networkCallback = callback
            getSystemService(android.net.ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(callback)
        }.onFailure { Log.w(TAG, "注册网络回调失败：${it.message}") }

        serviceScope.launch {
            engine.applied.collect { content -> mainHandler.post { applyRemoteToClipboard(content) } }
        }

        // 图片落点：独立通道（不触碰文字链路）
        serviceScope.launch {
            engine.appliedImage.collect { hash -> mainHandler.post { applyRemoteImageToClipboard(hash) } }
        }
        // 图片生命周期：清理无引用且已过期的旧文件；**当前剪贴板引用的那张不删**
        serviceScope.launch {
            runCatching { clipboardRepository?.pruneImages(currentClipboardImageHash()) }
        }

        if (config.isConfigured && config.autoSync) {
            // 输入法启动时补一次剪贴板同步（trailing-edge 合并，绝不会丢）
            engine.requestClipboardSync()
        }
    }

    /** 复制了新内容后，去抖地推一次（配置了才会真的动网络） */
    private fun requestAutoSync() {
        val engine = syncEngine ?: return
        val config = engine.loadConfig()
        if (!config.isConfigured || !config.autoSync) return
        // 复制后：只走剪贴板独立通道，不等词库 / 学习词
        engine.requestClipboardSync()
    }

    /**
     * 远端拉下来的内容写进系统剪贴板。
     *
     * **先登记抑制窗口**，否则系统会回调 `OnPrimaryClipChangedListener`，
     * 又被当成「本机复制」上传一次 → WebDAV → 本地 → 回调 → 上传 的无限回环。
     */
    private fun applyRemoteToClipboard(content: String) {
        val manager = clipboardManager ?: return
        clipboardRepository?.suppressCapture(ClipboardHasher.hash(content))
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText(CLIPBOARD_LABEL, content))
        }.onFailure {
            // Android 10+ 后台写剪贴板可能被忽略；本地历史里仍然有这条，用户可以从面板插入
            Log.w(TAG, "写入系统剪贴板被拒绝（后台限制）：${it.message}")
        }
    }

    private fun openSettings() {
        runCatching {
            startActivity(
                Intent(this, BoardSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * TopPanel 下面的区域怎么摆。TopPanel 自己永远是 56dp，不参与这里的变化：
     * - 只有键盘：工具栏 / 候选 / 更多
     * - 只有内容区：普通剪贴板（内容区高度 = 键盘高度，所以总高度不变）
     * - 内容区 + 键盘：剪贴板搜索（内容区有高度上限，键盘照常在下面，可以打字）
     */
    private fun applyPanelLayout(mode: PanelLayoutMode) {
        val binding = binding ?: return
        val keyboardHeight = metrics?.keyboardHeightPx(binding.keyboardView.keyboardLayout.rowCount) ?: 0
        val theme = KeyboardTheme.from(
            this,
            preferences.themeMode,
            preferences.candidateTextSize,
            settingsRepository.geometry(currentKeyboardScope()),
            settingsRepository.appearance(),
        )

        // 分隔线始终保留：面板与下方内容（键盘 / 剪贴板）的分界在四种状态下一致
        binding.panelDivider.visibility = View.VISIBLE

        when (mode) {
            PanelLayoutMode.KEYBOARD_ONLY -> {
                binding.keyboardView.visibility = View.VISIBLE
                binding.clipboardPanel.visibility = View.GONE
                binding.translationPanel.visibility = View.GONE
                binding.phrasesPanel.visibility = View.GONE
                binding.clipboardPanel.layoutParams = binding.clipboardPanel.layoutParams.apply {
                    height = keyboardHeight
                }
            }

            PanelLayoutMode.CLIPBOARD_ONLY -> {
                binding.keyboardView.visibility = View.GONE
                binding.translationPanel.visibility = View.GONE
                binding.phrasesPanel.visibility = View.GONE
                binding.clipboardPanel.visibility = View.VISIBLE
                binding.clipboardPanel.layoutParams = binding.clipboardPanel.layoutParams.apply {
                    height = keyboardHeight
                }
            }

            PanelLayoutMode.CLIPBOARD_WITH_KEYBOARD -> {
                binding.keyboardView.visibility = View.VISIBLE
                binding.translationPanel.visibility = View.GONE
                binding.phrasesPanel.visibility = View.GONE
                binding.clipboardPanel.visibility = View.VISIBLE
                binding.clipboardPanel.layoutParams = binding.clipboardPanel.layoutParams.apply {
                    height = theme.clipboardSearchMaxHeight
                }
            }

            PanelLayoutMode.TRANSLATION_ONLY -> {
                // 内容区占键盘的位置：总高度与其它状态完全一致，键盘不会跳
                binding.keyboardView.visibility = View.GONE
                binding.clipboardPanel.visibility = View.GONE
                binding.phrasesPanel.visibility = View.GONE
                binding.translationPanel.visibility = View.VISIBLE
                binding.translationPanel.layoutParams = binding.translationPanel.layoutParams.apply {
                    height = keyboardHeight
                }
            }

            PanelLayoutMode.PHRASES_ONLY -> {
                binding.keyboardView.visibility = View.GONE
                binding.clipboardPanel.visibility = View.GONE
                binding.translationPanel.visibility = View.GONE
                binding.phrasesPanel.visibility = View.VISIBLE
                binding.phrasesPanel.layoutParams = binding.phrasesPanel.layoutParams.apply {
                    height = keyboardHeight
                }
            }
        }
    }

    /** 剪贴板里的返回键：先退临时视图 / 搜索，最后才交给系统关输入法 */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            val panel = panelController
            if (panel != null && panel.currentState == TopPanelState.CLIPBOARD) {
                panel.handleClipboardBack()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------ 引擎

    private fun buildController(kind: EngineKind, letterMode: InputMode) {
        val newEngine: InputEngine = when (kind) {
            EngineKind.RIME -> RimeEngine()
            EngineKind.BASIC -> BasicEngine(letterMode)
        }
        rimeEngine = newEngine as? RimeEngine
        controller.onStateChanged = null
        engine = newEngine
        controller = InputController(newEngine, letterMode).also { it.onStateChanged = { refreshInputView() } }
        appliedEngineKind = kind
    }

    private fun ensureEngineKind() {
        val desired = preferences.engineKind
        if (appliedEngineKind == desired) return
        buildController(desired, preferences.mode)
        if (desired == EngineKind.RIME) {
            prepareRimeIfNeeded()
        }
    }

    private fun prepareRimeIfNeeded() {
        if (rimePrepareStarted || preferences.engineKind != EngineKind.RIME) return
        rimePrepareStarted = true
        serviceScope.launch {
            val installed = RimeRuntime.initialize(
                this@BoardInputMethodService,
                BuildConfig.VERSION_NAME,
            )
            val deployed = installed && RimeRuntime.deploy(fullCheck = RimeRuntime.dataWasReinstalled)
            val opened = deployed && RimeRuntime.openSession()
            mainHandler.post {
                if (opened) {
                    Log.i(
                        TAG,
                        "Rime 就绪：librime=${RimeRuntime.librimeVersion} schema=${RimeRuntime.currentSchema()}",
                    )
                } else {
                    Log.w(TAG, "Rime 未就绪：${RimeRuntime.lastError}")
                }
                refreshInputView()
            }
        }
    }

    private fun persistLetterMode() {
        if (preferences.mode != controller.letterMode) {
            preferences.mode = controller.letterMode
        }
    }

    // ------------------------------------------------------------ 视图刷新

    private fun refreshInputView() {
        val binding = binding ?: return
        binding.keyboardView.keyboardLayout = controller.currentLayout
        binding.keyboardView.keyboardMode = controller.mode
        binding.keyboardView.setShiftState(active = controller.shiftActive, locked = controller.capsLock)

        // P3 Stage 1：候选进入 UI 前按开关附加本地英文释义。
        // 纯内存 Map 查询、不联网、不访问数据库；不改顺序 / 下标 / 上屏。
        val candidates = if (preferences.candidateEnglishGlossEnabled) {
            controller.candidates.withEnglishGloss()
        } else {
            controller.candidates
        }
        panelController?.updateInput(
            candidates = candidates,
            mode = controller.mode,
            composing = controller.composing,
            statusText = rimeStatusText(),
        )
    }

    /** 面板本身不再显示输入状态，这里只把「引擎暂时不可用」这类真实状态传下去 */
    private fun rimeStatusText(): String? {
        if (controller.mode != InputMode.CHINESE) return null
        if (appliedEngineKind != EngineKind.RIME) return null
        return RimeRuntime.statusText()
    }

    private fun applyTheme() {
        val scope = currentKeyboardScope()
        // 关键防线：外观 / 尺寸出任何问题都只能影响「好不好看」，
        // **绝不允许影响「键盘能不能出来」**。所以这里整体兜底并回退默认外观。
        val resolved = runCatching {
            val geometry = settingsRepository.geometry(scope)
            val appearance = settingsRepository.appearance()
            Log.i(
                TAG,
                "外观/尺寸应用 scope=$scope height=${geometry.heightFactor} " +
                    "gap=${geometry.keyGapDp} keyRadius=${geometry.keyCornerRadiusDp} " +
                    "fn=${geometry.functionKeyWidthRatio} colors=${appearance.colors.size} " +
                    "radii=${appearance.radii.size} binding=${binding != null}",
            )
            KeyboardTheme.from(
                this,
                preferences.themeMode,
                preferences.candidateTextSize,
                geometry,
                appearance,
            )
        }.getOrElse { throwable ->
            Log.e(TAG, "外观/尺寸参数有问题，已回退到默认外观：${throwable.message}", throwable)
            runCatching {
                KeyboardTheme.from(this, preferences.themeMode, preferences.candidateTextSize)
            }.getOrElse { inner ->
                Log.e(TAG, "默认外观也解析失败，回退浅色标准外观：${inner.message}", inner)
                KeyboardTheme.from(this, ThemeMode.LIGHT, CandidateTextSize.STANDARD)
            }
        }
        val binding = binding ?: return

        binding.panelDivider.setBackgroundColor(resolved.divider)
        binding.panelDivider.layoutParams = binding.panelDivider.layoutParams?.apply {
            height = resolved.dividerHeight
        }
        binding.keyboardView.applyTheme(resolved)
        panelController?.applyTheme(resolved)

        applyLayoutMetrics(resolved, scope)
        refreshInputView()
    }

    /**
     * 竖屏铺满宽度；横屏 / 大屏使用悬浮布局。
     * 尺寸全部由 [KeyboardLayoutCalculator] 算出来，View 与布局里没有硬编码尺寸。
     */
    private fun applyLayoutMetrics(theme: KeyboardTheme, scope: KeyboardScope) {
        val binding = binding ?: return
        val floating = scope == KeyboardScope.FLOATING ||
            (scope == KeyboardScope.LANDSCAPE && preferences.layoutMode == LayoutMode.AUTO)
        val config = KeyboardLayoutCalculator.configFor(
            context = this,
            floating = floating,
            geometry = settingsRepository.geometry(scope),
        )
        val display = resources.displayMetrics

        val resolved = KeyboardLayoutCalculator.metrics(
            config = config,
            screenWidthPx = display.widthPixels,
            screenHeightPx = display.heightPixels,
            density = display.density,
            rowCount = binding.keyboardView.keyboardLayout.rowCount,
        )
        metrics = resolved
        binding.keyboardView.applyMetrics(resolved)

        val floatParams = binding.keyboardFloat.layoutParams as FrameLayout.LayoutParams
        if (resolved.floating) {
            floatParams.width = resolved.widthPx
            floatParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
            floatParams.gravity = Gravity.BOTTOM or
                if (resolved.alignEnd) Gravity.END else Gravity.CENTER_HORIZONTAL
            val margin = resolved.edgeMarginPx
            floatParams.setMargins(margin, margin, margin, margin)
            binding.keyboardFloat.background = floatingCard(theme, resolved.cornerRadiusPx)
            binding.keyboardRoot.setBackgroundColor(Color.TRANSPARENT)
            window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        } else {
            floatParams.width = FrameLayout.LayoutParams.MATCH_PARENT
            floatParams.height = FrameLayout.LayoutParams.WRAP_CONTENT
            floatParams.gravity = Gravity.NO_GRAVITY
            floatParams.setMargins(0, 0, 0, 0)
            binding.keyboardFloat.background = null
            binding.keyboardRoot.setBackgroundColor(theme.keyboardBackground)
        }
        binding.keyboardFloat.layoutParams = floatParams
        // 重新算尺寸后，按当前面板状态重排「键盘 / 剪贴板内容区」
        panelController?.let { panel ->
            val mode = when {
                panel.currentState == TopPanelState.TRANSLATION -> PanelLayoutMode.TRANSLATION_ONLY
                panel.currentState == TopPanelState.PHRASES -> PanelLayoutMode.PHRASES_ONLY
                panel.currentState != TopPanelState.CLIPBOARD -> PanelLayoutMode.KEYBOARD_ONLY
                panel.isSearching -> PanelLayoutMode.CLIPBOARD_WITH_KEYBOARD
                else -> PanelLayoutMode.CLIPBOARD_ONLY
            }
            applyPanelLayout(mode)
        }
    }

    private fun floatingCard(theme: KeyboardTheme, radius: Float) = GradientDrawable().apply {
        setColor(theme.keyboardBackground)
        cornerRadius = radius
    }

    private fun applyPreferences() {
        ensureEngineKind()
        controller.setLetterMode(preferences.mode)
        applyTheme()
    }

    private fun playKeyFeedback() {
        val view = binding?.keyboardView ?: return
        if (preferences.soundEnabled) {
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }
        if (preferences.vibrateEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    /** 首次启动清理旧的英文种子词，并登记本机设备 */
    private fun bootstrapDatabase() {
        serviceScope.launch {
            runCatching {
                val database = BoardDatabase.get(this@BoardInputMethodService)
                dictionaryRepository.removeLegacySeededWords()
                // 历史遗留：把「大词库逐条进 Room」的旧数据搬成词库数据源（幂等）
                runCatching { dictionaryRepository.migrateLegacyImportedEntries() }
                    .getOrNull()
                    ?.let { report ->
                        Log.i(
                            TAG,
                            "词库迁移：${report.movedEntries} 行 → ${report.fileName} " +
                                "（${report.rowsBefore} → ${report.rowsAfter} 行）",
                        )
                    }
                // 旧版长期保留 .dict.yaml / 裸 .dict.txt → 迁移成 <sha256>.dict.yaml.gz（校验通过才删原文件）
                runCatching { dictionaryRepository.migrateToCompressedStore() }
                    .getOrNull()
                    ?.let { report ->
                        Log.i(
                            TAG,
                            "词库压缩迁移：迁移 ${report.migrated}，已压缩 ${report.alreadyCompressed}，" +
                                "失败 ${report.failed}，清理旧文件 ${report.removedLegacyFiles}",
                        )
                    }
                val merged = clipboardRepository?.dedupeExisting() ?: 0
                if (merged > 0) {
                    Log.i(TAG, "剪贴板历史去重：处理 $merged 条")
                }
                DeviceRepository(database.deviceDao()).registerCurrentDevice(this@BoardInputMethodService)
            }
        }
    }

    /** 屏蔽词由 Board 在候选层过滤，每次进入输入框时同步一次 */
    private fun refreshBlockedWords() {
        serviceScope.launch {
            val blocked = runCatching { dictionaryRepository.blockedWords() }.getOrDefault(emptySet())
            mainHandler.post { rimeEngine?.blockedWords = blocked }
        }
    }

    /**
     * 纯数字自定义编码：与 [refreshBlockedWords] 同一时机刷新一次，注入内存映射。
     *
     * 按键路径只查这份内存映射，不访问数据库；前缀 / 部分匹配不会命中。
     */
    private fun refreshNumericCustomPhrases() {
        serviceScope.launch {
            val phrases = runCatching { dictionaryRepository.numericCustomPhrases() }
                .getOrDefault(emptyMap())
            mainHandler.post { rimeEngine?.setNumericCustomPhrases(phrases) }
        }
    }

    /**
     * 调试 / 自测入口（只给开发者模式用）。
     *
     * 剪贴板与「上屏」的容量测试必须走 Board 自己真实的
     * `InputConnection`，而 adb 没有办法触发这条路径，所以留一个进程内的钩子：
     * 设置页在开发者模式下调用 [debugCommitText]。
     */
    fun debugCommitText(text: String): Boolean = controller.commitText(text)

    fun debugHasConnection(): Boolean = controller.inputConnection != null

    companion object {
        const val TAG = "BoardIME"
        const val CLIPBOARD_LIMIT = 60
        /** 写系统剪贴板时的标签（只在系统的剪贴板浮层里显示） */
        const val CLIPBOARD_LABEL = "Board"

        /** 外观 / 尺寸变更的合并窗口：60ms 内多次拖动只重绘一次 */
        private const val THEME_REFRESH_DELAY_MS = 60L

        /**
         * 当前活着的输入法服务。
         *
         * 只给**开发者模式的自测**用（adb 无法触发 Board 自己的上屏路径），
         * 业务代码不应依赖它。
         */
        @Volatile
        var active: BoardInputMethodService? = null
    }
}
