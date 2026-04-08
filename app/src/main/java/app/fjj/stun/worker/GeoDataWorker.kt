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

        fun schedule(context: Context) {
            val interval = SettingsManager.getUpdateInterval(context)
            if (interval <= 0) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
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
