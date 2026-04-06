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
import app.fjj.stun.repo.ConfigManager

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
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            binding.toolbar.updatePadding(top = systemBars.top)
            insets
        }

        // Setup Log Level Spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, logLevels)
        binding.spinnerLogLevel.setAdapter(adapter)
        binding.spinnerLogLevel.setText(ConfigManager.getLogLevel(this), false)

        binding.btnSave.setOnClickListener {
            ConfigManager.saveLogLevel(this, binding.spinnerLogLevel.text.toString())
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
