package app.fjj.stun.ui

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivitySettingsBinding
import app.fjj.stun.repo.SettingsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val logLevels = arrayOf("V", "D", "I", "W", "E")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            
            // Apply padding for system bars and the keyboard (ime)
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom + ime.bottom)

            binding.toolbar.updatePadding(top = systemBars.top)
            insets
        }

        // Setup Log Level Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels)
        binding.spinnerLogLevel.setAdapter(adapter)
        binding.spinnerLogLevel.setText(SettingsManager.getLogLevel(this), false)

        // Setup DNS Servers
        binding.etRemoteDnsServer.setText(SettingsManager.getRemoteDnsServer(this))
        binding.etLocalDnsServer.setText(SettingsManager.getLocalDnsServer(this))

        // Setup Geo Data
        binding.etGeositeUrl.setText(SettingsManager.getGeositeUrl(this))
        binding.etGeoipUrl.setText(SettingsManager.getGeoipUrl(this))
        binding.etUpdateInterval.setText(SettingsManager.getUpdateInterval(this).toString())
        binding.etGeositeDirect.setText(SettingsManager.getGeositeDirect(this))
        binding.etGeoipDirect.setText(SettingsManager.getGeoipDirect(this))

        // Setup Last Update Time
        updateLastUpdateText()

        binding.btnUpdateNow.setOnClickListener {
            binding.btnUpdateNow.isEnabled = false
            binding.btnUpdateNow.text = "Updating..."
            SettingsManager.updateGeoData(this) {
                runOnUiThread {
                    updateLastUpdateText()
                    binding.btnUpdateNow.isEnabled = true
                    binding.btnUpdateNow.text = "Update Now"
                    Toast.makeText(this, "GeoData updated successfully", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSave.setOnClickListener {
            SettingsManager.saveLogLevel(this, binding.spinnerLogLevel.text.toString())
            SettingsManager.saveRemoteDnsServer(this, binding.etRemoteDnsServer.text.toString())
            SettingsManager.saveLocalDnsServer(this, binding.etLocalDnsServer.text.toString())
            
            SettingsManager.saveGeositeUrl(this, binding.etGeositeUrl.text.toString())
            SettingsManager.saveGeoipUrl(this, binding.etGeoipUrl.text.toString())
            val interval = binding.etUpdateInterval.text.toString().toLongOrNull() ?: 0L
            SettingsManager.saveUpdateInterval(this, interval)
            SettingsManager.saveGeositeDirect(this, binding.etGeositeDirect.text.toString())
            SettingsManager.saveGeoipDirect(this, binding.etGeoipDirect.text.toString())

            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun updateLastUpdateText() {
        val lastUpdate = SettingsManager.getLastUpdateTime(this)
        if (lastUpdate > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvLastUpdate.text = "Last updated: ${sdf.format(Date(lastUpdate * 1000))}"
        } else {
            binding.tvLastUpdate.text = "Last updated: Never"
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
