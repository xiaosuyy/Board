package com.board.ime.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.board.ime.dictionary.DictionarySource
import com.board.ime.dictionary.DictionarySourceType
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionarySourceDao {

    @Query("SELECT * FROM dictionary_source ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DictionarySource>>

    @Query("SELECT * FROM dictionary_source ORDER BY updatedAt DESC")
    suspend fun all(): List<DictionarySource>

    @Query("SELECT COUNT(*) FROM dictionary_source")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM dictionary_source WHERE type = :type")
    suspend fun countByType(type: DictionarySourceType): Int

    @Query("SELECT * FROM dictionary_source WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DictionarySource?

    @Query("SELECT * FROM dictionary_source WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): DictionarySource?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: DictionarySource): Long

    @Query("DELETE FROM dictionary_source WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("UPDATE dictionary_source SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, updatedAt: Long)
}
