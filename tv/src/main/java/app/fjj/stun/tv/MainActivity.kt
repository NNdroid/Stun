package app.fjj.stun.tv

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_DEL
import android.view.KeyEvent.KEYCODE_FORWARD_DEL
import android.view.KeyEvent.KEYCODE_MENU
import android.view.SoundEffectConstants
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.remote.BluetoothSyncManager
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.remote.TvStatusSource
import app.fjj.stun.remote.WebServer
import app.fjj.stun.remote.profileTypeLabel
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.ui.UserFeedback
import app.fjj.stun.util.AppUtils
import app.fjj.stun.util.CrashHandler
import app.fjj.stun.util.ExitIpProbe
import app.fjj.stun.util.GridSpans
import app.fjj.stun.util.PingResults
import app.fjj.stun.util.RootShell
import app.fjj.stun.tv.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.view.isVisible
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : FragmentActivity() {

    companion object {
        // 与手机端 testAllProfilesLatency 同目标同超时，两端结果才可比
        private const val PING_TARGET = "http://cp.cloudflare.com/generate_204"
        private const val PING_TIMEOUT_MS = 8000L
        private const val PUSH_CONFIRM_TIMEOUT_MS = 30_000L
        private const val QR_SIZE = 256
    }

    // ── 视图与运行时状态 ──────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProfileAdapterTV
    private var isSyncServerRunning = false
    private var currentVpnState = VpnState.DISCONNECTED

    // ── 后台任务与缓存（流量 1Hz 回调，findViewById 只走一次） ──────
    private var publicIpJob: Job? = null
    private var latencyTestJob: Job? = null
    private var tvTrafficView: TextView? = null
    private var tvTrafficTotalView: TextView? = null
    private var lastEngineError: String? = null

    /** VPN 授权弹窗结果：授权成功后才真正 start，否则服务起来也会立刻自毁。 */
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            UserFeedback.error(this@MainActivity, binding.root,
                    getString(CoreR.string.tv_vpn_permission_denied))
        }
    }

    /**
     * Android 12+ gates RFCOMM server creation behind the runtime BLUETOOTH_CONNECT grant, so the
     * Bluetooth sync server can only come up after this returns. The Wi-Fi (HTTP) sync server is
     * unaffected — see [startBluetoothSyncServer].
     */
    private val btConnectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            BluetoothSyncManager.startServer(this)
        } else {
            StunLogger.w("MainActivity", "BLUETOOTH_CONNECT denied; phone-to-TV Bluetooth sync disabled")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initBinding()

        // 明文凭据迁移已挪到 AppBootstrap（覆盖 phone/tv/car/wear/xr 全部入口），这里不再重复触发。

        setupUI()
        setupRemoteCallbacks()
        observeData()
        checkPreviousCrash()

        // 侧栏打开时 BACK 先关侧栏，否则走默认回退
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!hideOptionsSidebar()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // 同步服务（局域网 + 蓝牙）已由 TVApp 按持久化标志在进程级启动过；这里只把按钮/状态
        // 文案对齐到真实状态，并顺手补一遍（全部幂等）—— 覆盖「Activity 重建但进程没死」的场景。
        toggleSyncServer(SettingsManager.isRemoteSyncEnabled(this))

        // 上一次进程崩溃过就弹日志（TV 上看不到 logcat）
        CrashHandler.showCrashDialogIfAny(this)

        startWebConsole()
    }

    /**
     * ViewBinding 优先；个别被改过的 TV ROM 会抛异常，退回普通 setContentView。
     * 退回路径也必须把 binding 补上，否则它保持未初始化，下面任何 UI 代码都会崩。
     */
    private fun initBinding() {
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            StunLogger.e("MainActivity", "Failed to inflate layout", e)
            setContentView(R.layout.activity_main)
            binding = ActivityMainBinding.bind(findViewById(R.id.main))
        }
    }

    // ── Web 控制台 ────────────────────────────────────────────────
    /** 起 Web 控制台并把地址与二维码挂屏；TV 上扫码配对比手输 URL 省事。 */
    private fun startWebConsole() {
        lifecycleScope.launch(Dispatchers.IO) {
            val port = WebServer.start(this@MainActivity)
            if (port > 0) {
                // 进程保活服务由 RemoteControlHost.startAll 按「远程控制保活」标志统一挂载，
                // 这里不再单独挂一次（挂上后进程不再回落 cached 优先级被 ROM 裁掉）。
                updateWebConsoleUI(WebServer.getEffectiveUrl(this@MainActivity, port))
            }
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                // TV 把地址+二维码直接挂屏上，「关闭认证」模式下同网段任何设备都能控制本机 —— 显式提示
                binding.tvWebAuthWarning.visibility =
                    if (SettingsManager.getWebAuthMode(this@MainActivity) == SettingsManager.WEB_AUTH_MODE_DISABLED)
                        View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateWebConsoleUI(url: String) {
        lifecycleScope.launch {
            val qrBitmap = withContext(Dispatchers.IO) { generateQrBitmap(url, QR_SIZE) }
            binding.tvWebConsole.text = url
            if (qrBitmap != null) binding.ivWebQrCode.setImageBitmap(qrBitmap)
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap? = try {
        BarcodeEncoder().encodeBitmap(content, BarcodeFormat.QR_CODE, size, size)
    } catch (_: Exception) {
        null
    }

    // ── 远控通道 ──────────────────────────────────────────────────
    /**
     * 三个通道（蓝牙 / 局域网 / Web 控制台）共用同一套控制逻辑：手机端对蓝牙设备的「远程控制」
     * 面板与局域网面板解析同一份 TvStatusResponse，控制动作也走同一个回调，TV 侧 UI 联动一致。
     *
     * 注意**状态源不在此注册** —— 它已在 `TVApp.onCreate` 由 [TvStatusSource] 进程级提供。
     * 原先挂的是 `::buildTvStatus`（一个捕获了 `this@MainActivity` 的 bound method reference），
     * onDestroy 一置 null，手机就收到「在线但 profileCount=0」的兜底响应 —— 表现成
     * 「只有打开了 App 这里才有数据」；顺带还把整个已销毁的 Activity 连同 ViewBinding
     * 钉在进程级静态字段上（真泄漏）。
     */
    private fun setupRemoteCallbacks() {
        RemoteSyncManager.onRemoteControlRequested = ::handleRemoteAction
        BluetoothSyncManager.onRemoteControlRequested = RemoteSyncManager.onRemoteControlRequested
        WebServer.onVpnControlRequested = ::handleRemoteAction

        RemoteSyncManager.onProfilePushRequested = ::confirmProfilePush

        WebServer.onProfileSelected = { profileId ->
            refreshOnMain {
                adapter.updateSelectedId(profileId)
                updateSelectedNodeUI()
            }
        }

        // 加/删节点后都要重算「当前选中节点」标题：被删掉的正好可能是选中项。
        // 两个回调的参数类型不同（Profile / String），给不出同一个 lambda 对象，只能各写一行。
        WebServer.onProfileAdded = { _ -> refreshSelectedNode() }
        WebServer.onProfileDeleted = { _ -> refreshSelectedNode() }

        WebServer.onAuthConfigChanged = ::updateWebConsoleUI
    }

    /**
     * 三个远控通道统一的动作处理（返回 false 表示该动作对 TV 无意义）。
     *
     * 此前蓝牙+局域网与 Web 控制台各写了一份 when，两份语义不一致：Web 那份无条件
     * handleConnectClick()，已连接时按「连接」会**断开**；蓝牙+局域网那份又绕过了
     * VPN 授权 / tproxy Root 的前置检查。现在只有一份，start 走同一个受保护入口。
     */
    private suspend fun handleRemoteAction(action: String, profileId: String?): Boolean =
        withContext(Dispatchers.Main) {
            when (action) {
                "start_vpn" -> {
                    // 远端的「连接」是幂等的：已在连接途中就什么都不做，不像按钮那样切换
                    selectProfileById(profileId)
                    if (!isVpnBusy()) handleConnectClick()
                    true
                }
                "stop_vpn" -> {
                    stopVpn()
                    true
                }
                "restart_vpn" -> {
                    stopVpn()
                    refreshOnMain { awaitDisconnectedThenStart(profileId) }
                    true
                }
                "select_profile" -> {
                    if (profileId == null) return@withContext false
                    val wasActive = isVpnBusy()
                    selectProfileById(profileId)
                    if (wasActive) {
                        stopVpn()
                        refreshOnMain { awaitDisconnectedThenStart(null) }
                    }
                    true
                }
                else -> false
            }
        }

    /** 手机端推节点过来时弹确认框；超时按拒绝处理。 */
    private suspend fun confirmProfilePush(senderIp: String, profile: Profile): Boolean {
        val accepted = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) { showPushConfirmDialog(senderIp, profile, accepted) }
        return try {
            withTimeout(PUSH_CONFIRM_TIMEOUT_MS.milliseconds) { accepted.await() }
        } catch (_: TimeoutCancellationException) {
            false
        }
    }

    private fun showPushConfirmDialog(
        senderIp: String,
        profile: Profile,
        deferred: CompletableDeferred<Boolean>
    ) {
        if (isFinishing || isDestroyed) {
            deferred.complete(false)
            return
        }
        val message = getString(
            CoreR.string.tv_push_confirm_message,
            senderIp,
            profile.name,
            profile.sshAddr,
            profile.tunnelType.uppercase()
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.tv_push_confirm_title))
            .setMessage(message)
            .setIcon(CoreR.drawable.ic_notification)
            .setPositiveButton(getString(CoreR.string.accept)) { _, _ ->
                deferred.complete(true)
                Toast.makeText(this, getString(CoreR.string.tv_push_accepted, profile.name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(CoreR.string.reject)) { _, _ ->
                deferred.complete(false)
                Toast.makeText(this, getString(CoreR.string.tv_push_rejected), Toast.LENGTH_SHORT).show()
            }
            .setOnCancelListener { deferred.complete(false) }
            .create()
            .show()
    }

    /** Web 控制台的回调可能在任意线程触发，统一切回主线程再动 UI。 */
    private fun refreshOnMain(block: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { block() }
    }

    private fun refreshSelectedNode() {
        refreshOnMain { updateSelectedNodeUI() }
    }

    // ── 界面装配 ──────────────────────────────────────────────────
    private fun setupUI() {
        adapter = ProfileAdapterTV(
            SettingsManager.getSelectedProfileId(this),
            onProfileClick = { profile ->
                // 已连接/连接中时点别的节点：先确认再「切换 + 重连」，
                // 与远控 select_profile / WebUI 的行为对齐，不再只静默改选中
                if (isVpnBusy() && profile.id != SettingsManager.getSelectedProfileId(this)) {
                    confirmSwitchProfile(profile)
                } else {
                    selectProfileLocally(profile)
                }
            },
            onProfileLongClick = { profile -> showProfileOptionsDialog(profile) }
        )

        // 宽屏把节点列表切成一行多列：TV 横屏普遍 960dp 起，单列铺满整行、一张卡片横跨全屏，
        // 横向空间基本全废。阈值/列数见 core 的 GridSpans（TV 用 tv/values/dimens.xml 把每列
        // 最小宽覆盖成 480dp —— 10 尺 UI 的卡片要比手机大）。
        // ⚠️ 遥控焦点：只换几何排布，不写焦点代码。横向在列间移动由 FocusFinder 按坐标直接命中；
        // 走到可视区边缘时 RecyclerView 会走 `LayoutManager.onFocusSearchFailed` 把下一列滚进来
        // （LinearLayoutManager / GridLayoutManager 都实现了）。所以单列→多列**不需要**改
        // nextFocus*；但"最右列再往右是回到侧栏还是别的控件"这类边界行为，改完要在真机上按一遍。
        // ⚠️ 同理，卡片内部不要再长出第二个可聚焦控件，否则方向键会在卡片里打转（整卡一个焦点目标）。
        GridSpans.bind(binding.rvProfiles)
        binding.rvProfiles.adapter = adapter
        (binding.rvProfiles.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        updateSelectedNodeUI()

        // 遥控焦点音：与列表卡片同一套系统导航音，焦点移动时听觉也能定位
        bindFocusSound(
            binding.btnConnect, binding.btnTestLatency, binding.btnToggleSync,
            binding.btnOptionPing, binding.btnOptionDelete
        )

        binding.btnConnect.setOnClickListener { handleConnectClick() }
        binding.btnToggleSync.setOnClickListener { toggleSyncServer(!isSyncServerRunning) }
        binding.btnTestLatency.setOnClickListener { testAllProfilesLatency() }
    }

    /** 遥控焦点音：系统按「导航音/触摸反馈」设置播放；列表卡片的焦点音在 ProfileAdapterTV 里。 */
    private fun bindFocusSound(vararg views: View) {
        views.forEach { view ->
            view.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) v.playSoundEffect(SoundEffectConstants.CLICK)
            }
        }
    }

    // ── 节点列表交互 ──────────────────────────────────────────────
    private fun selectProfileLocally(profile: Profile) {
        selectProfileById(profile.id)
        Toast.makeText(this, getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
    }

    private fun selectProfileById(profileId: String?) {
        if (profileId == null) return
        SettingsManager.setSelectedProfileId(this, profileId)
        adapter.updateSelectedId(profileId)
        updateSelectedNodeUI()
    }

    /** 已连接时本地点击别的节点：确认后按远控 select_profile 的同一套时序切换重连。 */
    private fun confirmSwitchProfile(profile: Profile) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.tv_switch_confirm_title))
            .setMessage(getString(CoreR.string.tv_switch_confirm_message, profile.name))
            .setPositiveButton(getString(CoreR.string.ok)) { _, _ ->
                stopVpn()
                refreshOnMain { awaitDisconnectedThenStart(profile.id) }
            }
            .setNegativeButton(getString(CoreR.string.cancel), null)
            .show()
    }

    /**
     * stop 之后的收尾（P3 收尾异步化）可能拖过固定延迟：先等 600ms，再轮询等到
     * DISCONNECTED（上限 3s）才 start，否则 start 会撞上还没收尾完的旧会话。
     * 远控 restart_vpn / 远控 select_profile / 本地确认切换三处共用。
     */
    private suspend fun awaitDisconnectedThenStart(profileId: String?) {
        delay(600L)
        var waitedMs = 0L
        while (currentVpnState != VpnState.DISCONNECTED && waitedMs < 3_000L) {
            delay(100L)
            waitedMs += 100L
        }
        selectProfileById(profileId)
        startVpn()
    }

    private fun showProfileOptionsDialog(profile: Profile) {
        binding.tvOptionProfileName.text = profile.name

        binding.btnOptionPing.setOnClickListener {
            hideOptionsSidebar()
            testSingleProfileLatency(profile)
        }
        binding.btnOptionDelete.setOnClickListener {
            hideOptionsSidebar()
            confirmDeleteProfile(profile)
        }
        binding.sidebarDimOverlay.setOnClickListener { hideOptionsSidebar() }

        // Show with animation
        binding.sidebarDimOverlay.visibility = View.VISIBLE
        binding.sidebarDimOverlay.alpha = 0f
        binding.sidebarDimOverlay.animate().alpha(1f).setDuration(300).start()

        binding.profileOptionsSidebar.visibility = View.VISIBLE
        binding.profileOptionsSidebar.translationX =
            binding.profileOptionsSidebar.width.toFloat().takeIf { it > 0 } ?: 1000f
        binding.profileOptionsSidebar.animate()
            .translationX(0f)
            .setDuration(300)
            .withEndAction { binding.btnOptionPing.requestFocus() }
            .start()
    }

    private fun hideOptionsSidebar(): Boolean {
        if (binding.profileOptionsSidebar.visibility != View.VISIBLE) return false

        binding.sidebarDimOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            binding.sidebarDimOverlay.visibility = View.GONE
        }.start()

        binding.profileOptionsSidebar.animate()
            .translationX(binding.profileOptionsSidebar.width.toFloat())
            .setDuration(250)
            .withEndAction {
                binding.profileOptionsSidebar.visibility = View.GONE
                binding.rvProfiles.requestFocus()
            }
            .start()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode != KEYCODE_MENU && keyCode != KEYCODE_DEL && keyCode != KEYCODE_FORWARD_DEL) {
            return super.onKeyDown(keyCode, event)
        }
        val profile = profileUnderFocus() ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KEYCODE_MENU -> showProfileOptionsDialog(profile)
            else -> confirmDeleteProfile(profile)
        }
        return true
    }

    /** 焦点落在列表某张卡片上时返回对应节点；焦点不在列表上返回 null。 */
    private fun profileUnderFocus(): Profile? {
        val focused = currentFocus ?: return null
        val rv = findViewById<RecyclerView>(R.id.rvProfiles) ?: return null
        val item = rv.findContainingItemView(focused) ?: return null
        val position = rv.getChildAdapterPosition(item)
        return if (position in adapter.currentList.indices) adapter.currentList[position] else null
    }

    // ── 测速 ──────────────────────────────────────────────────────
    private fun testAllProfilesLatency() {
        // 进行中再点直接忽略：8s 超时窗口内连点会叠出两轮 "..."
        if (latencyTestJob?.isActive == true) return
        val profiles = adapter.currentList
        if (profiles.isEmpty()) {
            toastSelectNode()
            return
        }
        profiles.forEach { adapter.updateDelay(it.id, "...") }
        binding.btnTestLatency.isEnabled = false
        binding.btnTestLatency.text = getString(CoreR.string.tv_latency_testing)

        latencyTestJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = pingProfiles(profiles)
                withContext(Dispatchers.Main) {
                    profiles.forEach { p ->
                        adapter.updateDelay(p.id, results[p.id] ?: getString(CoreR.string.latency_network_error))
                    }
                    Toast.makeText(this@MainActivity, getString(CoreR.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main) {
                    UserFeedback.error(this@MainActivity, binding.root,
                            getString(CoreR.string.speed_test_error, e.message))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!(isFinishing || isDestroyed)) {
                        binding.btnTestLatency.isEnabled = true
                        binding.btnTestLatency.text = getString(CoreR.string.action_speed_test)
                    }
                }
            }
        }
    }

    private fun testSingleProfileLatency(profile: Profile) {
        adapter.updateDelay(profile.id, "...")
        lifecycleScope.launch(Dispatchers.IO) {
            val text = try {
                pingProfiles(listOf(profile))[profile.id] ?: getString(CoreR.string.latency_network_error)
            } catch (_: Exception) {
                getString(CoreR.string.latency_network_error)
            }
            withContext(Dispatchers.Main) {
                adapter.updateDelay(profile.id, text)
            }
        }
    }

    /** 全部节点 / 单节点共用的一次 pingNodes 调用（目标与超时见 companion）。 */
    private suspend fun pingProfiles(profiles: List<Profile>): Map<String, String> {
        val reqArray = JSONArray()
        profiles.forEach { profile ->
            val configJson = VpnConfigBuilder.buildMySshConfig(this@MainActivity, profile)
            reqArray.put(JSONObject().put("id", profile.id).put("config", JSONObject(configJson)))
        }
        return parsePingResults(
            StunRepository.proxy.pingNodes(reqArray.toString(), PING_TARGET, PING_TIMEOUT_MS)
        )
    }

    /**
     * 将 Go PingNodes 返回的结构化 JSON（[]PingResult）解析为 id -> 展示字符串。
     * 实现已下沉到 :core 的 [PingResults]，与手机端 / car / xr 共用同一份。
     */
    private fun parsePingResults(jsonStr: String): Map<String, String> =
        PingResults.parse(this, jsonStr)

    private fun confirmDeleteProfile(profile: Profile) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.dialog_delete_title))
            .setMessage(getString(CoreR.string.dialog_delete_message, profile.name))
            .setPositiveButton(getString(CoreR.string.delete)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ProfileManager.deleteProfile(this@MainActivity, profile)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, getString(CoreR.string.toast_deleted), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(CoreR.string.cancel), null)
            .show()
    }

    // ── 数据观察 ──────────────────────────────────────────────────
    private fun observeData() {
        val layoutTvEmpty = findViewById<View>(R.id.layoutTvEmpty)
        ProfileManager.getProfilesLiveData(this).observe(this) { profiles ->
            adapter.submitList(profiles)
            layoutTvEmpty?.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            // 右栏标题右侧的节点计数（效果图 "2 nodes"）
            binding.tvListCount.text = getString(R.string.tv_nodes_count, profiles.size)

            // 首次启动（没有任何选中记录）自动选第一个
            if (SettingsManager.getSelectedProfileId(this) == null && profiles.isNotEmpty()) {
                val firstId = profiles[0].id
                SettingsManager.setSelectedProfileId(this, firstId)
                adapter.updateSelectedId(firstId)
            }
            updateSelectedNodeUI()
        }

        StunRepository.vpnState.observe(this) { state ->
            currentVpnState = state
            updateVpnStatusUI(state)
        }

        // 引擎报错可见性：连不上/引擎异常时直接提示，避免 TV 无感知
        StunRepository.engineError.observe(this) { updateEngineErrorUI(it) }

        // 核心引擎崩溃/Panic 拦截事件弹窗展示
        StunRepository.crashEvent.observe(this) { crashLog ->
            if (!crashLog.isNullOrEmpty()) {
                showCrashDialog(crashLog, isPrevious = false)
                StunRepository.crashEvent.postValue(null)
            }
        }

        // 引擎 1Hz 回传的流量四件套共用同一个刷新函数
        listOf(StunRepository.txRate, StunRepository.rxRate, StunRepository.txTotal, StunRepository.rxTotal)
            .forEach { it.observe(this) { updateTrafficUI() } }
    }

    private fun checkPreviousCrash() {
        val prevCrash = StunRepository.checkPreviousCrash(this)
        if (!prevCrash.isNullOrEmpty()) {
            showCrashDialog(prevCrash, isPrevious = true)
        }
    }

    private fun showCrashDialog(crashLog: String, isPrevious: Boolean) {
        if (isFinishing || isDestroyed) return
        // 弹窗构造收口到 CrashHandler（core）。此前这里抄了一份：**没有复制也没有分享按钮**，
        // 日志在电视上完全出不来；字号还用 `textSize = 13f`（px 而非 sp），
        // 3x 密度屏上等效仅 4.3sp 且不跟随系统字体缩放。
        CrashHandler.showCrashDialog(
            this,
            crashLog,
            CrashHandler.CrashDialogStyle(
                titleRes = if (isPrevious) CoreR.string.crash_dialog_title_prev
                else CoreR.string.crash_dialog_title,
                // 电视 10 尺界面，13sp 更大更好读
                textSizeSp = 13f,
                horizontalPaddingDp = 24,
                verticalPaddingDp = 16,
                // Snackbar 锚点：电视上用 activity 的内容视图
                feedbackAnchor = binding.root,
            ),
        )
    }

    // ── 状态与流量展示 ────────────────────────────────────────────
    private fun updateVpnStatusUI(state: VpnState) {
        if (isFinishing || isDestroyed) return
        when (state) {
            VpnState.CONNECTED -> {
                showVpnStatusLine(CoreR.string.status_connected, CoreR.color.status_connected, CoreR.string.disconnect)
                fetchPublicIp()
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                hidePublicIp()
                // 此时点击 = stopVpn（取消连接），按钮语义是「断开」而不是「关闭」
                showVpnStatusLine(CoreR.string.main_connecting, CoreR.color.status_connecting, CoreR.string.disconnect)
            }
            else -> {
                hidePublicIp()
                showVpnStatusLine(CoreR.string.status_disconnected, CoreR.color.status_disconnected, CoreR.string.connect)
            }
        }
    }

    private fun showVpnStatusLine(statusRes: Int, colorRes: Int, connectButtonRes: Int) {
        val color = getColor(colorRes)
        binding.tvVpnStatus.text = getString(statusRes)
        binding.tvVpnStatus.setTextColor(color)
        binding.dotVpnStatus.backgroundTintList = ColorStateList.valueOf(color)
        binding.btnConnect.text = getString(connectButtonRes)
    }

    private fun hidePublicIp() {
        publicIpJob?.cancel()
        binding.tvPublicIp.visibility = View.GONE
        // 进程级状态源跟着清：否则断开后远端面板会一直挂着上个隧道留下的出口 IP
        TvStatusSource.lastPublicIp = null
    }

    private fun fetchPublicIp() {
        publicIpJob?.cancel()
        binding.tvPublicIp.visibility = View.VISIBLE
        binding.tvPublicIp.text = "🌐 ..."

        publicIpJob = lifecycleScope.launch(Dispatchers.IO) {
            // 与手机端共用 :core 的 ExitIpProbe（三路 HTTPS provider，按序降级）；
            // tproxy 模式下探测显式走本地 SOCKS5 —— App 自身流量被 uid 自旁路放行，
            // 直连探测只会拿到本地出口（contextAwareFetch 见 ExitIpProbe）
            val exit = ExitIpProbe(fetch = ExitIpProbe.contextAwareFetch(this@MainActivity)).run()

            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                when {
                    // 探回来的时候状态已经变了，别留着旧的出口 IP
                    currentVpnState != VpnState.CONNECTED -> {
                        binding.tvPublicIp.visibility = View.GONE
                        TvStatusSource.lastPublicIp = null
                    }
                    // 三家全失败时不要停在 🌐 ... 的假加载态
                    exit == null -> {
                        binding.tvPublicIp.visibility = View.GONE
                        TvStatusSource.lastPublicIp = null
                    }
                    else -> {
                        binding.tvPublicIp.visibility = View.VISIBLE
                        binding.tvPublicIp.text = "🌐 ${exit.displayText}"
                        // 同步给进程级状态源：远端面板读它，UI 关掉后不能丢
                        TvStatusSource.lastPublicIp = exit.displayText
                    }
                }
            }
        }
    }

    /** 引擎报错提示：把 engineError 直接显示到左栏，并仅在值变化时 Toast（避免刷屏）。 */
    private fun updateEngineErrorUI(err: String?) {
        if (isFinishing || isDestroyed) return
        if (err.isNullOrEmpty()) {
            binding.tvEngineError.visibility = View.GONE
            lastEngineError = null
        } else {
            binding.tvEngineError.visibility = View.VISIBLE
            binding.tvEngineError.text = "⚠ $err"
            if (err != lastEngineError) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                lastEngineError = err
            }
        }
    }

    private fun updateSelectedNodeUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            val selected = ProfileManager.getSelectedProfile(this@MainActivity)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                binding.tvSelectedNodeTitle.text =
                    if (selected.id.isNotEmpty() && selected.name.isNotEmpty())
                        "${selected.name} · ${profileTypeLabel(selected)}"
                    else getString(CoreR.string.tv_select_node_hint)
            }
        }
    }

    private fun updateTrafficUI() {
        if (isFinishing || isDestroyed) return
        val tv = tvTrafficView ?: findViewById<TextView>(R.id.tvTraffic).also { tvTrafficView = it }
        val tvTotal = tvTrafficTotalView ?: findViewById<TextView>(R.id.tvTrafficTotal).also { tvTrafficTotalView = it }
        tv?.text = getString(
            CoreR.string.tv_traffic_format,
            AppUtils.formatSpeed(StunRepository.txRate.value ?: 0L),
            AppUtils.formatSpeed(StunRepository.rxRate.value ?: 0L)
        )
        val totalBytes = (StunRepository.txTotal.value ?: 0L) + (StunRepository.rxTotal.value ?: 0L)
        tvTotal?.text = "Σ ${AppUtils.formatBytes(totalBytes)}"
    }

    private fun toastSelectNode() {
        Toast.makeText(this, getString(CoreR.string.tv_select_node_hint), Toast.LENGTH_SHORT).show()
    }

    // ── VPN / tproxy 启停 ─────────────────────────────────────────
    /**
     * 已连接或在连接途中（含自动重连）。这些状态下按钮点一下是「断开」，
     * 远端发 start 则应当空操作，切换节点需要先显式确认。
     */
    private fun isVpnBusy(): Boolean =
        currentVpnState == VpnState.CONNECTED ||
        currentVpnState == VpnState.CONNECTING ||
        currentVpnState == VpnState.RECONNECTING

    /** 屏幕上的大按钮：切换语义（busy → 断开，空闲 → 授权/Root 检查后连接）。 */
    private fun handleConnectClick() {
        if (isVpnBusy()) {
            stopVpn()
            return
        }
        if (isTProxyMode()) {
            // tproxy 没有 VpnService 授权这一说：prepare() 弹出的「设置虚拟专用网络」
            // 对透明代理毫无意义，还会让这次点击停在那一页。它的门槛是 Root。
            // isRoot() 会走 su 握手（最坏 90s 超时），不能在主线程调。
            lifecycleScope.launch {
                if (!withContext(Dispatchers.IO) { RootShell.isRoot() }) {
                    Toast.makeText(this@MainActivity,
                            getString(CoreR.string.error_root_required), Toast.LENGTH_LONG).show()
                    return@launch
                }
                startVpn()
            }
        } else {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) vpnPermissionLauncher.launch(permissionIntent)
            else startVpn()
        }
    }

    /**
     * 当前是透明代理还是 VPN。TV 自己没有模式开关，值来自共享库（与 WebUI / MCP / 手机端
     * 同一份 SharedPreferences），未设置时默认 VPN —— 与改动前的行为一致。
     */
    private fun isTProxyMode(): Boolean =
        SettingsManager.getServiceMode(this) == SettingsManager.SERVICE_MODE_TPROXY

    private fun startVpn() {
        if (SettingsManager.getSelectedProfileId(this) == null) {
            toastSelectNode()
            return
        }
        startService(vpnIntent(MyTransparentProxyService.ACTION_START, MyVpnService.ACTION_START))
    }

    private fun stopVpn() {
        startService(vpnIntent(MyTransparentProxyService.ACTION_STOP, MyVpnService.ACTION_STOP))
    }

    /** tproxy 与 VPN 是两套前台服务，start/stop 的 action 也各是各的，只能成套选。 */
    private fun vpnIntent(tproxyAction: String, vpnAction: String): Intent {
        val tproxy = isTProxyMode()
        return Intent(this, if (tproxy) MyTransparentProxyService::class.java else MyVpnService::class.java)
            .apply { action = if (tproxy) tproxyAction else vpnAction }
    }

    // ── 同步服务 ──────────────────────────────────────────────────
    private fun toggleSyncServer(enable: Boolean) {
        // 落盘：TVApp 冷启动与保活唤回都读它决定是否重新拉起同步服务，
        // 用户关掉后重启不会再被自动打开（之前只存在 Activity 成员变量里，重启即失效）。
        SettingsManager.saveRemoteSyncEnabled(this, enable)
        if (enable) {
            RemoteSyncManager.startServer(this)
            startBluetoothSyncServer()
            binding.tvSyncStatus.text = getString(CoreR.string.tv_sync_server_on, "StunTV")
            binding.btnToggleSync.text = getString(CoreR.string.tv_stop_sync)
            isSyncServerRunning = true
        } else {
            RemoteSyncManager.stopServer()
            BluetoothSyncManager.stopServer()
            binding.tvSyncStatus.text = getString(CoreR.string.tv_sync_server_off)
            binding.btnToggleSync.text = getString(CoreR.string.tv_start_sync)
            isSyncServerRunning = false
        }
    }

    /**
     * Starts the Bluetooth sync server, prompting for BLUETOOTH_CONNECT first when it has not been
     * granted yet. On API 31+ an RFCOMM server socket cannot be created without that runtime grant:
     * the framework reads the local adapter address inside
     * [BluetoothSyncManager.startServer] and throws SecurityException otherwise. The Wi-Fi (HTTP)
     * sync server started alongside it is unaffected.
     *
     * [prompt] = false 时只静默尝试，不弹权限框 —— 供 onResume 反复调用。
     */
    private fun startBluetoothSyncServer(prompt: Boolean = true) {
        if (BluetoothSyncManager.hasBluetoothConnectPermission(this)) {
            BluetoothSyncManager.startServer(this)
        } else if (prompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /**
     * 回到前台时把两个监听面补一遍。两个 `startServer` 都是 CAS 幂等的，已经在跑就什么都不做；
     * 这里补的是两条「只在 onCreate 试过一次」的失败路径：
     *  ① BLUETOOTH_CONNECT 先被拒绝、之后又在系统设置里放开 —— 之前没有任何东西会重新挂 RFCOMM
     *     server，只能杀掉 App 重来；
     *  ② 用户把系统蓝牙关掉过又打开 —— startServer 当时以 "bluetooth disabled" 收场，之后无人重试。
     */
    override fun onResume() {
        super.onResume()
        if (!SettingsManager.isRemoteSyncEnabled(this)) return
        // prompt=false：只静默尝试，不在每次切回前台时弹权限框。真要授权走同步开关
        // （toggleSyncServer → startBluetoothSyncServer）或系统设置。
        RemoteSyncManager.startServer(this)
        startBluetoothSyncServer(prompt = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        publicIpJob?.cancel()
        clearRemoteCallbacks()
    }

    /**
     * 2026-10：不再 stop WebServer / 同步服务 —— 它们是进程级常驻设施
     *（KeepAliveManager.isWebConsoleEverStarted 的语义就是「起过就要一直活着」），
     * 用户按 BACK 退出界面后 WebUI/手机远控仍需可访问；进程由前台保活服务
     *（WebConsoleKeepAliveService）撑着，不会被 cached 裁剪杀掉。
     * 这里只摘除**捕获了 Activity** 的回调引用避免泄漏；界面重建时 onCreate 会原样重新注册，
     * startServer/start 全部幂等，不会重复监听端口。
     *
     * 状态源（tvStatusProvider）不摘：它由 [TvStatusSource] 在进程级注册，不持有任何 Activity 引用，
     * 摘掉反而会让手机收到「在线但无节点」的兜底状态。
     */
    private fun clearRemoteCallbacks() {
        RemoteSyncManager.onProfilePushRequested = null
        RemoteSyncManager.onRemoteControlRequested = null
        BluetoothSyncManager.onRemoteControlRequested = null
        WebServer.onVpnControlRequested = null
        WebServer.onProfileSelected = null
        WebServer.onProfileDeleted = null
        WebServer.onProfileAdded = null
        WebServer.onAuthConfigChanged = null
    }
}
