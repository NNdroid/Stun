package app.fjj.stun.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityLogsBinding
import app.fjj.stun.repo.StunRepository

class LogsActivity : BaseActivity() {

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

        binding.fabScrollBottom.setOnClickListener {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
            binding.fabScrollBottom.hide()
        }

        binding.scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (isAtBottom()) {
                binding.fabScrollBottom.hide()
            } else {
                binding.fabScrollBottom.show()
            }
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

    private fun updateLogView(logs: CharSequence? = null) {
        // If user is currently selecting text, do not refresh to avoid clearing the selection
        if (binding.tvLogs.hasSelection()) return

        val content = logs ?: StunRepository.appLogs.value ?: ""
        
        binding.tvLogs.text = content

        if (content.isEmpty()) return

        // Display FAB only if we are not at the bottom
        if (isAtBottom()) {
            binding.fabScrollBottom.hide()
        } else {
            binding.fabScrollBottom.show()
        }
    }

    private fun isAtBottom(): Boolean {
        val scrollY = binding.scrollView.scrollY
        val innerHeight = binding.scrollView.getChildAt(0).height
        val scrollViewHeight = binding.scrollView.height
        if (scrollViewHeight <= 0) return true
        return scrollY + scrollViewHeight >= innerHeight - 150 // 150px buffer
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}