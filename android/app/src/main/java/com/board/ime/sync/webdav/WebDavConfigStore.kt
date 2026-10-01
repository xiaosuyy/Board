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
        val allowCleartext = runCatching { plain.getBoolean(KEY_ALLOW_CLEARTEXT, false) }
            .getOrDefault(false)
        val password = readString(securePrefs, KEY_PASSWORD) ?: memoryPassword
        return WebDavConfig(
            baseUrl = baseUrl,
            username = username,
            password = password,
            syncDir = syncDir,
            autoSync = autoSync,
            allowCleartextHttp = allowCleartext,
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
                .putBoolean(KEY_ALLOW_CLEARTEXT, config.allowCleartextHttp)
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
                .putString(KEY_CLIP_STATE, result.clipboardState.name)
                .putString(KEY_CLIP_ERROR, result.clipboardError.orEmpty())
                .putString(KEY_LAST_PUSHED_HASH, result.lastPushedHash.orEmpty())
                .putLong(KEY_LAST_PUSHED_AT, result.lastPushedAt ?: 0L)
                .apply()
        }
    }

    fun loadLastSync(): Triple<Long?, String?, String?> = runCatching {
        val at = plain.getLong(KEY_LAST_SYNC_AT, 0L).takeIf { it > 0L }
        val summary = plain.getString(KEY_LAST_SUMMARY, "").orEmpty().ifBlank { null }
        val error = plain.getString(KEY_LAST_ERROR, "").orEmpty().ifBlank { null }
        Triple(at, summary, error)
    }.getOrDefault(Triple(null, null, null))

    /**
     * 剪贴板通道的持久化扩展：5 态 + 最近一次真实上传成功的 hash/时间。
     * **只有真实 HTTP 2xx 才会写入 lastPushedHash**，所以重启后它依然可信。
     */
    fun loadClipboardExtras(): Triple<com.board.ime.sync.ClipboardSyncState, String?, Long?> = runCatching {
        val state = plain.getString(KEY_CLIP_STATE, null)
            ?.let { runCatching { com.board.ime.sync.ClipboardSyncState.valueOf(it) }.getOrNull() }
            ?: com.board.ime.sync.ClipboardSyncState.IDLE
        val hash = plain.getString(KEY_LAST_PUSHED_HASH, "").orEmpty().ifBlank { null }
        val at = plain.getLong(KEY_LAST_PUSHED_AT, 0L).takeIf { it > 0L }
        Triple(state, hash, at)
    }.getOrDefault(Triple(com.board.ime.sync.ClipboardSyncState.IDLE, null, null))

    /** 剪贴板通道的失败原因（不含正文） */
    fun loadClipboardError(): String? =
        runCatching { plain.getString(KEY_CLIP_ERROR, "").orEmpty().ifBlank { null } }.getOrNull()

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

    /**
     * B1：增量历史的设备本地 seq（**只存一个数字，不存正文**）。
     * 跨重启单调，避免 itemId 复用被对端水位跳过。
     */
    fun loadLogSeq(): Long = runCatching { plain.getLong(KEY_LOG_SEQ, 0L) }.getOrDefault(0L)

    fun saveLogSeq(value: Long) {
        runCatching { plain.edit().putLong(KEY_LOG_SEQ, value).apply() }
    }

    /**
     * B1：各远端设备的已消费水位 `remoteDeviceId -> lastSeenSeq`。
     * **只存 metadata，不存远端正文**；deviceId 已安全化（不含 `:`）。
     */
    fun loadLastSeenSeq(): Map<String, Long> = runCatching {
        plain.getString(KEY_LAST_SEEN_SEQ, "").orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val index = entry.lastIndexOf(':')
                if (index <= 0) return@mapNotNull null
                val key = entry.substring(0, index)
                val value = entry.substring(index + 1).toLongOrNull() ?: return@mapNotNull null
                key to value
            }
            .toMap()
    }.getOrDefault(emptyMap())

    fun saveLastSeenSeq(values: Map<String, Long>) {
        runCatching {
            plain.edit()
                .putString(KEY_LAST_SEEN_SEQ, values.entries.joinToString(",") { "${it.key}:${it.value}" })
                .apply()
        }
    }

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
        const val KEY_ALLOW_CLEARTEXT = "allow_cleartext_http"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_SUMMARY = "last_sync_summary"
        const val KEY_LAST_ERROR = "last_sync_error"
        const val KEY_PART_CLIPBOARD = "last_part_clipboard"
        const val KEY_PART_CLIPBOARD_DETAIL = "last_part_clipboard_detail"
        const val KEY_PART_DICTIONARY = "last_part_dictionary"
        const val KEY_PART_DICTIONARY_DETAIL = "last_part_dictionary_detail"
        const val KEY_CLIP_STATE = "clipboard_state"
        const val KEY_CLIP_ERROR = "clipboard_error"
        const val KEY_LAST_PUSHED_HASH = "last_pushed_hash"
        const val KEY_LAST_PUSHED_AT = "last_pushed_at"
        /** B1：增量历史（只存数字 / metadata，绝不含正文） */
        const val KEY_LOG_SEQ = "clipboard_log_seq"
        const val KEY_LAST_SEEN_SEQ = "clipboard_log_last_seen_seq"
    }
}
