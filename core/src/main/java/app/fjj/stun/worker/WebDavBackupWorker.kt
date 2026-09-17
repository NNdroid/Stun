package app.fjj.stun.worker

import android.content.Context
import androidx.work.*
import app.fjj.stun.backup.WebDavBackupManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.WebDavClient
import java.util.concurrent.TimeUnit

/**
 * 每日 WebDAV 自动备份。用户在设置中开启后注册（见 SettingsFragment）；
 * 未配置齐全时直接成功退出（不重试轰炸）。
 */
class WebDavBackupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val WORK_NAME = "WebDavAutoBackup"

        /** 按设置中的间隔调度（未开启自动备份时取消）。间隔变更后重复调用即重排。 */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (!SettingsManager.isWebDavAutoBackupEnabled(context)) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val hours = SettingsManager.getWebDavBackupIntervalHours(context).coerceIn(1, 720)
            val request = PeriodicWorkRequestBuilder<WebDavBackupWorker>(hours, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }

    override suspend fun doWork(): Result {
        val config = WebDavBackupManager.Config(
            url = SettingsManager.getWebDavUrl(applicationContext),
            user = SettingsManager.getWebDavUser(applicationContext),
            pass = SettingsManager.getWebDavPass(applicationContext),
            pin = SettingsManager.getWebDavPin(applicationContext)
        )
        if (!SettingsManager.isWebDavAutoBackupEnabled(applicationContext) || !config.isConfigured) {
            return Result.success()
        }
        return try {
            WebDavBackupManager.backup(applicationContext, config)
            SettingsManager.saveWebDavLastBackupTime(applicationContext, System.currentTimeMillis())
            StunLogger.i("WebDavBackup", "Auto backup OK")
            Result.success()
        } catch (e: WebDavClient.WebDavException) {
            StunLogger.e("WebDavBackup", "Auto backup failed: ${e.message}")
            // 网络类错误交给 WorkManager 退避重试；配置/PIN 错误重试无意义
            if (e.statusCode in listOf(401, 403, 404)) Result.failure() else Result.retry()
        } catch (e: Exception) {
            StunLogger.e("WebDavBackup", "Auto backup failed", e)
            Result.retry()
        }
    }
}
