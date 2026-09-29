package com.board.ime.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.board.ime.sync.DevicePlatform

/**
 * 已配对设备。
 *
 * 阶段一只有本机一条记录（[isCurrent] = true），
 * [publicKey] / [lastSyncAt] 为后续端到端加密与同步准备。
 */
@Entity(
    tableName = "device",
    indices = [Index(value = ["lastSeenAt"])],
)
data class Device(
    @PrimaryKey
    val deviceId: String,
    val displayName: String,
    val platform: DevicePlatform = DevicePlatform.ANDROID,
    val isCurrent: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis(),
    val lastSyncAt: Long? = null,
    val publicKey: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
