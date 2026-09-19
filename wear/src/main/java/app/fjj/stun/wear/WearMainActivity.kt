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
                when {
                    isVpnTransitioning ->
                        Toast.makeText(this, getString(CoreR.string.main_profile_switch_disabled), Toast.LENGTH_SHORT).show()
                    // 与 TV 对齐：连接中点别的节点 → 确认后「切换 + 重连」
                    isVpnRunning && profile.id != SettingsManager.getSelectedProfileId(this) ->
                        confirmSwitchProfile(profile)
                    else -> {
                        SettingsManager.setSelectedProfileId(this, profile.id)
                        loadProfiles()
                        Toast.makeText(this, getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )

        binding.rvWearNodes.layoutManager = LinearLayoutManager(this)
        binding.rvWearNodes.adapter = adapter
    }

    private fun confirmSwitchProfile(profile: app.fjj.stun.repo.Profile) {
        if (isFinishing || isDestroyed) return
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.tv_switch_confirm_title))
            .setMessage(getString(CoreR.string.tv_switch_confirm_message, profile.name))
            .setPositiveButton(CoreR.string.ok) { _, _ ->
                stopVpnService()
                lifecycleScope.launch(Dispatchers.Main) {
                    awaitDisconnectedThenStart(profile.id)
                }
            }
            .setNegativeButton(CoreR.string.cancel, null)
            .show()
    }

    private suspend fun awaitDisconnectedThenStart(profileId: String) {
        kotlinx.coroutines.delay(600L)
        var waitedMs = 0L
        while ((StunRepository.vpnState.value ?: VpnState.DISCONNECTED) != VpnState.DISCONNECTED) {
            if (waitedMs >= 3_000L) break
            kotlinx.coroutines.delay(100L)
            waitedMs += 100L
        }
        SettingsManager.setSelectedProfileId(this, profileId)
        startSelectedService()
    }

    private fun setupListeners() {
        binding.btnWearPower.setOnClickListener {
            handleStartStop()
        }
    }

    private var lastEngineError: String? = null

    private fun observeData() {
        StunRepository.vpnState.observe(this) { state ->
            updateVpnUi(state)
            // 状态变化即请求刷新 Tile（默认 30s freshness 太迟钝）
            StunWearTileService.requestTileUpdate(applicationContext)
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

        StunRepository.engineError.observe(this) { msg ->
            // 只在值变化时提示，避免引擎刷错误时连环 Toast
            if (!msg.isNullOrEmpty() && msg != lastEngineError) {
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                lastEngineError = msg
            }
        }

        // Go 引擎 Panic：手机/TV 端都有弹窗，表盘上不能悄无声息
        StunRepository.crashEvent.observe(this) { crashLog ->
            if (!crashLog.isNullOrEmpty()) {
                showCrashDialog(crashLog)
                StunRepository.crashEvent.postValue(null)
            }
        }
    }

    private fun showCrashDialog(crashLog: String) {
        if (isFinishing || isDestroyed) return
        val paddingH = (16 * resources.displayMetrics.density).toInt()
        val paddingV = (12 * resources.displayMetrics.density).toInt()
        val textView = android.widget.TextView(this).apply {
            text = crashLog
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(paddingH, paddingV, paddingH, paddingV)
            // 跟随主题取色：写死白色在浅色主题的浅色弹窗里不可读
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface
                )
            )
        }
        val scrollView = android.widget.ScrollView(this).apply { addView(textView) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.crash_dialog_title))
            .setView(scrollView)
            .setPositiveButton(getString(CoreR.string.close), null)
            .show()
    }

    private fun loadProfiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@WearMainActivity)
            val selectedId = SettingsManager.getSelectedProfileId(this@WearMainActivity)
            withContext(Dispatchers.Main) {
                adapter.updateProfiles(profiles, selectedId)
                // 空列表提示：手表没有添加节点的入口，必须告诉用户去手机端推送
                binding.tvWearEmptyHint.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateVpnUi(state: VpnState?) {
        when (state) {
            VpnState.CONNECTED -> {
                isVpnRunning = true
                isVpnTransitioning = false
                binding.btnWearPower.isEnabled = true
                binding.ivWearPowerIcon.setImageResource(CoreR.drawable.ic_pause)
                binding.wearStatusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_connected))
                binding.tvWearStatus.text = getString(CoreR.string.main_connected)
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                isVpnRunning = false
                isVpnTransitioning = true
                // 连接中按钮 = 取消连接（TV 同一语义）：禁用会让卡死的重连无处可逃
                binding.btnWearPower.isEnabled = true
                binding.ivWearPowerIcon.setImageResource(CoreR.drawable.ic_sync)
                binding.wearStatusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_connecting))
                binding.tvWearStatus.text = getString(CoreR.string.main_connecting)
            }
            VpnState.ERROR -> {
                // 引擎报错要与「正常断开」区分：直接把原因顶到状态行
                isVpnRunning = false
                isVpnTransitioning = false
                binding.btnWearPower.isEnabled = true
                binding.ivWearPowerIcon.setImageResource(CoreR.drawable.ic_play)
                binding.wearStatusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_disconnected))
                binding.tvWearStatus.text =
                    StunRepository.engineError.value?.takeIf { it.isNotBlank() } ?: getString(CoreR.string.main_disconnected)
            }
            else -> {
                isVpnRunning = false
                isVpnTransitioning = false
                binding.btnWearPower.isEnabled = true
                binding.ivWearPowerIcon.setImageResource(CoreR.drawable.ic_play)
                binding.wearStatusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_disconnected))
                binding.tvWearStatus.text = getString(CoreR.string.main_disconnected)
                // 断开后清掉上一轮会话的残留速率（观察者只在 isVpnRunning 时写）
                binding.tvWearSpeed.text = ""
            }
        }
    }

    private fun handleStartStop() {
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        when (currentState) {
            VpnState.CONNECTED -> stopVpnService()
            // 连接中再按 = 取消连接（此前按钮被禁用，卡死的重连无处可逃）
            VpnState.CONNECTING, VpnState.RECONNECTING -> stopVpnService()
            else -> checkAndRequestNotificationPermission()
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
        // 没选节点就拉服务只会空转重连（首跑点了电源没反应的根因）
        if (SettingsManager.getSelectedProfileId(this) == null) {
            Toast.makeText(this, getString(CoreR.string.tv_select_node_hint), Toast.LENGTH_SHORT).show()
            return
        }
        val mode = SettingsManager.getServiceMode(this)
        if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
            val intent = Intent(this, MyTransparentProxyService::class.java).apply { action = MyTransparentProxyService.ACTION_START }
            ContextCompat.startForegroundService(this, intent)
        } else {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                vpnLauncher.launch(intent)
            } else {
                val vpnIntent = Intent(this, MyVpnService::class.java).apply { action = MyVpnService.ACTION_START }
                ContextCompat.startForegroundService(this, vpnIntent)
            }
        }
    }

    private fun stopVpnService() {
        val mode = SettingsManager.getServiceMode(this)
        val isTProxy = mode == SettingsManager.SERVICE_MODE_TPROXY
        val intentClass = if (isTProxy) {
            MyTransparentProxyService::class.java
        } else {
            MyVpnService::class.java
        }
        val action = if (isTProxy) MyTransparentProxyService.ACTION_STOP else MyVpnService.ACTION_STOP
        val intent = Intent(this, intentClass).apply { this.action = action }
        ContextCompat.startForegroundService(this, intent)
    }
}
