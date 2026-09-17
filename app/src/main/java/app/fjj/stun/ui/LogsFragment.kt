package app.fjj.stun.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.ActivityLogsBinding
import app.fjj.stun.repo.LogEntry
import app.fjj.stun.repo.LogLevel
import app.fjj.stun.repo.StunLogger
import app.fjj.stun.repo.StunRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {

    private var _binding: ActivityLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var logAdapter: LogAdapter
    private var userIsScrolling = false
    private var filterJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener {
            (requireActivity() as MainActivity).navigateToHome()
        }
        
        binding.toolbar.inflateMenu(R.menu.logs_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_copy -> {
                    val fullLogs = StunRepository.appLogs.value?.toString() ?: ""
                    if (fullLogs.isBlank()) {
                        Toast.makeText(requireContext(), getString(CoreR.string.logs_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText(getString(CoreR.string.logs_title_full), fullLogs)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(requireContext(), getString(CoreR.string.copy_success), Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_share -> {
                    val fullLogs = StunRepository.appLogs.value?.toString() ?: ""
                    if (fullLogs.isBlank()) {
                        Toast.makeText(requireContext(), getString(CoreR.string.logs_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        shareLogsAsFile(fullLogs)
                    }
                    true
                }
                R.id.action_clear -> {
                    if (StunRepository.appLogs.value.isNullOrBlank()) {
                        Toast.makeText(requireContext(), getString(CoreR.string.logs_empty), Toast.LENGTH_SHORT).show()
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(CoreR.string.clear_logs)
                            .setMessage(CoreR.string.clear_logs_confirm)
                            .setPositiveButton(CoreR.string.clear_logs) { _, _ ->
                                StunRepository.clearLogs()
                            }
                            .setNegativeButton(CoreR.string.cancel, null)
                            .show()
                    }
                    true
                }
                else -> false
            }
        }

        setupRecyclerView()
        setupFilters()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            binding.rvLogs.updatePadding(bottom = systemBars.bottom + 80) // Space for FAB
            insets
        }

        StunRepository.logEntries.observe(viewLifecycleOwner) { entries ->
            allLogEntries = entries ?: emptyList()
            applyFiltersAndDisplay()
        }

        binding.fabScrollBottom.setOnClickListener {
            scrollToBottom(force = true)
            binding.fabScrollBottom.hide()
            userIsScrolling = false
        }

        allLogEntries = StunRepository.logEntries.value ?: emptyList()
        applyFiltersAndDisplay(immediate = true)
    }

    /**
     * 以文件形式分享日志：写进 cacheDir/logs 后交 FileProvider 生成一个临时可读 URI，
     * 走 ACTION_SEND 的 EXTRA_STREAM。日志通常很大，塞进 EXTRA_TEXT 会撞上剪贴板/Intent
     * 的大小上限（几十 KB 起就发不出去），文件形式没有这个问题。
     *
     * 文件写盘在 IO 线程；URI 授权只对这一次分享生效（FLAG_GRANT_READ_URI_PERMISSION）。
     */
    private fun shareLogsAsFile(content: String) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { writeLogsToShareFile(appContext, content) }
            }
            val uri = outcome.getOrNull()
            if (uri == null) {
                if (_binding != null) {
                    Toast.makeText(
                        appContext,
                        getString(CoreR.string.export_failed, outcome.exceptionOrNull()?.message ?: ""),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, getString(CoreR.string.logs_share_subject))
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(CoreR.string.share)))
        }
    }

    private fun writeLogsToShareFile(appContext: Context, content: String): android.net.Uri {
        val dir = File(appContext.cacheDir, "logs").apply { mkdirs() }
        // 每次分享只留这一份：cacheDir 会被系统回收，但同一会话反复点分享会堆积。
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "stun-logs-$stamp.txt")
        file.writeText(content)
        return FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
    }

    private var currentFilterLevel = "ALL"
    private var currentSearchQuery = ""
    private var allLogEntries = listOf<LogEntry>()

    private fun setupFilters() {
        binding.chipGroupLogLevel.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilterLevel = when (checkedIds.firstOrNull()) {
                R.id.chip_debug -> "DEBUG"
                R.id.chip_info -> "INFO"
                R.id.chip_warn -> "WARN"
                R.id.chip_error -> "ERROR"
                else -> "ALL"
            }
            applyFiltersAndDisplay()
        }

        binding.etSearchLogs.doAfterTextChanged { text ->
            currentSearchQuery = text?.toString()?.trim() ?: ""
            applyFiltersAndDisplay()
        }
    }

    private fun setupRecyclerView() {
        logAdapter = LogAdapter()
        binding.rvLogs.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = logAdapter
            
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastVisibleItem = layoutManager.findLastCompletelyVisibleItemPosition()
                    val isAtBottom = lastVisibleItem >= logAdapter.itemCount - 1
                    
                    if (isAtBottom) {
                        binding.fabScrollBottom.hide()
                        userIsScrolling = false
                    } else if (dy < -10) { // Scrolling up
                        binding.fabScrollBottom.show()
                        userIsScrolling = true
                    }
                }
            })
        }
    }

    private fun applyFiltersAndDisplay(immediate: Boolean = false) {
        val entries = allLogEntries
        val wasAtBottom = !userIsScrolling
        val targetLevel = when (currentFilterLevel) {
            "DEBUG" -> LogLevel.DEBUG
            "INFO" -> LogLevel.INFO
            "WARN" -> LogLevel.WARN
            "ERROR" -> LogLevel.ERROR
            else -> null
        }
        val query = currentSearchQuery

        filterJob?.cancel()
        filterJob = viewLifecycleOwner.lifecycleScope.launch {
            if (!immediate) delay(120L)
            val filtered = withContext(Dispatchers.Default) {
                if (entries.isEmpty()) {
                    emptyList()
                } else {
                    entries.filter { entry ->
                        val matchesLevel = targetLevel == null || entry.level == targetLevel
                        val matchesSearch = query.isEmpty() ||
                            entry.message.contains(query, ignoreCase = true) ||
                            entry.tag.contains(query, ignoreCase = true)
                        matchesLevel && matchesSearch
                    }
                }
            }
            logAdapter.submitList(filtered) {
                if (wasAtBottom) scrollToBottom()
            }
        }
    }

    private fun scrollToBottom(force: Boolean = false) {
        if (_binding == null) return
        if (force || !userIsScrolling) {
            val position = logAdapter.itemCount - 1
            if (position >= 0) {
                binding.rvLogs.scrollToPosition(position)
            }
        }
    }

    override fun onDestroyView() {
        filterJob?.cancel()
        filterJob = null
        super.onDestroyView()
        _binding = null
    }
}
