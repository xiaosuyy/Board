package com.board.ime.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.board.ime.dictionary.WordType

/**
 * 词库条目（「我的词库」的统一存储）。
 *
 * 一份表承载四类词：
 * - 学习词：librime 用户词典的镜像（可从 userdb 同步，可清空）
 * - 自定义词 / 固定词：会同步到 librime 的 `custom_phrase`，输入时优先出现
 * - 屏蔽词：由 Board 在候选层过滤掉
 *
 * [code] 是编码（拼音，音节用空格分隔，如 `ni hao`）。
 */
@Entity(
    tableName = "dictionary_entry",
    indices = [
        Index(value = ["word"], unique = true),
        Index(value = ["language"]),
        Index(value = ["frequency"]),
        Index(value = ["type"]),
        Index(value = ["isDeleted"]),
    ],
)
data class DictionaryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val word: String,
    val code: String? = null,
    val language: String = "zh",
    val frequency: Int = 0,
    val enabled: Boolean = true,
    /** 词条类型，见 [WordType]；默认值必须与迁移脚本保持一致 */
    @ColumnInfo(defaultValue = "CUSTOM")
    val type: WordType = WordType.CUSTOM,
    /** local / seeded / imported / rime / remote / synced */
    val source: String = "local",
    /** 同步来源设备（远端词条带回来时记录） */
    val sourceDeviceId: String? = null,
    /**
     * 软删除（tombstone）。删除不立刻物理删，而是留一条墓碑，
     * 这样同步时「删除」也能传播出去，不会又被另一台设备恢复回来。
     */
    @ColumnInfo(defaultValue = "0")
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
