package com.board.ime.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.board.ime.database.entity.DictionaryEntry
import com.board.ime.dictionary.WordType
import kotlinx.coroutines.flow.Flow

@Dao
interface DictionaryDao {

    // ---------------------------------------------------------------- 界面读取（不含墓碑）

    @Query(
        "SELECT * FROM dictionary_entry WHERE isDeleted = 0 " +
            "ORDER BY type ASC, updatedAt DESC LIMIT :limit",
    )
    fun observeAll(limit: Int): Flow<List<DictionaryEntry>>

    @Query(
        "SELECT * FROM dictionary_entry WHERE isDeleted = 0 AND type = :type " +
            "ORDER BY updatedAt DESC LIMIT :limit",
    )
    fun observeByType(type: WordType, limit: Int): Flow<List<DictionaryEntry>>

    @Query(
        "SELECT * FROM dictionary_entry WHERE isDeleted = 0 AND enabled = 1 " +
            "ORDER BY frequency DESC, word ASC LIMIT :limit",
    )
    fun observeTop(limit: Int): Flow<List<DictionaryEntry>>

    @Query(
        "SELECT * FROM dictionary_entry WHERE isDeleted = 0 AND enabled = 1 " +
            "AND word LIKE :prefix || '%' " +
            "ORDER BY frequency DESC, length(word) ASC, word ASC LIMIT :limit",
    )
    suspend fun suggestByPrefix(prefix: String, limit: Int): List<DictionaryEntry>

    @Query("SELECT * FROM dictionary_entry WHERE isDeleted = 0 AND word = :word LIMIT 1")
    suspend fun findByWord(word: String): DictionaryEntry?

    /** 同步 / 复活用：连墓碑一起找 */
    @Query("SELECT * FROM dictionary_entry WHERE word = :word ORDER BY updatedAt DESC LIMIT 1")
    suspend fun findByWordAny(word: String): DictionaryEntry?

    @Query("SELECT * FROM dictionary_entry WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DictionaryEntry?

    @Query("SELECT word FROM dictionary_entry WHERE isDeleted = 0")
    suspend fun allWords(): List<String>

    @Query("SELECT word FROM dictionary_entry WHERE isDeleted = 0 AND type = :type")
    suspend fun wordsByType(type: WordType): List<String>

    @Query("SELECT * FROM dictionary_entry WHERE isDeleted = 0 AND type = :type ORDER BY word ASC")
    suspend fun byType(type: WordType): List<DictionaryEntry>

    @Query("SELECT * FROM dictionary_entry WHERE isDeleted = 0 ORDER BY type ASC, word ASC")
    suspend fun all(): List<DictionaryEntry>

    @Query("SELECT COUNT(*) FROM dictionary_entry WHERE isDeleted = 0")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM dictionary_entry WHERE isDeleted = 0 AND type = :type")
    suspend fun countByType(type: WordType): Int

    // ---------------------------------------------------------------- 同步（含墓碑）

    /** 同步导出：连墓碑一起带走，删除才能传播到别的设备 */
    @Query("SELECT * FROM dictionary_entry WHERE type IN (:types) ORDER BY word ASC")
    suspend fun exportAll(types: List<WordType>): List<DictionaryEntry>

    // ---------------------------------------------------------------- 写入

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<DictionaryEntry>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: DictionaryEntry): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DictionaryEntry): Long

    @Delete
    suspend fun delete(entry: DictionaryEntry)

    @Query("DELETE FROM dictionary_entry WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM dictionary_entry WHERE type = :type")
    suspend fun deleteByType(type: WordType): Int

    @Query("DELETE FROM dictionary_entry WHERE source = :source")
    suspend fun deleteBySource(source: String): Int

    @Query(
        "UPDATE dictionary_entry SET word = :word, code = :code, frequency = :frequency, " +
            "type = :type, enabled = :enabled, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateFields(
        id: Long,
        word: String,
        code: String?,
        frequency: Int,
        type: WordType,
        enabled: Boolean,
        updatedAt: Long,
    )

    /** 墓碑：软删除，保留记录以便把删除同步出去 */
    @Query(
        "UPDATE dictionary_entry SET isDeleted = 1, deletedAt = :deletedAt, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun markDeleted(id: Long, deletedAt: Long, updatedAt: Long)

    /** 同步合并结果写回（整条覆盖，保留墓碑状态） */
    @Query(
        "UPDATE dictionary_entry SET code = :code, frequency = :frequency, type = :type, " +
            "enabled = :enabled, isDeleted = :isDeleted, deletedAt = :deletedAt, " +
            "source = :source, sourceDeviceId = :sourceDeviceId, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun updateFromSync(
        id: Long,
        code: String?,
        frequency: Int,
        type: WordType,
        enabled: Boolean,
        isDeleted: Boolean,
        deletedAt: Long?,
        source: String,
        sourceDeviceId: String?,
        updatedAt: Long,
    )

    @Query("UPDATE dictionary_entry SET frequency = frequency + 1, updatedAt = :updatedAt WHERE word = :word")
    suspend fun increaseFrequency(word: String, updatedAt: Long)
}
