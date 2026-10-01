package com.board.ime.sync

import android.content.Context
import com.board.ime.clipboard.ClipboardRepository
import com.board.ime.database.BoardDatabase
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit

/**
 * WorkManager 周期同步。
 *
 * 明确一点：这**不是实时推送**。它提供的是：
 * - 输入法 / 设置页启动时的一次检查
 * - 复制新内容后的一次去抖上传
 * - WorkManager 的周期兜底（最短 15 分钟，实际受系统调度影响）
 *
 * 真正的实时（局域网 / 长连接）留给后续阶段。
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Phase 1-F：后台周期维护剪贴板历史（与网络 / 同步状态无关，始终执行）。
        // Worker 拿不到当前系统剪贴板，因此传 null → 只让**非文件型**条目过期，
        // 绝不误删仍可能被系统剪贴板引用的 PNG（保护策略见 ClipboardRepository.expireHistory）。
        runCatching {
            ClipboardRepository(
                dao = BoardDatabase.get(applicationContext).clipboardDao(),
                contentStore = ClipboardRepository.contentStoreFor(applicationContext),
            ).expireHistory(currentClipboardHash = null)
        }
        val engine = SyncEngineHolder.get(applicationContext)
        val config = engine.loadConfig()
        if (!config.isConfigured || !config.autoSync) {
            return Result.success()
        }
        val result = engine.syncNow(SyncTrigger.WORKER)
        return when (result.outcome) {
            // 配置 / 认证问题：重试也不会好，标记成功以免 WorkManager 一直重试
            SyncOutcome.SUCCESS, SyncOutcome.SKIPPED, SyncOutcome.CONFIG_ERROR -> Result.success()
            // 网络 / 服务器临时问题：交给 WorkManager 退避重试
            SyncOutcome.RETRYABLE_ERROR -> Result.retry()
        }
    }

    companion object {
        const val PERIODIC_WORK_NAME = "board_clipboard_sync"
        const val ONE_SHOT_WORK_NAME = "board_clipboard_sync_once"
    }
}

/** 同步调度：按用户开关注册 / 取消周期任务，并提供一次性兜底任务 */
object SyncScheduler {

    private val networkConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun applyAutoSync(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            manager.cancelUniqueWork(SyncWorker.PERIODIC_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            PERIODIC_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            SyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** 失败后的一次性重试（网络恢复时 WorkManager 会按退避策略再跑） */
    fun requestOneShot(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(SyncWorker.ONE_SHOT_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /** WorkManager 周期下限是 15 分钟；这里用 30 分钟，省电且够用 */
    private const val PERIODIC_INTERVAL_MINUTES = 30L
}
