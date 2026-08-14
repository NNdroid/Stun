package app.fjj.stun.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import app.fjj.stun.R
import app.fjj.stun.databinding.ActivitySettingsBinding
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.ui.viewmodel.SettingsState
import app.fjj.stun.ui.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.*

class SettingsFragment : Fragment() {

    private var _binding: ActivitySettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()
    
    private val logLevels = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
    private val udpgwVersions = arrayOf("tun2proxy", "badvpn")
    private lateinit var serviceModes: Array<String>
    private lateinit var filterModes: Array<String>
    private lateinit var languageLabels: Array<String>
    private val languageValues = arrayOf("auto", "en", "zh", "zh-rTW", "de", "fr", "ja")

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivitySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize resource-dependent arrays
        serviceModes = arrayOf(
            getString(R.string.service_mode_vpn),
            getString(R.string.service_mode_tproxy)
        )
        filterModes = arrayOf(
            getString(R.string.filter_disallow_mode),
            getString(R.string.filter_allow_mode)
        )
        languageLabels = arrayOf(
            getString(R.string.lang_auto),
            getString(R.string.lang_en),
            getString(R.string.lang_zh_cn),
            getString(R.string.lang_zh_tw),
            getString(R.string.lang_de),
            getString(R.string.lang_fr),
            getString(R.string.lang_ja)
        )

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener {
            (requireActivity() as MainActivity).navigateToHome()
        }

        val initialPaddingBottom = binding.btnSave.parent.let { (it as View).paddingBottom }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            
            binding.btnSave.parent.let { 
                (it as View).updatePadding(bottom = initialPaddingBottom + systemBars.bottom + ime.bottom) 
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
            fragment.show(parentFragmentManager, "AppFilterDialog")
        }
        binding.etFilterApps.isFocusable = false
        binding.etFilterApps.isClickable = true

        binding.btnUpdateNow.setOnClickListener {
            binding.btnUpdateNow.isEnabled = false
            binding.btnUpdateNow.text = getString(R.string.updating)
            SettingsManager.updateGeoData(requireContext()) {
                activity?.runOnUiThread {
                    viewModel.loadSettings()
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateNow.text = getString(R.string.update_now)
                    Toast.makeText(requireContext(), getString(R.string.geodata_success), Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        viewModel.settingsState.observe(viewLifecycleOwner) { state ->
            binding.spinnerServiceMode.setText(if (state.serviceMode == SettingsManager.SERVICE_MODE_TPROXY) 
                getString(R.string.service_mode_tproxy) else getString(R.string.service_mode_vpn), false)

            val langIndex = languageValues.indexOf(state.language)
            binding.spinnerLanguage.setText(if (langIndex >= 0) languageLabels[langIndex] else languageLabels[0], false)

            binding.spinnerLogLevel.setText(state.logLevel, false)
            binding.etRemoteDnsServer.setText(state.remoteDns)
            binding.etLocalDnsServer.setText(state.localDns)
            binding.spinnerUdpgwVersion.setText(state.udpgwVersion, false)
            binding.etUdpgwAddr.setText(state.udpgwAddr)
            binding.spinnerFilterMode.setText(if (state.filterMode == 1) getString(R.string.filter_allow_mode) else getString(R.string.filter_disallow_mode), false)
            binding.etFilterApps.setText(state.filterApps)
            binding.etGeositeUrl.setText(state.geositeUrl)
            binding.etGeoipUrl.setText(state.geoipUrl)
            binding.etUpdateInterval.setText(state.updateInterval.toString())
            binding.etGeositeDirect.setText(state.geositeDirect)
            binding.etGeoipDirect.setText(state.geoipDirect)

            updateLastUpdateText(state.lastUpdateTime)
            setupAdapters()
        }

        viewModel.loadSettings()
    }

    private fun setupAdapters() {
        val context = requireContext()
        binding.spinnerServiceMode.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, serviceModes))
        binding.spinnerLanguage.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, languageLabels))
        binding.spinnerLogLevel.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, logLevels))
        binding.spinnerUdpgwVersion.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, udpgwVersions))
        binding.spinnerFilterMode.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, filterModes))
    }

    private fun updateLastUpdateText(lastUpdate: Long) {
        if (lastUpdate > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvLastUpdate.text = getString(R.string.last_updated, sdf.format(Date(lastUpdate * 1000)))
        } else {
            binding.tvLastUpdate.text = getString(R.string.last_updated, getString(R.string.never))
        }
    }

    private fun saveSettings() {
        val serviceMode = if (binding.spinnerServiceMode.text.toString() == getString(R.string.service_mode_tproxy)) 
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
            filterMode = if (binding.spinnerFilterMode.text.toString() == getString(R.string.filter_allow_mode)) 1 else 0,
            filterApps = binding.etFilterApps.text.toString()
        )

        viewModel.saveSettings(currentState)

        val langIndex = languageLabels.indexOf(binding.spinnerLanguage.text.toString())
        if (langIndex >= 0) {
            val newLang = languageValues[langIndex]
            if (newLang != SettingsManager.getLanguage(requireContext())) {
                SettingsManager.saveLanguage(requireContext(), newLang)
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
        Toast.makeText(requireContext(), getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
        (requireActivity() as MainActivity).navigateToHome()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
