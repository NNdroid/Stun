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
import app.fjj.stun.ui.viewmodel.SettingsState
import app.fjj.stun.ui.viewmodel.SettingsViewModel
import androidx.activity.viewModels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val viewModel: SettingsViewModel by viewModels()
    private val logLevels = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
    private val udpgwVersions = arrayOf("tun2proxy", "badvpn")
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
                    viewModel.loadSettings()
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateNow.text = getString(app.fjj.stun.R.string.update_now)
                    Toast.makeText(this, getString(app.fjj.stun.R.string.geodata_success), Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        viewModel.settingsState.observe(this) { state ->
            binding.spinnerServiceMode.setText(if (state.serviceMode == SettingsManager.SERVICE_MODE_TPROXY) 
                getString(app.fjj.stun.R.string.service_mode_tproxy) else getString(app.fjj.stun.R.string.service_mode_vpn), false)

            val langIndex = languageValues.indexOf(state.language)
            binding.spinnerLanguage.setText(if (langIndex >= 0) languageLabels[langIndex] else languageLabels[0], false)

            binding.spinnerLogLevel.setText(state.logLevel, false)
            binding.etRemoteDnsServer.setText(state.remoteDns)
            binding.etLocalDnsServer.setText(state.localDns)
            binding.spinnerUdpgwVersion.setText(state.udpgwVersion, false)
            binding.etUdpgwAddr.setText(state.udpgwAddr)
            binding.spinnerFilterMode.setText(if (state.filterMode == 1) getString(app.fjj.stun.R.string.filter_allow_mode) else getString(app.fjj.stun.R.string.filter_disallow_mode), false)
            binding.etFilterApps.setText(state.filterApps)
            binding.etGeositeUrl.setText(state.geositeUrl)
            binding.etGeoipUrl.setText(state.geoipUrl)
            binding.etUpdateInterval.setText(state.updateInterval.toString())
            binding.etGeositeDirect.setText(state.geositeDirect)
            binding.etGeoipDirect.setText(state.geoipDirect)

            updateLastUpdateText(state.lastUpdateTime)

            // Setup adapters after setting text to prevent filtering
            setupAdapters()
        }

        viewModel.loadSettings()
    }

    private fun setupAdapters() {
        binding.spinnerServiceMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, serviceModes))
        binding.spinnerLanguage.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languageLabels))
        binding.spinnerLogLevel.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels))
        binding.spinnerUdpgwVersion.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, udpgwVersions))
        binding.spinnerFilterMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, filterModes))
    }

    private fun updateLastUpdateText(lastUpdate: Long) {
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
        
        val currentState = SettingsState(
            serviceMode = serviceMode,
            logLevel = binding.spinnerLogLevel.text.toString(),
            remoteDns = binding.etRemoteDnsServer.text.toString(),
            localDns = binding.etLocalDnsServer.text.toString(),
            udpgwVersion = binding.spinnerUdpgwVersion.text.toString(),
            udpgwAddr = binding.etUdpgwAddr.text.toString(),
            geositeUrl = binding.etGeositeUrl.text.toString(),
            geoipUrl = binding.etGeoipUrl.text.toString(),
            updateInterval = binding.etUpdateInterval.text.toString().toLongOrNull() ?: 0L,
            geositeDirect = binding.etGeositeDirect.text.toString(),
            geoipDirect = binding.etGeoipDirect.text.toString(),
            filterMode = if (binding.spinnerFilterMode.text.toString() == getString(app.fjj.stun.R.string.filter_allow_mode)) 1 else 0,
            filterApps = binding.etFilterApps.text.toString()
        )

        viewModel.saveSettings(currentState)

        val langIndex = languageLabels.indexOf(binding.spinnerLanguage.text.toString())
        if (langIndex >= 0) {
            val newLang = languageValues[langIndex]
            if (newLang != SettingsManager.getLanguage(this)) {
                SettingsManager.saveLanguage(this, newLang)
                
                // Use the modern way to set locales globally
                val localeTag = when (newLang) {
                    "en" -> "en"
                    "zh" -> "zh-CN"
                    "zh-rTW" -> "zh-TW"
                    "de" -> "de"
                    "fr" -> "fr"
                    "ja" -> "ja"
                    else -> ""
                }
                val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags(localeTag)
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                
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
