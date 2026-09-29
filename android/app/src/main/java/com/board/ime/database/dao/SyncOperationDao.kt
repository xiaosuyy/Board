package com.board.ime.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.board.ime.database.entity.SyncOperation
import com.board.ime.sync.SyncEntityType
import com.board.ime.sync.SyncOperationStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncOperationDao {

    @Query("SELECT * FROM sync_operation WHERE status = :status ORDER BY createdAt ASC LIMIT :limit")
    suspend fun findByStatus(status: SyncOperationStatus, limit: Int): List<SyncOperation>

    @Query("SELECT COUNT(*) FROM sync_operation WHERE status = :status")
    fun observeCount(status: SyncOperationStatus): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_operation WHERE status = :status")
    suspend fun count(status: SyncOperationStatus): Int

    @Insert
    suspend fun enqueue(operation: SyncOperation): Long

    @Update
    suspend fun update(operation: SyncOperation)

    @Query("DELETE FROM sync_operation WHERE status = :status")
    suspend fun deleteByStatus(status: SyncOperationStatus): Int

    @Query("DELETE FROM sync_operation WHERE entityType = :entityType AND status = :status")
    suspend fun deleteByTypeAndStatus(entityType: SyncEntityType, status: SyncOperationStatus): Int

    @Query("SELECT COUNT(*) FROM sync_operation WHERE status = :status")
    suspend fun countByStatus(status: SyncOperationStatus): Int
}
