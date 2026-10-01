package com.board.ime.cloudlibrary

/**
 * Cloud Library（跨设备云端资料库）的**纯本地模型 + 规则**。
 *
 * 与 Windows 端 `CloudLibrary.cs`（Phase 1-H 已封板）逐条对齐：
 * - 远端：`/library/<hash><ext>`、`/library/index-<deviceId>.json`、`/library/devices.json`
 * - 24h TTL、多设备 `max(uploadedAt)` 保护、10 MiB 上限、原始 bytes / 原始扩展名
 * - 不用 PROPFIND，靠 `devices.json` 枚举设备
 *
 * 与 Clipboard **严格隔离**：不同目录、不同模型、不同生命周期；不自动互转。
 *
 * 本文件**不依赖 Android**（只有常量与纯函数），可直接 JVM 单测。
 */
data class CloudLibraryItem(
    val hash: String = "",
    /** `Image` / `File` */
    val type: String = TYPE_FILE,
    val name: String = "",
    val size: Long = 0L,
    val uploadedAt: Long = 0L,
    val deviceId: String = "",
) {
    companion object {
        const val TYPE_IMAGE = "Image"
        const val TYPE_FILE = "File"
    }
}

data class CloudLibraryIndex(
    val schema: Int = 1,
    val deviceId: String = "",
    val deviceType: String = "Android",
    val updatedAt: Long = 0L,
    val items: List<CloudLibraryItem> = emptyList(),
)

/**
 * 与 Windows `CloudLibrary` 同名的纯规则。**不要在这里加 Android 依赖。**
 */
object CloudLibrary {

    /** 单文件上限（两端一致 = 10 MiB） */
    const val MAX_LIBRARY_BYTES: Long = 10L * 1024L * 1024L

    /** 24 小时 TTL */
    const val TTL_MS: Long = 24L * 60L * 60L * 1000L

    const val FOLDER = "library"
    const val INDEX_PREFIX = "index-"
    const val DEVICES_PATH = "library/devices.json"

    /** 图片下载目标目录（相册可见）：`Pictures/Board` */
    const val IMAGE_DIR = "Pictures/Board"

    /** 文件下载目标目录：`Downloads/Board` */
    const val FILE_DIR = "Downloads/Board"

    fun isExpired(now: Long, uploadedAt: Long): Boolean = now - uploadedAt >= TTL_MS

    fun withinLimit(bytes: Long): Boolean = bytes > 0L && bytes <= MAX_LIBRARY_BYTES

    fun indexPathFor(deviceId: String?): String =
        "$FOLDER/$INDEX_PREFIX${sanitizeToken(deviceId)}.json"

    fun binaryPathFor(hash: String?, extension: String?): String =
        "$FOLDER/${if (hash.isNullOrEmpty()) "board" else hash}${normalizeExtension(extension)}"

    /** 日志用：设备 id 前缀（不记完整 token） */
    fun shortDevice(deviceId: String?): String {
        if (deviceId.isNullOrEmpty()) return "-"
        return if (deviceId.length <= 8) deviceId else deviceId.substring(0, 8)
    }

    /** deviceId 只保留字母数字与 `-`（索引文件名安全，防路径穿越） */
    fun sanitizeToken(value: String?): String {
        if (value.isNullOrEmpty()) return "device"
        val builder = StringBuilder()
        for (c in value) {
            val ok = (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-'
            if (ok && builder.length < 64) builder.append(c)
        }
        return if (builder.isEmpty()) "device" else builder.toString()
    }

    /** 文件展示名净化：去掉目录部分与控制/非法字符（只用于展示与落盘命名） */
    fun sanitizeFileName(name: String?): String {
        if (name.isNullOrEmpty()) return "board"
        val cut = maxOf(name.lastIndexOf('\\'), name.lastIndexOf('/'))
        val base = if (cut >= 0) name.substring(cut + 1) else name
        val builder = StringBuilder()
        for (c in base) {
            if (c < 32.toChar() || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') continue
            if (builder.length < 128) builder.append(c)
        }
        return if (builder.isEmpty()) "board" else builder.toString()
    }

    /** 扩展名净化：留 `.ext`（字母数字，小写，最长 10），其余丢弃 */
    fun normalizeExtension(extension: String?): String {
        if (extension.isNullOrEmpty()) return ""
        val trimmed = extension.trim().trimStart('.')
        val builder = StringBuilder()
        for (c in trimmed) {
            if (c.isLetterOrDigit() && builder.length < 10) builder.append(c)
        }
        return if (builder.isEmpty()) "" else "." + builder.toString().lowercase()
    }

    /**
     * 来源文案：**本机主动上传 = 本地；其它设备 = 云端 · <设备类型>**。
     * 不新增第三套状态；颜色由调用方按「本地 / 云端」映射。
     */
    fun originText(itemDeviceId: String?, myDeviceId: String?, deviceType: String?): String {
        if (!itemDeviceId.isNullOrEmpty() && itemDeviceId == myDeviceId) return "本地"
        return if (deviceType.isNullOrEmpty()) "云端" else "云端 · $deviceType"
    }

    /**
     * 点击主体是否进入预览：**只有图片**允许（文件一律不预览）。
     *
     * **大小写不敏感**：Windows 1-H 写入的 `type` 是小写 `"image"`，Android 早期用
     * 大写 `"Image"` 比较会导致图片被误判为文件（真机验收已复现）。
     */
    fun hasPreview(type: String?): Boolean = isImage(type)

    /** **大小写不敏感**的图片判定（跨端兼容：`image` / `Image` / `IMAGE` 均为图片） */
    fun isImage(type: String?): Boolean =
        type != null && type.equals(CloudLibraryItem.TYPE_IMAGE, ignoreCase = true)

    /**
     * hash 净化：**只保留小写十六进制**（SHA-256），防止 `../`、反斜杠等路径穿越。
     * 长度不足 32 视为非法 → 返回 null（调用方必须拒绝，不做静默兜底）。
     */
    fun sanitizeHashOrNull(hash: String?): String? {
        if (hash.isNullOrEmpty()) return null
        val builder = StringBuilder()
        for (c in hash) {
            val lower = c.lowercaseChar()
            if ((lower in '0'..'9' || lower in 'a'..'f') && builder.length < 64) builder.append(lower)
            if (builder.length >= 64) break
        }
        return if (builder.length >= 32) builder.toString() else null
    }

    /** 内容类型 → 协议 type 字段 */
    fun typeFor(mime: String?, name: String?): String {
        if (mime != null && mime.startsWith("image/", ignoreCase = true)) return CloudLibraryItem.TYPE_IMAGE
        val ext = normalizeExtension(name?.substringAfterLast('.', ""))
        return if (ext in IMAGE_EXTENSIONS) CloudLibraryItem.TYPE_IMAGE else CloudLibraryItem.TYPE_FILE
    }

    private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp")

    /** 下载时需要写进相册（MediaStore）还是普通 Downloads 目录 */
    fun isImageItem(type: String?): Boolean = isImage(type)
}
