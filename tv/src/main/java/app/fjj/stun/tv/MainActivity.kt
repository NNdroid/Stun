package app.fjj.stun.tv

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.remote.BluetoothSyncManager
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.remote.WebServer
import app.fjj.stun.repo.*
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.util.ExitIpProbe
import app.fjj.stun.util.PingResults
import com.google.android.material.button.MaterialButton
import app.fjj.stun.core.R as CoreR
import androidx.activity.OnBackPressedCallback
import org.json.JSONArray
import org.json.JSONObject

import app.fjj.stun.tv.databinding.ActivityMainBinding

class MainActivity : FragmentActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isSyncServerRunning = false
    private lateinit var adapter: ProfileAdapterTV
    private var currentVpnState = VpnState.DISCONNECTED

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpn()
        } else {
            Toast.makeText(this, getString(CoreR.string.tv_vpn_permission_denied), Toast.LENGTH_SHORT).show()
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
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            StunLogger.e("MainActivity", "Failed to inflate layout", e)
            // Fallback for some broken TV ROMs
            setContentView(R.layout.activity_main)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            app.fjj.stun.repo.ProfileManager.migratePlaintextProfiles(this@MainActivity)
        }

        setupUI()
        setupRemoteCallbacks()
        observeData()
        checkPreviousCrash()

        // Handle back button for sidebar
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!hideOptionsSidebar()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
        
        // Auto-start sync server
        toggleSyncServer(true)

        // Show crash report dialog if previous run crashed
        app.fjj.stun.util.CrashHandler.showCrashDialogIfAny(this)

        // Start WebServer and show URL + QR Code on screen
        lifecycleScope.launch(Dispatchers.IO) {
            val port = WebServer.start(this@MainActivity)
            if (port > 0) {
                val url = WebServer.getEffectiveUrl(this@MainActivity, port)
                updateWebConsoleUI(url)
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
        lifecycleScope.launch(Dispatchers.IO) {
            val qrBitmap = generateQrBitmap(url, 256)
            launch(Dispatchers.Main) {
                binding.tvWebConsole.text = url
                if (qrBitmap != null) {
                    binding.ivWebQrCode.setImageBitmap(qrBitmap)
                }
            }
        }
    }

    private fun generateQrBitmap(content: String, size: Int): android.graphics.Bitmap? {
        return try {
            val barcodeEncoder = com.journeyapps.barcodescanner.BarcodeEncoder()
            barcodeEncoder.encodeBitmap(content, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        } catch (_: Exception) {
            null
        }
    }

    private fun setupRemoteCallbacks() {
        // Status provider for remote queries
        RemoteSyncManager.tvStatusProvider = {
            try {
                val selectedProfile = ProfileManager.getSelectedProfile(this)
                val allProfiles = try {
                    ProfileManager.getProfiles(this).map {
                        app.fjj.stun.remote.TvProfileSummary(it.id, it.name, it.tunnelType)
                    }
                } catch (_: Exception) { emptyList() }
                val pubIp = if (binding.tvPublicIp.visibility == View.VISIBLE) binding.tvPublicIp.text.toString() else null
                val typeStr = when (selectedProfile.tunnelType) {
                    Profile.TUNNEL_TYPE_UDP_CUSTOM -> "UDP CUSTOM (${selectedProfile.udpCustomMagic.ifBlank { "UDPC" }})"
                    Profile.TUNNEL_TYPE_DNS -> "DNS"
                    Profile.TUNNEL_TYPE_KCP -> "KCP"
                    else -> selectedProfile.tunnelType.uppercase()
                }
                app.fjj.stun.remote.TvStatusResponse(
                    vpnState = (StunRepository.vpnState.value ?: currentVpnState).name,
                    currentProfileName = selectedProfile.name.ifBlank { null },
                    currentProfileId = SettingsManager.getSelectedProfileId(this),
                    currentProfileType = if (selectedProfile.name.isNotBlank()) typeStr else null,
                    currentProfileServer = if (selectedProfile.sshAddr.isNotBlank()) selectedProfile.sshAddr else null,
                    profileCount = allProfiles.size,
                    deviceName = android.os.Build.MODEL,
                    publicIp = pubIp,
                    txRate = StunRepository.txRate.value ?: 0L,
                    rxRate = StunRepository.rxRate.value ?: 0L,
                    txTotal = StunRepository.txTotal.value ?: 0L,
                    rxTotal = StunRepository.rxTotal.value ?: 0L,
                    profiles = allProfiles
                )
            } catch (e: Exception) {
                app.fjj.stun.remote.TvStatusResponse(
                    vpnState = (StunRepository.vpnState.value ?: currentVpnState).name,
                    currentProfileName = null,
                    currentProfileId = SettingsManager.getSelectedProfileId(this),
                    profileCount = 0,
                    deviceName = android.os.Build.MODEL
                )
            }
        }

        // Push confirmation dialog callback
        RemoteSyncManager.onProfilePushRequested = { senderIp, profile ->
            val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
            withContext(Dispatchers.Main) {
                showPushConfirmDialog(senderIp, profile, deferred)
            }
            try {
                kotlinx.coroutines.withTimeout(30000L) {
                    deferred.await()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                false
            }
        }

        // Remote control callback (Connect, Disconnect, Restart, Select Profile)
        RemoteSyncManager.onRemoteControlRequested = { action, profileId ->
            withContext(Dispatchers.Main) {
                when (action) {
                    "start_vpn" -> {
                        if (profileId != null) {
                            SettingsManager.setSelectedProfileId(this@MainActivity, profileId)
                            adapter.updateSelectedId(profileId)
                            updateSelectedNodeUI()
                        }
                        if (currentVpnState != VpnState.CONNECTED && currentVpnState != VpnState.CONNECTING) {
                            startVpn()
                        }
                        true
                    }
                    "stop_vpn" -> {
                        stopVpn()
                        true
                    }
                    "restart_vpn" -> {
                        stopVpn()
                        lifecycleScope.launch(Dispatchers.Main) {
                            awaitDisconnectedThenStart(profileId)
                        }
                        true
                    }
                    "select_profile" -> {
                        if (profileId != null) {
                            SettingsManager.setSelectedProfileId(this@MainActivity, profileId)
                            adapter.updateSelectedId(profileId)
                            updateSelectedNodeUI()
                            if (currentVpnState == VpnState.CONNECTED || currentVpnState == VpnState.CONNECTING) {
                                stopVpn()
                                lifecycleScope.launch(Dispatchers.Main) {
                                    awaitDisconnectedThenStart(null)
                                }
                            }
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }

        // 蓝牙通道与 HTTP 通道共用同一套状态来源与控制逻辑：
        // 手机端对蓝牙设备的「远程控制」面板解析的就是这份 TvStatusResponse，
        // 控制动作（start/stop/restart/select_profile）也走同一个回调，TV 侧 UI 联动保持一致。
        BluetoothSyncManager.tvStatusProvider = RemoteSyncManager.tvStatusProvider
        BluetoothSyncManager.onRemoteControlRequested = RemoteSyncManager.onRemoteControlRequested

        // Web Console callbacks
        WebServer.onVpnControlRequested = { action, profileId ->
            withContext(Dispatchers.Main) {
                when (action) {
                    "start_vpn" -> {
                        if (profileId != null) {
                            SettingsManager.setSelectedProfileId(this@MainActivity, profileId)
                            adapter.updateSelectedId(profileId)
                            updateSelectedNodeUI()
                        }
                        handleConnectClick()
                        true
                    }
                    "stop_vpn" -> {
                        stopVpn()
                        true
                    }
                    else -> false
                }
            }
        }

        WebServer.onProfileSelected = { profileId ->
            lifecycleScope.launch(Dispatchers.Main) {
                adapter.updateSelectedId(profileId)
                updateSelectedNodeUI()
            }
        }

        WebServer.onProfileDeleted = {
            lifecycleScope.launch(Dispatchers.Main) {
                updateSelectedNodeUI()
            }
        }

        WebServer.onProfileAdded = {
            lifecycleScope.launch(Dispatchers.Main) {
                updateSelectedNodeUI()
            }
        }

        WebServer.onAuthConfigChanged = { newUrl ->
            updateWebConsoleUI(newUrl)
        }
    }

    private fun showPushConfirmDialog(
        senderIp: String,
        profile: Profile,
        deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
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
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
            .setOnCancelListener {
                deferred.complete(false)
            }
            .create()

        dialog.show()
    }

    private fun setupUI() {
        val selectedId = SettingsManager.getSelectedProfileId(this)
        adapter = ProfileAdapterTV(
            selectedId,
            onProfileClick = { profile ->
                // 已连接/连接中时点别的节点：先确认再「切换 + 重连」，
                // 与远控 select_profile / WebUI 的行为对齐，不再只静默改选中
                if ((currentVpnState == VpnState.CONNECTED || currentVpnState == VpnState.CONNECTING) &&
                    profile.id != SettingsManager.getSelectedProfileId(this)
                ) {
                    confirmSwitchProfile(profile)
                } else {
                    selectProfileLocally(profile)
                }
            },
            onProfileLongClick = { profile ->
                showProfileOptionsDialog(profile)
            }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter
        (binding.rvProfiles.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        updateSelectedNodeUI()

        // 遥控焦点音：与列表卡片同一套系统导航音，焦点移动时听觉也能定位
        bindFocusSound(
            binding.btnConnect, binding.btnTestLatency, binding.btnToggleSync,
            binding.btnOptionPing, binding.btnOptionDelete
        )

        binding.btnConnect.setOnClickListener {
            handleConnectClick()
        }

        binding.btnToggleSync.setOnClickListener {
            toggleSyncServer(!isSyncServerRunning)
        }

        binding.btnTestLatency.setOnClickListener {
            testAllProfilesLatency()
        }
    }

    /** 遥控焦点音：系统按「导航音/触摸反馈」设置播放；列表卡片的焦点音在 ProfileAdapterTV 里。 */
    private fun bindFocusSound(vararg views: View) {
        views.forEach { v ->
            v.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
            }
        }
    }

    private fun selectProfileLocally(profile: Profile) {
        SettingsManager.setSelectedProfileId(this, profile.id)
        adapter.updateSelectedId(profile.id)
        updateSelectedNodeUI()
        Toast.makeText(this, getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
    }

    /** 已连接时本地点击别的节点：确认后按远控 select_profile 的同一套时序切换重连。 */
    private fun confirmSwitchProfile(profile: Profile) {
        if (isFinishing || isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.tv_switch_confirm_title))
            .setMessage(getString(CoreR.string.tv_switch_confirm_message, profile.name))
            .setPositiveButton(getString(CoreR.string.ok)) { _, _ ->
                stopVpn()
                lifecycleScope.launch(Dispatchers.Main) {
                    awaitDisconnectedThenStart(profile.id)
                }
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
        kotlinx.coroutines.delay(600L)
        var waitedMs = 0L
        while (currentVpnState != VpnState.DISCONNECTED) {
            if (waitedMs >= 3_000L) break
            kotlinx.coroutines.delay(100L)
            waitedMs += 100L
        }
        if (profileId != null) {
            SettingsManager.setSelectedProfileId(this, profileId)
            adapter.updateSelectedId(profileId)
            updateSelectedNodeUI()
        }
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

        binding.sidebarDimOverlay.setOnClickListener {
            hideOptionsSidebar()
        }

        // Show with animation
        binding.sidebarDimOverlay.visibility = View.VISIBLE
        binding.sidebarDimOverlay.alpha = 0f
        binding.sidebarDimOverlay.animate().alpha(1f).setDuration(300).start()

        binding.profileOptionsSidebar.visibility = View.VISIBLE
        binding.profileOptionsSidebar.translationX = binding.profileOptionsSidebar.width.toFloat().takeIf { it > 0 } ?: 1000f
        binding.profileOptionsSidebar.animate()
            .translationX(0f)
            .setDuration(300)
            .withEndAction {
                binding.btnOptionPing.requestFocus()
            }
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

    private fun testSingleProfileLatency(profile: Profile) {
        adapter.updateDelay(profile.id, "...")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reqArray = JSONArray()
                val configJson = VpnConfigBuilder.buildMySshConfig(this@MainActivity, profile, 1080, 53)
                reqArray.put(JSONObject().put("id", profile.id).put("config", JSONObject(configJson)))
                val jsonResStr = StunRepository.proxy.pingNodes(
                    reqArray.toString(),
                    "http://cp.cloudflare.com/generate_204",
                    8000L
                )
                val results = parsePingResults(jsonResStr)
                withContext(Dispatchers.Main) {
                    adapter.updateDelay(profile.id, results[profile.id] ?: getString(CoreR.string.latency_network_error))
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    adapter.updateDelay(profile.id, getString(CoreR.string.latency_network_error))
                }
            }
        }
    }

    private fun confirmDeleteProfile(profile: Profile) {
        if (isFinishing || isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
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
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            val focused = currentFocus
            if (focused != null) {
                val rv = findViewById<RecyclerView>(R.id.rvProfiles) ?: return super.onKeyDown(keyCode, event)
                val containingItem = rv.findContainingItemView(focused)
                if (containingItem != null) {
                    val pos = rv.getChildAdapterPosition(containingItem)
                    if (pos != RecyclerView.NO_POSITION && pos < adapter.currentList.size) {
                        val profile = adapter.currentList[pos]
                        if (keyCode == KeyEvent.KEYCODE_MENU) {
                            showProfileOptionsDialog(profile)
                        } else {
                            confirmDeleteProfile(profile)
                        }
                        return true
                    }
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun observeData() {
        val layoutTvEmpty = findViewById<View>(R.id.layoutTvEmpty)
        ProfileManager.getProfilesLiveData(this).observe(this) { profiles ->
            adapter.submitList(profiles)
            layoutTvEmpty?.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            // 右栏标题右侧的节点计数（效果图 "2 nodes"）
            binding.tvListCount.text = getString(R.string.tv_nodes_count, profiles.size)
            
            // Auto-select if nothing selected
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
        StunRepository.engineError.observe(this) { err ->
            updateEngineErrorUI(err)
        }

        // 🌟 核心引擎崩溃/Panic 拦截事件弹窗展示
        StunRepository.crashEvent.observe(this) { crashLog ->
            if (!crashLog.isNullOrEmpty()) {
                showCrashDialog(crashLog, isPrevious = false)
                StunRepository.crashEvent.postValue(null)
            }
        }

        // 实时流量速率（上行/下行），来自引擎 1Hz 回调
        StunRepository.txRate.observe(this) { updateTrafficUI() }
        StunRepository.rxRate.observe(this) { updateTrafficUI() }
        StunRepository.txTotal.observe(this) { updateTrafficUI() }
        StunRepository.rxTotal.observe(this) { updateTrafficUI() }
    }

    private fun checkPreviousCrash() {
        val prevCrash = StunRepository.checkPreviousCrash(this)
        if (!prevCrash.isNullOrEmpty()) {
            showCrashDialog(prevCrash, isPrevious = true)
        }
    }

    private fun showCrashDialog(crashLog: String, isPrevious: Boolean) {
        if (isFinishing || isDestroyed) return
        val titleRes = if (isPrevious) CoreR.string.crash_dialog_title_prev else CoreR.string.crash_dialog_title

        val paddingH = (24 * resources.displayMetrics.density).toInt()
        val paddingV = (16 * resources.displayMetrics.density).toInt()

        val textView = android.widget.TextView(this).apply {
            text = crashLog
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            // 跟随主题取色：写死白色在浅色主题的浅色弹窗里不可读
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface
                )
            )
        }

        val scrollView = android.widget.ScrollView(this).apply {
            addView(textView)
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(titleRes))
            .setView(scrollView)
            .setPositiveButton(getString(CoreR.string.close), null)
            .show()
    }

    private var publicIpJob: kotlinx.coroutines.Job? = null

    private fun updateVpnStatusUI(state: VpnState) {
        if (isFinishing || isDestroyed) return

        when (state) {
            VpnState.CONNECTED -> {
                binding.tvVpnStatus.text = getString(CoreR.string.status_connected)
                binding.tvVpnStatus.setTextColor(getColor(CoreR.color.status_connected))
                binding.dotVpnStatus.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_connected))
                binding.btnConnect.text = getString(CoreR.string.disconnect)
                fetchPublicIp()
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                publicIpJob?.cancel()
                binding.tvPublicIp.visibility = View.GONE
                binding.tvVpnStatus.text = getString(CoreR.string.main_connecting)
                binding.tvVpnStatus.setTextColor(getColor(CoreR.color.status_connecting))
                binding.dotVpnStatus.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_connecting))
                // 此时点击 = stopVpn（取消连接），按钮语义是「断开」而不是「关闭」
                binding.btnConnect.text = getString(CoreR.string.disconnect)
            }
            else -> {
                publicIpJob?.cancel()
                binding.tvPublicIp.visibility = View.GONE
                binding.tvVpnStatus.text = getString(CoreR.string.status_disconnected)
                binding.tvVpnStatus.setTextColor(getColor(CoreR.color.status_disconnected))
                binding.dotVpnStatus.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_disconnected))
                binding.btnConnect.text = getString(CoreR.string.connect)
            }
        }
    }

    private fun fetchPublicIp() {
        publicIpJob?.cancel()
        binding.tvPublicIp.visibility = View.VISIBLE
        binding.tvPublicIp.text = "🌐 ..."

        publicIpJob = lifecycleScope.launch(Dispatchers.IO) {
            // 与手机端共用 :core 的 ExitIpProbe（三路 HTTPS provider，按序降级）
            val exit = ExitIpProbe().run()

            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                when {
                    currentVpnState != VpnState.CONNECTED -> binding.tvPublicIp.visibility = View.GONE
                    // 三家全失败时不要停在 🌐 ... 的假加载态
                    exit == null -> binding.tvPublicIp.visibility = View.GONE
                    else -> {
                        binding.tvPublicIp.visibility = View.VISIBLE
                        binding.tvPublicIp.text = "🌐 ${exit.displayText}"
                    }
                }
            }
        }
    }

    // 引擎报错提示：把 engineError 直接显示到左栏，并仅在值变化时 Toast（避免刷屏）
    private var lastEngineError: String? = null
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
                if (selected.id.isNotEmpty() && selected.name.isNotEmpty()) {
                    val typeStr = when (selected.tunnelType) {
                        Profile.TUNNEL_TYPE_UDP_CUSTOM -> "UDP CUSTOM (${selected.udpCustomMagic.ifBlank { "UDPC" }})"
                        Profile.TUNNEL_TYPE_DNS -> "DNS"
                        Profile.TUNNEL_TYPE_KCP -> "KCP"
                        else -> selected.tunnelType.uppercase()
                    }
                    binding.tvSelectedNodeTitle.text = "${selected.name} · $typeStr"
                } else {
                    binding.tvSelectedNodeTitle.text = getString(CoreR.string.tv_select_node_hint)
                }
            }
        }
    }

    // 实时流量速率与累计（来自引擎 1Hz 回调）；一秒多次触发，findViewById 缓存成字段
    private var tvTrafficView: TextView? = null
    private var tvTrafficTotalView: TextView? = null

    private fun updateTrafficUI() {
        if (isFinishing || isDestroyed) return
        val tv = tvTrafficView ?: findViewById<TextView>(R.id.tvTraffic).also { tvTrafficView = it }
        val tvTotal = tvTrafficTotalView ?: findViewById<TextView>(R.id.tvTrafficTotal).also { tvTrafficTotalView = it }
        val tx = StunRepository.txRate.value ?: 0L
        val rx = StunRepository.rxRate.value ?: 0L
        val totalTx = StunRepository.txTotal.value ?: 0L
        val totalRx = StunRepository.rxTotal.value ?: 0L
        tv?.text = getString(CoreR.string.tv_traffic_format, 
            app.fjj.stun.util.AppUtils.formatSpeed(tx), 
            app.fjj.stun.util.AppUtils.formatSpeed(rx)
        )
        tvTotal?.text = "Σ ${app.fjj.stun.util.AppUtils.formatBytes(totalTx + totalRx)}"
    }

    private fun handleConnectClick() {
        if (currentVpnState == VpnState.DISCONNECTED || currentVpnState == VpnState.ERROR) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startVpn()
            }
        } else {
            stopVpn()
        }
    }

    private fun startVpn() {
        val profileId = SettingsManager.getSelectedProfileId(this)
        if (profileId == null) {
            Toast.makeText(this, getString(CoreR.string.tv_select_node_hint), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_START
        }
        startService(intent)
    }

    private fun stopVpn() {
        val intent = Intent(this, MyVpnService::class.java).apply {
            action = MyVpnService.ACTION_STOP
        }
        startService(intent)
    }

    private fun toggleSyncServer(enable: Boolean) {
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
     */
    private fun startBluetoothSyncServer() {
        if (BluetoothSyncManager.hasBluetoothConnectPermission(this)) {
            BluetoothSyncManager.startServer(this)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            btConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    // 测速入口：与手机端 testAllProfilesLatency 走同一套 Go pingNodes，结果按节点回填到列表
    private var latencyTestJob: kotlinx.coroutines.Job? = null

    private fun testAllProfilesLatency() {
        // 进行中再点直接忽略：8s 超时窗口内连点会叠出两轮 "..."
        if (latencyTestJob?.isActive == true) return
        val profiles = adapter.currentList
        if (profiles.isEmpty()) {
            Toast.makeText(this, getString(CoreR.string.tv_select_node_hint), Toast.LENGTH_SHORT).show()
            return
        }
        profiles.forEach { adapter.updateDelay(it.id, "...") }
        binding.btnTestLatency.isEnabled = false
        binding.btnTestLatency.text = getString(CoreR.string.tv_latency_testing)

        latencyTestJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reqArray = JSONArray()
                profiles.forEach { p ->
                    val configJson = VpnConfigBuilder.buildMySshConfig(this@MainActivity, p, 1080, 53)
                    reqArray.put(JSONObject().put("id", p.id).put("config", JSONObject(configJson)))
                }
                // 与手机端“选定/全部节点测速”方法论完全一致：同一目标、同一超时
                val jsonResStr = StunRepository.proxy.pingNodes(
                    reqArray.toString(),
                    "http://cp.cloudflare.com/generate_204",
                    8000L
                )
                val results = parsePingResults(jsonResStr)
                withContext(Dispatchers.Main) {
                    profiles.forEach { p ->
                        adapter.updateDelay(p.id, results[p.id] ?: getString(CoreR.string.latency_network_error))
                    }
                    Toast.makeText(this@MainActivity, getString(CoreR.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, getString(CoreR.string.speed_test_error, e.message), Toast.LENGTH_SHORT).show()
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

    /**
     * 将 Go PingNodes 返回的结构化 JSON（[]PingResult）解析为 id -> 展示字符串。
     * 实现已下沉到 :core 的 [PingResults]，与手机端 / car / xr 共用同一份。
     */
    private fun parsePingResults(jsonStr: String): Map<String, String> =
        PingResults.parse(this, jsonStr)

    override fun onDestroy() {
        super.onDestroy()
        publicIpJob?.cancel()
        RemoteSyncManager.onProfilePushRequested = null
        RemoteSyncManager.onRemoteControlRequested = null
        RemoteSyncManager.tvStatusProvider = null
        RemoteSyncManager.stopServer()
        // BT 通道的回调与 HTTP 同源（见 setupRemoteCallbacks），销毁时一并摘除，
        // 避免持有已销毁 Activity 的引用导致泄漏/操作已死的 View。
        BluetoothSyncManager.onRemoteControlRequested = null
        BluetoothSyncManager.tvStatusProvider = null
        WebServer.onVpnControlRequested = null
        WebServer.onProfileSelected = null
        WebServer.onProfileDeleted = null
        WebServer.onProfileAdded = null
        WebServer.stop()
    }
}

