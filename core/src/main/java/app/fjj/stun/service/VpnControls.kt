package app.fjj.stun.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.core.content.ContextCompat
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState

/**
 * VPN / TProxy 的启停与通知权限控制逻辑。
 *
 * 合并背景：`car` 与 `xr` 的 `handleStartStop` / `startSelectedService` / `stopVpnService` /
 * `checkAndRequestNotificationPermission` 曾逐字相同（相似度 1.00），改动必须两边同步，很容易漂移。
 * 这里收拢为单一实现，各端只保留自己的 View 绑定与 `ActivityResult` 回调接线。
 *
 * 注：`app` 的启动/停止路径与 car/xr **语义不同**（进程保活与前台提示更多），
 * 故只共用「是否需要通知权限」这一处判定，未强行统一。
 */
object VpnControls {

    /**
     * 通知权限名，供各端的 `notificationPermissionLauncher.launch(...)` 使用。
     *
     * 集中在这里是为了让「取权限名」只出现一处：`POST_NOTIFICATIONS` 是**编译期内联的
     * String 常量**（不是字段访问），低版本读取不会崩，因此这里不需要 API 33 守卫 ——
     * 真正的可用性判定由 [needsNotificationPermission] 负责。不压制的话，
     * 每个直接引用 `Manifest.permission.POST_NOTIFICATIONS` 的调用点都会报 `InlinedApi`。
     */
    @SuppressLint("InlinedApi")
    val notificationPermission: String = Manifest.permission.POST_NOTIFICATIONS

    /** Android 13+ 且未授予 POST_NOTIFICATIONS 时为 true —— 调用方应先走权限申请再启动。 */
    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, notificationPermission
            ) != PackageManager.PERMISSION_GRANTED

    /**
     * 启停总入口：已连接 -> 停止；正在连接/重连 -> 忽略（防抖）；其余 -> 交给调用方申请通知权限后启动。
     *
     * @param onNeedPermission 需要先申请通知权限时回调（各端用自己的 launcher 处理）
     */
    fun handleStartStop(context: Context, onNeedPermission: () -> Unit) {
        when (StunRepository.vpnState.value ?: VpnState.DISCONNECTED) {
            VpnState.CONNECTED -> stop(context)
            VpnState.CONNECTING, VpnState.RECONNECTING -> Unit
            else -> onNeedPermission()
        }
    }

    /**
     * 按当前服务模式启动：TProxy 直接起前台服务；VPN 先 `VpnService.prepare`，
     * 需要用户授权时回调 [onPrepareRequired]，否则直接起 `MyVpnService`。
     */
    fun start(context: Context, onPrepareRequired: (Intent) -> Unit) {
        if (SettingsManager.getServiceMode(context) == SettingsManager.SERVICE_MODE_TPROXY) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MyTransparentProxyService::class.java)
                    .apply { action = MyTransparentProxyService.ACTION_START }
            )
            return
        }
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            onPrepareRequired(prepare)
        } else {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MyVpnService::class.java).apply { action = MyVpnService.ACTION_START }
            )
        }
    }

    /** 停止当前服务模式对应的服务。 */
    fun stop(context: Context) {
        val intent = if (SettingsManager.getServiceMode(context) == SettingsManager.SERVICE_MODE_TPROXY) {
            Intent(context, MyTransparentProxyService::class.java)
                .apply { action = MyTransparentProxyService.ACTION_STOP }
        } else {
            Intent(context, MyVpnService::class.java).apply { action = MyVpnService.ACTION_STOP }
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
