package com.board.ime.voice

/**
 * 语音识别引擎的抽象。
 *
 * 现有 `VoiceInputController`（系统 SpeechRecognizer）与
 * [BaiduRealtimeVoiceRecognitionProvider]（百度实时语音识别）都实现它，
 * 这样 **WebSocket / 音频逻辑不会写进 VoiceHeader 或面板**，
 * 以后要加别的 Provider 也不用动 UI。
 *
 * 约定（隐私基线）：
 * - 原始 PCM 只允许在 **内存** 里存在，绝不落盘/进日志；
 * - 识别结束立即丢弃缓冲；
 * - 只有用户点击语音（且已确认隐私说明、已授权麦克风）才会打开麦克风与网络连接。
 */
interface VoiceRecognitionEngine {

    /** 界面上展示的服务名，例如「百度实时语音识别」 */
    val displayName: String

    /** 凭据等前置条件是否就绪（未就绪时 UI 提示去设置，而不是偷偷降级到别的服务） */
    fun isConfigured(): Boolean

    var onEvent: ((VoiceRecognitionEvent) -> Unit)?

    /** 开始这一轮识别（打开麦克风 + 建立连接） */
    fun start(language: VoiceLanguage)

    /** 正常结束：不再收音，等最后一句结果 */
    fun stop()

    /** 立即取消：不等结果，尽快关闭连接与音频 */
    fun cancel()

    /** 释放全部资源（不保留任何后台任务） */
    fun release()
}

/** 识别语言 → 百度 dev_pid（见官方「开放平台模型」表） */
enum class VoiceLanguage(val devPid: Int, val label: String) {
    CHINESE(15372, "中文普通话"),
    ENGLISH(17372, "英文"),
    ;

    companion object {
        fun forChineseMode(isChineseMode: Boolean): VoiceLanguage =
            if (isChineseMode) CHINESE else ENGLISH
    }
}

sealed interface VoiceRecognitionEvent {
    /** 一句话的临时结果（partial）：**只更新 UI，绝不上屏** */
    data class Partial(val text: String) : VoiceRecognitionEvent

    /** 已确定的整句结果（可多次到达，调用方按累计文本处理） */
    data class Final(val text: String) : VoiceRecognitionEvent

    data class Failed(val failure: VoiceFailure) : VoiceRecognitionEvent
}

/**
 * 失败原因。**只带中文人话**：不带服务端原始 JSON、不带音频、不带凭据。
 */
sealed class VoiceFailure(val message: String) {
    data object NotConfigured :
        VoiceFailure("请先配置语音识别服务（设置 → 语音）")

    data object Permission :
        VoiceFailure("需要麦克风权限才能使用语音输入")

    data object Network :
        VoiceFailure("网络不可用，请检查网络后重试")

    data object Auth :
        VoiceFailure("语音服务鉴权失败，请检查语音 AppID / API Key")

    data object Quota :
        VoiceFailure("语音服务额度不足或已停用")

    data object Timeout :
        VoiceFailure("语音识别超时，请重试")

    data object Disconnected :
        VoiceFailure("语音识别连接中断，请重试")

    data object Audio :
        VoiceFailure("麦克风不可用或录音失败")

    data object NoSpeech :
        VoiceFailure("没有听清，请再试一次")

    /** 服务端错误码：只回显码，不猜含义、不显示原始 JSON */
    data class Service(val code: String?) :
        VoiceFailure(if (code.isNullOrBlank()) "语音服务返回错误" else "语音服务返回错误（$code）")
}
