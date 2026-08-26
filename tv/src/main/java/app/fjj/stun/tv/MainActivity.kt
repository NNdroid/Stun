package app.fjj.stun.tv

import android.content.Intent
import android.net.VpnService
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
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.remote.WebServer
import app.fjj.stun.repo.*
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
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

        // Start WebServer and show URL + QR Code on screen
        lifecycleScope.launch(Dispatchers.IO) {
            val port = WebServer.start(this@MainActivity)
            if (port > 0) {
                val url = WebServer.getEffectiveUrl(this@MainActivity, port)
                updateWebConsoleUI(url)
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
            val selectedProfile = ProfileManager.getSelectedProfile(this)
            app.fjj.stun.remote.TvStatusResponse(
                vpnState = currentVpnState.name,
                currentProfileName = selectedProfile?.name,
                currentProfileId = SettingsManager.getSelectedProfileId(this),
                profileCount = adapter.itemCount,
                deviceName = android.os.Build.MODEL
            )
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

        // Remote control callback (Connect, Disconnect, Select Profile)
        RemoteSyncManager.onRemoteControlRequested = { action, profileId ->
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
                    "select_profile" -> {
                        if (profileId != null) {
                            SettingsManager.setSelectedProfileId(this@MainActivity, profileId)
                            adapter.updateSelectedId(profileId)
                            updateSelectedNodeUI()
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }

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
                SettingsManager.setSelectedProfileId(this, profile.id)
                updateSelectedNodeUI()
                Toast.makeText(this, getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
            },
            onProfileLongClick = { profile ->
                showProfileOptionsDialog(profile)
            }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter
        (binding.rvProfiles.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        updateSelectedNodeUI()

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

        // 实时流量速率（上行/下行），来自引擎 1Hz 回调
        StunRepository.txRate.observe(this) { updateTrafficUI() }
        StunRepository.rxRate.observe(this) { updateTrafficUI() }
        StunRepository.txTotal.observe(this) { updateTrafficUI() }
        StunRepository.rxTotal.observe(this) { updateTrafficUI() }
    }

    private var publicIpJob: kotlinx.coroutines.Job? = null

    private fun updateVpnStatusUI(state: VpnState) {
        if (isFinishing || isDestroyed) return

        when (state) {
            VpnState.CONNECTED -> {
                binding.tvVpnStatus.text = getString(CoreR.string.status_connected)
                binding.tvVpnStatus.setTextColor(getColor(CoreR.color.status_connected))
                binding.btnConnect.text = getString(CoreR.string.disconnect)
                fetchPublicIp()
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                publicIpJob?.cancel()
                binding.tvPublicIp.visibility = View.GONE
                binding.tvVpnStatus.text = getString(CoreR.string.main_connecting)
                binding.tvVpnStatus.setTextColor(getColor(CoreR.color.status_connecting))
                binding.btnConnect.text = getString(CoreR.string.close).uppercase()
            }
            else -> {
                publicIpJob?.cancel()
                binding.tvPublicIp.visibility = View.GONE
                binding.tvVpnStatus.text = getString(CoreR.string.status_disconnected)
                binding.tvVpnStatus.setTextColor(getColor(CoreR.color.status_disconnected))
                binding.btnConnect.text = getString(CoreR.string.connect)
            }
        }
    }

    private fun fetchPublicIp() {
        publicIpJob?.cancel()
        binding.tvPublicIp.visibility = View.VISIBLE
        binding.tvPublicIp.text = "🌐 ..."

        publicIpJob = lifecycleScope.launch(Dispatchers.IO) {
            var publicIp = ""
            var locationDesc = ""
            
            // ... (ip fetching logic)
            // 1. ip-api.com
            try {
                val url = java.net.URL("http://ip-api.com/json")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "curl/7.88.1")
                if (conn.responseCode == 200) {
                    val jsonText = conn.inputStream.bufferedReader().use { it.readText().trim() }
                    val obj = JSONObject(jsonText)
                    publicIp = obj.optString("query", "")
                    val country = obj.optString("country", "")
                    val countryCode = obj.optString("countryCode", "")
                    val city = obj.optString("city", "")
                    val flag = getCountryEmojiFlag(countryCode)
                    
                    val locParts = mutableListOf<String>()
                    if (flag.isNotEmpty()) locParts.add(flag)
                    if (city.isNotEmpty()) locParts.add(city)
                    if (country.isNotEmpty() && country != city) locParts.add(country)
                    locationDesc = locParts.joinToString(" ")
                }
            } catch (_: Exception) {}

            // 2. api.ip.sb
            if (publicIp.isEmpty()) {
                try {
                    val url2 = java.net.URL("https://api.ip.sb/geoip")
                    val conn2 = url2.openConnection() as java.net.HttpURLConnection
                    conn2.connectTimeout = 4000
                    conn2.readTimeout = 4000
                    conn2.setRequestProperty("User-Agent", "curl/7.88.1")
                    if (conn2.responseCode == 200) {
                        val jsonText = conn2.inputStream.bufferedReader().use { it.readText().trim() }
                        val obj = JSONObject(jsonText)
                        publicIp = obj.optString("ip", "")
                        val country = obj.optString("country", "")
                        val countryCode = obj.optString("country_code", "")
                        val city = obj.optString("city", "")
                        val flag = getCountryEmojiFlag(countryCode)
                        
                        val locParts = mutableListOf<String>()
                        if (flag.isNotEmpty()) locParts.add(flag)
                        if (city.isNotEmpty()) locParts.add(city)
                        if (country.isNotEmpty() && country != city) locParts.add(country)
                        locationDesc = locParts.joinToString(" ")
                    }
                } catch (_: Exception) {}
            }

            // 3. api64.ipify.org
            if (publicIp.isEmpty()) {
                try {
                    val url3 = java.net.URL("https://api64.ipify.org")
                    val conn3 = url3.openConnection() as java.net.HttpURLConnection
                    conn3.connectTimeout = 4000
                    conn3.readTimeout = 4000
                    if (conn3.responseCode == 200) {
                        publicIp = conn3.inputStream.bufferedReader().use { it.readText().trim() }
                    }
                } catch (_: Exception) {}
            }

            val ipDisplayStr = when {
                publicIp.isNotEmpty() && locationDesc.isNotEmpty() -> "🌐 $publicIp · $locationDesc"
                publicIp.isNotEmpty() -> "🌐 $publicIp"
                else -> ""
            }

            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                if (currentVpnState == VpnState.CONNECTED && ipDisplayStr.isNotEmpty()) {
                    binding.tvPublicIp.visibility = View.VISIBLE
                    binding.tvPublicIp.text = ipDisplayStr
                } else if (currentVpnState != VpnState.CONNECTED) {
                    binding.tvPublicIp.visibility = View.GONE
                }
            }
        }
    }

    private fun getCountryEmojiFlag(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val firstLetter = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
        val secondLetter = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstLetter)) + String(Character.toChars(secondLetter))
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

    // 实时流量速率与累计（来自引擎 1Hz 回调）
    private fun updateTrafficUI() {
        if (isFinishing || isDestroyed) return
        val tv = findViewById<TextView>(R.id.tvTraffic)
        val tvTotal = findViewById<TextView>(R.id.tvTrafficTotal)
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
            binding.tvSyncStatus.text = getString(CoreR.string.tv_sync_server_on, "StunTV")
            binding.btnToggleSync.text = getString(CoreR.string.tv_stop_sync)
            isSyncServerRunning = true
        } else {
            RemoteSyncManager.stopServer()
            binding.tvSyncStatus.text = getString(CoreR.string.tv_sync_server_off)
            binding.btnToggleSync.text = getString(CoreR.string.tv_start_sync)
            isSyncServerRunning = false
        }
    }

    // 测速入口：与手机端 testAllProfilesLatency 走同一套 Go pingNodes，结果按节点回填到列表
    private fun testAllProfilesLatency() {
        val profiles = adapter.currentList
        if (profiles.isEmpty()) {
            Toast.makeText(this, getString(CoreR.string.tv_select_node_hint), Toast.LENGTH_SHORT).show()
            return
        }
        profiles.forEach { adapter.updateDelay(it.id, "...") }

        lifecycleScope.launch(Dispatchers.IO) {
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
            }
        }
    }

    /**
     * 将 Go PingNodes 返回的结构化 JSON（[]PingResult）解析为 id -> 展示字符串，
     * 逻辑与手机端 HomeFragment.parsePingResults 一致。
     */
    private fun parsePingResults(jsonStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                if (obj.optBoolean("ok", false)) {
                    map[id] = "${obj.optLong("latencyMs", 0)} ms"
                } else {
                    map[id] = when (obj.optString("errorType", "other")) {
                        "timeout" -> getString(CoreR.string.latency_timeout)
                        "connrefused" -> getString(CoreR.string.latency_conn_refused)
                        "tls" -> getString(CoreR.string.latency_ssl_error)
                        "dns" -> getString(CoreR.string.latency_dns_error)
                        "http" -> "HTTP ${obj.optString("error", "")}"
                        else -> getString(CoreR.string.latency_network_error)
                    }
                }
            }
        } catch (_: Exception) {
            // 解析失败时返回空 map，调用方按“网络错误”兜底
        }
        return map
    }

    override fun onDestroy() {
        super.onDestroy()
        publicIpJob?.cancel()
        RemoteSyncManager.onProfilePushRequested = null
        RemoteSyncManager.onRemoteControlRequested = null
        RemoteSyncManager.tvStatusProvider = null
        RemoteSyncManager.stopServer()
        WebServer.onVpnControlRequested = null
        WebServer.onProfileSelected = null
        WebServer.onProfileDeleted = null
        WebServer.onProfileAdded = null
        WebServer.stop()
    }
}

