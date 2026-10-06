package app.fjj.stun.backup

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * WebDAV **备份前缀**与**同步范围**的契约回归。
 *
 * 两者都是"看着无害、错了才致命"的那种配置：
 *
 * - 前缀会原样拼进 WebDAV 路径段，而这个值来自 WebUI / MCP 这些**不受信任的入口** ——
 *   一旦 `sanitizePrefix` 漏掉 `/` `..`，一次备份就能写到备份根目录之外，
 *   或者让某个前缀的实例去裁剪别人的备份。
 * - 同步范围要区分"键不存在"（老用户升级，必须回落全量）与"显式空集"
 *   （用户主动只要节点）。这两者都读成空集的话，老用户升级后分区会静默地不再备份。
 *
 * 本类必须挂 Robolectric：`Config.activeSections` 会碰到 [BackupSections] 注册表，
 * 分区实现类间接牵到 [SettingsManager] 与日志初始化（`StunLogger` object 里就有
 * `"#9E9E9E".toColorInt()`，裸 JVM 下是 `ExceptionInInitializerError`）。
 * [RuntimeEnvironment.getApplication] 同时给出可用的 SharedPreferences，
 * 才能验证 `contains()` 那条判据在真实持久化实现上的行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebDavPrefixSelectionTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** `yyyy-MM-dd HH:mm` 的形状 —— 用来确认时间真的被解析过了，而不是原样回显。 */
    private companion object {
        val DISPLAY_SHAPE = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")
    }

    // ── 前缀归一化 ──

    @Test
    fun prefixAllowsLettersDigitsAndTheThreeSafeSeparators() {
        assertEquals("ht2", WebDavBackupManager.sanitizePrefix("ht2"))
        assertEquals("a_b-c.d", WebDavBackupManager.sanitizePrefix("a_b-c.d"))
        assertEquals("A1", WebDavBackupManager.sanitizePrefix("A1"))
    }

    @Test
    fun prefixDropsAnythingThatCouldEscapeTheBackupRoot() {
        // `.` 是允许的（合法的网盘前缀里常见），所以只剩 `/` `\` 和控制符被丢掉
        assertEquals("ht2..etcx", WebDavBackupManager.sanitizePrefix("ht2/../etc\n\u0000x"))
        assertEquals("abc", WebDavBackupManager.sanitizePrefix("a/b\\c"))
        assertEquals("abcd.ef", WebDavBackupManager.sanitizePrefix("ab cd\t.ef"))
        // 空输入与非字符串都不能抛：WebUI/MCP 会把缺字段变成 null 传进来
        assertEquals("", WebDavBackupManager.sanitizePrefix(null))
        assertEquals("", WebDavBackupManager.sanitizePrefix(""))
    }

    @Test
    fun prefixStripsTrailingSeparatorsSoTheDirNameDoesNotDoubleUnderscore() {
        assertEquals("ht2", WebDavBackupManager.sanitizePrefix("ht2_"))
        assertEquals("ht2", WebDavBackupManager.sanitizePrefix("ht2."))
        assertEquals("ht2", WebDavBackupManager.sanitizePrefix("ht2-"))
        assertEquals("a", WebDavBackupManager.sanitizePrefix("a-_."))
        // 全是分隔符 → 彻底清空，回落无前缀
        assertEquals("", WebDavBackupManager.sanitizePrefix("...___---"))
    }

    @Test
    fun prefixStripsLeadingDotSoTheDirIsNotHiddenOnUnixServers() {
        assertEquals("ht2", WebDavBackupManager.sanitizePrefix(".ht2"))
        assertEquals("a", WebDavBackupManager.sanitizePrefix("..a"))
        assertEquals("ht2", WebDavBackupManager.sanitizePrefix("...ht2"))
    }

    @Test
    fun prefixIsCappedAtTwentyFourChars() {
        // 截断而不是报错：第 25 个字符直接不进来
        assertEquals("a".repeat(24), WebDavBackupManager.sanitizePrefix("a".repeat(30)))
        assertEquals("a".repeat(24), WebDavBackupManager.sanitizePrefix("a".repeat(24) + "b"))
    }

    // ── 目录名校验：前缀即隔离边界 ──

    @Test
    fun unprefixedStampOnlyMatchesTheEmptyPrefix() {
        assertTrue(WebDavBackupManager.isBackupDirName("20261006-173300", ""))
        assertFalse(WebDavBackupManager.isBackupDirName("20261006-173300", "ht2"))
    }

    @Test
    fun prefixedStampOnlyMatchesItsOwnPrefix() {
        assertTrue(WebDavBackupManager.isBackupDirName("ht2_20261006-173300", "ht2"))
        // 换一套前缀 → 不是本套备份集，列举/裁剪都不会碰它
        assertFalse(WebDavBackupManager.isBackupDirName("ht2_20261006-173300", ""))
        assertFalse(WebDavBackupManager.isBackupDirName("ht2_20261006-173300", "other"))
        assertFalse(WebDavBackupManager.isBackupDirName("other_20261006-173300", "ht2"))
    }

    @Test
    fun prefixCannotMatchAcrossTheUnderscoreBoundary() {
        // "ht2_" 绝不能被前缀 "ht" 吃掉：否则两套前缀会互相裁剪
        assertFalse(WebDavBackupManager.isBackupDirName("ht2_20261006-173300", "ht"))
        assertFalse(WebDavBackupManager.isBackupDirName("ht2_20261006-173300", "ht2_"))
    }

    @Test
    fun restoreValidationStillBlocksPathTraversalAndBogusNames() {
        for (bad in listOf(
            "../etc/passwd",
            "20261006-173300/../../x",
            ".hidden",
            "20261006-1733",
            "20261006-1733001",
            "ht2_20261006-173300/extra",
            "",
        )) {
            assertFalse("should reject $bad", WebDavBackupManager.isBackupDirName(bad, ""))
            assertFalse("should reject $bad", WebDavBackupManager.isBackupDirName(bad, "ht2"))
        }
    }

    // ── 展示：前缀不该干扰时间格式化 ──

    @Test
    fun displayFormattingIsPrefixAgnostic() {
        // 只比形状不比具体日期：实现按**本机时区**渲染，断言绝对字符串会把测试绑死在 CI 的时区上
        val plain = WebDavBackupManager.formatDirForDisplay("20261006-173300")
        val prefixed = WebDavBackupManager.formatDirForDisplay("ht2_20261006-173300")
        assertEquals(plain, prefixed)
        assertTrue("should be a local-time rendering, got $plain", DISPLAY_SHAPE.matches(plain))
    }

    @Test
    fun displayFormattingLeavesUnparseableNamesAlone() {
        assertEquals("whatever", WebDavBackupManager.formatDirForDisplay("whatever"))
        assertEquals("ht2", WebDavBackupManager.formatDirForDisplay("ht2"))
    }

    // ── Config：范围语义 ──

    @Test
    fun omittingSectionsFallsBackToEveryRegisteredSection() {
        val all = BackupSections.all.map { it.id }.toSet()
        assertEquals(all, WebDavBackupManager.Config("u", "u", "p", "p").activeSections)
        assertEquals(all, SettingsManager.allWebDavSectionIds().toSet())
    }

    @Test
    fun explicitEmptySelectionMeansProfilesOnlyAndDoesNotFallBack() {
        val config = WebDavBackupManager.Config(
            url = "u", user = "u", pass = "p", pin = "p", sections = emptySet()
        )
        assertTrue(config.activeSections.isEmpty())
    }

    @Test
    fun partialSelectionPassesThroughUnchanged() {
        val config = WebDavBackupManager.Config(
            url = "u", user = "u", pass = "p", pin = "p",
            sections = setOf("subscription")
        )
        assertEquals(setOf("subscription"), config.activeSections)
    }

    @Test
    fun cleanPrefixNormalisesOnReadAsWell() {
        val config = WebDavBackupManager.Config(
            url = "u", user = "u", pass = "p", pin = "p", prefix = "ht2_/.."
        )
        assertEquals("ht2", config.cleanPrefix)
    }

    // ── 持久化：「键不存在」与「显式空集」必须可区分 ──

    /**
     * 整套改动里最容易写错、也最难从症状上反推的一条。
     *
     * AOSP 的 `putStringSet(key, emptySet())` 会直接 `remove(key)`（Javadoc 明写
     * "If the set is empty the value is removed"），所以用 StringSet 存"没选任何分区"
     * 等于没存；再读回来时 `contains()` 是 false，回落成全量 —— 用户取消三类分区只留节点，
     * 重启一次应用就悄悄变成全量备份，而且全程没有任何异常。
     * 这里钉死的就是"空选择写进去还读得回来"。
     */
    @Test
    fun emptySelectionSurvivesAWriteReadRoundTripAsEmpty() {
        SettingsManager.saveWebDavSyncSections(context, emptySet())
        assertTrue(SettingsManager.getWebDavSyncSections(context).isEmpty())
    }

    @Test
    fun absentSelectionKeyFallsBackToAllSections() {
        // 新装的库、以及从未写过这个键的老版本：必须回落全量，不能当成"全关"
        assertTrue(SettingsManager.getWebDavSyncSections(context).containsAll(SettingsManager.allWebDavSectionIds()))
        assertEquals(SettingsManager.allWebDavSectionIds().toSet(), SettingsManager.getWebDavSyncSections(context))
    }

    @Test
    fun savingUnknownSectionIdsIsFilteredToRegisteredOnes() {
        SettingsManager.saveWebDavSyncSections(
            context,
            setOf("subscription", "profiles", "no_such_section", "7up")
        )
        // profiles 不是分区（恒定参与），未知串直接丢掉
        assertEquals(setOf("subscription"), SettingsManager.getWebDavSyncSections(context))
    }

    @Test
    fun selectionRoundTripsWithPartialSelection() {
        SettingsManager.saveWebDavSyncSections(context, setOf("settings", "subscription_usage"))
        assertEquals(
            setOf("settings", "subscription_usage"),
            SettingsManager.getWebDavSyncSections(context)
        )
    }

    @Test
    fun prefixRoundTripsAlreadyNormalised() {
        SettingsManager.saveWebDavPrefix(context, "ht2_/..")
        assertEquals("ht2", SettingsManager.getWebDavPrefix(context))

        // 空串是有效的"清空"：写入后必须还是空串，不能回弹成别的默认值
        SettingsManager.saveWebDavPrefix(context, "")
        assertEquals("", SettingsManager.getWebDavPrefix(context))
    }

    @Test
    fun prefixAndSelectionDescribeTheSameBackupSet() {
        // 两个新键都在共享库（可随备份迁移）：不同设备的"备份什么"必须一致，
        // 否则两台设备对同一目录集的不同看法会互相裁剪。
        SettingsManager.saveWebDavPrefix(context, "ht2")
        SettingsManager.saveWebDavSyncSections(context, setOf("settings"))
        val config = WebDavBackupManager.Config(
            url = SettingsManager.getWebDavUrl(context),
            user = "u", pass = "p", pin = "p",
            prefix = SettingsManager.getWebDavPrefix(context),
            sections = SettingsManager.getWebDavSyncSections(context)
        )
        assertEquals("ht2", config.cleanPrefix)
        assertEquals(setOf("settings"), config.activeSections)
        assertNotEquals(config.activeSections, SettingsManager.allWebDavSectionIds().toSet())
    }

    // ── 回归：同步范围是设备态，不得随设置快照走 ──

    /** 「可迁移库」里那份 stun_settings 的原始句柄（PREF_NAME 是私有常量，测试里按名打开）。 */
    private fun legacyPrefs() = context.getSharedPreferences("stun_settings", Context.MODE_PRIVATE)

    /**
     * 「同步范围」曾经住在可迁移库，而设置快照正是它所控制的内容 —— **自指**：
     * 云端被判"更新"的一次拉取会把整个设置分区连同这个键一起盖回云端旧值，
     * 用户的勾选永远无法在拉取中幸存，表现成"勾选后无法保存，重开又是默认"。
     * 这组用例钉死它的新家（设备态库）与两条边界：导出不带出、恢复不回流。
     */
    @Test
    fun sectionsKeyMustNotLeakIntoTheSettingsSnapshot() {
        SettingsManager.saveWebDavSyncSections(context, setOf("settings"))
        // 先证明导出本身在工作：塞一个普通键，它必须出现在快照里
        legacyPrefs().edit().putString("webdav_snapshot_probe", "x").commit()
        val snapshot = SettingsManager.webDavSettingsSnapshot(context)
        assertTrue("导出应该包含普通设置键", snapshot.containsKey("webdav_snapshot_probe"))
        assertFalse(
            "同步范围键进了设置快照 —— 云端同步会反过来覆盖本机勾选",
            snapshot.containsKey("webdav_sync_sections")
        )
    }

    @Test
    fun applyingACloudSnapshotMustNotRevertTheLocalSectionsChoice() {
        SettingsManager.saveWebDavSyncSections(context, setOf("settings"))
        // 模拟旧版推上云、还带着旧范围值的设置快照
        val legacyCloud = mapOf(
            "webdav_sync_sections" to
                mapOf("t" to SettingsBackupCodec.STRING, "v" to "subscription,subscription_usage")
        )
        SettingsManager.applyWebDavSettingsSnapshot(context, legacyCloud)
        assertEquals(
            "云端旧快照不得覆盖本机的同步范围勾选",
            setOf("settings"),
            SettingsManager.getWebDavSyncSections(context)
        )
    }

    @Test
    fun legacyLocalSectionsValueMigratesToDeviceState() {
        // 旧版把键写在 stun_settings（可迁移库）：升级后第一次读就该搬进设备态库并清掉旧库
        legacyPrefs().edit().putString("webdav_sync_sections", "subscription").commit()
        assertEquals(setOf("subscription"), SettingsManager.getWebDavSyncSections(context))
        assertFalse("旧库残留会被下一次快照再次带出去", legacyPrefs().contains("webdav_sync_sections"))
        // 之后的写读都走设备态库，与旧库再无瓜葛
        SettingsManager.saveWebDavSyncSections(context, setOf("settings", "subscription"))
        assertEquals(setOf("settings", "subscription"), SettingsManager.getWebDavSyncSections(context))
        assertFalse(legacyPrefs().contains("webdav_sync_sections"))
    }
}
