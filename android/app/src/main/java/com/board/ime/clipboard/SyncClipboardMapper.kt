package com.board.ime.clipboard

import com.board.ime.database.entity.ClipboardItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * SyncClipboard 协议的一条记录。
 *
 * 字段名刻意与 SyncClipboard（`SyncClipboard.json`）保持一致：
 * `type` / `hash` / `text` / `hasData` / `dataName` / `size`。
 */
data class SyncClipboardPayload(
    val type: String,
    val hash: String,
    val text: String? = null,
    val hasData: Boolean = false,
    val dataName: String? = null,
    val size: Long = 0L,
    /**
     * 可选的设备标识。
     *
     * SyncClipboard 的 `SyncClipboard.json` **没有**设备字段，所以这是 Board 侧的
     * 向前兼容扩展：读得到就用来显示「云端 · Windows」，读不到就显示「云端」。
     * Board 自己**不写**这个字段，避免让别的客户端解析到不认识的键。
     */
    val device: String? = null,
)

/**
 * SyncClipboard 协议里的固定路径。
 *
 * 官方文档（SyncClipboard）：
 * ```
 * GET/PUT /SyncClipboard.json      当前剪贴板记录
 * GET/PUT /file/{dataName}         图片 / 文件的实际数据
 * ```
 */
object SyncClipboardPaths {
    const val MANIFEST = "SyncClipboard.json"
    const val FILE_DIR = "file"

    fun file(dataName: String): String = "$FILE_DIR/${dataName.trim('/')}"
}

/**
 * Board 数据结构 ⇄ 第三方剪贴板协议的映射层。
 *
 * **只做映射，不做网络。** 以后接 SyncClipboard / WebDAV 时，
 * 上传下载都走这里转换，Board 自己的表结构不必被第三方协议绑死：
 * - Board 侧多出来的 `isPinned` / `isFavorite` / 敏感策略只存在于本地，
 *   协议不认识就丢弃（[fromPayload] 会补默认值）。
 */
object SyncClipboardMapper {

    const val TYPE_TEXT = "Text"
    const val TYPE_IMAGE = "Image"
    const val TYPE_FILE = "File"
    const val TYPE_GROUP = "Group"

    /**
     * 真正支持的是**文本**，包含两种形态：
     * - 短文本：`type=Text` + `text` 内联（与 SyncClipboard 官方完全兼容）
     * - 超长文本：`type=Text` + `hasData=true` + `dataName`，正文放 `/file/{dataName}`
     *   （Board 自己的扩展用法，避免几十 MB 的正文被塞进 JSON 导致内存暴涨）
     *
     * `Image` / `File` / `Group` 一律返回 false —— 上层必须**安全忽略并记录日志**，
     * 不允许把不支持的类型伪装成已同步。
     */
    fun isSupported(payload: SyncClipboardPayload): Boolean = payload.type == TYPE_TEXT

    /**
     * 超过这个字节数的文本不再内联进 `SyncClipboard.json`，改走 `/file/`。
     *
     * 取值考虑：JSON 里还要再转义一遍，几百 KB 的正文会让「读远端清单」这一步
     * 就要分配好几份内存；256KB 之内内联仍然很快，普通短文本协议兼容性也不受影响。
     */
    const val INLINE_TEXT_MAX_BYTES = 256 * 1024

    /** 超长文本在 `/file/` 下的名字 */
    fun dataNameFor(hash: String): String = "${hash.ifEmpty { "board" }}.txt"

    /** Board → 协议（保持内联，短文本用）。[content] 是调用方读出来的完整内容 */
    fun toPayload(item: ClipboardItem, content: String): SyncClipboardPayload {
        val type = when (item.contentType) {
            ClipboardContentType.TEXT, ClipboardContentType.LINK -> TYPE_TEXT
            ClipboardContentType.IMAGE -> TYPE_IMAGE
            ClipboardContentType.FILE -> TYPE_FILE
            ClipboardContentType.UNKNOWN -> TYPE_GROUP
        }
        val isData = item.contentType == ClipboardContentType.IMAGE ||
            item.contentType == ClipboardContentType.FILE
        val hash = item.contentHash.ifEmpty { ClipboardHasher.hash(content) }
        val bytes = ClipboardHasher.utf8Length(content)
        return SyncClipboardPayload(
            type = type,
            hash = hash,
            text = if (isData) null else content,
            hasData = isData,
            dataName = if (isData) item.preview else null,
            size = bytes,
        )
    }

    /** Board → 协议（超长文本：正文走 `/file/`，清单里只有元信息） */
    fun toFilePayload(item: ClipboardItem, content: String): SyncClipboardPayload {
        val hash = item.contentHash.ifEmpty { ClipboardHasher.hash(content) }
        return SyncClipboardPayload(
            type = TYPE_TEXT,
            hash = hash,
            text = null,
            hasData = true,
            dataName = dataNameFor(hash),
            size = ClipboardHasher.utf8Length(content),
        )
    }

    /** 协议 → Board（**不写库**，调用方自己决定是否入库） */
    fun fromPayload(payload: SyncClipboardPayload): ClipboardItem? {
        val text = payload.text
            // 正文在 /file/ 里（hasData），这里没有内容可映射；
            // 由 SyncEngine 下载文件后再调用 applyRemote
            ?: return null
        val contentType = when (payload.type) {
            TYPE_TEXT -> if (LINK_REGEX.matches(text.trim())) {
                ClipboardContentType.LINK
            } else {
                ClipboardContentType.TEXT
            }

            TYPE_IMAGE -> ClipboardContentType.IMAGE
            TYPE_FILE -> ClipboardContentType.FILE
            else -> ClipboardContentType.UNKNOWN
        }
        if (text.isEmpty()) return null
        return ClipboardItem(
            content = text,
            contentType = contentType,
            contentHash = payload.hash.ifEmpty { ClipboardHasher.hash(text) },
            // 从别的设备来的记录先按「本机之外的来源」标记，本轮不参与同步回写
            sourceDeviceId = SOURCE_REMOTE,
        )
    }

    /** 单条 → `SyncClipboard.json` 形状的字符串 */
    fun payloadToJson(payload: SyncClipboardPayload): String = toJsonObject(payload).toString(2)

    fun payloadFromJson(json: String): SyncClipboardPayload? = try {
        fromJsonObject(JSONObject(json))
    } catch (throwable: Throwable) {
        null
    }

    /**
     * 整段历史 → JSON 数组（导出用）。
     *
     * 只接受 [ClipboardSummary]：超长内容现在存在文件里，这条导出路径
     * **不会**去读完整内容（否则导出几十条 MB 级记录会把内存打爆）。
     * 需要完整正文时用真正的同步路径（按条读 + `/file/`）。
     */
    fun historyToJson(items: List<ClipboardSummary>): String = JSONArray().apply {
        items.forEach { item ->
            put(
                toJsonObject(
                    SyncClipboardPayload(
                        type = TYPE_TEXT,
                        hash = item.contentHash,
                        text = item.preview,
                        hasData = item.isFileBacked,
                        dataName = if (item.isFileBacked) dataNameFor(item.contentHash) else null,
                        size = item.contentSize,
                    ),
                ),
            )
        }
    }.toString(2)

    fun historyFromJson(json: String): List<ClipboardItem> = try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { fromJsonObject(it) }?.let(::fromPayload)
        }
    } catch (throwable: Throwable) {
        emptyList()
    }

    private fun toJsonObject(payload: SyncClipboardPayload): JSONObject = JSONObject().apply {
        put("type", payload.type)
        put("hash", payload.hash)
        put("text", payload.text ?: JSONObject.NULL)
        put("hasData", payload.hasData)
        put("dataName", payload.dataName ?: "")
        put("size", payload.size)
    }

    private fun fromJsonObject(json: JSONObject): SyncClipboardPayload = SyncClipboardPayload(
        type = json.optString("type", TYPE_TEXT),
        hash = json.optString("hash"),
        text = json.optString("text").takeIf { it.isNotEmpty() && !json.isNull("text") },
        hasData = json.optBoolean("hasData", false),
        dataName = json.optString("dataName").takeIf { it.isNotEmpty() },
        size = json.optLong("size", 0L),
        // 可选扩展：有就带上，没有就是 null
        device = listOf("device", "deviceName", "from")
            .firstNotNullOfOrNull { key ->
                json.optString(key).takeIf { it.isNotEmpty() && !json.isNull(key) }
            },
    )

    private const val SOURCE_REMOTE = "remote"
    private val LINK_REGEX = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
}
