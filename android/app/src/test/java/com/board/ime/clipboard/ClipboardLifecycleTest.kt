package com.board.ime.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1-F 剪贴板历史生命周期**纯逻辑**单测（离线、不碰 DB / 文件）。
 *
 * 覆盖：1 小时边界、固定保护、未读保护、未读转已读后恢复生命周期。
 */
class ClipboardLifecycleTest {

    private val now = 1_000_000_000L
    private val hour = ClipboardLifecycle.TTL_MS

    // 1. 普通条目超过 1 小时 → 过期
    @Test
    fun plainItemOlderThanOneHourExpires() {
        assertTrue(
            ClipboardLifecycle.isExpired(now, now - hour - 1, pinned = false, unread = false),
        )
    }

    // 2. 未满 1 小时 → 保留
    @Test
    fun plainItemWithinOneHourKept() {
        assertFalse(
            ClipboardLifecycle.isExpired(now, now - hour + 1, pinned = false, unread = false),
        )
    }

    // 1 小时整点边界：>= 即过期
    @Test
    fun exactBoundaryExpires() {
        assertTrue(ClipboardLifecycle.isExpired(now, now - hour, pinned = false, unread = false))
    }

    // 3. pinned → 保留（即使早已超时）
    @Test
    fun pinnedNeverExpires() {
        assertFalse(
            ClipboardLifecycle.isExpired(now, now - hour * 24, pinned = true, unread = false),
        )
    }

    // 4. unread → 保留（远端刚同步、用户还没看过）
    @Test
    fun unreadNeverExpiresUntilViewed() {
        assertFalse(
            ClipboardLifecycle.isExpired(now, now - hour * 24, pinned = false, unread = true),
        )
    }

    // 5. unread 变 false → 立刻恢复 1 小时生命周期
    @Test
    fun unreadBecomesReadThenNormalLifecycleApplies() {
        val timestamp = now - hour - 1
        assertFalse(ClipboardLifecycle.isExpired(now, timestamp, pinned = false, unread = true))
        assertTrue(ClipboardLifecycle.isExpired(now, timestamp, pinned = false, unread = false))
    }

    // pinned 和 unread 同时存在也保留
    @Test
    fun pinnedAndUnreadBothProtect() {
        assertFalse(ClipboardLifecycle.isExpired(now, now - hour * 48, pinned = true, unread = true))
    }

    // 时间戳选择：远端用 receivedAt，本机用 createdAt
    @Test
    fun timestampPrefersReceivedAtForRemote() {
        assertTrue(ClipboardLifecycle.timestampOf(createdAt = 1L, receivedAt = 2L) == 2L)
        assertTrue(ClipboardLifecycle.timestampOf(createdAt = 1L, receivedAt = null) == 1L)
    }

    // 常量就是 1 小时（避免被误改）
    @Test
    fun ttlIsExactlyOneHour() {
        assertTrue(ClipboardLifecycle.TTL_MS == 60L * 60L * 1000L)
    }
}
