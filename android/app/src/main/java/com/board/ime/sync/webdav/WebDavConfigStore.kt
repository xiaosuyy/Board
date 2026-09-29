package com.board.ime.sync.webdav

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.board.ime.sync.SyncPart
import com.board.ime.sync.SyncPartState
import com.board.ime.sync.SyncStatus

/**
 * WebDAV 配置存储。
 *
 * 地址 / 用户名 / 目录 / 开关走普通 `SharedPreferences`，
 * **密码单独放在 `EncryptedSharedPreferences`**（Android Keystore 主密钥 + AES256）。
 *
 * 少数设备上 Keystore 不可用（例如被裁剪的 ROM），这时**不会**默默降级成明文：
 * 会记录日志、并在设置页显示「安全存储不可用」，密码只保存在内存里（本次会话有效）。
 */
class WebDavConfigStore(context: Context) {

    private val appContext = context.applicationContext

    private val plain: SharedPreferences =
        appContext.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE)

    /** 加密存储是否可用 */
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
        Log.e(TAG, "加密存储不可用，密码只保留在本次会话", throwable)
        null
    }

    /** 加密存储不可用时的内存兜底 */
    private var memoryPassword: String = ""

    /**
     * 读取配置。**任何异常都不能抛出去**：
     * 加密存储的解密可能在真机上失败（换机、恢复备份、Keystore 被重置等），
     * 这时退化成「没有密码」，而不是让设置页崩掉。
     */
    fun load(): WebDavConfig {
        val baseUrl = readString(plain, KEY_BASE_URL).orEmpty()
        val username = readString(plain, KEY_USERNAME).orEmpty()
        val syncDir = readString(plain, KEY_SYNC_DIR).orEmpty().ifBlank { WebDavConfig.DEFAULT_DIR }
        val autoSync = runCatching { plain.getBoolean(KEY_AUTO_SYNC, false) }.getOrDefault(false)
        val password = readString(securePrefs, KEY_PASSWORD) ?: memoryPassword
        return WebDavConfig(
            baseUrl = baseUrl,
            username = username,
            password = password,
            syncDir = syncDir,
            autoSync = autoSync,
        )
    }

    private fun readString(source: SharedPreferences?, key: String): String? {
        if (source == null) return null
        return try {
            source.getString(key, null)
        } catch (throwable: Throwable) {
            // 解密失败 / 文件损坏：把这个加密文件丢掉，下次重新建一个可用的
            Log.e(TAG, "读取加密配置失败，已丢弃损坏的加密存储", throwable)
            runCatching { appContext.deleteSharedPreferences(SECURE_FILE) }
            null
        }
    }

    private fun writeString(source: SharedPreferences?, key: String, value: String): Boolean {
        if (source == null) return false
        return try {
            source.edit().putString(key, value).apply()
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "写入加密配置失败", throwable)
            runCatching { appContext.deleteSharedPreferences(SECURE_FILE) }
            false
        }
    }

    fun save(config: WebDavConfig) {
        runCatching {
            plain.edit()
                .putString(KEY_BASE_URL, config.baseUrl.trim())
                .putString(KEY_USERNAME, config.username.trim())
                .putString(KEY_SYNC_DIR, config.syncDir.trim())
                .putBoolean(KEY_AUTO_SYNC, config.autoSync)
                .apply()
        }.onFailure { Log.e(TAG, "写入 WebDAV 配置失败", it) }

        val password = config.password
        if (!writeString(securePrefs, KEY_PASSWORD, password)) {
            memoryPassword = password
        }
    }

    fun setAutoSync(enabled: Boolean) {
        runCatching { plain.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply() }
    }

    /** 最近同步时间 / 摘要 / 错误：跨进程存活，设置页重启后还能看到 */
    fun saveLastSync(result: SyncStatus) {
        runCatching {
            plain.edit()
                .putLong(KEY_LAST_SYNC_AT, result.lastSyncAt ?: 0L)
                .putString(KEY_LAST_SUMMARY, result.lastSummary.orEmpty())
                .putString(KEY_LAST_ERROR, result.lastError.orEmpty())
                .putString(KEY_PART_CLIPBOARD, result.clipboard.state.name)
                .putString(KEY_PART_CLIPBOARD_DETAIL, result.clipboard.detail.orEmpty())
                .putString(KEY_PART_DICTIONARY, result.dictionary.state.name)
                .putString(KEY_PART_DICTIONARY_DETAIL, result.dictionary.detail.orEmpty())
                .apply()
        }
    }

    fun loadLastSync(): Triple<Long?, String?, String?> = runCatching {
        val at = plain.getLong(KEY_LAST_SYNC_AT, 0L).takeIf { it > 0L }
        val summary = plain.getString(KEY_LAST_SUMMARY, "").orEmpty().ifBlank { null }
        val error = plain.getString(KEY_LAST_ERROR, "").orEmpty().ifBlank { null }
        Triple(at, summary, error)
    }.getOrDefault(Triple(null, null, null))

    /** 上次各通道的结束状态（重启设置页后仍然显示「剪贴板 ✓ 已同步」） */
    fun loadLastParts(): Pair<SyncPart, SyncPart> = runCatching {
        SyncPart(
            state = plain.getString(KEY_PART_CLIPBOARD, null)?.let(::partState) ?: SyncPartState.IDLE,
            detail = plain.getString(KEY_PART_CLIPBOARD_DETAIL, "").orEmpty().ifBlank { null },
        ).settledIfRunning("上次同步中断") to SyncPart(
            state = plain.getString(KEY_PART_DICTIONARY, null)?.let(::partState) ?: SyncPartState.IDLE,
            detail = plain.getString(KEY_PART_DICTIONARY_DETAIL, "").orEmpty().ifBlank { null },
        ).settledIfRunning("上次同步中断")
    }.getOrDefault(SyncPart() to SyncPart())

    private fun partState(name: String): SyncPartState =
        runCatching { SyncPartState.valueOf(name) }.getOrDefault(SyncPartState.IDLE)

    fun clear() {
        runCatching { plain.edit().clear().apply() }
        runCatching { securePrefs?.edit()?.clear()?.apply() }
        memoryPassword = ""
    }

    private companion object {
        const val TAG = "BoardSync"
        const val PLAIN_FILE = "board_webdav"
        const val SECURE_FILE = "board_webdav_secure"
        const val KEY_BASE_URL = "base_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_SYNC_DIR = "sync_dir"
        const val KEY_AUTO_SYNC = "auto_sync"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SUMMARY = "last_sync_summary"
        const val KEY_LAST_ERROR = "last_sync_error"
        const val KEY_PART_CLIPBOARD = "last_part_clipboard"
        const val KEY_PART_CLIPBOARD_DETAIL = "last_part_clipboard_detail"
        const val KEY_PART_DICTIONARY = "last_part_dictionary"
        const val KEY_PART_DICTIONARY_DETAIL = "last_part_dictionary_detail"
    }
}
