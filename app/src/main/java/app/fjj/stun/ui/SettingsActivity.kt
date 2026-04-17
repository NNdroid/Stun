package app.fjj.stun.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivitySettingsBinding
import app.fjj.stun.repo.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val logLevels = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
    private lateinit var serviceModes: Array<String>
    private lateinit var filterModes: Array<String>
    private lateinit var languageLabels: Array<String>
    private val languageValues = arrayOf("auto", "en", "zh", "zh-rTW", "de", "fr", "ja")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize resource-dependent arrays
        serviceModes = arrayOf(
            getString(app.fjj.stun.R.string.service_mode_vpn),
            getString(app.fjj.stun.R.string.service_mode_tproxy)
        )
        filterModes = arrayOf(
            getString(app.fjj.stun.R.string.filter_disallow_mode),
            getString(app.fjj.stun.R.string.filter_allow_mode)
        )
        languageLabels = arrayOf(
            getString(app.fjj.stun.R.string.lang_auto),
            getString(app.fjj.stun.R.string.lang_en),
            getString(app.fjj.stun.R.string.lang_zh_cn),
            getString(app.fjj.stun.R.string.lang_zh_tw),
            getString(app.fjj.stun.R.string.lang_de),
            getString(app.fjj.stun.R.string.lang_fr),
            getString(app.fjj.stun.R.string.lang_ja)
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Setup adapters immediately so UI doesn't flicker/jump
        setupAdapters()

        val initialPaddingBottom = binding.btnSave.parent.let { (it as android.view.View).paddingBottom }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            
            binding.btnSave.parent.let { 
                (it as android.view.View).updatePadding(bottom = initialPaddingBottom + systemBars.bottom + ime.bottom) 
            }
            insets
        }

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
            saveSettings()
        }

        loadSettings()
    }

    private fun setupAdapters() {
        binding.spinnerServiceMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, serviceModes))
        binding.spinnerLanguage.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languageLabels))
        binding.spinnerLogLevel.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels))
        binding.spinnerFilterMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, filterModes))
    }

    private fun loadSettings() {
        thread {
            val logLevel = SettingsManager.getLogLevel(this)
            val remoteDns = SettingsManager.getRemoteDnsServer(this)
            val localDns = SettingsManager.getLocalDnsServer(this)
            val udpgw = SettingsManager.getUdpgwAddr(this)
            val filterMode = SettingsManager.getFilterMode(this)
            val filterApps = SettingsManager.getFilterApps(this)
            val serviceMode = SettingsManager.getServiceMode(this)
            val language = SettingsManager.getLanguage(this)
            val geositeUrl = SettingsManager.getGeositeUrl(this)
            val geoipUrl = SettingsManager.getGeoipUrl(this)
            val interval = SettingsManager.getUpdateInterval(this)
            val geositeDirect = SettingsManager.getGeositeDirect(this)
            val geoipDirect = SettingsManager.getGeoipDirect(this)
            val lastUpdate = SettingsManager.getLastUpdateTime(this)

            runOnUiThread {
                binding.spinnerServiceMode.setText(if (serviceMode == SettingsManager.SERVICE_MODE_TPROXY) 
                    getString(app.fjj.stun.R.string.service_mode_tproxy) else getString(app.fjj.stun.R.string.service_mode_vpn), false)

                val langIndex = languageValues.indexOf(language)
                binding.spinnerLanguage.setText(if (langIndex >= 0) languageLabels[langIndex] else languageLabels[0], false)

                binding.spinnerLogLevel.setText(logLevel, false)
                binding.etRemoteDnsServer.setText(remoteDns)
                binding.etLocalDnsServer.setText(localDns)
                binding.etUdpgwAddr.setText(udpgw)
                binding.spinnerFilterMode.setText(if (filterMode == 1) getString(app.fjj.stun.R.string.filter_allow_mode) else getString(app.fjj.stun.R.string.filter_disallow_mode), false)
                binding.etFilterApps.setText(filterApps)
                binding.etGeositeUrl.setText(geositeUrl)
                binding.etGeoipUrl.setText(geoipUrl)
                binding.etUpdateInterval.setText(interval.toString())
                binding.etGeositeDirect.setText(geositeDirect)
                binding.etGeoipDirect.setText(geoipDirect)

                updateLastUpdateText(lastUpdate)
            }
        }
    }

    private fun updateLastUpdateText(lastUpdate: Long = SettingsManager.getLastUpdateTime(this)) {
        if (lastUpdate > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvLastUpdate.text = getString(app.fjj.stun.R.string.last_updated, sdf.format(Date(lastUpdate * 1000)))
        } else {
            binding.tvLastUpdate.text = getString(app.fjj.stun.R.string.last_updated, getString(app.fjj.stun.R.string.never))
        }
    }

    private fun saveSettings() {
        val serviceMode = if (binding.spinnerServiceMode.text.toString() == getString(app.fjj.stun.R.string.service_mode_tproxy)) 
            SettingsManager.SERVICE_MODE_TPROXY else SettingsManager.SERVICE_MODE_VPN
        SettingsManager.saveServiceMode(this, serviceMode)
        SettingsManager.saveLogLevel(this, binding.spinnerLogLevel.text.toString())
        SettingsManager.saveRemoteDnsServer(this, binding.etRemoteDnsServer.text.toString())
        SettingsManager.saveLocalDnsServer(this, binding.etLocalDnsServer.text.toString())
        SettingsManager.saveUdpgwAddr(this, binding.etUdpgwAddr.text.toString())
        SettingsManager.saveGeositeUrl(this, binding.etGeositeUrl.text.toString())
        SettingsManager.saveGeoipUrl(this, binding.etGeoipUrl.text.toString())
        SettingsManager.saveUpdateInterval(this, binding.etUpdateInterval.text.toString().toLongOrNull() ?: 0L)
        SettingsManager.saveGeositeDirect(this, binding.etGeositeDirect.text.toString())
        SettingsManager.saveGeoipDirect(this, binding.etGeoipDirect.text.toString())
        SettingsManager.saveFilterMode(this, if (binding.spinnerFilterMode.text.toString() == getString(app.fjj.stun.R.string.filter_allow_mode)) 1 else 0)
        SettingsManager.saveFilterApps(this, binding.etFilterApps.text.toString())

        val langIndex = languageLabels.indexOf(binding.spinnerLanguage.text.toString())
        if (langIndex >= 0) {
            val newLang = languageValues[langIndex]
            if (newLang != SettingsManager.getLanguage(this)) {
                SettingsManager.saveLanguage(this, newLang)
                app.fjj.stun.util.LocaleHelper.applyLocale(this)
                recreate()
                return
            }
        }
        Toast.makeText(this, getString(app.fjj.stun.R.string.settings_saved), Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
