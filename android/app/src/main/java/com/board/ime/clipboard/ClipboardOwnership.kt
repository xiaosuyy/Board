package com.board.ime.clipboard

/**
 * 「这条内容是不是 Board **自己写回**系统剪贴板的」判定（Phase 1-F，**纯逻辑，可 JVM 单测**）。
 *
 * 背景：云端内容落库后会被写回系统剪贴板，系统随即回调 `OnPrimaryClipChangedListener`。
 * 如果这时按「本机新复制」处理，历史里就会多出一条 LOCAL 重复条目 ——
 * 图片尤其明显：落库用的是**远端 hash**，而回读重新编码后是**本端 hash**，两边永远对不上。
 *
 * Board 写回时用的是自己的 FileProvider URI，路径固定形如：
 * ```
 * content://<package>.fileprovider/clipboard_images/<hash>.png
 * ```
 * 只要认出这个形状，就能**直接定位到原来那条历史**，从而只刷新时间、不新建条目，
 * 让「来源身份」（LOCAL / CLOUD）保持稳定。**不新增任何协议字段。**
 */
object ClipboardOwnership {

    /** Board FileProvider 图片目录段（与 `res/xml/board_file_paths.xml` 一致） */
    const val IMAGE_PATH_SEGMENT = "clipboard_images"

    /**
     * 若给出的是 Board 自己写回的图片 URI，返回其 hash；否则返回 null。
     *
     * 不依赖 `Context` / `Uri`（由调用方拆好 `scheme` 与 `pathSegments`），方便单测。
     */
    fun ownImageHash(scheme: String?, pathSegments: List<String>?): String? {
        if (scheme == null || !scheme.equals("content", ignoreCase = true)) return null
        val segments = pathSegments ?: return null
        if (segments.size < 2) return null
        if (segments.first() != IMAGE_PATH_SEGMENT) return null
        val name = segments.last()
        if (!name.endsWith(ClipboardImageStore.PNG_SUFFIX)) return null
        return name.removeSuffix(ClipboardImageStore.PNG_SUFFIX).ifEmpty { null }
    }
}
