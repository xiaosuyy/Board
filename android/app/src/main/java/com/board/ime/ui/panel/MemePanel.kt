package com.board.ime.ui.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import com.board.ime.emoji.MemeRepository
import com.board.ime.ui.KeyboardTheme
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 斗图内容区（占键盘位置，总高度与其它状态一致）。
 *
 * 4 列大图网格，可滚动；图片从 `assets/meme/` 按需解码并缓存，
 * 一屏只画可见行，滚动不重建 View。点一张由宿主负责「分享到微信 / QQ」。
 */
class MemePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 点选一张斗图（宿主负责分享到微信 / QQ） */
    var onPick: ((MemeRepository.Meme) -> Unit)? = null

    private val scroll = ScrollView(context)
    private val grid = MemeGridView(context)
    private val emptyView = TextView(context)

    private var items: List<MemeRepository.Meme> = emptyList()

    init {
        scroll.isFillViewport = true
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        scroll.addView(
            grid,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        emptyView.apply {
            gravity = android.view.Gravity.CENTER
            setPadding(dp(12), dp(24), dp(12), dp(24))
            visibility = GONE
        }
        addView(emptyView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        grid.onPick = { meme -> onPick?.invoke(meme) }
        applyTheme(KeyboardTheme.from(context, com.board.ime.settings.ThemeMode.SYSTEM))
    }

    fun applyTheme(theme: KeyboardTheme) {
        setBackgroundColor(theme.keyboardBackground)
        scroll.setBackgroundColor(theme.keyboardBackground)
        emptyView.setTextColor(theme.candidateMutedText)
        emptyView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        grid.applyTheme(theme)
    }

    fun render(items: List<MemeRepository.Meme>, emptyText: String = "暂无斗图表情") {
        val changed = this.items != items
        this.items = items
        grid.setItems(items)
        emptyView.text = emptyText
        emptyView.visibility = if (items.isEmpty()) VISIBLE else GONE
        if (changed) scroll.scrollTo(0, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * 4 列斗图网格。只绘制当前可见行；Bitmap 按需解码 + LRU 缓存。
 */
private class MemeGridView(context: Context) : View(context) {

    var onPick: ((MemeRepository.Meme) -> Unit)? = null

    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }

    private var theme: KeyboardTheme = KeyboardTheme.from(
        context,
        com.board.ime.settings.ThemeMode.SYSTEM,
    )
    private var items: List<MemeRepository.Meme> = emptyList()
    private var pressedIndex = -1

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    private val srcRect = Rect()
    private val dstRect = RectF()

    private val bitmapCache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    /** 正在下载的远程图 URL（避免同一张图重复发请求） */
    private val pendingRemote = HashSet<String>()

    /** 下载 / 解码失败的远程图 URL（失败后显示占位，不反复重试） */
    private val failedRemote = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 占位块：网络图还没下载好 / 下载失败时画灰底 + 标题，避免整屏空白 */
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val placeholderTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
    }

    fun applyTheme(theme: KeyboardTheme) {
        this.theme = theme
        pressedPaint.color = theme.panelSurfacePressed
        invalidate()
    }

    fun setItems(list: List<MemeRepository.Meme>) {
        if (items == list) return
        items = list
        pressedIndex = -1
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cell = width / COLUMNS
        val rows = (items.size + COLUMNS - 1) / COLUMNS
        setMeasuredDimension(width, resolveSize(rows * cell, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty() || width <= 0) return

        val cell = width / COLUMNS
        val clip = canvas.clipBounds
        val rows = (items.size + COLUMNS - 1) / COLUMNS
        val firstRow = (clip.top / cell).coerceAtLeast(0)
        val lastRow = ((clip.bottom + cell - 1) / cell).coerceAtMost(rows)
        val gap = theme.spaceS.toFloat()

        for (row in firstRow until lastRow) {
            val top = (row * cell).toFloat()
            for (column in 0 until COLUMNS) {
                val index = row * COLUMNS + column
                if (index >= items.size) break
                val left = (column * cell).toFloat()
                if (index == pressedIndex) {
                    canvas.drawRoundRect(
                        left + gap, top + gap, left + cell - gap, top + cell - gap,
                        theme.radiusMedium, theme.radiusMedium, pressedPaint,
                    )
                }
                val bitmap = bitmapFor(items[index])
                if (bitmap == null) {
                    drawPlaceholder(canvas, items[index], left, top, cell, gap)
                    continue
                }
                srcRect.set(0, 0, bitmap.width, bitmap.height)
                dstRect.set(
                    left + gap, top + gap, left + cell - gap, top + cell - gap,
                )
                canvas.drawBitmap(bitmap, srcRect, dstRect, bitmapPaint)
            }
        }
    }

    private fun bitmapFor(meme: MemeRepository.Meme): Bitmap? {
        val remote = meme.remoteUrl
        return if (remote == null) decodeAsset(meme.asset) else decodeRemote(remote)
    }

    /** 本地内置图：从 assets 按需解码 + 内存缓存 */
    private fun decodeAsset(asset: String): Bitmap? {
        val key = "asset:$asset"
        bitmapCache.get(key)?.let { return it }
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        val bitmap = runCatching {
            context.assets.open("meme/$asset").use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        bitmapCache.put(key, bitmap)
        return bitmap
    }

    /** 网络梗图：内存缓存命中直接返回；未命中异步下载，下载完重绘；失败显示占位 */
    private fun decodeRemote(url: String): Bitmap? {
        bitmapCache.get(url)?.let { return it }
        if (url in failedRemote) return null
        if (pendingRemote.add(url)) {
            REMOTE_EXECUTOR.execute {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", MEMECUB_UA)
                        .get()
                        .build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            failedRemote += url
                            return@execute
                        }
                        val bytes = response.body?.bytes()
                        if (bytes == null) {
                            failedRemote += url
                            return@execute
                        }
                        // thumb 图按 2 倍降采样解码，4 列网格下不浪费内存
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        if (bitmap != null) bitmapCache.put(url, bitmap) else failedRemote += url
                        postInvalidate()
                    }
                } catch (_: Throwable) {
                    failedRemote += url
                    postInvalidate()
                } finally {
                    pendingRemote.remove(url)
                }
            }
        }
        return null
    }

    /** 网络图未就绪 / 失败时画占位块，保证面板始终有内容、不白屏 */
    private fun drawPlaceholder(
        canvas: Canvas,
        meme: MemeRepository.Meme,
        left: Float,
        top: Float,
        cell: Int,
        gap: Float,
    ) {
        placeholderPaint.color = theme.panelSurface
        canvas.drawRoundRect(
            left + gap, top + gap, left + cell - gap, top + cell - gap,
            theme.radiusMedium, theme.radiusMedium, placeholderPaint,
        )
        val label = meme.label.ifBlank { "梗图" }
        placeholderTextPaint.color = theme.candidateMutedText
        placeholderTextPaint.textSize = (cell / 7f).coerceAtLeast(9f)
        val shown = if (label.length > 6) label.take(6) + "…" else label
        val baseline = top + cell / 2f - (placeholderTextPaint.ascent() + placeholderTextPaint.descent()) / 2f
        canvas.drawText(shown, left + cell / 2f, baseline, placeholderTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                pressedIndex = indexAt(event.x, event.y)
                invalidate()
                return pressedIndex >= 0
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > touchSlop * touchSlop && pressedIndex >= 0) {
                    pressedIndex = -1
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val index = indexAt(event.x, event.y)
                val wasPressed = pressedIndex
                pressedIndex = -1
                invalidate()
                if (index >= 0 && index == wasPressed) {
                    performClick()
                    onPick?.invoke(items[index])
                }
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> {
                pressedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun indexAt(x: Float, y: Float): Int {
        if (x < 0 || y < 0 || x >= width || y >= height) return -1
        val cell = width / COLUMNS
        val column = (x / cell).toInt()
        val row = (y / cell).toInt()
        val index = row * COLUMNS + column
        return if (index in items.indices) index else -1
    }

    private fun cacheSizeBytes(): Int {
        val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxMemory / 8).coerceIn(2 * 1024, 16 * 1024) * 1024
    }

    private companion object {
        const val COLUMNS = 4
        const val MEMECUB_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /** 远程梗图下载线程池（daemon，跟随进程结束） */
        private val REMOTE_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(3) { runnable ->
            Thread(runnable, "meme-loader").apply { isDaemon = true }
        }
    }
}
