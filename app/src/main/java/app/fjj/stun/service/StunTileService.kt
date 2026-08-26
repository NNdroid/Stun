package app.fjj.stun.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import kotlinx.coroutines.*

@RequiresApi(Build.VERSION_CODES.N)
class StunTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = StunRepository.vpnState.value == VpnState.CONNECTED
        if (isRunning) {
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
            serviceScope.launch {
                val profiles = withContext(Dispatchers.IO) {
                    ProfileManager.getProfiles(this@StunTileService)
                }
                if (profiles.isNotEmpty()) {
                    val selectedId = SettingsManager.getSelectedProfileId(this@StunTileService)
                    val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
                    
                    val serviceMode = SettingsManager.getServiceMode(this@StunTileService)
                    val intent = if (serviceMode == SettingsManager.SERVICE_MODE_TPROXY) {
                        Intent(this@StunTileService, MyTransparentProxyService::class.java).apply {
                            action = MyTransparentProxyService.ACTION_START
                            putExtra("EXTRA_PROFILE_ID", profile.id)
                        }
                    } else {
                        Intent(this@StunTileService, MyVpnService::class.java).apply {
                            action = MyVpnService.ACTION_START
                            putExtra("EXTRA_PROFILE_ID", profile.id)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = StunRepository.vpnState.value == VpnState.CONNECTED
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(CoreR.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (isRunning) getString(CoreR.string.status_connected) else getString(CoreR.string.status_disconnected)
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
