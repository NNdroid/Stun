package app.fjj.stun.xr

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.service.VpnControls
import app.fjj.stun.util.AppUtils
import app.fjj.stun.util.PingResults
import app.fjj.stun.xr.databinding.ActivityXrMainBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class XRMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityXrMainBinding
    private lateinit var adapter: ProfileAdapterXR
    private var isVpnRunning = false
    private var isVpnTransitioning = false

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
        binding = ActivityXrMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        observeData()
        loadProfiles()
    }

    private fun setupRecyclerView() {
        val selectedId = SettingsManager.getSelectedProfileId(this)
        adapter = ProfileAdapterXR(
            selectedProfileId = selectedId,
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

        binding.rvXrNodes.layoutManager = LinearLayoutManager(this)
        binding.rvXrNodes.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnXrPower.setOnClickListener {
            handleStartStop()
        }

        binding.btnXrPingAll.setOnClickListener {
            pingAllNodes()
        }
    }

    private fun observeData() {
        StunRepository.vpnState.observe(this) { state ->
            updateVpnUi(state)
        }

        StunRepository.txRate.observe(this) { rate ->
            if (isVpnRunning) {
                binding.tvXrUpSpeed.text = getString(CoreR.string.traffic_up_format, AppUtils.formatBytes(rate))
            }
        }

        StunRepository.rxRate.observe(this) { rate ->
            if (isVpnRunning) {
                binding.tvXrDownSpeed.text = getString(CoreR.string.traffic_down_format, AppUtils.formatBytes(rate))
            }
        }

        StunRepository.engineError.observe(this) { msg ->
            if (!msg.isNullOrEmpty()) {
                Snackbar.make(binding.xrRoot, msg, Snackbar.LENGTH_LONG).show()
                StunRepository.engineError.postValue(null)
            }
        }
    }

    private fun loadProfiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@XRMainActivity)
            val selectedId = SettingsManager.getSelectedProfileId(this@XRMainActivity)
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
                binding.btnXrPower.isEnabled = true
                binding.ivXrPowerIcon.setImageResource(CoreR.drawable.ic_pause)
                binding.xrStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
                binding.tvXrStatus.text = getString(CoreR.string.xr_spatial_status_connected)
                binding.layoutXrTraffic.visibility = View.VISIBLE
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                isVpnRunning = false
                isVpnTransitioning = true
                binding.btnXrPower.isEnabled = false
                binding.ivXrPowerIcon.setImageResource(CoreR.drawable.ic_sync)
                binding.xrStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
                binding.tvXrStatus.text = getString(CoreR.string.main_connecting)
                binding.layoutXrTraffic.visibility = View.GONE
            }
            else -> {
                isVpnRunning = false
                isVpnTransitioning = false
                binding.btnXrPower.isEnabled = true
                binding.ivXrPowerIcon.setImageResource(CoreR.drawable.ic_play)
                binding.xrStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
                binding.tvXrStatus.text = getString(CoreR.string.xr_spatial_status_disconnected)
                binding.layoutXrTraffic.visibility = View.GONE
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

    private fun pingAllNodes() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@XRMainActivity)
            if (profiles.isEmpty()) return@launch

            withContext(Dispatchers.Main) {
                Toast.makeText(this@XRMainActivity, getString(CoreR.string.speed_test_started), Toast.LENGTH_SHORT).show()
            }

            try {
                val reqArray = JSONArray()
                profiles.forEach { p ->
                    val configJson = VpnConfigBuilder.buildMySshConfig(this@XRMainActivity, p, 1080, 53)
                    reqArray.put(JSONObject().put("id", p.id).put("config", JSONObject(configJson)))
                }
                val jsonResStr = StunRepository.proxy.pingNodes(reqArray.toString(), "http://cp.cloudflare.com/generate_204", 8000L)
                val results = PingResults.parse(this@XRMainActivity, jsonResStr)

                withContext(Dispatchers.Main) {
                    profiles.forEach { p ->
                        adapter.updateDelay(p.id, results[p.id] ?: getString(CoreR.string.latency_network_error))
                    }
                    Toast.makeText(this@XRMainActivity, getString(CoreR.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@XRMainActivity, getString(CoreR.string.speed_test_error, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
