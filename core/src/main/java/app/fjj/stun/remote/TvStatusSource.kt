package app.fjj.stun.remote

import android.content.Context
import android.os.Build
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository

/**
 * 「远端查状态」的**进程级**状态源。
 *
 * ## 为什么不能挂在 Activity 上
 *
 * 三个远控通道（局域网 [RemoteSyncManager]、蓝牙 [BluetoothSyncManager]、Web 控制台）都把
 * `tvStatusProvider` 存成**常驻 `object` 上的静态字段**，监听面也早已是进程级基础设施
 * （进程冷启动由 [RemoteControlHost] 拉起，界面销毁不再停）。
 *
 * 但先前 `tvStatusProvider` 挂的是 `MainActivity::buildTvStatus` —— 一个捕获了 `this@MainActivity`
 * 的 bound method reference。界面按返回销毁后 `clearRemoteCallbacks()` 把它置 null，于是不再是
 * 「没数据」而是「拿不到 provider」：手机连得上、`get_tv_status` 回 `status:"ok"`，内容却是
 * 兜底的 `profileCount = 0` / `profiles` 缺省，面板显示「电视端暂无可用节点」。重新打开 App 又
 * 会重新注册，节点就回来了 —— 表现成「只有打开了 App 这里才有数据」。
 *
 * 另一个代价：那个引用活在进程级静态字段里，会把**整个已销毁的 Activity 连同它的 ViewBinding
 * 全部钉住不放**，是真实的泄漏。
 *
 * 本类只依赖 [ProfileManager] / [SettingsManager] / [StunRepository]，与任何 Activity / ViewBinding
 * 无关，所以进程在、UI 不在的时候同样能给出真实状态。由 `tv/TVApp` 与 `car/CarApp` 在
 * `onCreate` 里注册一次（[Application.onCreate] 一定先于任何 Service / Worker 执行）。
 *
 * ## 调用约束
 * [ProfileManager] 是 Room，**必须在主线程之外调用** —— 局域网走 Ktor CIO 线程、蓝牙走
 * `Dispatchers.IO`，两条路径本来就满足。
 */
object TvStatusSource {
    private const val TAG = "TvStatusSource"

    /**
     * 出口 IP 只能由宿主界面自己探测（`ExitIpProbe`），UI 关掉后这个值不能跟着丢，
     * 所以缓存在这里而不是继续读 `binding.tvPublicIp`。null = 从未探到过。
     */
    @Volatile var lastPublicIp: String? = null

    /** 单块读取失败只丢掉那一项 —— 远端状态接口要的是「尽量给数据」，不能因为一块坏数据整包 500。 */
    private fun <T> safe(label: String, block: () -> T?): T? =
        runCatching { block() }.getOrElse {
            StunLogger.w(TAG, "read $label failed: ${it.message}")
            null
        }

    /** 组装一份完整的 [TvStatusResponse]。 */
    fun build(context: Context): TvStatusResponse {
        val profiles = safe<List<TvProfileSummary>>("profiles") {
            ProfileManager.getProfiles(context).map { TvProfileSummary(it.id, it.name, it.tunnelType) }
        } ?: emptyList()

        // getSelectedProfile 在「从未选中过」或「选中项已被删」时会回落到一个全新的 Profile()
        // （name 默认 "Default config"）。照抄会把一个根本不存在的节点报给远端面板 —— 那正好
        // 会伪装成「明明没节点却显示了一个默认节点」。只有它真的在列表里才算当前节点。
        val selected = safe<Profile>("selected") { ProfileManager.getSelectedProfile(context) }
        val current = if (selected != null && profiles.any { it.id == selected.id }) selected else null

        val rates = safe<Rates>("rates") {
            Rates(
                txRate = StunRepository.txRate.value ?: 0L,
                rxRate = StunRepository.rxRate.value ?: 0L,
                txTotal = StunRepository.txTotal.value ?: 0L,
                rxTotal = StunRepository.rxTotal.value ?: 0L,
            )
        } ?: Rates()

        return TvStatusResponse(
            vpnState = safe<String>("vpnState") { StunRepository.vpnState.value?.name } ?: "DISCONNECTED",
            currentProfileName = current?.name?.takeIf { it.isNotBlank() },
            currentProfileId = current?.let { SettingsManager.getSelectedProfileId(context) },
            currentProfileType = current?.let { profileTypeLabel(it) },
            currentProfileServer = current?.sshAddr?.takeIf { it.isNotBlank() },
            profileCount = profiles.size,
            deviceName = Build.MODEL,
            publicIp = lastPublicIp,
            txRate = rates.txRate,
            rxRate = rates.rxRate,
            txTotal = rates.txTotal,
            rxTotal = rates.rxTotal,
            profiles = profiles,
        )
    }

    /** 四路速率一次性读出来，避免四次独立降级判定。 */
    private data class Rates(
        val txRate: Long = 0L,
        val rxRate: Long = 0L,
        val txTotal: Long = 0L,
        val rxTotal: Long = 0L,
    )
}

/**
 * 节点类型标签：UDP CUSTOM 带上 magic header，其余给短名。
 *
 * 放在 `:core` 是因为 [TvStatusSource.build] 需要它，而远控状态要脱离宿主 UI 独立可用；
 * 宿主界面自己的展示可以继续用它，不必各写一份 when。
 */
fun profileTypeLabel(profile: Profile): String = when (profile.tunnelType) {
    Profile.TUNNEL_TYPE_UDP_CUSTOM -> "UDP CUSTOM (${profile.udpCustomMagic.ifBlank { "UDPC" }})"
    Profile.TUNNEL_TYPE_DNS -> "DNS"
    Profile.TUNNEL_TYPE_KCP -> "KCP"
    else -> profile.tunnelType.uppercase()
}
