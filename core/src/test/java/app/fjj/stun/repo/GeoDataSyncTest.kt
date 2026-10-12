package app.fjj.stun.repo

import android.app.Application
import android.content.Context
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [SettingsManager.updateGeoDataSync] 的成对提交不变量。
 *
 * [GeoRuleDownloadTest] 钉住的是单文件的三层校验，这里只钉住两份文件之间的事：
 *
 *  - 两份都通过 → 一起替换正式文件，并写下更新时间；
 *  - 任意一份没过 → 两份正式文件都**逐字节原样保留**，两个临时文件都清掉，
 *    而且**不能**写下更新时间——那是「已更新成功」的对外承诺。
 *
 * 必须挂 Robolectric：需要 Context 才能拿到 cacheDir 和 SharedPreferences。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoDataSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // 两份源文件：一份是最终生效的规则，另一份只用来证明「摘要对得上才装」。
    private val geositePayload = ruleFileFixture(
        byteArrayOf(0x0A, 0x02, 0x73, 0x70),
        byteArrayOf(0x0A, 0x01, 0x63),
    )
    private val geoipPayload = ruleFileFixture(byteArrayOf(0x0A, 0x03, 0x63, 0x6E, 0x2e))

    private val payloads = mapOf("geosite" to geositePayload, "geoip" to geoipPayload)

    // 已经装着的旧版本，内容和上面两份都不同，方便断言「没被替换」。
    private val oldGeosite = ruleFileFixture(byteArrayOf(0x0A, 0x01, 0x21))
    private val oldGeoip = ruleFileFixture(byteArrayOf(0x0A, 0x01, 0x22))

    @Before
    fun setUp() {
        StunLogger.isLogcatEnabled = false
        SettingsManager.saveLastUpdateTime(context, 0L)
    }

    @After
    fun tearDown() {
        StunLogger.isLogcatEnabled = true
    }

    @Test
    fun `两份都通过校验时一起替换正式文件并记下更新时间`() {
        servePayloads()
        installOldVersions()

        assertEquals("更新前不该有成功记录", 0L, SettingsManager.getLastUpdateTime(context))

        SettingsManager.updateGeoDataSync(context)

        assertArrayEquals("geosite 应被新内容替换", geositePayload, liveGeosite().readBytes())
        assertArrayEquals("geoip 应被新内容替换", geoipPayload, liveGeoip().readBytes())
        assertTrue("成功后必须记下更新时间", SettingsManager.getLastUpdateTime(context) > 0L)
        assertNoTempFilesLeft()
    }

    @Test
    fun `任一份校验不过时两份正式文件都原样保留且不记下更新时间`() {
        // geoip 的 sidecar 故意写错：只有校验和能发现载荷不是发布源的那一份。
        servePayloads(wrongSumFor = "geoip")
        installOldVersions()

        assertThrows(IOException::class.java) { SettingsManager.updateGeoDataSync(context) }

        assertArrayEquals("geosite 校验通过了也不该落盘，得等两份都过", oldGeosite, liveGeosite().readBytes())
        assertArrayEquals("geoip 校验没过，正式文件必须原样保留", oldGeoip, liveGeoip().readBytes())
        assertEquals("失败的更新不能记下「已更新」的时间戳", 0L, SettingsManager.getLastUpdateTime(context))
        assertNoTempFilesLeft()
    }

    @Test
    fun `载荷本身截断时同样不替换正式文件`() {
        // 镜像不发布 sidecar 的降级路径：这一份只剩结构检查能发现它。
        val truncated = geositePayload.copyOf(geositePayload.size - 2)
        val geositeSource = tmp.newFile("geosite-truncated.dat").apply { writeBytes(truncated) }
        serveSource("geoip")
        SettingsManager.saveGeositeUrl(context, geositeSource.toURI().toString())

        installOldVersions()

        assertThrows(IOException::class.java) { SettingsManager.updateGeoDataSync(context) }

        assertArrayEquals(oldGeosite, liveGeosite().readBytes())
        assertArrayEquals(oldGeoip, liveGeoip().readBytes())
        assertEquals(0L, SettingsManager.getLastUpdateTime(context))
        assertNoTempFilesLeft()
    }

    // ─────────────────────────── 辅助 ───────────────────────────

    private fun installOldVersions() {
        liveGeosite().writeBytes(oldGeosite)
        liveGeoip().writeBytes(oldGeoip)
    }

    private fun liveGeosite() = File(SettingsManager.getGeositeCachePath(context))

    private fun liveGeoip() = File(SettingsManager.getGeoipCachePath(context))

    /** 把两份载荷写到临时目录并登记成规则源；[wrongSumFor] 指定哪一份的摘要故意写错。 */
    private fun servePayloads(wrongSumFor: String? = null) {
        serveSource("geosite", geositePayload, wrongSumFor == "geosite")
        serveSource("geoip", geoipPayload, wrongSumFor == "geoip")
    }

    private fun serveSource(name: String, payload: ByteArray = payloads[name]!!, corruptSum: Boolean = false) {
        val source = tmp.newFile("$name-source.dat").apply { writeBytes(payload) }
        if (corruptSum) {
            // 拿另一份载荷的摘要冒充：字节合法、摘要不符，只有校验和能发现。
            val foreign = if (name == "geosite") geoipPayload else geositePayload
            File(source.parentFile, source.name + ".sha256sum").writeText("${sha256Of(foreign)}  ${source.name}\n")
        } else {
            writeSidecarFor(source, payload)
        }
        if (name == "geosite") SettingsManager.saveGeositeUrl(context, source.toURI().toString())
        else SettingsManager.saveGeoipUrl(context, source.toURI().toString())
    }

    private fun assertNoTempFilesLeft() {
        val leftovers = listOf(liveGeosite(), liveGeoip()).flatMap {
            // .tmp 是成对提交用的暂存，.tmp.tmp 是单文件校验用的暂存，两层都得清干净。
            listOf(File("${it.absolutePath}.tmp"), File("${it.absolutePath}.tmp.tmp"))
        }
        val present = leftovers.filter { it.exists() }
        assertTrue("更新结束后不该留下临时文件：${present.joinToString { it.name }}", present.isEmpty())
    }
}
