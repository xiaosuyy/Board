package com.board.ime.dictionary

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 大型词库**数据源**。
 *
 * 一个「导入的 60 万条词库」应该是一行 source，而不是 60 万行词条：
 * 词条本体存在独立文件里，Room 只记录这份元信息。
 * 这样：
 * - Room 不会因为导入大词库而膨胀（之前 60 万行 = 111MB）
 * - WebDAV 同步不会去扫描 / 上传整份大词库
 *
 * 小型词表（用户真正手写 / 少量导入的）仍然进
 * [com.board.ime.database.entity.DictionaryEntry]。
 */
@Entity(
    tableName = "dictionary_source",
    indices = [
        Index(value = ["type"]),
        Index(value = ["enabled"]),
        // 必须与 MIGRATION_6_7 创建的索引一致：Room 会逐项比对索引，
        // 迁移里建了但实体没声明 → "Migration didn't properly handle"（真机上踩过）
        Index(value = ["sourceId"]),
    ],
)
data class DictionarySource(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    /**
     * 稳定 ID：由名字派生（`sha256(name)` 前 16 位），**两台设备对同一个词库得到同一个值**。
     *
     * 用它当 WebDAV 上的目录名（而不是本地自增主键），A 上传、B 下载后能对上同一个词库，
     * 不会出现「同一份词库在云端有两个目录」。
     */
    @ColumnInfo(defaultValue = "")
    val sourceId: String = "",
    /** 展示名，例如 `names.dict.txt` */
    val name: String,
    /** 来源类型，见 [DictionarySourceType] */
    @ColumnInfo(defaultValue = "IMPORTED")
    val type: DictionarySourceType = DictionarySourceType.IMPORTED,
    /** 文件绝对路径（Board 私有目录） */
    val path: String,
    /** 文件内容 SHA-256，用于校验与迁移前后比对 */
    @ColumnInfo(defaultValue = "")
    val hash: String = "",
    /** 词条数量 */
    @ColumnInfo(defaultValue = "0")
    val entryCount: Int = 0,
    /** 文件字节数 */
    @ColumnInfo(defaultValue = "0")
    val sizeBytes: Long = 0L,
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** 原始文件格式：TXT / CSV / JSON / RIME_DICT */
    @ColumnInfo(defaultValue = "RIME_DICT")
    val format: String = "RIME_DICT",
    /** 内容版本号：内容变化时 +1，用于「云端更新 → 本地切换」的判断 */
    @ColumnInfo(defaultValue = "1")
    val version: Long = 1L,
    /** 是否已经生成 Rime 词典配置并部署成功（只有 ready 才算真正可用） */
    @ColumnInfo(defaultValue = "0")
    val ready: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 云端目录名：sourceId 为空（老数据）时退化用主键，保证不会写到空路径 */
    val remoteId: String get() = sourceId.ifBlank { "source-$id" }

    /** 显示用的大小 */
    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024L * 1024L -> "%.1f MB".format(sizeBytes / 1024.0 / 1024.0)
            sizeBytes >= 1024L -> "%.1f KB".format(sizeBytes / 1024.0)
            else -> "$sizeBytes B"
        }
}

/** 数据源类型：目前只有「导入的大词库」和「系统词库」占位 */
enum class DictionarySourceType {
    /** 系统 / Rime 原始词库（不进入 Board 用户词表） */
    SYSTEM,

    /** 用户导入的大型词库 */
    IMPORTED,
}
