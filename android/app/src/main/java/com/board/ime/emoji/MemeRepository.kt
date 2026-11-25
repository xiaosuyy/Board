package com.board.ime.emoji

import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 斗图表情数据源。
 *
 * - 本地：`assets/meme/` 内置热门斗图（离线兜底），只解析 `index.txt` 得到「文件名 + 文案」。
 * - 网络：[memecub.com/zh/memes] 热门梗图，抓取首页 HTML 解析出「缩略图 URL + 标题」，
 *   按需下载显示、点击分享到微信 / QQ。
 *
 * 网络失败或未联网时回退到本地内置斗图，保证斗图面板永远可用。
 */
class MemeRepository(context: Context) {

    data class Meme(
        val asset: String,
        val label: String,
        /** 非空 = 网络梗图（远程缩略图 URL）；空 = 本地内置图 */
        val remoteUrl: String? = null,
    )

    private val appContext = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var loaded = false

    private var memes: List<Meme> = emptyList()

    fun all(): List<Meme> {
        load()
        return memes
    }

    /** 按文案关键词过滤（中文包含匹配；用于将来扩展斗图搜索） */
    fun search(query: String): List<Meme> {
        val raw = query.trim()
        if (raw.isEmpty()) return all()
        return all().filter { it.label.contains(raw) }
    }

    @Synchronized
    private fun load() {
        if (loaded) return
        val out = ArrayList<Meme>(128)
        runCatching {
            appContext.assets.open(ASSET_INDEX).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank() || line.startsWith('#')) return@forEach
                    val parts = line.split('\t')
                    if (parts.size < 2) return@forEach
                    val asset = parts[0].trim()
                    if (asset.isEmpty()) return@forEach
                    out += Meme(asset, parts[1].trim())
                }
            }
        }
        memes = out
        loaded = true
    }

    /**
     * 从 memecub.com 抓取热门梗图列表（缩略图 URL + 标题）。
     * 解析失败 / 无网络时返回空列表，调用方回退到本地内置斗图。
     */
    suspend fun fetchMemecub(): List<Meme> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(MEMECUB_URL)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList<Meme>()
                val html = response.body?.string().orEmpty()
                // 只取首页前 N 张热门梗图，避免 200+ 张图同时异步下载造成面板长时间空白 / 内存压力
                parseMemecub(html).take(MEMECUB_LIMIT)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 下载一张远程梗图（同步、调用方需在 IO 线程执行）。
     * 失败返回 null，分享时由调用方降级提示。
     */
    fun downloadRemote(url: String): ByteArray? = runCatching {
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.bytes()
        }
    }.getOrNull()

    private fun parseMemecub(html: String): List<Meme> {
        val out = ArrayList<Meme>(256)
        val matcher = IMG_PATTERN.matcher(html)
        while (matcher.find()) {
            val id = matcher.group(1) ?: continue
            val title = matcher.group(2)?.trim().orEmpty()
            out += Meme(
                asset = "$id.webp",
                label = title.ifEmpty { "梗图" },
                remoteUrl = "https://gpic.code-nav.cn/memecub/public/thumb/$id.webp",
            )
        }
        return out.distinctBy { it.remoteUrl }
    }

    private companion object {
        const val ASSET_INDEX = "meme/index.txt"
        const val MEMECUB_LIMIT = 60
        const val MEMECUB_URL = "https://memecub.com/zh/memes"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

        /** 匹配 `thumb/{id}.webp" alt="{标题}"`，直接拿到图片 id 与标题 */
        private val IMG_PATTERN =
            Pattern.compile("""thumb/([a-z0-9]+)\.webp"\s+alt="([^"]*)"""")
    }
}
