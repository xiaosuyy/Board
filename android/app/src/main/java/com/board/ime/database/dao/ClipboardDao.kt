package com.board.ime.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.board.ime.clipboard.ClipboardOrigin
import com.board.ime.database.entity.ClipboardItem
import kotlinx.coroutines.flow.Flow

@Dao
interface ClipboardDao {

    // ---------------------------------------------------------------- 读取

    /** 固定 → 收藏 → 最近（同组内按最近使用时间倒序） */
    @Query(
        "SELECT * FROM clipboard_item WHERE isDeleted = 0 " +
            "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit",
    )
    fun observeActive(limit: Int): Flow<List<ClipboardItem>>

    @Query(
        "SELECT * FROM clipboard_item WHERE isDeleted = 0 " +
            "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit",
    )
    suspend fun active(limit: Int): List<ClipboardItem>

    /** 本地历史搜索：只做 SQLite LIKE，即时过滤，不联网 */
    @Query(
        "SELECT * FROM clipboard_item WHERE isDeleted = 0 AND content LIKE '%' || :query || '%' " +
            "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit",
    )
    fun observeSearch(query: String, limit: Int): Flow<List<ClipboardItem>>

    /** 只看某个来源（例如「云端」= REMOTE） */
    @Query(
        "SELECT * FROM clipboard_item WHERE isDeleted = 0 AND origin = :origin " +
            "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit",
    )
    fun observeByOrigin(origin: ClipboardOrigin, limit: Int): Flow<List<ClipboardItem>>

    /** 来源 + 关键字 */
    @Query(
        "SELECT * FROM clipboard_item WHERE isDeleted = 0 AND origin = :origin " +
            "AND content LIKE '%' || :query || '%' " +
            "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit",
    )
    fun observeSearchByOrigin(
        query: String,
        origin: ClipboardOrigin,
        limit: Int,
    ): Flow<List<ClipboardItem>>

    @Query("SELECT * FROM clipboard_item WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ClipboardItem?

    /** 去重用：按内容哈希找已有条目（包含软删除的，方便复活） */
    @Query("SELECT * FROM clipboard_item WHERE contentHash = :hash ORDER BY isDeleted ASC LIMIT 1")
    suspend fun findByHash(hash: String): ClipboardItem?

    @Query("SELECT * FROM clipboard_item WHERE isDeleted = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestActive(): ClipboardItem?

    @Query("SELECT * FROM clipboard_item WHERE contentHash = ''")
    suspend fun itemsWithEmptyHash(): List<ClipboardItem>

    @Query("UPDATE clipboard_item SET contentHash = :hash WHERE id = :id")
    suspend fun updateHash(id: Long, hash: String)

    @Query(
        "SELECT content FROM clipboard_item WHERE isDeleted = 0 " +
            "GROUP BY content HAVING COUNT(*) > 1",
    )
    suspend fun duplicateContents(): List<String>

    @Query(
        "SELECT * FROM clipboard_item WHERE isDeleted = 0 AND content = :content " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun activeByContent(content: String): List<ClipboardItem>

    @Query(
        "UPDATE clipboard_item SET isPinned = :pinned, isFavorite = :favorite, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateFlags(id: Long, pinned: Boolean, favorite: Boolean, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0 AND isPinned = 0 AND isFavorite = 0")
    suspend fun countPlain(): Int

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0 AND isPinned = 1")
    suspend fun countPinned(): Int

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0 AND isFavorite = 1 AND isPinned = 0")
    suspend fun countFavorite(): Int

    // ---------------------------------------------------------------- 写入

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ClipboardItem): Long

    /** 同一内容再次被复制：复活软删除 + 刷新「最近使用时间」 */
    @Query(
        "UPDATE clipboard_item SET isDeleted = 0, deletedAt = NULL, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun touchAndRestore(id: Long, updatedAt: Long)

    /** 插入 / 打开剪贴板时把条目顶到最前 */
    @Query("UPDATE clipboard_item SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE clipboard_item SET isPinned = :pinned, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE clipboard_item SET isFavorite = :favorite, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateFavorite(id: Long, favorite: Boolean, updatedAt: Long)

    @Query(
        "UPDATE clipboard_item SET isDeleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun markDeleted(id: Long, deletedAt: Long, updatedAt: Long)

    /** 清空：只删「普通」条目，固定 / 收藏保留 */
    @Query(
        "UPDATE clipboard_item SET isDeleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt " +
            "WHERE isDeleted = 0 AND isPinned = 0 AND isFavorite = 0",
    )
    suspend fun markAllPlainDeleted(deletedAt: Long, updatedAt: Long): Int

    /** 清空：连固定 / 收藏一起删（需要用户显式勾选） */
    @Query(
        "UPDATE clipboard_item SET isDeleted = 1, deletedAt = :deletedAt, updatedAt = :updatedAt " +
            "WHERE isDeleted = 0",
    )
    suspend fun markAllDeleted(deletedAt: Long, updatedAt: Long): Int

    @Query("DELETE FROM clipboard_item WHERE isDeleted = 1")
    suspend fun purgeDeleted(): Int
}
