package com.board.ime.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.ByteArrayOutputStream

/**
 * 本地离线语音识别引擎：SenseVoiceSmall(int8) + sherpa-onnx，全程不联网。
 *
 * 设计要点（对应语音需求与隐私要求）：
 * - 所有音频处理在设备本地完成，录音 PCM **只在内存**中短暂存在，绝不落盘、不进日志；
 * - 模型权重缓存在 [OfflineRecognizer] 单例里（懒加载，避免重复初始化），
 *   会话级别的 [com.k2fsa.sherpa.onnx.OfflineStream] 每次识别后立即释放；
 * - 录音采用「先开麦、后台加载模型」：模型首次加载约 1~3 秒，期间音频由
 *   AudioRecord 的大缓冲兜住，不丢开头、不阻塞主线程；
 * - 伪流式：每约 1 秒对**累积音频**做一次离线识别，回传 Partial 只更新预览；
 *   松开后对完整音频做最终识别回传 Final；
 * - 任意时刻调用 [cancel] 都立即丢弃音频缓冲与结果，不提交。
 */
class VoiceInputEngine(
    private val context: Context,
    private val modelManager: VoiceModelManager,
) : VoiceRecognitionEngine {

    override val displayName: String = "本地离线语音识别"

    /** 模型已下载即为就绪（不需要网络 / 凭据） */
    override fun isConfigured(): Boolean = modelManager.isReady()

    override var onEvent: ((VoiceRecognitionEvent) -> Unit)? = null

    override var onVolume: ((Float) -> Unit)? = null

    private val sampleRate = 16000

    /** 模型权重缓存：懒加载单例，避免每次识别都重新初始化 */
    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var recording = false

    @Volatile
    private var finalize = false

    /** 用户已取消 / 服务销毁：此时录音线程的异常不再回传错误（避免取消却弹「录音失败」） */
    @Volatile
    private var cancelled = false

    /** 防止 [start] 被并发调用造成重复初始化（模型加载互斥） */
    private val startLock = Any()

    override fun start(language: VoiceLanguage) {
        synchronized(startLock) {
            if (recording) return
            if (!modelManager.isReady()) {
                onEvent?.invoke(VoiceRecognitionEvent.Failed(VoiceFailure.ModelNotReady))
                return
            }
            recording = true
            finalize = false
            cancelled = false
            worker = Thread({ runRecognitionLoop() }, "board-voice-input").also { it.start() }
        }
    }

    /** 用户松开：停止收音，做最终识别后回传 [VoiceRecognitionEvent.Final] */
    override fun stop() {
        finalize = true
        recording = false
    }

    /** 用户取消 / 手指移出：丢弃一切，不回传结果 */
    override fun cancel() {
        cancelled = true
        finalize = false
        recording = false
        // 让阻塞在 AudioRecord.read 的录音线程尽快退出
        runCatching { audioRecord?.stop() }
    }

    /** 释放全部资源（服务销毁时调用）。之后对象不再使用。 */
    override fun release() {
        cancelled = true
        finalize = false
        recording = false
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        // 等录音线程退出（最多 1.5s），避免其后续再触碰已释放的对象
        worker?.let { runCatching { it.join(1500) } }
        worker = null
        releaseRecognizer()
    }

    // ------------------------------------------------------------ 录音 + 伪流式识别

    private fun runRecognitionLoop() {
        try {
            // 1) 先开麦：无论模型加载多慢，都不丢用户开头说的话
            val ar = initAudioRecord()
            if (ar == null) {
                recording = false
                onEvent?.invoke(VoiceRecognitionEvent.Failed(VoiceFailure.Audio))
                return
            }
            audioRecord = ar
            runCatching { ar.startRecording() }

            // 2) 再加载模型（首次约 1~3s，录音在硬件缓冲里继续累积）
            val rec = ensureRecognizer()
            if (rec == null) {
                recording = false
                stopAndReleaseAudioRecord()
                onEvent?.invoke(VoiceRecognitionEvent.Failed(VoiceFailure.ModelLoadFailed))
                return
            }

            // 3) 录音循环 + 伪流式识别
            val pcm = ByteArrayOutputStream()
            val buffer = ByteArray(sampleRate / 10 * 2) // 100ms 的 16bit PCM = 3200 字节
            var lastPartialAt = 0L

            while (recording && !finalize) {
                val n = ar.read(buffer, 0, buffer.size)
                if (n < 0) {
                    // 录音出错（麦克风被占用 / 系统打断）：上报后退出，finally 释放资源
                    if (!cancelled) {
                        onEvent?.invoke(VoiceRecognitionEvent.Failed(VoiceFailure.Audio))
                    }
                    return
                }
                if (n > 0) {
                    pcm.write(buffer, 0, n)
                    // 实时音量：真实麦克风 RMS，归一化到 0..1，驱动声波动画
                    onVolume?.invoke(computeVolume(buffer, n))
                }
                val now = System.currentTimeMillis()
                if (now - lastPartialAt >= PARTIAL_INTERVAL_MS) {
                    lastPartialAt = now
                    val text = recognize(rec, pcm.toByteArray())
                    if (text.isNotBlank()) {
                        onEvent?.invoke(VoiceRecognitionEvent.Partial(text))
                    }
                }
            }

            // 4) 停止收音
            stopAndReleaseAudioRecord()

            // 5) 最终识别（仅正常结束，取消不走到这里）
            if (finalize) {
                val full = pcm.toByteArray()
                if (full.size < MIN_SPEECH_BYTES) {
                    onEvent?.invoke(VoiceRecognitionEvent.Failed(VoiceFailure.TooShort))
                } else {
                    val text = recognize(rec, full)
                    if (text.isNotBlank()) {
                        onEvent?.invoke(VoiceRecognitionEvent.Final(text))
                    } else {
                        onEvent?.invoke(VoiceRecognitionEvent.Failed(VoiceFailure.NoSpeech))
                    }
                }
            }
            // 丢弃本次缓冲（pcm 随函数结束即可回收）
        } catch (e: Exception) {
            recording = false
            // 用户取消 / 释放导致的退出不回传错误，只有真正的硬件/识别异常才提示
            if (!cancelled) {
                onEvent?.invoke(VoiceRecognitionEvent.Failed(VoiceFailure.Audio))
            }
        } finally {
            recording = false
            stopAndReleaseAudioRecord()
        }
    }

    /**
     * 计算 16bit PCM 的实时音量目标值（0..1），供声波动画使用。
     *
     * 关键：**不做线性 RMS 映射**。16bit 麦克风的正常说话 RMS 只有满幅的 0.005~0.08，
     * 直接归一化会让「说话」长期挤在 0.01~0.1 的低位，声波几乎不动。这里改成：
     *
     * 1. RMS → 分贝（20·log10），把人耳/电平表感知的动态范围压到合理区间；
     * 2. 噪声门限：低于 [NOISE_FLOOR_DB] 视为静音 → 0，避免背景底噪让声波一直跳；
     * 3. 非线性映射到 0..1：[CEIL_DB]（大声典型峰值）映射到 1，轻声也有可见反馈。
     *
     * 攻击/释放的平滑放在绘制端（KeyboardView）每帧处理，这里只输出「目标值」。
     * 纯内存计算，不碰磁盘、不写日志。
     */
    private fun computeVolume(pcm: ByteArray, len: Int): Float {
        val samples = len / 2
        if (samples == 0) return 0f
        var sum = 0L
        for (i in 0 until samples) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s = (lo or (hi shl 8)).toShort().toInt()
            sum += s.toLong() * s
        }
        val rms = kotlin.math.sqrt(sum.toDouble() / samples) / 32768.0
        val db = 20.0 * kotlin.math.log10(maxOf(rms, MIN_RMS))
        return ((db - NOISE_FLOOR_DB) / (CEIL_DB - NOISE_FLOOR_DB))
            .toFloat().coerceIn(0f, 1f)
    }

    /** 识别一段 PCM（16kHz / 16bit / mono → 文本）。不碰磁盘、不写日志。 */
    private fun recognize(rec: OfflineRecognizer, pcm: ByteArray): String {
        val samples = FloatArray(pcm.size / 2)
        for (i in samples.indices) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            samples[i] = ((lo or (hi shl 8)).toShort()).toInt() / 32768f
        }
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            rec.decode(stream)
            return rec.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** 懒加载 + 缓存 recognizer（模型权重）。加载失败返回 null。 */
    private fun ensureRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        return runCatching {
            // 优先用本地下载的模型（filesDir，可能更新）；否则用内置进 APK 的模型，
            // 后者传 AssetManager，由 sherpa-onnx 直接从 assets 读取，无需复制到磁盘。
            val fromAssets = !modelManager.isLocalReady() && modelManager.isBundled()
            val assetManager = if (fromAssets) context.assets else null
            val modelPath =
                if (fromAssets) VoiceModelManager.ASSETS_MODEL else modelManager.modelFile.absolutePath
            val tokensPath =
                if (fromAssets) VoiceModelManager.ASSETS_TOKENS else modelManager.tokensFile.absolutePath
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = sampleRate, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = modelPath,
                        language = "auto",
                        useInverseTextNormalization = true,
                    ),
                    tokens = tokensPath,
                    numThreads = NUM_THREADS,
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            OfflineRecognizer(assetManager = assetManager, config = config).also { recognizer = it }
        }.getOrNull()
    }

    private fun initAudioRecord(): AudioRecord? = runCatching {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 大缓冲（至少 4 秒）：模型加载期间硬件继续累积，不丢开头
        val size = maxOf(min * 8, sampleRate * 4)
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            size,
        )
    }.getOrNull()

    private fun stopAndReleaseAudioRecord() {
        val ar = audioRecord ?: return
        audioRecord = null
        runCatching { ar.stop() }
        runCatching { ar.release() }
    }

    private fun releaseRecognizer() {
        recognizer?.let { runCatching { it.release() } }
        recognizer = null
    }

    private companion object {
        const val PARTIAL_INTERVAL_MS = 1000L

        /** 低于 0.5 秒的音频视为没有有效语音（16000Hz * 0.5s * 2 字节） */
        const val MIN_SPEECH_BYTES = 16000

        /** SenseVoice 解码线程数：2 线程在移动端 CPU 上是速度/功耗的较好折中 */
        const val NUM_THREADS = 2

        /** dB 计算下限（约 -100dB），避免 log(0) 得到负无穷 */
        const val MIN_RMS = 1e-5

        /** 噪声门限：低于 -50dB（RMS≈0.003）视为静音，映射为 0，滤掉背景底噪 */
        const val NOISE_FLOOR_DB = -50.0

        /** 音量上界：-8dB（RMS≈0.4）起映射到 1，让「正常/大声说话」占据高位区间 */
        const val CEIL_DB = -8.0
    }
}
