package com.board.ime.translation

import java.security.MessageDigest

/**
 * 翻译语言。
 *
 * `code` 是**服务商使用的语种代码**（此处与百度翻译通用文本翻译 API 的 code 一致，
 * 依据官方接入文档：`auto` / `zh` / `en` / `jp` / `kor`）。
 */
enum class TranslationLanguage(val code: String, val label: String) {
    AUTO("auto", "自动检测"),
    CHINESE("zh", "中文"),
    ENGLISH("en", "English"),
    JAPANESE("jp", "日本語"),
    KOREAN("kor", "한국어"),
    ;

    companion object {
        /** 目标语言不能是 auto（官方文档：to 不可设置为 auto） */
        val SOURCE_OPTIONS: List<TranslationLanguage> = entries.toList()
        val TARGET_OPTIONS: List<TranslationLanguage> = entries.filter { it != AUTO }

        fun fromCode(code: String?): TranslationLanguage? =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/** 一次翻译请求。**只在内存中存在**，用完即弃。 */
data class TranslationRequest(
    val text: String,
    val source: TranslationLanguage,
    val target: TranslationLanguage,
)

/** 一次翻译结果。**只在内存中存在**，用完即弃。 */
data class TranslationResult(
    val translatedText: String,
    /** 实际使用的源语言（源语言为「自动检测」时，这里是服务商检测出来的语种） */
    val source: TranslationLanguage,
    val target: TranslationLanguage,
)

/**
 * 失败原因。**只带给人看的中文说明**，不带原始 JSON、不带请求参数、
 * 更不带凭据（凭据永远不会出现在任何 failure 里）。
 */
sealed class TranslationFailure(val message: String) {
    data object EmptyText : TranslationFailure("没有可翻译的文本")
    data object TooLong : TranslationFailure("文本过长，超出了翻译服务的字符限制")
    data object NotConfigured : TranslationFailure("请先配置翻译服务")
    data object Network : TranslationFailure("网络不可用，请检查网络后重试")
    data object Tls : TranslationFailure("安全连接失败（HTTPS/TLS 错误）")
    data object Timeout : TranslationFailure("翻译超时，请稍后重试")
    data object Cancelled : TranslationFailure("已取消")

    /** 服务商返回了错误码。只显示错误码本身，不显示原始响应体。 */
    data class ProviderError(val code: String?) :
        TranslationFailure(if (code.isNullOrBlank()) "翻译服务返回了错误" else "翻译服务返回错误（$code）")

    /** HTTP 层失败（只显示状态码） */
    data class Http(val status: Int) : TranslationFailure("翻译服务不可用（HTTP $status）")

    data class Unexpected(val kind: String) : TranslationFailure("翻译失败（$kind）")
}

sealed interface TranslationOutcome {
    data class Success(val result: TranslationResult) : TranslationOutcome
    data class Failure(val failure: TranslationFailure) : TranslationOutcome
}

/**
 * 翻译服务抽象。
 *
 * **翻译逻辑不写在 UI 里**；UI 只依赖这个接口，未来可以换
 * 百度 / 阿里云 / 自建服务，而不动界面代码。
 *
 * 实现方必须保证：
 * - 只发送完成翻译所必需的数据（待翻译文本 + 语言 + 凭据）；
 * - 只走 HTTPS；
 * - 不把原文 / 译文写进任何本地存储或日志。
 */
interface TranslationProvider {

    /** 界面上显示的服务名，例如「百度翻译」 */
    val displayName: String

    /** 是否需要用户先配置凭据（APPID / 密钥） */
    fun isConfigured(): Boolean

    suspend fun translate(request: TranslationRequest): TranslationOutcome
}

/** 计算百度翻译签名：`sign = MD5(appid + q + salt + 密钥)`，32 位小写。 */
internal fun baiduSign(appId: String, query: String, salt: String, secret: String): String {
    val raw = "$appId$query$salt$secret"
    val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
