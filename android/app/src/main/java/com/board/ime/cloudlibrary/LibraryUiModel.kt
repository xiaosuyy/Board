package com.board.ime.cloudlibrary

/**
 * Library 卡片（**统一混合列表**：图片与文件不再分区）。
 *
 * - 图片：[canPreview] = true（点主体进预览）
 * - 文件：[canPreview] = false（不预览 / 不打开 / 不详情）
 * - 两者都有：下载 + 隐藏
 */
data class LibraryRow(
    val item: CloudLibraryItem,
    /** 显示名（已净化） */
    val name: String,
    /** 大小文案（B / KB / MB） */
    val sizeLabel: String,
    /** 来源文案：本地 / 云端 · <设备类型>；颜色由 UI 按 [isLocal] 取灰 / accent */
    val sourceLabel: String,
    val isLocal: Boolean,
    val canPreview: Boolean,
    val canDownload: Boolean,
) {
    val isImage: Boolean get() = canPreview
}

/**
 * Library UI 的纯逻辑。
 *
 * **不引用任何 Android / Clipboard 组件**（结构断言可验证），可在 JVM 单测里直接覆盖 UI 契约。
 */
object LibraryUiModel {

    /** Library 页面 RESUMED 时的自动刷新周期（低频；不是轮询） */
    const val AUTO_REFRESH_MS: Long = 60_000L

    /** 与 `ClipboardSummary.sizeLabel` 同一套格式 */
    fun sizeLabel(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun toRow(
        item: CloudLibraryItem,
        myDeviceId: String?,
        deviceType: String? = null,
    ): LibraryRow {
        val local = !item.deviceId.isNullOrEmpty() && item.deviceId == myDeviceId
        val image = CloudLibrary.isImage(item.type)
        return LibraryRow(
            item = item,
            name = CloudLibrary.sanitizeFileName(item.name),
            sizeLabel = sizeLabel(item.size),
            sourceLabel = if (local) "本地" else CloudLibrary.originText(item.deviceId, myDeviceId, deviceType),
            isLocal = local,
            // 文件绝不预览
            canPreview = image,
            canDownload = true,
        )
    }

    /**
     * **统一混合列表**（唯一入口）：
     * 1. 过滤掉**本机隐藏**的 hash（只影响本机 UI，不影响云端）
     * 2. `uploadedAt` **DESC**（越新越靠前）
     * 3. `uploadedAt` 相同时按 `hash` 稳定排序 —— 避免每次刷新顺序抖动
     */
    fun rows(
        items: List<CloudLibraryItem>,
        myDeviceId: String?,
        deviceTypes: Map<String, String> = emptyMap(),
        hiddenHashes: Set<String> = emptySet(),
    ): List<LibraryRow> = items
        .asSequence()
        .filter { !hiddenHashes.contains(it.hash) }
        .sortedWith(compareByDescending<CloudLibraryItem> { it.uploadedAt }.thenBy { it.hash })
        .map { toRow(it, myDeviceId, deviceTypes[it.deviceId]) }
        .toList()

    /** 卡片主体是否可点击进入预览：**只有图片** */
    fun isBodyClickable(row: LibraryRow): Boolean = row.canPreview

    /** 当前应该加载缩略图的 hash 集合（**仅图片**） */
    fun liveHashes(items: List<CloudLibraryItem>): Set<String> =
        items.filter { CloudLibrary.isImage(it.type) }.map { it.hash }.toSet()

    /**
     * 缩略图淘汰：缓存里**已不存在**的 hash。
     * 增量刷新后只清理这些，其余 hash 继续复用 Bitmap（不重复下载）。
     */
    fun <T> staleThumbnailKeys(cache: Map<String, T>, liveHashes: Set<String>): List<String> =
        cache.keys.filter { !liveHashes.contains(it) }

    /** 空列表文案（只有真正成功且为空、且没有隐藏项时才显示「暂无资料」） */
    fun emptyText(snapshot: LibrarySnapshot): String = when {
        snapshot.partial -> "部分设备暂时不可读"
        snapshot.error != LibraryError.NONE -> snapshot.error.message
        else -> "暂无资料"
    }
}
