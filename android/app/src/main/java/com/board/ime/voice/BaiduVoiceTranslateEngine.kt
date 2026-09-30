package com.board.ime.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import com.board.ime.translation.TranslationCredentials
import com.board.ime.translation.translateProviderMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

/**
 * 语音翻译（百度**翻译开放平台** `/api/trans/v2/voicetrans`）。
 *
 * 这条路径**复用用户已有的翻译凭据**（APPID + 密钥），不需要另开智能云账号。
 *
 * 官方契约（语音翻译 API 接入文档）：
 * - `POST https://fanyi-api.baidu.com/api/trans/v2/voicetrans`，`Content-Type: application/json`
 * - 头：`X-Appid` / `X-Timestamp`(10 位) / `X-Sign`
 *   `X-Sign = base64(hmac_sha256(key=密钥, msg = appid + timestamp + voiceBase64))`
 * - body：`from` / `to` / `format`(pcm|wav|amr|m4a) / `voice`(音频 base64)
 * - 限制：**必须是完整录音，≤60 秒**；采样率 16000/8000、16bit、单声道；base64 前 ≤4MB；
 *   非中文语种只支持 pcm
 * - 返回：`{code, msg, data:{source(识别原文), target(译文), target_tts}}`，`code==0` 才算成功
 *
 * 因此它**没有 partial**（要等整段上传完），面板在识别期间显示"处理中"。
 *
 * 隐私：PCM 只存在于**内存** `ByteArrayOutputStream`，上传后立即丢弃；
 * 不写文件、不做 wav/pcm 缓存、不打印音频/原文/译文/凭据。
 */
class BaiduVoiceTranslateEngine(
    private val credentialsProvider: () -> TranslationCredentials,
    private val client: OkHttpClient = defaultClient(),
) : VoiceRecognitionEngine {

    override val displayName: String = "百度翻译 · 语音翻译"

    override var onEvent: ((VoiceRecognitionEvent) -> Unit)? = null

    private val lock = Any()
    private var recorder: AudioRecord? = null
    private var recordingThread: Thread? = null

    /** 音频**只在这里**：内存缓冲，上传后立刻丢弃 */
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var recording = false

    private var fromCode = "zh"
    private var toCode = "en"
    private var startedAt = 0L

    override fun isConfigured(): Boolean = credentialsProvider().isComplete

    @SuppressLint("MissingPermission")
    override fun start(language: VoiceLanguage) {
        if (!isConfigured()) {
            emit(VoiceRecognitionEvent.Failed(VoiceFailure.NotConfigured))
            return
        }
        teardown()
        // 说哪种语言 → 译到另一种（中文模式：zh→en；英文模式：en→zh）
        fromCode = if (language == VoiceLanguage.CHINESE) "zh" else "en"
        toCode = if (language == VoiceLanguage.CHINESE) "en" else "zh"
        synchronized(lock) { pcm.reset() }
        startedAt = System.currentTimeMillis()
        if (!startRecording()) return
        Log.i(TAG, "voice start provider=$displayName")
        // 这条路径没有 partial（要整段上传），先给一个占位提示，避免用户以为没反应
        emit(VoiceRecognitionEvent.Partial("正在录音…再次点击语音结束"))
    }

    /** 结束录音 → 上传整段音频 → 得到译文 */
    override fun stop() {
        if (!recording) return
        recording = false
        stopRecording()
        val audio = synchronized(lock) {
            val bytes = pcm.toByteArray()
            pcm.reset()
            bytes
        }
        if (audio.isEmpty()) {
            emit(VoiceRecognitionEvent.Failed(VoiceFailure.NoSpeech))
            return
        }
        val credentials = credentialsProvider()
        Thread({ upload(credentials, audio) }, "board-voice-upload").start()
    }

    override fun cancel() {
        recording = false
        stopRecording()
        synchronized(lock) { pcm.reset() }
    }

    override fun release() {
        onEvent = null
        cancel()
    }

    // ---------------------------------------------------------------- 录音

    @SuppressLint("MissingPermission")
    private fun startRecording(): Boolean {
        val minBuffer = runCatching {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        }.getOrDefault(-1)
        if (minBuffer <= 0) {
            emit(VoiceRecognitionEvent.Failed(VoiceFailure.Audio))
            return false
        }
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                max(minBuffer, CHUNK_BYTES * 4),
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record?.release() }
            emit(VoiceRecognitionEvent.Failed(VoiceFailure.Audio))
            return false
        }
        if (!runCatching { record.startRecording() }.isSuccess) {
            runCatching { record.release() }
            emit(VoiceRecognitionEvent.Failed(VoiceFailure.Audio))
            return false
        }
        synchronized(lock) { recorder = record }
        recording = true
        val maxBytes = MAX_SECONDS * SAMPLE_RATE * 2
        val thread = Thread({
            val buffer = ByteArray(CHUNK_BYTES)
            while (recording) {
                val read = runCatching { record.read(buffer, 0, buffer.size) }.getOrDefault(-1)
                if (read <= 0) break
                synchronized(lock) {
                    if (pcm.size() + read > maxBytes) {
                        // 官方限制：完整录音 ≤60 秒，到点自动收尾
                        pcm.write(buffer, 0, max(0, maxBytes - pcm.size()))
                        recording = false
                        break
                    }
                    pcm.write(buffer, 0, read)
                }
            }
        }, "board-voice-record")
        synchronized(lock) { recordingThread = thread }
        thread.start()
        return true
    }

    private fun stopRecording() {
        val record = synchronized(lock) {
            val current = recorder
            recorder = null
            current
        }
        if (record != null) {
            runCatching { record.stop() }
            runCatching { record.release() }
        }
        val thread = synchronized(lock) {
            val current = recordingThread
            recordingThread = null
            current
        }
        if (thread != null) runCatching { thread.join(AUDIO_JOIN_MS) }
    }

    private fun teardown() = stopRecording()

    // ---------------------------------------------------------------- 上传

    private fun upload(credentials: TranslationCredentials, audio: ByteArray) {
        val started = System.currentTimeMillis()
        val outcome: VoiceRecognitionEvent = try {
            val voice = Base64.encodeToString(audio, Base64.NO_WRAP)
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val sign = sign(credentials.appId, timestamp, voice, credentials.secret)
            val body = JSONObject()
                .put("from", fromCode)
                .put("to", toCode)
                .put("format", "pcm")
                .put("voice", voice)
                .toString()
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Content-Type", "application/json")
                .header("X-Appid", credentials.appId)
                .header("X-Timestamp", timestamp)
                .header("X-Sign", sign)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "voice error http=${response.code}")
                    VoiceRecognitionEvent.Failed(VoiceFailure.Service("HTTP ${response.code}"))
                } else {
                    val json = runCatching { JSONObject(raw) }.getOrNull()
                    if (json == null) {
                        VoiceRecognitionEvent.Failed(VoiceFailure.Service(null))
                    } else {
                        val code = json.optInt("code", -1)
                        if (code != 0) {
                            // 只记错误码，不打原始 JSON、不打音频
                            Log.w(TAG, "voice error code=$code")
                            VoiceRecognitionEvent.Failed(VoiceFailure.Service(code.toString()))
                        } else {
                            val data = json.optJSONObject("data")
                            val source = data?.optString("source").orEmpty()
                            val target = data?.optString("target").orEmpty()
                            if (target.isBlank()) {
                                VoiceRecognitionEvent.Failed(VoiceFailure.NoSpeech)
                            } else {
                                val elapsed = System.currentTimeMillis() - started
                                Log.i(
                                    TAG,
                                    "voice translate ok sourceChars=${source.length} " +
                                        "targetChars=${target.length} elapsedMs=$elapsed",
                                )
                                VoiceRecognitionEvent.Final(target)
                            }
                        }
                    }
                }
            }
        } catch (tls: javax.net.ssl.SSLException) {
            VoiceRecognitionEvent.Failed(VoiceFailure.Network)
        } catch (io: java.io.IOException) {
            VoiceRecognitionEvent.Failed(VoiceFailure.Network)
        } catch (t: Throwable) {
            Log.w(TAG, "voice error kind=${t.javaClass.simpleName}")
            VoiceRecognitionEvent.Failed(VoiceFailure.Service(null))
        } finally {
            Log.i(TAG, "voice upload done elapsedMs=${System.currentTimeMillis() - started}")
        }
        emit(outcome)
    }

    /** `X-Sign = base64(hmac_sha256(密钥, appid + timestamp + voiceBase64))` */
    private fun sign(appId: String, timestamp: String, voice: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal("$appId$timestamp$voice".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun emit(event: VoiceRecognitionEvent) {
        onEvent?.invoke(event)
    }

    private companion object {
        const val TAG = "BoardVoice"

        /** 主机取自官方平台域名 fanyi-api.baidu.com + 文档路径 */
        const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/v2/voicetrans"

        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 官方限制：完整录音不超过 60 秒 */
        const val MAX_SECONDS = 60
        const val CHUNK_BYTES = 3200
        const val AUDIO_JOIN_MS = 500L

        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/** 便于排查：只给长度用的辅助（不参与日志输出内容） */
internal fun String.charCountForLog(): Int = length

/** 复用文本翻译的错误码中文说明（同一平台的 code 体系） */
internal fun voiceCodeMessage(code: String?): String = translateProviderMessage(code)
