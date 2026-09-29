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

/**
 * 单条同步通道（剪贴板 / 词库 / 学习词…）的阶段状态。
 *
 * 「立即同步」是**分阶段**跑的：剪贴板先跑完就先显示「已同步」，
 * 大型词库文件再单独跑并显示下载百分比 —— 不允许因为一个 20~30MB 的词库
 * 让整页一直只显示「同步中」。
 */
enum class SyncPartState {
    /** 本次还没轮到 */
    IDLE,

    /** 正在同步 */
    SYNCING,

    /** 本次成功 */
    SUCCESS,

    /** 本次失败（详情在 detail，不含正文） */
    FAILED,

    /** 按开关跳过（没勾选 / 没配置） */
    SKIPPED,
}

/** 单条通道的状态 + 进度（progress 为 0f..1f，null 表示不确定） */
data class SyncPart(
    val state: SyncPartState = SyncPartState.IDLE,
    val detail: String? = null,
    val progress: Float? = null,
) {
    /**
     * 把「还在同步中」归到终态。
     *
     * 任何中断 / 失败 / 进程被杀都不允许在 UI 上留下「同步中」，
     * 否则用户看到的永远是一个不会结束的状态。
     */
    fun settledIfRunning(reason: String? = null): SyncPart =
        if (state == SyncPartState.SYNCING) {
            SyncPart(SyncPartState.FAILED, reason ?: detail, null)
        } else {
            this
        }

    /** 例如「已同步」「下载中 37%」「失败」「已跳过」 */
    val label: String
        get() = when (state) {
            SyncPartState.IDLE -> "待同步"
            SyncPartState.SYNCING -> {
                val percent = progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                "同步中$percent"
            }

            SyncPartState.SUCCESS -> "已同步"
            SyncPartState.FAILED -> "失败"
            SyncPartState.SKIPPED -> "已跳过"
        }

    val ok: Boolean
        get() = state == SyncPartState.SUCCESS || state == SyncPartState.SKIPPED
}

/** 同步状态 + 最近一次同步时间 */
data class SyncStatus(
    val kind: SyncStatusKind = SyncStatusKind.NOT_CONFIGURED,
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    /** 上一次成功同步了哪些方向，给设置页一行摘要 */
    val lastSummary: String? = null,
    /** 剪贴板通道的独立状态 */
    val clipboard: SyncPart = SyncPart(),
    /** 导入词库（大型数据源文件）通道的独立状态 */
    val dictionary: SyncPart = SyncPart(),
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
