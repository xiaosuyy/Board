package com.board.ime.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.board.ime.clipboard.ClipboardContentStorage
import com.board.ime.clipboard.ClipboardOrigin
import com.board.ime.clipboard.ClipboardSummary
import com.board.ime.database.entity.ClipboardItem
import kotlinx.coroutines.flow.Flow

/**
 * 剪贴板 DAO。
 *
 * **规则：列表 / 搜索 / 去重一律用 [ClipboardSummary]，永远不要 `SELECT *`。**
 *
 * 原因：SQLite 用 `CursorWindow`（约 2MB）取数据，单行内容超过它就会
 * `Row too big to fit into CursorWindow`，而且会让**整个查询**失败。
 * 超长文本现在存在文件里（`contentStorage = FILE`），列表查询连 content 都不读，
 * 所以多大的文本都不会拖垮列表。
 *
 * 只有「取一条的完整内容」才走 [contentById] / [findById]。
 */
@Dao
interface ClipboardDao {

    // ---------------------------------------------------------------- 列表（无 content 投影）

    @Query(SUMMARY_COLUMNS + " WHERE isDeleted = 0 " +
        "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit")
    fun observeActive(limit: Int): Flow<List<ClipboardSummary>>

    @Query(SUMMARY_COLUMNS + " WHERE isDeleted = 0 " +
        "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit")
    suspend fun active(limit: Int): List<ClipboardSummary>

    /** 本地历史搜索：只做 SQLite LIKE，即时过滤，不联网；不加载完整内容 */
    @Query(SUMMARY_COLUMNS + " WHERE isDeleted = 0 AND (" + MATCH_COLUMNS + ") " +
        "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit")
    fun observeSearch(query: String, limit: Int): Flow<List<ClipboardSummary>>

    /** 只看某个来源（例如「云端」= REMOTE） */
    @Query(SUMMARY_COLUMNS + " WHERE isDeleted = 0 AND origin = :origin " +
        "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit")
    fun observeByOrigin(origin: ClipboardOrigin, limit: Int): Flow<List<ClipboardSummary>>

    /** 来源 + 关键字 */
    @Query(SUMMARY_COLUMNS + " WHERE isDeleted = 0 AND origin = :origin " +
        "AND (" + MATCH_COLUMNS + ") " +
        "ORDER BY isPinned DESC, isFavorite DESC, updatedAt DESC LIMIT :limit")
    fun observeSearchByOrigin(
        query: String,
        origin: ClipboardOrigin,
        limit: Int,
    ): Flow<List<ClipboardSummary>>

    @Query(SUMMARY_COLUMNS + " WHERE id = :id LIMIT 1")
    suspend fun findSummaryById(id: Long): ClipboardSummary?

    /**
     * 去重用：按内容哈希找已有条目（包含软删除的，方便复活）。
     * 只读元信息 —— 过去这里 `SELECT *`，一条超大记录就会把去重判断打挂。
     */
    @Query(SUMMARY_COLUMNS + " WHERE contentHash = :hash ORDER BY isDeleted ASC LIMIT 1")
    suspend fun findSummaryByHash(hash: String): ClipboardSummary?

    @Query(SUMMARY_COLUMNS + " WHERE isDeleted = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestSummary(): ClipboardSummary?

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0 AND isPinned = 0 AND isFavorite = 0")
    suspend fun countPlain(): Int

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0 AND isPinned = 1")
    suspend fun countPinned(): Int

    @Query("SELECT COUNT(*) FROM clipboard_item WHERE isDeleted = 0 AND isFavorite = 1 AND isPinned = 0")
    suspend fun countFavorite(): Int

    // ---------------------------------------------------------------- 单条完整内容

    @Query("SELECT * FROM clipboard_item WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ClipboardItem?

    /** 只取 content 一列（内联条目用；FILE 条目返回空串） */
    @Query("SELECT content FROM clipboard_item WHERE id = :id LIMIT 1")
    suspend fun contentById(id: Long): String?

    @Query("SELECT contentFilePath FROM clipboard_item WHERE id = :id LIMIT 1")
    suspend fun contentFilePathById(id: Long): String?

    /** 所有仍然是 INLINE 的行（用于按需把大内容搬到文件里） */
    @Query(
        "SELECT id, length(content) AS chars FROM clipboard_item " +
            "WHERE contentStorage = 'INLINE' AND length(content) > :minChars " +
            "ORDER BY length(content) DESC LIMIT :limit",
    )
    suspend fun inlineRowsLargerThan(minChars: Int, limit: Int): List<InlineRow>

    /** 所有文件型条目的路径（孤儿文件回收用） */
    @Query("SELECT contentFilePath FROM clipboard_item WHERE contentStorage = 'FILE'")
    suspend fun allFilePaths(): List<String>

    /** 需要补搜索索引的文件型条目（老数据升级后 content 已被清空） */
    @Query(
        "SELECT id, contentFilePath FROM clipboard_item " +
            "WHERE contentStorage = 'FILE' AND searchText = '' LIMIT :limit",
    )
    suspend fun fileRowsWithoutSearchText(limit: Int): List<FileRow>

    @Query("SELECT searchText FROM clipboard_item WHERE id = :id LIMIT 1")
    suspend fun searchTextOf(id: Long): String?

    @Query("UPDATE clipboard_item SET searchText = :text WHERE id = :id")
    suspend fun updateSearchText(id: Long, text: String)

    // ---------------------------------------------------------------- 维护（老数据收敛）

    @Query("SELECT * FROM clipboard_item WHERE contentHash = ''")
    suspend fun itemsWithEmptyHash(): List<ClipboardItem>

    @Query("UPDATE clipboard_item SET contentHash = :hash WHERE id = :id")
    suspend fun updateHash(id: Long, hash: String)

    @Query(
        "SELECT content FROM clipboard_item WHERE isDeleted = 0 AND contentStorage = 'INLINE' " +
            "GROUP BY content HAVING COUNT(*) > 1",
    )
    suspend fun duplicateContents(): List<String>

    @Query(
        "SELECT * FROM clipboard_item WHERE isDeleted = 0 AND contentStorage = 'INLINE' " +
            "AND content = :content ORDER BY updatedAt DESC",
    )
    suspend fun activeByContent(content: String): List<ClipboardItem>

    @Query(
        "UPDATE clipboard_item SET isPinned = :pinned, isFavorite = :favorite, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateFlags(id: Long, pinned: Boolean, favorite: Boolean, updatedAt: Long)

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

    /** 把一条记录从内联切换成文件（或反过来） */
    @Query(
        "UPDATE clipboard_item SET content = :content, contentStorage = :storage, " +
            "contentFilePath = :path, contentSize = :size, searchText = :searchText, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateContent(
        id: Long,
        content: String,
        storage: ClipboardContentStorage,
        path: String,
        size: Long,
        searchText: String,
        updatedAt: Long,
    )

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

    /** 内容按需搬到文件后，库里只剩路径：拿到「已永久删除」的行的文件路径，用于清文件 */
    @Query("SELECT contentFilePath FROM clipboard_item WHERE contentStorage = 'FILE' AND isDeleted = 1")
    suspend fun deletedFilePaths(): List<String>

    companion object {
        /** 列表投影的列：**没有 content**，这是本轮架构的关键 */
        const val SUMMARY_COLUMNS =
            "SELECT id, preview, contentType, contentHash, contentSize, contentStorage, " +
                "contentFilePath, origin, originType, isPinned, isFavorite, isDeleted, " +
                "createdAt, updatedAt FROM clipboard_item"

        /**
         * 搜索匹配条件。
         *
         * 必须同时看 `content`（内联条目）和 `searchText`（文件型条目的轻量索引），
         * 否则超长文本会「搜不到」。
         */
        const val MATCH_COLUMNS =
            "content LIKE '%' || :query || '%' OR searchText LIKE '%' || :query || '%'"
    }
}

/** `length(content)` 的查询结果 */
data class InlineRow(
    val id: Long,
    val chars: Int,
)

/** 文件型条目里需要补搜索索引的行 */
data class FileRow(
    val id: Long,
    val contentFilePath: String,
)
