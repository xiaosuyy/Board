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
    SUCCESS,
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
class VoiceInputController(private val context: Context) {

    /** (状态, 展示文本)：LISTENING 时是 partial 预览，SUCCESS 时是最终结果，ERROR 时是提示语 */
    var onState: ((VoiceState, String) -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var released = false
    private var languageRetried = false
    private var languageTag: String = ""

    fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    /** 开始识别；重复点击不会创建第二个会话 */
    fun start(languageTag: String, allowLanguageRetry: Boolean = true) {
        if (released) return
        if (listening) return
        if (!isAvailable()) {
            emit(VoiceState.ERROR, "此设备没有可用的语音识别服务")
            return
        }
        val engine = ensureRecognizer() ?: return
        this.languageTag = languageTag
        languageRetried = !allowLanguageRetry
        val intent = buildIntent(languageTag)
        listening = true
        emit(VoiceState.LISTENING, "")
        runCatching { engine.startListening(intent) }.onFailure {
            listening = false
            emit(VoiceState.ERROR, "无法启动语音识别")
        }
    }

    /** 用户点“结束”：停止收音，已返回的结果照常回调 */
    fun stop() {
        if (!listening) return
        runCatching { recognizer?.stopListening() }
    }

    /** 取消（丢弃结果） */
    fun cancel() {
        listening = false
        runCatching { recognizer?.cancel() }
    }

    /** onDestroy 调用：先停/取消，再 destroy，最后置空 */
    fun release() {
        released = true
        if (listening) {
            runCatching { recognizer?.cancel() }
        }
        listening = false
        runCatching { recognizer?.destroy() }
        recognizer = null
        onState = null
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
                emit(VoiceState.SUCCESS, text)
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
class VoiceInputSession(private val context: Context, private val host: Host) {

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
    }

    private val controller = VoiceInputController(context)
    private var state: VoiceState = VoiceState.IDLE

    init {
        controller.onState = { newState, text -> handleState(newState, text) }
    }

    val currentState: VoiceState
        get() = state

    /** 语音工具的唯一入口：点一次开始，识别中再点一次结束 */
    fun toggle() {
        when (state) {
            VoiceState.LISTENING, VoiceState.PROCESSING -> controller.stop()
            else -> start()
        }
    }

    /** 用户点返回：取消当前识别 */
    fun cancel() {
        controller.cancel()
        state = VoiceState.IDLE
        host.hideVoicePanel()
    }

    fun release() {
        state = VoiceState.IDLE
        controller.release()
    }

    private fun start() {
        if (!controller.isAvailable()) {
            // 面板**留在原地**并把原因写清楚：只弹一条瞬时 Toast 用户会以为“点了没反应”
            state = VoiceState.IDLE
            host.showVoicePanel()
            host.updateVoicePanel(VoiceState.ERROR, "此设备没有可用的语音识别服务")
            host.notify("此设备没有可用的语音识别服务")
            return
        }
        if (!hasMicPermission()) {
            state = VoiceState.IDLE
            host.showVoicePanel()
            host.updateVoicePanel(VoiceState.ERROR, "需要麦克风权限才能使用语音输入")
            host.notify("需要麦克风权限才能使用语音输入")
            host.requestMicPermission()
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

            VoiceState.SUCCESS -> {
                host.updateVoicePanel(newState, text)
                host.discardComposition()
                // 只在最终结果时上屏一次（partial 绝不上屏），插入点由 InputConnection 决定
                host.inputConnection?.commitText(text, CURSOR_AT_END)
                state = VoiceState.IDLE
                host.hideVoicePanel()
            }

            VoiceState.ERROR -> {
                host.notify(text)
                state = VoiceState.IDLE
                host.hideVoicePanel()
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

        /** 仅用于老版本兼容判断，避免直接引用可能不存在的常量 */
        @Suppress("unused")
        private val API_33 = Build.VERSION_CODES.TIRAMISU
    }
}
