package app.fjj.stun.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.fjj.stun.core.R
import app.fjj.stun.remote.RemoteControlHost
import app.fjj.stun.repo.StunLogger

/**
 * 远程控制的前台保活服务（TV / Car 主诉求）。
 *
 * ## 为什么需要它
 * Web 控制台、蓝牙远控、局域网同步、MCP 都是**跑在 App 进程里的 object**，不是 Service：
 * 进程被系统回收 ⇒ 四个监听面一起消失，而且没有任何组件会重建它们。
 * 前台服务把进程顶到 foreground 优先级，从根上脱离 cached 裁剪的射程；
 * [START_STICKY] 再兜一层：进程即便被杀，系统也会择机重建本服务（此时 intent 为 null），
 * 保活链路自愈。
 *
 * 与既有两条保活通道的关系（互补而非替代）：
 * - Magisk service.d：管「重启后拉起」+ 30s 看门狗（需 root）；
 * - Shizuku 保活：管「进程死后唤回」+ 电池白名单反复断言（Doze 豁免，15min 周期）；
 * - 本服务：管「活着的时候不被杀」—— 覆盖最常见的主场景，且无通知栏之外的感知成本。
 *
 * 本服务是**进程保活的地基**，不负责判定该拉回哪些监听面 —— 那交给 [RemoteControlHost]，
 * 它同时被本服务与 Shizuku 周期任务调用，判定逻辑只此一份。
 *
 * ⚠️ Android 12+ 对「后台启动前台服务」有限制：进程被保活任务唤回时 startForegroundService
 * 可能被拒（ForegroundServiceStartNotAllowedException）—— [start] 已 try/catch 降级，
 * 最坏维持现状（等下个周期再试）；Activity 在前台时的启动不受影响。
 */
class WebConsoleKeepAliveService : Service() {

    companion object {
        private const val TAG = "ConsoleKeepAlive"
        private const val CHANNEL_ID = "webui_keepalive"
        // 1001=VPN / 3004=TProxy，错开避免互相覆盖
        private const val NOTIFICATION_ID = 3005

        /** 尽力启动；后台启动限制（Android 12+）等场景降级为仅日志，不拖垮调用方。 */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, WebConsoleKeepAliveService::class.java))
            } catch (e: Exception) {
                StunLogger.w(TAG, "start keep-alive service failed: ${e.message}")
            }
        }

        /**
         * 停掉本服务（WebUI「远程控制常驻」开关关掉时）。
         *
         * 从服务自身线程调用时走 [stopSelf]：`stopService` 走的是框架查表再投递，
         * 在自己的销毁流程里再发一次外部 stop 反而可能截断 [onDestroy]。
         */
        fun stop(context: Context) {
            if (context is WebConsoleKeepAliveService) {
                context.stopSelf()
                return
            }
            try {
                context.stopService(Intent(context, WebConsoleKeepAliveService::class.java))
            } catch (e: Exception) {
                StunLogger.w(TAG, "stop keep-alive service failed: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 记录停用原因：用户关掉「远程控制常驻」开关走 stopSelf，系统裁进程走的是进程死亡。
        // 排查"远控突然连不上"时靠这行日志区分是被关闭还是被杀。
        StunLogger.i(TAG, "Keep-alive service destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 的时限窗口内必须同步进前台，不能经过协程调度
        startForegroundNow()
        // 服务存在的意义就是「进程活着 ⇒ 远程控制可连」：把掉了的监听面统一补回来（幂等）。
        StunLogger.i(TAG, "Keep-alive service up → ${RemoteControlHost.restoreAll(this)}")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_console_keepalive),
            NotificationManager.IMPORTANCE_LOW
        )
        nm?.createNotificationChannel(channel)
    }

    private fun startForegroundNow() {
        // 通知构建失败绝不能把服务（连同整个 App）带崩 —— startForeground 必须被调用到，
        // 否则会换成「startForegroundService 后 5 秒内未进前台」的另一种崩法；所以最坏
        // 情况退化为最小通知。
        val notification = try {
            buildNotification()
        } catch (e: Exception) {
            StunLogger.e(TAG, "build keep-alive notification failed; fallback to bare", e)
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_console_keepalive))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * 内容点击 = 打开 App 主界面。
     *
     * ⚠️ TV 端 launcher 挂的是 `LEANBACK_LAUNCHER`（没有 `LAUNCHER` category），而
     * [PackageManager.getLaunchIntentForPackage] 只找 LAUNCHER —— 在 TV 上必然返回 **null**；
     * 把 null 丢进 [PendingIntent.getActivity] 会 NPE 并让整个 App 闪退（2026-10 线上事故）。
     * 所以 LAUNCHER 探不到时再探 LEANBACK_LAUNCHER，仍探不到就退化为无点击动作的通知。
     */
    private fun buildNotification(): android.app.Notification {
        val mainIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
            ).firstOrNull()?.let { info ->
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
            }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_console_keepalive))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
        if (mainIntent != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this, 0, mainIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        return builder.build()
    }
}
