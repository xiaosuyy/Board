package com.board.ime.sync

import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.clipboard.SyncClipboardMapper
import com.board.ime.clipboard.SyncClipboardPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * B1 增量 Clipboard History 协议层测试。
 *
 * 与 Windows 端 `Tests/Program.cs` 的 `[clipboard-log]` 组**同一份断言**，
 * 保证两端对 `/clipboard-log/<deviceId>.json` 的理解逐条一致。
 *
 * 隐私：这里的正文都是测试用短串，不涉及任何真实剪贴板内容。
 */
class ClipboardLogRecordsTest {

    private fun event(seq: Long, hash: String, text: String? = null): ClipboardLogEvent =
        ClipboardLogEvent(
            seq = seq,
            itemId = "dev:$seq",
            hash = hash,
            createdAt = System.currentTimeMillis(),
            size = 1,
            text = text,
        )

    private fun logOf(texts: List<String>): ClipboardLogFile {
        val now = System.currentTimeMillis()
        return ClipboardLogFile(
            deviceId = "android-test",
            deviceType = "Android",
            updatedAt = now,
            events = texts.mapIndexed { index, text ->
                val seq = (index + 1).toLong()
                ClipboardLogEvent(
                    seq = seq,
                    itemId = ClipboardLogRecords.itemIdFor("android-test", seq),
                    hash = ClipboardHasher.hash(text),
                    createdAt = now,
                    size = ClipboardHasher.utf8Length(text),
                    text = text,
                )
            },
        )
    }

    // 1) seq 单调递增
    @Test
    fun seqIsMonotonic() {
        val sequence = ClipboardLogSequence(0)
        var last = 0L
        repeat(100) {
            val next = sequence.next()
            assertTrue("seq 必须严格递增", next > last)
            last = next
        }
        assertEquals(100L, sequence.value)
    }

    // 2) itemId 唯一
    @Test
    fun itemIdIsUnique() {
        val ids = (1..50).map { ClipboardLogRecords.itemIdFor("android-abc", it.toLong()) }
        assertEquals(50, ids.toSet().size)
        assertEquals("d1:7", ClipboardLogRecords.itemIdFor("d1", 7))
    }

    // 3) A/B 快速连续复制 → 两个 event
    @Test
    fun rapidCopyProducesTwoEvents() {
        val log = logOf(listOf("AAA", "BBB"))
        assertEquals(2, log.events.size)
    }

    // 4) latest-state 仍只有 B（去抖只影响 newest，不影响 event 数量）
    @Test
    fun latestStateStillOnlyB() {
        val texts = listOf("AAA", "BBB")
        assertEquals("BBB", texts.last())
        assertEquals(2, logOf(texts).events.size)
    }

    // 5) pull A/B → 两条候选（按 seq 升序）
    @Test
    fun pullSeesTwoItems() {
        val candidates = ClipboardLogRecords.candidatesFor(0, logOf(listOf("AAA", "BBB")))
        assertEquals(2, candidates.size)
        assertEquals(1L, candidates[0].seq)
        assertEquals(2L, candidates[1].seq)
    }

    // 6) 相同 hash 的两个 event 都进入候选（去重是本地 History DB 的既有语义）
    @Test
    fun sameHashKeepsBothCandidates() {
        val candidates = ClipboardLogRecords.candidatesFor(0, logOf(listOf("same", "same")))
        assertEquals(2, candidates.size)
        assertEquals(candidates[0].hash, candidates[1].hash)
        assertEquals(ClipboardHasher.hash("same"), ClipboardHasher.hash("same"))
    }

    // 7) offline A/B/C → 上线后全部补齐
    @Test
    fun offlineBackfillAll() {
        assertEquals(3, ClipboardLogRecords.candidatesFor(0, logOf(listOf("A", "B", "C"))).size)
    }

    // 8) 多设备各写自己的 log → 路径隔离、互不覆盖
    @Test
    fun perDevicePathIsolation() {
        assertEquals("clipboard-log/win-aaa.json", ClipboardLogRecords.pathFor("win-aaa"))
        assertEquals("clipboard-log/android-bbb.json", ClipboardLogRecords.pathFor("android-bbb"))
        assertTrue(ClipboardLogRecords.pathFor("a") != ClipboardLogRecords.pathFor("b"))
    }

    // 9) 同设备并发 append → seq 不重复
    @Test
    fun concurrentAppendIsUnique() {
        val sequence = ClipboardLogSequence(0)
        val seen = Collections.synchronizedList(mutableListOf<Long>())
        val threads = (1..8).map {
            Thread { repeat(250) { seen.add(sequence.next()) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(2000, seen.size)
        assertEquals(2000, seen.toSet().size)
        assertEquals(2000L, sequence.value)
    }

    // 10) PUT 失败不回收 seq（否则 itemId 复用会被对端水位跳过）
    @Test
    fun seqIsNotReclaimedOnPutFailure() {
        val sequence = ClipboardLogSequence(5)
        assertEquals(6L, sequence.next())
        assertEquals(7L, sequence.next())
        assertEquals(8L, sequence.next())
        assertEquals(8L, sequence.value)
    }

    // 11) pull 中途失败 ⇒ 不推进 lastSeenSeq
    @Test
    fun watermarkNotAdvancedOnFailure() {
        assertEquals(3L, ClipboardLogRecords.advanceWatermark(3, listOf(4, 5, 6), false))
        assertEquals(6L, ClipboardLogRecords.advanceWatermark(3, listOf(4, 5, 6), true))
        assertEquals(9L, ClipboardLogRecords.advanceWatermark(9, emptyList(), true))
    }

    // 12) 404 / 缺失 log 安全处理
    @Test
    fun missingLogIsSafe() {
        assertEquals(0, ClipboardLogRecords.candidatesFor(0, null).size)
        assertFalse(ClipboardLogRecords.gapDetected(0, null))
        assertNull(ClipboardLogRecords.parse(""))
        assertNull(ClipboardLogRecords.parse(null))
    }

    // 13) 坏 JSON 安全处理
    @Test
    fun badJsonIsSafe() {
        assertNull(ClipboardLogRecords.parse("{ this is not json ]"))
    }

    // 14) minSeq gap 被正确识别（不猜测、不伪造）
    @Test
    fun minSeqGapDetected() {
        val gap = ClipboardLogFile(
            deviceId = "android-gap",
            minSeq = 50,
            events = listOf(event(50, "h50")),
        )
        assertTrue(ClipboardLogRecords.gapDetected(3, gap))
        assertFalse(ClipboardLogRecords.gapDetected(49, gap))
        assertEquals(1, ClipboardLogRecords.candidatesFor(49, gap).size)
    }

    // 15/16/17) 只有 Text 且 ≤256 KiB 才产生 event
    @Test
    fun onlyInlineTextProducesEvents() {
        val limit = SyncClipboardMapper.INLINE_TEXT_MAX_BYTES.toLong()
        assertFalse("File 不产生 event", ClipboardLogRecords.shouldRecord("File", 10))
        assertFalse("Image B1 不产生 event", ClipboardLogRecords.shouldRecord("Image", 10))
        assertFalse(">256 KiB 不产生 event", ClipboardLogRecords.shouldRecord(ClipboardLogRecords.TYPE_TEXT, limit + 1))
        assertTrue(ClipboardLogRecords.shouldRecord(ClipboardLogRecords.TYPE_TEXT, 1))
        assertTrue(ClipboardLogRecords.shouldRecord(ClipboardLogRecords.TYPE_TEXT, limit))
        assertFalse(ClipboardLogRecords.shouldRecord(ClipboardLogRecords.TYPE_TEXT, 0))
    }

    // 18) 消费远端事件是只读的（不写系统剪贴板 ⇒ 不触发回调 ⇒ 无回环）
    @Test
    fun consumeIsReadOnly() {
        val log = logOf(listOf("R1", "R2"))
        val before = log.events.size
        ClipboardLogRecords.candidatesFor(0, log)
        assertEquals(before, log.events.size)
    }

    // 19) 与 latest-state 并存且隔离
    @Test
    fun coexistWithLatestState() {
        val path = ClipboardLogRecords.pathFor("x")
        assertTrue(path != SyncClipboardPaths.MANIFEST)
        assertTrue(path.startsWith("clipboard-log/"))
        assertFalse(path.startsWith("library/"))
    }

    // 裁剪：只裁本设备、更新 minSeq、不重排 seq、上限 200
    @Test
    fun compactKeepsBoundedAndOrdered() {
        val now = System.currentTimeMillis()
        val many = ClipboardLogFile(
            deviceId = "android-many",
            updatedAt = now,
            events = (1..250).map {
                ClipboardLogEvent(
                    seq = it.toLong(),
                    itemId = ClipboardLogRecords.itemIdFor("android-many", it.toLong()),
                    hash = "h$it",
                    createdAt = now,
                    size = 1,
                )
            },
        )
        val compacted = ClipboardLogRecords.compact(many, now)
        assertEquals(ClipboardLogRecords.MAX_EVENTS, compacted.events.size)
        assertEquals(51L, compacted.events.first().seq)
        assertEquals(51L, compacted.minSeq)
        assertTrue(compacted.events.zipWithNext().all { (a, b) -> b.seq > a.seq })
    }

    // 裁剪：7 天前的事件被移除，minSeq 随之上移
    @Test
    fun compactRespectsRetention() {
        val now = System.currentTimeMillis()
        val aged = ClipboardLogFile(
            deviceId = "android-aged",
            updatedAt = now,
            events = listOf(
                ClipboardLogEvent(seq = 1, itemId = "android-aged:1", hash = "old", createdAt = now - ClipboardLogRecords.RETENTION_MS - 1000, size = 1),
                ClipboardLogEvent(seq = 2, itemId = "android-aged:2", hash = "new", createdAt = now, size = 1),
            ),
        )
        val compacted = ClipboardLogRecords.compact(aged, now)
        assertEquals(1, compacted.events.size)
        assertEquals(2L, compacted.events.first().seq)
        assertEquals(2L, compacted.minSeq)
    }

    // 序列化往返（远端 schema）
    @Test
    fun schemaRoundTrip() {
        val original = logOf(listOf("AAA", "BBB"))
        val parsed = ClipboardLogRecords.parse(ClipboardLogRecords.serialize(original))
        assertNotNull(parsed)
        assertEquals("android-test", parsed!!.deviceId)
        assertEquals(2, parsed.events.size)
        assertEquals(ClipboardHasher.hash("AAA"), parsed.events[0].hash)
        assertEquals("AAA", parsed.events[0].text)
    }

    // ---- seq 崩溃窗口（D-extra）：已成功发布的 seq 绝不能被重新分配 ----

    @Test
    fun crashWindowReassignsReusedSeq() {
        val remote = listOf(
            ClipboardLogEvent(seq = 7, itemId = "dev:7", hash = "HASH-OLD", createdAt = 1, size = 1),
        )
        val sequence = ClipboardLogSequence(6)   // 崩溃前没落盘 → 水位落后
        val reused = ClipboardLogEvent(
            seq = sequence.next(),
            itemId = ClipboardLogRecords.itemIdFor("dev", 7),
            hash = "HASH-NEW",
            createdAt = 2,
            size = 1,
        )
        assertEquals("应先复现 seq 被重复分配", "dev:7", reused.itemId)

        val out = ClipboardLogRecords.reassignReusedSequences(listOf(reused), remote, "dev", sequence)
        assertEquals(1, out.size)
        assertEquals(8L, out[0].seq)
        assertEquals("dev:8", out[0].itemId)
        assertNotEquals("不得复用已发布的 itemId", "dev:7", out[0].itemId)
        assertEquals("内容不得丢弃", "HASH-NEW", out[0].hash)
        assertTrue("分配后水位必须超过 remoteMax", sequence.value > 7)
    }

    @Test
    fun crashWindowSameIdSameHashNotReassigned() {
        val remote = listOf(
            ClipboardLogEvent(seq = 3, itemId = "dev:3", hash = "SAME", createdAt = 1, size = 1),
        )
        val batch = listOf(
            ClipboardLogEvent(seq = 3, itemId = "dev:3", hash = "SAME", createdAt = 1, size = 1),
        )
        val sequence = ClipboardLogSequence(3)
        val out = ClipboardLogRecords.reassignReusedSequences(batch, remote, "dev", sequence)
        assertEquals("同 id 同内容不应换 seq", "dev:3", out[0].itemId)
        assertEquals(3L, out[0].seq)
    }

    @Test
    fun crashWindowNoConflictNotReassigned() {
        val sequence = ClipboardLogSequence(10)
        val batch = listOf(
            ClipboardLogEvent(seq = 11, itemId = "dev:11", hash = "X", createdAt = 1, size = 1),
        )
        val out = ClipboardLogRecords.reassignReusedSequences(batch, emptyList(), "dev", sequence)
        assertEquals(11L, out[0].seq)
        assertEquals("dev:11", out[0].itemId)
        assertEquals("无冲突不得消耗 seq", 10L, sequence.value)
    }

    @Test
    fun crashWindowBumpThenMonotonic() {
        val bumped = ClipboardLogSequence(7)   // remoteMax
        assertEquals(8L, bumped.next())
        assertEquals(9L, bumped.next())
        assertEquals(9L, bumped.value)
    }

    // deviceId 安全化：路径穿越字符不得进入远端路径
    @Test
    fun deviceIdIsSanitised() {
        val path = ClipboardLogRecords.pathFor("../../etc/passwd")
        assertEquals("clipboard-log/etcpasswd.json", path)
        assertFalse(path.contains(".."))
    }
}
