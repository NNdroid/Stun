package app.fjj.stun.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.updateLayoutParams
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import app.fjj.stun.databinding.ActivityMainBinding
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.util.QRUtils
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ProfileAdapter
    private var isVpnRunning = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, getString(app.fjj.stun.R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents != null) {
            try {
                // 1. 先将扫到的 Base64 字符串解码还原为普通字符串 (JSON)
                // 使用 Base64.DEFAULT 进行解码即可兼容我们之前生成的 NO_WRAP 格式
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
                // 这里不仅能捕获 Gson 解析失败，也能捕获 Base64 解码异常
                StunLogger.e("MainActivity", "Scan QR Code failed", e)
                Toast.makeText(this, getString(app.fjj.stun.R.string.invalid_qr), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Trigger GeoData update check on startup
        SettingsManager.checkAndUpdateGeoData(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val initialRvPadding = binding.rvProfiles.paddingBottom
        val initialFabMargin = (binding.fabStartStop.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin
        val initialStatusMargin = (binding.statusCard.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            
            binding.rvProfiles.updatePadding(bottom = initialRvPadding + systemBars.bottom)
            binding.fabStartStop.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = initialFabMargin + systemBars.bottom
            }
            binding.statusCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = initialStatusMargin + systemBars.bottom
            }
            insets
        }

        setupRecyclerView()

        ProfileManager.getProfilesLiveData(this).observe(this) { profiles ->
            val selectedId = SettingsManager.getSelectedProfileId(this)
            adapter.updateProfiles(profiles, selectedId)
        }

        binding.fabStartStop.setOnClickListener {
            prepareVpn()
        }

        binding.statusBar.setOnClickListener {
            testSelectedProfileLatency()
        }

        StunRepository.vpnStatus.observe(this) { running ->
            isVpnRunning = running
            if (running) {
                binding.fabStartStop.extend()
                binding.fabStartStop.text = getString(app.fjj.stun.R.string.main_disconnect)
                binding.fabStartStop.setIconResource(app.fjj.stun.R.drawable.ic_pause)
                binding.tvStatus.text = getString(app.fjj.stun.R.string.main_connected)
                binding.ivStatusIcon.setColorFilter(getColor(app.fjj.stun.R.color.primary))
            } else {
                binding.fabStartStop.extend()
                binding.fabStartStop.text = getString(app.fjj.stun.R.string.main_connect)
                binding.fabStartStop.setIconResource(app.fjj.stun.R.drawable.ic_play)
                binding.tvStatus.text = getString(app.fjj.stun.R.string.main_disconnected)
                binding.ivStatusIcon.clearColorFilter()
            }
        }
    }

    private fun setupRecyclerView() {
        val selectedId = SettingsManager.getSelectedProfileId(this)

        adapter = ProfileAdapter(
            profiles = emptyList(),
            selectedProfileId = selectedId,
            onProfileClick = { profile ->
                SettingsManager.setSelectedProfileId(this, profile.id)
                adapter.updateProfiles(adapter.getProfiles(), profile.id)
                Toast.makeText(this, getString(app.fjj.stun.R.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
            },
            onEditClick = { profile ->
                val intent = Intent(this, ConfigActivity::class.java)
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
        // 1. 将 json 字符串转为 UTF-8 字节数组，然后进行 Base64 编码
        // 使用 Base64.NO_WRAP 避免生成多余的换行符 (\n)
        val base64String = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        // 2. 使用编码后的 base64 字符串生成二维码
        val bitmap = QRUtils.generateQRCode(base64String, 500, 500)

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
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            app.fjj.stun.R.id.action_add -> {
                showAddOptions()
                return true
            }
            app.fjj.stun.R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                return true
            }
            app.fjj.stun.R.id.action_logs -> {
                startActivity(Intent(this, LogsActivity::class.java))
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
                    0 -> startActivity(Intent(this, ConfigActivity::class.java))
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
        barcodeLauncher.launch(options)
    }

    private fun testSelectedProfileLatency() {
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

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            isVpnRunning = StunRepository.vpnStatus.value ?: false
            if (isVpnRunning) {
                stopVpnService()
            } else {
                startVpnService()
            }
        }
    }

    private fun stopVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = MyVpnService.ACTION_STOP
        startService(intent)
    }

    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = MyVpnService.ACTION_START
        startService(intent)
    }
}
