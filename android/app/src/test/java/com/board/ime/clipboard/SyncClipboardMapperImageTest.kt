package com.board.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1-E：图片**清单形状**与解析（复用现有 JSON，不新增协议字段）。
 */
class SyncClipboardMapperImageTest {

    @Test
    fun imagePayloadShape() {
        val payload = SyncClipboardMapper.toImagePayload("deadbeef", 1234L)
        assertEquals(SyncClipboardMapper.TYPE_IMAGE, payload.type)
        assertEquals("deadbeef", payload.hash)
        assertNull("图片正文绝不能内联", payload.text)
        assertTrue(payload.hasData)
        assertEquals("deadbeef.png", payload.dataName)
        assertEquals(1234L, payload.size)
    }

    @Test
    fun imagePayloadJsonRoundTrip() {
        val json = SyncClipboardMapper.payloadToJson(SyncClipboardMapper.toImagePayload("cafe01", 42L))
        assertTrue("type 必须是 Image", json.contains("\"type\": \"Image\"") || json.contains("\"type\":\"Image\""))
        assertTrue("必须 hasData=true", json.contains("\"hasData\": true") || json.contains("\"hasData\":true"))
        assertTrue(json.contains("cafe01.png"))
        assertTrue("text 必须为 null", json.contains("\"text\": null") || json.contains("\"text\":null"))

        val back = SyncClipboardMapper.payloadFromJson(json)
        assertTrue(back != null)
        assertEquals(SyncClipboardMapper.TYPE_IMAGE, back!!.type)
        assertEquals("cafe01", back.hash)
        assertEquals("cafe01.png", back.dataName)
        assertEquals(42L, back.size)
        assertNull(back.text)
    }

    @Test
    fun imageIsSupportedOnlyWhenFileBacked() {
        assertTrue(SyncClipboardMapper.isSupported(SyncClipboardMapper.toImagePayload("h", 1L)))
        assertFalse(
            "没有数据的 Image 必须安全忽略",
            SyncClipboardMapper.isSupported(
                SyncClipboardPayload(type = SyncClipboardMapper.TYPE_IMAGE, hash = "h", hasData = false),
            ),
        )
        assertFalse(
            "hasData 但缺 dataName 也必须忽略",
            SyncClipboardMapper.isSupported(
                SyncClipboardPayload(
                    type = SyncClipboardMapper.TYPE_IMAGE,
                    hash = "h",
                    hasData = true,
                    dataName = null,
                ),
            ),
        )
    }

    @Test
    fun textStillSupportedAndFileGroupStillRejected() {
        // 文本回归：不能因为引入 Image 破坏原有支持范围
        assertTrue(SyncClipboardMapper.isSupported(SyncClipboardPayload(type = "Text", hash = "h")))
        assertFalse(SyncClipboardMapper.isSupported(SyncClipboardPayload(type = "File", hash = "h")))
        assertFalse(SyncClipboardMapper.isSupported(SyncClipboardPayload(type = "Group", hash = "h")))
    }
}
