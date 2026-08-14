package app.fjj.stun.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.databinding.ActivityLogsBinding
import app.fjj.stun.repo.StunRepository

class LogsFragment : Fragment() {

    private var _binding: ActivityLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var logAdapter: LogAdapter
    private var userIsScrolling = false

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
                    val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val fullLogs = StunRepository.appLogs.value?.toString() ?: ""
                    val clip = ClipData.newPlainText("Stun Logs", fullLogs)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(requireContext(), getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_clear -> {
                    StunRepository.clearLogs()
                    true
                }
                else -> false
            }
        }

        setupRecyclerView()

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            binding.rvLogs.updatePadding(bottom = systemBars.bottom + 80) // Space for FAB
            insets
        }

        StunRepository.appLogs.observe(viewLifecycleOwner) {
            updateLogs(it?.toString() ?: "")
        }

        binding.fabScrollBottom.setOnClickListener {
            scrollToBottom(force = true)
            binding.fabScrollBottom.hide()
            userIsScrolling = false
        }

        updateLogs(StunRepository.appLogs.value?.toString() ?: "")
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

    private fun updateLogs(fullText: String) {
        if (fullText.isEmpty()) {
            logAdapter.submitList(emptyList())
            return
        }

        val lines = fullText.lines().filter { it.isNotBlank() }
        val wasAtBottom = !userIsScrolling
        
        logAdapter.submitList(lines) {
            if (wasAtBottom) {
                scrollToBottom()
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
        super.onDestroyView()
        _binding = null
    }
}
