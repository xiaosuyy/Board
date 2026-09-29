package com.board.ime.sync.webdav

import com.board.ime.clipboard.SyncClipboardPaths
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * WebDAV 客户端接口。
 *
 * 上层（[com.board.ime.sync.SyncEngine]）只依赖这个接口，不直接碰 HTTP，
 * 因此以后换实现（OkHttp → 其它 / 加代理）不需要动同步逻辑。
 */
interface WebDavClient {

    /** 测连接：确保同步目录存在 + 试着读取一次清单 */
    suspend fun testConnection(): WebDavResponse<Unit>

    /** GET 文本；文件不存在返回 [WebDavError.NOT_FOUND] */
    suspend fun getText(path: String): WebDavResponse<String>

    /** GET 二进制（图片 / 文件，用 dataName 指定） */
    suspend fun getBytes(path: String): WebDavResponse<ByteArray>

    /** PUT 文本 */
    suspend fun putText(path: String, body: String, contentType: String): WebDavResponse<Unit>

    /** PUT 二进制 */
    suspend fun putBytes(path: String, bytes: ByteArray, contentType: String): WebDavResponse<Unit>

    /** 递归创建目录（MKCOL）；已存在视为成功 */
    suspend fun ensureDirectory(path: String): WebDavResponse<Unit>
}

/**
 * OkHttp 实现。
 *
 * 用 OkHttp 而不是 `HttpURLConnection`，是因为 WebDAV 需要 `MKCOL` 这类
 * 自定义方法 —— `HttpURLConnection` 会直接抛 `ProtocolException`。
 */
class OkHttpWebDavClient(
    private val config: WebDavConfig,
    private val client: OkHttpClient = defaultClient(),
) : WebDavClient {

    override suspend fun testConnection(): WebDavResponse<Unit> {
        if (!config.isConfigured) {
            return WebDavResponse.fail(WebDavError.NOT_CONFIGURED, message = "未填写 WebDAV 地址")
        }
        val dir = ensureDirectory(config.normalizedDir)
        if (!dir.isOk && dir.error != WebDavError.CONFLICT) return dir

        // 目录可达即可；清单不存在（404）也说明连接是通的
        val probe = getText(SyncClipboardPaths.MANIFEST)
        return when (probe.error) {
            WebDavError.NONE, WebDavError.NOT_FOUND -> WebDavResponse.ok(Unit, probe.httpCode)
            else -> WebDavResponse.fail(probe.error, probe.httpCode, probe.message)
        }
    }

    override suspend fun getText(path: String): WebDavResponse<String> {
        val response = execute(authed(urlFor(path)).get().build())
        return when {
            response.error == WebDavError.NONE -> WebDavResponse.ok(
                response.value.orEmpty(),
                response.httpCode,
                response.lastModified,
            )

            else -> WebDavResponse.fail(response.error, response.httpCode, response.message)
        }
    }

    override suspend fun getBytes(path: String): WebDavResponse<ByteArray> {
        val response = execute(authed(urlFor(path)).get().build())
        return when {
            response.error == WebDavError.NONE ->
                WebDavResponse.ok(response.value.orEmpty().toByteArray(Charsets.UTF_8), response.httpCode)

            else -> WebDavResponse.fail(response.error, response.httpCode, response.message)
        }
    }

    override suspend fun putText(
        path: String,
        body: String,
        contentType: String,
    ): WebDavResponse<Unit> = executeUnit(
        authed(urlFor(path)).put(body.toRequestBody(contentType.toMediaType())).build(),
    )

    override suspend fun putBytes(
        path: String,
        bytes: ByteArray,
        contentType: String,
    ): WebDavResponse<Unit> = executeUnit(
        authed(urlFor(path)).put(bytes.toRequestBody(contentType.toMediaType())).build(),
    )

    override suspend fun ensureDirectory(path: String): WebDavResponse<Unit> {
        val dir = path.trim('/')
        if (dir.isEmpty()) return WebDavResponse.ok(Unit)

        // 逐级 MKCOL：/a → /a/b → /a/b/c
        // 注意：这里用的是**相对 baseUrl** 的路径（调用方传的就是同步目录本身），
        // 不能再拼一次 syncDir，否则会建出 board/board 这种多余层级。
        var accumulated = ""
        for (segment in dir.split('/').filter { it.isNotBlank() }) {
            accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
            val response = executeUnit(
                // 必须带一个空的 body：部分服务器（例如 .NET HttpListener）对没有
                // Content-Length 的 MKCOL 直接回 400 Bad Request
                authed(baseUrlFor(accumulated))
                    .method("MKCOL", ByteArray(0).toRequestBody(null))
                    .build(),
            )
            if (response.isOk) continue
            // 已存在（405 / 409 / 301）继续往下走，其它错误直接返回
            if (response.httpCode == 405 || response.httpCode == 409 || response.httpCode == 301) continue
            return response
        }
        return WebDavResponse.ok(Unit)
    }

    // ---------------------------------------------------------------- 内部

    private fun executeUnit(request: Request): WebDavResponse<Unit> {
        val response = execute(request)
        return if (response.isOk) {
            WebDavResponse.ok(Unit, response.httpCode)
        } else {
            WebDavResponse.fail(response.error, response.httpCode, response.message)
        }
    }

    private fun execute(request: Request): WebDavResponse<String> = try {
        client.newCall(request).execute().use { response ->
            classify(response)
        }
    } catch (timeout: SocketTimeoutException) {
        WebDavResponse.fail(WebDavError.TIMEOUT, message = "超时")
    } catch (io: IOException) {
        WebDavResponse.fail(WebDavError.NETWORK, message = io.message ?: "网络不可用")
    } catch (throwable: Throwable) {
        WebDavResponse.fail(WebDavError.UNEXPECTED, message = throwable.message)
    }

    private fun classify(response: Response): WebDavResponse<String> {
        val lastModified = response.header("Last-Modified")?.let(::parseHttpDate)
        return when (response.code) {
            in 200..299 -> WebDavResponse.ok(
                response.body?.string().orEmpty(),
                response.code,
                lastModified,
            )

            404 -> WebDavResponse.fail(WebDavError.NOT_FOUND, 404, "服务器上没有这个文件")

            401, 403 -> WebDavResponse.fail(WebDavError.AUTH, response.code, "认证失败")

            405 -> WebDavResponse.fail(WebDavError.CONFLICT, 405, "方法不允许")

            409 -> WebDavResponse.fail(WebDavError.CONFLICT, 409, "目录不存在或冲突")

            in 500..599 -> WebDavResponse.fail(WebDavError.SERVER, response.code, "服务器错误 ${response.code}")

            else -> WebDavResponse.fail(WebDavError.UNEXPECTED, response.code, "HTTP ${response.code}")
        }
    }

    /** baseUrl + syncDir + path；每一段单独 URL 编码（中文文件名也能用） */
    private fun urlFor(path: String): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val dir = config.normalizedDir
        val segments = ArrayList<String>()
        if (dir.isNotEmpty()) segments += dir.split('/')
        segments += path.trim('/').split('/')
        val encoded = segments.filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
        return if (encoded.isEmpty()) base else "$base/$encoded"
    }

    /** 相对 baseUrl 的路径（不含 syncDir），目录创建用 */
    private fun baseUrlFor(path: String): String {
        val base = config.baseUrl.trim().trimEnd('/')
        val encoded = path.trim('/').split('/')
            .filter { it.isNotBlank() }
            .joinToString("/") { encodeSegment(it) }
        return if (encoded.isEmpty()) base else "$base/$encoded"
    }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    /** 只加认证头，不替换 OkHttp 自带的 url() */
    private fun authed(url: String): Request.Builder = Request.Builder().url(url).apply {
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            header("Authorization", Credentials.basic(config.username, config.password))
        }
    }

    private fun parseHttpDate(value: String): Long? = try {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value)?.time
    } catch (throwable: Throwable) {
        null
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
