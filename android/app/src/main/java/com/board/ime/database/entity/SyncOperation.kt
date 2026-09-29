package com.board.ime.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.board.ime.sync.SyncEntityType
import com.board.ime.sync.SyncOperationAction
import com.board.ime.sync.SyncOperationStatus

/**
 * 待同步操作队列。
 *
 * 阶段一只建表和 DAO，不产生真实同步流量：
 * 后续 WebDAV / 局域网同步会依次消费这张表。
 */
@Entity(
    tableName = "sync_operation",
    indices = [
        Index(value = ["status"]),
        Index(value = ["entityType", "entityId"]),
        Index(value = ["createdAt"]),
    ],
)
data class SyncOperation(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val entityType: SyncEntityType,
    val entityId: String,
    val operation: SyncOperationAction,
    /** 变更内容快照（JSON），同步引擎负责填 */
    val payload: String? = null,
    val status: SyncOperationStatus = SyncOperationStatus.PENDING,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    /** 这条变更来自哪台设备（本机 = null / 本机 id） */
    val deviceId: String? = null,
    /** 是否为删除操作（墓碑），同步时用来传播删除 */
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    /** 变更发生时间（冲突比较用） */
    @ColumnInfo(defaultValue = "0")
    val timestamp: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
