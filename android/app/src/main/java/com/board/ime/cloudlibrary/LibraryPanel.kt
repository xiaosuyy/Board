package com.board.ime.cloudlibrary

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.board.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云端资料库面板（1B）。
 *
 * **本地优先**：进入页面**先显示本机 Library 缓存**（立即出现，不等网络），
 * 再后台增量刷新云端 index，只把**新 hash** 加入列表。
 *
 * - 统一混合列表（图片 / 文件同列），`uploadedAt` DESC（同值按 hash 稳定）
 * - 本地已收到但云端已 TTL 消失的条目**仍然保留**（云端 TTL 只管云端）
 * - 每行：缩略图/图标 + 名称 + 大小 + 来源 + **下载** + **× 隐藏**
 * - 「×」= **本机隐藏**（只影响本机显示，绝不动云端、不删本机缓存）
 * - 缩略图 / 正文优先读本机缓存，命中则**不再访问 WebDAV**
 */
class LibraryPanel(
    context: Context,
    private val repository: CloudLibraryRepository,
    private val cacheStore: LibraryCacheStore,
) : LinearLayout(context) {

    /** `+` 上传：由宿主用既有 SAF `OpenDocument` 启动器承接 */
    var onPickUpload: (() -> Unit)? = null

    /** 本机隐藏集合（由宿主从设置读取；只读，不在此写盘） */
    var hiddenHashesProvider: () -> Set<String> = { emptySet() }

    /** 用户点「×」：由宿主持久化 hash（只存 hash） */
    var onHide: (String) -> Unit = {}

    /** 本机主动重新上传成功：清除该 hash 的本机隐藏 */
    var onUnhide: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val listColumn = LinearLayout(context)
    private val statusText = TextView(context)
    private var refreshIcon: ImageView? = null
    private var refreshAnimator: ObjectAnimator? = null

    private var refreshJob: Job? = null
    private var contentJob: Job? = null
    private var released = false

    /** 本机缓存（立即显示） */
    private var localItems: List<CloudLibraryItem> = emptyList()

    /** 最近一次云端 snapshot（异步到达） */
    private var snapshot = LibrarySnapshot()

    private val thumbnails = HashMap<String, Bitmap>()
    private val thumbnailJobs = HashMap<String, Job>()

    private val accent: Int by lazy { resolveColor(android.R.attr.colorAccent, Color.parseColor("#2F6FED")) }
    private val secondaryText: Int by lazy { resolveColor(android.R.attr.textColorSecondary, Color.GRAY) }

    /**
     * 中性按钮色（文件图标 / × 隐藏）。
     *
     * **不能**用 theme 解析出来的 `textColorSecondary` 直接当颜色：那是 ColorStateList，
     * `TypedValue.data` 对它没有意义（常为 0 = 全透明）⇒ 图标会“看不见”。
     * 这里固定用一个在浅色/深色背景都可见的中性灰。
     */
    private val neutralIcon: Int = Color.parseColor("#8A8A8A")

    init {
        orientation = VERTICAL
        addView(buildHeader(), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        statusText.apply {
            text = "正在刷新…"
            textSize = 12f
            setTextColor(secondaryText)
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        listColumn.orientation = VERTICAL
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(listColumn, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 打开：**先读本机缓存并立即显示**（不等网络），再订阅云端状态 + 后台增量刷新。
     */
    fun start() {
        scope.launch {
            localItems = withContext(Dispatchers.IO) { cacheStore.load().map { it.toItem() } }
            if (!released) render()

            scope.launch {
                repository.state.collect { state ->
                    if (released) return@collect
                    snapshot = state
                    render()
                    ensureContentCached()
                }
            }
            refresh()
        }
    }

    /** 仅 RESUMED 期间每 60s **增量**刷新；STOPPED 自动停止 */
    fun bindLifecycle(owner: LifecycleOwner) {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                while (true) {
                    delay(LibraryUiModel.AUTO_REFRESH_MS)
                    refresh()
                }
            }
        }
    }

    fun release() {
        released = true
        refreshAnimator?.cancel()
        refreshAnimator = null
        refreshIcon = null
        scope.cancel()
        thumbnailJobs.clear()
        for (bitmap in thumbnails.values) runCatching { bitmap.recycle() }
        thumbnails.clear()
        listColumn.removeAllViews()
    }

    fun upload(uri: android.net.Uri?) {
        if (uri == null) return
        val name = queryName(uri) ?: "board"
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        scope.launch {
            toast("正在上传…")
            val result = withContext(Dispatchers.IO) { repository.upload(uri.toString(), name, mime) }
            if (result is UploadResult.Success) {
                onUnhide(result.item.hash)   // 本机主动重传同 hash ⇒ 解除本机隐藏（缓存可复用）
                toast("✓ 已上传")
                refresh()
            } else {
                toast("× 上传失败")
            }
        }
    }

    /** 「×」本机隐藏：只影响本机显示，**不删本机缓存、不动云端** */
    private fun hide(row: LibraryRow) {
        onHide(row.item.hash)
        render()
    }

    // ---------------------------------------------------------------- refresh（增量）

    private fun refresh() {
        if (refreshJob?.isActive == true) return
        setRefreshing(true)
        refreshJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repository.prune()
                    repository.refresh()
                }
            } catch (throwable: Throwable) {
                // 失败保留本机列表 + 旧云端快照
            } finally {
                setRefreshing(false)
            }
        }
    }

    /**
     * 增量：**只为难新 hash** 取正文并写入本机缓存；已缓存的不再访问 WebDAV。
     * 已有 content ⇒ 只更新 metadata。
     */
    private fun ensureContentCached() {
        if (contentJob?.isActive == true) return
        if (snapshot.items.isEmpty()) return
        contentJob = scope.launch {
            val entries = withContext(Dispatchers.IO) { cacheStore.load() }.associateBy { it.hash }.toMutableMap()
            var changed = false
            for (item in snapshot.items) {
                if (released) return@launch
                if (item.hash.isEmpty()) continue
                val deviceType = snapshot.deviceTypes[item.deviceId]
                val already = withContext(Dispatchers.IO) { cacheStore.hasContent(item.hash, item.name) }
                if (!already) {
                    // 只有“本机还没有正文”时才去 WebDAV 取
                    val bytes = withContext(Dispatchers.IO) { repository.fetchBytes(item) }
                    if (bytes != null) withContext(Dispatchers.IO) { cacheStore.writeContent(item.hash, item.name, bytes) }
                }
                val contentNow = withContext(Dispatchers.IO) { cacheStore.hasContent(item.hash, item.name) }
                val previous = entries[item.hash]
                val merged = cacheStore.toEntry(item, deviceType, contentNow)
                if (previous != merged) {
                    entries[item.hash] = merged
                    changed = true
                }
            }
            if (changed) {
                val all = entries.values.toList()
                withContext(Dispatchers.IO) { cacheStore.save(all) }
                localItems = LibraryMerge.merge(all.map { it.toItem() }, emptyList())
                if (!released) render()
            }
        }
    }

    private fun setRefreshing(busy: Boolean) {
        val icon = refreshIcon
        icon?.isEnabled = !busy
        icon?.alpha = if (busy) 0.45f else 1f
        if (busy) {
            // 只有“本机确实什么都没有”时才显示加载文案，避免已有内容时闪空
            if (localItems.isEmpty() && snapshot.items.isEmpty()) {
                statusText.text = "正在刷新…"
                statusText.visibility = View.VISIBLE
            }
            if (icon != null) {
                val animator = refreshAnimator ?: ObjectAnimator
                    .ofFloat(icon, View.ROTATION, 0f, 360f)
                    .apply {
                        duration = 900L
                        repeatCount = ObjectAnimator.INFINITE
                        interpolator = LinearInterpolator()
                    }
                refreshAnimator = animator
                if (!animator.isRunning) animator.start()
            }
        } else {
            refreshAnimator?.cancel()
            refreshAnimator = null
            icon?.rotation = 0f
        }
    }

    private fun render() {
        statusText.text = when {
            snapshot.partial -> LibraryUiModel.emptyText(snapshot)
            snapshot.error != LibraryError.NONE && localItems.isEmpty() -> snapshot.error.message
            else -> ""
        }
        statusText.visibility = if (statusText.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // **本机缓存 ∪ 云端**：云端 TTL 消失不会删掉本机已收到的内容
        val merged = LibraryMerge.merge(localItems, snapshot.items)
        val rows = LibraryUiModel.rows(
            items = merged,
            myDeviceId = repository.deviceId,
            deviceTypes = snapshot.deviceTypes,
            hiddenHashes = hiddenHashesProvider(),
        )

        // 缩略图：只淘汰“本机/云端都不再存在”的 hash
        val live = LibraryUiModel.liveHashes(merged)
        for (key in LibraryUiModel.staleThumbnailKeys(thumbnails, live)) {
            thumbnails.remove(key)?.let { runCatching { it.recycle() } }
        }
        for (key in LibraryUiModel.staleThumbnailKeys(thumbnailJobs, live)) {
            thumbnailJobs.remove(key)?.cancel()
        }

        listColumn.removeAllViews()
        if (rows.isEmpty()) {
            listColumn.addView(hint(LibraryUiModel.emptyText(snapshot)))
            return
        }
        // **逐行容错**：某一行构建失败（缺正文 / 缺缩略图 / 任何异常）绝不能连带吃掉后面的行，
        // 也绝不能让 metadata-only 条目消失 —— 失败时回退到最简行。
        for (row in rows) {
            val view = runCatching { buildRow(row) }.getOrElse { buildFallbackRow(row) }
            listColumn.addView(view)
        }
    }

    /**
     * 兜底行：**只要本地有 metadata 就一定能显示**（图片/文件通用图标 + 名称 + 大小 + 来源 + [下载] [×]）。
     *
     * 用于 `contentCached=false`（正文不在本机 / 云端已 404）或任何构建异常的情况。
     */
    private fun buildFallbackRow(row: LibraryRow): View {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        container.addView(
            ImageView(context).apply {
                layoutParams = LayoutParams(dp(44), dp(44))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                ResourcesCompat.getDrawable(resources, R.drawable.ic_board_clipboard, null)
                    ?.mutate()?.apply { setTint(neutralIcon) }
                    ?.let { setImageDrawable(it) }
            },
        )
        container.addView(space(dp(12)))
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(
            TextView(context).apply {
                text = row.name
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
        )
        texts.addView(TextView(context).apply { text = row.sizeLabel; textSize = 11f; setTextColor(secondaryText) })
        texts.addView(
            TextView(context).apply {
                text = row.sourceLabel
                textSize = 11f
                setTextColor(if (row.isLocal) Color.parseColor("#8A8A8A") else accent)
            },
        )
        container.addView(texts)
        container.addView(
            ImageView(context).apply {
                layoutParams = LayoutParams(dp(26), dp(26))
                contentDescription = "下载"
                ResourcesCompat.getDrawable(resources, R.drawable.ic_board_download, null)
                    ?.mutate()?.apply { setTint(accent) }
                    ?.let { setImageDrawable(it) }
                setOnClickListener { download(row) }
            },
        )
        container.addView(space(dp(6)))
        container.addView(
            ImageView(context).apply {
                layoutParams = LayoutParams(dp(30), dp(30)).apply { marginStart = dp(2) }
                contentDescription = "隐藏"
                background = circleBackground(Color.parseColor("#1F000000"))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                ResourcesCompat.getDrawable(resources, R.drawable.ic_board_close, null)
                    ?.mutate()?.apply { setTint(Color.parseColor("#444444")) }
                    ?.let { setImageDrawable(it) }
                setOnClickListener { hide(row) }
            },
        )
        return container
    }

    // ---------------------------------------------------------------- 卡片

    private fun buildRow(row: LibraryRow): View {
        val container = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        val thumb = ImageView(context).apply {
            layoutParams = LayoutParams(dp(44), dp(44))
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        if (row.isImage) {
            thumb.setBackgroundColor(Color.parseColor("#14000000"))
            loadThumbnail(row, thumb)
            if (row.canPreview) thumb.setOnClickListener { showPreview(row) }
        } else {
            ResourcesCompat.getDrawable(resources, R.drawable.ic_board_clipboard, null)
                ?.mutate()?.apply { setTint(neutralIcon) }
                ?.let { thumb.setImageDrawable(it) }
            thumb.scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
        container.addView(thumb)
        container.addView(space(dp(12)))

        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(
            TextView(context).apply {
                text = row.name
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            },
        )
        texts.addView(
            TextView(context).apply {
                text = row.sizeLabel
                textSize = 11f
                setTextColor(secondaryText)
            },
        )
        texts.addView(
            TextView(context).apply {
                text = row.sourceLabel
                textSize = 11f
                setTextColor(if (row.isLocal) Color.parseColor("#8A8A8A") else accent)
            },
        )
        container.addView(texts)

        // [下载] 保持原行为
        container.addView(
            ImageView(context).apply {
                layoutParams = LayoutParams(dp(26), dp(26))
                contentDescription = "下载"
                ResourcesCompat.getDrawable(resources, R.drawable.ic_board_download, null)
                    ?.mutate()?.apply { setTint(accent) }
                    ?.let { setImageDrawable(it) }
                setOnClickListener { download(row) }
            },
        )
        container.addView(space(dp(6)))

        // [×] 明确的关闭/隐藏图标：加淡灰圆形底 + 深色描边，保证一眼可见
        container.addView(
            ImageView(context).apply {
                layoutParams = LayoutParams(dp(30), dp(30)).apply { marginStart = dp(2) }
                contentDescription = "隐藏"
                background = circleBackground(Color.parseColor("#1F000000"))
                setPadding(dp(6), dp(6), dp(6), dp(6))
                ResourcesCompat.getDrawable(resources, R.drawable.ic_board_close, null)
                    ?.mutate()?.apply { setTint(Color.parseColor("#444444")) }
                    ?.let { setImageDrawable(it) }
                setOnClickListener { hide(row) }
            },
        )

        return container
    }

    private fun download(row: LibraryRow) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { repository.download(row.item) }
            toast(outcome.message)
        }
    }

    // ---------------------------------------------------------------- 缩略图 / 正文（缓存优先）

    /** 缓存优先：本机已有正文 ⇒ 直接解码，**不再访问 WebDAV** */
    private suspend fun bytesFor(item: CloudLibraryItem): ByteArray? {
        val cached = withContext(Dispatchers.IO) { cacheStore.readContent(item.hash, item.name) }
        if (cached != null) return cached
        return withContext(Dispatchers.IO) { repository.fetchBytes(item) }
    }

    private fun loadThumbnail(row: LibraryRow, target: ImageView) {
        val hash = row.item.hash
        thumbnails[hash]?.let {
            if (!it.isRecycled) {
                target.setImageBitmap(it)
                return
            }
        }
        if (thumbnailJobs[hash]?.isActive == true) return
        thumbnailJobs[hash] = scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                val bytes = bytesFor(row.item) ?: return@withContext null
                decodeDownsampled(bytes, dp(96))
            }
            thumbnailJobs.remove(hash)
            if (released) {
                bitmap?.recycle()
                return@launch
            }
            if (bitmap != null) {
                thumbnails[hash] = bitmap
                target.setImageBitmap(bitmap)
            } else {
                // 正文不可得（本机没缓存 + 云端已清理/断网）：显示**通用占位图标**，
                // 而不是一个空白方块；该行本身仍然保留（metadata-only 必须可见）。
                target.setImageDrawable(null)
                target.scaleType = ImageView.ScaleType.CENTER_INSIDE
                ResourcesCompat.getDrawable(resources, R.drawable.ic_board_clipboard, null)
                    ?.mutate()?.apply { setTint(neutralIcon) }
                    ?.let { target.setImageDrawable(it) }
            }
        }
    }

    private fun decodeDownsampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    // ---------------------------------------------------------------- 预览（图片专属）

    private fun showPreview(row: LibraryRow) {
        if (!row.canPreview) return
        scope.launch {
            val bitmap = thumbnails[row.item.hash] ?: withContext(Dispatchers.IO) {
                bytesFor(row.item)?.let { decodeDownsampled(it, dp(1080)) }
            }
            if (bitmap == null) {
                // 正文不可得（本机没有缓存、且云端已不可用）：给出**明确**反馈，而不是笼统的“预览失败”
                toast("× 内容已不可用")
                return@launch
            }
            val dialog = android.app.Dialog(context)
            val root = FrameLayout(context)
            root.addView(
                ImageView(context).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                },
            )
            root.addView(
                ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(30), dp(30)).apply {
                        gravity = Gravity.BOTTOM or Gravity.END
                        setMargins(0, 0, dp(16), dp(16))
                    }
                    contentDescription = "下载"
                    ResourcesCompat.getDrawable(resources, R.drawable.ic_board_download, null)
                        ?.mutate()?.apply { setTint(accent) }
                        ?.let { setImageDrawable(it) }
                    setOnClickListener {
                        download(row)
                        dialog.dismiss()
                    }
                },
            )
            dialog.setContentView(root)
            dialog.show()
        }
    }

    // ---------------------------------------------------------------- 顶部操作区

    private fun buildHeader(): View {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(2))
        }
        val icon = ImageView(context).apply {
            layoutParams = LayoutParams(dp(26), dp(26))
            contentDescription = "刷新"
            ResourcesCompat.getDrawable(resources, R.drawable.ic_board_refresh, null)
                ?.mutate()?.apply { setTint(accent) }
                ?.let { setImageDrawable(it) }
            setOnClickListener { refresh() }
        }
        refreshIcon = icon
        header.addView(icon)
        header.addView(View(context).apply { layoutParams = LayoutParams(0, 1, 1f) })
        header.addView(
            TextView(context).apply {
                text = "+"
                textSize = 20f
                setTextColor(accent)
                setPadding(dp(12), 0, dp(4), dp(4))
                setOnClickListener { onPickUpload?.invoke() }
            },
        )
        return header
    }

    // ---------------------------------------------------------------- 小工具

    private fun toast(text: String) {
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    private fun hint(text: String) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(secondaryText)
        setPadding(dp(16), dp(24), dp(16), dp(24))
    }

    private fun space(width: Int) = View(context).apply { layoutParams = LayoutParams(width, 1) }

    private fun circleBackground(fill: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(fill)
        }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        // ColorStateList（如 textColorSecondary）必须以 resourceId 取真实颜色；
        // 直接读 value.data 会拿到 0（透明）—— 这正是「图标看不见」的根因。
        if (value.resourceId != 0) {
            return runCatching { androidx.core.content.ContextCompat.getColor(context, value.resourceId) }
                .getOrDefault(fallback)
        }
        return if (value.data != 0) value.data else fallback
    }

    private fun queryName(uri: android.net.Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else {
                    null
                }
            }
    }.getOrNull()
}
