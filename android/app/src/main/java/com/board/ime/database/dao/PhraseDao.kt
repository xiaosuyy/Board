package com.board.ime.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.board.ime.database.entity.PhraseEntry
import kotlinx.coroutines.flow.Flow

/**
 * 常用语 DAO。
 *
 * 界面只读**非墓碑**条目；同步用 [all]（含墓碑）以保证删除能传播。
 * 身份一律用 `phraseId`，本地自增 `id` 不参与同步。
 */
@Dao
interface PhraseDao {

    // ---------------------------------------------------------------- 界面

    /** 正常列表：墓碑不显示；按用户顺序，其次按更新时间 */
    @Query(
        "SELECT * FROM phrase_entry WHERE deletedAt IS NULL " +
            "ORDER BY sortOrder ASC, updatedAt DESC",
    )
    fun observeActive(): Flow<List<PhraseEntry>>

    @Query("SELECT COUNT(*) FROM phrase_entry WHERE deletedAt IS NULL")
    suspend fun countActive(): Int

    // ---------------------------------------------------------------- 同步 / 维护

    /** 同步专用：**连墓碑一起**取 */
    @Query("SELECT * FROM phrase_entry ORDER BY sortOrder ASC, updatedAt DESC")
    suspend fun all(): List<PhraseEntry>

    @Query("SELECT * FROM phrase_entry WHERE phraseId = :phraseId LIMIT 1")
    suspend fun findByPhraseId(phraseId: String): PhraseEntry?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: PhraseEntry): Long

    @Query(
        "UPDATE phrase_entry SET text = :text, updatedAt = :updatedAt, " +
            "sourceDeviceId = :sourceDeviceId WHERE phraseId = :phraseId",
    )
    suspend fun updateText(
        phraseId: String,
        text: String,
        updatedAt: Long,
        sourceDeviceId: String?,
    )

    @Query(
        "UPDATE phrase_entry SET deletedAt = :deletedAt, updatedAt = :updatedAt " +
            "WHERE phraseId = :phraseId",
    )
    suspend fun markDeleted(phraseId: String, deletedAt: Long?, updatedAt: Long)

    /**
     * 用户主动排序后写回：**同时更新 updatedAt**。
     *
     * 只改 sortOrder 而不动 updatedAt，会导致合并时"本端不一定胜出"，
     * 多设备顺序就永远收敛不了（这正是之前的 bug）。
     */
    @Query(
        "UPDATE phrase_entry SET sortOrder = :sortOrder, updatedAt = :updatedAt " +
            "WHERE phraseId = :phraseId",
    )
    suspend fun updateSortOrder(phraseId: String, sortOrder: Int, updatedAt: Long)

    /** 一次排序操作 = **一个事务 + 一个时间戳**（不逐条写、不逐条改时间） */
    @androidx.room.Transaction
    suspend fun applyOrder(orderedIds: List<String>, changed: List<String>, updatedAt: Long) {
        changed.forEach { phraseId ->
            val index = orderedIds.indexOf(phraseId)
            if (index >= 0) updateSortOrder(phraseId, index, updatedAt)
        }
    }

    /** 同步落库：整条覆盖（含 sortOrder / 墓碑 / 时间戳） */
    @Query(
        "UPDATE phrase_entry SET text = :text, sortOrder = :sortOrder, deletedAt = :deletedAt, " +
            "createdAt = :createdAt, updatedAt = :updatedAt, sourceDeviceId = :sourceDeviceId " +
            "WHERE phraseId = :phraseId",
    )
    suspend fun overwrite(
        phraseId: String,
        text: String,
        sortOrder: Int,
        deletedAt: Long?,
        createdAt: Long,
        updatedAt: Long,
        sourceDeviceId: String?,
    )

    /** 墓碑不该无限增长：很老的墓碑可以物理清掉（默认不动，留给仓库决定） */
    @Query("DELETE FROM phrase_entry WHERE deletedAt IS NOT NULL AND deletedAt < :before")
    suspend fun purgeDeletedBefore(before: Long): Int
}
