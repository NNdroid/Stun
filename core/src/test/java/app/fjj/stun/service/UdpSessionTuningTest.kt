package app.fjj.stun.service

import android.app.Application
import android.content.Context
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.SettingsManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * UDP 会话旋钮的下发契约：[SettingsManager] 的钳位与 [VpnConfigBuilder] 的 JSON 输出。
 *
 * 这两个值过去从不被 Stun 下发，一直靠 myssh 的静默默认值（1024 条 / 60s）。
 * UDP 会话按「客户端四元组 → 目标」建，局域网组播与发现类流量每换一个源端口
 * 就是一条新会话，连接数只能靠它们压。所以验收点是两条：填得进设置（钳位），
 * 改完真的进 JSON（下发）。后者缺了就等于没修 —— 用户调了数值，连接数纹丝不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UdpSessionTuningTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    /** 0 必须原样保留：它就是「交给引擎默认」的取值，不能被钳位成别的。 */
    @Test
    fun udpDefaultZeroIsPreserved() {
        assertEquals(0, SettingsManager.UDP_MAX_SESSIONS_MIN)
        assertEquals(0, SettingsManager.UDP_IDLE_TIMEOUT_SEC_MIN)
        assertEquals(SettingsManager.DEFAULT_UDP_MAX_SESSIONS, 0)
        assertEquals(SettingsManager.DEFAULT_UDP_IDLE_TIMEOUT_SEC, 0)

        SettingsManager.saveUdpMaxSessions(context, 0)
        SettingsManager.saveUdpIdleTimeoutSec(context, 0)
        assertEquals(0, SettingsManager.getUdpMaxSessions(context))
        assertEquals(0, SettingsManager.getUdpIdleTimeoutSec(context))
    }

    @Test
    fun udpInBoundsValuesRoundTrip() {
        SettingsManager.saveUdpMaxSessions(context, 256)
        SettingsManager.saveUdpIdleTimeoutSec(context, 30)
        assertEquals(256, SettingsManager.getUdpMaxSessions(context))
        assertEquals(30, SettingsManager.getUdpIdleTimeoutSec(context))
    }

    // 越界必须在**读回来**之前就被钳住：myssh 的 validatePerformanceConfig 对越界
    // 是硬失败，proxy.start 会直接报错，用户看到的是「连不上」而不是「填大了」。
    @Test
    fun udpOutOfRangeValuesAreClamped() {
        SettingsManager.saveUdpMaxSessions(context, 999_999)
        SettingsManager.saveUdpIdleTimeoutSec(context, -5)
        assertEquals(SettingsManager.UDP_MAX_SESSIONS_MAX, SettingsManager.getUdpMaxSessions(context))
        assertEquals(SettingsManager.UDP_IDLE_TIMEOUT_SEC_MIN, SettingsManager.getUdpIdleTimeoutSec(context))

        SettingsManager.saveUdpMaxSessions(context, -1)
        SettingsManager.saveUdpIdleTimeoutSec(context, 999_999)
        assertEquals(SettingsManager.UDP_MAX_SESSIONS_MIN, SettingsManager.getUdpMaxSessions(context))
        assertEquals(SettingsManager.UDP_IDLE_TIMEOUT_SEC_MAX, SettingsManager.getUdpIdleTimeoutSec(context))
    }

    // 钳位必须发生在 JSON 组装之前：这条断言保证引擎收到的值永远在合法区间内，
    // 即使 SharedPreferences 里被外部（旧版本、第三方写入）塞进了脏值。
    @Test
    fun buildMySshConfig_emitsUdpSessionKnobs() {
        SettingsManager.saveUdpMaxSessions(context, 256)
        SettingsManager.saveUdpIdleTimeoutSec(context, 30)

        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, Profile(), 1080, 53))

        assertEquals(256, json.getInt("udp_max_sessions"))
        assertEquals(30, json.getInt("udp_idle_timeout_sec"))
    }

    @Test
    fun buildMySshConfig_neverEmitsOutOfRangeValues() {
        SettingsManager.saveUdpMaxSessions(context, 999_999)
        SettingsManager.saveUdpIdleTimeoutSec(context, -5)

        val json = JSONObject(VpnConfigBuilder.buildMySshConfig(context, Profile(), 1080, 53))

        assertEquals(SettingsManager.UDP_MAX_SESSIONS_MAX, json.getInt("udp_max_sessions"))
        assertEquals(SettingsManager.UDP_IDLE_TIMEOUT_SEC_MIN, json.getInt("udp_idle_timeout_sec"))
    }
}
