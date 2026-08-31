package app.fjj.stun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProfileEditViewModel(application: Application) : AndroidViewModel(application) {

    private val _profile = MutableLiveData<Profile>()
    val profile: LiveData<Profile> = _profile

    private val _saveResult = MutableLiveData<Boolean>()
    val saveResult: LiveData<Boolean> = _saveResult

    fun loadProfile(profileId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val loadedProfile = if (profileId != null) {
                ProfileManager.getProfileById(context, profileId) ?: createDefaultProfile(context)
            } else {
                createDefaultProfile(context)
            }
            _profile.postValue(loadedProfile)
        }
    }

    private fun createDefaultProfile(context: android.content.Context): Profile {
        return Profile().apply {
            name = context.getString(app.fjj.stun.core.R.string.node_default_name)
            remoteDns = SettingsManager.getRemoteDnsServer(context)
            localDns = SettingsManager.getLocalDnsServer(context)
            udpgwVersion = SettingsManager.getUdpgwVersion(context)
            udpgwAddr = SettingsManager.getUdpgwAddr(context)
            geositeDirect = SettingsManager.getGeositeDirect(context)
            geoipDirect = SettingsManager.getGeoipDirect(context)
        }
    }

    fun saveProfile(profile: Profile, isEdit: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (isEdit) {
                ProfileManager.updateProfile(context, profile)
            } else {
                ProfileManager.addProfile(context, profile)
            }
            _saveResult.postValue(true)
        }
    }
}
