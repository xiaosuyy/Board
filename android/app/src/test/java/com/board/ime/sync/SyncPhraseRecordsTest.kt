package com.board.ime.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常用语同步的纯逻辑单测（离线、不联网）：
 * 身份只认 phraseId、墓碑规则、排序归一化、旧 JSON 兼容、防回环。
 */
class SyncPhraseRecordsTest {

    private fun rec(
        id: String,
        text: String,
        updatedAt: Long,
        sortOrder: Int = 0,
        deletedAt: Long? = null,
    ) = SyncPhraseRecord(
        phraseId = id,
        text = text,
        updatedAt = updatedAt,
        sortOrder = sortOrder,
        deletedAt = deletedAt,
    )

    // 1. 同文不同 phraseId 必须都保留
    @Test
    fun sameTextDifferentIdsAreKeptSeparately() {
        val local = listOf(rec("A", "谢谢", 100))
        val remote = listOf(rec("B", "谢谢", 100))
        val merged = SyncPhraseMerge.merge(local, remote)
        assertEquals(2, merged.size)
        assertEquals(setOf("A", "B"), merged.map { it.phraseId }.toSet())
    }

    // 2. 同 phraseId 新旧版本：新的胜
    @Test
    fun sameIdNewerWins() {
        val merged = SyncPhraseMerge.merge(
            listOf(rec("A", "旧", 100)),
            listOf(rec("A", "新", 200)),
        )
        assertEquals(1, merged.size)
        assertEquals("新", merged[0].text)
    }

    // 3. 墓碑胜旧活数据
    @Test
    fun tombstoneBeatsOlderAlive() {
        val merged = SyncPhraseMerge.merge(
            listOf(rec("A", "谢谢", 100)),
            listOf(rec("A", "谢谢", 300, deletedAt = 300)),
        )
        assertTrue(merged[0].deleted)
    }

    // 4. 旧活数据不能复活墓碑
    @Test
    fun olderAliveCannotReviveTombstone() {
        val merged = SyncPhraseMerge.merge(
            local = listOf(rec("A", "谢谢", 300, deletedAt = 300)),   // 本地墓碑
            remote = listOf(rec("A", "谢谢", 100)),                   // 远端旧活数据
        )
        assertTrue("墓碑不能被旧的活数据复活", merged[0].deleted)
    }

    // 5. sortOrder 稳定归一化（同 sortOrder 用 updatedAt → phraseId 决胜）
    @Test
    fun duplicateSortOrderIsNormalisedStably() {
        val merged = SyncPhraseMerge.merge(
            listOf(rec("A", "a", 100, sortOrder = 0), rec("B", "b", 200, sortOrder = 0)),
            emptyList(),
        )
        assertEquals(listOf("A", "B"), merged.map { it.phraseId })
        // 同样的输入必然同样的输出
        assertEquals(merged, SyncPhraseMerge.normalize(merged))
    }

    // 6. 重复 phraseId（同一文件里出现两次）：只保留合并后的一条
    @Test
    fun duplicatePhraseIdInOneListCollapsesToOne() {
        val merged = SyncPhraseMerge.merge(
            listOf(rec("A", "旧", 100)),
            listOf(rec("A", "新", 300), rec("A", "更新", 200)),
        )
        assertEquals(1, merged.size)
        assertEquals("新", merged[0].text)
    }

    // 7/8. 旧 JSON：没有 phraseId / sortOrder 也能解析，且 ID 稳定
    @Test
    fun legacyJsonWithoutPhraseIdOrSortOrderParsesWithStableId() {
        val legacy = """
            {"schema":1,"device":"old","exportedAt":1,
             "words":[{"word":"谢谢您的理解。","code":null,"deleted":false,
                       "createdAt":10,"updatedAt":20,"type":"PHRASE"}]}
        """.trimIndent()
        val first = SyncPhraseCodec.fromJson(legacy)
        assertNotNull(first)
        assertEquals(1, first!!.size)
        assertEquals("谢谢您的理解。", first[0].text)
        assertEquals(0, first[0].sortOrder)
        assertNull(first[0].deletedAt)
        assertTrue(first[0].phraseId.startsWith("legacy-"))

        // 再次解析必须得到**同一个** ID（不能每次随机）
        val second = SyncPhraseCodec.fromJson(legacy)!!
        assertEquals(first[0].phraseId, second[0].phraseId)
        // 稳定派生：同文本同 code → 同 ID
        assertEquals(
            SyncPhraseCodec.legacyPhraseId("谢谢您的理解。", null),
            first[0].phraseId,
        )
    }

    @Test
    fun legacyDeletedFlagBecomesTombstone() {
        val legacy = """
            {"words":[{"word":"我稍后回复您。","deleted":true,"updatedAt":50,"type":"PHRASE"}]}
        """.trimIndent()
        val parsed = SyncPhraseCodec.fromJson(legacy)!!
        assertTrue(parsed[0].deleted)
        assertEquals(50L, parsed[0].deletedAt)
    }

    @Test
    fun newFormatRoundTripKeepsTombstonesAndOrder() {
        val records = listOf(
            rec("D", "请稍等", 10, sortOrder = 0),
            rec("A", "你好", 20, sortOrder = 1),
            rec("C", "我稍后回复您", 30, sortOrder = 2, deletedAt = 30),
        )
        val json = SyncPhraseCodec.toJson("devA", records)
        // 墓碑必须写进 JSON
        assertTrue(json.contains("\"deletedAt\""))
        val back = SyncPhraseCodec.fromJson(json)!!
        assertEquals(SyncPhraseMerge.normalize(records), back)
    }

    // 9. Pull 不触发无限 Push：内容等价（仅顺序不同）时判定不需要回写
    @Test
    fun equalContentInDifferentOrderDoesNotNeedPush() {
        val local = listOf(rec("A", "a", 100, sortOrder = 0), rec("B", "b", 100, sortOrder = 1))
        val remoteSameDifferentOrder = listOf(
            rec("B", "b", 100, sortOrder = 1),
            rec("A", "a", 100, sortOrder = 0),
        )
        assertFalse(SyncPhraseMerge.needsPush(local, remoteSameDifferentOrder))
    }

    @Test
    fun localOnlyContentDoesNeedPush() {
        val local = listOf(rec("A", "a", 100))
        assertTrue(SyncPhraseMerge.needsPush(local, emptyList()))
    }

    // 10. 墓碑正确写入 JSON（并且不会被过滤掉）
    @Test
    fun tombstonesAreWrittenToJson() {
        val json = SyncPhraseCodec.toJson("devA", listOf(rec("A", "谢谢", 1, deletedAt = 99)))
        val back = SyncPhraseCodec.fromJson(json)!!
        assertEquals(1, back.size)
        assertEquals(99L, back[0].deletedAt)
    }

    @Test
    fun brokenJsonIsIgnoredSafely() {
        assertNull(SyncPhraseCodec.fromJson("{not json"))
    }
}
