package com.board.ime.translation

import java.security.MessageDigest

/**
 * 翻译语言（13 种目标语言，与互译模式的语言列表一致）。
 *
 * - `code` 是**百度翻译通用文本翻译 API 的语种代码**（依据官方接入文档）；
 * - `promptName` 是给 OpenAI 格式接口的系统提示里用的语言全名，
 *   避免让模型去猜「fra」「pt」这类缩写。
 */
enum class TranslationLanguage(val code: String, val label: String, val promptName: String) {
    AUTO("auto", "自动检测", "the source language (detect it automatically)"),
    CHINESE("zh", "中文", "Simplified Chinese"),
    ENGLISH("en", "English", "English"),
    JAPANESE("jp", "日本語", "Japanese"),
    KOREAN("kor", "한국어", "Korean"),
    FRENCH("fra", "Français", "French"),
    GERMAN("de", "Deutsch", "German"),
    SPANISH("spa", "Español", "Spanish"),
    RUSSIAN("ru", "Русский", "Russian"),
    PORTUGUESE("pt", "Português", "Portuguese"),
    ITALIAN("it", "Italiano", "Italian"),
    ARABIC("ara", "العربية", "Arabic"),
    THAI("th", "ไทย", "Thai"),
    VIETNAMESE("vie", "Tiếng Việt", "Vietnamese"),
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
    /** 服务商返回了错误码：显示映射后的中文说明 + 错误码，不显示原始响应体。 */
    data class ProviderError(val code: String?) :
        TranslationFailure(translateProviderMessage(code))

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

/**
 * 百度翻译开放平台「错误码列表」的中文说明。
 *
 * 依据官方错误码表（52003 未授权用户 / 54001 签名错误 / 54003 访问频率受限 /
 * 54004 账户余额不足 …）。**表里没有的码不猜测含义**，只回显原始错误码，方便排查。
 */
internal fun translateProviderMessage(code: String?): String {
    if (code.isNullOrBlank()) return "翻译服务返回了错误"
    val explain = when (code) {
        "52001" -> "请求超时，请重试"
        "52002" -> "服务端系统错误，请重试"
        "52003" -> "未授权用户：APPID 不正确，或「通用文本翻译」服务未开通"
        "54000" -> "必填参数为空"
        "54001" -> "签名错误：请检查密钥是否填对"
        "54003" -> "访问频率受限，请稍后重试"
        "54004" -> "账户余额不足"
        "54005" -> "长文本请求过于频繁，请稍后重试"
        "58000" -> "客户端 IP 非法"
        "58001" -> "语言方向不支持"
        "58002" -> "服务当前已关闭"
        "90107" -> "认证未通过或未生效"
        else -> null
    }
    return if (explain == null) "翻译服务返回错误（$code）" else "$explain（$code）"
}
