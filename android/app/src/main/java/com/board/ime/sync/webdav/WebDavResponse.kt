package com.board.ime.sync.webdav

/**
 * WebDAV 调用的错误分类。
 *
 * 分开是为了让上层能做出可预测的反应：
 * - [AUTH] / [NOT_CONFIGURED]：配置问题，重试没意义 → 直接失败
 * - [NETWORK] / [TIMEOUT] / [SERVER]：临时问题 → 保留任务、下次重试
 * - [NOT_FOUND]：服务器上还没有文件，不算错误（首次上传前）
 * - [CONFLICT]：目录不存在（409）等 → 先建目录
 * - [PROTOCOL]：响应不是合法 JSON / 字段缺失
 */
enum class WebDavError {
    NONE,
    NOT_CONFIGURED,
    NOT_FOUND,
    AUTH,
    CONFLICT,
    SERVER,
    NETWORK,
    TIMEOUT,
    PROTOCOL,
    UNEXPECTED,
    ;

    /** 值不值得重试 */
    val isRetryable: Boolean
        get() = this == NETWORK || this == TIMEOUT || this == SERVER || this == CONFLICT
}

/** 一次 WebDAV 调用的结果（不抛异常，全部用分类表达） */
data class WebDavResponse<out T>(
    val value: T? = null,
    val error: WebDavError = WebDavError.NONE,
    val httpCode: Int = 0,
    /** 人类可读的简短说明，**不包含剪贴板正文** */
    val message: String? = null,
    /** 服务器返回的 Last-Modified（毫秒），冲突判断会用到 */
    val lastModified: Long? = null,
) {
    val isOk: Boolean get() = error == WebDavError.NONE

    companion object {
        fun <T> ok(value: T, httpCode: Int = 200, lastModified: Long? = null): WebDavResponse<T> =
            WebDavResponse(value = value, httpCode = httpCode, lastModified = lastModified)

        fun <T> fail(
            error: WebDavError,
            httpCode: Int = 0,
            message: String? = null,
        ): WebDavResponse<T> = WebDavResponse(error = error, httpCode = httpCode, message = message)
    }
}
