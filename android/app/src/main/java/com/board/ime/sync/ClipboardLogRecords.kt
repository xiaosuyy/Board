package com.board.ime.sync

import com.board.ime.clipboard.SyncClipboardMapper
import org.json.JSONArray
import org.json.JSONObject

/**
 * 增量 Clipboard History 的一条事件（B1，只做 Text）。
 *
 * 与 `SyncClipboard.json` 的 latest-state **完全并存、互不影响**：
 * - latest-state 决定「当前剪贴板是什么」；
 * - 本日志决定「最近产生过哪些不同的 Text」，供对端补齐 History。
 */
data class ClipboardLogEvent(
    val seq: Long,
    val itemId: String,
    val hash: String,
    val type: String = ClipboardLogRecords.TYPE_TEXT,
    val createdAt: Long,
    val size: Long,
    /** ≤256 KiB 的 Text 正文**内联**；其余类型 / 超长一律不产生 event（此处为 null） */
    val text: String? = null,
)

/** 一台设备一份的增量历史日志（`/clipboard-log/<deviceId>.json`） */
data class ClipboardLogFile(
    val deviceId: String,
    val deviceType: String = "",
    val updatedAt: Long = 0L,
    /** 已被裁剪掉的最小 seq（消费方据此判断「是否漏了历史」） */
    val minSeq: Long = 0L,
    val schema: Int = 1,
    val events: List<ClipboardLogEvent> = emptyList(),
)

/**
 * 增量 Clipboard History 的**纯逻辑 + 编解码**（B1）。
 *
 * 必须与 Windows 端 `ClipboardLog.cs` 语义逐条对齐（跨端同一份断言）。
 *
 * 与 Cloud Library **严格隔离**：不同目录（`clipboard-log/` vs `library/`）、
 * 不同 TTL（7d vs 24h）、不同删除语义。
 */
object ClipboardLogRecords {

    const val FOLDER = "clipboard-log"
    const val TYPE_TEXT = "Text"

    /** 每设备日志最多保留的事件数 */
    const val MAX_EVENTS = 200

    /** 每设备日志的保留时长（7 天，远大于 Clipboard 的 1h） */
    const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L

    fun pathFor(deviceId: String?): String = "$FOLDER/${sanitizeToken(deviceId)}.json"

    /** 设备 id 安全化（只留字母数字与 `-`，最长 64） */
    fun sanitizeToken(value: String?): String {
        if (value.isNullOrEmpty()) return "device"
        val builder = StringBuilder()
        for (c in value) {
            val ok = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-'
            if (ok && builder.length < 64) builder.append(c)
        }
        return if (builder.isEmpty()) "device" else builder.toString()
    }

    /** `itemId = deviceId:seq`（全局唯一） */
    fun itemIdFor(deviceId: String?, seq: Long): String = "${sanitizeToken(deviceId)}:$seq"

    /**
     * **是否产生 history event**：只做 Text，且 ≤ 256 KiB（与内联上限一致）。
     * Image / File / 超长 Text 一律**不产生**（B1 范围；File 永不自动上传）。
     */
    fun shouldRecord(type: String?, size: Long): Boolean =
        type == TYPE_TEXT && size > 0 && size <= SyncClipboardMapper.INLINE_TEXT_MAX_BYTES

    /** 只取 `seq > lastSeenSeq` 的事件，按 seq 升序（消费方逐个处理） */
    fun candidatesFor(lastSeenSeq: Long, file: ClipboardLogFile?): List<ClipboardLogEvent> =
        file?.events
            ?.filter { it.seq > lastSeenSeq }
            ?.sortedBy { it.seq }
            ?: emptyList()

    /**
     * 水位推进：**必须先收集成功处理过的 seq，全部成功后才一次性推进**；
     * 中途失败（allSucceeded=false）⇒ 保持原水位，下次重来。
     */
    fun advanceWatermark(current: Long, processed: List<Long>, allSucceeded: Boolean): Long {
        if (!allSucceeded || processed.isEmpty()) return current
        return maxOf(current, processed.max())
    }

    /** 是否检测到「日志缺档」（`minSeq` 已经超过我下一次该读的位置） */
    fun gapDetected(lastSeenSeq: Long, file: ClipboardLogFile?): Boolean =
        file != null && file.minSeq > lastSeenSeq + 1

    /**
     * 只裁剪**本设备自己的**日志：先按保留时长，再按最大条数；
     * 更新 `minSeq` 且**不重排 seq**（保持原有 seq 值）。
     */
    fun compact(file: ClipboardLogFile, now: Long): ClipboardLogFile {
        val kept = file.events
            .filter { now - it.createdAt <= RETENTION_MS }
            .sortedBy { it.seq }
        val trimmed = if (kept.size > MAX_EVENTS) kept.subList(kept.size - MAX_EVENTS, kept.size) else kept
        return file.copy(
            updatedAt = now,
            minSeq = trimmed.firstOrNull()?.seq ?: file.minSeq,
            events = trimmed.toList(),
        )
    }

    /**
     * 崩溃窗口防御（seq 复用）。
     *
     * 场景：seq=N 已成功 PUT 到远端，但在持久化 seq 前进程崩溃；
     * 重启后本地水位落后，于是**又分配了 N** ⇒ itemId 冲突。
     *
     * 处理：远端已存在同 itemId 但 hash **不同** ⇒ 换一个全新 seq。
     * 绝不丢弃内容、绝不覆盖远端已发布的事件。
     */
    fun reassignReusedSequences(
        batch: List<ClipboardLogEvent>,
        remoteEvents: List<ClipboardLogEvent>,
        deviceId: String?,
        sequence: ClipboardLogSequence,
    ): List<ClipboardLogEvent> {
        if (batch.isEmpty()) return batch
        val remoteHashById = HashMap<String, String>()
        for (event in remoteEvents) {
            if (event.itemId.isEmpty()) continue
            remoteHashById.putIfAbsent(event.itemId, event.hash)
        }
        return batch.map { event ->
            val remoteHash = remoteHashById[event.itemId]
            if (remoteHash != null && remoteHash != event.hash) {
                val fresh = sequence.next()
                event.copy(seq = fresh, itemId = itemIdFor(deviceId, fresh))
            } else {
                event
            }
        }
    }

    fun serialize(file: ClipboardLogFile): String {
        val root = JSONObject()
        root.put("schema", file.schema)
        root.put("deviceId", file.deviceId)
        root.put("deviceType", file.deviceType)
        root.put("updatedAt", file.updatedAt)
        root.put("minSeq", file.minSeq)
        val array = JSONArray()
        for (event in file.events) {
            array.put(
                JSONObject().apply {
                    put("seq", event.seq)
                    put("itemId", event.itemId)
                    put("hash", event.hash)
                    put("type", event.type)
                    put("createdAt", event.createdAt)
                    put("size", event.size)
                    put("text", event.text ?: JSONObject.NULL)
                },
            )
        }
        root.put("events", array)
        return root.toString()
    }

    fun parse(json: String?): ClipboardLogFile? {
        if (json.isNullOrEmpty()) return null
        return try {
            val root = JSONObject(json)
            val array = root.optJSONArray("events") ?: JSONArray()
            val events = ArrayList<ClipboardLogEvent>(array.length())
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                events.add(
                    ClipboardLogEvent(
                        seq = item.optLong("seq"),
                        itemId = item.optString("itemId"),
                        hash = item.optString("hash"),
                        type = item.optString("type").ifEmpty { TYPE_TEXT },
                        createdAt = item.optLong("createdAt"),
                        size = item.optLong("size"),
                        text = if (item.isNull("text")) null else item.optString("text"),
                    ),
                )
            }
            ClipboardLogFile(
                deviceId = root.optString("deviceId"),
                deviceType = root.optString("deviceType"),
                updatedAt = root.optLong("updatedAt"),
                minSeq = root.optLong("minSeq"),
                schema = root.optInt("schema", 1),
                events = events,
            )
        } catch (throwable: Throwable) {
            // 坏 JSON ⇒ 本轮跳过（调用方不得推进水位）
            null
        }
    }
}

/**
 * 设备本地 seq 分配器：**线程安全、严格单调、绝不回收**。
 *
 * 即使 PUT 失败也不回收 —— 否则 itemId 复用会被对端水位跳过。
 */
class ClipboardLogSequence(start: Long = 0L) {

    private val gate = Any()
    private var current: Long = start

    fun next(): Long = synchronized(gate) { ++current }

    val value: Long get() = synchronized(gate) { current }
}
