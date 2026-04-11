package app.fjj.stun.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityLogsBinding
import app.fjj.stun.repo.StunRepository
import com.google.android.material.tabs.TabLayout

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding
    private var currentTab = 0 // 0 for App Logs, 1 for Tunnel Logs

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val initialScrollPadding = binding.scrollView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            
            // Adjust BottomAppBar padding for the system navigation bar
            binding.bottomBar.updatePadding(bottom = systemBars.bottom)
            // Adjust ScrollView bottom padding to account for both BottomAppBar and system bars
            binding.scrollView.updatePadding(bottom = initialScrollPadding + systemBars.bottom)
            insets
        }

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                updateLogView()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        StunRepository.appLogs.observe(this) {
            if (currentTab == 0) updateLogView(it)
        }

        StunRepository.tunnelLogs.observe(this) {
            if (currentTab == 1) updateLogView(it)
        }

        binding.btnClear.setOnClickListener {
            StunRepository.clearLogs()
        }

        binding.btnCopy.setOnClickListener {
            val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("VPN Logs", binding.tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        updateLogView()
    }

    private fun updateLogView(logs: String? = null) {
        val content = logs ?: if (currentTab == 0) {
            StunRepository.appLogs.value ?: ""
        } else {
            StunRepository.tunnelLogs.value ?: ""
        }
        binding.tvLogs.text = content
        
        // Auto scroll to bottom
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}