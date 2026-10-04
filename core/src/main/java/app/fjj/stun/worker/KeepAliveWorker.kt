package app.fjj.stun.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.fjj.stun.remote.KeepAliveManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import java.util.concurrent.TimeUnit

/**
 * Shizuku 保活的周期任务。
 *
 * 它干两件事，缺一不可：
 * 1. **把进程拉回来** —— WorkManager 到点会自己把 app 进程唤活（这正是无 root 场景下唯一
 *    能在进程死后重新跑代码的通道），然后 [KeepAliveManager] 把 Web 控制台重新监听起来。
 * 2. **重新断言省电豁免** —— 借 Shizuku 再写一次电池白名单 / 待机桶。光是"定时唤醒"并不够：
 *    进了 Doze 之后进程随时可能再被回收，而白名单被 ROM 的清理工具摘掉是常事。
 *
 * 周期固定 15min（WorkManager 允许的最小间隔）。要更快只能靠 Magisk service.d 那条路径。
 */
class KeepAliveWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "KeepAlive"
        private const val WORK_NAME = "StunKeepAlive"
        private const val PERIOD_MINUTES = 15L

        /** 调度保活任务。重复调用即重排（UPDATE），所以"开启"可以放心反复点。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<KeepAliveWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            StunLogger.i(TAG, "Scheduled keep-alive worker (period=${PERIOD_MINUTES}min)")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            StunLogger.i(TAG, "Cancelled keep-alive worker")
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        // 用户在设置里关掉后仍可能被调度到（cancel 与已入队任务之间有竞态），
        // 这里再挡一次，直接空转退出，不做任何设备操作。
        if (!SettingsManager.isShizukuKeepAliveEnabled(ctx)) {
            StunLogger.i(TAG, "Keep-alive disabled by user; skipping")
            return Result.success()
        }
        val notes = KeepAliveManager.runShizukuKeepAliveOnce(ctx)
        StunLogger.i(TAG, "Keep-alive tick: $notes")
        // 失败也不重试：Shizuku 没起 / root 没了这类问题重试多少次都一样，
        // 等下一个周期或用户重新开关更合适。
        return Result.success()
    }
}
