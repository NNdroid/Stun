package app.fjj.stun.wear

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
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.util.AppUtils
import app.fjj.stun.wear.databinding.ActivityWearMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WearMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWearMainBinding
    private lateinit var adapter: ProfileAdapterWear
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
        binding = ActivityWearMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        observeData()
        loadProfiles()
    }

    private fun setupRecyclerView() {
        val selectedId = SettingsManager.getSelectedProfileId(this)
        adapter = ProfileAdapterWear(
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

        binding.rvWearNodes.layoutManager = LinearLayoutManager(this)
        binding.rvWearNodes.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnWearPower.setOnClickListener {
            handleStartStop()
        }
    }

    private fun observeData() {
        StunRepository.vpnState.observe(this) { state ->
            updateVpnUi(state)
        }

        StunRepository.txRate.observe(this) { rate ->
            val rx = StunRepository.rxRate.value ?: 0L
            if (isVpnRunning) {
                binding.tvWearSpeed.text = "▲ ${AppUtils.formatBytes(rate)}  ▼ ${AppUtils.formatBytes(rx)}"
            }
        }

        StunRepository.rxRate.observe(this) { rate ->
            val tx = StunRepository.txRate.value ?: 0L
            if (isVpnRunning) {
                binding.tvWearSpeed.text = "▲ ${AppUtils.formatBytes(tx)}  ▼ ${AppUtils.formatBytes(rate)}"
            }
        }
    }

    private fun loadProfiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@WearMainActivity)
            val selectedId = SettingsManager.getSelectedProfileId(this@WearMainActivity)
            withContext(Dispatchers.Main) {
                adapter.updateProfiles(profiles, selectedId)
            }
        }
    }

    private fun updateVpnUi(state: VpnState?) {
        when (state) {
            VpnState.CONNECTED -> {
                isVpnRunning = true
                binding.ivWearPowerIcon.setImageResource(CoreR.drawable.ic_pause)
                binding.wearStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
                binding.tvWearStatus.text = getString(CoreR.string.main_connected)
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                isVpnRunning = false
                binding.ivWearPowerIcon.setImageResource(CoreR.drawable.ic_sync)
                binding.wearStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF9800.toInt())
                binding.tvWearStatus.text = getString(CoreR.string.main_connecting)
            }
            else -> {
                isVpnRunning = false
                binding.ivWearPowerIcon.setImageResource(CoreR.drawable.ic_play)
                binding.wearStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
                binding.tvWearStatus.text = getString(CoreR.string.main_disconnected)
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
}
