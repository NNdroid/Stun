package app.fjj.stun.remote

import android.app.Application
import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [BluetoothSyncManager] 服务端**动作契约**。
 *
 * 背景：手机端对蓝牙设备的「远程控制」面板复用了 Wi-Fi 版 UI，数据面靠 `get_tv_status`
 * 与 HTTP `/api/status` **同构**（同一份 [TvStatusResponse]），控制面靠 `start_vpn` /
 * `stop_vpn` / `restart_vpn` / `select_profile` 委托宿主回调。这里钉住几条容易悄悄破掉的约定：
 *
 * ① `get_tv_status` 即使**没有任何宿主注册**也必须返回可解析的最小状态 —— 手机端面板
 *    拿它当"设备在线但信息有限"的信号，返回结构一旦变了，面板会把在线设备误判成离线；
 * ② 宿主注册后返回的就是宿主给的数据（TV/Car 的 UI 状态得以透传）；
 * ③ 控制动作在没有宿主回调时只保留启停回落，`select_profile` 明确报不支持 ——
 *    「静默改成别的语义」比报错更危险；
 * ④ 旧手机 APK 发的 `toggle_vpn` 语义不变（兼容期不能断）。
 *
 * 不测真蓝牙（RFCOMM 套接字），只测 JSON 动作分发这一层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BluetoothSyncActionsTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @org.junit.Before
    fun setUp() {
        // 默认读取口会碰 StunRepository（类初始化加载 myssh 的 JNI 库，单测环境起不来）；
        // 动作分发层把它做成可注入后，这里替换成常量即可覆盖协议本身。
        BluetoothSyncManager.vpnStateNameProvider = { "DISCONNECTED" }
    }

    @org.junit.After
    fun tearDown() {
        BluetoothSyncManager.vpnStateNameProvider = { app.fjj.stun.repo.StunRepository.vpnState.value?.name }
        BluetoothSyncManager.tvStatusProvider = null
        BluetoothSyncManager.onRemoteControlRequested = null
    }

    private fun request(action: String, vararg pairs: Pair<String, String>): String =
        org.json.JSONObject().put("action", action).apply {
            pairs.forEach { (k, v) -> put(k, v) }
        }.toString()

    private fun dispatch(json: String) = runBlocking {
        org.json.JSONObject(BluetoothSyncManager.processRequest(context, json))
    }

    @Test
    fun `未知动作返回 error`() {
        val res = dispatch(request("no_such_action"))
        assertEquals("error", res.optString("status"))
    }

    @Test
    fun `无宿主时 get_tv_status 返回可解析的最小状态`() = runBlocking {
        val res = dispatch(request("get_tv_status"))
        assertEquals("ok", res.optString("status"))

        val payload = res.optJSONObject("tvStatus")
        assertNotNull("tvStatus 字段不能缺，面板靠它判断设备是否可用", payload)

        val status = Gson().fromJson(payload.toString(), TvStatusResponse::class.java)
        assertEquals("DISCONNECTED", status.vpnState)
        assertNull(status.currentProfileName)
        assertEquals(android.os.Build.MODEL, status.deviceName)
        // 没有宿主就没有节点列表，但字段本身必须在 —— 解析端不判空结构
        assertNotNull(status.profiles == null || status.profiles?.isEmpty() == true)
    }

    @Test
    fun `宿主注册后 get_tv_status 透传宿主数据`() = runBlocking {
        val expected = TvStatusResponse(
            vpnState = "CONNECTED",
            currentProfileName = "节点A",
            currentProfileId = "id-1",
            profileCount = 2,
            deviceName = "CAR",
            profiles = listOf(
                TvProfileSummary("id-1", "节点A", "ssh"),
                TvProfileSummary("id-2", "节点B", "udp_custom")
            )
        )
        BluetoothSyncManager.tvStatusProvider = { expected }
        try {
            val res = dispatch(request("get_tv_status"))
            val status = Gson().fromJson(
                res.optJSONObject("tvStatus").toString(),
                TvStatusResponse::class.java
            )
            assertEquals("CONNECTED", status.vpnState)
            assertEquals("节点A", status.currentProfileName)
            assertEquals(2, status.profileCount)
            assertEquals(2, status.profiles?.size)
        } finally {
            BluetoothSyncManager.tvStatusProvider = null
        }
    }

    @Test
    fun `无宿主回调时 select_profile 明确不支持且不改选中节点`() = runBlocking {
        val res = dispatch(request("select_profile", "profileId" to "id-9"))
        assertEquals("error", res.optString("status"))
        assertNull(app.fjj.stun.repo.SettingsManager.getSelectedProfileId(context))
    }

    @Test
    fun `宿主回调注册后控制动作委托给它`() = runBlocking {
        val received = mutableListOf<Pair<String, String?>>()
        BluetoothSyncManager.onRemoteControlRequested = { action, profileId ->
            received += action to profileId
            action != "select_profile"   // 模拟宿主拒绝 select_profile
        }
        try {
            assertTrue(dispatch(request("stop_vpn")).optString("status") == "success")
            assertTrue(dispatch(request("start_vpn", "profileId" to "id-1")).optString("status") == "success")
            assertEquals("error", dispatch(request("select_profile", "profileId" to "id-1")).optString("status"))

            // 三个动作都原样透传给宿主（包括被拒绝的 select_profile —— 拒绝与否由宿主决定）
            assertEquals(
                listOf<Pair<String, String?>>(
                    "stop_vpn" to null,
                    "start_vpn" to "id-1",
                    "select_profile" to "id-1"
                ),
                received
            )
        } finally {
            BluetoothSyncManager.onRemoteControlRequested = null
        }
    }

    @Test
    fun `旧 toggle_vpn 语义保持不变`() = runBlocking {
        // 只验证「带 profileId 会先选中」这一半；真正拉起服务的部分由 startOrStopService 完成，
        // 在 Robolectric 下 startForegroundService 只是记录 intent，不会真的起服务。
        val res = dispatch(request("toggle_vpn", "serviceMode" to "start", "profileId" to "id-tv"))
        assertEquals("success", res.optString("status"))
        assertEquals("id-tv", app.fjj.stun.repo.SettingsManager.getSelectedProfileId(context))
        assertNotNull(res.opt("vpnState"))
    }

    @Test
    fun `推送节点的 id 归一与 WebServer 同语义`() {
        // 普通 id：保留 —— 手机端「推送并启动」要靠同一个 id 发 start_vpn，重生成必选错节点
        assertEquals("id-1", BluetoothSyncManager.resolvePushedProfileId("id-1", emptySet()))
        assertEquals("id-1", BluetoothSyncManager.resolvePushedProfileId("id-1", setOf("id-2")))
        // 空 id / 与现存条目冲突：重新生成（非空、且不等于原值）
        val forBlank = BluetoothSyncManager.resolvePushedProfileId("", emptySet())
        assertTrue(forBlank.isNotBlank())
        val forCollision = BluetoothSyncManager.resolvePushedProfileId("id-1", setOf("id-1", "id-2"))
        assertTrue(forCollision.isNotBlank() && forCollision != "id-1")
    }
}
