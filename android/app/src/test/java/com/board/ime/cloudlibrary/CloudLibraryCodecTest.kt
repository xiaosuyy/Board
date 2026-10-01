package com.board.ime.cloudlibrary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloud Library 数据层（与 Windows `CloudLibrary.cs` 同语义）。
 *
 * 纯 JVM，不使用 Android 类。
 */
class CloudLibraryCodecTest {

    private fun item(hash: String, at: Long, type: String = CloudLibraryItem.TYPE_FILE, name: String = "a.bin", size: Long = 10) =
        CloudLibraryItem(hash = hash, type = type, name = name, size = size, uploadedAt = at, deviceId = "dev-a")

    private fun indexJson(vararg items: CloudLibraryItem): String =
        CloudLibraryCodec.serializeIndex(CloudLibraryIndex(deviceId = "dev-a", items = items.toList()))

    // ---- 编解码 ----

    @Test
    fun indexRoundTrip() {
        val original = CloudLibraryIndex(
            deviceId = "dev-a",
            deviceType = "Android",
            updatedAt = 123L,
            items = listOf(item("h1", 100), item("h2", 200, CloudLibraryItem.TYPE_IMAGE, "p.png")),
        )
        val back = CloudLibraryCodec.parseIndex(CloudLibraryCodec.serializeIndex(original))
        assertNotNull(back)
        assertEquals("dev-a", back!!.deviceId)
        assertEquals(2, back.items.size)
        assertEquals("h1", back.items[0].hash)
        assertEquals(CloudLibraryItem.TYPE_IMAGE, back.items[1].type)
        assertEquals("p.png", back.items[1].name)
    }

    @Test
    fun brokenJsonSafe() {
        assertNull(CloudLibraryCodec.parseIndex("{ not json ]"))
        assertNull(CloudLibraryCodec.parseIndex(""))
        assertNull(CloudLibraryCodec.parseIndex(null))
        assertEquals(0, CloudLibraryCodec.parseDeviceIds("{ not json ]").size)
        assertEquals(0, CloudLibraryCodec.parseDeviceIds(null).size)
    }

    // ---- 合并（union）----

    @Test
    fun unionDedupByHash() {
        val merged = CloudLibraryCodec.mergeIndexes(
            listOf(indexJson(item("h1", 100)), indexJson(item("h1", 100), item("h2", 200))),
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun unionKeepsNewestUploadedAt() {
        val merged = CloudLibraryCodec.mergeIndexes(
            listOf(
                indexJson(item("h1", 100)),
                indexJson(item("h1", 999)),
            ),
        )
        assertEquals(1, merged.size)
        assertEquals(999L, merged[0].uploadedAt)
    }

    @Test
    fun mergeSortedNewestFirst() {
        val merged = CloudLibraryCodec.mergeIndexes(
            listOf(indexJson(item("old", 100), item("new", 900), item("mid", 500))),
        )
        assertEquals(listOf("new", "mid", "old"), merged.map { it.hash })
    }

    // ---- TTL / 多设备保护 ----

    @Test
    fun ttlIsExactly24Hours() {
        val now = 1_000_000_000L
        assertFalse(CloudLibrary.isExpired(now, now))                                   // 刚上传
        assertFalse(CloudLibrary.isExpired(now, now - CloudLibrary.TTL_MS + 1))         // 差 1ms
        assertTrue(CloudLibrary.isExpired(now, now - CloudLibrary.TTL_MS))              // 正好 24h
        assertTrue(CloudLibrary.isExpired(now, now - CloudLibrary.TTL_MS - 1))
    }

    @Test
    fun deletableOnlyWhenAllDevicesExpired() {
        val now = 2_000_000_000L
        // 合并结果取 max(uploadedAt)：另一台设备刚续期 ⇒ 合并值新鲜 ⇒ 不可删
        val merged = CloudLibraryCodec.mergeIndexes(
            listOf(
                indexJson(item("old", now - CloudLibrary.TTL_MS - 5)),
                indexJson(item("old", now - 1000)),          // 另一设备刚上传
            ),
        )
        assertEquals(0, CloudLibraryCodec.deletableItems(now, merged).size)

        val stale = CloudLibraryCodec.mergeIndexes(listOf(indexJson(item("dead", now - CloudLibrary.TTL_MS - 1))))
        assertEquals(1, CloudLibraryCodec.deletableItems(now, stale).size)
    }

    // ---- 上限 / 路径 / 命名 ----

    @Test
    fun tenMibLimit() {
        val limit = CloudLibrary.MAX_LIBRARY_BYTES
        assertFalse(CloudLibrary.withinLimit(0))
        assertTrue(CloudLibrary.withinLimit(1))
        assertTrue(CloudLibrary.withinLimit(limit))
        assertFalse(CloudLibrary.withinLimit(limit + 1))
    }

    @Test
    fun indexPathIsSanitised() {
        assertEquals("library/index-dev-a.json", CloudLibrary.indexPathFor("dev-a"))
        assertEquals("library/index-etcpasswd.json", CloudLibrary.indexPathFor("../../etc/passwd"))
        assertEquals("library/index-device.json", CloudLibrary.indexPathFor(""))
    }

    @Test
    fun binaryPathKeepsExtension() {
        assertEquals("library/h1.png", CloudLibrary.binaryPathFor("h1", ".png"))
        assertEquals("library/h1.png", CloudLibrary.binaryPathFor("h1", "png"))
        assertEquals("library/h1.png", CloudLibrary.binaryPathFor("h1", "PnG"))
        assertEquals("library/board", CloudLibrary.binaryPathFor("", ""))
        assertEquals("library/h1", CloudLibrary.binaryPathFor("h1", null))
    }

    @Test
    fun fileNameIsSanitised() {
        assertEquals("a.txt", CloudLibrary.sanitizeFileName("../../etc/a.txt"))
        assertEquals("board", CloudLibrary.sanitizeFileName(""))
        assertEquals("board", CloudLibrary.sanitizeFileName("dir/"))
        assertFalse(CloudLibrary.sanitizeFileName("a:b*c?.txt").contains(':'))
    }

    // ---- 来源 ----

    @Test
    fun originTextLocalVersusCloud() {
        assertEquals("本地", CloudLibrary.originText("me", "me", "Android"))
        assertEquals("云端 · Windows", CloudLibrary.originText("win-1", "me", "Windows"))
        assertEquals("云端", CloudLibrary.originText("win-1", "me", ""))
    }

    // ---- UI 契约（图片可预览 / 文件一律不预览）----

    @Test
    fun onlyImagesHavePreview() {
        assertTrue(CloudLibrary.hasPreview(CloudLibraryItem.TYPE_IMAGE))
        assertFalse("文件绝不预览", CloudLibrary.hasPreview(CloudLibraryItem.TYPE_FILE))
        assertFalse(CloudLibrary.hasPreview(null))
    }

    @Test
    fun typeForUsesMimeThenExtension() {
        assertEquals(CloudLibraryItem.TYPE_IMAGE, CloudLibrary.typeFor("image/png", "x"))
        assertEquals(CloudLibraryItem.TYPE_IMAGE, CloudLibrary.typeFor(null, "x.JPG"))
        assertEquals(CloudLibraryItem.TYPE_FILE, CloudLibrary.typeFor("application/pdf", "x.pdf"))
        assertEquals(CloudLibraryItem.TYPE_FILE, CloudLibrary.typeFor(null, "noext"))
    }

    @Test
    fun downloadTargets() {
        assertEquals("Pictures/Board", CloudLibrary.IMAGE_DIR)
        assertEquals("Downloads/Board", CloudLibrary.FILE_DIR)
        assertTrue(CloudLibrary.isImageItem(CloudLibraryItem.TYPE_IMAGE))
        assertFalse(CloudLibrary.isImageItem(CloudLibraryItem.TYPE_FILE))
    }

    // ---- 设备注册表 ----

    @Test
    fun deviceIdsRoundTripAndDedup() {
        val json = CloudLibraryCodec.serializeDeviceIds(listOf("dev-a", "dev-b", "dev-a"))
        val ids = CloudLibraryCodec.parseDeviceIds(json)
        assertEquals(listOf("dev-a", "dev-b"), ids)
    }

    @Test
    fun shortDeviceNeverLeaksFullToken() {
        assertEquals("-", CloudLibrary.shortDevice(""))
        assertEquals("dev-a", CloudLibrary.shortDevice("dev-a"))
        val long = "win-14a240a8c84f4d3b"
        assertEquals(8, CloudLibrary.shortDevice(long).length)
        assertFalse(CloudLibrary.shortDevice(long) == long)
    }

    // ---- 跨端 type 大小写兼容（Windows 1-H 写入 type="image"）----

    @Test
    fun typeComparisonIsCaseInsensitive() {
        assertTrue(CloudLibrary.isImage("image"))
        assertTrue(CloudLibrary.isImage("Image"))
        assertTrue(CloudLibrary.isImage("IMAGE"))
        assertFalse(CloudLibrary.isImage("file"))
        assertFalse(CloudLibrary.isImage("File"))
        assertTrue(CloudLibrary.hasPreview("image"))
        assertTrue(CloudLibrary.hasPreview("IMAGE"))
        assertFalse(CloudLibrary.hasPreview("file"))
        // 规范输入不受影响
        assertTrue(CloudLibrary.isImage(CloudLibraryItem.TYPE_IMAGE))
        assertFalse(CloudLibrary.isImage(CloudLibraryItem.TYPE_FILE))
        // malformed / unknown 保持「非图片」行为
        assertFalse(CloudLibrary.isImage(null))
        assertFalse(CloudLibrary.isImage(""))
        assertFalse(CloudLibrary.isImage("garbage"))
    }

    @Test
    fun windowsLowercaseImageIsPreviewableInUnifiedList() {
        // 真实 Windows index 片段：type 为**小写** "image"
        val json = """
        {"schema":1,"deviceId":"win-1","deviceType":"Windows","items":[
          {"hash":"ca247479190a02fc9c7a8857c58ebc6ecd37b213523e568f6ef6eb3b6b7df428",
           "type":"image","name":"QQ_1790055256843.png","size":81117,
           "uploadedAt":1790835220986,"deviceId":"win-1"}]}
        """
        val index = CloudLibraryCodec.parseIndex(json)!!
        assertEquals("image", index.items[0].type)   // 原样保留，不做改写

        val rows = LibraryUiModel.rows(index.items, "android-1", mapOf("win-1" to "Windows"))
        assertEquals("小写 image 必须出现在统一列表里", 1, rows.size)
        val row = rows[0]
        assertTrue("图片必须可预览", row.canPreview)
        assertEquals("云端 · Windows", row.sourceLabel)
        assertFalse(row.isLocal)
    }

    @Test
    fun lowercaseFileIsNotPreviewableInUnifiedList() {
        val item = CloudLibraryItem(
            hash = "b".repeat(64), type = "file", name = "a.bin",
            size = 1, uploadedAt = 1, deviceId = "win-1",
        )
        val rows = LibraryUiModel.rows(listOf(item), "android-1")
        assertEquals(1, rows.size)
        assertFalse("文件绝不预览", rows[0].canPreview)
        assertTrue(rows[0].canDownload)
    }

    // ---- MIME（Android 适配层的纯逻辑部分）----
    @Test
    fun libraryMimePrefersExtensionThenHint() {
        assertEquals("image/png", LibraryMime.forName("p.PNG"))
        assertEquals("image/jpeg", LibraryMime.forName("p.jpg"))
        assertEquals("application/pdf", LibraryMime.forName("d.pdf"))
        assertEquals("application/octet-stream", LibraryMime.forName("noext"))
        assertEquals("image/*", LibraryMime.forName("noext", isImageHint = true))
        assertEquals("application/octet-stream", LibraryMime.forName(null))
    }
}
