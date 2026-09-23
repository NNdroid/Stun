package app.fjj.stun.worker

import android.content.Context
import androidx.work.*
import app.fjj.stun.backup.WebDavBackupManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.util.WebDavClient
import java.util.concurrent.TimeUnit

/**
 * WebDAV 定时同步（默认档＝仅上传，即原来的"每日自动备份"）。用户在设置中开启后注册；
 * 未配置齐全时直接成功退出（不重试轰炸）。
 *
 * 走哪条路径由 [SettingsManager.getWebDavSyncMode] 决定，三档统一收敛到
 * [WebDavBackupManager.sync] —— 这样"方向"只有一处判断，worker 与手动按钮不会语义漂移。
 */
class WebDavBackupWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    companion object {
        /** ⚠️ 值保持 `WebDavAutoBackup` 不变：改名会让升级前已排好的周期任务变成孤儿。 */
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
        // 三档模式都走同一条 sync()：仅上传时它就等价于原来的 backup()，
        // 这样"方向"只有一处判断，不会出现 worker 和手动按钮语义漂移。
        val mode = SettingsManager.getWebDavSyncMode(applicationContext)
        return try {
            val result = WebDavBackupManager.sync(applicationContext, config, mode)
            // 「上次备份」只在**真的传了**的时候刷新：仅下载模式从不上传，
            // 刷了会让用户以为备份一直在跑。它的进度看「上次同步」。
            if (result.pushed) {
                SettingsManager.saveWebDavLastBackupTime(applicationContext, System.currentTimeMillis())
            }
            StunLogger.i(
                "WebDavBackup",
                "Auto sync(${mode.id}) OK: pulled=[${result.pulled.joinToString()}] pushed=${result.pushed}" +
                    (if (result.bootstrapped) " (bootstrapped)" else "")
            )
            Result.success()
        } catch (e: WebDavClient.WebDavException) {
            StunLogger.e("WebDavBackup", "Auto sync failed: ${e.message}")
            // 网络类错误交给 WorkManager 退避重试；配置/PIN 错误重试无意义
            if (e.statusCode in listOf(401, 403, 404)) Result.failure() else Result.retry()
        } catch (e: Exception) {
            StunLogger.e("WebDavBackup", "Auto sync failed", e)
            Result.retry()
        }
    }
}
