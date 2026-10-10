package app.fjj.stun.worker

import android.content.Context
import androidx.work.*
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import java.util.concurrent.TimeUnit

class GeoDataWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    override fun doWork(): Result {
        StunLogger.i("GeoDataWorker", "Starting scheduled GeoData update...")
        return try {
            SettingsManager.updateGeoDataSync(applicationContext)
            Result.success()
        } catch (e: Exception) {
            StunLogger.e("GeoDataWorker", "GeoData update failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "GeoDataUpdateWork"

        // WorkManager 周期任务的下限（15 分钟）。见 schedule() 里的说明。
        private const val MIN_PERIODIC_INTERVAL_SECONDS = 900L

        fun schedule(context: Context) {
            val requested = SettingsManager.getUpdateInterval(context)
            if (requested <= 0) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }

            // WorkManager 的周期性任务最短间隔是 15 分钟：低于该值 build() 直接抛
            // IllegalArgumentException。schedule() 的调用方（AppBootstrap.start）只 catch 后
            // 打一条日志，于是 geo 规则从此不再自动更新，而设置界面还显示着用户选的间隔——
            // 一个完全静默的功能丢失。钳到下限并留痕。
            val interval = maxOf(requested, MIN_PERIODIC_INTERVAL_SECONDS)
            if (interval != requested) {
                StunLogger.w("GeoDataWorker", "Update interval $requested s is below WorkManager's ${MIN_PERIODIC_INTERVAL_SECONDS} s minimum; clamping")
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val updateRequest = PeriodicWorkRequestBuilder<GeoDataWorker>(
                interval, TimeUnit.SECONDS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                updateRequest
            )
            StunLogger.i("GeoDataWorker", "GeoData update scheduled every $interval seconds")
        }

        fun runOnceNow(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val runOnceRequest = OneTimeWorkRequestBuilder<GeoDataWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(runOnceRequest)
        }
    }
}

