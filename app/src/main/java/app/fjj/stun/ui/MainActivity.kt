package app.fjj.stun.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
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
import app.fjj.stun.repo.GostRepository
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
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
            Toast.makeText(this, "VPN Permission Denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents != null) {
            try {
                val profile = Gson().fromJson(result.contents, Profile::class.java)
                // Ensure it gets a new ID if it's a clone/import
                val newProfile = profile.copy(id = java.util.UUID.randomUUID().toString())
                thread {
                    ProfileManager.addProfile(this, newProfile)
                    runOnUiThread {
                        Toast.makeText(this, "Profile added: ${newProfile.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid QR Code format", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Trigger GeoData update check on startup
        app.fjj.stun.repo.SettingsManager.checkAndUpdateGeoData(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val statusBarPaddingBottom = binding.statusBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            binding.toolbar.updatePadding(top = systemBars.top)
            binding.statusBar.updatePadding(bottom = statusBarPaddingBottom + navBars.bottom)
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

        GostRepository.vpnStatus.observe(this) { running ->
            isVpnRunning = running
            if (running) {
                binding.fabStartStop.setImageResource(android.R.drawable.ic_media_pause)
                binding.tvStatus.text = "Connected, tap to check connection"
            } else {
                binding.fabStartStop.setImageResource(android.R.drawable.ic_media_play)
                binding.tvStatus.text = "Disconnected, tap to start"
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
                Toast.makeText(this, "Selected: ${profile.name}", Toast.LENGTH_SHORT).show()
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
        val bitmap = QRUtils.generateQRCode(json, 500, 500)

        if (bitmap != null) {
            val dialogView = LayoutInflater.from(this).inflate(app.fjj.stun.R.layout.dialog_qr_code, null)
            val ivQrCode = dialogView.findViewById<ImageView>(app.fjj.stun.R.id.iv_qr_code)
            val tvName = dialogView.findViewById<TextView>(app.fjj.stun.R.id.tv_profile_name)

            ivQrCode.setImageBitmap(bitmap)
            tvName.text = profile.name

            AlertDialog.Builder(this)
                .setTitle("Share Profile")
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show()
        } else {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
    }

    private fun refreshProfiles() {
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
        val options = arrayOf("Add Manually", "Scan QR Code")
        AlertDialog.Builder(this)
            .setTitle("Add Profile")
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
        options.setPrompt("Scan a profile QR code")
        options.setCameraId(0)
        options.setBeepEnabled(false)
        options.setBarcodeImageEnabled(true)
        barcodeLauncher.launch(options)
    }

    private fun testSelectedProfileLatency() {
        val selectedProfile = ProfileManager.getSelectedProfile(this)
        binding.tvStatus.text = "Testing latency..."

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
                result = e.message ?: "Unknown Error"
            }

            runOnUiThread {
                adapter.updateDelay(selectedProfile.id, result)
                binding.tvStatus.text = if (isVpnRunning) "Connected ($result)" else "Disconnected ($result)"
            }
        }
    }

    private fun prepareVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnLauncher.launch(intent)
        } else {
            isVpnRunning = GostRepository.vpnStatus.value ?: false
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
