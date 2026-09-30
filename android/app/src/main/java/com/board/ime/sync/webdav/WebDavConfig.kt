package com.board.ime.sync.webdav

/**
 * 用户自己的 WebDAV 配置。
 *
 * **不硬编码任何服务器地址和账号** —— 全部由用户在设置页填写，密码单独走加密存储
 * （见 [WebDavConfigStore]）。
 */
data class WebDavConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    /** 同步目录（相对 baseUrl），例如 `board` 或 `apps/board`；空表示直接用根目录 */
    val syncDir: String = DEFAULT_DIR,
    /** 自动同步开关（WorkManager 周期 + 输入法启动检查） */
    val autoSync: Boolean = false,
    /**
     * 用户是否**明确允许**未加密 HTTP（默认否）。
     *
     * 隐私基线：默认只允许 HTTPS；开启后也仅对**可信局域网**地址放行，公网 HTTP 一律拒绝
     * （见 [WebDavUrlPolicy]）。
     */
    val allowCleartextHttp: Boolean = false,
) {

    /** 只要填了地址就算「配置过」，能否用还要看测试连接 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    /**
     * 地址 scheme 是否合法。
     *
     * OkHttp 对 `htpps://…` 这种拼错的 scheme 会直接抛 `IllegalArgumentException`，
     * 如果不在这里挡住，错误会以「未预期的异常」形式冒到 UI（真机上见过
     * 「同步失败 · IllegalArgumentException」这种看不懂的提示）。
     */
    val hasValidScheme: Boolean
        get() {
            val url = baseUrl.trim()
            return url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)
        }

    /**
     * 传输安全裁决（HTTPS 放行；HTTP 需用户明确开启且仅限可信局域网）。
     *
     * 注意：判定 HTTP 地址时需要做一次**地址解析**，不要在 UI 主线程反复调用。
     */
    fun securityVerdict(): WebDavUrlPolicy.Verdict =
        WebDavUrlPolicy.evaluate(baseUrl, allowCleartextHttp)

    /** 传输安全层面的拦截原因；`null` 表示放行 */
    fun securityProblem(): String? =
        (securityVerdict() as? WebDavUrlPolicy.Verdict.Blocked)?.message

    /** 配置是否「可以尝试同步」 */
    val isUsable: Boolean
        get() = isConfigured && hasValidScheme

    val hasCredentials: Boolean
        get() = username.isNotBlank() || password.isNotEmpty()

    /** 规范化后的同步目录（去掉首尾斜杠） */
    val normalizedDir: String
        get() = syncDir.trim().trim('/')

    companion object {
        const val DEFAULT_DIR = "board"
    }
}
