package app.fjj.stun.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.SubscriptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val profilesLiveData: LiveData<List<Profile>> = ProfileManager.getProfilesLiveData(application)

    private val _selectedProfile = MutableLiveData<Profile?>()
    val selectedProfile: LiveData<Profile?> = _selectedProfile

    init {
        // 节点卡片的"来源订阅"徽标要按 subId 查订阅名。名字快照是懒加载的，若等列表第一次
        // bind 时才去查库，首帧就会多一次查库（而 bind 在主线程，Room 会直接拒绝同步查询，
        // 那里的实现因此改成"缓存没热就只返回空串"）。这里提前在 IO 上把它焐热。
        viewModelScope.launch(Dispatchers.IO) {
            SubscriptionManager.warmSubscriptionNameCache(getApplication())
        }
    }

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
