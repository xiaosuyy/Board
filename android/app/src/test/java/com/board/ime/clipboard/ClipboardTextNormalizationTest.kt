package com.board.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Phase 1-G：文本路径的 **hash 归一化差异** 回归。
 *
 * `capture()`（系统剪贴板回调）会把文本 `trim()` 后再算 hash，
 * 而协议 / `applyRemote` 侧用的是**未 trim** 的 hash。
 * 只要远端文本带首尾空白（例如结尾换行），两者就不是同一个 hash ——
 * 这正是「仅靠 hash 短路会漏出 LOCAL 重复条目」的原因，
 * 也是 `applyRemote` 必须**同时登记归一化 hash 抑制**的依据。
 */
class ClipboardTextNormalizationTest {

    @Test
    fun trailingNewlineChangesHash() {
        assertNotEquals(ClipboardHasher.hash("board\n"), ClipboardHasher.hash("board"))
    }

    @Test
    fun surroundingSpacesChangeHash() {
        assertNotEquals(ClipboardHasher.hash("  board  "), ClipboardHasher.hash("board"))
    }

    @Test
    fun normalizedHashMatchesTrimmedText() {
        val raw = "board\n\n"
        assertEquals(ClipboardHasher.hash("board"), ClipboardHasher.hash(raw.trim()))
    }
}
