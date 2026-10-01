package com.board.ime.cloudlibrary

import com.board.ime.clipboard.ClipboardHasher
import com.board.ime.sync.webdav.WebDavClient
import com.board.ime.sync.webdav.WebDavError
import com.board.ime.sync.webdav.WebDavResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cloud Library Repository（纯 JVM；fake WebDAV + fake sink/source，不碰真实服务器/磁盘） */
class CloudLibraryRepositoryTest {

    private val me = "android-1"
    private val other = "win-1"
    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    // ------------------------------------------------------------ fakes

    private class FakeWebDav : WebDavClient {
        val texts = LinkedHashMap<String, String>()
        val blobs = LinkedHashMap<String, ByteArray>()
        val getTextFail = HashMap<String, Pair<WebDavError, Int>>()
        val getBytesFail = HashMap<String, Pair<WebDavError, Int>>()
        val putTextFail = HashMap<String, Pair<WebDavError, Int>>()
        val putBytesFail = HashMap<String, Pair<WebDavError, Int>>()
        val deleteFail = HashMap<String, Pair<WebDavError, Int>>()
        val deleted = ArrayList<String>()

        override suspend fun testConnection() = WebDavResponse.ok(Unit)
        override suspend fun getText(path: String): WebDavResponse<String> {
            getTextFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            return texts[path]?.let { WebDavResponse.ok(it, 200) }
                ?: WebDavResponse.fail(WebDavError.NOT_FOUND, 404)
        }

        override suspend fun getBytes(path: String): WebDavResponse<ByteArray> {
            getBytesFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            return blobs[path]?.let { WebDavResponse.ok(it, 200) }
                ?: WebDavResponse.fail(WebDavError.NOT_FOUND, 404)
        }

        override suspend fun downloadToFile(path: String, destination: java.io.File, onProgress: (Long, Long) -> Unit) =
            WebDavResponse.ok(Unit)

        override suspend fun uploadFile(path: String, file: java.io.File, contentType: String, onProgress: (Long, Long) -> Unit) =
            WebDavResponse.ok(Unit)

        override suspend fun putText(path: String, body: String, contentType: String): WebDavResponse<Unit> {
            putTextFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            texts[path] = body
            return WebDavResponse.ok(Unit, 201)
        }

        override suspend fun putBytes(path: String, bytes: ByteArray, contentType: String): WebDavResponse<Unit> {
            putBytesFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            blobs[path] = bytes
            return WebDavResponse.ok(Unit, 201)
        }

        override suspend fun list(path: String) = WebDavResponse.ok(emptyList<String>())
        override suspend fun delete(path: String): WebDavResponse<Unit> {
            deleteFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            deleted.add(path)
            blobs.remove(path)
            return WebDavResponse.ok(Unit, 204)
        }

        override suspend fun ensureDirectory(path: String) = WebDavResponse.ok(Unit)
    }

    private class FakeSink : LibraryFileSink {
        val images = ArrayList<String>()
        val files = ArrayList<String>()
        var fail = false
        override suspend fun saveImage(bytes: ByteArray, name: String): String? {
            if (fail) return null
            images.add(name)
            return "Pictures/Board/$name"
        }

        override suspend fun saveFile(bytes: ByteArray, name: String): String? {
            if (fail) return null
            files.add(name)
            return "Downloads/Board/$name"
        }
    }

    private class FakeUploadSource(private val body: ByteArray?) : LibraryUploadSource {
        var lastLimit = 0L
        override suspend fun read(uri: String, limit: Long): ByteArray? {
            lastLimit = limit
            return body
        }
    }

    private fun item(hash: String, at: Long, type: String = CloudLibraryItem.TYPE_FILE, name: String = "a.bin") =
        CloudLibraryItem(hash = hash, type = type, name = name, size = 3, uploadedAt = at, deviceId = other)

    private fun indexOf(device: String, vararg items: CloudLibraryItem) =
        CloudLibraryCodec.serializeIndex(CloudLibraryIndex(deviceId = device, deviceType = "X", items = items.toList()))

    private fun repo(fake: FakeWebDav, sink: FakeSink = FakeSink(), source: FakeUploadSource = FakeUploadSource(null)) =
        CloudLibraryRepository(CloudLibraryRemote(fake, me), sink, source, me)

    // ------------------------------------------------------------ 1-6 refresh/合并/过期

    @Test
    fun refreshSuccessBuildsList() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        fake.texts[CloudLibrary.indexPathFor(other)] = indexOf(other, item(hashA, 100))
        val snap = repo(fake).refresh(now = 500)
        assertEquals(1, snap.items.size)
        assertEquals(hashA, snap.items[0].hash)
        assertFalse(snap.partial)
        assertEquals(LibraryError.NONE, snap.error)
    }

    @Test
    fun refreshPartialKeepsReadableDevices() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        fake.texts[CloudLibrary.indexPathFor(other)] = indexOf(other, item(hashA, 100))
        fake.getTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.SERVER to 500
        val snap = repo(fake).refresh()
        assertEquals("可读设备的资料必须保留", 1, snap.items.size)
        assertTrue(snap.partial)
        assertEquals(LibraryError.PARTIAL, snap.error)
    }

    @Test
    fun refreshAllFailPreservesPreviousList() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        fake.texts[CloudLibrary.indexPathFor(other)] = indexOf(other, item(hashA, 100))
        val r = repo(fake)
        assertEquals(1, r.refresh().items.size)

        fake.getTextFail[CloudLibrary.DEVICES_PATH] = WebDavError.NETWORK to 0
        val snap = r.refresh()
        assertEquals("完全失败必须保留旧列表", 1, snap.items.size)
        assertEquals(LibraryError.NETWORK, snap.error)
    }

    @Test
    fun refreshUsesMaxUploadedAtAcrossDevices() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        fake.texts[CloudLibrary.indexPathFor(other)] = indexOf(other, item(hashA, 100))
        fake.texts[CloudLibrary.indexPathFor(me)] = indexOf(me, item(hashA, 900))
        val snap = repo(fake).refresh()
        assertEquals("同 hash 合并为一条", 1, snap.items.size)
        assertEquals(900L, snap.items[0].uploadedAt)
    }

    @Test
    fun expiredOnlyWhenAllDevicesExpired() {
        val now = 10_000_000_000L
        val old = item(hashA, now - CloudLibrary.TTL_MS - 1)
        val fresh = item(hashA, now - 1000)
        assertEquals(1, CloudLibraryCodec.deletableItems(now, listOf(old)).size)
        assertEquals(0, CloudLibraryCodec.deletableItems(now, listOf(fresh)).size)
    }

    @Test
    fun multiDeviceUnionKeepsNewest() {
        val merged = CloudLibraryCodec.mergeIndexes(
            listOf(indexOf(other, item(hashA, 100)), indexOf(me, item(hashA, 800))),
        )
        assertEquals(1, merged.size)
        assertEquals(800L, merged[0].uploadedAt)
    }

    // ------------------------------------------------------------ 7-10 download

    @Test
    fun downloadImageGoesToAlbum() = runBlocking {
        val fake = FakeWebDav()
        val sink = FakeSink()
        val payload = byteArrayOf(1, 2, 3, 4)
        fake.blobs[CloudLibrary.binaryPathFor(hashA, ".png")] = payload
        val outcome = repo(fake, sink).download(item(hashA, 1, CloudLibraryItem.TYPE_IMAGE, "p.png"))
        assertTrue(outcome is DownloadOutcome.ImageSaved)
        assertEquals("✓ 已下载到相册", outcome.message)
        assertEquals(listOf("p.png"), sink.images)
        assertTrue(sink.files.isEmpty())
    }

    @Test
    fun downloadFileGoesToDownloads() = runBlocking {
        val fake = FakeWebDav()
        val sink = FakeSink()
        fake.blobs[CloudLibrary.binaryPathFor(hashA, ".bin")] = byteArrayOf(9)
        val outcome = repo(fake, sink).download(item(hashA, 1, CloudLibraryItem.TYPE_FILE, "a.bin"))
        assertTrue(outcome is DownloadOutcome.FileSaved)
        assertEquals("✓ 已下载", outcome.message)
        assertEquals(listOf("a.bin"), sink.files)
        assertTrue(sink.images.isEmpty())
    }

    @Test
    fun downloadFailureReportsFailure() = runBlocking {
        val fake = FakeWebDav()
        val sink = FakeSink()
        fake.getBytesFail[CloudLibrary.binaryPathFor(hashA, ".bin")] = WebDavError.NETWORK to 0
        val outcome = repo(fake, sink).download(item(hashA, 1))
        assertTrue(outcome is DownloadOutcome.Failed)
        assertEquals("× 下载失败", outcome.message)
        assertTrue(sink.files.isEmpty())
        assertTrue(sink.images.isEmpty())
    }

    @Test
    fun downloadDoesNotChangeUploadedAt() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        val before = indexOf(other, item(hashA, 123))
        fake.texts[CloudLibrary.indexPathFor(other)] = before
        fake.blobs[CloudLibrary.binaryPathFor(hashA, ".bin")] = byteArrayOf(1)
        val r = repo(fake)
        r.refresh()
        r.download(item(hashA, 123))
        assertEquals("下载不得改远端 index", before, fake.texts[CloudLibrary.indexPathFor(other)])
        assertEquals(123L, r.state.value.items[0].uploadedAt)
    }

    // ------------------------------------------------------------ 11-15 upload

    @Test
    fun oversizedUploadRejectedWithBoundedRead() = runBlocking {
        val fake = FakeWebDav()
        val source = FakeUploadSource(null)   // 有界读取超限 → null
        val outcome = repo(fake, FakeSink(), source).upload("uri", "big.bin", null)
        assertTrue(outcome is UploadResult.Rejected)
        assertEquals(CloudLibrary.MAX_LIBRARY_BYTES, source.lastLimit)
        assertTrue("不得写任何东西", fake.blobs.isEmpty() && fake.texts.isEmpty())
    }

    @Test
    fun successfulUploadUsesRawBytesSha256AndOriginalExtension() = runBlocking {
        val fake = FakeWebDav()
        val bytes = byteArrayOf(4, 5, 6)
        val outcome = repo(fake, FakeSink(), FakeUploadSource(bytes)).upload("uri", "photo.PNG", "image/png", now = 777)
        assertTrue(outcome is UploadResult.Success)
        val expectedHash = ClipboardHasher.hashBytes(bytes)
        assertEquals(expectedHash, (outcome as UploadResult.Success).item.hash)
        assertArrayEquals("binary 不得被改写", bytes, fake.blobs[CloudLibrary.binaryPathFor(expectedHash, ".png")])
        val index = CloudLibraryCodec.parseIndex(fake.texts[CloudLibrary.indexPathFor(me)])!!
        assertEquals(777L, index.items[0].uploadedAt)
        assertEquals(CloudLibraryItem.TYPE_IMAGE, index.items[0].type)
        assertTrue(fake.texts.containsKey(CloudLibrary.DEVICES_PATH))
    }

    @Test
    fun binaryFailureDoesNotReportSuccess() = runBlocking {
        val fake = FakeWebDav()
        val bytes = byteArrayOf(1)
        fake.putBytesFail[CloudLibrary.binaryPathFor(ClipboardHasher.hashBytes(bytes), ".bin")] = WebDavError.SERVER to 500
        val outcome = repo(fake, FakeSink(), FakeUploadSource(bytes)).upload("uri", "a.bin", null)
        assertTrue(outcome is UploadResult.Failed)
        assertFalse(fake.texts.containsKey(CloudLibrary.indexPathFor(me)))
    }

    @Test
    fun indexFailureLeavesBinaryAndReportsFailure() = runBlocking {
        val fake = FakeWebDav()
        val bytes = byteArrayOf(1, 2)
        val hash = ClipboardHasher.hashBytes(bytes)
        fake.putTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.SERVER to 500
        val outcome = repo(fake, FakeSink(), FakeUploadSource(bytes)).upload("uri", "a.bin", null)
        assertTrue(outcome is UploadResult.Failed)
        assertTrue("index 失败绝不 DELETE binary", fake.blobs.containsKey(CloudLibrary.binaryPathFor(hash, ".bin")))
        assertTrue(fake.deleted.isEmpty())
    }

    @Test
    fun uploadRefreshesUploadedAtForSameHash() = runBlocking {
        val fake = FakeWebDav()
        val bytes = byteArrayOf(1)
        val hash = ClipboardHasher.hashBytes(bytes)
        val source = FakeUploadSource(bytes)
        repo(fake, FakeSink(), source).upload("u", "a.bin", null, now = 100)
        repo(fake, FakeSink(), source).upload("u", "a.bin", null, now = 900)
        val index = CloudLibraryCodec.parseIndex(fake.texts[CloudLibrary.indexPathFor(me)])!!
        assertEquals(1, index.items.size)
        assertEquals(900L, index.items[0].uploadedAt)
    }

    // ------------------------------------------------------------ 16-18 prune

    private fun seedExpired(fake: FakeWebDav, now: Long) {
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        fake.texts[CloudLibrary.indexPathFor(other)] = indexOf(other, item(hashA, now - CloudLibrary.TTL_MS - 1))
        fake.texts[CloudLibrary.indexPathFor(me)] = indexOf(me, item(hashA, now - CloudLibrary.TTL_MS - 1))
        fake.blobs[CloudLibrary.binaryPathFor(hashA, ".bin")] = byteArrayOf(1)
    }

    @Test
    fun pruneDeletesExpiredAndRewritesOwnIndex() = runBlocking {
        val fake = FakeWebDav()
        val now = 5_000_000_000L
        seedExpired(fake, now)
        val result = repo(fake).prune(now)
        assertEquals(1, result.candidates)
        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
        assertTrue(fake.deleted.contains(CloudLibrary.binaryPathFor(hashA, ".bin")))
        val mine = CloudLibraryCodec.parseIndex(fake.texts[CloudLibrary.indexPathFor(me)])!!
        assertEquals("删除成功后才移除自己的条目", 0, mine.items.size)
    }

    @Test
    fun pruneTreats404AsSuccess() = runBlocking {
        val fake = FakeWebDav()
        val now = 5_000_000_000L
        seedExpired(fake, now)
        fake.deleteFail[CloudLibrary.binaryPathFor(hashA, ".bin")] = WebDavError.NOT_FOUND to 404
        val result = repo(fake).prune(now)
        assertEquals(1, result.deleted)
        assertEquals(0, result.failed)
    }

    @Test
    fun pruneFailurePreservesIndexEntry() = runBlocking {
        val fake = FakeWebDav()
        val now = 5_000_000_000L
        seedExpired(fake, now)
        fake.deleteFail[CloudLibrary.binaryPathFor(hashA, ".bin")] = WebDavError.SERVER to 500
        val before = fake.texts[CloudLibrary.indexPathFor(me)]!!
        val result = repo(fake).prune(now)
        assertEquals(1, result.failed)
        assertEquals(0, result.deleted)
        assertEquals("DELETE 失败必须保留 index 条目", before, fake.texts[CloudLibrary.indexPathFor(me)])
        assertTrue(fake.blobs.containsKey(CloudLibrary.binaryPathFor(hashA, ".bin")))
    }

    @Test
    fun pruneSkipsWhenPartial() = runBlocking {
        val fake = FakeWebDav()
        val now = 5_000_000_000L
        seedExpired(fake, now)
        fake.getTextFail[CloudLibrary.indexPathFor(other)] = WebDavError.NETWORK to 0
        val result = repo(fake).prune(now)
        assertTrue("partial 时必须直接放弃", result.skipped)
        assertTrue(fake.deleted.isEmpty())
    }

    // ------------------------------------------------------------ 19-20 隐私

    @Test
    fun repositoryNeverWritesSystemClipboard() = runBlocking {
        // 本类不引用任何 Clipboard API；用「不产生任何非 library 路径访问」作为结构断言
        val fake = FakeWebDav()
        val src = "com.board.ime.cloudlibrary.CloudLibraryRepository"
        val text = javaClass.classLoader!!.getResourceAsStream(
            src.replace('.', '/') + ".class",
        )?.readBytes() ?: ByteArray(0)
        val asText = String(text, Charsets.ISO_8859_1)
        assertFalse("不得引用 ClipboardManager", asText.contains("ClipboardManager"))
        assertFalse("不得引用 setPrimaryClip", asText.contains("setPrimaryClip"))
        assertTrue(fake.blobs.isEmpty())
    }

    @Test
    fun repositoryWritesNoPlaintextCacheOrThumbnail() = runBlocking {
        val src = "com.board.ime.cloudlibrary.CloudLibraryRepository"
        val text = javaClass.classLoader!!.getResourceAsStream(
            src.replace('.', '/') + ".class",
        )?.readBytes() ?: ByteArray(0)
        val asText = String(text, Charsets.ISO_8859_1)
        assertFalse("不得写 SharedPreferences", asText.contains("SharedPreferences"))
        assertFalse("不得写文件缓存", asText.contains("FileOutputStream"))
        assertFalse("不得用 Bitmap 缩略图缓存", asText.contains("Bitmap"))
    }

    // ---- 缩略图数据源（fetchBytes：只读原始 bytes，无副作用）----

    @Test
    fun fetchBytesReturnsRawBytesWithoutSideEffects() = runBlocking {
        val fake = FakeWebDav()
        val payload = byteArrayOf(1, 2, 3)
        fake.blobs[CloudLibrary.binaryPathFor(hashA, ".png")] = payload
        val r = repo(fake)
        assertArrayEquals(payload, r.fetchBytes(item(hashA, 1, CloudLibraryItem.TYPE_IMAGE, "p.png")))
        assertTrue("fetchBytes 不得写任何东西", fake.texts.isEmpty() && fake.deleted.isEmpty())
    }

    @Test
    fun fetchBytesFailureReturnsNull() = runBlocking {
        val fake = FakeWebDav()
        fake.getBytesFail[CloudLibrary.binaryPathFor(hashA, ".bin")] = WebDavError.NETWORK to 0
        assertTrue("失败必须返回 null（缩略图占位，不影响整页）", repo(fake).fetchBytes(item(hashA, 1)) == null)
    }

    @Test
    fun repositoryExposesDeviceId() {
        assertEquals(me, repo(FakeWebDav()).deviceId)
    }

    // ---- 来源设备类型（云端 · Windows）----

    private fun windowsIndexJson(device: String, type: String, vararg items: CloudLibraryItem): String =
        CloudLibraryCodec.serializeIndex(
            CloudLibraryIndex(deviceId = device, deviceType = type, items = items.toList()),
        )

    @Test
    fun refreshMapsDeviceIdToDeviceType() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        fake.texts[CloudLibrary.indexPathFor(other)] = windowsIndexJson(other, "Windows", item(hashA, 100))
        fake.texts[CloudLibrary.indexPathFor(me)] = windowsIndexJson(me, "Android", item(hashB, 100).copy(deviceId = me))

        val snapshot = repo(fake).refresh()
        assertEquals("Windows", snapshot.deviceTypes[other])
        assertEquals("Android", snapshot.deviceTypes[me])

        val rows = LibraryUiModel.rows(snapshot.items, me, snapshot.deviceTypes)
        val windowsRow = rows.first { it.item.hash == hashA }
        val androidRow = rows.first { it.item.hash == hashB }
        assertEquals("云端 · Windows", windowsRow.sourceLabel)
        assertFalse(windowsRow.isLocal)
        assertEquals("本地", androidRow.sourceLabel)
        assertTrue("本机条目不可显示为云端", androidRow.isLocal)
    }

    @Test
    fun missingDeviceTypeFallsBackSafely() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other))
        fake.texts[CloudLibrary.indexPathFor(other)] = windowsIndexJson(other, "", item(hashA, 100))
        val snapshot = repo(fake).refresh()
        val row = LibraryUiModel.rows(snapshot.items, me, snapshot.deviceTypes).first()
        assertEquals("云端", row.sourceLabel)
    }

    @Test
    fun mergeKeepsNewestAndMapsItsDeviceType() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf(other, me))
        // 同一 hash：Windows 更新（900）胜出
        fake.texts[CloudLibrary.indexPathFor(other)] = windowsIndexJson(other, "Windows", item(hashA, 100))
        fake.texts[CloudLibrary.indexPathFor(me)] = windowsIndexJson(me, "Android", item(hashA, 900))
        val snapshot = repo(fake).refresh()
        assertEquals(1, snapshot.items.size)
        assertEquals(900L, snapshot.items[0].uploadedAt)
        assertEquals(other, snapshot.items[0].deviceId)
        assertEquals("云端 · Windows", LibraryUiModel.rows(snapshot.items, me, snapshot.deviceTypes)[0].sourceLabel)
    }

    @Test
    fun deviceTypeMapDoesNotChangeRemoteSchema() {
        val json = windowsIndexJson("win-1", "Windows", item(hashA, 100))
        val index = CloudLibraryCodec.parseIndex(json)!!
        assertEquals("win-1", index.deviceId)
        assertEquals("Windows", index.deviceType)
        assertEquals(1, index.items.size)
        // 只读映射不影响原 JSON 结构
        assertEquals(CloudLibraryCodec.deviceTypes(listOf(json))["win-1"], "Windows")
        assertEquals(json, CloudLibraryCodec.serializeIndex(index))
    }
}
