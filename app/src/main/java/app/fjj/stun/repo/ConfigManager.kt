package app.fjj.stun.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData

object ConfigManager {
    private const val PREF_NAME = "vpn_config"
    private const val KEY_SELECTED_PROFILE_ID = "selected_profile_id"
    private const val KEY_LOG_LEVEL = "log_level"

    const val DEFAULT_SSH_ADDR = "198.98.61.214:666"
    const val DEFAULT_USER = "opentunnel.net-test007"
    const val DEFAULT_PASS = "521qqwq"
    const val DEFAULT_TUNNEL_TYPE = "tls"
    const val DEFAULT_PROXY_ADDR = "198.98.61.214:443"
    const val DEFAULT_CUSTOM_HOST = "microsoft.com"
    const val DEFAULT_LOG_LEVEL = "V"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getProfilesLiveData(context: Context): LiveData<List<Profile>> {
        return AppDatabase.getDatabase(context).profileDao().getAll()
    }

    fun getProfiles(context: Context): List<Profile> {
        return AppDatabase.getDatabase(context).profileDao().getAllStatic()
    }

    fun addProfile(context: Context, profile: Profile) {
        AppDatabase.getDatabase(context).profileDao().insert(profile)
    }

    fun updateProfile(context: Context, profile: Profile) {
        AppDatabase.getDatabase(context).profileDao().update(profile)
    }

    fun deleteProfile(context: Context, profile: Profile) {
        AppDatabase.getDatabase(context).profileDao().delete(profile)
    }

    fun saveProfiles(context: Context, profiles: List<Profile>) {
        // Since we are using Room, we usually save individually, 
        // but for compatibility with your existing UI logic:
        val dao = AppDatabase.getDatabase(context).profileDao()
        dao.deleteAll()
        profiles.forEach { dao.insert(it) }
    }

    fun getSelectedProfileId(context: Context): String? {
        val id = getPrefs(context).getString(KEY_SELECTED_PROFILE_ID, null)
        return id ?: getProfiles(context).firstOrNull()?.id
    }

    fun setSelectedProfileId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_PROFILE_ID, id).apply()
    }

    fun getSelectedProfile(context: Context): Profile {
        val id = getSelectedProfileId(context)
        return if (id != null) {
            AppDatabase.getDatabase(context).profileDao().getById(id) ?: Profile()
        } else {
            Profile()
        }
    }

    fun getLogLevel(context: Context) = getPrefs(context).getString(KEY_LOG_LEVEL, DEFAULT_LOG_LEVEL) ?: DEFAULT_LOG_LEVEL
    
    fun saveLogLevel(context: Context, level: String) {
        getPrefs(context).edit().putString(KEY_LOG_LEVEL, level).apply()
    }
}
