package app.fjj.stun.tv

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.remote.WebLogServer
import app.fjj.stun.repo.*
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
import com.google.android.material.button.MaterialButton
import app.fjj.stun.core.R as CoreR
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : FragmentActivity() {
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
        setContentView(R.layout.activity_main)

        lifecycleScope.launch(Dispatchers.IO) {
            app.fjj.stun.repo.ProfileManager.migratePlaintextProfiles(this@MainActivity)
        }

        setupUI()
        setupRemoteCallbacks()
        observeData()
        
        // Auto-start sync server
        toggleSyncServer(true)

        // Start web log server and show URL on screen
        lifecycleScope.launch(Dispatchers.IO) {
            val port = WebLogServer.start(this@MainActivity)
            if (port > 0) {
                val ip = WebLogServer.getLocalIp(this@MainActivity)
                val url = getString(CoreR.string.web_console_url_format, ip, port, WebLogServer.token)
                launch(Dispatchers.Main) {
                    val tvWebConsole = findViewById<TextView>(R.id.tvWebConsole)
                    tvWebConsole.text = url
                    tvWebConsole.animate().alpha(1f).setDuration(600).start()
                }
            }
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
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
    }

    private fun showPushConfirmDialog(
        senderIp: String,
        profile: Profile,
        deferred: kotlinx.coroutines.CompletableDeferred<Boolean>
    ) {
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
        val btnConnect = findViewById<MaterialButton>(R.id.btnConnect)
        val btnToggleSync = findViewById<MaterialButton>(R.id.btnToggleSync)
        val rvProfiles = findViewById<RecyclerView>(R.id.rvProfiles)

        val selectedId = SettingsManager.getSelectedProfileId(this)
        adapter = ProfileAdapterTV(selectedId) { profile ->
            SettingsManager.setSelectedProfileId(this, profile.id)
            Toast.makeText(this, getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
        }

        rvProfiles.layoutManager = LinearLayoutManager(this)
        rvProfiles.adapter = adapter

        btnConnect.setOnClickListener {
            handleConnectClick()
        }

        btnToggleSync.setOnClickListener {
            toggleSyncServer(!isSyncServerRunning)
        }

        val btnTestLatency = findViewById<MaterialButton>(R.id.btnTestLatency)
        btnTestLatency.setOnClickListener {
            testAllProfilesLatency()
        }
    }

    private fun observeData() {
        ProfileManager.getProfilesLiveData(this).observe(this) { profiles ->
            adapter.submitList(profiles)
            
            // Auto-select if nothing selected
            if (SettingsManager.getSelectedProfileId(this) == null && profiles.isNotEmpty()) {
                val firstId = profiles[0].id
                SettingsManager.setSelectedProfileId(this, firstId)
                adapter.updateSelectedId(firstId)
            }
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

    private fun updateVpnStatusUI(state: VpnState) {
        val tvVpnStatus = findViewById<TextView>(R.id.tvVpnStatus)
        val btnConnect = findViewById<MaterialButton>(R.id.btnConnect)

        when (state) {
            VpnState.CONNECTED -> {
                tvVpnStatus.text = getString(CoreR.string.status_connected)
                tvVpnStatus.setTextColor(getColor(CoreR.color.md_theme_light_primary))
                btnConnect.text = getString(CoreR.string.disconnect)
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                tvVpnStatus.text = getString(CoreR.string.main_connecting)
                tvVpnStatus.setTextColor(getColor(android.R.color.holo_orange_light))
                btnConnect.text = getString(CoreR.string.close).uppercase()
            }
            else -> {
                tvVpnStatus.text = getString(CoreR.string.status_disconnected)
                tvVpnStatus.setTextColor(getColor(android.R.color.holo_red_light))
                btnConnect.text = getString(CoreR.string.connect)
            }
        }
    }

    // 引擎报错提示：把 engineError 直接显示到左栏，并仅在值变化时 Toast（避免刷屏）
    private var lastEngineError: String? = null
    private fun updateEngineErrorUI(err: String?) {
        val tv = findViewById<TextView>(R.id.tvEngineError)
        if (err.isNullOrEmpty()) {
            tv.visibility = View.GONE
            lastEngineError = null
        } else {
            tv.visibility = View.VISIBLE
            tv.text = "⚠ $err"
            if (err != lastEngineError) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                lastEngineError = err
            }
        }
    }

    // 实时流量速率（上行/下行，单位 bytes/s），来自引擎 1Hz 回调
    private fun updateTrafficUI() {
        val tv = findViewById<TextView>(R.id.tvTraffic)
        val tx = StunRepository.txRate.value ?: 0L
        val rx = StunRepository.rxRate.value ?: 0L
        tv.text = getString(CoreR.string.tv_traffic_format, formatSpeed(tx), formatSpeed(rx))
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "0 B/s"
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s")
        val digitGroups = (kotlin.math.log10(bytesPerSec.toDouble()) / kotlin.math.log10(1024.0)).toInt()
            .coerceIn(0, units.size - 1)
        return String.format(java.util.Locale.US, "%.1f %s", bytesPerSec / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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
        val tvSyncStatus = findViewById<TextView>(R.id.tvSyncStatus)
        val btnToggleSync = findViewById<MaterialButton>(R.id.btnToggleSync)

        if (enable) {
            RemoteSyncManager.startServer(this)
            tvSyncStatus.text = getString(CoreR.string.tv_sync_server_on, "StunTV")
            btnToggleSync.text = getString(CoreR.string.tv_stop_sync)
            isSyncServerRunning = true
        } else {
            RemoteSyncManager.stopServer()
            tvSyncStatus.text = getString(CoreR.string.tv_sync_server_off)
            btnToggleSync.text = getString(CoreR.string.tv_start_sync)
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
        RemoteSyncManager.onProfilePushRequested = null
        RemoteSyncManager.onRemoteControlRequested = null
        RemoteSyncManager.tvStatusProvider = null
        RemoteSyncManager.stopServer()
        WebLogServer.stop()
    }
}
