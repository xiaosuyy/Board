package com.board.ime.clipboard

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 1-E 图片**文件生命周期**单测（只用 java.io，不需要设备）。
 *
 * 重点验证需求里的保护规则：
 * - 当前系统剪贴板 URI 正在引用的图片**绝不删**；
 * - 仍被历史条目引用的图片**绝不删**；
 * - 无引用且已过期的文件才删（失败 / 中断 / 旧文件残留）。
 */
class ClipboardImageStoreTest {

    private lateinit var root: File
    private lateinit var store: ClipboardImageStore

    @Before
    fun setUp() {
        root = File(
            System.getProperty("java.io.tmpdir"),
            "board-image-store-test-" + System.nanoTime(),
        )
        store = ClipboardImageStore(root)
    }

    @After
    fun tearDown() {
        runCatching { root.deleteRecursively() }
    }

    private fun write(hash: String, byte: Int = 7): File {
        val file = store.write(hash, ByteArray(16) { byte.toByte() })
        assertNotNull("写入应成功", file)
        return file!!
    }

    @Test
    fun writeReadExistsDelete() {
        val file = write("aaa111")
        assertEquals(SyncClipboardMapper.dataNameForImage("aaa111"), file.name)
        assertTrue(store.exists("aaa111"))
        val bytes = store.read("aaa111")
        assertNotNull(bytes)
        assertEquals(16, bytes!!.size)
        assertTrue(store.delete("aaa111"))
        assertFalse(store.exists("aaa111"))
        assertNull(store.read("aaa111"))
    }

    @Test
    fun pruneDeletesStaleUnreferencedFiles() {
        val stale = write("stale1")
        stale.setLastModified(System.currentTimeMillis() - 10_000L)
        val removed = store.prune(
            referenced = emptySet(),
            currentClipboardHash = null,
            now = System.currentTimeMillis(),
            ttlMs = 5_000L,
        )
        assertEquals(1, removed)
        assertFalse(store.exists("stale1"))
    }

    @Test
    fun pruneKeepsReferencedFileEvenWhenOld() {
        val referenced = write("keep01")
        referenced.setLastModified(System.currentTimeMillis() - 10_000L)
        val removed = store.prune(
            referenced = setOf("keep01"),
            currentClipboardHash = null,
            now = System.currentTimeMillis(),
            ttlMs = 5_000L,
        )
        assertEquals(0, removed)
        assertTrue("仍被历史条目引用 → 必须保留", store.exists("keep01"))
    }

    @Test
    fun pruneNeverDeletesCurrentClipboardFile() {
        // 这是需求里最关键的一条：剪贴板 URI 还指着的图片不能删，否则用户稍后粘贴会失败
        val current = write("current")
        current.setLastModified(System.currentTimeMillis() - 10_000L)
        val removed = store.prune(
            referenced = emptySet(),
            currentClipboardHash = "current",
            now = System.currentTimeMillis(),
            ttlMs = 5_000L,
        )
        assertEquals(0, removed)
        assertTrue("当前剪贴板引用的图片 → 必须保留", store.exists("current"))
    }

    @Test
    fun pruneKeepsFreshUnreferencedFile() {
        write("fresh1")
        val removed = store.prune(
            referenced = emptySet(),
            currentClipboardHash = null,
            now = System.currentTimeMillis(),
            ttlMs = 60_000L,
        )
        assertEquals("刚写入、尚未过期的文件不应被删", 0, removed)
        assertTrue(store.exists("fresh1"))
    }

    @Test
    fun pruneMixedCase() {
        val staleUnreferenced = write("gone01")
        staleUnreferenced.setLastModified(System.currentTimeMillis() - 10_000L)
        val referenced = write("keep02")
        referenced.setLastModified(System.currentTimeMillis() - 10_000L)
        val current = write("now003")
        current.setLastModified(System.currentTimeMillis() - 10_000L)

        val removed = store.prune(
            referenced = setOf("keep02"),
            currentClipboardHash = "now003",
            now = System.currentTimeMillis(),
            ttlMs = 5_000L,
        )
        assertEquals(1, removed)
        assertFalse(store.exists("gone01"))
        assertTrue(store.exists("keep02"))
        assertTrue(store.exists("now003"))
    }
}
