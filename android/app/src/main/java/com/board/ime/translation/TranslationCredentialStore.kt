package com.board.ime.translation

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 翻译服务配置（百度 APPID/密钥，或 OpenAI 格式接口地址/Key/模型）。
 *
 * 与 WebDAV 密码同样的处理方式：
 * - 走 **EncryptedSharedPreferences**（Android Keystore 主密钥 + AES256），不落普通明文配置；
 * - Keystore 不可用时**不静默降级成明文**：记日志并把配置只留在本次会话内存里；
 * - **绝不保存翻译原文 / 译文**（本类只认识配置字段）；
 * - 日志绝不输出 API Key / 密钥内容。
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

    /** 加密存储不可用时的内存兜底（配置只保留在本次会话） */
    private var memorySettings: TranslationSettings? = null

    /** 配置读取：两种服务商共用一份加密存储，正文永远不在这里 */
    fun load(): TranslationSettings {
        val fallback = memorySettings
        return TranslationSettings(
            kind = TranslationProviderKind.fromId(read(KEY_PROVIDER)) ?: fallback?.kind
                ?: TranslationProviderKind.BAIDU,
            appId = read(KEY_APP_ID) ?: fallback?.appId.orEmpty(),
            secret = read(KEY_SECRET) ?: fallback?.secret.orEmpty(),
            baseUrl = read(KEY_BASE_URL) ?: fallback?.baseUrl.orEmpty(),
            apiKey = read(KEY_API_KEY) ?: fallback?.apiKey.orEmpty(),
            model = read(KEY_MODEL) ?: fallback?.model.orEmpty(),
        )
    }

    fun save(settings: TranslationSettings) {
        val normalized = settings.normalized()
        memorySettings = normalized
        val target = securePrefs ?: return
        runCatching {
            target.edit()
                .putString(KEY_PROVIDER, normalized.kind.id)
                .putString(KEY_APP_ID, normalized.appId)
                .putString(KEY_SECRET, normalized.secret)
                .putString(KEY_BASE_URL, normalized.baseUrl)
                .putString(KEY_API_KEY, normalized.apiKey)
                .putString(KEY_MODEL, normalized.model)
                .apply()
        }.onFailure { Log.e(TAG, "写入翻译配置失败", it) }
    }

    /** 只切换服务商，不动机密字段（设置页切换单选时用） */
    fun saveProviderKind(kind: TranslationProviderKind) {
        memorySettings = load().copy(kind = kind)
        runCatching {
            securePrefs?.edit()?.putString(KEY_PROVIDER, kind.id)?.apply()
        }.onFailure { Log.e(TAG, "写入翻译服务商失败", it) }
    }

    fun clear() {
        memorySettings = null
        runCatching { securePrefs?.edit()?.clear()?.apply() }
            .onFailure { Log.e(TAG, "清空翻译配置失败", it) }
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
        const val KEY_PROVIDER = "provider"
        const val KEY_APP_ID = "app_id"
        const val KEY_SECRET = "secret"
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL = "model"
    }
}

/** 翻译服务商类型（设置页二选一，默认百度） */
enum class TranslationProviderKind(val id: String, val label: String) {
    BAIDU("baidu", "百度翻译"),
    OPENAI("openai", "OpenAI 格式接口"),
    ;

    companion object {
        fun fromId(id: String?): TranslationProviderKind? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/**
 * 翻译配置：百度 APPID/密钥，或 OpenAI 格式接口的地址/Key/模型。
 *
 * **只用于构造请求**；不保存原文、译文、历史或缓存。
 */
data class TranslationSettings(
    val kind: TranslationProviderKind = TranslationProviderKind.BAIDU,
    val appId: String = "",
    val secret: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
) {
    val baiduReady: Boolean
        get() = appId.isNotBlank() && secret.isNotBlank()

    /** 本地 Ollama 不需要 Key；远程端点按服务商要求，空 Key 由服务端报错 */
    val openAiReady: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    val isConfigured: Boolean
        get() = when (kind) {
            TranslationProviderKind.BAIDU -> baiduReady
            TranslationProviderKind.OPENAI -> openAiReady
        }

    fun normalized(): TranslationSettings = copy(
        appId = appId.trim(),
        secret = secret.trim(),
        baseUrl = baseUrl.trim().trimEnd('/'),
        apiKey = apiKey.trim(),
        model = model.trim(),
    )
}

/** 百度翻译凭据。**只用于构造请求，不写日志、不进日志字段。** */
data class TranslationCredentials(
    val appId: String = "",
    val secret: String = "",
) {
    val isComplete: Boolean
        get() = appId.isNotBlank() && secret.isNotBlank()
}
