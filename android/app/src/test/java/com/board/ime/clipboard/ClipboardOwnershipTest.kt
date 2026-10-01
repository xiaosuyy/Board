package com.board.ime.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 1-F 「Board 自己写回剪贴板」判定单测。
 *
 * 这是**防止 CLOUD → LOCAL 重复条目**的关键一步：认出自己写回的 URI 后，
 * 只刷新原条目、绝不新建 LOCAL 历史。同时必须**不误判**普通图片 URI
 * （否则用户真正手动复制的新图会被吞掉）。
 */
class ClipboardOwnershipTest {

    @Test
    fun recognisesBoardOwnWrittenBackImage() {
        val hash = ClipboardOwnership.ownImageHash(
            "content",
            listOf("clipboard_images", "abc123.png"),
        )
        assertEquals("abc123", hash)
    }

    @Test
    fun uppercaseSchemeStillRecognised() {
        assertEquals(
            "deadbeef",
            ClipboardOwnership.ownImageHash("CONTENT", listOf("clipboard_images", "deadbeef.png")),
        )
    }

    // 其它 App / 相册的图片 URI 绝不能被误判（否则会吞掉用户真正的新复制）
    @Test
    fun otherProvidersAreNotOurs() {
        assertNull(ClipboardOwnership.ownImageHash("content", listOf("images", "media", "42")))
        assertNull(ClipboardOwnership.ownImageHash("content", listOf("clipboard_images2", "a.png")))
        assertNull(ClipboardOwnership.ownImageHash("file", listOf("clipboard_images", "a.png")))
        assertNull(ClipboardOwnership.ownImageHash(null, listOf("clipboard_images", "a.png")))
        assertNull(ClipboardOwnership.ownImageHash("content", null))
    }

    @Test
    fun malformedPathsAreSafe() {
        assertNull(ClipboardOwnership.ownImageHash("content", listOf("clipboard_images")))
        assertNull(ClipboardOwnership.ownImageHash("content", listOf("clipboard_images", "a.txt")))
        assertNull(ClipboardOwnership.ownImageHash("content", listOf("clipboard_images", ".png")))
        assertNull(ClipboardOwnership.ownImageHash("content", emptyList()))
    }
}
