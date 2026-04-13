package app.fjj.stun.repo

import android.content.Context
import androidx.lifecycle.LiveData

object ProfileManager {
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
        val dao = AppDatabase.getDatabase(context).profileDao()
        dao.deleteAll()
        profiles.forEach { dao.insert(it) }
    }

    fun getSelectedProfile(context: Context): Profile {
        val id = SettingsManager.getSelectedProfileId(context)
        return if (id != null) {
            AppDatabase.getDatabase(context).profileDao().getById(id) ?: Profile()
        } else {
            getProfiles(context).firstOrNull() ?: Profile()
        }
    }

    fun updateTrafficStats(context: Context, id: String, tx: Long, rx: Long) {
        AppDatabase.getDatabase(context).profileDao().updateTrafficStats(id, tx, rx)
    }
}
