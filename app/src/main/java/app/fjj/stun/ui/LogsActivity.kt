package app.fjj.stun.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityLogsBinding
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.GostRepository

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private var currentLogLevel = "V"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        currentLogLevel = SettingsManager.getLogLevel(this)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            binding.toolbar.updatePadding(top = systemBars.top)
            insets
        }

        GostRepository.logData.observe(this) { logs ->
            binding.tvLogs.text = filterLogs(logs, currentLogLevel)
            // Auto scroll to bottom
//            binding.scrollView.post {
//                binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
//            }
        }

        // Start logcat capture when activity opens
        GostRepository.startLogFileCapture(this@LogsActivity)

        binding.btnClear.setOnClickListener {
            GostRepository.clearLogs()
        }

        binding.btnCopy.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("VPN Logs", binding.tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun filterLogs(logs: String, level: String): String {
        if (level == "V") return logs
        
        val levels = listOf("V", "D", "I", "W", "E")
        val minLevelIndex = levels.indexOf(level)
        
        return logs.lines().filter { line ->
            // Logcat formats usually include " V/", " D/", etc. or just the letter at a specific position
            // This is a simple heuristic check for standard logcat -v time format
            val lineLevel = when {
                line.contains(" E/") || line.startsWith("E") -> "E"
                line.contains(" W/") || line.startsWith("W") -> "W"
                line.contains(" I/") || line.startsWith("I") -> "I"
                line.contains(" D/") || line.startsWith("D") -> "D"
                else -> "V"
            }
            levels.indexOf(lineLevel) >= minLevelIndex
        }.joinToString("\n")
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
