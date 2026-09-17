package app.fjj.stun.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.ui.MainActivity
import app.fjj.stun.ui.VpnQuickActionActivity

@RequiresApi(Build.VERSION_CODES.N)
class StunTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val isActive = state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.RECONNECTING
        if (isActive) {
            val serviceMode = SettingsManager.getServiceMode(this)
            val intent = if (serviceMode == SettingsManager.SERVICE_MODE_TPROXY) {
                Intent(this, MyTransparentProxyService::class.java).apply {
                    action = MyTransparentProxyService.ACTION_STOP
                }
            } else {
                Intent(this, MyVpnService::class.java).apply {
                    action = MyVpnService.ACTION_STOP
                }
            }
            startService(intent)
        } else {
            // Launch the shared connection flow so profile validation, VPN consent,
            // notification permission, and root checks are never bypassed.
            launchConnectionFlow()
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        // TileService 不是 AppCompat 组件，getString 只反映系统语言；
        // 应用内设置了语言时要显式包一层。
        val ctx = app.fjj.stun.util.LocaleHelper.wrapContext(this)
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        val isActive = state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.RECONNECTING
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, CoreR.drawable.ic_fox_logo)
        tile.label = ctx.getString(CoreR.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                VpnState.CONNECTED -> ctx.getString(CoreR.string.status_connected)
                VpnState.CONNECTING -> ctx.getString(CoreR.string.main_connecting)
                VpnState.RECONNECTING -> ctx.getString(CoreR.string.main_reconnecting)
                else -> ctx.getString(CoreR.string.status_disconnected)
            }
        }
        tile.updateTile()
    }

    private fun launchConnectionFlow() {
        // 透明执行页：能静默完成就不露出应用界面；需要授权/报错时由其内部
        // 决定弹系统授权框或转交 MainActivity。
        val intent = Intent(this, VpnQuickActionActivity::class.java).apply {
            action = MainActivity.ACTION_SHORTCUT_START_VPN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        // PendingIntent 重载自 UPSIDE_DOWN_CAKE(34) 起可用；34- 走旧重载，
        // lint StartActivityAndCollapseDeprecated 按未使用的弃用 API 报 Error，
        // 但 minSdk 28 上没有替代 API —— 按 lint id 显式抑制（issue id 才能压住，DEPRECATION 压不住）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                10,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("StartActivityAndCollapseDeprecated", "DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

}
