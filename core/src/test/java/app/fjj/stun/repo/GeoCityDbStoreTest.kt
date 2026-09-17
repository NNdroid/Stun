package app.fjj.stun.repo

import android.app.Application
import android.content.Context
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 地球离线地理库的本地状态管理（[SettingsManager] 的 GeoCityDb 一族）。
 *
 * 这里刻意**不测真下载**（那是网络的事），只钉住三件容易出事的东西：
 * 文件名与 cacheDir 的绑定、就绪/缺失判定、以及**下载失败不能毁掉已下好的旧文件**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoCityDbStoreTest {

    // 用 RuntimeEnvironment 而不是 androidx.test 的 ApplicationProvider：:core 的测试
    // 依赖里只有 junit + robolectric，不额外为一个 Context 引一个测试库。
    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun target(db: SettingsManager.GeoCityDb) = SettingsManager.geoCityFile(context, db)

    private fun clean() {
        SettingsManager.GeoCityDb.entries.forEach {
            target(it).delete()
            File(context.cacheDir, "${it.fileName}.tmp").delete()
        }
    }

    @Test
    fun `两份库都落在 cacheDir 下的固定文件名`() {
        clean()
        val ipv4 = target(SettingsManager.GeoCityDb.IPV4)
        val ipv6 = target(SettingsManager.GeoCityDb.IPV6)

        assertEquals(context.cacheDir, ipv4.parentFile)
        assertEquals(SettingsManager.GEO_CITY_IPV4_FILE, ipv4.name)
        assertEquals(SettingsManager.GEO_CITY_IPV6_FILE, ipv6.name)
        // 用户已经下过的文件必须能被后续版本继续认出来 —— 改名等于让所有人重下 43MB
        assertEquals("geoip-city-ipv4.mmdb", ipv4.name)
        assertEquals("geoip-city-ipv6.mmdb", ipv6.name)
    }

    @Test
    fun `就绪判定要求文件存在且非空`() {
        clean()
        assertFalse(SettingsManager.geoCityReady(context, SettingsManager.GeoCityDb.IPV4))
        assertEquals(
            listOf(SettingsManager.GeoCityDb.IPV4, SettingsManager.GeoCityDb.IPV6),
            SettingsManager.missingGeoCityDbs(context),
        )

        // 空文件不算就绪：下载中断留下的 0 字节文件不能当成可用
        target(SettingsManager.GeoCityDb.IPV4).writeBytes(ByteArray(0))
        assertFalse(SettingsManager.geoCityReady(context, SettingsManager.GeoCityDb.IPV4))

        target(SettingsManager.GeoCityDb.IPV4).writeBytes(ByteArray(16))
        assertTrue(SettingsManager.geoCityReady(context, SettingsManager.GeoCityDb.IPV4))
        assertEquals(
            listOf(SettingsManager.GeoCityDb.IPV6),
            SettingsManager.missingGeoCityDbs(context),
        )
        assertFalse("只补上一份时整体不算就绪", SettingsManager.geoCityAllReady(context))

        target(SettingsManager.GeoCityDb.IPV6).writeBytes(ByteArray(16))
        assertTrue(SettingsManager.missingGeoCityDbs(context).isEmpty())
        assertTrue(SettingsManager.geoCityAllReady(context))
    }

    @Test
    fun `URL 可配置且默认指向 sapics 的双栈库`() {
        clean()
        assertTrue(
            SettingsManager.getGeoCityUrl(context, SettingsManager.GeoCityDb.IPV4)
                .endsWith("geolite2-city-ipv4.mmdb"),
        )
        assertTrue(
            SettingsManager.getGeoCityUrl(context, SettingsManager.GeoCityDb.IPV6)
                .endsWith("geolite2-city-ipv6.mmdb"),
        )

        // 国内不通 GitHub 时换镜像是这套 URL 可配的唯一目的
        val mirror = "https://mirror.example.com/city-ipv4.mmdb"
        SettingsManager.saveGeoCityUrl(context, SettingsManager.GeoCityDb.IPV4, mirror)
        assertEquals(mirror, SettingsManager.getGeoCityUrl(context, SettingsManager.GeoCityDb.IPV4))
        assertTrue(
            "改 IPv4 不能连带把 IPv6 的 URL 也改了",
            SettingsManager.getGeoCityUrl(context, SettingsManager.GeoCityDb.IPV6)
                .endsWith("geolite2-city-ipv6.mmdb"),
        )
    }

    /**
     * 核心不变量：先下 `.tmp`、校验非空、最后 `renameTo`。
     * 所以下载失败时**旧的可用文件必须原样还在**，`.tmp` 也要清干净。
     *
     * 用 `file://` 指向不存在的路径来制造一个立即失败的下载 —— 不起网络服务、毫秒级。
     */
    @Test
    fun `下载失败时旧文件原样保留且不留临时文件`() {
        clean()
        val existing = target(SettingsManager.GeoCityDb.IPV4)
        val original = byteArrayOf(1, 2, 3, 4, 5)
        existing.writeBytes(original)

        SettingsManager.saveGeoCityUrl(
            context,
            SettingsManager.GeoCityDb.IPV4,
            "file:///definitely/not/a/real/path/city-ipv4.mmdb",
        )

        val thrown = runCatching {
            SettingsManager.downloadGeoCitySync(context, listOf(SettingsManager.GeoCityDb.IPV4))
        }.exceptionOrNull()

        assertTrue("应当抛出下载失败，实际：$thrown", thrown is java.io.IOException)
        assertArrayEquals("旧的可用库必须原样保留", original, existing.readBytes())
        assertFalse(
            "临时文件必须清掉，否则下次下载会看到残留",
            File(context.cacheDir, "${SettingsManager.GEO_CITY_IPV4_FILE}.tmp").exists(),
        )
    }
}
