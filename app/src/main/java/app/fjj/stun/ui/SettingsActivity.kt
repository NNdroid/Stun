package app.fjj.stun.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivitySettingsBinding
import app.fjj.stun.repo.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val logLevels = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
    private lateinit var filterModes: Array<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filterModes = arrayOf(
            getString(app.fjj.stun.R.string.filter_disallow_mode),
            getString(app.fjj.stun.R.string.filter_allow_mode)
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val initialPaddingBottom = binding.btnSave.parent.let { (it as android.view.View).paddingBottom }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            
            // Apply bottom padding to the scrollable container's child to keep content above nav bar/keyboard
            binding.btnSave.parent.let { 
                (it as android.view.View).updatePadding(bottom = initialPaddingBottom + systemBars.bottom + ime.bottom) 
            }
            insets
        }

        loadSettings()

        binding.etFilterApps.setOnClickListener {
            val fragment = AppFilterDialogFragment.newInstance(binding.etFilterApps.text.toString())
            fragment.setOnAppFilterSelectedListener(object : AppFilterDialogFragment.OnAppFilterSelectedListener {
                override fun onAppFilterSelected(selectedPackages: String) {
                    binding.etFilterApps.setText(selectedPackages)
                }
            })
            fragment.show(supportFragmentManager, "AppFilterDialog")
        }

        binding.etFilterApps.isFocusable = false
        binding.etFilterApps.isClickable = true

        // Setup Geo Data
        binding.etGeositeUrl.setText(SettingsManager.getGeositeUrl(this))
        binding.etGeoipUrl.setText(SettingsManager.getGeoipUrl(this))
        binding.etUpdateInterval.setText(SettingsManager.getUpdateInterval(this).toString())
        binding.etGeositeDirect.setText(SettingsManager.getGeositeDirect(this))
        binding.etGeoipDirect.setText(SettingsManager.getGeoipDirect(this))

        // Setup Last Update Time
        updateLastUpdateText()

        binding.btnUpdateNow.setOnClickListener {
            binding.btnUpdateNow.isEnabled = false
            binding.btnUpdateNow.text = getString(app.fjj.stun.R.string.updating)
            SettingsManager.updateGeoData(this) {
                runOnUiThread {
                    updateLastUpdateText()
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateNow.text = getString(app.fjj.stun.R.string.update_now)
                    Toast.makeText(this, getString(app.fjj.stun.R.string.geodata_success), Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSave.setOnClickListener {
            SettingsManager.saveLogLevel(this, binding.spinnerLogLevel.text.toString())
            SettingsManager.saveRemoteDnsServer(this, binding.etRemoteDnsServer.text.toString())
            SettingsManager.saveLocalDnsServer(this, binding.etLocalDnsServer.text.toString())
            SettingsManager.saveUdpgwAddr(this, binding.etUdpgwAddr.text.toString())
            
            SettingsManager.saveGeositeUrl(this, binding.etGeositeUrl.text.toString())
            SettingsManager.saveGeoipUrl(this, binding.etGeoipUrl.text.toString())
            val interval = binding.etUpdateInterval.text.toString().toLongOrNull() ?: 0L
            SettingsManager.saveUpdateInterval(this, interval)
            SettingsManager.saveGeositeDirect(this, binding.etGeositeDirect.text.toString())
            SettingsManager.saveGeoipDirect(this, binding.etGeoipDirect.text.toString())

            val filterMode = if (binding.spinnerFilterMode.text.toString() == getString(app.fjj.stun.R.string.filter_allow_mode)) 1 else 0
            SettingsManager.saveFilterMode(this, filterMode)
            SettingsManager.saveFilterApps(this, binding.etFilterApps.text.toString())

            Toast.makeText(this, getString(app.fjj.stun.R.string.settings_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun loadSettings() {
        thread {
            val logLevel = SettingsManager.getLogLevel(this)
            val remoteDns = SettingsManager.getRemoteDnsServer(this)
            val localDns = SettingsManager.getLocalDnsServer(this)
            val udpgw = SettingsManager.getUdpgwAddr(this)
            val filterMode = SettingsManager.getFilterMode(this)
            val filterApps = SettingsManager.getFilterApps(this)
            val geositeUrl = SettingsManager.getGeositeUrl(this)
            val geoipUrl = SettingsManager.getGeoipUrl(this)
            val interval = SettingsManager.getUpdateInterval(this)
            val geositeDirect = SettingsManager.getGeositeDirect(this)
            val geoipDirect = SettingsManager.getGeoipDirect(this)

            runOnUiThread {
                // Setup Log Level Spinner
                val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels)
                binding.spinnerLogLevel.setAdapter(adapter)
                binding.spinnerLogLevel.setText(logLevel, false)

                // Setup DNS Servers
                binding.etRemoteDnsServer.setText(remoteDns)
                binding.etLocalDnsServer.setText(localDns)
                binding.etUdpgwAddr.setText(udpgw)

                // Setup Application Filtering
                val filterAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, filterModes)
                binding.spinnerFilterMode.setAdapter(filterAdapter)
                binding.spinnerFilterMode.setText(if (filterMode == 1) getString(app.fjj.stun.R.string.filter_allow_mode) else getString(app.fjj.stun.R.string.filter_disallow_mode), false)
                binding.etFilterApps.setText(filterApps)

                // Setup Geo Data
                binding.etGeositeUrl.setText(geositeUrl)
                binding.etGeoipUrl.setText(geoipUrl)
                binding.etUpdateInterval.setText(interval.toString())
                binding.etGeositeDirect.setText(geositeDirect)
                binding.etGeoipDirect.setText(geoipDirect)

                // Setup Last Update Time
                updateLastUpdateText()
            }
        }
    }

    private fun updateLastUpdateText() {
        val lastUpdate = SettingsManager.getLastUpdateTime(this)
        if (lastUpdate > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvLastUpdate.text = getString(app.fjj.stun.R.string.last_updated, sdf.format(Date(lastUpdate * 1000)))
        } else {
            binding.tvLastUpdate.text = getString(app.fjj.stun.R.string.last_updated, getString(app.fjj.stun.R.string.never))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
