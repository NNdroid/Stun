package app.fjj.stun.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import app.fjj.stun.databinding.ActivityProfileEditBinding
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.Profile
import kotlin.concurrent.thread

class ProfileEditActivity : AppCompatActivity() {

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

        // Setup Tunnel Type Dropdown
        val tunnelTypes = Profile.getAllTunnelTypes()
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, tunnelTypes)
        binding.spinnerTunnelType.setAdapter(adapter)

        binding.spinnerTunnelType.setOnItemClickListener { _, _, _, _ ->
            updateTunnelTypeVisibility()
        }

        // Setup Auth Type Dropdown
        val authAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, authTypes.map {
            if (it == Profile.AUTH_TYPE_PASSWORD) getString(app.fjj.stun.R.string.auth_password) else getString(app.fjj.stun.R.string.auth_key)
        })
        binding.spinnerAuthType.setAdapter(authAdapter)
        binding.spinnerAuthType.setOnItemClickListener { _, _, position, _ ->
            updateAuthTypeVisibility(authTypes[position])
        }

        binding.switchDnsOverride.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDnsOverride.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchAppFilterOverride.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutAppFilterOverride.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.switchVerifyFingerprint.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutServerFingerprint.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        val filterAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, filterModes)
        binding.spinnerFilterMode.setAdapter(filterAdapter)

        binding.etPrivateKey.doAfterTextChanged { text ->
            validatePrivateKey(text.toString())
        }

        binding.etPass.doAfterTextChanged { text ->
            validatePassword(text.toString())
        }

        binding.etCustomPath.doAfterTextChanged { text ->
            validatePath(text.toString())
        }

        // Load values
        thread {
            currentProfile = if (isEdit) {
                ProfileManager.getProfiles(this).find { it.id == profileId } ?: Profile()
            } else {
                Profile()
            }

            runOnUiThread {
                binding.etName.setText(currentProfile.name)
                binding.etSshAddr.setText(currentProfile.sshAddr)
                binding.etUser.setText(currentProfile.user)
                
                binding.spinnerAuthType.setText(if (currentProfile.authType == Profile.AUTH_TYPE_PRIVATEKEY) getString(app.fjj.stun.R.string.auth_key) else getString(app.fjj.stun.R.string.auth_password), false)
                updateAuthTypeVisibility(currentProfile.authType)
                binding.etPass.setText(currentProfile.pass)
                binding.etPrivateKey.setText(currentProfile.privateKey)

                binding.spinnerTunnelType.setText(currentProfile.tunnelType, false)
                updateTunnelTypeVisibility()

                binding.etHttpPayload.setText(currentProfile.httpPayload)
                binding.switchDisableStatusCheck.isChecked = currentProfile.disableStatusCheck
                binding.etProxyAddr.setText(currentProfile.proxyAddr)
                binding.etCustomHost.setText(currentProfile.customHost)
                binding.etCustomPath.setText(currentProfile.customPath)

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
            }
        }

        binding.btnSave.setOnClickListener {
            val authTypeString = binding.spinnerAuthType.text.toString()
            val authType = if (authTypeString == getString(app.fjj.stun.R.string.auth_key)) Profile.AUTH_TYPE_PRIVATEKEY else Profile.AUTH_TYPE_PASSWORD
            
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

            val updatedProfile = currentProfile.copy(
                name = binding.etName.text.toString(),
                sshAddr = binding.etSshAddr.text.toString(),
                user = binding.etUser.text.toString(),
                authType = authType,
                pass = binding.etPass.text.toString(),
                privateKey = binding.etPrivateKey.text.toString(),
                tunnelType = binding.spinnerTunnelType.text.toString(),
                httpPayload = binding.etHttpPayload.text.toString(),
                disableStatusCheck = binding.switchDisableStatusCheck.isChecked,
                proxyAddr = binding.etProxyAddr.text.toString(),
                customHost = binding.etCustomHost.text.toString(),
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
                serverFingerprint = binding.etServerFingerprint.text.toString()
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
    }

    private fun updateTunnelTypeVisibility() {
        val selected = binding.spinnerTunnelType.text.toString()
        val isHttp = selected == Profile.TUNNEL_TYPE_HTTP
        val isBase = selected == Profile.TUNNEL_TYPE_BASE
        val isWsOrWssOrH2OrGrpcOrH3OrWt = selected == Profile.TUNNEL_TYPE_WS || selected == Profile.TUNNEL_TYPE_WSS ||
                selected == Profile.TUNNEL_TYPE_H2 || selected == Profile.TUNNEL_TYPE_H2C ||
                selected == Profile.TUNNEL_TYPE_GRPC || selected == Profile.TUNNEL_TYPE_GRPCC ||
                selected == Profile.TUNNEL_TYPE_H3 || selected == Profile.TUNNEL_TYPE_WT

        binding.layoutHttpPayload.visibility = if (isHttp) View.VISIBLE else View.GONE
        binding.layoutProxyAddr.visibility = if (isBase) View.GONE else View.VISIBLE
        binding.layoutCustomHost.visibility = if (isBase) View.GONE else View.VISIBLE
        binding.layoutCustomPath.visibility = if (isWsOrWssOrH2OrGrpcOrH3OrWt) View.VISIBLE else View.GONE
        binding.switchDisableStatusCheck.visibility = if (isHttp) View.VISIBLE else View.GONE
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
        if (content.isBlank()) {
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
        
        // Trigger validation when switching
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
