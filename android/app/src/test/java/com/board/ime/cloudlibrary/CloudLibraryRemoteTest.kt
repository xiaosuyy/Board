package com.board.ime.cloudlibrary

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

/**
 * Cloud Library 远端层（只打 fake WebDAV，**不碰真实服务器**）。
 */
class CloudLibraryRemoteTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val me = "android-1"

    private class FakeWebDav : WebDavClient {
        val texts = LinkedHashMap<String, String>()
        val blobs = LinkedHashMap<String, ByteArray>()
        val getTextFail = HashMap<String, Pair<WebDavError, Int>>()
        val getBytesFail = HashMap<String, Pair<WebDavError, Int>>()
        val putTextFail = HashMap<String, Pair<WebDavError, Int>>()
        val putBytesFail = HashMap<String, Pair<WebDavError, Int>>()
        val deleteFail = HashMap<String, Pair<WebDavError, Int>>()
        val deleted = ArrayList<String>()
        val touchedPaths = ArrayList<String>()

        override suspend fun testConnection(): WebDavResponse<Unit> = WebDavResponse.ok(Unit)

        override suspend fun getText(path: String): WebDavResponse<String> {
            touchedPaths.add(path)
            getTextFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            val value = texts[path] ?: return WebDavResponse.fail(WebDavError.NOT_FOUND, 404)
            return WebDavResponse.ok(value, 200)
        }

        override suspend fun getBytes(path: String): WebDavResponse<ByteArray> {
            touchedPaths.add(path)
            getBytesFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            val value = blobs[path] ?: return WebDavResponse.fail(WebDavError.NOT_FOUND, 404)
            return WebDavResponse.ok(value, 200)
        }

        override suspend fun downloadToFile(path: String, destination: java.io.File, onProgress: (Long, Long) -> Unit): WebDavResponse<Unit> =
            WebDavResponse.ok(Unit)

        override suspend fun uploadFile(path: String, file: java.io.File, contentType: String, onProgress: (Long, Long) -> Unit): WebDavResponse<Unit> =
            WebDavResponse.ok(Unit)

        override suspend fun putText(path: String, body: String, contentType: String): WebDavResponse<Unit> {
            touchedPaths.add(path)
            putTextFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            texts[path] = body
            return WebDavResponse.ok(Unit, 201)
        }

        override suspend fun putBytes(path: String, bytes: ByteArray, contentType: String): WebDavResponse<Unit> {
            touchedPaths.add(path)
            putBytesFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            blobs[path] = bytes
            return WebDavResponse.ok(Unit, 201)
        }

        override suspend fun list(path: String): WebDavResponse<List<String>> = WebDavResponse.ok(emptyList())

        override suspend fun delete(path: String): WebDavResponse<Unit> {
            touchedPaths.add(path)
            deleteFail[path]?.let { return WebDavResponse.fail(it.first, it.second) }
            deleted.add(path)
            blobs.remove(path)
            return WebDavResponse.ok(Unit, 204)
        }

        override suspend fun ensureDirectory(path: String): WebDavResponse<Unit> = WebDavResponse.ok(Unit)
    }

    private fun remote(fake: FakeWebDav) = CloudLibraryRemote(fake, me, "Android")

    // 1) devices GET
    @Test
    fun devicesGetParsesIds() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1", "android-1"))
        val read = remote(fake).readDeviceIds()
        assertTrue(read is LibraryRead.Ok)
        assertEquals(listOf("win-1", "android-1"), (read as LibraryRead.Ok).value)
    }

    // 2) devices 404
    @Test
    fun devicesMissingIsNotAnError() = runBlocking {
        val read = remote(FakeWebDav()).readDeviceIds()
        assertTrue(read is LibraryRead.Missing)
    }

    // 3) devices 坏 JSON → 明确失败（安全），不崩溃
    @Test
    fun devicesBadJsonIsFailure() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = "{ not json ]"
        val read = remote(fake).readDeviceIds()
        assertTrue(read is LibraryRead.Failure)
        assertEquals(WebDavError.PROTOCOL, (read as LibraryRead.Failure).error)
    }

    // 4) index GET
    @Test
    fun indexGetParses() = runBlocking {
        val fake = FakeWebDav()
        val index = CloudLibraryIndex(
            deviceId = "win-1",
            deviceType = "Windows",
            items = listOf(CloudLibraryItem(hash = hashA, type = CloudLibraryItem.TYPE_FILE, name = "a.bin", size = 3, uploadedAt = 7, deviceId = "win-1")),
        )
        fake.texts[CloudLibrary.indexPathFor("win-1")] = CloudLibraryCodec.serializeIndex(index)
        val read = remote(fake).readIndex("win-1")
        assertTrue(read is LibraryRead.Ok)
        assertEquals(hashA, (read as LibraryRead.Ok).value.items[0].hash)
    }

    // 5) index 404 → Missing（不得据此删 deviceId）
    @Test
    fun indexMissingKeepsDeviceId() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1"))
        val batch = remote(fake).readIndexes()
        assertTrue(batch.deviceIds.contains("win-1"))   // deviceId 仍在
        assertEquals(0, batch.jsons.size)
        assertFalse(batch.partial)
    }

    // 6) index 坏 JSON → 安全失败 + partial
    @Test
    fun indexBadJsonIsSafeFailure() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.indexPathFor(me)] = "{ broken"
        val read = remote(fake).readIndex(me)
        assertTrue(read is LibraryRead.Failure)
        assertTrue(remote(fake).readIndexes().partial)
    }

    // 7) binary GET（原始 bytes）
    @Test
    fun binaryGetReturnsRawBytes() = runBlocking {
        val fake = FakeWebDav()
        val payload = byteArrayOf(0, 1, 2, 3, -1, -2)
        fake.blobs[CloudLibrary.binaryPathFor(hashA, ".png")] = payload
        val read = remote(fake).downloadBinary(hashA, ".png")
        assertTrue(read is LibraryRead.Ok)
        assertArrayEquals(payload, (read as LibraryRead.Ok).value)
    }

    // 8) binary PUT fail
    @Test
    fun binaryPutFailDoesNotWriteIndex() = runBlocking {
        val fake = FakeWebDav()
        fake.putBytesFail[CloudLibrary.binaryPathFor(hashA, ".bin")] = WebDavError.SERVER to 500
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1, 2, 3))
        assertTrue(outcome is UploadOutcome.BinaryFailed)
        assertFalse(fake.texts.containsKey(CloudLibrary.indexPathFor(me)))
    }

    // 9/10) binary PUT ok + index PUT ok → Success（顺序正确）
    @Test
    fun uploadSuccessWritesBinaryThenIndex() = runBlocking {
        val fake = FakeWebDav()
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(9, 8, 7), now = 1000)
        assertTrue(outcome is UploadOutcome.Success)
        val binary = CloudLibrary.binaryPathFor(hashA, ".bin")
        val index = CloudLibrary.indexPathFor(me)
        assertTrue(fake.blobs.containsKey(binary))
        assertTrue(fake.texts.containsKey(index))
        assertTrue("binary 必须先于 index", fake.touchedPaths.indexOf(binary) < fake.touchedPaths.indexOf(index))
        val parsed = CloudLibraryCodec.parseIndex(fake.texts[index])!!
        assertEquals(1, parsed.items.size)
        assertEquals(1000L, parsed.items[0].uploadedAt)
        assertTrue("必须自注册 devices.json", fake.texts.containsKey(CloudLibrary.DEVICES_PATH))
    }

    // 11) index PUT 失败 → **binary 保留**（不做回滚；安全优先于清理）
    @Test
    fun indexPutFailureKeepsBinary() = runBlocking {
        val fake = FakeWebDav()
        val binary = CloudLibrary.binaryPathFor(hashA, ".bin")
        fake.putTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.SERVER to 500
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1))
        assertTrue(outcome is UploadOutcome.IndexFailed)
        assertTrue("index 失败必须留下 binary（孤儿可接受）", fake.blobs.containsKey(binary))
        assertTrue("绝不主动 DELETE", fake.deleted.isEmpty())
    }

    // 12/13/14) DELETE 200 / 204 / 404 = 成功
    @Test
    fun deleteIsIdempotent() = runBlocking {
        val fake = FakeWebDav()
        assertTrue(remote(fake).deleteBinary(hashA, ".bin") is LibraryRead.Ok)     // 204 默认
        fake.deleteFail[CloudLibrary.binaryPathFor(hashB, ".bin")] = WebDavError.NOT_FOUND to 404
        assertTrue("404 必须视为成功", remote(fake).deleteBinary(hashB, ".bin") is LibraryRead.Ok)
    }

    // 15) DELETE 401/403/405/5xx/网络 = 失败
    @Test
    fun deleteFailuresAreFailures() = runBlocking {
        val codes = listOf(
            WebDavError.AUTH to 401,
            WebDavError.AUTH to 403,
            WebDavError.SERVER to 405,
            WebDavError.SERVER to 500,
            WebDavError.NETWORK to 0,
        )
        for ((error, code) in codes) {
            val fake = FakeWebDav()
            fake.deleteFail[CloudLibrary.binaryPathFor(hashA, ".bin")] = error to code
            val read = remote(fake).deleteBinary(hashA, ".bin")
            assertTrue("http=$code 必须失败", read is LibraryRead.Failure)
        }
    }

    // 16) 重复 hash 刷新 uploadedAt（延长 TTL）
    @Test
    fun reuploadRefreshesUploadedAt() = runBlocking {
        val fake = FakeWebDav()
        val r = remote(fake)
        r.upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1), now = 100)
        r.upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1), now = 500)
        val index = CloudLibraryCodec.parseIndex(fake.texts[CloudLibrary.indexPathFor(me)])!!
        assertEquals("同 hash 必须只有一条", 1, index.items.size)
        assertEquals(500L, index.items[0].uploadedAt)
    }

    // 17) 自注册只追加，不丢已有设备
    @Test
    fun registrationMergesWithoutLosingOthers() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1"))
        assertTrue(remote(fake).ensureRegistered())
        val ids = CloudLibraryCodec.parseDeviceIds(fake.texts[CloudLibrary.DEVICES_PATH])
        assertTrue(ids.contains("win-1"))
        assertTrue(ids.contains(me))
    }

    // 404 devices.json 时能创建 schema=1
    @Test
    fun registrationCreatesDevicesFile() = runBlocking {
        val fake = FakeWebDav()
        assertTrue(remote(fake).ensureRegistered())
        val raw = fake.texts[CloudLibrary.DEVICES_PATH]!!
        assertEquals(1, org.json.JSONObject(raw).optInt("schema"))
    }

    // 18) 路径穿越 / 非法 hash 被拒绝
    @Test
    fun pathTraversalIsRejected() = runBlocking {
        val fake = FakeWebDav()
        val r = remote(fake)
        assertTrue(r.upload("../../etc/passwd", ".bin", "x", CloudLibraryItem.TYPE_FILE, byteArrayOf(1)) is UploadOutcome.Rejected)
        assertTrue(r.downloadBinary("../../etc/passwd", ".bin") is LibraryRead.Failure)
        assertTrue(r.deleteBinary("..\\..\\x", ".bin") is LibraryRead.Failure)
        assertTrue(fake.touchedPaths.isEmpty() || fake.touchedPaths.none { it.contains("..") })
    }

    // 19) >10 MiB 不允许进入 upload（且不发任何请求）
    @Test
    fun oversizedRejectedBeforeAnyRequest() = runBlocking {
        val fake = FakeWebDav()
        val big = ByteArray((CloudLibrary.MAX_LIBRARY_BYTES + 1).toInt())
        val outcome = remote(fake).upload(hashA, ".bin", "big.bin", CloudLibraryItem.TYPE_FILE, big)
        assertTrue(outcome is UploadOutcome.Rejected)
        assertTrue("拒绝的请求不得发起任何网络调用", fake.touchedPaths.isEmpty())
    }

    // 20) 原始 binary bytes 不被修改
    @Test
    fun uploadDoesNotModifyBytes() = runBlocking {
        val fake = FakeWebDav()
        val payload = ByteArray(64) { (it * 3).toByte() }
        val copy = payload.copyOf()
        remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, payload)
        assertArrayEquals(copy, fake.blobs[CloudLibrary.binaryPathFor(hashA, ".bin")])
        assertArrayEquals(copy, payload)
    }

    // 21/22) 绝不碰 /file/ 与 SyncClipboard.json
    @Test
    fun neverTouchesFileOrManifest() = runBlocking {
        val fake = FakeWebDav()
        val r = remote(fake)
        r.readIndexes()
        r.upload(hashA, ".png", "p.png", CloudLibraryItem.TYPE_IMAGE, byteArrayOf(1, 2))
        r.downloadBinary(hashA, ".png")
        r.deleteBinary(hashA, ".png")
        r.ensureRegistered()
        assertTrue(fake.touchedPaths.none { it.startsWith("file/") })
        assertTrue(fake.touchedPaths.none { it.contains("SyncClipboard.json") })
        assertTrue(fake.touchedPaths.all { it.startsWith("library/") })
    }

    // 23) 坏设备/索引安全处理：partial 必须置位（不得据此判过期）
    @Test
    fun partialReadIsFlagged() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1", me))
        fake.getTextFail[CloudLibrary.indexPathFor("win-1")] = WebDavError.SERVER to 500
        val batch = remote(fake).readIndexes()
        assertTrue("有一台读失败必须标记 partial", batch.partial)
    }

    // 上传时读到自己的索引失败 → 不报告成功；binary 保留
    @Test
    fun indexReadFailureDoesNotReportSuccess() = runBlocking {
        val fake = FakeWebDav()
        fake.getTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.SERVER to 500
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1))
        assertTrue(outcome is UploadOutcome.IndexFailed)
        assertNotNull(fake.blobs[CloudLibrary.binaryPathFor(hashA, ".bin")])
        assertTrue(fake.deleted.isEmpty())
    }

    // ---------------------------------------------------------------- 回滚安全性

    private fun otherIndexJson(vararg items: CloudLibraryItem): String =
        CloudLibraryCodec.serializeIndex(
            CloudLibraryIndex(deviceId = "win-1", deviceType = "Windows", items = items.toList()),
        )

    private fun item(hash: String, at: Long) = CloudLibraryItem(
        hash = hash,
        type = CloudLibraryItem.TYPE_FILE,
        name = "a.bin",
        size = 1,
        uploadedAt = at,
        deviceId = "win-1",
    )

    // A + E：新 binary + index 失败 → **binary 保留**（本阶段绝不回滚）
    @Test
    fun newBinaryKeptWhenIndexFails() = runBlocking {
        val fake = FakeWebDav()
        val binary = CloudLibrary.binaryPathFor(hashA, ".bin")
        fake.putTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.SERVER to 500
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1, 2))
        assertTrue(outcome is UploadOutcome.IndexFailed)
        assertTrue("新建 binary 也必须保留（不许回滚）", fake.blobs.containsKey(binary))
        assertTrue("绝不主动 DELETE", fake.deleted.isEmpty())
    }

    // B + F：其他设备索引已引用该 hash → index 失败时同样保留
    @Test
    fun existingBinaryKeptWhenIndexFails() = runBlocking {
        val fake = FakeWebDav()
        val binary = CloudLibrary.binaryPathFor(hashA, ".bin")
        fake.blobs[binary] = byteArrayOf(7, 7, 7)
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1", me))
        fake.texts[CloudLibrary.indexPathFor("win-1")] = otherIndexJson(item(hashA, 100))
        fake.putTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.SERVER to 500

        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(7, 7, 7))
        assertTrue(outcome is UploadOutcome.IndexFailed)
        assertTrue("binary 必须保留", fake.blobs.containsKey(binary))
        assertTrue("不得产生任何 DELETE", fake.deleted.isEmpty())
    }

    // 3) 其它设备引用 + 索引还读不到（partial）→ 仍然保留
    @Test
    fun partialIndexReadNeverDeletes() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1", me))
        fake.getTextFail[CloudLibrary.indexPathFor("win-1")] = WebDavError.SERVER to 500
        fake.putTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.SERVER to 500
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1))
        assertTrue(outcome is UploadOutcome.IndexFailed)
        assertTrue(fake.blobs.containsKey(CloudLibrary.binaryPathFor(hashA, ".bin")))
        assertTrue(fake.deleted.isEmpty())
    }

    // 4) 并发上传者场景：任何失败路径都不得主动 DELETE
    @Test
    fun concurrentUploaderNeverTriggersDelete() = runBlocking {
        val fake = FakeWebDav()
        val binary = CloudLibrary.binaryPathFor(hashA, ".bin")
        // 模拟另一台上传者正在写：devices 已有两台，对方 index 暂时不可读
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1", me))
        fake.getTextFail[CloudLibrary.indexPathFor("win-1")] = WebDavError.TIMEOUT to 0
        fake.putTextFail[CloudLibrary.indexPathFor(me)] = WebDavError.NETWORK to 0
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1))
        assertTrue(outcome is UploadOutcome.IndexFailed)
        assertTrue("并发场景下绝不主动 DELETE", fake.deleted.isEmpty())
        assertNotNull(fake.blobs[binary])
    }

    // C：已存在 binary + index 成功 → uploadedAt 刷新，且不产生副本
    @Test
    fun existingBinaryReuploadRefreshesUploadedAt() = runBlocking {
        val fake = FakeWebDav()
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1", me))
        fake.texts[CloudLibrary.indexPathFor("win-1")] = otherIndexJson(item(hashA, 100))
        fake.texts[CloudLibrary.indexPathFor(me)] = CloudLibraryCodec.serializeIndex(
            CloudLibraryIndex(deviceId = me, items = listOf(item(hashA, 100))),
        )
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(5), now = 900)
        assertTrue(outcome is UploadOutcome.Success)
        val mine = CloudLibraryCodec.parseIndex(fake.texts[CloudLibrary.indexPathFor(me)])!!
        assertEquals("同 hash 不得产生第二条", 1, mine.items.size)
        assertEquals("必须刷新 uploadedAt（延长 TTL）", 900L, mine.items[0].uploadedAt)
        assertEquals("binary 只允许一个 hash 文件", 1, fake.blobs.size)
    }

    // D：同 hash 重传成功不破坏其他设备的 index
    @Test
    fun reuploadDoesNotDamageOtherIndexes() = runBlocking {
        val fake = FakeWebDav()
        val other = otherIndexJson(item(hashA, 100))
        fake.texts[CloudLibrary.DEVICES_PATH] = CloudLibraryCodec.serializeDeviceIds(listOf("win-1", me))
        fake.texts[CloudLibrary.indexPathFor("win-1")] = other
        fake.blobs[CloudLibrary.binaryPathFor(hashA, ".bin")] = byteArrayOf(1)
        val outcome = remote(fake).upload(hashA, ".bin", "a.bin", CloudLibraryItem.TYPE_FILE, byteArrayOf(1), now = 700)
        assertTrue(outcome is UploadOutcome.Success)
        assertEquals("其他设备 index 必须原样保留", other, fake.texts[CloudLibrary.indexPathFor("win-1")])
        assertTrue(fake.blobs.containsKey(CloudLibrary.binaryPathFor(hashA, ".bin")))
    }
}
