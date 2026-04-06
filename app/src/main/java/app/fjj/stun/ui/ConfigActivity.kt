package app.fjj.stun.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityConfigBinding
import app.fjj.stun.repo.ConfigManager
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
        supportActionBar?.title = if (isEdit) "Edit Profile" else "Add Profile"

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            binding.toolbar.updatePadding(top = systemBars.top)
            insets
        }

        // Load values
        thread {
            currentProfile = if (isEdit) {
                ConfigManager.getProfiles(this).find { it.id == profileId } ?: Profile()
            } else {
                Profile()
            }

            runOnUiThread {
                binding.etName.setText(currentProfile.name)
                binding.etSshAddr.setText(currentProfile.sshAddr)
                binding.etUser.setText(currentProfile.user)
                binding.etPass.setText(currentProfile.pass)
                binding.etTunnelType.setText(currentProfile.tunnelType)
                binding.etProxyAddr.setText(currentProfile.proxyAddr)
                binding.etCustomHost.setText(currentProfile.customHost)
            }
        }

        binding.btnSave.setOnClickListener {
            val updatedProfile = currentProfile.copy(
                name = binding.etName.text.toString(),
                sshAddr = binding.etSshAddr.text.toString(),
                user = binding.etUser.text.toString(),
                pass = binding.etPass.text.toString(),
                tunnelType = binding.etTunnelType.text.toString(),
                proxyAddr = binding.etProxyAddr.text.toString(),
                customHost = binding.etCustomHost.text.toString()
            )

            if (isEdit) {
                thread {
                    ConfigManager.updateProfile(this, updatedProfile)
                }
            } else {
                thread {
                    ConfigManager.addProfile(this, updatedProfile)
                }
            }
            
            Toast.makeText(this, "Profile saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
