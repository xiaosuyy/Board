package com.board.ime.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.board.ime.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

/**
 * 更新包下载通道。
 *
 * 检查更新始终直连 GitHub API；gh-proxy 只改写 GitHub Release 资产的下载地址。
 */
enum class UpdateDownloadSource(
    val label: String,
    val storageValue: String,
    private val proxyPrefix: String?,
) {
    GITHUB("GitHub", "github", null),
    GH_PROXY("gh-proxy", "ghproxy", "https://gh-proxy.com/");

    fun routedDownloadUrl(url: String): String {
        val prefix = proxyPrefix ?: return url
        val parsed = runCatching { URL(url) }.getOrNull() ?: return url
        if (parsed.protocol != "https" || parsed.host !in GITHUB_DOWNLOAD_HOSTS) return url
        return prefix + url
    }

    companion object {
        private val GITHUB_DOWNLOAD_HOSTS = setOf("github.com", "objects.githubusercontent.com")

        fun fromStorage(value: String?): UpdateDownloadSource =
            when (value?.trim()?.lowercase(Locale.ROOT)) {
                GH_PROXY.storageValue, "gh-proxy", "gh_proxy" -> GH_PROXY
                else -> GITHUB
            }
    }
}

data class UpdateInfo(
    val versionLabel: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val notes: String,
)

/** 从 GitHub Releases 检查更新（公开仓库，无需登录）。 */
object UpdateChecker {

    const val REPOSITORY = "xiaosuyy/Board"

    private const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val TAG = "UpdateChecker"

    fun check(): Result<UpdateInfo?> = runCatching {
        parseLatest(request(API_URL))
    }

    fun openDownload(context: Context, update: UpdateInfo, source: UpdateDownloadSource): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(source.routedDownloadUrl(update.downloadUrl)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun request(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Board-IME")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                Log.w(TAG, "HTTP $code $url · ${detail ?: "<empty>"}")
                throw IOException(
                    when {
                        code == 404 -> "暂无发布版本"
                        detail == null -> "HTTP $code"
                        else -> "HTTP $code · ${detail.replace(Regex("\\s+"), " ").take(120)}"
                    },
                )
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseLatest(json: String): UpdateInfo? {
        val release = JSONObject(json)
        val assets = release.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length())
            .mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            ?: return null
        val latest = extractVersion(release.optString("tag_name"))
            ?: extractVersion(apk.optString("name"))
            ?: return null
        val current = extractVersion(BuildConfig.VERSION_NAME) ?: return null
        if (compareVersion(latest, current) <= 0) return null
        return UpdateInfo(
            versionLabel = "v" + latest.joinToString("."),
            downloadUrl = apk.optString("browser_download_url"),
            sizeBytes = apk.optLong("size"),
            notes = release.optString("body").trim(),
        )
    }

    private fun extractVersion(value: String): List<Int>? {
        val match = Regex("""\d+(?:\.\d+)+""").find(value) ?: return null
        return match.value.split('.').mapNotNull { it.toIntOrNull() }
    }

    private fun compareVersion(left: List<Int>, right: List<Int>): Int {
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = left.getOrElse(index) { 0 }
            val b = right.getOrElse(index) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }
}
