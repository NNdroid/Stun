package app.fjj.stun.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.ui.MainActivity
import app.fjj.stun.ui.VpnQuickActionActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DynamicShortcutManager {

    fun updateShortcuts(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 应用内语言（AppCompat per-app locale）不会作用于 Application context，
                // 快捷方式标签由 launcher 进程渲染，必须显式包一层选中语言的配置。
                val ctx = LocaleHelper.wrapContext(context)
                val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
                val isConnected = state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.RECONNECTING
                val selectedProfile = ProfileManager.getSelectedProfile(context)

                // 只维护「启动/断开」toggle：固定 id dynamic_toggle_vpn，按状态显示连接或断开。
                // 切换节点 / 远程控制 TV 与状态无关，由静态 shortcuts.xml 提供，
                // 这里重复注册会造成长按菜单同义项成对出现。
                val shortcuts = mutableListOf<ShortcutInfoCompat>()
                if (isConnected) {
                    val stopIntent = Intent(context, VpnQuickActionActivity::class.java).apply {
                        action = MainActivity.ACTION_SHORTCUT_STOP_VPN
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val stopShortcut = ShortcutInfoCompat.Builder(context, "dynamic_toggle_vpn")
                        .setShortLabel(ctx.getString(CoreR.string.shortcut_stop_vpn))
                        .setLongLabel(ctx.getString(CoreR.string.shortcut_stop_vpn))
                        .setIcon(IconCompat.createWithResource(context, CoreR.drawable.ic_shortcut_disconnect))
                        .setIntent(stopIntent)
                        .setRank(0)
                        .build()
                    shortcuts.add(stopShortcut)
                } else {
                    val startIntent = Intent(context, VpnQuickActionActivity::class.java).apply {
                        action = MainActivity.ACTION_SHORTCUT_START_VPN
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val startLabel = if (selectedProfile.name.isNotBlank()) {
                        "${ctx.getString(CoreR.string.shortcut_start_vpn)} · ${selectedProfile.name}"
                    } else {
                        ctx.getString(CoreR.string.shortcut_start_vpn)
                    }
                    val startShortcut = ShortcutInfoCompat.Builder(context, "dynamic_toggle_vpn")
                        .setShortLabel(ctx.getString(CoreR.string.shortcut_start_vpn))
                        .setLongLabel(startLabel)
                        .setIcon(IconCompat.createWithResource(context, CoreR.drawable.ic_shortcut_connect))
                        .setIntent(startIntent)
                        .setRank(0)
                        .build()
                    shortcuts.add(startShortcut)
                }

                ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
            } catch (_: Throwable) {}
        }
    }
}
