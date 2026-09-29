package com.board.ime.clipboard

/**
 * 敏感度判定结果。
 *
 * 现在只有一个便宜的启发式实现；将来接更强的检测（模型 / 规则库）时，
 * 只要返回同一套枚举，上层与数据库都不用改。
 */
enum class ClipboardSensitivity {
    /** 普通内容，可以正常进历史 */
    NORMAL,

    /** 疑似密码 / 验证码 / OTP */
    SENSITIVE,

    /** 判不出来（例如非文本类型） */
    UNKNOWN,
}

/**
 * 对敏感内容的处理策略。
 *
 * 三个值都先占好，本轮只用到前两个；[ALLOW_SYNC] 留给以后的 SyncClipboard / WebDAV。
 */
enum class ClipboardSyncPolicy {
    /** 根本不进历史（最安全） */
    DO_NOT_SAVE,

    /** 只留在本机，将来也不参与同步 */
    LOCAL_ONLY,

    /** 可以参与跨设备同步 */
    ALLOW_SYNC,
}

/**
 * 敏感内容检测接口。
 *
 * 设计成接口而不是写死的函数，是为了以后能换成更聪明的实现
 * （比如按应用来源、按长度分布、或者本地小模型），而不用动仓库和 UI。
 */
interface ClipboardSensitivityDetector {

    /** 判定一段内容有多敏感 */
    fun detect(content: String, contentType: ClipboardContentType = ClipboardContentType.TEXT): ClipboardSensitivity

    /** 敏感度对应的保存 / 同步策略 */
    fun policyFor(sensitivity: ClipboardSensitivity): ClipboardSyncPolicy
}

/**
 * 默认实现：便宜、可解释、不联网、不上模型。
 *
 * 只认两类最典型的敏感内容：
 * 1. 纯数字 4–8 位（验证码 / OTP 的常见形态）
 * 2. 含「验证码 / 密码 / password / otp」等关键词 **且** 带数字串
 *
 * 判定为 [ClipboardSensitivity.SENSITIVE] 的内容按 [ClipboardSyncPolicy.DO_NOT_SAVE] 处理，
 * 也就是**不会写进历史**（本轮策略，见 README）。
 */
class HeuristicClipboardSensitivityDetector(
    /** 用户可以在「隐私」里关掉它：关掉后敏感内容会保存，但只留在本机 */
    private val skipSensitive: () -> Boolean = { true },
) : ClipboardSensitivityDetector {

    override fun detect(
        content: String,
        contentType: ClipboardContentType,
    ): ClipboardSensitivity {
        if (!contentType.isTextLike) return ClipboardSensitivity.UNKNOWN
        val text = content.trim()
        if (text.isEmpty()) return ClipboardSensitivity.UNKNOWN

        if (OTP_DIGITS.matches(text)) return ClipboardSensitivity.SENSITIVE

        val lower = text.lowercase()
        val hasKeyword = KEYWORDS.any { lower.contains(it) }
        if (hasKeyword && DIGITS.containsMatchIn(text)) return ClipboardSensitivity.SENSITIVE

        return ClipboardSensitivity.NORMAL
    }

    override fun policyFor(sensitivity: ClipboardSensitivity): ClipboardSyncPolicy =
        when (sensitivity) {
            // 用户在隐私设置里关掉「不保存」后：仍然保存，但标记成本机专用，不参与同步
            ClipboardSensitivity.SENSITIVE ->
                if (skipSensitive()) ClipboardSyncPolicy.DO_NOT_SAVE else ClipboardSyncPolicy.LOCAL_ONLY

            ClipboardSensitivity.NORMAL -> ClipboardSyncPolicy.ALLOW_SYNC
            ClipboardSensitivity.UNKNOWN -> ClipboardSyncPolicy.LOCAL_ONLY
        }

    private companion object {
        /** 纯数字 4–8 位：验证码的典型形态 */
        val OTP_DIGITS = Regex("^\\d{4,8}$")
        val DIGITS = Regex("\\d")
        val KEYWORDS = listOf(
            "验证码", "校验码", "动态码", "口令", "密码",
            "verification code", "verify code", "one-time", "otp", "password", "passcode", "pin code",
        )
    }
}
