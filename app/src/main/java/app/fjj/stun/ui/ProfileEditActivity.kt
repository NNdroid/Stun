package app.fjj.stun.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import app.fjj.stun.databinding.ActivityProfileEditBinding
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.Profile
import app.fjj.stun.ui.viewmodel.ProfileEditViewModel
import androidx.activity.viewModels
import app.fjj.stun.util.KeystoreUtils
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    private val dnsRecordTypes = arrayOf("txt", "null", "cname", "a", "aaaa", "mx", "srv", "ns")
    private val kcpCryptOptions = arrayOf("none", "aes", "aes-128", "aes-192", "aes-256", "aes-gcm", "salsa20", "sm4", "twofish", "blowfish", "cast5", "3des", "tea", "xtea", "xor")

    override fun onCreate(savedInstanceState: Bundle?) {
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
            getString(CoreR.string.filter_disallow_mode),
            getString(CoreR.string.filter_allow_mode)
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        profileId = intent.getStringExtra("EXTRA_PROFILE_ID")
        val isEdit = profileId != null
        supportActionBar?.title = if (isEdit) getString(CoreR.string.edit_profile) else getString(CoreR.string.add_profile)

        val bottomBarHeight = (72 * resources.displayMetrics.density).toInt() // Approx height of bottom bar including padding

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            
            v.updatePadding(left = systemBars.left, right = systemBars.right, top = systemBars.top)
            
            // Fixed bottom bar: only add navigation bar padding, NOT IME padding 
            // when adjustResize is active, otherwise it doubles up.
            val bottomBarParent = binding.btnSave.parent as View
            bottomBarParent.updatePadding(bottom = systemBars.bottom)
            
            // Since we use adjustResize, the window height is already reduced.
            // We just need to make sure the NestedScrollView has enough padding at the bottom 
            // to scroll the focused element above the Save button.
            binding.scrollView.updatePadding(bottom = systemBars.bottom + bottomBarHeight)
            
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

        // Fetch Fingerprint Buttons
        binding.btnFetchSshFingerprint.setOnClickListener {
            val sshAddr = binding.etSshAddr.text.toString().trim()
            if (sshAddr.isBlank()) {
                binding.layoutSshAddr.error = getString(CoreR.string.error_missing_ssh_addr)
                binding.etSshAddr.requestFocus()
                return@setOnClickListener
            }

            binding.btnFetchSshFingerprint.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fp = myssh.Myssh.getSSHFingerprint(sshAddr)
                    withContext(Dispatchers.Main) {
                        binding.btnFetchSshFingerprint.isEnabled = true
                        binding.etSshFingerprint.setText(fp)
                        binding.switchVerifySshFingerprint.isChecked = true
                        binding.layoutSshFingerprint.isVisible = true
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.ssh_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnFetchSshFingerprint.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnFetchCertFingerprint.setOnClickListener {
            val target = binding.etProxyAddr.text.toString().trim().ifBlank { 
                binding.etSshAddr.text.toString().trim() 
            }
            if (target.isBlank()) {
                binding.layoutProxyAddr.error = getString(CoreR.string.error_missing_proxy_or_ssh_addr)
                binding.etProxyAddr.requestFocus()
                return@setOnClickListener
            }
            val serverName = binding.etServerName.text.toString().trim().ifBlank {
                binding.etCustomHost.text.toString().trim()
            }

            binding.btnFetchCertFingerprint.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fp = myssh.Myssh.getTLSCertFingerprint(target, serverName)
                    withContext(Dispatchers.Main) {
                        binding.btnFetchCertFingerprint.isEnabled = true
                        binding.etCertFingerprint.setText(fp)
                        binding.switchVerifyCertFingerprint.isChecked = true
                        binding.layoutCertFingerprint.isVisible = true
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.cert_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnFetchCertFingerprint.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Details Buttons
        binding.btnDetailsSsh.setOnClickListener {
            val sshAddr = binding.etSshAddr.text.toString().trim()
            if (sshAddr.isBlank()) {
                binding.layoutSshAddr.error = getString(CoreR.string.error_missing_ssh_addr)
                binding.etSshAddr.requestFocus()
                return@setOnClickListener
            }

            binding.btnDetailsSsh.isEnabled = false
            Toast.makeText(this, getString(CoreR.string.fetching_details), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = myssh.Myssh.getSSHServerDetailsJSON(sshAddr)
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsSsh.isEnabled = true
                        showSSHDetailsDialog(jsonStr)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsSsh.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnDetailsCert.setOnClickListener {
            val target = binding.etProxyAddr.text.toString().trim().ifBlank { 
                binding.etSshAddr.text.toString().trim() 
            }
            if (target.isBlank()) {
                binding.layoutProxyAddr.error = getString(CoreR.string.error_missing_proxy_or_ssh_addr)
                binding.etProxyAddr.requestFocus()
                return@setOnClickListener
            }
            val serverName = binding.etServerName.text.toString().trim().ifBlank {
                binding.etCustomHost.text.toString().trim()
            }

            binding.btnDetailsCert.isEnabled = false
            Toast.makeText(this, getString(CoreR.string.fetching_details), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = myssh.Myssh.getTLSCertDetailsJSON(target, serverName)
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsCert.isEnabled = true
                        showTLSDetailsDialog(jsonStr)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsCert.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Real-time Validation & Error Clearing
        binding.etSshAddr.doAfterTextChanged { binding.layoutSshAddr.error = null }
        binding.etProxyAddr.doAfterTextChanged { binding.layoutProxyAddr.error = null }
        binding.etDnsTunnelServers.doAfterTextChanged { binding.layoutDnsTunnelServers.error = null }
        binding.etDnsTunnelDomain.doAfterTextChanged { binding.layoutDnsTunnelDomain.error = null }
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
                Toast.makeText(this, getString(CoreR.string.profile_saved), Toast.LENGTH_SHORT).show()
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
                getString(CoreR.string.auth_key) else getString(CoreR.string.auth_password), false)
            updateAuthTypeVisibility(profile.authType)
            
            etPrivateKey.setText(profile.privateKey)
            etKeyPass.setText("") // Clear for security

            spinnerTunnelType.setText(profile.tunnelType, false)
            spinnerDnsRecordType.setText(profile.dnsTunnelType.ifBlank { "txt" }, false)
            etHttpPayload.setText(profile.httpPayload)
            switchDisableStatusCheck.isChecked = profile.disableStatusCheck
            
            etKcpPassword.setText(profile.kcpPassword)
            spinnerKcpCrypt.setText(profile.kcpCrypt.ifBlank { "aes" }, false)
            switchKcpNodelay.isChecked = profile.kcpNoDelay
            etUdpCustomPsk.setText(profile.udpCustomPsk)
            etUdpCustomMagic.setText(profile.udpCustomMagic.ifBlank { "UDPC" })
            
            etProxyAddr.setText(profile.proxyAddr)
            etCustomHost.setText(profile.customHost)
            etDnsTunnelServers.setText(profile.dnsTunnelServers)
            etDnsTunnelDomain.setText(profile.dnsTunnelDomain)
            
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
                getString(CoreR.string.filter_allow_mode) else getString(CoreR.string.filter_disallow_mode), false)
            etFilterApps.setText(profile.filterApps)

            updateUIBasedOnSettings()
            setupAdapters()
        }
    }

    private fun setupAdapters() {
        binding.apply {
            spinnerTunnelType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, Profile.getAllTunnelTypes()))
            spinnerDnsRecordType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, dnsRecordTypes))
            spinnerKcpCrypt.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, kcpCryptOptions))
            spinnerAuthType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, authTypes.map {
                if (it == Profile.AUTH_TYPE_PASSWORD) getString(CoreR.string.auth_password) else getString(CoreR.string.auth_key)
            }))
            spinnerFilterMode.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, filterModes))
            spinnerUdpgwVersion.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, udpgwVersions))
            
            val isXhttp = spinnerTunnelType.text.toString() == Profile.TUNNEL_TYPE_XHTTP
            spinnerAlpn.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, if (isXhttp) alpnOptionsH3 else alpnOptionsNoH3))
        }
    }

    private fun updateUIBasedOnSettings() {
        val selected = binding.spinnerTunnelType.text.toString()
        val isDns = selected == Profile.TUNNEL_TYPE_DNS
        val isHttp = selected == Profile.TUNNEL_TYPE_HTTP
        val isBase = selected == Profile.TUNNEL_TYPE_BASE
        val isMasque = selected == Profile.TUNNEL_TYPE_MASQUE
        val isKcp = selected == Profile.TUNNEL_TYPE_KCP
        val isUdpCustom = selected == Profile.TUNNEL_TYPE_UDP_CUSTOM

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
            layoutProxyAddr.isVisible = !isBase && !isDns
            layoutCustomHost.isVisible = !isBase && !isDns && !isKcp && !isUdpCustom && selected != Profile.TUNNEL_TYPE_TLS && selected != Profile.TUNNEL_TYPE_QUIC
            layoutServerName.isVisible = isServerNameSupported

            layoutDnsTunnelServers.isVisible = isDns
            layoutDnsTunnelDomain.isVisible = isDns
            layoutDnsRecordType.isVisible = isDns

            layoutKcpContainer.isVisible = isKcp
            layoutUdpCustomContainer.isVisible = isUdpCustom

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
            
            layoutRowVerifyCertFingerprint.isVisible = isServerNameSupported
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
        val isKeyAuth = binding.spinnerAuthType.text.toString() == getString(CoreR.string.auth_key)
        val selectedTunnel = binding.spinnerTunnelType.text.toString()
        val isDns = selectedTunnel == Profile.TUNNEL_TYPE_DNS
        
        var firstErrorView: View? = null

        fun setError(layout: com.google.android.material.textfield.TextInputLayout, errorRes: Int) {
            layout.error = getString(errorRes)
            if (firstErrorView == null) firstErrorView = layout
        }

        // 1. Validate Address
        if (!validateAddress(binding.etSshAddr.text.toString(), binding.layoutSshAddr)) {
            if (firstErrorView == null) firstErrorView = binding.layoutSshAddr
        }
        
        if (binding.layoutProxyAddr.isVisible) {
            if (!validateAddress(binding.etProxyAddr.text.toString(), binding.layoutProxyAddr)) {
                if (firstErrorView == null) firstErrorView = binding.layoutProxyAddr
            }
        }

        if (isDns) {
            if (binding.etDnsTunnelServers.text.toString().isBlank()) {
                setError(binding.layoutDnsTunnelServers, CoreR.string.error_field_required)
            }
            if (binding.etDnsTunnelDomain.text.toString().isBlank()) {
                setError(binding.layoutDnsTunnelDomain, CoreR.string.error_field_required)
            }
        }

        // 2. Validate Auth
        if (isKeyAuth) {
            val privateKey = binding.etPrivateKey.text.toString()
            if (privateKey.isBlank()) {
                setError(binding.layoutPrivateKey, CoreR.string.error_field_required)
            } else if (!privateKey.contains("BEGIN") || !privateKey.contains("PRIVATE KEY")) {
                setError(binding.layoutPrivateKey, CoreR.string.error_invalid_private_key)
            } else {
                // Check Encryption
                val checkResult = myssh.Myssh.checkIfKeyEncrypted(privateKey)
                if (checkResult == 1L) {
                    val inputPass = binding.etKeyPass.text.toString()
                    val actualPass = inputPass.ifEmpty { currentProfile.keyPass }
                    if (actualPass.isEmpty() || !myssh.Myssh.validatePassphrase(privateKey, actualPass)) {
                        setError(binding.layoutKeyPass, CoreR.string.error_invalid_key_password)
                    }
                } else if (checkResult == 2L) {
                    setError(binding.layoutPrivateKey, CoreR.string.error_invalid_private_key)
                }
            }
        } else if (binding.etPass.text.toString().isBlank() && currentProfile.pass.isEmpty()) {
            setError(binding.layoutPass, CoreR.string.error_field_required)
        }

        // 3. Validate Path
        val path = binding.etCustomPath.text.toString()
        if (binding.layoutCustomPath.isVisible && path.isNotBlank() && !path.startsWith("/")) {
            setError(binding.layoutCustomPath, CoreR.string.error_invalid_path)
        }

        if (firstErrorView != null) {
            binding.scrollView.smoothScrollTo(0, firstErrorView!!.top - 100)
            firstErrorView!!.requestFocus()
            Toast.makeText(this, getString(CoreR.string.error_field_required), Toast.LENGTH_SHORT).show()
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
            keyPass = if (binding.etKeyPass.text.toString().isNotEmpty()) binding.etKeyPass.text.toString() else currentProfile.keyPass,
            tunnelType = binding.spinnerTunnelType.text.toString(),
            httpPayload = binding.etHttpPayload.text.toString(),
            disableStatusCheck = binding.switchDisableStatusCheck.isChecked,
            proxyAddr = binding.etProxyAddr.text.toString(),
            customHost = binding.etCustomHost.text.toString(),
            dnsTunnelDomain = binding.etDnsTunnelDomain.text.toString(),
            dnsTunnelServers = binding.etDnsTunnelServers.text.toString(),
            dnsTunnelType = if (isDns) binding.spinnerDnsRecordType.text.toString().ifBlank { "txt" } else currentProfile.dnsTunnelType,
            kcpPassword = binding.etKcpPassword.text.toString(),
            kcpCrypt = binding.spinnerKcpCrypt.text.toString().ifBlank { "aes" },
            kcpNoDelay = binding.switchKcpNodelay.isChecked,
            udpCustomPsk = binding.etUdpCustomPsk.text.toString(),
            udpCustomMagic = binding.etUdpCustomMagic.text.toString().ifBlank { "UDPC" },
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
            filterMode = if (binding.spinnerFilterMode.text.toString() == getString(CoreR.string.filter_allow_mode)) 1 else 0,
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
            layout.error = getString(CoreR.string.error_field_required)
            return false
        }
        // IPv6
        if (content.startsWith("[") && Regex("""^\[([0-9a-fA-F:]+)\]:(\d+)$""").matches(content)) return true
        // IPv4 / Domain
        if (Regex("""^([^:]+):(\d+)$""").matches(content)) return true
        
        layout.error = getString(CoreR.string.error_invalid_address)
        return false
    }

    override fun onSupportNavigateUp(): Boolean {
        if (hasUnsavedChanges()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(CoreR.string.unsaved_changes_title)
                .setMessage(CoreR.string.unsaved_changes_msg)
                .setPositiveButton(CoreR.string.ok) { _, _ -> finish() }
                .setNegativeButton(CoreR.string.cancel, null)
                .show()
        } else {
            finish()
        }
        return true
    }

    private fun hasUnsavedChanges(): Boolean {
        val isKeyAuth = binding.spinnerAuthType.text.toString() == getString(CoreR.string.auth_key)
        return binding.etName.text.toString() != currentProfile.name ||
            binding.etSshAddr.text.toString() != currentProfile.sshAddr ||
            binding.etUser.text.toString() != currentProfile.user ||
            binding.etProxyAddr.text.toString() != currentProfile.proxyAddr ||
            binding.etCustomHost.text.toString() != currentProfile.customHost ||
            binding.etDnsTunnelServers.text.toString() != currentProfile.dnsTunnelServers ||
            binding.etDnsTunnelDomain.text.toString() != currentProfile.dnsTunnelDomain ||
            binding.etServerName.text.toString() != currentProfile.serverName ||
            binding.spinnerTunnelType.text.toString() != currentProfile.tunnelType ||
            binding.etKcpPassword.text.toString() != currentProfile.kcpPassword ||
            binding.spinnerKcpCrypt.text.toString() != currentProfile.kcpCrypt ||
            binding.switchKcpNodelay.isChecked != currentProfile.kcpNoDelay ||
            binding.etUdpCustomPsk.text.toString() != currentProfile.udpCustomPsk ||
            binding.etUdpCustomMagic.text.toString() != currentProfile.udpCustomMagic ||
            // 密码字段非空时认为已修改（因为加载时出于安全清空了显示）
            binding.etPass.text.toString().isNotEmpty() ||
            (isKeyAuth && binding.etPrivateKey.text.toString() != currentProfile.privateKey)
    }

    private fun showSSHDetailsDialog(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val addr = json.optString("address")
        val banner = json.optString("banner").ifBlank { "N/A" }
        val keyType = json.optString("key_type")
        val fpSha256 = json.optString("fingerprint_sha256")
        val fpMd5 = json.optString("fingerprint_md5")
        val latencyMs = json.optLong("latency_ms")

        val sb = StringBuilder().apply {
            append("🌐 ${getString(CoreR.string.info_target_address)}: $addr\n")
            append("🏷️ ${getString(CoreR.string.info_server_banner)}: $banner\n")
            append("🔑 ${getString(CoreR.string.info_public_key_type)}: $keyType\n")
            append("⚡ ${getString(CoreR.string.info_handshake_latency)}: ${getString(CoreR.string.latency_format, latencyMs.toInt())}\n\n")
            append("🛡️ ${getString(CoreR.string.info_sha256_fingerprint)}:\n$fpSha256\n\n")
            append("🔒 ${getString(CoreR.string.info_md5_fingerprint)}:\n$fpMd5")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.ssh_server_details_title))
            .setMessage(sb.toString())
            .setPositiveButton(getString(CoreR.string.apply_fingerprint)) { _, _ ->
                binding.etSshFingerprint.setText(fpSha256)
                binding.switchVerifySshFingerprint.isChecked = true
                binding.layoutSshFingerprint.isVisible = true
                Toast.makeText(this, getString(CoreR.string.ssh_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(CoreR.string.copy_details)) { _, _ ->
                copyToClipboard(getString(CoreR.string.ssh_server_details_title), sb.toString())
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTLSDetailsDialog(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val target = json.optString("target")
        val sni = json.optString("sni")
        val subject = json.optString("subject").ifBlank { "N/A" }
        val issuer = json.optString("issuer").ifBlank { "N/A" }
        val daysRemaining = json.optInt("days_remaining")
        val isExpired = json.optBoolean("is_expired")
        val sigAlg = json.optString("signature_algorithm")
        val pubKeyAlg = json.optString("public_key_algorithm")
        val fpSha256 = json.optString("fingerprint_sha256")
        val tlsVer = json.optString("tls_version")
        val proto = json.optString("negotiated_protocol").ifBlank { "N/A" }
        val latencyMs = json.optLong("latency_ms")

        val dnsNamesArray = json.optJSONArray("dns_names")
        val sans = if (dnsNamesArray != null && dnsNamesArray.length() > 0) {
            val list = mutableListOf<String>()
            for (i in 0 until dnsNamesArray.length()) list.add(dnsNamesArray.getString(i))
            list.joinToString(", ")
        } else "N/A"

        val expireStatus = if (isExpired) getString(CoreR.string.info_cert_expired) else getString(CoreR.string.info_days_remaining, daysRemaining)

        val sb = StringBuilder().apply {
            append("🌐 ${getString(CoreR.string.info_target_endpoint)}: $target\n")
            append("🏷️ ${getString(CoreR.string.info_sni_domain)}: $sni\n")
            append("🔒 ${getString(CoreR.string.info_protocol_negotiation)}: ${getString(CoreR.string.info_protocol_negotiation_format, tlsVer, proto)}\n")
            append("⚡ ${getString(CoreR.string.info_handshake_latency)}: ${getString(CoreR.string.latency_format, latencyMs.toInt())}\n\n")
            append("📜 ${getString(CoreR.string.info_subject)}:\n$subject\n\n")
            append("🏢 ${getString(CoreR.string.info_issuer)}:\n$issuer\n\n")
            append("📅 ${getString(CoreR.string.info_validity_status)}: $expireStatus\n")
            append("🌐 ${getString(CoreR.string.info_sans)}: $sans\n")
            append("🔐 ${getString(CoreR.string.info_algorithm)}: $pubKeyAlg / $sigAlg\n\n")
            append("🛡️ ${getString(CoreR.string.info_cert_sha256_fingerprint)}:\n$fpSha256")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.tls_cert_details_title))
            .setMessage(sb.toString())
            .setPositiveButton(getString(CoreR.string.apply_fingerprint)) { _, _ ->
                binding.etCertFingerprint.setText(fpSha256)
                binding.switchVerifyCertFingerprint.isChecked = true
                binding.layoutCertFingerprint.isVisible = true
                Toast.makeText(this, getString(CoreR.string.cert_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(CoreR.string.copy_details)) { _, _ ->
                copyToClipboard(getString(CoreR.string.tls_cert_details_title), sb.toString())
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, getString(CoreR.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }
}
