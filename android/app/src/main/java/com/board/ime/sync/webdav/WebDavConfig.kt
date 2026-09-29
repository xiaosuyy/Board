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
) {

    /** 只要填了地址就算「配置过」，能否用还要看测试连接 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank()

    val hasCredentials: Boolean
        get() = username.isNotBlank() || password.isNotEmpty()

    /** 规范化后的同步目录（去掉首尾斜杠） */
    val normalizedDir: String
        get() = syncDir.trim().trim('/')

    companion object {
        const val DEFAULT_DIR = "board"
    }
}
