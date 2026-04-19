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
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.Profile
import app.fjj.stun.util.KeystoreUtils
import kotlin.concurrent.thread
import androidx.core.view.isVisible

class ProfileEditActivity : BaseActivity() {

    private lateinit var binding: ActivityProfileEditBinding
    private var profileId: String? = null
    private var currentProfile: Profile = Profile()
    private lateinit var filterModes: Array<String>
    private val authTypes = arrayOf(Profile.AUTH_TYPE_PASSWORD, Profile.AUTH_TYPE_PRIVATEKEY)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        filterModes = arrayOf(
            getString(app.fjj.stun.R.string.filter_disallow_mode),
            getString(app.fjj.stun.R.string.filter_allow_mode)
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        profileId = intent.getStringExtra("EXTRA_PROFILE_ID")
        val isEdit = profileId != null
        supportActionBar?.title = if (isEdit) getString(app.fjj.stun.R.string.edit_profile) else getString(app.fjj.stun.R.string.add_profile)

        setupAdapters()

        val initialPaddingBottom = binding.btnSave.parent.let { (it as View).paddingBottom }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            
            // Apply bottom padding to the scrollable container's child to keep content above nav bar/keyboard
            binding.btnSave.parent.let { 
                (it as View).updatePadding(bottom = initialPaddingBottom + systemBars.bottom + ime.bottom)
            }
            insets
        }

        binding.spinnerTunnelType.setOnItemClickListener { _, _, _, _ ->
            updateTunnelTypeVisibility()
        }

        binding.spinnerAuthType.setOnItemClickListener { _, _, position, _ ->
            updateAuthTypeVisibility(authTypes[position])
        }

        binding.switchDnsOverride.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDnsOverride.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchAuthRequired.setOnCheckedChangeListener { _, _ ->
            updateProxyAuthTokenVisibility()
        }

        binding.switchAppFilterOverride.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutAppFilterOverride.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchVerifyFingerprint.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutServerFingerprint.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchEnableCustomPath.setOnCheckedChangeListener { _, _ ->
            updateTunnelTypeVisibility()
        }

        binding.etSshAddr.doAfterTextChanged { text ->
            validateAddress(text.toString(), binding.layoutSshAddr)
        }

        binding.etProxyAddr.doAfterTextChanged { text ->
            validateAddress(text.toString(), binding.layoutProxyAddr)
        }

        binding.etPrivateKey.doAfterTextChanged { text ->
            validatePrivateKey(text.toString())
        }

        binding.etPass.doAfterTextChanged { text ->
            validatePassword(text.toString())
        }

        binding.etCustomPath.doAfterTextChanged { text ->
            validatePath(text.toString())
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

        binding.btnSave.setOnClickListener {
            val authTypeString = binding.spinnerAuthType.text.toString()
            val authType = if (authTypeString == getString(app.fjj.stun.R.string.auth_key)) Profile.AUTH_TYPE_PRIVATEKEY else Profile.AUTH_TYPE_PASSWORD
            
            val sshAddr = binding.etSshAddr.text.toString()
            validateAddress(sshAddr, binding.layoutSshAddr)
            if (binding.layoutSshAddr.error != null) {
                Toast.makeText(this, binding.layoutSshAddr.error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (binding.layoutProxyAddr.visibility == View.VISIBLE) {
                val proxyAddr = binding.etProxyAddr.text.toString()
                validateAddress(proxyAddr, binding.layoutProxyAddr)
                if (binding.layoutProxyAddr.error != null) {
                    Toast.makeText(this, binding.layoutProxyAddr.error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            // Final validation check
            if (authType == Profile.AUTH_TYPE_PRIVATEKEY) {
                val privateKey = binding.etPrivateKey.text.toString()
                validatePrivateKey(privateKey)
                if (binding.layoutPrivateKey.error != null) {
                    Toast.makeText(this, binding.layoutPrivateKey.error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            } else {
                val password = binding.etPass.text.toString()
                validatePassword(password)
                if (binding.layoutPass.error != null) {
                    Toast.makeText(this, binding.layoutPass.error, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }
            
            val customPath = binding.etCustomPath.text.toString()
            validatePath(customPath)
            if (binding.layoutCustomPath.error != null) {
                Toast.makeText(this, binding.layoutCustomPath.error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (authType == Profile.AUTH_TYPE_PRIVATEKEY) {
                val privateKey = binding.etPrivateKey.text.toString()
                val inputKeyPass = binding.etKeyPass.text.toString()
                val actualKeyPass = inputKeyPass.ifEmpty { KeystoreUtils.decrypt(currentProfile.keyPass) }

                val checkResult = myssh.Myssh.checkIfKeyEncrypted(privateKey)
                when (checkResult) {
                    1L -> { // Password required
                        if (actualKeyPass.isEmpty()) {
                            binding.layoutKeyPass.error = getString(app.fjj.stun.R.string.error_field_required)
                            Toast.makeText(this, getString(app.fjj.stun.R.string.error_key_password_required), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        // Try to parse with password to verify it
                        try {
                            val validatePassphraseResult = myssh.Myssh.validatePassphrase(privateKey, actualKeyPass)
                            if (!validatePassphraseResult) {
                                binding.layoutKeyPass.error = getString(app.fjj.stun.R.string.error_invalid_key_password)
                                Toast.makeText(this, getString(app.fjj.stun.R.string.error_invalid_key_password), Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                        } catch (e: Exception) {
                            binding.layoutKeyPass.error = getString(app.fjj.stun.R.string.error_invalid_key_password)
                            Toast.makeText(this, getString(app.fjj.stun.R.string.error_invalid_key_password), Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                    }
                    2L -> { // Incorrect format
                        binding.layoutPrivateKey.error = getString(app.fjj.stun.R.string.error_invalid_private_key)
                        Toast.makeText(this, getString(app.fjj.stun.R.string.error_invalid_private_key), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }
                binding.layoutKeyPass.error = null
            }

            val updatedProfile = currentProfile.copy(
                name = binding.etName.text.toString(),
                sshAddr = binding.etSshAddr.text.toString(),
                user = binding.etUser.text.toString(),
                authType = authType,
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
                udpgwAddr = binding.etUdpgwAddr.text.toString(),
                geositeDirect = binding.etGeositeDirect.text.toString(),
                geoipDirect = binding.etGeoipDirect.text.toString(),
                appFilterOverride = binding.switchAppFilterOverride.isChecked,
                filterMode = if (binding.spinnerFilterMode.text.toString() == getString(app.fjj.stun.R.string.filter_allow_mode)) 1 else 0,
                filterApps = binding.etFilterApps.text.toString(),
                verifyFingerprint = binding.switchVerifyFingerprint.isChecked,
                serverFingerprint = binding.etServerFingerprint.text.toString(),
                proxyAuthRequired = binding.switchAuthRequired.isChecked,
                proxyAuthToken = binding.etAuthToken.text.toString(),
                proxyAuthUser = binding.etAuthUser.text.toString(),
                proxyAuthPass = binding.etAuthPass.text.toString()
            )

            thread {
                if (isEdit) {
                    ProfileManager.updateProfile(this, updatedProfile)
                } else {
                    ProfileManager.addProfile(this, updatedProfile)
                }
                runOnUiThread {
                    Toast.makeText(this, getString(app.fjj.stun.R.string.profile_saved), Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        loadProfileValues(isEdit)
    }

    private fun setupAdapters() {
        val tunnelTypes = Profile.getAllTunnelTypes()
        binding.spinnerTunnelType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tunnelTypes))

        val authAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, authTypes.map {
            if (it == Profile.AUTH_TYPE_PASSWORD) getString(app.fjj.stun.R.string.auth_password) else getString(app.fjj.stun.R.string.auth_key)
        })
        binding.spinnerAuthType.setAdapter(authAdapter)

        binding.spinnerFilterMode.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, filterModes))
    }

    private fun loadProfileValues(isEdit: Boolean) {
        thread {
            currentProfile = if (isEdit && profileId != null) {
                ProfileManager.getProfileById(this, profileId!!) ?: Profile()
            } else {
                Profile()
            }

            runOnUiThread {
                binding.etName.setText(currentProfile.name)
                binding.etSshAddr.setText(currentProfile.sshAddr)
                binding.etUser.setText(currentProfile.user)
                
                binding.spinnerAuthType.setText(if (currentProfile.authType == Profile.AUTH_TYPE_PRIVATEKEY) getString(app.fjj.stun.R.string.auth_key) else getString(app.fjj.stun.R.string.auth_password), false)
                updateAuthTypeVisibility(currentProfile.authType)
                binding.etPass.setText("")
                binding.etPrivateKey.setText(currentProfile.privateKey)
                binding.etKeyPass.setText("")

                binding.spinnerTunnelType.setText(currentProfile.tunnelType, false)
                updateTunnelTypeVisibility()

                binding.etHttpPayload.setText(currentProfile.httpPayload)
                binding.switchDisableStatusCheck.isChecked = currentProfile.disableStatusCheck
                binding.etProxyAddr.setText(currentProfile.proxyAddr)
                binding.etCustomHost.setText(currentProfile.customHost)
                binding.etServerName.setText(currentProfile.serverName)
                binding.switchEnableCustomPath.isChecked = currentProfile.enableCustomPath
                binding.etCustomPath.setText(currentProfile.customPath)

                binding.switchAuthRequired.isChecked = currentProfile.proxyAuthRequired
                binding.etAuthToken.setText(currentProfile.proxyAuthToken)
                binding.etAuthUser.setText(currentProfile.proxyAuthUser)
                binding.etAuthPass.setText(currentProfile.proxyAuthPass)

                // Server Fingerprint
                binding.switchVerifyFingerprint.isChecked = currentProfile.verifyFingerprint
                binding.layoutServerFingerprint.visibility = if (currentProfile.verifyFingerprint) View.VISIBLE else View.GONE
                binding.etServerFingerprint.setText(currentProfile.serverFingerprint)

                // DNS and Routing Overrides
                binding.switchDnsOverride.isChecked = currentProfile.dnsOverride
                binding.layoutDnsOverride.visibility = if (currentProfile.dnsOverride) View.VISIBLE else View.GONE
                binding.etRemoteDns.setText(currentProfile.remoteDns)
                binding.etLocalDns.setText(currentProfile.localDns)
                binding.etUdpgwAddr.setText(currentProfile.udpgwAddr)
                binding.etGeositeDirect.setText(currentProfile.geositeDirect)
                binding.etGeoipDirect.setText(currentProfile.geoipDirect)

                // App Filtering Overrides
                binding.switchAppFilterOverride.isChecked = currentProfile.appFilterOverride
                binding.layoutAppFilterOverride.visibility = if (currentProfile.appFilterOverride) View.VISIBLE else View.GONE
                binding.spinnerFilterMode.setText(if (currentProfile.filterMode == 1) getString(app.fjj.stun.R.string.filter_allow_mode) else getString(app.fjj.stun.R.string.filter_disallow_mode), false)
                binding.etFilterApps.setText(currentProfile.filterApps)
            }
        }
    }

    private fun updateTunnelTypeVisibility() {
        val selected = binding.spinnerTunnelType.text.toString()
        val isHttp = selected == Profile.TUNNEL_TYPE_HTTP
        val isBase = selected == Profile.TUNNEL_TYPE_BASE
        val isMasque = selected == Profile.TUNNEL_TYPE_MASQUE

        val isCustomPathSupported = selected == Profile.TUNNEL_TYPE_WS || selected == Profile.TUNNEL_TYPE_WSS ||
                selected == Profile.TUNNEL_TYPE_H2 || selected == Profile.TUNNEL_TYPE_H2C ||
                selected == Profile.TUNNEL_TYPE_GRPC || selected == Profile.TUNNEL_TYPE_GRPCC ||
                selected == Profile.TUNNEL_TYPE_H3 || selected == Profile.TUNNEL_TYPE_WT ||
                selected == Profile.TUNNEL_TYPE_XHTTP || selected == Profile.TUNNEL_TYPE_XHTTPC
        
        val isServerNameSupported = selected == Profile.TUNNEL_TYPE_WSS ||
                selected == Profile.TUNNEL_TYPE_TLS ||
                selected == Profile.TUNNEL_TYPE_QUIC ||
                selected == Profile.TUNNEL_TYPE_H2 ||
                selected == Profile.TUNNEL_TYPE_GRPC ||
                selected == Profile.TUNNEL_TYPE_H3 ||
                selected == Profile.TUNNEL_TYPE_WT ||
                selected == Profile.TUNNEL_TYPE_MASQUE ||
                selected == Profile.TUNNEL_TYPE_XHTTP

        val isCustomHostSupported = !isBase && selected != Profile.TUNNEL_TYPE_TLS && selected != Profile.TUNNEL_TYPE_QUIC

        binding.layoutHttpPayload.visibility = if (isHttp) View.VISIBLE else View.GONE
        binding.layoutProxyAddr.visibility = if (isBase) View.GONE else View.VISIBLE
        binding.layoutCustomHost.visibility = if (isCustomHostSupported) View.VISIBLE else View.GONE
        binding.layoutServerName.visibility = if (isServerNameSupported) View.VISIBLE else View.GONE
        
        binding.switchEnableCustomPath.visibility = if (isMasque) View.VISIBLE else View.GONE
        
        if (!isMasque && !binding.switchEnableCustomPath.isChecked) {
            binding.switchEnableCustomPath.isChecked = true
        }
        val showCustomPath = (isMasque && binding.switchEnableCustomPath.isChecked) || isCustomPathSupported
        binding.layoutCustomPath.visibility = if (showCustomPath) View.VISIBLE else View.GONE

        binding.switchDisableStatusCheck.visibility = if (isHttp) View.VISIBLE else View.GONE

        val supportsProxyAuth = selected == Profile.TUNNEL_TYPE_H2 ||//token
                selected == Profile.TUNNEL_TYPE_H2C ||
                selected == Profile.TUNNEL_TYPE_GRPC ||
                selected == Profile.TUNNEL_TYPE_GRPCC ||
                selected == Profile.TUNNEL_TYPE_H3 ||
                selected == Profile.TUNNEL_TYPE_WT ||
                selected == Profile.TUNNEL_TYPE_MASQUE||
                selected == Profile.TUNNEL_TYPE_XHTTP ||
                selected == Profile.TUNNEL_TYPE_XHTTPC ||
                (selected == Profile.TUNNEL_TYPE_WS || // username:password
                 selected == Profile.TUNNEL_TYPE_WSS ||
                 selected == Profile.TUNNEL_TYPE_HTTP)

        binding.switchAuthRequired.visibility = if (supportsProxyAuth) View.VISIBLE else View.GONE
        updateProxyAuthTokenVisibility()
    }

    private fun updateProxyAuthTokenVisibility() {
        val selected = binding.spinnerTunnelType.text.toString()
        val isAuthEnabled = binding.switchAuthRequired.isVisible && binding.switchAuthRequired.isChecked
        
        val isTokenMode = selected in listOf(
            Profile.TUNNEL_TYPE_H2, Profile.TUNNEL_TYPE_H2C, 
            Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_GRPCC,
            Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_WT, Profile.TUNNEL_TYPE_MASQUE,
            Profile.TUNNEL_TYPE_XHTTP, Profile.TUNNEL_TYPE_XHTTPC
        )
        val isUserPassMode = selected in listOf(
            Profile.TUNNEL_TYPE_WS, Profile.TUNNEL_TYPE_WSS, Profile.TUNNEL_TYPE_HTTP
        )

        binding.layoutAuthToken.visibility = if (isAuthEnabled && isTokenMode) View.VISIBLE else View.GONE
        binding.layoutAuthUser.visibility = if (isAuthEnabled && isUserPassMode) View.VISIBLE else View.GONE
        binding.layoutAuthPass.visibility = if (isAuthEnabled && isUserPassMode) View.VISIBLE else View.GONE
    }

    private fun validateAddress(content: String, layout: com.google.android.material.textfield.TextInputLayout) {
        if (content.isBlank()) {
            layout.error = getString(app.fjj.stun.R.string.error_field_required)
            return
        }

        val ipv6Match = Regex("""^\[([0-9a-fA-F:]+)\]:(\d+)$""").find(content)
        if (ipv6Match != null) {
            val port = ipv6Match.groupValues[2].toIntOrNull()
            if (port == null || port !in 1..65535) {
                layout.error = getString(app.fjj.stun.R.string.error_invalid_port)
            } else {
                layout.error = null
            }
            return
        }

        val genericMatch = Regex("""^([^:]+):(\d+)$""").find(content)
        if (genericMatch != null) {
            val host = genericMatch.groupValues[1]
            val port = genericMatch.groupValues[2].toIntOrNull()
            
            if (port == null || port !in 1..65535) {
                layout.error = getString(app.fjj.stun.R.string.error_invalid_port)
                return
            }

            val ipPattern = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
            val domainPattern = Regex("""^([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}$""")
            
            if (ipPattern.matches(host) || domainPattern.matches(host) || host == "localhost") {
                layout.error = null
            } else {
                layout.error = getString(app.fjj.stun.R.string.error_invalid_address)
            }
            return
        }

        layout.error = getString(app.fjj.stun.R.string.error_invalid_address)
    }

    private fun validatePrivateKey(content: String) {
        if (content.isBlank()) {
            binding.layoutPrivateKey.error = getString(app.fjj.stun.R.string.error_field_required)
        } else if (!content.contains("BEGIN") || !content.contains("PRIVATE KEY")) {
            binding.layoutPrivateKey.error = getString(app.fjj.stun.R.string.error_invalid_private_key)
        } else {
            binding.layoutPrivateKey.error = null
        }
    }

    private fun validatePassword(content: String) {
        if (content.isBlank() && currentProfile.pass.isEmpty()) {
            binding.layoutPass.error = getString(app.fjj.stun.R.string.error_field_required)
        } else {
            binding.layoutPass.error = null
        }
    }

    private fun validatePath(content: String) {
        if (content.isNotBlank() && !content.startsWith("/")) {
            binding.layoutCustomPath.error = getString(app.fjj.stun.R.string.error_invalid_path)
        } else {
            binding.layoutCustomPath.error = null
        }
    }

    private fun updateAuthTypeVisibility(authType: String) {
        val isKey = authType == Profile.AUTH_TYPE_PRIVATEKEY
        binding.layoutPass.visibility = if (isKey) View.GONE else View.VISIBLE
        binding.layoutPrivateKey.visibility = if (isKey) View.VISIBLE else View.GONE
        binding.layoutKeyPass.visibility = if (isKey) View.VISIBLE else View.GONE
        
        if (isKey) {
            validatePrivateKey(binding.etPrivateKey.text.toString())
            binding.layoutPass.error = null
        } else {
            validatePassword(binding.etPass.text.toString())
            binding.layoutPrivateKey.error = null
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
