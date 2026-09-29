package com.board.ime.sync

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.board.ime.database.dao.DeviceDao
import com.board.ime.database.entity.Device
import kotlinx.coroutines.flow.Flow

/**
 * 设备仓库。
 *
 * 阶段一只是把本机登记进 device 表（为后续配对同步做准备），
 * 不涉及任何网络通信。
 */
class DeviceRepository(private val dao: DeviceDao) {

    fun observeAll(): Flow<List<Device>> = dao.observeAll()

    suspend fun all(): List<Device> = dao.all()

    suspend fun current(): Device? = dao.current()

    /** 登记 / 刷新本机设备记录，deviceId 使用系统 ANDROID_ID */
    suspend fun registerCurrentDevice(context: Context, displayName: String? = null): Device {
        val deviceId = androidId(context)
        val existing = dao.findById(deviceId)
        val now = System.currentTimeMillis()

        val device = Device(
            deviceId = deviceId,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: defaultDisplayName(),
            platform = DevicePlatform.ANDROID,
            isCurrent = true,
            lastSeenAt = now,
            lastSyncAt = existing?.lastSyncAt,
            publicKey = existing?.publicKey,
            createdAt = existing?.createdAt ?: now,
        )

        dao.upsert(device)
        dao.clearCurrentExcept(deviceId)
        return device
    }

    suspend fun markSynced(deviceId: String, syncedAt: Long = System.currentTimeMillis()) {
        dao.updateLastSync(deviceId, syncedAt)
    }

    suspend fun remove(deviceId: String): Int = dao.deleteById(deviceId)

    private fun androidId(context: Context): String = runCatching {
        // ANDROID_ID 不在 Android 14 的限制名单里；这里仍然兜底，
        // 任何 SecurityException 都退化成固定 id，不影响输入法本身。
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: FALLBACK_DEVICE_ID

    private fun defaultDisplayName(): String {
        val model = Build.MODEL
        return if (model.isNullOrBlank()) "Android 设备" else "Android · $model"
    }

    companion object {
        private const val FALLBACK_DEVICE_ID = "board-unknown-device"
    }
}
