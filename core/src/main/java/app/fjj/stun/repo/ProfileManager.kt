package app.fjj.stun.repo

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import app.fjj.stun.util.KeystoreUtils

/**
 * 一个凭据字段的读/写器。[name] 必须与 [ProfileSecrets.SECRET_FIELDS] 里的名字一致
 * —— 两侧一致性由 `ProfileSecretFieldsTest` 钉死。
 */
internal class SecretIoField(
    val name: String,
    private val read: (Profile) -> String,
    private val write: (Profile, String) -> Profile
) {
    fun readFrom(profile: Profile): String = read(profile)
    fun writeTo(profile: Profile, value: String): Profile = write(profile, value)
}

/**
 * 落盘要加密的凭据字段 —— **静态加密的单一事实来源**。
 *
 * ## 为什么要有这张表
 * 从前 `encryptProfile` / `decryptProfile` 各自手写一份具名参数清单，加字段时极易只改一处
 * （症状是「写进去读不出来」或「读出来还是密文」，只在真机暴露）。更糟的是它还有
 * **第二重漂移**：出口掩码的清单在 [ProfileSecrets.SECRET_FIELDS]，与这里必须同名同量。
 * 正是这两个清单不同步，让 `icmpCustomPsk` / `udpCustomPsk` / `dnsTunnelPsk` / `kcpPassword`
 * 长期处在「对外显示 `*****`（看起来安全）、SQLite 里却是明文」的半安全状态。
 *
 * 现在凭据字段只有这一份清单，`ProfileSecretFieldsTest` 会断言它与 `SECRET_FIELDS` 相等。
 */
internal val SECRET_IO_FIELDS: List<SecretIoField> = listOf(
    SecretIoField("pass", { it.pass }, { p, v -> p.copy(pass = v) }),
    SecretIoField("privateKey", { it.privateKey }, { p, v -> p.copy(privateKey = v) }),
    SecretIoField("keyPass", { it.keyPass }, { p, v -> p.copy(keyPass = v) }),
    SecretIoField("proxyAuthToken", { it.proxyAuthToken }, { p, v -> p.copy(proxyAuthToken = v) }),
    SecretIoField("proxyAuthPass", { it.proxyAuthPass }, { p, v -> p.copy(proxyAuthPass = v) }),
    SecretIoField("icmpCustomPsk", { it.icmpCustomPsk }, { p, v -> p.copy(icmpCustomPsk = v) }),
    SecretIoField("udpCustomPsk", { it.udpCustomPsk }, { p, v -> p.copy(udpCustomPsk = v) }),
    SecretIoField("dnsTunnelPsk", { it.dnsTunnelPsk }, { p, v -> p.copy(dnsTunnelPsk = v) }),
    SecretIoField("kcpPassword", { it.kcpPassword }, { p, v -> p.copy(kcpPassword = v) })
)

/**
 * 把 [profile] 的每个凭据字段过一遍 [transform]，其余字段原样保留。
 *
 * 抽成独立函数而不是内联进 `encryptProfile`，是为了让「到底哪些字段进了加解密」这件事能在
 * **纯 JVM 单测**里用假 transform 覆盖 —— 真 crypto 需要 Android Keystore，Robolectric 跑不起来。
 */
internal fun mapSecretFields(profile: Profile, transform: (String) -> String): Profile {
    var result = profile
    for (field in SECRET_IO_FIELDS) {
        result = field.writeTo(result, transform(field.readFrom(result)))
    }
    return result
}

/**
 * 迁移期判定：这个值还需要加密吗？
 *
 * ⚠️ **只对「加密引入之前就存在」的值成立** —— 那时取到带 `ENC:` 前缀的值只可能是密文。
 * 正常写路径绝不能用它：用户完全可以把密码本身设成 `ENC:` 开头，那样短路会把明文原样存下去，
 * 之后 `decrypt` 解不开、静默返回空串，密码就没了（见 [ProfileManager.encryptProfile]）。
 */
internal fun needsEncryption(value: String): Boolean =
    value.isNotEmpty() && !value.startsWith(KeystoreUtils.ENC_PREFIX)

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

    fun markConnected(context: Context, id: String) {
        if (id.isBlank()) return
        try {
            AppDatabase.getDatabase(context).profileDao().updateLastConnectedAt(id, System.currentTimeMillis())
        } catch (_: Exception) {}
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

    /**
     * 写库前加密。**无条件加密**，不做「已经是 `ENC:` 就跳过」的短路 ——
     * 用户完全可能把密码本身设成 `ENC:` 开头，短路会把明文原样存下去，
     * 之后 `decrypt` 解不开、静默返回空串，密码就没了。
     */
    fun encryptProfile(profile: Profile): Profile =
        mapSecretFields(profile) { KeystoreUtils.encrypt(it) }

    fun decryptProfile(profile: Profile): Profile =
        mapSecretFields(profile) { KeystoreUtils.decrypt(it) }

    private const val PREFS_NAME = "stun_profile_manager"

    /**
     * 明文迁移的代际标记。
     *
     * v1（`encryption_migrated_v1`）只覆盖 5 个字段；v2 补上 4 个隧道凭据。
     * **不能复用 v1 的 key** —— 存量用户在 v1 时代就已置位，复用会让这 4 个字段永远不会被补齐：
     * 迁移看起来「跑过了」，实际只覆盖一半。
     */
    private const val KEY_ENCRYPTION_MIGRATED = "encryption_migrated_v2"

    /**
     * 一次性把库里的明文凭据就地转成 `ENC:` 密文。幂等（靠 `ENC:` 前缀判定）。
     *
     * 触发点在 `AppBootstrap.start`，且排在**资产就绪门之后** —— 覆盖全部 5 个 App 入口
     * （phone / tv / car / wear / xr），从前只有 phone 与 tv 的 `MainActivity` 调它，
     * 只在这些端用过的话这 4 个隧道凭据永远不会被加密。
     *
     * **逐字段判定、只碰 [needsEncryption] 为真的非空值**，不做 `decrypt → encrypt` 往返：
     * 往返会先把已经加密的字段解出来再重新加密，一旦那次 Keystore 初始化失败，`decrypt`
     * 返回空串就把好好的凭据抹掉了。只做前缀判定则失败面为零 —— 加密失败返回空串，
     * `fixed != raw` 仍成立，下次启动会重试。
     */
    fun migratePlaintextProfiles(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 已迁移过则直接跳过，避免每次启动都全表扫描
        if (prefs.getBoolean(KEY_ENCRYPTION_MIGRATED, false)) return

        val dao = AppDatabase.getDatabase(context).profileDao()
        var migratedCount = 0

        dao.getAllStatic().forEach { raw ->
            val fixed = mapSecretFields(raw) { if (needsEncryption(it)) KeystoreUtils.encrypt(it) else it }
            if (fixed != raw) {
                dao.update(fixed)
                migratedCount++
            }
        }

        // 无论有无需要迁移的数据，完成后都写 flag，下次直接跳过
        prefs.edit().putBoolean(KEY_ENCRYPTION_MIGRATED, true).apply()
        if (migratedCount > 0) {
            StunLogger.i("ProfileManager", "Migrated $migratedCount profiles to encrypted format (v2)")
        }
    }
}
