package com.board.ime.voice

import android.content.Context
import java.io.File

/**
 * 离线语音模型（SenseVoiceSmall int8）的本地管理。
 *
 * 模型有两种来源，优先级：**本地（filesDir，用户曾手动放置）> 内置（assets，随 APK 打包）**。
 * 默认模型已内置进 APK，开箱即用、完全离线，无需联网下载。
 *
 * 隐私与边界：
 * - 这里管理的**只有模型权重文件**（model.int8.onnx / tokens.txt），
 *   它们是公开的神经网络权重，**不是**用户的录音或识别文本；
 * - 本地模型存放在应用私有目录 `filesDir/voice-model/`，不落缓存、不进数据库；
 * - 识别全程离线，模型权重不参与任何联网行为。
 */
class VoiceModelManager(context: Context) {

    private val appContext = context.applicationContext

    /** 本地模型根目录（可选：用户手动放置的模型，优先于内置模型使用） */
    val modelDir: File = File(appContext.filesDir, "voice-model")

    /** 本地 SenseVoiceSmall 量化权重（约 228 MB，可选） */
    val modelFile: File = File(modelDir, "model.int8.onnx")

    /** 本地词表文件（约 308 KB，可选） */
    val tokensFile: File = File(modelDir, "tokens.txt")

    /** 本地（filesDir）模型是否就绪（两个文件都在且非空） */
    fun isLocalReady(): Boolean =
        modelFile.isFile && modelFile.length() > 0 && tokensFile.isFile && tokensFile.length() > 0

    /** 模型是否已内置进 APK（assets/voice-model/，随包分发，无需联网下载） */
    fun isBundled(): Boolean = assetExists(ASSETS_MODEL) && assetExists(ASSETS_TOKENS)

    /** 模型是否可用：本地已放置 或 已内置进 APK */
    fun isReady(): Boolean = isLocalReady() || isBundled()

    /** 检查 assets 下某个文件是否存在（只读目录元数据，不打开大文件流） */
    private fun assetExists(path: String): Boolean {
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return false
        val dir = path.substring(0, slash)
        val name = path.substring(slash + 1)
        return runCatching {
            appContext.assets.list(dir)?.contains(name) == true
        }.getOrDefault(false)
    }

    /** 删除本地模型，释放磁盘空间（删除后自动回退到内置模型） */
    fun delete() {
        runCatching { modelFile.delete() }
        runCatching { tokensFile.delete() }
    }

    /** 本地模型已占用的磁盘大小（用于设置页展示），未就绪时返回 0 */
    fun sizeBytes(): Long {
        if (!isLocalReady()) return 0L
        return modelFile.length() + tokensFile.length()
    }

    companion object {
        /**
         * 内置进 APK 的模型在 assets 下的相对路径（不含前导斜杠）。
         * 打包时由构建流程把 model.int8.onnx / tokens.txt 放到 assets/voice-model/ 下，
         * sherpa-onnx 通过 AssetManager 直接读取，无需复制到私有目录。
         */
        const val ASSETS_MODEL = "voice-model/model.int8.onnx"
        const val ASSETS_TOKENS = "voice-model/tokens.txt"
    }
}
