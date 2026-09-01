package app.fjj.stun.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.fjj.stun.car.databinding.ActivityCarMainBinding
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.util.AppUtils
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class CarMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCarMainBinding
    private lateinit var adapter: ProfileAdapterCar
    private var isVpnRunning = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkAndRequestNotificationPermission()
        } else {
            Toast.makeText(this, getString(CoreR.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSelectedService()
        } else {
            Toast.makeText(this, getString(CoreR.string.notification_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCarMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        observeData()
        loadProfiles()
    }

    private fun setupRecyclerView() {
        val selectedId = SettingsManager.getSelectedProfileId(this)
        adapter = ProfileAdapterCar(
            selectedProfileId = selectedId,
            onProfileClick = { profile ->
                if (!isVpnRunning) {
                    SettingsManager.setSelectedProfileId(this, profile.id)
                    loadProfiles()
                    Toast.makeText(this, getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(CoreR.string.main_profile_switch_disabled), Toast.LENGTH_SHORT).show()
                }
            }
        )

        binding.rvCarNodes.layoutManager = LinearLayoutManager(this)
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
                binding.ivCarPowerIcon.setImageResource(CoreR.drawable.ic_pause)
                binding.tvCarPowerLabel.text = getString(CoreR.string.car_power_button_disconnect)
                binding.carStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
                binding.tvCarStatus.text = getString(CoreR.string.car_status_protected)
                binding.layoutCarTraffic.visibility = View.VISIBLE
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                isVpnRunning = false
                binding.ivCarPowerIcon.setImageResource(CoreR.drawable.ic_sync)
                binding.tvCarPowerLabel.text = getString(CoreR.string.main_connecting)
                binding.carStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
                binding.tvCarStatus.text = getString(CoreR.string.main_connecting)
                binding.layoutCarTraffic.visibility = View.GONE
            }
            else -> {
                isVpnRunning = false
                binding.ivCarPowerIcon.setImageResource(CoreR.drawable.ic_play)
                binding.tvCarPowerLabel.text = getString(CoreR.string.car_power_button_connect)
                binding.carStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
                binding.tvCarStatus.text = getString(CoreR.string.car_status_unprotected)
                binding.layoutCarTraffic.visibility = View.GONE
            }
        }
    }

    private fun handleStartStop() {
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (currentState == VpnState.CONNECTED || currentState == VpnState.RECONNECTING) {
            stopVpnService()
        } else {
            checkAndRequestNotificationPermission()
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startSelectedService()
    }

    private fun startSelectedService() {
        val mode = SettingsManager.getServiceMode(this)
        if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
            val intent = Intent(this, MyTransparentProxyService::class.java).apply { action = "START" }
            ContextCompat.startForegroundService(this, intent)
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnLauncher.launch(intent)
            } else {
                val vpnIntent = Intent(this, MyVpnService::class.java).apply { action = "START" }
                ContextCompat.startForegroundService(this, vpnIntent)
            }
        }
    }

    private fun stopVpnService() {
        val mode = SettingsManager.getServiceMode(this)
        val intentClass = if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
            MyTransparentProxyService::class.java
        } else {
            MyVpnService::class.java
        }
        val intent = Intent(this, intentClass).apply { action = "STOP" }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun pingAllNodes() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@CarMainActivity)
            if (profiles.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                Toast.makeText(this@CarMainActivity, getString(CoreR.string.speed_test_started), Toast.LENGTH_SHORT).show()
            }

            try {
                val reqArray = JSONArray()
                profiles.forEach { p ->
                    val configJson = VpnConfigBuilder.buildMySshConfig(this@CarMainActivity, p, 1080, 53)
                    reqArray.put(JSONObject().put("id", p.id).put("config", JSONObject(configJson)))
                }
                val jsonResStr = StunRepository.proxy.pingNodes(reqArray.toString(), "http://cp.cloudflare.com/generate_204", 8000L)
                val results = parsePingResults(jsonResStr)

                withContext(Dispatchers.Main) {
                    profiles.forEach { p ->
                        adapter.updateDelay(p.id, results[p.id] ?: getString(CoreR.string.latency_network_error))
                    }
                    Toast.makeText(this@CarMainActivity, getString(CoreR.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CarMainActivity, getString(CoreR.string.speed_test_error, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
                    map[id] = getString(CoreR.string.latency_network_error)
                }
            }
        } catch (_: Exception) {}
        return map
    }
}
