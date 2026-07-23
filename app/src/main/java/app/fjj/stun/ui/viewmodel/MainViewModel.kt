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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val profilesLiveData: LiveData<List<Profile>> = ProfileManager.getProfilesLiveData(application)

    private val _selectedProfile = MutableLiveData<Profile?>()
    val selectedProfile: LiveData<Profile?> = _selectedProfile

    fun loadSelectedProfile(callback: ((Profile) -> Unit)? = null) {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                val id = SettingsManager.getSelectedProfileId(getApplication())
                if (id != null) {
                    ProfileManager.getProfileById(getApplication(), id) ?: Profile()
                } else {
                    ProfileManager.getProfiles(getApplication()).firstOrNull() ?: Profile()
                }
            }
            _selectedProfile.value = profile
            callback?.invoke(profile)
        }
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            ProfileManager.deleteProfile(getApplication(), profile)
        }
    }

    fun addProfile(profile: Profile) {
        viewModelScope.launch(Dispatchers.IO) {
            ProfileManager.addProfile(getApplication(), profile)
        }
    }
}
