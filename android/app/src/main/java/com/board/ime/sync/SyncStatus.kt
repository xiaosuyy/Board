package com.board.ime.sync

/**
 * 同步通道状态。
 *
 * 只在**设置页**展示，键盘顶部区域保持原样（不加快捷状态文字）。
 */
enum class SyncStatusKind {
    /** 还没填 WebDAV 地址 */
    NOT_CONFIGURED,

    /** 配置存在、上次连接成功 */
    CONNECTED,

    /** 正在同步 */
    SYNCING,

    /** 上次同步成功 */
    SUCCESS,

    /** 上次同步失败（详情看 lastError） */
    FAILED,
}

/** 同步状态 + 最近一次同步时间 */
data class SyncStatus(
    val kind: SyncStatusKind = SyncStatusKind.NOT_CONFIGURED,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** 上一次成功同步了哪些方向，给设置页一行摘要 */
    val lastSummary: String? = null,
) {
    val label: String
        get() = when (kind) {
            SyncStatusKind.NOT_CONFIGURED -> "未配置"
            SyncStatusKind.CONNECTED -> "已连接"
            SyncStatusKind.SYNCING -> "同步中…"
            SyncStatusKind.SUCCESS -> "同步成功"
            SyncStatusKind.FAILED -> "同步失败"
        }
}

/** 一次同步的结果分类，Worker 用它决定要不要重试 */
enum class SyncOutcome {
    SUCCESS,

    /** 没配置 / 自动同步关闭 —— 不算失败 */
    SKIPPED,

    /** 配置或认证问题，重试没意义 */
    CONFIG_ERROR,

    /** 网络 / 服务器临时问题，可以重试 */
    RETRYABLE_ERROR,
}

/** 一次同步的返回值 */
data class SyncResult(
    val outcome: SyncOutcome,
    val status: SyncStatus,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val conflicts: Int = 0,
    val skipped: Int = 0,
)

/** 触发来源，只用于日志和「最近同步时间」 */
enum class SyncTrigger {
    MANUAL,
    APP_OPEN,
    IME_START,
    AFTER_CLIPBOARD_CHANGE,
    WORKER,
}
