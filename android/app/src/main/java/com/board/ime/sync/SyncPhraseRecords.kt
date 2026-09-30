package com.board.ime.sync

import com.board.ime.database.entity.PhraseEntry
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 常用语同步记录（`board_phrases.json`）。
 *
 * 与词库共用一个文件不现实，但**沿用同一套信封**（schema / device / exportedAt），
 * 条目放在 `phrases[]` 里；读取时**同时兼容**旧版写在 `words[]` 里的记录。
 *
 * 身份：**只认 phraseId**。本地 Room 自增 id、word、word+code 都**不是**身份。
 *
 * 兼容旧文件（没有 phraseId / sortOrder）：
 * - `word` → `text`；
 * - `deleted`(bool) 或 `deletedAt` → 墓碑；
 * - 缺 phraseId 时**按稳定信息派生**一个（不是随机 UUID），否则每次 Pull 都会
 *   生成新 id → 无限重复；
 * - **已知限制**：旧格式的身份键是 `word+code`，因此旧文件里两条**完全一样**的
 *   PHRASE 无法区分，会合并成一条（新格式没有这个问题）。
 */
data class SyncPhraseRecord(
    val phraseId: String,
    val text: String,
    val sortOrder: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val deletedAt: Long? = null,
    val sourceDeviceId: String? = null,
) {
    val deleted: Boolean get() = deletedAt != null
}

object SyncPhraseCodec {

    const val SCHEMA = 1

    fun toJson(device: String?, records: List<SyncPhraseRecord>): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("device", device ?: JSONObject.NULL)
        put("exportedAt", System.currentTimeMillis())
        // **墓碑必须写出去**，否则别的设备永远不知道这条被删过
        put("phrases", JSONArray().apply {
            records.forEach { record ->
                put(
                    JSONObject().apply {
                        put("phraseId", record.phraseId)
                        put("text", record.text)
                        put("sortOrder", record.sortOrder)
                        put("createdAt", record.createdAt)
                        put("updatedAt", record.updatedAt)
                        put("deletedAt", record.deletedAt ?: JSONObject.NULL)
                        put("sourceDeviceId", record.sourceDeviceId ?: JSONObject.NULL)
                    },
                )
            }
        })
    }.toString()

    /** 解析失败返回 null（调用方安全忽略，不动本地数据） */
    fun fromJson(json: String): List<SyncPhraseRecord>? = try {
        val root = JSONObject(json)
        val newArray = root.optJSONArray("phrases")
        val legacyArray = root.optJSONArray("words")
        val records = ArrayList<SyncPhraseRecord>()
        if (newArray != null) {
            for (i in 0 until newArray.length()) {
                val item = newArray.optJSONObject(i) ?: continue
                val text = item.optString("text").ifEmpty { item.optString("word") }
                if (text.isEmpty()) continue
                records += SyncPhraseRecord(
                    phraseId = item.optString("phraseId").takeIf { it.isNotBlank() }
                        ?: legacyPhraseId(text, item.optString("code").takeIf { it.isNotBlank() }),
                    text = text,
                    sortOrder = item.optInt("sortOrder", 0),
                    createdAt = item.optLong("createdAt", 0L),
                    updatedAt = item.optLong("updatedAt", 0L),
                    deletedAt = optDeletedAt(item),
                    sourceDeviceId = item.optString("sourceDeviceId")
                        .takeIf { it.isNotBlank() && !item.isNull("sourceDeviceId") },
                )
            }
        }
        if (records.isEmpty() && legacyArray != null) {
            // 旧版：本文件由词库同步写入，只有 word/code/deleted/时间戳
            for (i in 0 until legacyArray.length()) {
                val item = legacyArray.optJSONObject(i) ?: continue
                if (item.optString("type").takeIf { it.isNotBlank() } != null &&
                    item.optString("type") != "PHRASE"
                ) {
                    continue
                }
                val text = item.optString("word").ifEmpty { item.optString("text") }
                if (text.isEmpty()) continue
                val code = item.optString("code").takeIf { it.isNotBlank() }
                records += SyncPhraseRecord(
                    phraseId = legacyPhraseId(text, code),
                    text = text,
                    sortOrder = item.optInt("sortOrder", 0),
                    createdAt = item.optLong("createdAt", 0L),
                    updatedAt = item.optLong("updatedAt", 0L),
                    deletedAt = optDeletedAt(item),
                    sourceDeviceId = item.optString("sourceDeviceId")
                        .takeIf { it.isNotBlank() && !item.isNull("sourceDeviceId") },
                )
            }
        }
        records
    } catch (throwable: Throwable) {
        null
    }

    private fun optDeletedAt(item: JSONObject): Long? {
        if (!item.isNull("deletedAt")) {
            val raw = item.optLong("deletedAt", 0L)
            if (raw > 0L) return raw
        }
        // 旧字段：deleted=true 但没有时间戳
        return if (item.optBoolean("deleted", false)) {
            item.optLong("updatedAt", 0L).takeIf { it > 0L } ?: 1L
        } else {
            null
        }
    }

    /**
     * 旧记录（没有 phraseId）派生稳定 ID。
     *
     * 依据是旧格式里唯一稳定且两端一致的键：`word + code`。
     * 确定性派生 ⇒ 每次 Pull 得到同一个 ID，不会不断产生重复。
     */
    fun legacyPhraseId(text: String, code: String?): String {
        val raw = text + "\u0000" + (code ?: "")
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return "legacy-" + digest.joinToString("") { "%02x".format(it) }.take(24)
    }
}

/**
 * 常用语合并（**完全按 phraseId**，不看 word / word+code / Room id）。
 *
 * 规则与词库一致：
 * 1. 只有一边有 → 采用（含墓碑）；
 * 2. 一边墓碑 → `deletedAt/updatedAt` 较新者胜（墓碑不会被旧的活数据复活）；
 * 3. 都是活的 → `updatedAt` 较新者胜；
 * 4. 不同 phraseId → 各自保留（**同文也绝不合并**）；
 * 5. 结果按 `sortOrder → updatedAt → phraseId` 稳定排序：同样的输入必然同样的输出。
 */
object SyncPhraseMerge {

    fun normalize(records: List<SyncPhraseRecord>): List<SyncPhraseRecord> =
        records.sortedWith(
            compareBy({ it.sortOrder }, { it.updatedAt }, { it.phraseId }),
        )

    fun merge(
        local: List<SyncPhraseRecord>,
        remote: List<SyncPhraseRecord>,
    ): List<SyncPhraseRecord> {
        val merged = LinkedHashMap<String, SyncPhraseRecord>()
        local.forEach { merged[it.phraseId] = it }
        remote.forEach { incoming ->
            val existing = merged[incoming.phraseId]
            merged[incoming.phraseId] = if (existing == null) incoming else resolve(existing, incoming)
        }
        return normalize(merged.values.toList())
    }

    /** 远端是否已经等于合并结果（相等就不需要回写，避免 Pull→Push 无限循环） */
    fun needsPush(local: List<SyncPhraseRecord>, remote: List<SyncPhraseRecord>): Boolean =
        merge(local, remote) != normalize(remote)

    private fun resolve(local: SyncPhraseRecord, remote: SyncPhraseRecord): SyncPhraseRecord {
        if (local.deleted != remote.deleted) {
            val tombstone = if (local.deleted) local else remote
            val alive = if (local.deleted) remote else local
            // 墓碑只有在不比活数据旧的时候才生效（删完又加回来的情况由活数据胜出）
            return if (tombstone.updatedAt >= alive.updatedAt) tombstone else alive
        }
        val newer = if (local.updatedAt >= remote.updatedAt) local else remote
        val older = if (local.updatedAt >= remote.updatedAt) remote else local
        return newer.copy(
            createdAt = minOf(
                local.createdAt.takeIf { it > 0 } ?: Long.MAX_VALUE,
                remote.createdAt.takeIf { it > 0 } ?: Long.MAX_VALUE,
            ).takeIf { it != Long.MAX_VALUE } ?: 0L,
            sourceDeviceId = newer.sourceDeviceId ?: older.sourceDeviceId,
        )
    }
}

/** 数据库实体 ⇄ 同步记录 */
fun PhraseEntry.toSyncRecord(): SyncPhraseRecord = SyncPhraseRecord(
    phraseId = phraseId,
    text = text,
    sortOrder = sortOrder,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    sourceDeviceId = sourceDeviceId,
)

fun SyncPhraseRecord.toEntry(): PhraseEntry = PhraseEntry(
    id = 0L,
    phraseId = phraseId,
    text = text,
    sortOrder = sortOrder,
    deletedAt = deletedAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    sourceDeviceId = sourceDeviceId,
)
