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

        // Go 引擎 Panic：手机/TV 端都有弹窗，XR 不能只留在旧界面上
        StunRepository.crashEvent.observe(this) { crashLog ->
            if (!crashLog.isNullOrEmpty()) {
                showCrashDialog(crashLog)
                StunRepository.crashEvent.postValue(null)
            }
        }
    }

    private fun showCrashDialog(crashLog: String) {
        if (isFinishing || isDestroyed) return
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
        val scrollView = android.widget.ScrollView(this).apply { addView(textView) }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.crash_dialog_title))
            .setView(scrollView)
            .setPositiveButton(getString(CoreR.string.close), null)
            .show()
    }

    private fun loadProfiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(this@XRMainActivity)
            val selectedId = SettingsManager.getSelectedProfileId(this@XRMainActivity)
            withContext(Dispatchers.Main) {
                adapter.updateProfiles(profiles, selectedId)
                // 空列表提示（XR 没有添加节点的入口，首跑必须告诉用户去手机端推）
                binding.tvXrEmptyHint.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateVpnUi(state: VpnState?) {
        when (state) {
            VpnState.CONNECTED -> {
                isVpnRunning = true
                isVpnTransitioning = false
                binding.btnXrPower.isEnabled = true
                binding.btnXrPower.alpha = 1f
                binding.ivXrPowerIcon.setImageResource(CoreR.drawable.ic_pause)
                binding.xrStatusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_connected))
                binding.tvXrStatus.text = getString(CoreR.string.xr_spatial_status_connected)
                binding.layoutXrTraffic.visibility = View.VISIBLE
            }
            VpnState.CONNECTING, VpnState.RECONNECTING -> {
                isVpnRunning = false
                isVpnTransitioning = true
                binding.btnXrPower.isEnabled = false
                // 禁用态给视觉反馈（原来只挡点击，界面毫无变化）
                binding.btnXrPower.alpha = 0.5f
                binding.ivXrPowerIcon.setImageResource(CoreR.drawable.ic_sync)
                binding.xrStatusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_connecting))
                binding.tvXrStatus.text = getString(CoreR.string.xr_spatial_status_connecting)
                binding.layoutXrTraffic.visibility = View.GONE
            }
            else -> {
                isVpnRunning = false
                isVpnTransitioning = false
                binding.btnXrPower.isEnabled = true
                binding.btnXrPower.alpha = 1f
                binding.ivXrPowerIcon.setImageResource(CoreR.drawable.ic_play)
                binding.xrStatusDot.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(CoreR.color.status_disconnected))
                binding.tvXrStatus.text = getString(CoreR.string.xr_spatial_status_disconnected)
                binding.layoutXrTraffic.visibility = View.GONE
                // 断开后清掉上一轮会话的残留速率（观察者只在 isVpnRunning 时写）
                binding.tvXrUpSpeed.text = getString(CoreR.string.traffic_up_format, AppUtils.formatBytes(0))
                binding.tvXrDownSpeed.text = getString(CoreR.string.traffic_down_format, AppUtils.formatBytes(0))
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
            val profiles = ProfileManager.getProfiles(this@XRMainActivity)
            if (profiles.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@XRMainActivity, getString(CoreR.string.tv_select_node_hint), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(this@XRMainActivity, getString(CoreR.string.speed_test_started), Toast.LENGTH_SHORT).show()
                binding.btnXrPingAll.isEnabled = false
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
                    // 整轮结果一次性回填（缺的补网络错误），N 个节点只触发一次重绘
                    val filled = profiles.associate {
                        it.id to (results[it.id] ?: getString(CoreR.string.latency_network_error))
                    }
                    adapter.updateDelays(filled)
                    Toast.makeText(this@XRMainActivity, getString(CoreR.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@XRMainActivity, getString(CoreR.string.speed_test_error, e.message), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (!(isFinishing || isDestroyed)) {
                        binding.btnXrPingAll.isEnabled = true
                    }
                }
            }
        }
    }
}
