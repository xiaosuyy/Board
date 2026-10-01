package com.board.ime.cloudlibrary

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud Library 索引 / 设备注册表的编解码与合并规则。
 *
 * 与 Windows 端 `CloudLibrary.ParseIndex/SerializeIndex/MergeIndexes/DeletableItems`
 * **语义逐条一致**，且不依赖 Android，可直接 JVM 单测。
 */
object CloudLibraryCodec {

    /** 坏 JSON 一律安全忽略（返回 null） */
    fun parseIndex(json: String?): CloudLibraryIndex? {
        if (json.isNullOrEmpty()) return null
        return try {
            val root = JSONObject(json)
            val array = root.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<CloudLibraryItem>(array.length())
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val hash = obj.optString("hash")
                if (hash.isEmpty()) continue
                items.add(
                    CloudLibraryItem(
                        hash = hash,
                        type = obj.optString("type").ifEmpty { CloudLibraryItem.TYPE_FILE },
                        name = obj.optString("name"),
                        size = obj.optLong("size"),
                        uploadedAt = obj.optLong("uploadedAt"),
                        deviceId = obj.optString("deviceId"),
                    ),
                )
            }
            CloudLibraryIndex(
                schema = root.optInt("schema", 1),
                deviceId = root.optString("deviceId"),
                deviceType = root.optString("deviceType"),
                updatedAt = root.optLong("updatedAt"),
                items = items,
            )
        } catch (throwable: Throwable) {
            null
        }
    }

    fun serializeIndex(index: CloudLibraryIndex?): String {
        if (index == null) return "{}"
        val root = JSONObject()
        root.put("schema", index.schema)
        root.put("deviceId", index.deviceId)
        root.put("deviceType", index.deviceType)
        root.put("updatedAt", index.updatedAt)
        val array = JSONArray()
        for (item in index.items) {
            array.put(
                JSONObject().apply {
                    put("hash", item.hash)
                    put("type", item.type)
                    put("name", item.name)
                    put("size", item.size)
                    put("uploadedAt", item.uploadedAt)
                    put("deviceId", item.deviceId)
                },
            )
        }
        root.put("items", array)
        return root.toString()
    }

    /** `/library/devices.json` → 设备 id 列表（净化 + 去重 + 保序）；坏 JSON 返回空 */
    fun parseDeviceIds(json: String?): List<String> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            val array = JSONObject(json).optJSONArray("devices") ?: JSONArray()
            val result = ArrayList<String>(array.length())
            for (index in 0 until array.length()) {
                val raw = array.optString(index)
                if (raw.isBlank()) continue
                val id = CloudLibrary.sanitizeToken(raw)
                if (!result.contains(id)) result.add(id)
            }
            result
        } catch (throwable: Throwable) {
            emptyList()
        }
    }

    /**
     * 从各设备索引里抽出 `deviceId -> deviceType`（**只读**，不改远端 schema）。
     *
     * 用于 UI 把 `item.deviceId` 映射回来源设备类型（`云端 · Windows`）。
     * 缺失/空值由调用方安全退化为 `云端`。
     */
    fun deviceTypes(indexJsons: List<String>?): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        if (indexJsons != null) {
            for (json in indexJsons) {
                val index = parseIndex(json) ?: continue
                val id = index.deviceId
                if (id.isNullOrEmpty()) continue
                val type = index.deviceType.orEmpty()
                val existing = map[id]
                if (existing == null || existing.isEmpty()) map[id] = type
            }
        }
        return map
    }

    fun serializeDeviceIds(ids: List<String>): String {
        val array = JSONArray()
        for (id in ids) array.put(CloudLibrary.sanitizeToken(id))
        return JSONObject().apply {
            put("schema", 1)
            put("devices", array)
        }.toString()
    }

    /**
     * 多设备索引合并：**按 hash 去重，`uploadedAt` 新的胜**
     * （= 从最近一次主动上传算 TTL）。按 `uploadedAt` 倒序。
     */
    fun mergeIndexes(indexJsons: List<String>?): List<CloudLibraryItem> {
        val map = LinkedHashMap<String, CloudLibraryItem>()
        if (indexJsons != null) {
            for (json in indexJsons) {
                val index = parseIndex(json) ?: continue
                for (item in index.items) {
                    if (item.hash.isEmpty()) continue
                    val existing = map[item.hash]
                    if (existing == null || item.uploadedAt > existing.uploadedAt) {
                        map[item.hash] = item
                    }
                }
            }
        }
        return map.values.sortedByDescending { it.uploadedAt }
    }

    /**
     * 可删除判定（**关键安全规则**）：只有该 hash 在**所有设备**的合并结果里都已过期才可删。
     * 合并取 `max(uploadedAt)` ⇒ 任一设备未过期 ⇒ 合并值未过期 ⇒ 不出现在结果里。
     */
    fun deletableItems(now: Long, merged: List<CloudLibraryItem>?): List<CloudLibraryItem> {
        if (merged == null) return emptyList()
        return merged.filter { it.hash.isNotEmpty() && CloudLibrary.isExpired(now, it.uploadedAt) }
    }
}
