package com.board.ime.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.board.ime.database.entity.Device
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {

    @Query("SELECT * FROM device ORDER BY isCurrent DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<Device>>

    @Query("SELECT * FROM device ORDER BY isCurrent DESC, lastSeenAt DESC")
    suspend fun all(): List<Device>

    @Query("SELECT * FROM device WHERE deviceId = :deviceId LIMIT 1")
    suspend fun findById(deviceId: String): Device?

    @Query("SELECT * FROM device WHERE isCurrent = 1 LIMIT 1")
    suspend fun current(): Device?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: Device)

    @Query("UPDATE device SET isCurrent = 0 WHERE deviceId != :keepDeviceId")
    suspend fun clearCurrentExcept(keepDeviceId: String): Int

    @Query("UPDATE device SET lastSyncAt = :syncedAt WHERE deviceId = :deviceId")
    suspend fun updateLastSync(deviceId: String, syncedAt: Long)

    @Query("DELETE FROM device WHERE deviceId = :deviceId")
    suspend fun deleteById(deviceId: String): Int
}
