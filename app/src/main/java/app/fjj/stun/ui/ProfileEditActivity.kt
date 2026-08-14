package app.fjj.stun.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import app.fjj.stun.databinding.ActivityProfileEditBinding
import app.fjj.stun.repo.Profile
import app.fjj.stun.ui.viewmodel.ProfileEditViewModel
import androidx.activity.viewModels
import app.fjj.stun.util.KeystoreUtils
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProfileEditActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private val viewModel: ProfileEditViewModel by viewModels()
    private var profileId: String? = null
    private var currentProfile: Profile = Profile()
    private lateinit var filterModes: Array<String>
    private val authTypes = arrayOf(Profile.AUTH_TYPE_PASSWORD, Profile.AUTH_TYPE_PRIVATEKEY)
    private val udpgwVersions = arrayOf("tun2proxy", "badvpn")
    private val alpnOptionsH3 = arrayOf("h3", "h2", "http/1.1", "h3,h2,http/1.1", "h3,h2", "h2,http/1.1")
    private val alpnOptionsNoH3 = arrayOf("h2", "http/1.1", "h2,http/1.1")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupThemeAndNavigation()
        setupListeners()
        observeViewModel()
        
        viewModel.loadProfile(profileId)
    }

    private fun setupThemeAndNavigation() {
        filterModes = arrayOf(
            getString(app.fjj.stun.R.string.filter_disallow_mode),
            getString(app.fjj.stun.R.string.filter_allow_mode)
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        profileId = intent.getStringExtra("EXTRA_PROFILE_ID")
        val isEdit = profileId != null
        supportActionBar?.title = if (isEdit) getString(app.fjj.stun.R.string.edit_profile) else getString(app.fjj.stun.R.string.add_profile)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(left = systemBars.left, right = systemBars.right, top = systemBars.top)
            binding.btnSave.parent.let { 
                (it as View).updatePadding(bottom = systemBars.bottom + ime.bottom + 16)
            }
            insets
        }
    }

    private fun setupListeners() {
        // Dropdown changes
        binding.spinnerTunnelType.setOnItemClickListener { _, _, _, _ -> updateUIBasedOnSettings() }
        binding.spinnerAuthType.setOnItemClickListener { _, _, position, _ -> updateAuthTypeVisibility(authTypes[position]) }
        binding.spinnerAlpn.setOnItemClickListener { _, _, _, _ -> /* Update logic if needed */ }

        // Switch changes
        binding.switchDnsOverride.setOnCheckedChangeListener { _, isChecked -> binding.layoutDnsOverride.isVisible = isChecked }
        binding.switchAuthRequired.setOnCheckedChangeListener { _, _ -> updateProxyAuthVisibility() }
        binding.switchAppFilterOverride.setOnCheckedChangeListener { _, isChecked -> binding.layoutAppFilterOverride.isVisible = isChecked }
        binding.switchVerifySshFingerprint.setOnCheckedChangeListener { _, isChecked -> binding.layoutSshFingerprint.isVisible = isChecked }
        binding.switchVerifyCertFingerprint.setOnCheckedChangeListener { _, isChecked -> binding.layoutCertFingerprint.isVisible = isChecked }
        binding.switchEnableCustomPath.setOnCheckedChangeListener { _, _ -> updateUIBasedOnSettings() }

        // Real-time Validation & Error Clearing
        binding.etSshAddr.doAfterTextChanged { binding.layoutSshAddr.error = null }
        binding.etProxyAddr.doAfterTextChanged { binding.layoutProxyAddr.error = null }
        binding.etPrivateKey.doAfterTextChanged { binding.layoutPrivateKey.error = null }
        binding.etPass.doAfterTextChanged { binding.layoutPass.error = null }

        // App Filter Dialog
        binding.etFilterApps.setOnClickListener {
            val fragment = AppFilterDialogFragment.newInstance(binding.etFilterApps.text.toString())
            fragment.setOnAppFilterSelectedListener(object : AppFilterDialogFragment.OnAppFilterSelectedListener {
                override fun onAppFilterSelected(selectedPackages: String) {
                    binding.etFilterApps.setText(selectedPackages)
                }
            })
            fragment.show(supportFragmentManager, "AppFilterDialog")
        }

        binding.btnSave.setOnClickListener { validateAndSave() }
    }

    private fun observeViewModel() {
        viewModel.profile.observe(this) { profile ->
            currentProfile = profile
            bindProfileToUI(profile)
        }

        viewModel.saveResult.observe(this) { success ->
            if (success) {
                Toast.makeText(this, getString(app.fjj.stun.R.string.profile_saved), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun bindProfileToUI(profile: Profile) {
        binding.apply {
            etName.setText(profile.name)
            etSshAddr.setText(profile.sshAddr)
            etUser.setText(profile.user)
            
            spinnerAuthType.setText(if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY) 
                getString(app.fjj.stun.R.string.auth_key) else getString(app.fjj.stun.R.string.auth_password), false)
            updateAuthTypeVisibility(profile.authType)
            
            etPrivateKey.setText(profile.privateKey)
            etKeyPass.setText("") // Clear for security

            spinnerTunnelType.setText(profile.tunnelType, false)
            etHttpPayload.setText(profile.httpPayload)
            switchDisableStatusCheck.isChecked = profile.disableStatusCheck
            etProxyAddr.setText(profile.proxyAddr)
            etCustomHost.setText(profile.customHost)
            etServerName.setText(profile.serverName)
            switchEnableCustomPath.isChecked = profile.enableCustomPath
            etCustomPath.setText(profile.customPath)

            switchAuthRequired.isChecked = profile.proxyAuthRequired
            etAuthToken.setText(profile.proxyAuthToken)
            etAuthUser.setText(profile.proxyAuthUser)
            etAuthPass.setText(profile.proxyAuthPass)

            switchVerifySshFingerprint.isChecked = profile.verifyFingerprint
            layoutSshFingerprint.isVisible = profile.verifyFingerprint
            etSshFingerprint.setText(profile.serverFingerprint)

            switchVerifyCertFingerprint.isChecked = profile.verifyCertFingerprint
            layoutCertFingerprint.isVisible = profile.verifyCertFingerprint
            etCertFingerprint.setText(profile.serverCertFingerprint)

            spinnerAlpn.setText(profile.alpn, false)

            switchDnsOverride.isChecked = profile.dnsOverride
            layoutDnsOverride.isVisible = profile.dnsOverride
            etRemoteDns.setText(profile.remoteDns)
            etLocalDns.setText(profile.localDns)
            spinnerUdpgwVersion.setText(profile.udpgwVersion, false)
            etUdpgwAddr.setText(profile.udpgwAddr)
            etGeositeDirect.setText(profile.geositeDirect)
            etGeoipDirect.setText(profile.geoipDirect)

            switchAppFilterOverride.isChecked = profile.appFilterOverride
            layoutAppFilterOverride.isVisible = profile.appFilterOverride
            spinnerFilterMode.setText(if (profile.filterMode == 1) 
                getString(app.fjj.stun.R.string.filter_allow_mode) else getString(app.fjj.stun.R.string.filter_disallow_mode), false)
            etFilterApps.setText(profile.filterApps)

            updateUIBasedOnSettings()
            setupAdapters()
        }
    }

    private fun setupAdapters() {
        binding.apply {
            spinnerTunnelType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, Profile.getAllTunnelTypes()))
            spinnerAuthType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, authTypes.map {
                if (it == Profile.AUTH_TYPE_PASSWORD) getString(app.fjj.stun.R.string.auth_password) else getString(app.fjj.stun.R.string.auth_key)
            }))
            spinnerFilterMode.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, filterModes))
            spinnerUdpgwVersion.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, udpgwVersions))
            
            val isXhttp = spinnerTunnelType.text.toString() == Profile.TUNNEL_TYPE_XHTTP
            spinnerAlpn.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, if (isXhttp) alpnOptionsH3 else alpnOptionsNoH3))
        }
    }

    private fun updateUIBasedOnSettings() {
        val selected = binding.spinnerTunnelType.text.toString()
        val isHttp = selected == Profile.TUNNEL_TYPE_HTTP
        val isBase = selected == Profile.TUNNEL_TYPE_BASE
        val isMasque = selected == Profile.TUNNEL_TYPE_MASQUE

        val isCustomPathSupported = selected in listOf(
            Profile.TUNNEL_TYPE_WS, Profile.TUNNEL_TYPE_WSS, Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_H2C,
            Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_GRPCC, Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_WT,
            Profile.TUNNEL_TYPE_XHTTP, Profile.TUNNEL_TYPE_XHTTPC
        )
        
        val isServerNameSupported = selected in listOf(
            Profile.TUNNEL_TYPE_TLS, Profile.TUNNEL_TYPE_WSS, Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_QUIC,
            Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_WT, Profile.TUNNEL_TYPE_MASQUE,
            Profile.TUNNEL_TYPE_XHTTP
        )

        binding.apply {
            layoutHttpPayload.isVisible = isHttp
            layoutProxyAddr.isVisible = !isBase
            layoutCustomHost.isVisible = !isBase && selected != Profile.TUNNEL_TYPE_TLS && selected != Profile.TUNNEL_TYPE_QUIC
            layoutServerName.isVisible = isServerNameSupported
            
            switchEnableCustomPath.isVisible = isMasque
            layoutCustomPath.isVisible = (isMasque && switchEnableCustomPath.isChecked) || isCustomPathSupported
            
            val isXhttp = selected == Profile.TUNNEL_TYPE_XHTTP || selected == Profile.TUNNEL_TYPE_XHTTPC
            layoutAlpn.isVisible = isXhttp
            
            switchDisableStatusCheck.isVisible = isHttp
            switchAuthRequired.isVisible = isHttp || selected in listOf(
                Profile.TUNNEL_TYPE_WS, Profile.TUNNEL_TYPE_WSS, Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_H2C,
                Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_GRPCC, Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_WT,
                Profile.TUNNEL_TYPE_MASQUE, Profile.TUNNEL_TYPE_XHTTP, Profile.TUNNEL_TYPE_XHTTPC
            )

            updateProxyAuthVisibility()
            
            switchVerifyCertFingerprint.isVisible = isServerNameSupported
            layoutCertFingerprint.isVisible = isServerNameSupported && switchVerifyCertFingerprint.isChecked
        }
    }

    private fun updateProxyAuthVisibility() {
        val selected = binding.spinnerTunnelType.text.toString()
        val isEnabled = binding.switchAuthRequired.isVisible && binding.switchAuthRequired.isChecked
        
        val isTokenMode = selected in listOf(
            Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_H2C, Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_GRPCC,
            Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_WT, Profile.TUNNEL_TYPE_MASQUE, Profile.TUNNEL_TYPE_XHTTP, Profile.TUNNEL_TYPE_XHTTPC
        )
        val isUserPassMode = selected in listOf(Profile.TUNNEL_TYPE_WS, Profile.TUNNEL_TYPE_WSS, Profile.TUNNEL_TYPE_HTTP)

        binding.layoutAuthToken.isVisible = isEnabled && isTokenMode
        binding.layoutAuthUser.isVisible = isEnabled && isUserPassMode
        binding.layoutAuthPass.isVisible = isEnabled && isUserPassMode
    }

    private fun updateAuthTypeVisibility(authType: String) {
        val isKey = authType == Profile.AUTH_TYPE_PRIVATEKEY
        binding.apply {
            layoutPass.isVisible = !isKey
            layoutPrivateKey.isVisible = isKey
            layoutKeyPass.isVisible = isKey
        }
    }

    private fun validateAndSave() {
        val isKeyAuth = binding.spinnerAuthType.text.toString() == getString(app.fjj.stun.R.string.auth_key)
        
        // 1. Validate Address
        if (!validateAddress(binding.etSshAddr.text.toString(), binding.layoutSshAddr)) return
        if (binding.layoutProxyAddr.isVisible && !validateAddress(binding.etProxyAddr.text.toString(), binding.layoutProxyAddr)) return

        // 2. Validate Auth
        if (isKeyAuth) {
            val privateKey = binding.etPrivateKey.text.toString()
            if (privateKey.isBlank()) {
                binding.layoutPrivateKey.error = getString(app.fjj.stun.R.string.error_field_required)
                return
            }
            if (!privateKey.contains("BEGIN") || !privateKey.contains("PRIVATE KEY")) {
                binding.layoutPrivateKey.error = getString(app.fjj.stun.R.string.error_invalid_private_key)
                return
            }
            
            // Check Encryption
            val checkResult = myssh.Myssh.checkIfKeyEncrypted(privateKey)
            if (checkResult == 1L) {
                val inputPass = binding.etKeyPass.text.toString()
                val actualPass = inputPass.ifEmpty { KeystoreUtils.decrypt(currentProfile.keyPass) }
                if (actualPass.isEmpty() || !myssh.Myssh.validatePassphrase(privateKey, actualPass)) {
                    binding.layoutKeyPass.error = getString(app.fjj.stun.R.string.error_invalid_key_password)
                    return
                }
            } else if (checkResult == 2L) {
                binding.layoutPrivateKey.error = getString(app.fjj.stun.R.string.error_invalid_private_key)
                return
            }
        } else if (binding.etPass.text.toString().isBlank() && currentProfile.pass.isEmpty()) {
            binding.layoutPass.error = getString(app.fjj.stun.R.string.error_field_required)
            return
        }

        // 3. Validate Path
        val path = binding.etCustomPath.text.toString()
        if (binding.layoutCustomPath.isVisible && path.isNotBlank() && !path.startsWith("/")) {
            binding.layoutCustomPath.error = getString(app.fjj.stun.R.string.error_invalid_path)
            return
        }

        // 4. Save
        val isEdit = profileId != null
        val updatedProfile = currentProfile.copy(
            name = binding.etName.text.toString().ifBlank { "New Node" },
            sshAddr = binding.etSshAddr.text.toString(),
            user = binding.etUser.text.toString(),
            authType = if (isKeyAuth) Profile.AUTH_TYPE_PRIVATEKEY else Profile.AUTH_TYPE_PASSWORD,
            pass = binding.etPass.text.toString().ifEmpty { currentProfile.pass },
            privateKey = binding.etPrivateKey.text.toString(),
            keyPass = if (binding.etKeyPass.text.toString().isNotEmpty()) KeystoreUtils.encrypt(binding.etKeyPass.text.toString()) else currentProfile.keyPass,
            tunnelType = binding.spinnerTunnelType.text.toString(),
            httpPayload = binding.etHttpPayload.text.toString(),
            disableStatusCheck = binding.switchDisableStatusCheck.isChecked,
            proxyAddr = binding.etProxyAddr.text.toString(),
            customHost = binding.etCustomHost.text.toString(),
            serverName = binding.etServerName.text.toString(),
            enableCustomPath = binding.switchEnableCustomPath.isChecked,
            customPath = binding.etCustomPath.text.toString(),
            dnsOverride = binding.switchDnsOverride.isChecked,
            remoteDns = binding.etRemoteDns.text.toString(),
            localDns = binding.etLocalDns.text.toString(),
            udpgwVersion = binding.spinnerUdpgwVersion.text.toString(),
            udpgwAddr = binding.etUdpgwAddr.text.toString(),
            geositeDirect = binding.etGeositeDirect.text.toString(),
            geoipDirect = binding.etGeoipDirect.text.toString(),
            appFilterOverride = binding.switchAppFilterOverride.isChecked,
            filterMode = if (binding.spinnerFilterMode.text.toString() == getString(app.fjj.stun.R.string.filter_allow_mode)) 1 else 0,
            filterApps = binding.etFilterApps.text.toString(),
            verifyFingerprint = binding.switchVerifySshFingerprint.isChecked,
            serverFingerprint = binding.etSshFingerprint.text.toString(),
            verifyCertFingerprint = binding.switchVerifyCertFingerprint.isChecked,
            serverCertFingerprint = binding.etCertFingerprint.text.toString(),
            alpn = binding.spinnerAlpn.text.toString(),
            proxyAuthRequired = binding.switchAuthRequired.isChecked,
            proxyAuthToken = binding.etAuthToken.text.toString(),
            proxyAuthUser = binding.etAuthUser.text.toString(),
            proxyAuthPass = binding.etAuthPass.text.toString()
        )

        viewModel.saveProfile(updatedProfile, isEdit)
    }

    private fun validateAddress(content: String, layout: com.google.android.material.textfield.TextInputLayout): Boolean {
        if (content.isBlank()) {
            layout.error = getString(app.fjj.stun.R.string.error_field_required)
            return false
        }
        // IPv6
        if (content.startsWith("[") && Regex("""^\[([0-9a-fA-F:]+)\]:(\d+)$""").matches(content)) return true
        // IPv4 / Domain
        if (Regex("""^([^:]+):(\d+)$""").matches(content)) return true
        
        layout.error = getString(app.fjj.stun.R.string.error_invalid_address)
        return false
    }

    override fun onSupportNavigateUp(): Boolean {
        if (hasUnsavedChanges()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(app.fjj.stun.R.string.unsaved_changes_title)
                .setMessage(app.fjj.stun.R.string.unsaved_changes_msg)
                .setPositiveButton(app.fjj.stun.R.string.ok) { _, _ -> finish() }
                .setNegativeButton(app.fjj.stun.R.string.cancel, null)
                .show()
        } else {
            finish()
        }
        return true
    }

    private fun hasUnsavedChanges(): Boolean {
        // Simplified check: only check name for now, or compare entire profile
        return binding.etName.text.toString() != currentProfile.name
    }
}
