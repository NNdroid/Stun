package app.fjj.stun.car

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import app.fjj.stun.car.databinding.ActivityCarMainBinding
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.remote.BluetoothSyncManager
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.service.VpnControls
import app.fjj.stun.util.AppUtils
import app.fjj.stun.util.CrashHandler
import app.fjj.stun.util.GridSpans
import app.fjj.stun.util.PingResults
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import app.fjj.stun.ui.UserFeedback

class CarMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCarMainBinding
    private lateinit var adapter: ProfileAdapterCar
    private var isVpnRunning = false
    private var isVpnTransitioning = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkAndRequestNotificationPermission()
        } else {
            UserFeedback.error(this@CarMainActivity, binding.root,
                    getString(CoreR.string.vpn_permission_denied))
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSelectedService()
        } else {
            UserFeedback.error(this@CarMainActivity, binding.root,
                    getString(CoreR.string.notification_permission_required))
        }
    }

    /**
     * Android 12+ gates RFCOMM server creation behind the runtime BLUETOOTH_CONNECT grant, so the
     * Bluetooth sync server can only come up after this returns. See [startBluetoothSyncServer].
     */
    private val btConnectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            BluetoothSyncManager.startServer(this)
        } else {
            StunLogger.w("CarMainActivity", "BLUETOOTH_CONNECT denied; phone-to-car Bluetooth sync disabled")
        }
        updateBtBadge()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCarMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        observeData()
        loadProfiles()
        setupBluetoothRemoteCallbacks()
        startBluetoothSyncServer()
        updateBtBadge()
    }

    /** BT 徽标反映服务器真实状态（原来硬编码 "ON"，BT 被拒/没开时也在撒谎）。 */
    private fun updateBtBadge() {
        val on = BluetoothSyncManager.isRunning()
        binding.tvBtStatusBadge.text = getString(
            if (on) CoreR.string.car_bt_sync_on else CoreR.string.car_bt_sync_off
        )
        binding.tvBtStatusBadge.alpha = if (on) 1f else 0.5f
    }

    /**
     * 手机端「远程控制」蓝牙面板的控制入口。
     *
     * 与 TV 的 `RemoteSyncManager` 回调同构：手机端解析的是同一份 [app.fjj.stun.remote.TvStatusResponse]，
     * 控制动作（start/stop/restart/select_profile）走同一套启停语义 —— start 会经由
     * [startSelectedService] 弹 VPN 授权/通知权限，而不是像旧 BT `toggle_vpn` 那样绕过一切直接拉服务。
     *
     * 只有控制回调注册在这里：它必须引用 Activity（loadProfiles / launcher）。
     * 状态源 [app.fjj.stun.remote.TvStatusSource] 是纯数据读取，已在 `CarApp.onCreate` 进程级注册，
     * 不挂在 Activity 上 —— 原先挂在这里时界面一销毁就被置 null，手机只看到空状态。
     */
    private fun setupBluetoothRemoteCallbacks() {
        BluetoothSyncManager.onRemoteControlRequested = { action, profileId ->
            withContext(Dispatchers.Main) {
                when (action) {
                    "start_vpn" -> {
                        if (profileId != null) {
                            SettingsManager.setSelectedProfileId(this@CarMainActivity, profileId)
                            loadProfiles()
                        }
                        // 正在连接/重连时防抖（与本地按钮同一套状态判定）
                        if (!isVpnRunning && !isVpnTransitioning) {
                            startSelectedService()
                        }
                        true
                    }
                    "stop_vpn" -> {
                        VpnControls.stop(this@CarMainActivity)
                        true
                    }
                    "restart_vpn" -> {
                        VpnControls.stop(this@CarMainActivity)
                        lifecycleScope.launch(Dispatchers.Main) {
                            kotlinx.coroutines.delay(600L)
                            // P3 收尾异步化后 DISCONNECTING 可能拖过 600ms：等过渡态退出（上限 3s）
                            // 再启动，否则 start 会撞上还在收尾的服务（本地按钮路径有防抖，这里绕过了它）。
                            var waitedMs = 0L
                            while (isVpnTransitioning && waitedMs < 3_000L) {
                                kotlinx.coroutines.delay(100L)
                                waitedMs += 100L
                            }
                            if (profileId != null) {
                                SettingsManager.setSelectedProfileId(this@CarMainActivity, profileId)
                                loadProfiles()
                            }
                            startSelectedService()
                        }
                        true
                    }
                    // 与本地点击同一约束：连接中不允许只切节点（切换节点请用「选择并启动」）
                    "select_profile" -> {
                        if (profileId == null || isVpnRunning || isVpnTransitioning) {
                            false
                        } else {
                            SettingsManager.setSelectedProfileId(this@CarMainActivity, profileId)
                            loadProfiles()
                            true
                        }
                    }
                    else -> false
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 控制回调引用了 Activity（loadProfiles / launcher），销毁时必须摘除，防泄漏。
        // 状态源（tvStatusProvider）不摘：它已在 CarApp 进程级注册、不持有任何 Activity 引用。
        BluetoothSyncManager.onRemoteControlRequested = null
        // 控制回调摘除后，远端 start_vpn 会落到 core 的 startOrStopService 兜底 —— 那条路
        // 不经过 VpnService.prepare，会让服务空转重连。停掉服务器把这条路彻底关死；
        // 用户再打开本页时会重新拉起。
        BluetoothSyncManager.stopServer()
        updateBtBadge()
    }

    /**
     * Starts the Bluetooth sync server, prompting for BLUETOOTH_CONNECT first when it has not been
     * granted yet. The framework reads the local adapter address while creating the RFCOMM server
     * socket, which throws SecurityException on API 31+ without the runtime grant.
     */
    private fun startBluetoothSyncServer() {
        if (BluetoothSyncManager.hasBluetoothConnectPermission(this)) {
            BluetoothSyncManager.startServer(this)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun setupRecyclerView() {
        adapter = ProfileAdapterCar(
            onProfileClick = { profile ->
                if (!isVpnRunning && !isVpnTransitioning) {
                    SettingsManager.setSelectedProfileId(this, profile.id)
                    loadProfiles()
                    Toast.makeText(this, getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(CoreR.string.main_profile_switch_disabled), Toast.LENGTH_SHORT).show()
                }
            }
        )

        // 车机横屏很宽（常见 1280dp 起），列表切成一行多列才不浪费横向空间。规则见 core 的 GridSpans。
        // ⚠️ 列数按 RecyclerView 自己的宽度算 —— 这个列表外面还包着 12dp padding 的卡片，
        // 按屏宽算会偏多。
        GridSpans.bind(binding.rvCarNodes)
        binding.rvCarNodes.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnCarPower.setOnClickListener {
            handleStartStop()
        }

        binding.btnCarPingAll.setOnClickListener {
            pingAllNodes()
        }
    }

    private fun observeData() {
        StunRepository.vpnState.observe(this) { state ->
            updateVpnUi(state)
        }

        StunRepository.txRate.observe(this) { rate ->
            if (isVpnRunning) {
                binding.tvCarUpSpeed.text = getString(CoreR.string.traffic_up_format, AppUtils.formatBytes(rate))
            }
        }

        StunRepository.rxRate.observe(this) { rate ->
            if (isVpnRunning) {
                binding.tvCarDownSpeed.text = getString(CoreR.string.traffic_down_format, AppUtils.formatBytes(rate))
            }
        }

        StunRepository.engineError.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Snackbar.make(binding.carRoot, msg, Snackbar.LENGTH_LONG).show()
                StunRepository.engineError.postValue(null)
            }
        }

        // Go 引擎 Panic：手机/TV 端都有弹窗，车机端不能只留在旧界面上
        StunRepository.crashEvent.observe(this) { crashLog ->
            if (!crashLog.isNullOrEmpty()) {
                showCrashDialog(crashLog)
                StunRepository.crashEvent.postValue(null)
            }
        }
    }

    private fun showCrashDialog(crashLog: String) {
        if (isFinishing || isDestroyed) return
        // 弹窗构造收口到 CrashHandler（core）。此前这里抄了一份：**没有复制也没有分享按钮**
        // （车机崩了日志完全出不来），字号还用 `textSize = 13f`（px 而非 sp）。
        CrashHandler.showCrashDialog(
            this,
            crashLog,
            CrashHandler.CrashDialogStyle(
                titleRes = CoreR.string.crash_dialog_title,
                textSizeSp = 13f,
                horizontalPaddingDp = 24,
                verticalPaddingDp = 16,
                feedbackAnchor = binding.root,
            ),
        )
    }

    private fun loadProfiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@CarMainActivity)
            val selectedId = SettingsManager.getSelectedProfileId(this@CarMainActivity)
            withContext(Dispatchers.Main) {
                adapter.updateProfiles(profiles, selectedId)
            }
        }
    }

    private fun updateVpnUi(state: VpnState?) {
        when (state) {
            VpnState.CONNECTED -> {
                isVpnRunning = true
                isVpnTransitioning = false
                binding.btnCarPower.isEnabled = true
                binding.ivCarPowerIcon.setImageResource(CoreR.drawable.ic_pause)
                binding.tvCarPowerLabel.text = getString(CoreR.string.car_power_button_disconnect)
                binding.carStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
                binding.tvCarStatus.text = getString(CoreR.string.car_status_protected)
                binding.layoutCarTraffic.visibility = View.VISIBLE
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                isVpnRunning = false
                isVpnTransitioning = true
                binding.btnCarPower.isEnabled = false
                binding.ivCarPowerIcon.setImageResource(CoreR.drawable.ic_sync)
                binding.tvCarPowerLabel.text = getString(CoreR.string.main_connecting)
                binding.carStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
                binding.tvCarStatus.text = getString(CoreR.string.main_connecting)
                binding.layoutCarTraffic.visibility = View.GONE
            }
            else -> {
                isVpnRunning = false
                isVpnTransitioning = false
                binding.btnCarPower.isEnabled = true
                binding.ivCarPowerIcon.setImageResource(CoreR.drawable.ic_play)
                binding.tvCarPowerLabel.text = getString(CoreR.string.car_power_button_connect)
                binding.carStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
                binding.tvCarStatus.text = getString(CoreR.string.car_status_unprotected)
                binding.layoutCarTraffic.visibility = View.GONE
            }
        }
    }

    private fun handleStartStop() =
        VpnControls.handleStartStop(this) { checkAndRequestNotificationPermission() }

    private fun checkAndRequestNotificationPermission() {
        if (VpnControls.needsNotificationPermission(this)) {
            notificationPermissionLauncher.launch(VpnControls.notificationPermission)
        } else {
            startSelectedService()
        }
    }

    private fun startSelectedService() =
        VpnControls.start(this) { vpnLauncher.launch(it) }

    private var latencyTestJob: kotlinx.coroutines.Job? = null

    private fun pingAllNodes() {
        // 进行中再点直接忽略：8s 窗口内连点会叠出两轮并发 pingNodes
        if (latencyTestJob?.isActive == true) return
        latencyTestJob = lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@CarMainActivity)
            if (profiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CarMainActivity, getString(CoreR.string.tv_select_node_hint), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@CarMainActivity, getString(CoreR.string.speed_test_started), Toast.LENGTH_SHORT).show()
                binding.btnCarPingAll.isEnabled = false
            }

            try {
                val reqArray = JSONArray()
                profiles.forEach { p ->
                    val configJson = VpnConfigBuilder.buildMySshConfig(this@CarMainActivity, p)
                    reqArray.put(JSONObject().put("id", p.id).put("config", JSONObject(configJson)))
                }
                val jsonResStr = StunRepository.proxy.pingNodes(reqArray.toString(), "http://cp.cloudflare.com/generate_204", 8000L)
                val results = PingResults.parse(this@CarMainActivity, jsonResStr)

                withContext(Dispatchers.Main) {
                    // 批量回填：整轮测速只刷一次。原来是逐个 updateDelay ⇒ N 个节点触发
                    // N 次 notifyDataSetChanged，节点多时在大屏上肉眼可见地卡。
                    val filled = profiles.associate { p ->
                        p.id to (results[p.id] ?: getString(CoreR.string.latency_network_error))
                    }
                    adapter.updateDelays(filled)
                    Toast.makeText(this@CarMainActivity, getString(CoreR.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    UserFeedback.error(this@CarMainActivity, binding.root,
                    getString(CoreR.string.speed_test_error, e.message))
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!(isFinishing || isDestroyed)) {
                        binding.btnCarPingAll.isEnabled = true
                    }
                }
            }
        }
    }
}

