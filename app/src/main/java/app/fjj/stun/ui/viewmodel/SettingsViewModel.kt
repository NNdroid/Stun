package app.fjj.stun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.fjj.stun.repo.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class SettingsState(
    val logLevel: String = SettingsManager.DEFAULT_LOG_LEVEL,
    val remoteDns: String = SettingsManager.DEFAULT_REMOTE_DNS_SERVER,
    val localDns: String = SettingsManager.DEFAULT_LOCAL_DNS_SERVER,
    val udpgwVersion: String = SettingsManager.DEFAULT_UDPGW_VERSION,
    val udpgwAddr: String = SettingsManager.DEFAULT_UDPGW_ADDR,
    val filterMode: Int = 0,
    val filterApps: String = "",
    val serviceMode: Int = SettingsManager.SERVICE_MODE_VPN,
    val language: String = "auto",
    val geositeUrl: String = SettingsManager.DEFAULT_GEOSITE_URL,
    val geoipUrl: String = SettingsManager.DEFAULT_GEOIP_URL,
    val updateInterval: Long = SettingsManager.DEFAULT_UPDATE_INTERVAL,
    val geositeDirect: String = SettingsManager.DEFAULT_GEOSITE_DIRECT_FLAGS,
    val geoipDirect: String = SettingsManager.DEFAULT_GEOIP_DIRECT_FLAGS,
    val lastUpdateTime: Long = 0L
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val _settingsState = MutableLiveData<SettingsState>()
    val settingsState: LiveData<SettingsState> = _settingsState

    fun loadSettings() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val state = SettingsState(
                logLevel = SettingsManager.getLogLevel(context),
                remoteDns = SettingsManager.getRemoteDnsServer(context),
                localDns = SettingsManager.getLocalDnsServer(context),
                udpgwVersion = SettingsManager.getUdpgwVersion(context),
                udpgwAddr = SettingsManager.getUdpgwAddr(context),
                filterMode = SettingsManager.getFilterMode(context),
                filterApps = SettingsManager.getFilterApps(context),
                serviceMode = SettingsManager.getServiceMode(context),
                language = SettingsManager.getLanguage(context),
                geositeUrl = SettingsManager.getGeositeUrl(context),
                geoipUrl = SettingsManager.getGeoipUrl(context),
                updateInterval = SettingsManager.getUpdateInterval(context),
                geositeDirect = SettingsManager.getGeositeDirect(context),
                geoipDirect = SettingsManager.getGeoipDirect(context),
                lastUpdateTime = SettingsManager.getLastUpdateTime(context)
            )
            _settingsState.postValue(state)
        }
    }

    fun saveSettings(state: SettingsState) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            SettingsManager.saveServiceMode(context, state.serviceMode)
            SettingsManager.saveLogLevel(context, state.logLevel)
            SettingsManager.saveRemoteDnsServer(context, state.remoteDns)
            SettingsManager.saveLocalDnsServer(context, state.localDns)
            SettingsManager.saveUdpgwVersion(context, state.udpgwVersion)
            SettingsManager.saveUdpgwAddr(context, state.udpgwAddr)
            SettingsManager.saveGeositeUrl(context, state.geositeUrl)
            SettingsManager.saveGeoipUrl(context, state.geoipUrl)
            SettingsManager.saveUpdateInterval(context, state.updateInterval)
            SettingsManager.saveGeositeDirect(context, state.geositeDirect)
            SettingsManager.saveGeoipDirect(context, state.geoipDirect)
            SettingsManager.saveFilterMode(context, state.filterMode)
            SettingsManager.saveFilterApps(context, state.filterApps)
            // Language is handled specially in Activity for recreation
        }
    }
}
