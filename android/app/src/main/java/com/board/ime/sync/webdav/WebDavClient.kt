package com.board.ime.sync.webdav

import com.board.ime.clipboard.SyncClipboardPaths
import java.io.File
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

    /**
     * 流式下载到文件（大型词库 20~30MB）。
     *
     * 用流而不是 `getBytes()`，避免把整份文件读进内存；下载中途失败时
     * 目标文件是**不完整**的，调用方必须写临时文件、校验后再原子替换。
     *
     * @param onProgress (已下载字节, 总字节；总字节未知时为 -1)
     */
    suspend fun downloadToFile(
        path: String,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): WebDavResponse<Unit>

    /** 流式上传文件；语义与 [downloadToFile] 对称 */
    suspend fun uploadFile(
        path: String,
        file: File,
        contentType: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): WebDavResponse<Unit>

    /** PUT 文本 */
    suspend fun putText(path: String, body: String, contentType: String): WebDavResponse<Unit>

    /** PUT 二进制 */
    suspend fun putBytes(path: String, bytes: ByteArray, contentType: String): WebDavResponse<Unit>

    /**
     * PROPFIND 列目录（Depth: 1），返回**子项名**列表。
     * 用于「清理云端孤儿目录」：先列出现有目录，再和有效数据源对比。
     */
    suspend fun list(path: String): WebDavResponse<List<String>>
    /** DELETE 一个文件 / 集合（合并重复词库时清理云端多余目录） */
    suspend fun delete(path: String): WebDavResponse<Unit>
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
    /**
     * 大文件专用客户端：`callTimeout` 必须比小请求长得多，
     * 否则一个 20~30MB 的词库上传会被 20s 的调用超时直接掐断。
     */
    private val fileClient: OkHttpClient = defaultFileClient(),
) : WebDavClient {

    override suspend fun testConnection(): WebDavResponse<Unit> {
        if (!config.isConfigured) {
            return WebDavResponse.fail(WebDavError.NOT_CONFIGURED, message = "未填写 WebDAV 地址")
        }
        invalidUrl<Unit>()?.let { return it }
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
        invalidUrl<String>()?.let { return it }
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
        invalidUrl<ByteArray>()?.let { return it }
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
    ): WebDavResponse<Unit> {
        invalidUrl<Unit>()?.let { return it }
        return executeUnit(
            authed(urlFor(path)).put(body.toRequestBody(contentType.toMediaType())).build(),
        )
    }

    override suspend fun downloadToFile(
        path: String,
        destination: File,
        onProgress: (Long, Long) -> Unit,
    ): WebDavResponse<Unit> {
        invalidUrl<Unit>()?.let { return it }
        return try {
        val request = authed(urlFor(path)).get().build()
        val start = System.currentTimeMillis()
        fileClient.newCall(request).execute().use { response ->
            logHttp(request, response, start)
            if (response.code !in 200..299) {
                fromClassified(classify(response))
            } else {
                val body = response.body
                val total = body?.contentLength() ?: -1L
                destination.parentFile?.mkdirs()
                body?.byteStream()?.use { input ->
                    java.io.FileOutputStream(destination).use { output ->
                        val buffer = ByteArray(STREAM_BUFFER_BYTES)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                        output.flush()
                    }
                } ?: destination.writeBytes(ByteArray(0))
                WebDavResponse.ok(Unit, response.code)
            }
        }
        } catch (timeout: SocketTimeoutException) {
            WebDavResponse.fail(WebDavError.TIMEOUT, message = "超时")
        } catch (io: IOException) {
            WebDavResponse.fail(WebDavError.NETWORK, message = io.message ?: "网络不可用")
        } catch (throwable: Throwable) {
            WebDavResponse.fail(WebDavError.UNEXPECTED, message = throwable.message)
        }
    }

    override suspend fun uploadFile(
        path: String,
        file: File,
        contentType: String,
        onProgress: (Long, Long) -> Unit,
    ): WebDavResponse<Unit> {
        invalidUrl<Unit>()?.let { return it }
        return try {
        val request = authed(urlFor(path))
            .put(ProgressRequestBody(file, contentType.toMediaType(), onProgress))
            .build()
        val start = System.currentTimeMillis()
        fileClient.newCall(request).execute().use { response ->
            logHttp(request, response, start)
            if (response.code in 200..299) {
                WebDavResponse.ok(Unit, response.code)
            } else {
                fromClassified(classify(response))
            }
        }
    } catch (timeout: SocketTimeoutException) {
        WebDavResponse.fail(WebDavError.TIMEOUT, message = "超时")
    } catch (io: IOException) {
        WebDavResponse.fail(WebDavError.NETWORK, message = io.message ?: "网络不可用")
    } catch (throwable: Throwable) {
        WebDavResponse.fail(WebDavError.UNEXPECTED, message = throwable.message)
    }
    }

    override suspend fun putBytes(
        path: String,
        bytes: ByteArray,
        contentType: String,
    ): WebDavResponse<Unit> {
        invalidUrl<Unit>()?.let { return it }
        return executeUnit(
            authed(urlFor(path)).put(bytes.toRequestBody(contentType.toMediaType())).build(),
        )
    }

    override suspend fun list(path: String): WebDavResponse<List<String>> {
        invalidUrl<Unit>()?.let { return WebDavResponse.fail(it.error, it.httpCode, it.message) }
        val request = authed(urlFor(path))
            .method("PROPFIND", ByteArray(0).toRequestBody(null))
            .header("Depth", "1")
            .build()
        val response = execute(request)
        if (!response.isOk) return WebDavResponse.fail(response.error, response.httpCode, response.message)
        return WebDavResponse.ok(parseChildNames(response.value.orEmpty(), path), response.httpCode)
    }

    /** 从 multistatus XML 里取出子目录名（排除自身、文件项、以及 .  开头的隐藏项） */
    private fun parseChildNames(xml: String, requestedPath: String): List<String> {
        val self = requestedPath.trim('/').substringAfterLast('/')
        val names = LinkedHashSet<String>()
        HREF.findAll(xml).forEach { match ->
            val raw = match.groupValues[1].trim()
            val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            val name = decoded.trimEnd('/').substringAfterLast('/')
            if (name.isNotBlank() && name != self && !name.contains('.')) names += name
        }
        return names.toList()
    }
    override suspend fun delete(path: String): WebDavResponse<Unit> {
        invalidUrl<Unit>()?.let { return it }
        return executeUnit(authed(urlFor(path)).delete().build())
    }
    override suspend fun ensureDirectory(path: String): WebDavResponse<Unit> {
        invalidUrl<Unit>()?.let { return it }
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

    /**
     * 地址层面的统一拦截原因（scheme 拼错 / HTTP 未开启 / 公网 HTTP…）。
     *
     * **只在构造时算一次**：其中的 HTTP 判定需要做地址解析，不能每个请求都做一遍。
     */
    private val urlProblem: String? = config.securityProblem()

    private fun <T> invalidUrl(): WebDavResponse<T>? =
        urlProblem?.let { WebDavResponse.fail(WebDavError.PROTOCOL, message = it) }

    private fun executeUnit(request: Request): WebDavResponse<Unit> {
        val response = execute(request)
        return if (response.isOk) {
            WebDavResponse.ok(Unit, response.httpCode)
        } else {
            WebDavResponse.fail(response.error, response.httpCode, response.message)
        }
    }

    private fun execute(request: Request): WebDavResponse<String> = try {
        val start = System.currentTimeMillis()
        client.newCall(request).execute().use { response ->
            logHttp(request, response, start)
            classify(response)
        }
    } catch (timeout: SocketTimeoutException) {
        WebDavResponse.fail(WebDavError.TIMEOUT, message = "超时")
    } catch (io: IOException) {
        WebDavResponse.fail(WebDavError.NETWORK, message = io.message ?: "网络不可用")
    } catch (throwable: Throwable) {
        WebDavResponse.fail(WebDavError.UNEXPECTED, message = throwable.message)
    }

    /** 只记方法 + 文件名 + 状态码 + 耗时，不记完整 URL / 正文（隐私） */
    private fun logHttp(request: Request, response: Response, start: Long) {
        com.board.ime.sync.SyncLog.event(
            if (response.code in 200..299) com.board.ime.sync.SyncEvent.DONE
            else com.board.ime.sync.SyncEvent.NETWORK_ERROR,
            "HTTP ${request.method} ${request.url.pathSegments.lastOrNull().orEmpty()} " +
                "http=${response.code} ${System.currentTimeMillis() - start}ms",
        )
    }

    /** 把 [classify] 的分类结果转成 `WebDavResponse<Unit>`（错误路径用） */
    private fun fromClassified(classified: WebDavResponse<String>): WebDavResponse<Unit> =
        WebDavResponse.fail(classified.error, classified.httpCode, classified.message)

    /**
     * 带进度的上传体：直接从文件流写入 socket，不在内存里拼整个 body。
     */
    private class ProgressRequestBody(
        private val file: File,
        private val mediaType: okhttp3.MediaType?,
        private val onProgress: (Long, Long) -> Unit,
    ) : okhttp3.RequestBody() {

        override fun contentType(): okhttp3.MediaType? = mediaType

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: okio.BufferedSink) {
            val total = file.length()
            var written = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(STREAM_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
                    written += read
                    onProgress(written, total)
                }
            }
        }
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
        /** 流式读写的块大小（64KB） */
        const val STREAM_BUFFER_BYTES = 64 * 1024

        /** multistatus 里的 href 提取（命名空间前缀不固定，用宽松匹配） */
        private val HREF = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href>", RegexOption.IGNORE_CASE)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 更短的超时：不可达的服务器要**快速失败**，而不是让 UI 一直「同步中」
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        /**
         * 大文件客户端。
         *
         * 20~30MB 的词库在慢速 WebDAV 上可能要几十秒到几分钟，
         * 所以连接超时仍然短（快速发现不可达），但读写 / 调用超时放宽到 5 分钟。
         * 仍然有上限，不会「永久同步中」。
         */
        fun defaultFileClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .followRedirects(true)
            .build()
    }
}
