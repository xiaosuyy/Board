package com.board.ime.voice

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 语音识别的凭据（百度智能云语音服务的 **AppID + API Key**）。
 *
 * 与翻译凭据**完全分开存**（不同文件、不同字段）：
 * 官方实时语音识别用的是 AppID + API Key，而文本翻译用的是另一套
 * APPID + 密钥，两者不能混用 —— 混用会得到 -3004 之类的鉴权错误。
 *
 * 隐私要求：
 * - 走 **EncryptedSharedPreferences**（Android Keystore + AES256），不落普通明文配置；
 * - Keystore 不可用时**不静默降级成明文**，凭据只留在本次会话内存；
 * - **绝不保存语音内容、PCM、识别文本**（本类只认识这两个字段）；
 * - **绝不把凭据写进日志**。
 */
class VoiceCredentialStore(context: Context) {

    private val appContext = context.applicationContext

    /** 加密存储是否可用（设置页要如实显示） */
    val secureStorageAvailable: Boolean
        get() = securePrefs != null

    private val securePrefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (throwable: Throwable) {
        Log.e(TAG, "语音凭据加密存储不可用，凭据只保留在本次会话", throwable)
        null
    }

    private var memoryAppId: String = ""
    private var memoryApiKey: String = ""

    fun load(): VoiceCredentials {
        val appId = read(KEY_APP_ID) ?: memoryAppId
        val apiKey = read(KEY_API_KEY) ?: memoryApiKey
        return VoiceCredentials(appId.orEmpty(), apiKey.orEmpty())
    }

    fun save(credentials: VoiceCredentials) {
        val appId = credentials.appId.trim()
        val apiKey = credentials.apiKey.trim()
        val target = securePrefs
        if (target == null) {
            memoryAppId = appId
            memoryApiKey = apiKey
            return
        }
        runCatching {
            target.edit()
                .putString(KEY_APP_ID, appId)
                .putString(KEY_API_KEY, apiKey)
                .apply()
        }.onFailure { Log.e(TAG, "写入语音凭据失败", it) }
    }

    fun clear() {
        memoryAppId = ""
        memoryApiKey = ""
        runCatching { securePrefs?.edit()?.clear()?.apply() }
            .onFailure { Log.e(TAG, "清空语音凭据失败", it) }
    }

    private fun read(key: String): String? {
        val source = securePrefs ?: return null
        return try {
            source.getString(key, null)
        } catch (throwable: Throwable) {
            Log.e(TAG, "读取语音凭据失败，已丢弃损坏的加密存储", throwable)
            runCatching { appContext.deleteSharedPreferences(SECURE_FILE) }
            null
        }
    }

    private companion object {
        const val TAG = "BoardVoice"
        const val SECURE_FILE = "board_voice_secure"
        const val KEY_APP_ID = "speech_app_id"
        const val KEY_API_KEY = "speech_api_key"
    }
}

/** 语音服务凭据。**只用于构造 START 帧，不写日志、不进日志字段。** */
data class VoiceCredentials(
    val appId: String = "",
    val apiKey: String = "",
) {
    val isComplete: Boolean
        get() = appId.isNotBlank() && apiKey.isNotBlank()
}
