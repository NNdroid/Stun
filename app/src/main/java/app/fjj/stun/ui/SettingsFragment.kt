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
import app.fjj.stun.core.R as CoreR
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
    
    private var globalFocusChangeListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null

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
            getString(CoreR.string.service_mode_vpn),
            getString(CoreR.string.service_mode_tproxy)
        )
        filterModes = arrayOf(
            getString(CoreR.string.filter_disallow_mode),
            getString(CoreR.string.filter_allow_mode)
        )
        languageLabels = arrayOf(
            getString(CoreR.string.lang_auto),
            getString(CoreR.string.lang_en),
            getString(CoreR.string.lang_zh_cn),
            getString(CoreR.string.lang_zh_tw),
            getString(CoreR.string.lang_de),
            getString(CoreR.string.lang_fr),
            getString(CoreR.string.lang_ja)
        )

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener {
            (requireActivity() as MainActivity).navigateToHome()
        }

        val bottomBarHeight = (88 * resources.displayMetrics.density).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            _binding?.appBar?.updatePadding(top = systemBars.top)
            
            val bottomInset = maxOf(systemBars.bottom, ime.bottom)
            
            _binding?.btnSave?.parent?.let { 
                (it as View).updatePadding(bottom = bottomInset) 
            }
            
            _binding?.scrollView?.updatePadding(bottom = bottomInset + bottomBarHeight)
            
            if (ime.bottom > 0) {
                v.postDelayed({
                    activity?.currentFocus?.let { scrollToFocusedView(it) }
                }, 100)
            }
            
            insets
        }

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    val insets = ViewCompat.getRootWindowInsets(binding.root)
                    val ime = insets?.getInsets(WindowInsetsCompat.Type.ime())
                    if (ime != null && ime.bottom > 0) {
                        activity?.currentFocus?.let { scrollToFocusedView(it) }
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    return insets
                }
            }
        )

        globalFocusChangeListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            val scroll = _binding?.scrollView ?: return@OnGlobalFocusChangeListener
            if (newFocus != null && isDescendantOf(newFocus, scroll)) {
                scrollToFocusedView(newFocus)
            }
        }
        binding.scrollView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusChangeListener)

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
            binding.btnUpdateNow.text = getString(CoreR.string.updating)
            SettingsManager.updateGeoData(requireContext()) {
                activity?.runOnUiThread {
                    viewModel.loadSettings()
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateNow.text = getString(CoreR.string.update_now)
                    Toast.makeText(requireContext(), getString(CoreR.string.geodata_success), Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        viewModel.settingsState.observe(viewLifecycleOwner) { state ->
            binding.spinnerServiceMode.setText(if (state.serviceMode == SettingsManager.SERVICE_MODE_TPROXY) 
                getString(CoreR.string.service_mode_tproxy) else getString(CoreR.string.service_mode_vpn), false)

            val langIndex = languageValues.indexOf(state.language)
            binding.spinnerLanguage.setText(if (langIndex >= 0) languageLabels[langIndex] else languageLabels[0], false)

            binding.spinnerLogLevel.setText(state.logLevel, false)
            binding.etRemoteDnsServer.setText(state.remoteDns)
            binding.etLocalDnsServer.setText(state.localDns)
            binding.spinnerUdpgwVersion.setText(state.udpgwVersion, false)
            binding.etUdpgwAddr.setText(state.udpgwAddr)
            binding.spinnerFilterMode.setText(if (state.filterMode == 1) getString(CoreR.string.filter_allow_mode) else getString(CoreR.string.filter_disallow_mode), false)
            binding.etFilterApps.setText(state.filterApps)
            binding.etGeositeUrl.setText(state.geositeUrl)
            binding.etGeoipUrl.setText(state.geoipUrl)
            binding.etUpdateInterval.setText(state.updateInterval.toString())
            binding.etGeositeDirect.setText(state.geositeDirect)
            binding.etGeoipDirect.setText(state.geoipDirect)
            binding.switchShowNotificationSpeed.isChecked = SettingsManager.getShowNotificationSpeed(requireContext())

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
            binding.tvLastUpdate.text = getString(CoreR.string.last_updated, sdf.format(Date(lastUpdate * 1000)))
        } else {
            binding.tvLastUpdate.text = getString(CoreR.string.last_updated, getString(CoreR.string.never))
        }
    }

    private fun saveSettings() {
        SettingsManager.saveShowNotificationSpeed(requireContext(), binding.switchShowNotificationSpeed.isChecked)

        val serviceMode = if (binding.spinnerServiceMode.text.toString() == getString(CoreR.string.service_mode_tproxy)) 
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
            filterMode = if (binding.spinnerFilterMode.text.toString() == getString(CoreR.string.filter_allow_mode)) 1 else 0,
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
        Toast.makeText(requireContext(), getString(CoreR.string.settings_saved), Toast.LENGTH_SHORT).show()
        (requireActivity() as MainActivity).navigateToHome()
    }

    private fun scrollToFocusedView(view: View) {
        val scroll = _binding?.scrollView ?: return
        scroll.post {
            if (!isAdded || activity == null || _binding == null) return@post
            
            var target: View = view
            var p = view.parent
            while (p is View && p !== scroll) {
                if (p is com.google.android.material.textfield.TextInputLayout) {
                    target = p
                    break
                }
                p = p.parent
            }

            val rect = android.graphics.Rect()
            target.getDrawingRect(rect)
            try {
                scroll.offsetDescendantRectToMyCoords(target, rect)
                
                val density = resources.displayMetrics.density
                val bottomPadding = scroll.paddingBottom
                val visibleHeight = scroll.height - bottomPadding
                
                if (visibleHeight <= 0) return@post
                
                val targetTop = rect.top
                val targetBottom = rect.bottom
                val margin = (20 * density).toInt()
                
                val idealScrollY = maxOf(0, (targetBottom + margin) - visibleHeight)
                val finalScrollY = if (targetTop - margin < idealScrollY) {
                    maxOf(0, targetTop - margin)
                } else {
                    idealScrollY
                }
                
                scroll.smoothScrollTo(0, finalScrollY)
            } catch (_: Exception) {}
        }
    }

    private fun isDescendantOf(child: View, parent: View): Boolean {
        var p = child.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    override fun onDestroyView() {
        globalFocusChangeListener?.let {
            _binding?.scrollView?.viewTreeObserver?.removeOnGlobalFocusChangeListener(it)
        }
        globalFocusChangeListener = null
        super.onDestroyView()
        _binding = null
    }
}
