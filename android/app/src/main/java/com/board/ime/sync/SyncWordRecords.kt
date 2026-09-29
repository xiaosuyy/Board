package com.board.ime.sync

import com.board.ime.dictionary.WordType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 词库同步的**统一记录**。
 *
 * 一个结构承载三种同步实体，字段对得上用户要求：
 * - [WordType.LEARNED] → 学习词（word / pinyin / frequency / createdAt / updatedAt / sourceDeviceId）
 * - [WordType.PHRASE] → 常用语
 * - [WordType.CUSTOM] / [WordType.PINNED] / [WordType.BLOCKED] → 自定义 / 固定 / 屏蔽词
 *
 * 墓碑：`deleted = true` 表示这条被删除了，用来把「删除」传播到别的设备，
 * 避免下次同步又被另一端恢复回来。
 */
data class SyncWordRecord(
    val word: String,
    val code: String? = null,
    val frequency: Int = 0,
    val type: String = WordType.CUSTOM.name,
    val enabled: Boolean = true,
    val deleted: Boolean = false,
    val sourceDeviceId: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    /** 合并用的键：**同词不同拼音算两条**，不会被互相覆盖 */
    val key: String get() = word + "\u0000" + (code ?: "")
}

/** 一个同步文件（一种实体一个文件，不塞进一个巨大 JSON） */
data class SyncWordFile(
    val schema: Int = SCHEMA,
    val device: String? = null,
    val exportedAt: Long = 0L,
    val words: List<SyncWordRecord> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

/** 词库同步文件 ⇄ JSON */
object SyncWordCodec {

    fun toJson(file: SyncWordFile): String = JSONObject().apply {
        put("schema", file.schema)
        put("device", file.device ?: JSONObject.NULL)
        put("exportedAt", file.exportedAt)
        put("words", JSONArray().apply {
            file.words.forEach { record ->
                put(
                    JSONObject().apply {
                        put("word", record.word)
                        put("code", record.code ?: JSONObject.NULL)
                        put("frequency", record.frequency)
                        put("type", record.type)
                        put("enabled", record.enabled)
                        put("deleted", record.deleted)
                        put("sourceDeviceId", record.sourceDeviceId ?: JSONObject.NULL)
                        put("createdAt", record.createdAt)
                        put("updatedAt", record.updatedAt)
                    },
                )
            }
        })
    }.toString()

    /** 解析失败返回 null（调用方安全忽略，不动本地数据） */
    fun fromJson(json: String): SyncWordFile? = try {
        val root = JSONObject(json)
        val array = root.optJSONArray("words") ?: JSONArray()
        val words = ArrayList<SyncWordRecord>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val word = item.optString("word")
            if (word.isEmpty()) continue
            words += SyncWordRecord(
                word = word,
                code = item.optString("code").takeIf { it.isNotEmpty() && !item.isNull("code") },
                frequency = item.optInt("frequency", 0),
                type = item.optString("type", WordType.CUSTOM.name),
                enabled = item.optBoolean("enabled", true),
                deleted = item.optBoolean("deleted", false),
                sourceDeviceId = item.optString("sourceDeviceId")
                    .takeIf { it.isNotEmpty() && !item.isNull("sourceDeviceId") },
                createdAt = item.optLong("createdAt", 0L),
                updatedAt = item.optLong("updatedAt", 0L),
            )
        }
        SyncWordFile(
            schema = root.optInt("schema", SyncWordFile.SCHEMA),
            device = root.optString("device").takeIf { it.isNotEmpty() && !root.isNull("device") },
            exportedAt = root.optLong("exportedAt", 0L),
            words = words,
        )
    } catch (throwable: Throwable) {
        null
    }
}

/**
 * 合并规则（明确、可预测，**不做设备之间互相覆盖**）：
 *
 * 1. 键 = `词条 + 编码` —— 同词不同拼音是两条，不会被错误覆盖
 * 2. 只有一边有 → 采用它（包括墓碑）
 * 3. 两边都有：
 *    - 一侧是墓碑，且它的 `updatedAt` 不比另一侧旧 → **删除生效**（不会被恢复）
 *    - 一侧是墓碑但更旧 → 新的一条（活着的）胜出（说明是删完又加回来的）
 *    - 都是活的 → `frequency` 取**最大值**（保留并合并学习信息），
 *      `enabled` / `code` / `type` 取 `updatedAt` 较新的一侧
 *
 * 结果按 (type, word) 排序，保证同样的输入产生同样的输出（便于测试与去重）。
 */
object SyncWordMerge {

    fun merge(local: List<SyncWordRecord>, remote: List<SyncWordRecord>): List<SyncWordRecord> {
        val merged = LinkedHashMap<String, SyncWordRecord>()
        local.forEach { merged[it.key] = it }

        remote.forEach { incoming ->
            val existing = merged[incoming.key]
            merged[incoming.key] = if (existing == null) incoming else resolve(existing, incoming)
        }
        return merged.values.sortedWith(compareBy({ it.type }, { it.word }, { it.code ?: "" }))
    }

    private fun resolve(local: SyncWordRecord, remote: SyncWordRecord): SyncWordRecord {
        val isLearned = local.type == WordType.LEARNED.name || remote.type == WordType.LEARNED.name

        // 学习词是「积累出来的证据」：只要有一端还活着就保留，词频取最大。
        // （A 设备 frequency 10 + B 设备 6 → 10；不会因为对端删过就消失）
        if (isLearned) {
            if (!local.deleted && !remote.deleted) {
                return newerOf(local, remote).copy(frequency = maxOf(local.frequency, remote.frequency))
            }
            if (!local.deleted) return local.copy(frequency = maxOf(local.frequency, remote.frequency))
            if (!remote.deleted) return remote.copy(frequency = maxOf(local.frequency, remote.frequency))
            return newerOf(local, remote)
        }

        // 词库条目：墓碑按时间判定 —— 删除不会被另一端恢复（除非本地是删完又加回来的）
        if (local.deleted != remote.deleted) {
            val tombstone = if (local.deleted) local else remote
            val alive = if (local.deleted) remote else local
            return if (tombstone.updatedAt >= alive.updatedAt) tombstone else alive
        }
        if (local.deleted && remote.deleted) return newerOf(local, remote)

        // 都是活着的：合并学习信息（取较大词频），其余字段听较新的一条
        val newer = newerOf(local, remote)
        val older = if (local.updatedAt >= remote.updatedAt) remote else local
        return newer.copy(
            frequency = maxOf(local.frequency, remote.frequency),
            code = newer.code ?: older.code,
            sourceDeviceId = newer.sourceDeviceId ?: older.sourceDeviceId,
            createdAt = minOf(
                local.createdAt.takeIf { it > 0 } ?: Long.MAX_VALUE,
                remote.createdAt.takeIf { it > 0 } ?: Long.MAX_VALUE,
            ).takeIf { it != Long.MAX_VALUE } ?: 0L,
        )
    }

    private fun newerOf(local: SyncWordRecord, remote: SyncWordRecord): SyncWordRecord =
        if (local.updatedAt >= remote.updatedAt) local else remote
}
