package app.fjj.stun.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import app.fjj.stun.databinding.ActivityMainBinding
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.util.AppUtils
import app.fjj.stun.util.KeystoreUtils
import app.fjj.stun.util.QRUtils
import app.fjj.stun.util.ShizukuUtils
import com.google.android.material.navigation.NavigationView
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : BaseActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProfileAdapter

    // 仅在完全连接时为 true，用于控制延迟测试是否走代理
    private var isVpnRunning = false
    private var isStopping = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            checkAndRequestNotificationPermission()
        } else {
            Toast.makeText(this, getString(app.fjj.stun.R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val mode = SettingsManager.getServiceMode(this)
        if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
            startTProxyService()
        } else {
            startVpnService()
        }
    }

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents != null) {
            try {
                // 1. 先将扫到的 Base64 字符串解码还原为普通字符串 (JSON)
                val decodedBytes = Base64.decode(result.contents, Base64.DEFAULT)
                val jsonString = String(decodedBytes, Charsets.UTF_8)

                // 2. 将还原后的 JSON 字符串交给 Gson 解析
                val profile = Gson().fromJson(jsonString, Profile::class.java)
                val profileName = profile.name ?: "Unknown"

                // 确保生成一个新的 ID
                val newProfile = profile.copy(id = java.util.UUID.randomUUID().toString())

                thread {
                    ProfileManager.addProfile(this, newProfile)
                    runOnUiThread {
                        Toast.makeText(this, getString(app.fjj.stun.R.string.profile_added, profileName), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                StunLogger.e("MainActivity", "Scan QR Code failed", e)
                Toast.makeText(this, getString(app.fjj.stun.R.string.invalid_qr), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportProfilesToUri(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importProfilesFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.toolbar,
            app.fjj.stun.R.string.connect, app.fjj.stun.R.string.disconnect
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        binding.navView.setNavigationItemSelectedListener(this)

        // Update version in header
        val headerView = binding.navView.getHeaderView(0)
        val ivHeaderBg = headerView.findViewById<ImageView>(app.fjj.stun.R.id.iv_header_bg)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ivHeaderBg?.setRenderEffect(
                android.graphics.RenderEffect.createBlurEffect(
                    25f, 25f, android.graphics.Shader.TileMode.CLAMP
                )
            )
        }

        try {
            headerView.findViewById<TextView>(app.fjj.stun.R.id.tv_version)?.text = AppUtils.getAppVersion(this)
        } catch (e: Exception) {
            headerView.findViewById<TextView>(app.fjj.stun.R.id.tv_version)?.text = "v1.0.0"
        }

        val initialRvPadding = binding.rvProfiles.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)

            binding.rvProfiles.updatePadding(bottom = initialRvPadding + systemBars.bottom)
            binding.bottomContainer.updatePadding(bottom = systemBars.bottom)
            insets
        }

        setupRecyclerView()

        ProfileManager.getProfilesLiveData(this).observe(this) { profiles ->
            val selectedId = SettingsManager.getSelectedProfileId(this)
            adapter.updateProfiles(profiles, selectedId)
        }

        binding.fabStartStop.setOnClickListener {
            // Click feedback animation
            it.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                handleStartStop()
            }.start()
        }

        binding.tvStatus.setOnClickListener {
            testSelectedProfileLatency()
        }

        // 🌟 监听枚举状态并精细化控制 UI
        StunRepository.vpnState.observe(this) { state ->
            binding.fabStartStop.clearAnimation() // 停止之前的动画
            when (state) {
                VpnState.DISCONNECTED -> {
                    isVpnRunning = false
                    binding.fabStartStop.isEnabled = true
                    binding.fabStartStop.setImageResource(app.fjj.stun.R.drawable.ic_play)
                    binding.tvStatus.text = getString(app.fjj.stun.R.string.main_disconnected)
                    binding.progressBar.visibility = android.view.View.GONE
                    
                    // Fade in animation
                    binding.fabStartStop.alpha = 0f
                    binding.fabStartStop.animate().alpha(1f).setDuration(300).start()

                    if (isStopping) {
                        isStopping = false
                        //Toast.makeText(this, getString(app.fjj.stun.R.string.connection_complete), Toast.LENGTH_SHORT).show()
                    }
                }
                VpnState.CONNECTING -> {
                    isVpnRunning = false
                    binding.fabStartStop.isEnabled = false // 禁用按钮，防止连点
                    binding.fabStartStop.setImageResource(app.fjj.stun.R.drawable.ic_sync)
                    binding.progressBar.visibility = android.view.View.VISIBLE
                    
                    // 开始旋转 + 呼吸缩放动画
                    val rotate = android.view.animation.RotateAnimation(
                        0f, 360f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 1000
                        repeatCount = android.view.animation.Animation.INFINITE
                        interpolator = android.view.animation.LinearInterpolator()
                    }
                    
                    val scale = android.view.animation.ScaleAnimation(
                        1f, 1.1f, 1f, 1.1f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                        android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                    ).apply {
                        duration = 800
                        repeatCount = android.view.animation.Animation.INFINITE
                        repeatMode = android.view.animation.Animation.REVERSE
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    }

                    val animSet = android.view.animation.AnimationSet(false)
                    animSet.addAnimation(rotate)
                    animSet.addAnimation(scale)
                    binding.fabStartStop.startAnimation(animSet)

                    binding.tvStatus.text = getString(app.fjj.stun.R.string.main_connecting)
                }
                VpnState.CONNECTED -> {
                    isVpnRunning = true
                    binding.fabStartStop.isEnabled = true
                    binding.fabStartStop.setImageResource(app.fjj.stun.R.drawable.ic_pause)
                    binding.tvStatus.text = getString(app.fjj.stun.R.string.main_connected)
                    binding.progressBar.visibility = android.view.View.GONE
                    
                    // Success "Pop" animation
                    binding.fabStartStop.scaleX = 0.8f
                    binding.fabStartStop.scaleY = 0.8f
                    binding.fabStartStop.animate()
                        .scaleX(1.1f).scaleY(1.1f)
                        .setDuration(200)
                        .withEndAction {
                            binding.fabStartStop.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                        }.start()
                }
                VpnState.RECONNECTING -> {
                    isVpnRunning = false
                    binding.fabStartStop.isEnabled = true // 允许用户在重连时打断
                    binding.fabStartStop.setImageResource(app.fjj.stun.R.drawable.ic_pause)
                    binding.tvStatus.text = getString(app.fjj.stun.R.string.main_reconnecting)
                    binding.progressBar.visibility = android.view.View.VISIBLE
                }
                VpnState.ERROR -> {
                    isVpnRunning = false
                    binding.fabStartStop.isEnabled = true
                    binding.fabStartStop.setImageResource(app.fjj.stun.R.drawable.ic_play)
                    binding.tvStatus.text = getString(app.fjj.stun.R.string.main_connection_failed)
                    binding.progressBar.visibility = android.view.View.GONE
                }
                null -> {
                    isVpnRunning = false
                    binding.progressBar.visibility = android.view.View.GONE
                }
            }
        }

        StunLogger.errorListener = { tag, msg, _ ->
            runOnUiThread {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    if (app.fjj.stun.BuildConfig.DEBUG) "[$tag] $msg" else "$msg",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).setAnchorView(binding.bottomContainer).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    override fun onDestroy() {
        StunLogger.errorListener = null
        super.onDestroy()
    }

    private fun applyShizukuKeepAlive() {
        // 在协程作用域中调用
        CoroutineScope(Dispatchers.Main).launch {
            // 这一行会挂起，直到权限请求出结果，不会阻塞主线程！
            val granted = ShizukuUtils.requestPermissionAwait()
            if (granted) {
                // 授权成功，执行保活操作
                ShizukuUtils.addSelfToBatteryWhitelist(packageName)
                ShizukuUtils.setStandbyBucketActive(packageName)
                StunLogger.i("MainActivity", "Shizuku permission granted")
            } else {
                // 授权失败或服务未启动
                StunLogger.w("MainActivity", "Shizuku permission denied")
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            app.fjj.stun.R.id.nav_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
            app.fjj.stun.R.id.nav_logs -> {
                startActivity(Intent(this, LogsActivity::class.java))
            }
            app.fjj.stun.R.id.nav_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
            }
        }
        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun setupRecyclerView() {
        val selectedId = SettingsManager.getSelectedProfileId(this)

        adapter = ProfileAdapter(
            selectedProfileId = selectedId,
            onProfileClick = { profile ->
                if (!isVpnRunning) {
                    SettingsManager.setSelectedProfileId(this, profile.id)
                    adapter.updateProfiles(adapter.getProfiles(), profile.id)
                    Toast.makeText(this, getString(app.fjj.stun.R.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(app.fjj.stun.R.string.main_profile_switch_disabled), Toast.LENGTH_SHORT).show()
                }
            },
            onEditClick = { profile ->
                val intent = Intent(this, ProfileEditActivity::class.java)
                intent.putExtra("EXTRA_PROFILE_ID", profile.id)
                startActivity(intent)
            },
            onDeleteClick = { profile ->
                thread {
                    ProfileManager.deleteProfile(this, profile)
                }
            },
            onShareClick = { profile ->
                showShareDialog(profile)
            }
        )

        binding.rvProfiles.layoutManager = LinearLayoutManager(this)
        binding.rvProfiles.adapter = adapter
    }

    private fun showShareDialog(profile: Profile) {
        val json = Gson().toJson(profile)
        val base64String = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        
        val displayMetrics = resources.displayMetrics
        val qrSize = (displayMetrics.widthPixels * 0.83).toInt()
        val bitmap = QRUtils.generateQRCode(base64String, qrSize, qrSize)

        if (bitmap != null) {
            val dialogView = LayoutInflater.from(this).inflate(app.fjj.stun.R.layout.dialog_qr_code, null)
            val ivQrCode = dialogView.findViewById<ImageView>(app.fjj.stun.R.id.iv_qr_code)
            val tvName = dialogView.findViewById<TextView>(app.fjj.stun.R.id.tv_profile_name)
            val btnClose = dialogView.findViewById<com.google.android.material.button.MaterialButton>(app.fjj.stun.R.id.btn_close)

            ivQrCode.setImageBitmap(bitmap)
            tvName.text = profile.name

            val dialog = AlertDialog.Builder(this)
                .setView(dialogView)
                .create()

            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnClose.setOnClickListener {
                dialog.dismiss()
            }

            dialog.show()
        } else {
            Toast.makeText(this, getString(app.fjj.stun.R.string.main_qr_fail), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(app.fjj.stun.R.menu.main_menu, menu)

        val filterItem = menu?.findItem(app.fjj.stun.R.id.action_filter)
        val searchView = filterItem?.actionView as? androidx.appcompat.widget.SearchView

        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText ?: "")
                return true
            }
        })

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            app.fjj.stun.R.id.action_add -> {
                showAddOptions()
                return true
            }
            app.fjj.stun.R.id.action_filter -> {
                return true
            }
            app.fjj.stun.R.id.action_export -> {
                exportLauncher.launch("stun_profiles_backup.json")
                return true
            }
            app.fjj.stun.R.id.action_import -> {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun showAddOptions() {
        val options = arrayOf(
            getString(app.fjj.stun.R.string.main_add_manually),
            getString(app.fjj.stun.R.string.main_scan_qr)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(app.fjj.stun.R.string.main_add_profile_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, ProfileEditActivity::class.java))
                    1 -> scanQRCode()
                }
            }
            .show()
    }

    private fun scanQRCode() {
        val options = ScanOptions()
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE)
        options.setPrompt(getString(app.fjj.stun.R.string.main_scan_prompt))
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(true)
        options.setOrientationLocked(false)
        barcodeLauncher.launch(options)
    }

    private fun testSelectedProfileLatency() {
        if (!isVpnRunning) {
            return
        }

        val selectedProfile = ProfileManager.getSelectedProfile(this)
        binding.tvStatus.text = getString(app.fjj.stun.R.string.main_testing_latency)

        var result = "Timeout"
        thread {

            try {
                val start = System.nanoTime()

                val url = URL("https://www.google.com/generate_204")

                val connection = if (isVpnRunning) {
                    val proxyAddr = InetSocketAddress.createUnresolved(
                        "127.0.0.1",
                        MyVpnService.SOCKS_PORT
                    )
                    val proxy = Proxy(Proxy.Type.SOCKS, proxyAddr)
                    url.openConnection(proxy) as HttpURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }

                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.requestMethod = "GET"

                connection.connect()

                val code = connection.responseCode

                val latency = (System.nanoTime() - start) / 1_000_000

                result = if (code in 200..399) {
                    "$latency ms"
                } else {
                    "HTTP $code"
                }

                connection.disconnect()

            } catch (e: Exception) {
                StunLogger.e("MainActivity", "Latency test failed", e)
                result = e.message ?: "Unknown Error"
            }

            runOnUiThread {
                adapter.updateDelay(selectedProfile.id, result)
                binding.tvStatus.text = if (isVpnRunning) {
                    getString(app.fjj.stun.R.string.main_connected) + " ($result)"
                } else {
                    getString(app.fjj.stun.R.string.main_disconnected) + " ($result)"
                }
            }
        }
    }

    private fun validateSelectedProfile(): Boolean {
        val profile = ProfileManager.getSelectedProfile(this)
        if (profile.id.isEmpty()) {
            Toast.makeText(this, getString(app.fjj.stun.R.string.error_no_profile_selected), Toast.LENGTH_SHORT).show()
            return false
        }

        if (profile.authType == Profile.AUTH_TYPE_PASSWORD) {
            if (profile.pass.isEmpty()) {
                Toast.makeText(this, getString(app.fjj.stun.R.string.error_field_required), Toast.LENGTH_SHORT).show()
                return false
            }
        } else if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY) {
            if (profile.privateKey.isEmpty()) {
                Toast.makeText(this, getString(app.fjj.stun.R.string.error_field_required), Toast.LENGTH_SHORT).show()
                return false
            }

            val checkResult = myssh.Myssh.checkIfKeyEncrypted(profile.privateKey)
            when (checkResult) {
                1L -> { // Password required
                    val decryptedPass = KeystoreUtils.decrypt(profile.keyPass)
                    if (decryptedPass.isEmpty()) {
                        Toast.makeText(this, getString(app.fjj.stun.R.string.error_key_password_required), Toast.LENGTH_SHORT).show()
                        return false
                    }
                    try {
                        if (!myssh.Myssh.validatePassphrase(profile.privateKey, decryptedPass)) {
                            Toast.makeText(this, getString(app.fjj.stun.R.string.error_invalid_key_password), Toast.LENGTH_SHORT).show()
                            return false
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this, getString(app.fjj.stun.R.string.error_invalid_key_password), Toast.LENGTH_SHORT).show()
                        return false
                    }
                }
                2L -> { // Incorrect format
                    Toast.makeText(this, getString(app.fjj.stun.R.string.error_invalid_private_key), Toast.LENGTH_SHORT).show()
                    return false
                }
            }
        }
        return true
    }

    private fun handleStartStop() {
        val mode = SettingsManager.getServiceMode(this)
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED

        if (currentState == VpnState.CONNECTED || currentState == VpnState.RECONNECTING) {
            isStopping = true
            binding.progressBar.visibility = android.view.View.VISIBLE
            if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
                stopTProxyProcess()
            } else {
                stopVpnProcess()
            }
        } else {
            if (currentState == VpnState.CONNECTING) return
            if (!validateSelectedProfile()) return

            isStopping = false
            applyShizukuKeepAlive()
            if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
                startTProxyProcess()
            } else {
                startVpnProcess()
            }
        }
    }

    private fun startVpnProcess() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            checkAndRequestNotificationPermission()
        }
    }

    private fun stopVpnProcess() {
        stopVpnService()
    }

    private fun startTProxyProcess() {
        checkAndRequestNotificationPermission()
    }

    private fun stopTProxyProcess() {
        stopTProxyService()
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                val mode = SettingsManager.getServiceMode(this)
                if (mode == SettingsManager.SERVICE_MODE_TPROXY) startTProxyService() else startVpnService()
            }
        } else {
            val mode = SettingsManager.getServiceMode(this)
            if (mode == SettingsManager.SERVICE_MODE_TPROXY) startTProxyService() else startVpnService()
        }
    }

    private fun <T> startServiceInBackground(serviceClass: Class<T>, actionStr: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val intent = Intent(this@MainActivity, serviceClass)
            intent.action = actionStr
            startService(intent)
        }
    }

    private fun stopVpnService() = startServiceInBackground(MyVpnService::class.java, MyVpnService.ACTION_STOP)
    private fun startVpnService() = startServiceInBackground(MyVpnService::class.java, MyVpnService.ACTION_START)
    private fun stopTProxyService() = startServiceInBackground(MyTransparentProxyService::class.java, MyTransparentProxyService.ACTION_STOP)
    private fun startTProxyService() = startServiceInBackground(MyTransparentProxyService::class.java, MyTransparentProxyService.ACTION_START)

    private fun exportProfilesToUri(uri: android.net.Uri) {
        thread {
            try {
                val profiles = ProfileManager.getProfiles(this)
                val json = Gson().toJson(profiles)
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(json.toByteArray())
                }
                runOnUiThread {
                    Toast.makeText(this, getString(app.fjj.stun.R.string.export_success), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                StunLogger.e("MainActivity", "Export failed", e)
                runOnUiThread {
                    Toast.makeText(this, getString(app.fjj.stun.R.string.export_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun importProfilesFromUri(uri: android.net.Uri) {
        thread {
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    val json = inputStream.bufferedReader().use { it.readText() }
                    val type = object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
                    val profiles: List<Profile> = Gson().fromJson(json, type)
                    
                    profiles.forEach { profile ->
                        // Ensure unique ID for imported profiles to avoid conflicts
                        val newProfile = profile.copy(id = java.util.UUID.randomUUID().toString())
                        ProfileManager.addProfile(this, newProfile)
                    }
                    
                    runOnUiThread {
                        Toast.makeText(this, getString(app.fjj.stun.R.string.import_success, profiles.size), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                StunLogger.e("MainActivity", "Import failed", e)
                runOnUiThread {
                    Toast.makeText(this, getString(app.fjj.stun.R.string.import_failed, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}