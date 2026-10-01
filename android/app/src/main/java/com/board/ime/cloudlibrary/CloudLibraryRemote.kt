package com.board.ime.cloudlibrary

import com.board.ime.sync.webdav.WebDavClient
import com.board.ime.sync.webdav.WebDavError
import com.board.ime.sync.webdav.WebDavResponse

/**
 * Library 读取结果。
 *
 * - [Ok]：读到合法内容
 * - [Missing]：**404 = 正常“还没有”**（首次使用 / 该设备没有索引），不是错误
 * - [Failure]：明确失败（401/403/5xx/网络/坏 JSON）—— 调用方**不得**据此删除任何东西
 */
sealed class LibraryRead<out T> {
    data class Ok<T>(val value: T) : LibraryRead<T>()
    object Missing : LibraryRead<Nothing>()
    data class Failure(val error: WebDavError, val httpCode: Int, val message: String?) : LibraryRead<Nothing>()
}

/** 上传结果（**只有 index 成功才算成功**） */
sealed class UploadOutcome {
    object Success : UploadOutcome()
    /** 本地就能判定不接受（非法 hash / 超 10 MiB），**未发起任何请求** */
    data class Rejected(val reason: String) : UploadOutcome()
    /** binary 失败 ⇒ 不写 index */
    data class BinaryFailed(val error: WebDavError, val httpCode: Int) : UploadOutcome()
    /**
     * index 失败。
     *
     * **本阶段不做 binary 回滚**：
     * `index PUT failure may leave an orphan binary; safety takes precedence over cleanup.`
     *
     * 在没有 ETag/If-Match、锁、事务、PROPFIND 的前提下，无法证明「先检查引用、再 DELETE」
     * 绝对安全（检查与删除之间另一个上传者可能刚写入 index ⇒ dangling reference）。
     * 因此**宁可留下 orphan binary，也绝不删除可能被其它设备引用的 binary**。
     */
    data class IndexFailed(val error: WebDavError, val httpCode: Int) : UploadOutcome()
}

/**
 * 一批设备索引的读取结果。
 *
 * [partial] = true 表示**至少有一台设备读取失败**：
 * 调用方**绝不能**据此判定“过期/可删除”（多设备保护的前提是读全）。
 */
data class IndexBatch(
    val jsons: List<String>,
    val deviceIds: List<String>,
    val partial: Boolean,
)

/**
 * Cloud Library **远端层**：只做 `/library/` 的协议读写。
 *
 * - 只依赖 [WebDavClient] 接口（可注入 fake，单测不碰真实服务器）
 * - **不切线程**（全是 suspend）：执行线程由上层 Repository 决定
 * - 无 UI / Toast / Activity / SharedPreferences / 本地落盘 / 缩略图缓存
 * - 不碰 `/file/`、不碰 `SyncClipboard.json`、不碰 B1 的 `clipboard-log/`
 * - 日志只允许 status / size / type / 结果（[log] 默认空实现）
 *
 * 语义与 Windows 1-H `CloudLibraryService` 基本一致，**有一处有意偏离**：
 * - 上传：`PUT binary → PUT index`；**index 失败时不回滚 binary**
 *   （`index PUT failure may leave an orphan binary; safety takes precedence over cleanup.`
 *   无 ETag/If-Match、锁、事务、PROPFIND 时无法证明「检查引用后 DELETE」绝对安全，
 *   宁可留 orphan，也绝不删除可能被其它设备引用的 binary）；
 * - DELETE 404 = 成功（幂等）。
 */
class CloudLibraryRemote(
    private val client: WebDavClient,
    private val deviceId: String,
    private val deviceType: String = "Android",
    private val log: (String) -> Unit = {},
) {

    // ---------------------------------------------------------------- 读取

    /** `GET /library/devices.json`；404 = 暂无设备（不是错误）；坏 JSON = 明确失败 */
    suspend fun readDeviceIds(): LibraryRead<List<String>> {
        val response = client.getText(CloudLibrary.DEVICES_PATH)
        return when {
            response.isOk -> {
                val raw = response.value.orEmpty()
                if (raw.isNotEmpty() && !isValidJson(raw)) {
                    LibraryRead.Failure(WebDavError.PROTOCOL, response.httpCode, "devices.json 不是合法 JSON")
                } else {
                    LibraryRead.Ok(CloudLibraryCodec.parseDeviceIds(raw))
                }
            }

            response.error == WebDavError.NOT_FOUND -> LibraryRead.Missing
            else -> failure(response)
        }
    }

    private fun isValidJson(raw: String): Boolean = try {
        org.json.JSONObject(raw)
        true
    } catch (throwable: Throwable) {
        false
    }

    /** `GET /library/index-<deviceId>.json` 的**原始文本**（坏 JSON = PROTOCOL 失败） */
    suspend fun readIndexRaw(deviceId: String): LibraryRead<String> {
        val response = client.getText(CloudLibrary.indexPathFor(deviceId))
        return when {
            response.isOk -> {
                val raw = response.value.orEmpty()
                if (CloudLibraryCodec.parseIndex(raw) == null) {
                    LibraryRead.Failure(WebDavError.PROTOCOL, response.httpCode, "index 不是合法 JSON")
                } else {
                    LibraryRead.Ok(raw)
                }
            }

            response.error == WebDavError.NOT_FOUND -> LibraryRead.Missing
            else -> failure(response)
        }
    }

    suspend fun readIndex(deviceId: String): LibraryRead<CloudLibraryIndex> =
        when (val read = readIndexRaw(deviceId)) {
            is LibraryRead.Ok -> LibraryRead.Ok(CloudLibraryCodec.parseIndex(read.value)!!)
            is LibraryRead.Missing -> LibraryRead.Missing
            is LibraryRead.Failure -> read
        }

    /**
     * 读取所有设备的索引（`devices.json` + 自己）。
     *
     * **404 只代表没有索引，绝不删除 `devices.json` 里的 deviceId。**
     */
    suspend fun readIndexes(): IndexBatch {
        val ids = when (val devices = readDeviceIds()) {
            is LibraryRead.Ok -> devices.value
            LibraryRead.Missing -> emptyList()
            is LibraryRead.Failure -> return IndexBatch(emptyList(), emptyList(), partial = true)
        }
        val all = LinkedHashSet<String>(ids)
        all.add(CloudLibrary.sanitizeToken(deviceId))

        val jsons = ArrayList<String>()
        var partial = false
        for (id in all) {
            when (val read = readIndexRaw(id)) {
                is LibraryRead.Ok -> jsons.add(read.value)
                LibraryRead.Missing -> Unit            // 404：空索引，保留 deviceId
                is LibraryRead.Failure -> partial = true
            }
        }
        return IndexBatch(jsons, all.toList(), partial)
    }

    /**
     * `GET /library/<hash><ext>` —— **原始 bytes**，不做任何规范化、不落盘。
     */
    suspend fun downloadBinary(hash: String, extension: String): LibraryRead<ByteArray> {
        val safeHash = CloudLibrary.sanitizeHashOrNull(hash)
            ?: return LibraryRead.Failure(WebDavError.PROTOCOL, 0, "非法 hash")
        val response = client.getBytes(CloudLibrary.binaryPathFor(safeHash, extension))
        return when {
            response.isOk -> {
                val bytes = response.value ?: ByteArray(0)
                if (bytes.isEmpty() || !CloudLibrary.withinLimit(bytes.size.toLong())) {
                    LibraryRead.Failure(WebDavError.PROTOCOL, response.httpCode, "binary 为空或超限")
                } else {
                    LibraryRead.Ok(bytes)
                }
            }

            response.error == WebDavError.NOT_FOUND -> LibraryRead.Missing
            else -> failure(response)
        }
    }

    // ---------------------------------------------------------------- 上传

    /**
     * 主动上传（调用方已保证是**用户显式动作**，且 [bytes] 已读进内存）。
     *
     * 顺序（与 Windows 一致）：
     * `PUT binary` → `GET 自己 index` → upsert（刷新 `uploadedAt`）→ `PUT index` → 自注册 devices。
     * - binary 失败 ⇒ 不写 index
     * - index 失败 ⇒ 尽力 DELETE 本次 binary，且**不报告成功**
     */
    suspend fun upload(
        hash: String,
        extension: String,
        name: String,
        type: String,
        bytes: ByteArray,
        now: Long = System.currentTimeMillis(),
    ): UploadOutcome {
        val safeHash = CloudLibrary.sanitizeHashOrNull(hash)
            ?: return UploadOutcome.Rejected("非法 hash")
        if (!CloudLibrary.withinLimit(bytes.size.toLong())) {
            return UploadOutcome.Rejected("超过 10 MiB 上限")
        }

        val putBinary = client.putBytes(
            CloudLibrary.binaryPathFor(safeHash, extension),
            bytes,
            contentTypeFor(extension, type),
        )
        if (!putBinary.isOk) {
            log("library upload binary status=${putBinary.httpCode} size=${bytes.size} result=fail")
            return UploadOutcome.BinaryFailed(putBinary.error, putBinary.httpCode)
        }

        val existing = when (val read = readIndexRaw(deviceId)) {
            is LibraryRead.Ok -> CloudLibraryCodec.parseIndex(read.value)
            LibraryRead.Missing -> null
            is LibraryRead.Failure -> {
                // 读不到自己的 index：不做破坏性回滚，但**不报告成功**
                log("library upload index-read status=${read.httpCode} result=fail")
                return UploadOutcome.IndexFailed(read.error, read.httpCode)
            }
        }

        val me = CloudLibrary.sanitizeToken(deviceId)
        val items = ArrayList(existing?.items ?: emptyList())
        items.removeAll { it.hash == safeHash }
        items.add(
            CloudLibraryItem(
                hash = safeHash,
                type = type,
                name = CloudLibrary.sanitizeFileName(name),
                size = bytes.size.toLong(),
                uploadedAt = now,       // 重复 hash 同样刷新 ⇒ 延长 24h TTL
                deviceId = me,
            ),
        )
        val index = CloudLibraryIndex(
            schema = existing?.schema ?: 1,
            deviceId = me,
            deviceType = deviceType,
            updatedAt = now,
            items = items,
        )
        val putIndex = client.putText(
            CloudLibrary.indexPathFor(deviceId),
            CloudLibraryCodec.serializeIndex(index),
            CONTENT_TYPE_JSON,
        )
        if (!putIndex.isOk) {
            // **绝不删除 binary**（见 IndexFailed 注释）：留下 orphan binary，
            // 也绝不冒「删掉别人仍可能引用的 binary」的风险。
            log("library upload index status=${putIndex.httpCode} result=fail orphan_binary=true")
            return UploadOutcome.IndexFailed(putIndex.error, putIndex.httpCode)
        }

        val registered = ensureRegistered()
        log("library upload status=${putIndex.httpCode} size=${bytes.size} type=$type registered=$registered result=ok")
        return UploadOutcome.Success
    }

    /**
     * `DELETE /library/<hash><ext>`
     *
     * `200/204 → 成功`，`404 → 成功（幂等）`，其余 → 失败。
     * **不在这里删 index** —— “DELETE binary 成功之后再删 index”由 Repository/Prune 负责。
     */
    suspend fun deleteBinary(hash: String, extension: String): LibraryRead<Unit> {
        val safeHash = CloudLibrary.sanitizeHashOrNull(hash)
            ?: return LibraryRead.Failure(WebDavError.PROTOCOL, 0, "非法 hash")
        val response = client.delete(CloudLibrary.binaryPathFor(safeHash, extension))
        return when {
            response.isOk -> LibraryRead.Ok(Unit)
            response.error == WebDavError.NOT_FOUND -> LibraryRead.Ok(Unit)
            else -> failure(response)
        }
    }

    /**
     * 原样 PUT 自己的 index。
     *
     * **不是新协议**：路径 / body 形状与 [upload] 内部完全一致，只是把「写自己的索引」
     * 暴露出来，供 Repository 在 prune 之后重写条目（`DELETE binary` 成功后才移除）。
     */
    suspend fun putOwnIndex(index: CloudLibraryIndex): Boolean {
        val put = client.putText(
            CloudLibrary.indexPathFor(deviceId),
            CloudLibraryCodec.serializeIndex(index),
            CONTENT_TYPE_JSON,
        )
        return put.isOk
    }

    /**
     * 把自己登记进 `/library/devices.json`（**只追加，不删除别人**）。
     * 404 时创建 `{"schema":1,"devices":[...]}`。
     *
     * 注意：这是 GET → modify → PUT，**本阶段不声称完全无竞态**；
     * 失败不会破坏本地状态，下次主动上传会再次尝试自注册。
     */
    suspend fun ensureRegistered(): Boolean {
        val me = CloudLibrary.sanitizeToken(deviceId)
        val current = when (val read = readDeviceIds()) {
            is LibraryRead.Ok -> ArrayList(read.value)
            LibraryRead.Missing -> ArrayList()
            is LibraryRead.Failure -> return false
        }
        if (current.contains(me)) return true
        current.add(me)
        val put = client.putText(
            CloudLibrary.DEVICES_PATH,
            CloudLibraryCodec.serializeDeviceIds(current),
            CONTENT_TYPE_JSON,
        )
        return put.isOk
    }

    // ---------------------------------------------------------------- 内部

    private fun <T> failure(response: WebDavResponse<T>): LibraryRead.Failure =
        LibraryRead.Failure(response.error, response.httpCode, response.message)

    private fun contentTypeFor(extension: String, type: String): String =
        when (CloudLibrary.normalizeExtension(extension)) {
            ".png" -> "image/png"
            ".jpg", ".jpeg" -> "image/jpeg"
            ".gif" -> "image/gif"
            ".webp" -> "image/webp"
            ".pdf" -> "application/pdf"
            ".zip" -> "application/zip"
            ".txt" -> "text/plain"
            else -> if (type.equals(CloudLibraryItem.TYPE_IMAGE, ignoreCase = true)) "image/*" else "application/octet-stream"
        }

    private companion object {
        const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
    }
}
