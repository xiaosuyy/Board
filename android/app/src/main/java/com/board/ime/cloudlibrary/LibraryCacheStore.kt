package com.board.ime.cloudlibrary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本机 **Library 历史缓存**（App 私有存储）。
 *
 * 目的：**本地已收到的内容立即显示**，不再每次进入 Library 都等网络重建列表。
 *
 * 结构（都在 `filesDir/library-cache/`）：
 * - `index.json`：已收到的 item **metadata**（含是否已缓存正文）
 * - `<hash><ext>`：**已成功取回的正文 bytes**（原始字节，不改写）
 *
 * 边界：
 * - 只在本机使用；**不上传回云端**、不进 `SyncClipboard.json`、不进 WebDAV、
 *   不进 `ClipboardRepository` 的同步协议、不建数据库/表。
 * - 与 `hiddenLibraryHashes` 独立：隐藏只影响显示，**不删除本机缓存**。
 */
class LibraryCacheStore(context: Context) {

    private val dir = File(context.applicationContext.filesDir, DIR)
    private val indexFile = File(dir, "index.json")

    /** 一条本机缓存记录 */
    data class Entry(
        val hash: String,
        val name: String,
        val size: Long,
        val type: String,
        val uploadedAt: Long,
        val deviceId: String,
        val deviceType: String,
        val contentCached: Boolean,
    ) {
        fun toItem(): CloudLibraryItem = CloudLibraryItem(
            hash = hash,
            type = type,
            name = name,
            size = size,
            uploadedAt = uploadedAt,
            deviceId = deviceId,
        )
    }

    /** 读取本机缓存的 metadata（坏文件一律当作空，不崩溃） */
    fun load(): List<Entry> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val array = JSONArray(indexFile.readText())
            val result = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val hash = obj.optString("hash")
                if (hash.isEmpty()) continue
                result.add(
                    Entry(
                        hash = hash,
                        name = obj.optString("name"),
                        size = obj.optLong("size"),
                        type = obj.optString("type").ifEmpty { CloudLibraryItem.TYPE_FILE },
                        uploadedAt = obj.optLong("uploadedAt"),
                        deviceId = obj.optString("deviceId"),
                        deviceType = obj.optString("deviceType"),
                        contentCached = obj.optBoolean("contentCached"),
                    ),
                )
            }
            result
        } catch (throwable: Throwable) {
            emptyList()
        }
    }

    fun save(entries: List<Entry>): Boolean = try {
        dir.mkdirs()
        val array = JSONArray()
        for (e in entries) {
            array.put(
                JSONObject().apply {
                    put("hash", e.hash)
                    put("name", e.name)
                    put("size", e.size)
                    put("type", e.type)
                    put("uploadedAt", e.uploadedAt)
                    put("deviceId", e.deviceId)
                    put("deviceType", e.deviceType)
                    put("contentCached", e.contentCached)
                },
            )
        }
        indexFile.writeText(array.toString())
        true
    } catch (throwable: Throwable) {
        false
    }

    /** 正文文件位置（扩展名来自 item.name） */
    fun contentFile(hash: String, name: String): File =
        File(dir, hash + CloudLibrary.normalizeExtension(name.substringAfterLast('.', "")))

    fun hasContent(hash: String, name: String): Boolean = contentFile(hash, name).let { it.exists() && it.length() > 0 }

    /** 写入正文（**原始 bytes，不改写**）；只在下载/取回成功后调用 */
    fun writeContent(hash: String, name: String, bytes: ByteArray): Boolean = try {
        dir.mkdirs()
        contentFile(hash, name).writeBytes(bytes)
        true
    } catch (throwable: Throwable) {
        false
    }

    /** 读取本机已缓存的正文（命中则**无需再访问 WebDAV**） */
    fun readContent(hash: String, name: String): ByteArray? = try {
        val file = contentFile(hash, name)
        if (file.exists() && file.length() > 0) file.readBytes() else null
    } catch (throwable: Throwable) {
        null
    }

    fun toEntry(item: CloudLibraryItem, deviceType: String?, contentCached: Boolean) = Entry(
        hash = item.hash,
        name = item.name,
        size = item.size,
        type = item.type,
        uploadedAt = item.uploadedAt,
        deviceId = item.deviceId,
        deviceType = deviceType.orEmpty(),
        contentCached = contentCached,
    )

    private companion object {
        const val DIR = "library-cache"
    }
}

/**
 * 本机缓存 ∪ 云端 snapshot 的**合并**（本轮的核心语义）。
 *
 * - 同 hash：`uploadedAt` 新的胜（元数据以较新者为准）
 * - **本机已有、云端已消失（24h TTL prune）⇒ 保留本机条目** —— 云端 TTL 只管云端
 */
object LibraryMerge {

    fun merge(cached: List<CloudLibraryItem>, cloud: List<CloudLibraryItem>): List<CloudLibraryItem> {
        val map = LinkedHashMap<String, CloudLibraryItem>()
        for (item in cached) {
            if (item.hash.isNotEmpty()) map[item.hash] = item
        }
        for (item in cloud) {
            if (item.hash.isEmpty()) continue
            val existing = map[item.hash]
            map[item.hash] = if (existing == null || item.uploadedAt >= existing.uploadedAt) item else existing
        }
        return map.values.toList()
    }
}
