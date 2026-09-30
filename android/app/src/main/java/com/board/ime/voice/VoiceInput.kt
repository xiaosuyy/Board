package com.board.ime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.inputmethod.InputConnection

/**
 * 语音输入状态机。
 *
 * ```
 * IDLE → 点击语音 → LISTENING → (onPartialResults 只更新预览)
 *                             → onResults → SUCCESS → 上屏 → IDLE
 *                             → onError   → ERROR   → 提示 → IDLE
 * ```
 * 任何一次识别结束（成功/出错/取消）都必须回到 [IDLE]，避免旧状态卡住下一次识别。
 */
enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,

    /** 已拿到最终文本：面板直接显示它，用户点击才上屏 */
    RESULT,

    ERROR,
}

/**
 * 只调用**设备自带的** SpeechRecognizer（第一版不引入任何本地模型 / 云 SDK）。
 *
 * 隐私与生命周期约束：
 * - 不保存录音、不写缓存、不上传、**不把识别文本写进日志**
 * - 同一时刻最多一个识别会话（[start] 内部有保护）
 * - [release] 之后对象作废；宿主必须在 onDestroy 调用
 */
class VoiceInputController(
    private val context: Context,
    /**
     * 云端实时语音识别引擎（默认百度）。
     *
     * **已配置凭据时优先使用它**；未配置时才回退到系统 SpeechRecognizer。
     * 这样「语音」工具在目标环境（没有 RecognitionService 的机器）上依然可用。
     */
    private val cloudEngine: VoiceRecognitionEngine? = null,
) {

    /** (状态, 展示文本)：LISTENING 时是 partial 预览，SUCCESS 时是最终结果，ERROR 时是提示语 */
    var onState: ((VoiceState, String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var released = false
    private var languageRetried = false
    private var languageTag: String = ""

    /** 云端引擎的回调在工作线程上，统一 post 回主线程 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 百度已配置就一定能用；否则才看系统有没有识别服务 */
    fun isAvailable(): Boolean = cloud() != null || runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    private fun cloud(): VoiceRecognitionEngine? = cloudEngine?.takeIf { it.isConfigured() }

    /** 开始识别；重复点击不会创建第二个会话 */
    fun start(languageTag: String, allowLanguageRetry: Boolean = true) {
        if (released) return
        if (listening) return

        // 首选云端实时识别（百度）：WebSocket 边录边发
        val engine = cloud()
        if (engine != null) {
            listening = true
            engine.onEvent = { event -> onEngineEvent(event) }
            emit(VoiceState.LISTENING, "")
            runCatching {
                engine.start(VoiceLanguage.forChineseMode(languageTag.startsWith("zh")))
            }.onFailure {
                listening = false
                emit(VoiceState.ERROR, "无法启动语音识别")
            }
            return
        }

        if (!isAvailable()) {
            emit(VoiceState.ERROR, "此设备没有可用的语音识别服务")
            return
        }
        val systemEngine = ensureRecognizer() ?: return
        this.languageTag = languageTag
        languageRetried = !allowLanguageRetry
        val intent = buildIntent(languageTag)
        listening = true
        emit(VoiceState.LISTENING, "")
        runCatching { systemEngine.startListening(intent) }.onFailure {
            listening = false
            emit(VoiceState.ERROR, "无法启动语音识别")
        }
    }

    /** 用户点“结束”：停止收音，已返回的结果照常回调 */
    fun stop() {
        if (!listening) return
        runCatching { cloudEngine?.stop() }
        runCatching { recognizer?.stopListening() }
    }

    /** 取消（丢弃结果） */
    fun cancel() {
        listening = false
        runCatching { cloudEngine?.cancel() }
        runCatching { recognizer?.cancel() }
    }

    /** onDestroy 调用：先停/取消，再 destroy，最后置空 */
    fun release() {
        released = true
        if (listening) {
            runCatching { recognizer?.cancel() }
        }
        listening = false
        runCatching { cloudEngine?.cancel() }
        runCatching { cloudEngine?.release() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        onState = null
    }

    /**
     * 云端引擎事件 → 现有 UI 状态。
     *
     * partial 只更新面板；final 变成 RESULT，等用户点文本才上屏 —— 与 Phase 2.2 完全一致。
     */
    private fun onEngineEvent(event: VoiceRecognitionEvent) {
        // 云端引擎的回调发生在 OkHttp / 录音工作线程上：**必须切回主线程**再动 UI
        mainHandler.post {
            when (event) {
                is VoiceRecognitionEvent.Partial -> emit(VoiceState.LISTENING, event.text)

                is VoiceRecognitionEvent.Final -> emit(VoiceState.RESULT, event.text)

                is VoiceRecognitionEvent.Failed -> {
                    listening = false
                    emit(VoiceState.ERROR, event.failure.message)
                }
            }
        }
    }

    private fun buildIntent(tag: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            if (tag.isNotEmpty()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun ensureRecognizer(): SpeechRecognizer? {
        recognizer?.let { return it }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null
        return runCatching {
            SpeechRecognizer.createSpeechRecognizer(context.applicationContext).also {
                it.setRecognitionListener(listener)
                recognizer = it
            }
        }.getOrNull()
    }

    private fun emit(state: VoiceState, text: String) {
        onState?.invoke(state, text)
    }

    private val listener = object : RecognitionListener {

        /** partial 只用于预览，绝不在这里上屏 */
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) emit(VoiceState.LISTENING, text)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (text.isEmpty()) {
                emit(VoiceState.ERROR, "没有听清，请再试一次")
            } else {
                emit(VoiceState.RESULT, text)
            }
        }

        override fun onError(error: Int) {
            listening = false
            // 语言不被支持时，用设备默认语言重试一次（不硬编码“永远支持 zh-CN”）
            if (!languageRetried && isLanguageError(error)) {
                languageRetried = true
                listening = false
                runCatching { recognizer?.cancel() }
                start(languageTag = "", allowLanguageRetry = false)
                return
            }
            emit(VoiceState.ERROR, messageFor(error))
        }

        override fun onEndOfSpeech() {
            if (listening) emit(VoiceState.PROCESSING, "")
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun isLanguageError(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> true
        else -> error == ERROR_LANGUAGE_NOT_SUPPORTED
    }

    /** 把内部错误号转成简短中文提示，绝不把 ERROR_x 暴露给用户 */
    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> "没有听清，请再试一次"

        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER,
        -> "网络连接异常，请稍后再试"

        SpeechRecognizer.ERROR_AUDIO -> "麦克风不可用"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限才能使用语音输入"

        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT,
        -> "语音识别服务忙，请稍后再试"

        else -> "语音识别失败，请再试一次"
    }

    private companion object {
        /** API 33 才公开的常量，老系统上等价于旧的行为 */
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 14
    }
}

/**
 * 把「语音工具」的整条流程收在一个地方，宿主（输入法服务）只需要：
 * 创建它、把界面回调接上、[toggle]、退出时 [release]。
 *
 * 这样服务侧几乎不增加逻辑，也不会碰到 Rime / Clipboard / 存储等无关模块。
 */
class VoiceInputSession(
    private val context: Context,
    private val host: Host,
    /** 云端实时识别引擎（默认百度）；未配置时内部回退到系统 SpeechRecognizer */
    cloudEngine: VoiceRecognitionEngine? = null,
) {

    interface Host {
        /** 当前是否中文输入（决定 zh-CN / en-US） */
        val isChineseMode: Boolean
        val inputConnection: InputConnection?
        /** 丢掉当前 Rime 组词（不把拼音上屏），只用引擎公开 API */
        fun discardComposition()
        fun showVoicePanel()
        fun hideVoicePanel()
        fun updateVoicePanel(state: VoiceState, text: String)
        /** 引导用户去授权（打开设置页请求权限 / 系统设置） */
        fun requestMicPermission()
        fun notify(message: String)

        /** 是否已确认过语音隐私说明（只存一个布尔，绝不存语音内容/识别文本） */
        val voicePrivacyAcknowledged: Boolean

        /** 首次使用时展示隐私说明；用户点「继续」后由实现方回调 [onPrivacyAccepted] */
        fun requestVoicePrivacyConsent()

        /** 展示完整隐私说明（语音面板底部说明的入口） */
        fun showVoicePrivacyInfo()
    }

    private val controller = VoiceInputController(context, cloudEngine)
    private var state: VoiceState = VoiceState.IDLE

    /** 最终识别文本；只有用户点击才提交，返回则丢弃 */
    private var pendingText = ""

    init {
        controller.onState = { newState, text -> handleState(newState, text) }
    }

    val currentState: VoiceState
        get() = state

    /** 语音工具的唯一入口：点一次开始；识别中再点一次 = 结束收音 */
    fun toggle() {
        when (state) {
            VoiceState.LISTENING, VoiceState.PROCESSING -> controller.stop()
            VoiceState.RESULT -> commitPending()
            else -> start()
        }
    }

    /** 点击面板中间的文字：识别中 = 结束收音；有结果 = 提交上屏 */
    fun onPrimary() {
        when (state) {
            VoiceState.LISTENING, VoiceState.PROCESSING -> controller.stop()
            VoiceState.RESULT -> commitPending()
            else -> Unit
        }
    }

    /**
     * 上屏**一次**：先丢弃未完成的 Rime 组词（避免 "zhongguo你好"），
     * 再按当前光标位置 commitText；提交后清空并退出语音状态（重复点击不会重复输入）。
     */
    private fun commitPending() {
        val text = pendingText
        if (text.isEmpty()) return
        pendingText = ""
        state = VoiceState.IDLE
        host.discardComposition()
        host.inputConnection?.commitText(text, CURSOR_AT_END)
        host.hideVoicePanel()
    }

    /** 用户点返回：不提交任何文字，直接退出 */
    fun cancel() {
        pendingText = ""
        controller.cancel()
        state = VoiceState.IDLE
        host.hideVoicePanel()
    }

    fun release() {
        state = VoiceState.IDLE
        controller.release()
    }

    /**
     * 用户已确认隐私说明：**这时才真正开始识别**（此前不碰麦克风）。
     */
    fun onPrivacyAccepted() {
        start()
    }

    private fun start() {
        if (!controller.isAvailable()) {
            // 面板**留在原地**并把原因写清楚：只弹一条瞬时 Toast 用户会以为“点了没反应”
            // （真机上 Board 的 Toast 还可能被系统抑制，所以面板才是唯一可靠的提示面）
            state = VoiceState.IDLE
            host.showVoicePanel()
            host.updateVoicePanel(VoiceState.ERROR, UNAVAILABLE_MESSAGE)
            return
        }
        if (!hasMicPermission()) {
            // 输入法自己弹不了运行时权限：交给无界面的 MicPermissionActivity 弹系统授权框，
            // 用户允许后再点一次语音即可（不能只显示文案、什么都不做）。
            host.requestMicPermission()
            state = VoiceState.IDLE
            host.showVoicePanel()
            host.updateVoicePanel(VoiceState.ERROR, PERMISSION_MESSAGE)
            return
        }
        // 真正启动录音之前，先让用户明确知道这是系统语音识别（可能联网）——
        // 未经确认绝不调用 startListening()。
        if (!host.voicePrivacyAcknowledged) {
            host.requestVoicePrivacyConsent()
            return
        }
        state = VoiceState.LISTENING
        host.showVoicePanel()
        host.updateVoicePanel(VoiceState.LISTENING, "")
        controller.start(if (host.isChineseMode) LANGUAGE_ZH else LANGUAGE_EN)
    }

    private fun handleState(newState: VoiceState, text: String) {
        state = newState
        when (newState) {
            VoiceState.LISTENING, VoiceState.PROCESSING -> host.updateVoicePanel(newState, text)

            VoiceState.RESULT -> {
                // 只显示，不上屏：等用户点击文本才提交（partial 永不提交）
                pendingText = text
                host.updateVoicePanel(newState, text)
            }

            VoiceState.ERROR -> {
                // 失败也留在面板里：只显示原因 + 左侧返回，不依赖 Toast、不自动关闭
                host.showVoicePanel()
                host.updateVoicePanel(VoiceState.ERROR, text)
            }

            VoiceState.IDLE -> {
                state = VoiceState.IDLE
                host.hideVoicePanel()
            }
        }
    }

    private fun hasMicPermission(): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    companion object {
        const val LANGUAGE_ZH = "zh-CN"
        const val LANGUAGE_EN = "en-US"
        private const val CURSOR_AT_END = 1

        /** 无服务 / 无权限：面板上两行说明（不依赖 Toast、不依赖通知权限） */
        const val UNAVAILABLE_MESSAGE =
            "没有可用的语音识别服务\n请在「设置 → 语音」配置百度的 AppID / API Key"
        const val PERMISSION_MESSAGE =
            "需要麦克风权限才能使用语音输入\n请在系统弹窗中允许，然后再次点击语音按钮"

        /** 仅用于老版本兼容判断，避免直接引用可能不存在的常量 */
        @Suppress("unused")
        private val API_33 = Build.VERSION_CODES.TIRAMISU
    }
}
