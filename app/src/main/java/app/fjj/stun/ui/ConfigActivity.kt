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
import app.fjj.stun.databinding.ActivityConfigBinding
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.Profile
import kotlin.concurrent.thread

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private var profileId: String? = null
    private var currentProfile: Profile = Profile()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
            val selected = binding.spinnerTunnelType.text.toString()
            val isHttp = selected == Profile.TUNNEL_TYPE_HTTP
            val isBase = selected == Profile.TUNNEL_TYPE_BASE
            val isWsOrWss = selected == Profile.TUNNEL_TYPE_WS || selected == Profile.TUNNEL_TYPE_WSS
            
            binding.layoutHttpPayload.visibility = if (isHttp) View.VISIBLE else View.GONE
            binding.layoutProxyAddr.visibility = if (isBase) View.GONE else View.VISIBLE
            binding.layoutCustomHost.visibility = if (isBase) View.GONE else View.VISIBLE
            binding.layoutCustomPath.visibility = if (isWsOrWss) View.VISIBLE else View.GONE
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
                binding.etPass.setText(currentProfile.pass)
                binding.spinnerTunnelType.setText(currentProfile.tunnelType, false)
                
                val selected = currentProfile.tunnelType
                val isHttp = selected == Profile.TUNNEL_TYPE_HTTP
                val isBase = selected == Profile.TUNNEL_TYPE_BASE
                val isWsOrWss = selected == Profile.TUNNEL_TYPE_WS || selected == Profile.TUNNEL_TYPE_WSS
                
                binding.layoutHttpPayload.visibility = if (isHttp) View.VISIBLE else View.GONE
                binding.layoutProxyAddr.visibility = if (isBase) View.GONE else View.VISIBLE
                binding.layoutCustomHost.visibility = if (isBase) View.GONE else View.VISIBLE
                binding.layoutCustomPath.visibility = if (isWsOrWss) View.VISIBLE else View.GONE

                binding.etHttpPayload.setText(currentProfile.httpPayload)
                binding.etProxyAddr.setText(currentProfile.proxyAddr)
                binding.etCustomHost.setText(currentProfile.customHost)
                binding.etCustomPath.setText(currentProfile.customPath)
            }
        }

        binding.btnSave.setOnClickListener {
            val updatedProfile = currentProfile.copy(
                name = binding.etName.text.toString(),
                sshAddr = binding.etSshAddr.text.toString(),
                user = binding.etUser.text.toString(),
                pass = binding.etPass.text.toString(),
                tunnelType = binding.spinnerTunnelType.text.toString(),
                httpPayload = binding.etHttpPayload.text.toString(),
                proxyAddr = binding.etProxyAddr.text.toString(),
                customHost = binding.etCustomHost.text.toString(),
                customPath = binding.etCustomPath.text.toString()
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
