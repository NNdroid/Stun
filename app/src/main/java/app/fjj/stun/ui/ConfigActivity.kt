package app.fjj.stun.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityConfigBinding
import app.fjj.stun.repo.ConfigManager

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private val logLevels = arrayOf("V", "D", "I", "W", "E")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            binding.toolbar.updatePadding(top = systemBars.top)
            insets
        }

        // Setup Log Level Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels)
        binding.spinnerLogLevel.setAdapter(adapter)

        // Load current values
        binding.etSshAddr.setText(ConfigManager.getSshAddr(this))
        binding.etUser.setText(ConfigManager.getUser(this))
        binding.etPass.setText(ConfigManager.getPass(this))
        binding.etTunnelType.setText(ConfigManager.getTunnelType(this))
        binding.etProxyAddr.setText(ConfigManager.getProxyAddr(this))
        binding.etCustomHost.setText(ConfigManager.getCustomHost(this))
        binding.spinnerLogLevel.setText(ConfigManager.getLogLevel(this), false)

        binding.btnSave.setOnClickListener {
            ConfigManager.saveConfig(
                this,
                binding.etSshAddr.text.toString(),
                binding.etUser.text.toString(),
                binding.etPass.text.toString(),
                binding.etTunnelType.text.toString(),
                binding.etProxyAddr.text.toString(),
                binding.etCustomHost.text.toString(),
                binding.spinnerLogLevel.text.toString()
            )
            Toast.makeText(this, "Configuration saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
