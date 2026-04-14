package app.fjj.stun.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityLogsBinding
import app.fjj.stun.repo.StunRepository

class LogsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            
            binding.scrollView.updatePadding(bottom = systemBars.bottom)
            insets
        }

        StunRepository.appLogs.observe(this) {
            updateLogView(it)
        }

        updateLogView()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(app.fjj.stun.R.menu.logs_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            app.fjj.stun.R.id.action_copy -> {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Stun Logs", binding.tvLogs.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, getString(app.fjj.stun.R.string.copy_success), Toast.LENGTH_SHORT).show()
                return true
            }
            app.fjj.stun.R.id.action_clear -> {
                StunRepository.clearLogs()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun updateLogView(logs: String? = null) {
        val content = logs ?: StunRepository.appLogs.value ?: ""
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