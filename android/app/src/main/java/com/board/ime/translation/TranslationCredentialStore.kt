package com.board.ime.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 翻译服务凭据（APPID / 密钥）。
 *
 * 与 WebDAV 密码同样的处理方式：
 * - 走 **EncryptedSharedPreferences**（Android Keystore 主密钥 + AES256），不落普通明文配置；
 * - Keystore 不可用时**不静默降级成明文**：记日志并把凭据只留在本次会话内存里；
 * - **绝不保存翻译原文 / 译文**（本类只认识这两个凭据字段）；
 * - 日志绝不输出凭据内容。
 *
 * 注意：APPID 也会被反编译出来，但它本身不是密钥；**密钥**才是机密，
 * 因此这里只做加密存储，代码/资源/manifest 里不存在任何内置 Key。
 */
class TranslationCredentialStore(context: Context) {

    private val appContext = context.applicationContext

    private val plain: SharedPreferences =
        appContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

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
        Log.e(TAG, "翻译凭据加密存储不可用，凭据只保留在本次会话", throwable)
        null
    }

    /** 加密存储不可用时的内存兜底 */
    private var memoryAppId: String = ""
    private var memorySecret: String = ""

    /** APPID 不是密钥，但仍与密钥一样放在加密存储里，避免散落在多个文件 */
    fun load(): TranslationCredentials {
        val appId = read(KEY_APP_ID) ?: memoryAppId
        val secret = read(KEY_SECRET) ?: memorySecret
        return TranslationCredentials(appId.orEmpty(), secret.orEmpty())
    }

    fun save(credentials: TranslationCredentials) {
        val appId = credentials.appId.trim()
        val secret = credentials.secret.trim()
        val target = securePrefs
        if (target == null) {
            memoryAppId = appId
            memorySecret = secret
            return
        }
        runCatching {
            target.edit()
                .putString(KEY_APP_ID, appId)
                .putString(KEY_SECRET, secret)
                .apply()
        }.onFailure { Log.e(TAG, "写入翻译凭据失败", it) }
    }

    fun clear() {
        memoryAppId = ""
        memorySecret = ""
        runCatching { securePrefs?.edit()?.clear()?.apply() }
            .onFailure { Log.e(TAG, "清空翻译凭据失败", it) }
    }

    private fun read(key: String): String? {
        val source = securePrefs ?: return null
        return try {
            source.getString(key, null)
        } catch (throwable: Throwable) {
            // 解密失败 / 文件损坏：丢掉这个加密文件，下次重新建一个可用的
            Log.e(TAG, "读取翻译凭据失败，已丢弃损坏的加密存储", throwable)
            runCatching { appContext.deleteSharedPreferences(SECURE_FILE) }
            null
        }
    }

    private companion object {
        const val TAG = "BoardTranslate"
        const val PLAIN_FILE = "board_translation"
        const val SECURE_FILE = "board_translation_secure"
        const val KEY_APP_ID = "app_id"
        const val KEY_SECRET = "secret"
    }
}

/** 翻译服务凭据。**只用于构造请求，不写日志、不进日志字段。** */
data class TranslationCredentials(
    val appId: String = "",
    val secret: String = "",
) {
    val isComplete: Boolean
        get() = appId.isNotBlank() && secret.isNotBlank()
}
