package com.board.ime.translation

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.random.Random

/**
 * 百度翻译 · 通用文本翻译 API。
 *
 * 实现依据官方接入文档（通用翻译API接入文档）：
 * - 通过 HTTP 接口对外提供多语种互译，**本实现强制走 HTTPS**；
 * - 参数：`q`（UTF-8）、`from`（可 `auto`）、`to`（**不可** `auto`）、
 *   `appid`、`salt`（随机数）、`sign`；
 * - `sign = MD5(appid + q + salt + 密钥)`，32 位小写；
 * - **拼接签名时 `q` 不做 URL encode**（文档明确：先签名，再在发送前对 q 编码）；
 * - 返回：`from` / `to` / `trans_result[{src,dst}]` / 出错时 `error_code`。
 *
 * 凭据（APPID + 密钥）**由用户自己提供**，只从加密存储里读；
 * 代码 / BuildConfig / assets / manifest / native 里**不存在任何内置 Key**。
 *
 * 日志只记录：provider 名、成功/失败、HTTP 状态码、字符长度、耗时。
 * **绝不记录原文、译文、密钥、Authorization、完整请求 URL。**
 */
class BaiduTranslationProvider(
    private val credentialsProvider: () -> TranslationCredentials,
    private val client: OkHttpClient = defaultClient(),
) : TranslationProvider {

    override val displayName: String = "百度翻译"

    override fun isConfigured(): Boolean = credentialsProvider().isComplete

    override suspend fun translate(request: TranslationRequest): TranslationOutcome =
        withContext(Dispatchers.IO) {
            val text = request.text
            if (text.isBlank()) {
                return@withContext TranslationOutcome.Failure(TranslationFailure.EmptyText)
            }
            if (text.length > MAX_QUERY_CHARS) {
                return@withContext TranslationOutcome.Failure(TranslationFailure.TooLong)
            }
            val credentials = credentialsProvider()
            if (!credentials.isComplete) {
                return@withContext TranslationOutcome.Failure(TranslationFailure.NotConfigured)
            }

            val salt = Random.nextLong(100_000, 999_999).toString()
            val sign = baiduSign(credentials.appId, text, salt, credentials.secret)
            val form = FormBody.Builder()
                .add("q", text)
                .add("from", request.source.code)
                .add("to", request.target.code)
                .add("appid", credentials.appId)
                .add("salt", salt)
                .add("sign", sign)
                .build()
            val httpRequest = Request.Builder().url(ENDPOINT).post(form).build()
            val call = client.newCall(httpRequest)

            // 协程被取消（用户离开翻译面板）时立刻取消 HTTP 请求，不留后台请求
            val job: Job? = coroutineContext[Job]
            val cancellation = job?.invokeOnCompletion { if (job.isCancelled) call.cancel() }

            val startedAt = System.currentTimeMillis()
            try {
                call.execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "translate failed provider=$displayName http=${response.code} " +
                                "chars=${text.length} elapsedMs=$elapsed",
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
                            "translate failed provider=$displayName " +
                                "code=${(outcome.failure as? TranslationFailure.ProviderError)?.code ?: "-"} " +
                                "chars=${text.length} elapsedMs=$elapsed",
                        )
                    }
                    outcome
                }
            } catch (ssl: SSLException) {
                Log.w(TAG, "translate failed provider=$displayName reason=tls")
                TranslationOutcome.Failure(TranslationFailure.Tls)
            } catch (io: IOException) {
                Log.w(TAG, "translate failed provider=$displayName reason=io")
                // OkHttp 的取消也是 IOException：区分「用户取消」与真实网络故障
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

    private fun parse(raw: String, request: TranslationRequest): TranslationOutcome {
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return TranslationOutcome.Failure(TranslationFailure.Unexpected("响应解析"))
        val errorCode = json.optString("error_code").takeIf { it.isNotBlank() }
        if (errorCode != null) {
            return TranslationOutcome.Failure(TranslationFailure.ProviderError(errorCode))
        }
        val array = json.optJSONArray("trans_result")
            ?: return TranslationOutcome.Failure(TranslationFailure.Unexpected("响应字段"))
        val builder = StringBuilder()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val dst = item.optString("dst")
            if (dst.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(dst)
        }
        if (builder.isEmpty()) {
            return TranslationOutcome.Failure(TranslationFailure.Unexpected("空结果"))
        }
        return TranslationOutcome.Success(
            TranslationResult(
                translatedText = builder.toString(),
                source = TranslationLanguage.fromCode(json.optString("from")) ?: request.source,
                target = TranslationLanguage.fromCode(json.optString("to")) ?: request.target,
            ),
        )
    }

    companion object {
        private const val TAG = "BoardTranslate"

        /**
         * 官方接口路径 `/api/trans/vip/translate`；主机取自百度翻译开放平台域名
         * `fanyi-api.baidu.com`。**强制 HTTPS**，不提供 HTTP 回退。
         */
        const val ENDPOINT = "https://fanyi-api.baidu.com/api/trans/vip/translate"

        /**
         * 客户端侧保守上限。
         *
         * 官方文档的字符/字节上限本阶段**未能核到**，因此这里不是"官方限制"，
         * 而是一个**保守的自我保护值**，超限直接在本地拒绝、不发请求。
         */
        const val MAX_QUERY_CHARS = 1000

        /** 超时必须有上限：不阻塞键盘主线程，也不无限等待 */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
