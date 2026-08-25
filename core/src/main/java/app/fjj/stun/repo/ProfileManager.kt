package app.fjj.stun.repo

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import app.fjj.stun.util.KeystoreUtils

object ProfileManager {
    fun getProfilesLiveData(context: Context): LiveData<List<Profile>> {
        return AppDatabase.getDatabase(context).profileDao().getAll().map { list ->
            list.map { decryptProfile(it) }
        }
    }

    fun getProfiles(context: Context): List<Profile> {
        return AppDatabase.getDatabase(context).profileDao().getAllStatic().map { decryptProfile(it) }
    }

    fun getProfileById(context: Context, id: String): Profile? {
        return AppDatabase.getDatabase(context).profileDao().getById(id)?.let { decryptProfile(it) }
    }

    fun addProfile(context: Context, profile: Profile) {
        AppDatabase.getDatabase(context).profileDao().insert(encryptProfile(profile))
    }

    fun updateProfile(context: Context, profile: Profile) {
        AppDatabase.getDatabase(context).profileDao().update(encryptProfile(profile))
    }

    fun deleteProfile(context: Context, profile: Profile) {
        AppDatabase.getDatabase(context).profileDao().delete(profile)
    }

    fun saveProfiles(context: Context, profiles: List<Profile>) {
        val dao = AppDatabase.getDatabase(context).profileDao()
        dao.deleteAll()
        profiles.forEach { dao.insert(encryptProfile(it)) }
    }

    fun getSelectedProfile(context: Context): Profile {
        val id = SettingsManager.getSelectedProfileId(context)
        val profile = if (id != null) {
            AppDatabase.getDatabase(context).profileDao().getById(id) ?: Profile()
        } else {
            AppDatabase.getDatabase(context).profileDao().getAllStatic().firstOrNull() ?: Profile()
        }
        return decryptProfile(profile)
    }

    fun updateTrafficStats(context: Context, id: String, tx: Long, rx: Long) {
        AppDatabase.getDatabase(context).profileDao().updateTrafficStats(id, tx, rx)
    }

    fun addTrafficStats(context: Context, id: String, deltaTx: Long, deltaRx: Long) {
        AppDatabase.getDatabase(context).profileDao().addTrafficStats(id, deltaTx, deltaRx)
    }

    fun updateProfileIndices(context: Context, profiles: List<Profile>) {
        val dao = AppDatabase.getDatabase(context).profileDao()
        profiles.forEachIndexed { index, profile ->
            if (profile.sortIndex != index) {
                val encProfile = encryptProfile(profile).copy(sortIndex = index)
                dao.update(encProfile)
            }
        }
    }

    fun encryptProfile(profile: Profile): Profile {
        return profile.copy(
            pass = KeystoreUtils.encrypt(profile.pass),
            privateKey = KeystoreUtils.encrypt(profile.privateKey),
            keyPass = KeystoreUtils.encrypt(profile.keyPass),
            proxyAuthPass = KeystoreUtils.encrypt(profile.proxyAuthPass),
            proxyAuthToken = KeystoreUtils.encrypt(profile.proxyAuthToken)
        )
    }

    fun decryptProfile(profile: Profile): Profile {
        return profile.copy(
            pass = KeystoreUtils.decrypt(profile.pass),
            privateKey = KeystoreUtils.decrypt(profile.privateKey),
            keyPass = KeystoreUtils.decrypt(profile.keyPass),
            proxyAuthPass = KeystoreUtils.decrypt(profile.proxyAuthPass),
            proxyAuthToken = KeystoreUtils.decrypt(profile.proxyAuthToken)
        )
    }

    private const val PREFS_NAME = "stun_profile_manager"
    private const val KEY_ENCRYPTION_MIGRATED = "encryption_migrated_v1"

    fun migratePlaintextProfiles(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        // 已迁移过则直接跳过，避免每次启动都全表扫描
        if (prefs.getBoolean(KEY_ENCRYPTION_MIGRATED, false)) return

        val dao = AppDatabase.getDatabase(context).profileDao()
        val rawProfiles = dao.getAllStatic()
        var migratedCount = 0

        rawProfiles.forEach { p ->
            val needsMigration = (p.pass.isNotEmpty() && !p.pass.startsWith("ENC:")) ||
                (p.privateKey.isNotEmpty() && !p.privateKey.startsWith("ENC:")) ||
                (p.keyPass.isNotEmpty() && !p.keyPass.startsWith("ENC:")) ||
                (p.proxyAuthPass.isNotEmpty() && !p.proxyAuthPass.startsWith("ENC:")) ||
                (p.proxyAuthToken.isNotEmpty() && !p.proxyAuthToken.startsWith("ENC:"))

            if (needsMigration) {
                val decrypted = decryptProfile(p)
                dao.update(encryptProfile(decrypted))
                migratedCount++
            }
        }

        // 无论有无需要迁移的数据，完成后都写 flag，下次直接跳过
        prefs.edit().putBoolean(KEY_ENCRYPTION_MIGRATED, true).apply()
        if (migratedCount > 0) {
            StunLogger.i("ProfileManager", "Migrated $migratedCount plaintext profiles to encrypted format")
        }
    }
}
