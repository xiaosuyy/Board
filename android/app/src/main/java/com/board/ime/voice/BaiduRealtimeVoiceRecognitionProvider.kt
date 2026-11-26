package com.board.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * 百度实时语音识别（WebSocket）。
 *
 * 严格按官方《实时语音识别-websocket API》实现（2026-07-23 版）：
 * - 连接：`wss://vop.baidu.com/realtime_asr?sn=<随机>`（**只允许 WSS**）
 * - 开始帧（Text/JSON）：`{"type":"START","data":{appid, appkey, dev_pid, cuid, format:"pcm", sample:16000}}`
 *   —— 注意 `appkey` 是控制台上的 **API Key**；
 * - 音频帧（Binary）：PCM 16k / 16bit / 单声道，每帧建议 160ms = 5120 字节；
 *   **服务端 5s 收不到音频就断开**，所以边录边发、不停顿；
 * - 结束帧 `{"type":"FINISH"}`、取消帧 `{"type":"CANCEL"}`；
 * - 结果：`MID_TEXT`（临时）/ `FIN_TEXT`（整句最终，可累计）；错误在 `err_no`。
 *
 * 隐私：
 * - **PCM 只存在于内存**：麦克风 → 内存帧 → WebSocket，不写文件、不做 wav/pcm 缓存、不进日志；
 * - 停止/取消/释放都会立即丢掉音频缓冲、关连接、释放 AudioRecord；
 * - 关键字（识别文本）只在内存里短暂存在，不落任何存储。
 *
 * 日志只允许：start / websocket connected / final chars=N / error code / elapsedMs。
 * **绝不打印 PCM、partial 文本、final 文本、凭据、Authorization。**
 */
class BaiduRealtimeVoiceRecognitionProvider(
    private val credentialsProvider: () -> VoiceCredentials,
    private val client: OkHttpClient = defaultClient(),
) : VoiceRecognitionEngine {

    override val displayName: String = "百度实时语音识别"

    override var onEvent: ((VoiceRecognitionEvent) -> Unit)? = null

    override var onVolume: ((Float) -> Unit)? = null

    private val lock = Any()
    private var webSocket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null

    @Volatile
    private var sessionActive = false

    @Volatile
    private var finishing = false

    /** 文本组装：partial 覆盖、段级 final 只追加一次（见 VoiceTranscriptAssembler） */
    private val transcript = VoiceTranscriptAssembler()
    private var startedAt = 0L
    private var segmentIndex = 0

    /** 用户点停止后的收尾兜底：服务端若没主动断开，也要在合理时间内结束会话 */
    private val endHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun isConfigured(): Boolean = credentialsProvider().isComplete

    @SuppressLint("MissingPermission")
    override fun start(language: VoiceLanguage) {
        val credentials = credentialsProvider()
        if (!credentials.isComplete) {
            emit(VoiceRecognitionEvent.Failed(VoiceFailure.NotConfigured))
            return
        }
        // 先确保没有上一轮残留（麦克风 / socket）
        teardown(closeSocket = true)

        synchronized(lock) {
            sessionActive = true
            finishing = false
            segmentIndex = 0
            transcript.reset()
        }
        startedAt = System.currentTimeMillis()

        val sn = UUID.randomUUID().toString()
        val request = Request.Builder().url("$WSS_ENDPOINT?sn=$sn").build()
        val socket = client.newWebSocket(request, listener(language, credentials))
        synchronized(lock) { webSocket = socket }
        Log.i(TAG, "voice start provider=$displayName devPid=${language.devPid}")
    }

    override fun stop() {
        val wasActive = synchronized(lock) {
            if (!sessionActive || finishing) false else {
                finishing = true
                true
            }
        }
        // 只允许发一次停止：重复点不会重复发 FINISH
        if (!wasActive) return
        // 先把最后不足一帧的音频补发出去，再发 FINISH（顺序不能反）
        stopRecording()
        runCatching { webSocket?.send(FINISH_FRAME) }
        // 告诉 UI：正在收尾（麦克风保持显示，不出现对勾）
        emit(VoiceRecognitionEvent.Stopping(transcript.displayText))
        Log.i(TAG, "voice user stop, waiting for final")
        // 兜底：服务端若没主动断开，也在合理时间内收尾
        endHandler.removeCallbacksAndMessages(null)
        endHandler.postDelayed({ finishSession() }, ENDING_GRACE_MS)
    }

    /**
     * 会话真正结束（用户已点停止，且服务端收尾完成/超时）：
     * 这时才有资格进入 RESULT。
     */
    private fun finishSession() {
        val active = synchronized(lock) {
            val was = sessionActive
            sessionActive = false
            finishing = false
            was
        }
        if (!active) return
        endHandler.removeCallbacksAndMessages(null)
        val text = transcript.settledText.ifEmpty { transcript.displayText }
        teardown(closeSocket = true)
        if (text.isNotEmpty()) {
            Log.i(TAG, "voice session end chars=${text.length} segment=$segmentIndex")
            emit(VoiceRecognitionEvent.Final(text))
        } else {
            emit(VoiceRecognitionEvent.Failed(VoiceFailure.NoSpeech))
        }
    }

    override fun cancel() {
        synchronized(lock) {
            sessionActive = false
            finishing = false
        }
        runCatching { webSocket?.send(CANCEL_FRAME) }
        runCatching { webSocket?.close(NORMAL_CLOSE, "cancel") }
        teardown(closeSocket = true)
    }

    override fun release() {
        onEvent = null
        synchronized(lock) {
            sessionActive = false
            finishing = false
        }
        teardown(closeSocket = true)
    }

    // ---------------------------------------------------------------- 内部

    private fun listener(language: VoiceLanguage, credentials: VoiceCredentials) =
        object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "voice websocket connected")
                webSocket.send(startFrame(language, credentials))
                // 连上之后才开始收音：不会在没连上时白录一段
                if (!startRecording(webSocket)) {
                    runCatching { webSocket.close(NORMAL_CLOSE, "audio failed") }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onSocketGone()
                // 用户已点停止 → 这就是收尾完成；否则是异常断连
                if (synchronized(lock) { finishing }) {
                    finishSession()
                } else if (synchronized(lock) { sessionActive }) {
                    Log.w(TAG, "voice error kind=closed")
                    synchronized(lock) { sessionActive = false }
                    stopRecording()
                    emit(VoiceRecognitionEvent.Failed(VoiceFailure.Disconnected))
                }
            }

            override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
                // 注意：不打印音频/凭据；只记异常类型
                Log.w(TAG, "voice error kind=${throwable.javaClass.simpleName}")
                val wasFinishing = synchronized(lock) { finishing }
                onSocketGone()
                if (wasFinishing) {
                    // 已收到用户停止：尽量把已有文本交出去，而不是报错
                    finishSession()
                } else if (synchronized(lock) { sessionActive }) {
                    synchronized(lock) { sessionActive = false }
                    stopRecording()
                    emit(VoiceRecognitionEvent.Failed(VoiceFailure.Disconnected))
                }
            }
        }

    private fun startFrame(language: VoiceLanguage, credentials: VoiceCredentials): String {
        val id = credentials.appId.trim()
        val data = JSONObject()
            .put("appid", id.toLongOrNull() ?: id)
            .put("appkey", credentials.apiKey.trim())
            .put("dev_pid", language.devPid)
            // cuid 只用于服务端 UV 统计：这里用**随机会话 id**，不上报任何设备标识
            .put("cuid", UUID.randomUUID().toString().take(32))
            .put("format", "pcm")
            .put("sample", SAMPLE_RATE)
        return JSONObject().put("type", "START").put("data", data).toString()
    }

    private fun handleMessage(raw: String) {
        if (!synchronized(lock) { sessionActive }) return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val type = json.optString("type")
        when (type) {
            "HEARTBEAT" -> Unit // 服务端心跳，忽略

            "MID_TEXT", "FIN_TEXT" -> {
                val errNo = json.optInt("err_no", 0)
                if (errNo != 0) {
                    // 只记错误码，不打原始 JSON、不打音频
                    Log.w(TAG, "voice error code=$errNo")
                    if (errNo == ERR_AUTH) {
                        emit(VoiceRecognitionEvent.Failed(VoiceFailure.Auth))
                    } else {
                        emit(VoiceRecognitionEvent.Failed(VoiceFailure.Service(errNo.toString())))
                    }
                    return
                }
                val result = json.optString("result")
                if (result.isEmpty()) return
                if (type == "MID_TEXT") {
                    // 官方语义：MID_TEXT 是「这一句的累计文本」→ **覆盖**显示，不能 append
                    transcript.onPartial(result)
                    emit(VoiceRecognitionEvent.Partial(transcript.displayText))
                } else {
                    // 官方语义：FIN_TEXT 是「一句话的最终结果」，
                    // 它**不代表用户结束语音** —— 只把这一段定下来，会话继续保持 LISTENING。
                    val key = VoiceTranscriptAssembler.segmentKeyOf(
                        json.optString("sn").takeIf { it.isNotBlank() },
                        json.optLong("start_time", 0L),
                    )
                    transcript.onSegmentFinal(result, key)
                    segmentIndex++
                    // 只记类型/长度/段号，绝不打印识别正文
                    Log.i(
                        TAG,
                        "voice message type=FINAL chars=${result.length} segment=$segmentIndex",
                    )
                    emit(VoiceRecognitionEvent.Partial(transcript.displayText))
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording(webSocket: WebSocket): Boolean {
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        }.getOrDefault(-1)
        if (minBuffer <= 0) {
            failAudio()
            return false
        }
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                max(minBuffer, FRAME_BYTES * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            failAudio()
            return false
        }
        val started = runCatching { record.startRecording() }.isSuccess
        if (!started) {
            runCatching { record.release() }
            failAudio()
            return false
        }
        synchronized(lock) { recorder = record }

        val thread = Thread({
            // 音频帧缓冲：**只在内存**，循环复用同一块 ByteArray
            val buffer = ByteArray(FRAME_BYTES)
            var filled = 0
            while (synchronized(lock) { sessionActive }) {
                val read = runCatching {
                    record.read(buffer, filled, FRAME_BYTES - filled)
                }.getOrDefault(-1)
                if (read <= 0) break
                filled += read
                if (filled < FRAME_BYTES) continue
                if (!webSocket.send(buffer.toByteString())) break
                filled = 0
            }
            // 退出前把最后不足一帧的数据补发（结束时服务端才能收到完整尾音）
            if (filled > 0) {
                runCatching { webSocket.send(buffer.copyOf(filled).toByteString()) }
            }
        }, "board-voice-audio")
        synchronized(lock) { recordingThread = thread }
        thread.start()
        return true
    }

    private fun stopRecording() {
        val record = synchronized(lock) { recorder }
        synchronized(lock) { recorder = null }
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        val thread = synchronized(lock) { recordingThread }
        synchronized(lock) { recordingThread = null }
        if (thread != null) runCatching { thread.join(AUDIO_JOIN_MS) }
    }

    private fun failAudio() {
        Log.w(TAG, "voice error kind=audio")
        emit(VoiceRecognitionEvent.Failed(VoiceFailure.Audio))
        synchronized(lock) { sessionActive = false }
        teardown(closeSocket = true)
    }

    private fun onSocketGone() {
        synchronized(lock) { webSocket = null }
    }

    private fun teardown(closeSocket: Boolean) {
        stopRecording()
        if (closeSocket) {
            val socket = synchronized(lock) {
                val current = webSocket
                webSocket = null
                current
            }
            runCatching { socket?.close(NORMAL_CLOSE, null) }
        }
    }

    private fun emit(event: VoiceRecognitionEvent) {
        onEvent?.invoke(event)
    }

    private companion object {
        const val TAG = "BoardVoice"
        const val WSS_ENDPOINT = "wss://vop.baidu.com/realtime_asr"
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 官方建议 160ms 一帧：160 * 16000 * 2 / 1000 = 5120 字节 */
        const val FRAME_BYTES = 5120

        const val FINISH_FRAME = "{\"type\":\"FINISH\"}"
        const val CANCEL_FRAME = "{\"type\":\"CANCEL\"}"
        const val NORMAL_CLOSE = 1000

        /** 文档示例里明确标注的错误码：-3004 = asr authentication failed */
        const val ERR_AUTH = -3004

        /** 用户点停止后，最多再等这么久就收尾（服务端通常会更快关闭连接） */
        const val ENDING_GRACE_MS = 3000L

        const val AUDIO_JOIN_MS = 500L

        /**
         * 连接：只走 WSS；超时必须有上限（不放任后台长时间等待）。
         * 服务端每 5s 下发心跳，这里 20s 发一次 OkHttp ping 保活即可。
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/** 便于日志与测试的显式类型（不参与序列化） */
internal typealias VoiceFrame = ByteString
