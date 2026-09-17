package app.fjj.stun.worker

import android.content.Context
import androidx.work.*
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.SubscriptionManager
import java.util.concurrent.TimeUnit

/**
 * 周期订阅同步 worker。
 *
 * - 周期固定为 15min（WorkManager 允许的最小间隔）；内部按每条订阅的 profile-update-interval
 *   门控，只有距上次成功同步已超过该间隔的订阅才会真正去拉取，避免无谓请求。
 * - 首次执行延迟对齐到下一个 15min 边界，使后台刷新落在整齐的时间点（:00/:15/:30/:45）。
 * - 未配置订阅 / 没有到期订阅时直接成功退出，不重试轰炸。
 */
class SubscriptionSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "SubscriptionSync"
        private const val WORK_NAME = "SubscriptionAutoSync"
        private const val PERIOD_MINUTES = 15L
        /** updateIntervalHours==0（服务端未给间隔）时的兜底自动更新间隔。 */
        private const val DEFAULT_INTERVAL_HOURS = 24

        /** 调度周期订阅同步。重复调用即重排（ExistingPeriodicWorkPolicy.UPDATE）。 */
        fun schedule(context: Context) {
            val periodMs = PERIOD_MINUTES * 60_000L
            // 首次延迟对齐到下一个 15min 边界
            val delayMs = periodMs - (System.currentTimeMillis() % periodMs)
            val request = PeriodicWorkRequestBuilder<SubscriptionSyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            StunLogger.i(TAG, "Scheduled periodic subscription sync (period=${PERIOD_MINUTES}min, firstDelay=${delayMs}ms)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // 用户可在订阅面板关掉后台自动同步；关掉时 worker 直接空转退出（调度仍保留，便于再开）。
        if (!app.fjj.stun.repo.SettingsManager.isSubscriptionAutoSyncEnabled(ctx)) {
            StunLogger.i(TAG, "Background subscription sync disabled by user; skipping")
            return Result.success()
        }
        val subs = SubscriptionManager.getSubscriptions(ctx)
        if (subs.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        val due = subs.filter { sub ->
            // 只处理本机可拉取的协议（https）；历史残留的 sftp 等非法/不支持的链接直接跳过，不做无效尝试
            if (!SubscriptionManager.isValidSubscriptionScheme(sub.url)) return@filter false
            val hours = if (sub.updateIntervalHours > 0) sub.updateIntervalHours else DEFAULT_INTERVAL_HOURS
            val last = SubscriptionManager.getLastSyncForUrl(ctx, sub.url)
            (now - last) >= hours * 3_600_000L
        }
        if (due.isEmpty()) {
            StunLogger.i(TAG, "No subscription due for update")
            return Result.success()
        }

        StunLogger.i(TAG, "Syncing ${due.size} due subscription(s)")
        val results = SubscriptionManager.syncAllSubscriptions(ctx, due)
        // syncAllSubscriptions 已对成功项写入 getLastSyncForUrl，此处仅做日志统计
        val ok = results.count { it.success }
        StunLogger.i(TAG, "Subscription sync done: $ok/${due.size} succeeded")
        return Result.success()
    }
}
