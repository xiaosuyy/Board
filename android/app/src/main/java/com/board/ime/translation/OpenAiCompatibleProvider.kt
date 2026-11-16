package com.board.ime.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * OpenAI 格式的自定义翻译接口（`POST {baseUrl}/chat/completions`）。
 *
 * 兼容 DeepSeek、通义千问（DashScope 兼容模式）、Ollama 等：
 * - 请求体走标准 `messages` / `model`；
 * - 系统提示要求「只输出译文」，不解释、不加引号、不加 Markdown；
 * - 解析 `choices[0].message.content`。
 *
 * 安全约束：
 * - 远程地址**必须 HTTPS**；仅本机 / 内网地址（Ollama）允许 HTTP；
 * - API Key 只放在 `Authorization` 头里，日志绝不输出 Key、也不输出请求体；
 * - 日志只记录服务名、HTTP 状态、字符数、耗时；失败时额外记录服务端原始错误说明
 *   （不含原文）。
 */
class OpenAiCompatibleProvider(
    private val settingsProvider: () -> TranslationSettings,
    private val client: OkHttpClient = defaultClient(),
) : TranslationProvider {

    override val displayName: String = "OpenAI 格式接口"

    override fun isConfigured(): Boolean = settingsProvider().openAiReady

    override suspend fun translate(request: TranslationRequest): TranslationOutcome =
        withContext(Dispatchers.IO) {
            val text = request.text
            if (text.isBlank()) {
                return@withContext TranslationOutcome.Failure(TranslationFailure.EmptyText)
            }
            if (text.length > MAX_QUERY_CHARS) {
                return@withContext TranslationOutcome.Failure(TranslationFailure.TooLong)
            }
            val settings = settingsProvider().normalized()
            if (!settings.openAiReady) {
                return@withContext TranslationOutcome.Failure(TranslationFailure.NotConfigured)
            }
            val endpoint = chatCompletionsUrl(settings.baseUrl)
                ?: return@withContext TranslationOutcome.Failure(
                    TranslationFailure.Unexpected("接口地址需为 HTTPS（本机 / 内网可用 HTTP）"),
                )

            val payload = buildPayload(settings.model, request, text)
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .header("Content-Type", JSON_MEDIA_TYPE.toString())
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            if (settings.apiKey.isNotBlank()) {
                requestBuilder.header("Authorization", "Bearer ${settings.apiKey}")
            }
            val call = client.newCall(requestBuilder.build())
            Log.d(TAG, "translate start provider=$displayName endpoint=${endpointHost(endpoint)}")

            val job: Job? = coroutineContext[Job]
            val cancellation = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }

            val startedAt = System.currentTimeMillis()
            try {
                call.execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (!response.isSuccessful) {
                        // 保留服务端原始错误说明（截断、去换行），排查才有依据；绝不记录原文
                        Log.w(
                            TAG,
                            "translate failed provider=$displayName http=${response.code} " +
                                "chars=${text.length} elapsedMs=$elapsed " +
                                "remote=${remoteError(raw)}",
                        )
                        return@withContext TranslationOutcome.Failure(
                            TranslationFailure.Http(response.code),
                        )
                    }
                    val outcome = parse(raw, request)
                    when (outcome) {
                        is TranslationOutcome.Success -> Log.i(
                            TAG,
                            "translate success provider=$displayName chars=${text.length} elapsedMs=$elapsed",
                        )

                        is TranslationOutcome.Failure -> Log.w(
                            TAG,
                            "translate failed provider=$displayName reason=parse " +
                                "chars=${text.length} elapsedMs=$elapsed",
                        )
                    }
                    outcome
                }
            } catch (ssl: SSLException) {
                Log.w(TAG, "translate failed provider=$displayName reason=tls")
                TranslationOutcome.Failure(TranslationFailure.Tls)
            } catch (io: IOException) {
                // 只记录异常类型与说明（可能含主机名，不含 Key / 原文）
                Log.w(TAG, "translate failed provider=$displayName reason=io msg=${io.message}")
                if (job?.isCancelled == true) {
                    TranslationOutcome.Failure(TranslationFailure.Cancelled)
                } else {
                    TranslationOutcome.Failure(TranslationFailure.Network)
                }
            } catch (throwable: Throwable) {
                Log.w(TAG, "translate failed provider=$displayName reason=${throwable.javaClass.simpleName}")
                TranslationOutcome.Failure(TranslationFailure.Unexpected(throwable.javaClass.simpleName))
            } finally {
                cancellation?.dispose()
            }
        }

    private fun buildPayload(
        model: String,
        request: TranslationRequest,
        text: String,
    ): String {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", systemPrompt(request, text)),
            )
            .put(JSONObject().put("role", "user").put("content", text))
        return JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("stream", false)
            .put("messages", messages)
            .toString()
    }

    private fun systemPrompt(request: TranslationRequest, text: String): String {
        val from = request.source.promptName
        return buildString {
            append("You are a professional translation engine. ")
            append("Translate the user's text from $from into ${request.target.promptName}. ")
            append("Output only the translation itself: no explanations, no notes, no quotes, ")
            append("no Markdown fences, and no repetition of the source text. ")
            if (text.contains('\n')) {
                append("Keep the original line breaks and formatting. ")
            }
        }
    }

    private fun parse(raw: String, request: TranslationRequest): TranslationOutcome {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return TranslationOutcome.Failure(TranslationFailure.Unexpected("响应解析"))
        val choices = json.optJSONArray("choices")
        val content = choices?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (content.isEmpty()) {
            return TranslationOutcome.Failure(TranslationFailure.Unexpected("空结果"))
        }
        return TranslationOutcome.Success(
            TranslationResult(
                translatedText = content,
                source = if (request.source == TranslationLanguage.AUTO) {
                    TranslationLanguage.AUTO
                } else {
                    request.source
                },
                target = request.target,
            ),
        )
    }

    /** 只记录主机与端口，不记录路径 / 查询串（可能含访问令牌） */
    private fun endpointHost(endpoint: String): String = runCatching {
        val uri = URI(endpoint)
        "${uri.scheme}://${uri.host}:${uri.port}"
    }.getOrDefault("-")

    /** 服务端错误体里的原始说明（截断到 200 字符，只用于日志） */
    private fun remoteError(raw: String): String = runCatching {
        val json = JSONObject(raw)
        val error = json.optJSONObject("error")
        val message = error?.optString("message").orEmpty().ifBlank {
            error?.optString("type").orEmpty().ifBlank { raw }
        }
        message.replace('\n', ' ').take(200)
    }.getOrDefault(raw.replace('\n', ' ').take(200))

    companion object {
        private const val TAG = "BoardTranslate"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 按行翻译的文本远小于此；超过就本地拒绝，不发请求 */
        const val MAX_QUERY_CHARS = 4000

        /**
         * 拼接 `chat/completions` 路径；地址不安全时返回 null。
         *
         * - HTTPS：任意主机；
         * - HTTP：只允许本机 / 内网（Ollama、局域网网关），避免公网明文传输。
         */
        internal fun chatCompletionsUrl(baseUrl: String): String? {
            val trimmed = baseUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host ?: return null
            val secure = scheme == "https" || (scheme == "http" && isLocalHost(host))
            if (!secure) return null
            return if (trimmed.endsWith("/chat/completions")) {
                trimmed
            } else {
                "$trimmed/chat/completions"
            }
        }

        private fun isLocalHost(host: String): Boolean {
            val normalized = host.lowercase()
            if (normalized == "localhost" || normalized == "[::1]" || normalized == "::1") return true
            if (normalized.endsWith(".local")) return true
            val parts = normalized.split('.').mapNotNull { it.toIntOrNull() }
            if (parts.size != 4) return false
            return when (parts[0]) {
                10, 127 -> true
                192 -> parts[1] == 168
                172 -> parts[1] in 16..31
                169 -> parts[1] == 254
                else -> false
            }
        }

        /** LLM 通常比词典翻译慢：读超时放宽，但仍然有上限 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }
}
