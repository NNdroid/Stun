package app.fjj.stun.remote

import android.app.Application
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.util.KeystoreUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TvStatusSource] 是「宿主界面不在时仍要给出真实状态」的那条链 —— 见该文件头注释。
 * 之前的实现挂在 `MainActivity` 上，界面一销毁 provider 就被置 null，手机拿到的是一份
 * `profileCount = 0` 的兜底状态，面板于是显示「电视端暂无可用节点」。
 *
 * 这里用**真 Room**（不是 mock）钉住四件事：
 * ① 没有任何宿主 Activity 参与时，profileCount / profiles / 当前节点都是真实数据；
 * ② 空库时**不会**把 `getSelectedProfile` 回落出来的那个虚构 `Profile()`（name = "Default config"）
 *    当成当前节点报出去 —— 否则就是「明明没节点却显示一个默认节点」；
 * ③ UDP CUSTOM 的类型标签带上 magic header（手机端「切换节点」列表靠它区分同类节点）；
 * ④ [TvStatusSource.lastPublicIp] 是唯一的宿主派生字段，UI 关掉后不能跟着丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TvStatusSourceTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // 凭据加解密前必须就位；产品代码里各端 Application.onCreate 都做了这件事。
        runCatching { KeystoreUtils.init(context) }
            .onFailure { println("TEST keystore init failed: ${it.message}") }
        TvStatusSource.lastPublicIp = null
        // 清库：同一轮测试里的其他用例（TvProfileListProbeTest）也往同一个 Room 实例里插数据，
        // Room 的 DB 文件在测试进程间共享，不清的话彼此的 profileCount 断言会互相污染。
        onIo { ProfileManager.saveProfiles(context, emptyList()) }
    }

    @After
    fun tearDown() {
        onIo { ProfileManager.saveProfiles(context, emptyList()) }
    }

    /** Room 禁止主线程访问，所以所有会碰 ProfileManager 的读写都放进 worker 线程。 */
    private fun onIo(action: () -> Unit) {
        var thrown: Throwable? = null
        val worker = Thread {
            try {
                action()
            } catch (t: Throwable) {
                thrown = t
            }
        }
        worker.start()
        worker.join(30_000)
        thrown?.let { throw it }
    }

    private fun buildStatus(): TvStatusResponse {
        var result: TvStatusResponse? = null
        onIo { result = TvStatusSource.build(context) }
        return result ?: error("build() returned nothing")
    }

    @Test
    fun 空库时不会上报虚构的默认节点() {
        val status = buildStatus()

        assertEquals(0, status.profileCount)
        assertEquals(0, status.profiles?.size ?: -1)
        // Profile() 的默认 name 是 "Default config"，若没拦住这里会是那个字符串
        assertNull(status.currentProfileName)
        assertNull(status.currentProfileType)
        assertNull(status.currentProfileServer)
        assertNull(status.currentProfileId)
    }

    @Test
    fun 无宿主Activity参与时节点列表与当前节点都是真实值() {
        onIo {
            ProfileManager.addProfile(context, Profile().apply {
                id = "probe-udp"
                name = "Probe UDP"
                sshAddr = "10.0.0.9:443"
                tunnelType = Profile.TUNNEL_TYPE_UDP_CUSTOM
                udpCustomMagic = "MAGIC1"
            })
            ProfileManager.addProfile(context, Profile().apply {
                id = "probe-raw"
                name = "Probe Raw"
                sshAddr = "10.0.0.9:22"
                tunnelType = Profile.TUNNEL_TYPE_RAW
            })
            SettingsManager.setSelectedProfileId(context, "probe-udp")
        }

        val status = buildStatus()

        assertEquals(2, status.profileCount)
        assertEquals(2, status.profiles?.size)
        assertEquals("Probe UDP", status.currentProfileName)
        assertEquals("probe-udp", status.currentProfileId)
        assertEquals("10.0.0.9:443", status.currentProfileServer)
        assertEquals("UDP CUSTOM (MAGIC1)", status.currentProfileType)
        assertEquals(setOf("Probe UDP", "Probe Raw"), status.profiles?.map { it.name }?.toSet())
    }

    @Test
    fun 选中项被删后回落到真实存在的节点() {
        onIo {
            ProfileManager.addProfile(context, Profile().apply {
                id = "probe-raw"
                name = "Probe Raw"
                tunnelType = Profile.TUNNEL_TYPE_RAW
            })
            SettingsManager.setSelectedProfileId(context, "probe-raw")
        }
        // 把选中项删掉：getSelectedProfile 会回落到 Profile()，但列表里只剩 Probe Raw
        onIo { ProfileManager.deleteProfile(context, Profile().apply { id = "probe-raw" }) }

        val status = buildStatus()
        assertEquals(0, status.profileCount)
        assertNull("库空了就不该再报一个当前节点", status.currentProfileName)
    }

    @Test
    fun publicIp缓存独立于宿主界面() {
        assertNull(buildStatus().publicIp)

        TvStatusSource.lastPublicIp = "1.2.3.4"
        assertEquals("1.2.3.4", buildStatus().publicIp)

        TvStatusSource.lastPublicIp = null
        assertNull(buildStatus().publicIp)
    }

    @Test
    fun 节点类型标签覆盖各隧道类型() {
        assertEquals(
            "UDP CUSTOM (ABCD)",
            profileTypeLabel(Profile().apply {
                tunnelType = Profile.TUNNEL_TYPE_UDP_CUSTOM
                udpCustomMagic = "ABCD"
            })
        )
        // magic 为空时给默认值，不能出现 "UDP CUSTOM ()"
        assertEquals(
            "UDP CUSTOM (UDPC)",
            profileTypeLabel(Profile().apply {
                tunnelType = Profile.TUNNEL_TYPE_UDP_CUSTOM
                udpCustomMagic = ""
            })
        )
        assertEquals("DNS", profileTypeLabel(Profile().apply { tunnelType = Profile.TUNNEL_TYPE_DNS }))
        assertEquals("KCP", profileTypeLabel(Profile().apply { tunnelType = Profile.TUNNEL_TYPE_KCP }))
        assertEquals("RAW", profileTypeLabel(Profile().apply { tunnelType = Profile.TUNNEL_TYPE_RAW }))
    }
}
