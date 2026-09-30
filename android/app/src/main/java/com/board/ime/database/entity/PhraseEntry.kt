package com.board.ime.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 常用语条目（**独立表**，与词库 `dictionary_entry` 完全分开）。
 *
 * 为什么不用 dictionary_entry：
 * - 那张表对 `word` 建了**唯一索引**，存不下两条一模一样的「谢谢」；
 *   而常用语必须允许「不同 id、正文完全相同」并存；
 * - 常用语还需要 `sortOrder`（用户拖动排序），词库语义里没有这个概念。
 *
 * 身份规则（重要）：
 * - [id]：**本地自增主键**，只在本机有意义，**绝不用于跨设备识别**；
 * - [phraseId]：**跨设备稳定 ID（UUID 字符串）**，同步层唯一身份。
 *   两台设备各自自增出 `id=1` 也绝不会被当成同一条。
 */
@Entity(
    tableName = "phrase_entry",
    indices = [
        Index(value = ["phraseId"], unique = true),
        Index(value = ["deletedAt"]),
        Index(value = ["sortOrder"]),
    ],
)
data class PhraseEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /** 跨设备稳定 ID；不同 phraseId 即使 text 完全相同也允许并存 */
    val phraseId: String,
    /** 常用语正文（允许多行、较长文本） */
    val text: String,
    /** 用户排列顺序，参与同步 */
    val sortOrder: Int = 0,
    /** 软删除墓碑：非空表示已删除，仍参与同步（删除要能传播） */
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 远端带回来的来源设备（本机为空） */
    val sourceDeviceId: String? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}
