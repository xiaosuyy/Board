package com.board.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 1-F 来源小字文案单测。
 *
 * 注意：这里只验证 **UI 文案**，不涉及 origin 数据层逻辑（origin 不因同步改变）。
 */
class ClipboardOriginLabelTest {

    @Test
    fun localShowsLocal() {
        assertEquals("本地", ClipboardOriginLabel.of(ClipboardOrigin.LOCAL, null))
        assertEquals("本地", ClipboardOriginLabel.of(ClipboardOrigin.LOCAL, "Windows"))
    }

    @Test
    fun cloudWithoutDeviceShowsCloud() {
        assertEquals("云端", ClipboardOriginLabel.of(ClipboardOrigin.REMOTE, null))
        assertEquals("云端", ClipboardOriginLabel.of(ClipboardOrigin.REMOTE, ""))
        assertEquals("云端", ClipboardOriginLabel.of(ClipboardOrigin.REMOTE, "   "))
    }

    @Test
    fun cloudWithDeviceShowsDevice() {
        assertEquals("云端 · Windows", ClipboardOriginLabel.of(ClipboardOrigin.REMOTE, "Windows"))
        assertEquals("云端 · Android", ClipboardOriginLabel.of(ClipboardOrigin.REMOTE, "Android"))
    }
}
