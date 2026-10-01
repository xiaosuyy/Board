package com.board.ime.clipboard

/**
 * 剪贴板历史生命周期（**纯逻辑，可 JVM 单测**）。
 *
 * 规则（Phase 1-F）：
 * - 普通 Text / Image / File 条目超过 [TTL_MS] 过期；
 * - **固定（pinned）** 不参与过期；
 * - **未读（unread）**（远端刚同步到本机、用户还没查看过）暂不参与过期；
 * - 用户查看后 unread 变 false → 立即回到正常生命周期。
 *
 * 本对象只负责「判断该不该过期」，真正的删除在 DAO / 仓库层执行。
 * 时间判断**不依赖 UI 是否打开**：调用方随时可传入 now。
 */
object ClipboardLifecycle {

    /** 默认历史保留时长：1 小时 */
    const val TTL_MS: Long = 60L * 60L * 1000L

    /**
     * 生命周期基准时间。
     *
     * 远端条目用「落到本机的时间」[receivedAt]，本机条目用 [createdAt]；
     * 都缺失时退化为 [createdAt]。这样**不依赖 UI**、也不受 `updatedAt`（使用会刷新）影响。
     */
    fun timestampOf(createdAt: Long, receivedAt: Long?): Long = receivedAt ?: createdAt

    /**
     * 是否已过期：**非固定** 且 **非未读** 且 距今 ≥ [ttlMs]。
     *
     * 注意：这只描述「可以过期」，是否真的删除还要看调用方（例如图片文件还必须满足
     * 「不被当前系统剪贴板引用」）。
     */
    fun isExpired(
        now: Long,
        timestamp: Long,
        pinned: Boolean,
        unread: Boolean,
        ttlMs: Long = TTL_MS,
    ): Boolean = !pinned && !unread && (now - timestamp) >= ttlMs
}
