package app.fjj.stun.ui

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.fragment.app.viewModels
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.ActivitySettingsBinding
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.ui.viewmodel.SettingsState
import app.fjj.stun.ui.viewmodel.SettingsViewModel
import app.fjj.stun.util.revealAboveBottomPadding
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.*

class SettingsFragment : Fragment(), GeoTagsPickerBottomSheet.OnTagsConfirmedListener {

    private var _binding: ActivitySettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()
    
    private var globalFocusChangeListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var initialFormSnapshot: String? = null
    private var isSaving = false
    private var imeVisible = false

    // Geo 标签选择器的回写目标；旋转后以 kind 兜底重解析到 binding 字段
    private var geoTagPickerTarget: com.google.android.material.textfield.TextInputEditText? = null

    private val logLevels = arrayOf("DEBUG", "INFO", "WARN", "ERROR")
    private val udpgwVersions = arrayOf("tun2proxy", "badvpn")
    private lateinit var serviceModes: Array<String>
    private lateinit var filterModes: Array<String>
    private lateinit var languageLabels: Array<String>
    private val languageValues = arrayOf("auto", "en", "zh", "zh-rTW", "de", "fr", "ja")
    private lateinit var mcpAuthLabels: Array<String>
    private val mcpAuthValues = arrayOf(
        SettingsManager.MCP_AUTH_MODE_NONE,
        SettingsManager.MCP_AUTH_MODE_API_KEY,
        SettingsManager.MCP_AUTH_MODE_BASIC,
        SettingsManager.MCP_AUTH_MODE_OAUTH
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivitySettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize resource-dependent arrays
        serviceModes = arrayOf(
            getString(CoreR.string.service_mode_vpn),
            getString(CoreR.string.service_mode_tproxy)
        )
        filterModes = arrayOf(
            getString(CoreR.string.filter_disallow_mode),
            getString(CoreR.string.filter_allow_mode)
        )
        languageLabels = arrayOf(
            getString(CoreR.string.lang_auto),
            getString(CoreR.string.lang_en),
            getString(CoreR.string.lang_zh_cn),
            getString(CoreR.string.lang_zh_tw),
            getString(CoreR.string.lang_de),
            getString(CoreR.string.lang_fr),
            getString(CoreR.string.lang_ja)
        )
        mcpAuthLabels = arrayOf(
            getString(CoreR.string.mcp_auth_none),
            getString(CoreR.string.mcp_auth_api_key),
            getString(CoreR.string.mcp_auth_basic),
            getString(CoreR.string.mcp_auth_oauth)
        )
        setupAdapters()

        // Geo direct tags：点击放大镜图标弹出搜索多选器（与节点编辑页一致）
        binding.layoutGeositeDirect.setEndIconOnClickListener {
            openGeoTagPicker(GeoTagsPickerBottomSheet.TagKind.SITE, binding.etGeositeDirect)
        }
        binding.layoutGeoipDirect.setEndIconOnClickListener {
            openGeoTagPicker(GeoTagsPickerBottomSheet.TagKind.IP, binding.etGeoipDirect)
        }

        // WebDAV 云备份
        binding.btnWebdavBackup.setOnClickListener { runWebDavBackup() }
        binding.btnWebdavRestore.setOnClickListener { pickAndRestoreWebDav() }
        binding.switchWebdavAuto.setOnCheckedChangeListener { _, checked -> setWebDavAutoBackup(checked) }

        binding.toolbar.setNavigationIcon(R.drawable.ic_back)
        binding.toolbar.setNavigationOnClickListener {
            confirmDiscardOrNavigateHome()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscardOrNavigateHome()
        })

        val bottomBar = binding.btnSave.parent as View
        val initialBottomBarPaddingBottom = bottomBar.paddingBottom
        // 底栏现在是布局流内的兄弟节点（不再是 CoordinatorLayout 悬浮叠层），
        // 静止态不需要再为它预留高度 —— 旧实现写 bottomBar.height，等于在页面底部掏一块空白，
        // 而且一旦 insets 回调没跑到就会留下半途的中间值，把页面末尾压在按钮下面。
        // 这个间距只负责"页面底部呼吸"，并且与布局里 scroll_view 的 paddingBottom 同源，
        // 所以哪怕所有回调一次都没跑到，静止态的留白也是对的。
        val bottomBreathingRoom = resources.getDimensionPixelSize(R.dimen.bottom_scroll_breathing_room)

        // 键盘占多高就留多高 + 呼吸间距；收起（imeBottom <= 0）时回到布局兜底值。
        fun applyScrollBottomSpacing(imeBottom: Int) {
            val currentBinding = _binding ?: return
            val padding = if (imeBottom > 0) imeBottom + bottomBreathingRoom else bottomBreathingRoom
            currentBinding.scrollView.updatePadding(bottom = padding)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0
            
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            _binding?.appBar?.updatePadding(top = systemBars.top)

            bottomBar.isVisible = !imeVisible
            bottomBar.updatePadding(bottom = initialBottomBarPaddingBottom + systemBars.bottom)
            // 打字时整块底栏让位给内容，只按键盘实际占用的高度留白。
            applyScrollBottomSpacing(if (imeVisible) ime.bottom else 0)
            if (imeVisible) {
                v.postDelayed({
                    activity?.currentFocus?.let { scrollToFocusedView(it) }
                }, 120)
            }
            
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)

        setupResponsiveContentWidth()

        ViewCompat.setWindowInsetsAnimationCallback(
            binding.root,
            object : androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP) {
                override fun onEnd(animation: androidx.core.view.WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    val b = _binding ?: return
                    // 收尾两个方向都要做：onProgress 在 imeNow <= 0 时会提前 return，
                    // 关键盘这一程它不会再写终值。少了这一步，paddingBottom 会停在半途的中间值上
                    // —— 这正是"关掉输入法后页面末尾被保存按钮压住"的直接成因。
                    val imeBottom = ViewCompat.getRootWindowInsets(b.root)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    applyScrollBottomSpacing(imeBottom)
                    if (imeBottom > 0) {
                        activity?.currentFocus?.let { scrollToFocusedView(it) }
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<androidx.core.view.WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    // 键盘逐帧升降时，底部留白和"可视底边界"一起变，焦点框必须跟着被顶上去 ——
                    // setOnApplyWindowInsetsListener 的最终值要等动画结束才分发，
                    // 只靠 onEnd 补滚的话，整个弹出过程里输入框都压在键盘下面。
                    if (runningAnimations.none { it.typeMask and WindowInsetsCompat.Type.ime() != 0 }) {
                        return insets
                    }
                    val b = _binding ?: return insets
                    val imeNow = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    if (imeNow <= 0) return insets
                    b.scrollView.updatePadding(bottom = imeNow + bottomBreathingRoom)
                    activity?.currentFocus
                        ?.takeIf { isDescendantOf(it, b.scrollView) }
                        ?.let { revealAboveBottomPadding(b.scrollView, it, animate = false) }
                    return insets
                }
            }
        )

        globalFocusChangeListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            val scroll = _binding?.scrollView ?: return@OnGlobalFocusChangeListener
            if (newFocus != null && isDescendantOf(newFocus, scroll)) {
                if (newFocus is TextInputEditText) configureImeNavigation(newFocus)
                scrollToFocusedView(newFocus)
            }
        }
        binding.scrollView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusChangeListener)

        // 输入法走"窗口 resize"时 ScrollView 高度在获得焦点后才变，焦点那次滚动用的是旧高度。
        // 高度变化时补滚一次，字段才不会被键盘盖住。
        binding.scrollView.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob ->
            if ((b - t) != (ob - ot)) {
                activity?.currentFocus?.let {
                    val scroll = _binding?.scrollView ?: return@let
                    if (isDescendantOf(it, scroll)) scrollToFocusedView(it)
                }
            }
        }

        parentFragmentManager.setFragmentResultListener(
            AppFilterDialogFragment.REQUEST_KEY,
            viewLifecycleOwner
        ) { _, result ->
            binding.etFilterApps.setText(result.getString(AppFilterDialogFragment.RESULT_PACKAGES).orEmpty())
        }

        binding.etFilterApps.setOnClickListener {
            val fragment = AppFilterDialogFragment.newInstance(binding.etFilterApps.text.toString())
            fragment.show(parentFragmentManager, "AppFilterDialog")
        }
        binding.etFilterApps.isFocusable = false
        binding.etFilterApps.isClickable = true

        binding.btnUpdateNow.setOnClickListener {
            binding.btnUpdateNow.isEnabled = false
            binding.btnUpdateNow.text = getString(CoreR.string.updating)
            SettingsManager.updateGeoData(requireContext()) { error ->
                activity?.runOnUiThread {
                    val b = _binding ?: return@runOnUiThread
                    b.btnUpdateNow.isEnabled = true
                    b.btnUpdateNow.text = getString(CoreR.string.update_now)
                    if (error == null) {
                        updateLastUpdateText(SettingsManager.getLastUpdateTime(requireContext()))
                        Toast.makeText(requireContext(), getString(CoreR.string.geodata_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            getString(CoreR.string.error_prefix, error.localizedMessage ?: getString(CoreR.string.error_unknown)),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        binding.switchMcpServer.setOnCheckedChangeListener { _, isChecked ->
            updateMcpControlsEnabled(isChecked)
        }

        binding.etMcpServerPort.doAfterTextChanged { findTextInputLayout(binding.etMcpServerPort)?.error = null }
        binding.etMcpAuthSecret.doAfterTextChanged { binding.layoutMcpAuthSecret.error = null }

        binding.switchDbWeb.setOnCheckedChangeListener { _, isChecked -> updateDbWebControlsEnabled(isChecked) }
        binding.etDbWebPort.doAfterTextChanged { findTextInputLayout(binding.etDbWebPort)?.error = null }
        binding.etDbWebUser.doAfterTextChanged { findTextInputLayout(binding.etDbWebUser)?.error = null }
        binding.etDbWebPass.doAfterTextChanged { binding.layoutDbWebPass.error = null }

        binding.btnCopyMcpConfig.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(CoreR.string.mcp_server_copy_config)
                .setItems(arrayOf("Codex (config.toml)", "Claude Code (.mcp.json)")) { _, which ->
                    val (label, config) = if (which == 0) {
                        "Codex MCP Config" to app.fjj.stun.remote.StunMcpServer.getCodexConfigToml(requireContext())
                    } else {
                        "Claude Code MCP Config" to app.fjj.stun.remote.StunMcpServer.getClaudeConfigJson(requireContext())
                    }
                    val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, config))
                    Toast.makeText(requireContext(), getString(CoreR.string.mcp_server_config_copied), Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        binding.btnCopyDbWebUrl.setOnClickListener {
            val url = app.fjj.stun.dbwebui.DbWebServer.getEffectiveUrl(requireContext())
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("db_web_url", url))
            Toast.makeText(requireContext(), getString(CoreR.string.db_web_url_copied), Toast.LENGTH_SHORT).show()
        }

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        viewModel.settingsState.observe(viewLifecycleOwner) { state ->
            binding.spinnerServiceMode.setText(if (state.serviceMode == SettingsManager.SERVICE_MODE_TPROXY) 
                getString(CoreR.string.service_mode_tproxy) else getString(CoreR.string.service_mode_vpn), false)

            val langIndex = languageValues.indexOf(state.language)
            binding.spinnerLanguage.setText(if (langIndex >= 0) languageLabels[langIndex] else languageLabels[0], false)

            binding.spinnerLogLevel.setText(state.logLevel, false)
            binding.etRemoteDnsServer.setText(state.remoteDns)
            binding.etLocalDnsServer.setText(state.localDns)
            binding.spinnerUdpgwVersion.setText(state.udpgwVersion, false)
            binding.etUdpgwAddr.setText(state.udpgwAddr)
            binding.spinnerFilterMode.setText(if (state.filterMode == 1) getString(CoreR.string.filter_allow_mode) else getString(CoreR.string.filter_disallow_mode), false)
            binding.etFilterApps.setText(state.filterApps)
            binding.etGeositeUrl.setText(state.geositeUrl)
            binding.etGeoipUrl.setText(state.geoipUrl)
            binding.etUpdateInterval.setText(state.updateInterval.toString())
            binding.etGeositeDirect.setText(state.geositeDirect)
            binding.etGeoipDirect.setText(state.geoipDirect)
            fillWebDavUi()
            binding.switchShowNotificationSpeed.isChecked = state.showNotificationSpeed

            binding.etMcpServerPort.setText(state.mcpServerPort.toString())
            binding.switchMcpServer.isChecked = state.mcpServerEnabled
            val authIndex = mcpAuthValues.indexOf(state.mcpAuthMode)
            binding.spinnerMcpAuthMode.setText(if (authIndex >= 0) mcpAuthLabels[authIndex] else mcpAuthLabels[0], false)
            updateMcpAuthSecretUI(state.mcpAuthMode, state.mcpAuthSecret)
            updateMcpControlsEnabled(state.mcpServerEnabled)

            updateMcpServerStatus()

            binding.etDbWebPort.setText(state.dbWebPort.toString())
            binding.etDbWebUser.setText(state.dbWebUser)
            binding.etDbWebPass.setText(state.dbWebPass)
            binding.switchDbWeb.isChecked = state.dbWebEnabled
            updateDbWebControlsEnabled(state.dbWebEnabled)
            updateDbWebStatus()

            updateLastUpdateText(state.lastUpdateTime)
            binding.root.post {
                if (_binding != null && isAdded) initialFormSnapshot = captureFormState()
            }
        }

        viewModel.loadSettings()
    }

    private fun setupResponsiveContentWidth() {
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max_width)
        binding.scrollView.doOnLayout { scrollView ->
            val content = binding.scrollView.getChildAt(0) ?: return@doOnLayout
            val availableWidth = (scrollView.width - scrollView.paddingLeft - scrollView.paddingRight).coerceAtLeast(0)
            content.updateLayoutParams<FrameLayout.LayoutParams> {
                width = minOf(availableWidth, maxWidth)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val bottomBar = binding.btnSave.parent as ViewGroup
        bottomBar.doOnLayout { bar ->
            val availableWidth = (bar.width - bar.paddingLeft - bar.paddingRight).coerceAtLeast(0)
            binding.btnSave.updateLayoutParams<FrameLayout.LayoutParams> {
                width = minOf(availableWidth, maxWidth)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
    }

    private fun updateMcpAuthSecretUI(mode: Int, currentSecret: String? = null) {
        val context = context ?: return
        when (mode) {
            SettingsManager.MCP_AUTH_MODE_NONE -> {
                binding.layoutMcpAuthSecret.visibility = View.GONE
            }
            SettingsManager.MCP_AUTH_MODE_API_KEY -> {
                binding.layoutMcpAuthSecret.visibility = View.VISIBLE
                binding.layoutMcpAuthSecret.hint = getString(CoreR.string.mcp_auth_api_key)
                val secret = if (!currentSecret.isNullOrBlank()) currentSecret else SettingsManager.getMcpApiKey(context)
                binding.etMcpAuthSecret.setText(secret)
            }
            SettingsManager.MCP_AUTH_MODE_BASIC -> {
                binding.layoutMcpAuthSecret.visibility = View.VISIBLE
                binding.layoutMcpAuthSecret.hint = getString(CoreR.string.mcp_basic_password_hint)
                val secret = if (!currentSecret.isNullOrBlank()) currentSecret else SettingsManager.getMcpBasicPass(context)
                binding.etMcpAuthSecret.setText(secret)
            }
            SettingsManager.MCP_AUTH_MODE_OAUTH -> {
                binding.layoutMcpAuthSecret.visibility = View.VISIBLE
                binding.layoutMcpAuthSecret.hint = getString(CoreR.string.mcp_oauth_secret_hint)
                val secret = if (!currentSecret.isNullOrBlank()) currentSecret else SettingsManager.getMcpOAuthClientSecret(context)
                binding.etMcpAuthSecret.setText(secret)
            }
        }
    }

    private fun updateMcpControlsEnabled(enabled: Boolean) {
        binding.etMcpServerPort.isEnabled = enabled
        binding.spinnerMcpAuthMode.isEnabled = enabled
        binding.etMcpAuthSecret.isEnabled = enabled
    }

    private fun updateMcpServerStatus() {
        val server = app.fjj.stun.remote.StunMcpServer
        val running = server.isRunning()
        if (running) {
            binding.tvMcpServerStatus.text = getString(
                CoreR.string.mcp_server_running_format,
                server.getLocalIpAddress(),
                server.getPort()
            )
        } else {
            binding.tvMcpServerStatus.text = getString(CoreR.string.mcp_server_stopped)
        }
        binding.btnCopyMcpConfig.isEnabled = running
        applyStatusGlobeTint(binding.tvMcpServerStatus, running)
    }

    private fun updateDbWebControlsEnabled(enabled: Boolean) {
        binding.etDbWebPort.isEnabled = enabled
        binding.etDbWebUser.isEnabled = enabled
        binding.etDbWebPass.isEnabled = enabled
    }

    private fun updateDbWebStatus() {
        val server = app.fjj.stun.dbwebui.DbWebServer
        val running = server.isRunning()
        if (running) {
            binding.tvDbWebStatus.text = getString(CoreR.string.db_web_started, server.getEffectiveUrl(requireContext()))
        } else {
            binding.tvDbWebStatus.text = getString(CoreR.string.db_web_stopped)
        }
        binding.btnCopyDbWebUrl.isEnabled = running
        applyStatusGlobeTint(binding.tvDbWebStatus, running)
    }

    /**
     * MCP / 数据库 WebUI 状态条左侧的地球图标按运行状态换色。
     *
     * 运行中用「连接状态」那套既有语义色字面量（`connection_state_online` 绿）；
     * 未启动用主题的次级前景色（跟随动态取色，不写死）——「已停止」是正常状态，
     * 不该拿 `connection_state_offline` 那个红来报警。
     *
     * 布局里的 `android:drawableTint` 只是**上屏前的兜底**（按未启动处理）。运行时一律由这里
     * 覆盖；不覆盖的后果是白色地球在浅色主题上不可见 —— `ic_widget_globe` 的
     * fillColor 是 `@android:color/white`，自带 tint。
     */
    private fun applyStatusGlobeTint(tv: android.widget.TextView, running: Boolean) {
        val color = if (running) {
            ContextCompat.getColor(tv.context, R.color.connection_state_online)
        } else {
            MaterialColors.getColor(tv, com.google.android.material.R.attr.colorOnSurfaceVariant)
        }
        tv.setCompoundDrawableTintList(ColorStateList.valueOf(color))
    }

    private fun setupAdapters() {
        val context = requireContext()
        binding.spinnerServiceMode.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, serviceModes))
        binding.spinnerLanguage.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, languageLabels))
        binding.spinnerLogLevel.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, logLevels))
        binding.spinnerUdpgwVersion.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, udpgwVersions))
        binding.spinnerFilterMode.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, filterModes))
        binding.spinnerMcpAuthMode.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, mcpAuthLabels))
        binding.spinnerMcpAuthMode.setOnItemClickListener { _, _, position, _ ->
            val mode = mcpAuthValues[position]
            updateMcpAuthSecretUI(mode)
        }
    }

    private fun updateLastUpdateText(lastUpdate: Long) {
        if (lastUpdate > 0) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            binding.tvLastUpdate.text = getString(CoreR.string.last_updated, sdf.format(Date(lastUpdate * 1000)))
        } else {
            binding.tvLastUpdate.text = getString(CoreR.string.last_updated, getString(CoreR.string.never))
        }
    }

    private fun saveSettings() {
        if (isSaving) return
        val serviceMode = if (binding.spinnerServiceMode.text.toString() == getString(CoreR.string.service_mode_tproxy)) 
            SettingsManager.SERVICE_MODE_TPROXY else SettingsManager.SERVICE_MODE_VPN

        val mcpAuthIndex = mcpAuthLabels.indexOf(binding.spinnerMcpAuthMode.text.toString())
        val mcpAuthMode = if (mcpAuthIndex >= 0) mcpAuthValues[mcpAuthIndex] else SettingsManager.MCP_AUTH_MODE_NONE
        val authSecret = binding.etMcpAuthSecret.text.toString().trim()

        val mcpEnabled = binding.switchMcpServer.isChecked
        val mcpPort = binding.etMcpServerPort.text.toString().toIntOrNull()
        if (mcpEnabled && (mcpPort == null || mcpPort !in 1..65535)) {
            findTextInputLayout(binding.etMcpServerPort)?.error = getString(CoreR.string.error_invalid_port)
            binding.etMcpServerPort.requestFocus()
            scrollToFocusedView(binding.etMcpServerPort)
            return
        }
        if (mcpEnabled && mcpAuthMode != SettingsManager.MCP_AUTH_MODE_NONE && authSecret.isBlank()) {
            binding.layoutMcpAuthSecret.error = getString(CoreR.string.error_field_required)
            binding.etMcpAuthSecret.requestFocus()
            scrollToFocusedView(binding.etMcpAuthSecret)
            return
        }

        val dbEnabled = binding.switchDbWeb.isChecked
        val dbPort = binding.etDbWebPort.text.toString().toIntOrNull()
        val dbUser = binding.etDbWebUser.text.toString().trim()
        val dbPass = binding.etDbWebPass.text.toString()
        if (dbEnabled && (dbPort == null || dbPort !in 1..65535)) {
            findTextInputLayout(binding.etDbWebPort)?.error = getString(CoreR.string.error_invalid_port)
            binding.etDbWebPort.requestFocus()
            scrollToFocusedView(binding.etDbWebPort)
            return
        }
        if (dbEnabled && dbUser.isBlank()) {
            findTextInputLayout(binding.etDbWebUser)?.error = getString(CoreR.string.error_field_required)
            binding.etDbWebUser.requestFocus()
            scrollToFocusedView(binding.etDbWebUser)
            return
        }
        
        val currentState = SettingsState(
            serviceMode = serviceMode,
            logLevel = binding.spinnerLogLevel.text.toString(),
            remoteDns = binding.etRemoteDnsServer.text.toString(),
            localDns = binding.etLocalDnsServer.text.toString(),
            udpgwVersion = binding.spinnerUdpgwVersion.text.toString(),
            udpgwAddr = binding.etUdpgwAddr.text.toString(),
            geositeUrl = binding.etGeositeUrl.text.toString(),
            geoipUrl = binding.etGeoipUrl.text.toString(),
            updateInterval = binding.etUpdateInterval.text.toString().toLongOrNull() ?: 0L,
            geositeDirect = binding.etGeositeDirect.text.toString(),
            geoipDirect = binding.etGeoipDirect.text.toString(),
            filterMode = if (binding.spinnerFilterMode.text.toString() == getString(CoreR.string.filter_allow_mode)) 1 else 0,
            filterApps = binding.etFilterApps.text.toString(),
            showNotificationSpeed = binding.switchShowNotificationSpeed.isChecked,
            mcpServerEnabled = mcpEnabled,
            mcpServerPort = mcpPort ?: SettingsManager.DEFAULT_MCP_SERVER_PORT,
            mcpAuthMode = mcpAuthMode,
            mcpAuthSecret = authSecret,
            dbWebEnabled = dbEnabled,
            dbWebPort = dbPort ?: SettingsManager.DEFAULT_DB_WEB_PORT,
            dbWebUser = dbUser,
            dbWebPass = dbPass
        )

        val langIndex = languageLabels.indexOf(binding.spinnerLanguage.text.toString())
        val newLang = languageValues.getOrElse(langIndex) { "auto" }
        val oldLang = SettingsManager.getLanguage(requireContext())

        isSaving = true
        binding.btnSave.isEnabled = false
        viewModel.saveSettings(currentState) saveResult@ { error ->
            val b = _binding ?: return@saveResult
            isSaving = false
            b.btnSave.isEnabled = true
            if (error != null) {
                Toast.makeText(
                    requireContext(),
                    getString(CoreR.string.error_prefix, error.localizedMessage ?: getString(CoreR.string.error_unknown)),
                    Toast.LENGTH_LONG
                ).show()
                return@saveResult
            }

            val server = app.fjj.stun.remote.StunMcpServer
            if (currentState.mcpServerEnabled) {
                server.restart(requireContext(), currentState.mcpServerPort)
            } else {
                server.stop()
            }
            updateMcpServerStatus()

            val dbServer = app.fjj.stun.dbwebui.DbWebServer
            if (currentState.dbWebEnabled) {
                dbServer.restart(requireContext(), currentState.dbWebPort)
            } else {
                dbServer.stop()
            }
            updateDbWebStatus()

            initialFormSnapshot = captureFormState()

            if (newLang != oldLang) {
                SettingsManager.saveLanguage(requireContext(), newLang)
                val localeTag = when (newLang) {
                    "en" -> "en"
                    "zh" -> "zh-CN"
                    "zh-rTW" -> "zh-TW"
                    "de" -> "de"
                    "fr" -> "fr"
                    "ja" -> "ja"
                    else -> ""
                }
                val appLocale = androidx.core.os.LocaleListCompat.forLanguageTags(localeTag)
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(appLocale)
                // Launcher 快捷方式与小组件文案不由 AppCompat 管辖，需手动重注册
                app.fjj.stun.util.DynamicShortcutManager.updateShortcuts(requireContext())
                app.fjj.stun.widget.StunWidgets.refreshAll(requireContext())
            } else {
                Toast.makeText(requireContext(), getString(CoreR.string.settings_saved), Toast.LENGTH_SHORT).show()
                (requireActivity() as MainActivity).navigateToHome()
            }
        }
    }

    // ── WebDAV 云备份 ──

    private fun fillWebDavUi() {
        val ctx = requireContext().applicationContext
        binding.etWebdavUrl.setText(SettingsManager.getWebDavUrl(ctx))
        binding.etWebdavUser.setText(SettingsManager.getWebDavUser(ctx))
        binding.etWebdavPass.setText(SettingsManager.getWebDavPass(ctx))
        binding.etWebdavPin.setText(SettingsManager.getWebDavPin(ctx))
        binding.etWebdavInterval.setText(SettingsManager.getWebDavBackupIntervalHours(ctx).toString())
        binding.switchWebdavAuto.isChecked = SettingsManager.isWebDavAutoBackupEnabled(ctx)
        updateWebDavLastText(ctx)
    }

    private fun updateWebDavLastText(ctx: Context) {
        val last = SettingsManager.getWebDavLastBackupTime(ctx)
        binding.tvWebdavLast.text = if (last > 0) {
            getString(
                CoreR.string.webdav_last,
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(last))
            )
        } else {
            getString(CoreR.string.webdav_last, getString(CoreR.string.subscription_never_synced))
        }
    }

    private fun captureWebDavConfig(): app.fjj.stun.backup.WebDavBackupManager.Config {
        return app.fjj.stun.backup.WebDavBackupManager.Config(
            url = binding.etWebdavUrl.text.toString().trim(),
            user = binding.etWebdavUser.text.toString().trim(),
            pass = binding.etWebdavPass.text.toString(),
            pin = binding.etWebdavPin.text.toString().trim()
        )
    }

    /** 备份 PIN 最短长度：低于它的 PIN 在 PBKDF2 面前约等于没设，直接在字段上拦掉。 */
    private val minWebDavPinLength = 4

    /**
     * PIN 长度校验（就地打字段错误，返回是否放行）。
     *
     * 空 PIN **不算**"太短"：那是"配置还没填完"，该由配置不完整那条提示来说，
     * 两个错同时报只会让人以为 PIN 写错了。
     */
    private fun validateWebDavPin(): Boolean {
        val pin = binding.etWebdavPin.text.toString().trim()
        val tooShort = pin.isNotEmpty() && pin.length < minWebDavPinLength
        binding.tilWebdavPin.error = if (tooShort) getString(CoreR.string.webdav_pin_too_short) else null
        return !tooShort
    }

    /**
     * 把备份/恢复的失败原因翻成人话。
     *
     * 旧实现直接把 `e.message` 拼进提示语，用户看到的是 "wrong pin or no backup file"
     * 这种英文内部串，而且"PIN 记错"和"备份没了"这两件补救方式完全不同的事共用一句话。
     */
    private fun webDavErrorText(e: Exception): String = when {
        e is app.fjj.stun.backup.WebDavBackupManager.BackupException -> when (e.code) {
            app.fjj.stun.backup.WebDavBackupManager.ErrorCode.CONFIG_INCOMPLETE ->
                getString(CoreR.string.webdav_err_config)
            app.fjj.stun.backup.WebDavBackupManager.ErrorCode.PIN_MISMATCH ->
                getString(CoreR.string.webdav_err_pin)
            app.fjj.stun.backup.WebDavBackupManager.ErrorCode.PAYLOAD_MISSING ->
                getString(CoreR.string.webdav_err_missing)
            else -> e.message ?: e.javaClass.simpleName
        }
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun saveWebDavConfigIfComplete() {
        val config = captureWebDavConfig()
        val appCtx = requireContext().applicationContext
        // 太短的 PIN 不落盘（也不去清空已有配置）：让用户改回合规值再说
        if (config.isConfigured && validateWebDavPin()) {
            SettingsManager.saveWebDavConfig(appCtx, config.url, config.user, config.pass, config.pin)
        }
        // 间隔独立保存（无需完整配置）；变更后重排 WorkManager 周期
        binding.etWebdavInterval.text.toString().toLongOrNull()?.let {
            SettingsManager.saveWebDavBackupIntervalHours(appCtx, it)
        }
        app.fjj.stun.worker.WebDavBackupWorker.schedule(appCtx)
    }

    private fun runWebDavBackup() {
        if (webDavJob?.isActive == true) return
        // 先拦太短的 PIN：否则备份会用一个人形同虚设的密钥加密后传到公网网盘上
        if (!validateWebDavPin()) return
        saveWebDavConfigIfComplete()
        val config = captureWebDavConfig()
        if (!config.isConfigured) {
            Toast.makeText(requireContext(), CoreR.string.webdav_err_config, Toast.LENGTH_SHORT).show()
            return
        }
        val appCtx = requireContext().applicationContext
        webDavJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.btnWebdavBackup.isEnabled = false
            binding.btnWebdavRestore.isEnabled = false
            try {
                val result = app.fjj.stun.backup.WebDavBackupManager.backup(appCtx, config)
                SettingsManager.saveWebDavLastBackupTime(appCtx, System.currentTimeMillis())
                Toast.makeText(
                    requireContext(),
                    getString(
                        CoreR.string.webdav_ok,
                        result.profiles,
                        app.fjj.stun.backup.WebDavBackupManager.sectionSummary(appCtx, result.sections)
                    ), Toast.LENGTH_LONG
                ).show()
                updateWebDavLastText(appCtx)
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(CoreR.string.webdav_failed, webDavErrorText(e)), Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnWebdavBackup.isEnabled = true
                binding.btnWebdavRestore.isEnabled = true
            }
        }
    }

    /** 恢复入口：列出服务器上的备份目录 → 单选 → 二次确认（合并/覆盖不可撤销）。 */
    private fun pickAndRestoreWebDav() {
        if (webDavJob?.isActive == true) return
        if (!validateWebDavPin()) return
        saveWebDavConfigIfComplete()
        val config = captureWebDavConfig()
        if (!config.isConfigured) {
            Toast.makeText(requireContext(), CoreR.string.webdav_err_config, Toast.LENGTH_SHORT).show()
            return
        }
        webDavJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.btnWebdavBackup.isEnabled = false
            binding.btnWebdavRestore.isEnabled = false
            try {
                val backups = app.fjj.stun.backup.WebDavBackupManager.listBackups(config)
                if (backups.isEmpty()) {
                    Toast.makeText(requireContext(), CoreR.string.webdav_no_backups, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val labels = backups.map {
                    app.fjj.stun.backup.WebDavBackupManager.formatDirForDisplay(it)
                }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(CoreR.string.webdav_restore_pick_title)
                    .setItems(labels) { _, idx -> confirmRestoreWebDav(config, backups[idx]) }
                    .setNegativeButton(CoreR.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(CoreR.string.webdav_failed, webDavErrorText(e)), Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnWebdavBackup.isEnabled = true
                binding.btnWebdavRestore.isEnabled = true
            }
        }
    }

    /** 二次确认：恢复会按 id 合并节点并覆盖设置，必须明确警告。 */
    private fun confirmRestoreWebDav(
        config: app.fjj.stun.backup.WebDavBackupManager.Config,
        dir: String
    ) {
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(CoreR.string.webdav_restore_confirm)
            .setNegativeButton(CoreR.string.cancel, null)
            .setPositiveButton(CoreR.string.webdav_restore) { _, _ -> restoreWebDavFrom(config, dir) }
            .show()
    }

    private fun restoreWebDavFrom(
        config: app.fjj.stun.backup.WebDavBackupManager.Config,
        dir: String
    ) {
        if (webDavJob?.isActive == true) return
        webDavJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.btnWebdavBackup.isEnabled = false
            binding.btnWebdavRestore.isEnabled = false
            try {
                val appCtx = requireContext().applicationContext
                val result = app.fjj.stun.backup.WebDavBackupManager.restore(appCtx, config, dir)
                if (result.settings) {
                    // 快照可能带回新的 auto/interval 配置，重排周期备份
                    app.fjj.stun.worker.WebDavBackupWorker.schedule(appCtx)
                }
                // 按"到底恢复了哪些分区"选文案，而不是只看 settings：
                // 设置分区失败但订阅恢复了的话，报"只恢复了节点"等于把恢复到的内容瞒下来。
                Toast.makeText(
                    requireContext(),
                    if (result.sections.isEmpty()) {
                        getString(CoreR.string.webdav_restore_ok, result.profiles)
                    } else {
                        getString(
                            CoreR.string.webdav_restore_ok_full,
                            result.profiles,
                            app.fjj.stun.backup.WebDavBackupManager.sectionSummary(appCtx, result.sections)
                        )
                    }, Toast.LENGTH_LONG
                ).show()
                updateWebDavLastText(appCtx)
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    getString(CoreR.string.webdav_failed, webDavErrorText(e)), Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.btnWebdavBackup.isEnabled = true
                binding.btnWebdavRestore.isEnabled = true
            }
        }
    }

    private var webDavJob: kotlinx.coroutines.Job? = null

    private fun setWebDavAutoBackup(enabled: Boolean) {
        saveWebDavConfigIfComplete()
        SettingsManager.setWebDavAutoBackup(requireContext().applicationContext, enabled)
        app.fjj.stun.worker.WebDavBackupWorker.schedule(requireContext().applicationContext)
    }

    private fun openGeoTagPicker(kind: GeoTagsPickerBottomSheet.TagKind, target: com.google.android.material.textfield.TextInputEditText) {
        val current = target.text?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        geoTagPickerTarget = target
        GeoTagsPickerBottomSheet.newInstance(kind, current).show(childFragmentManager, "GeoTagsPicker")
    }

    override fun onTagsConfirmed(kind: GeoTagsPickerBottomSheet.TagKind, selected: List<String>) {
        // 选择器旋转恢复后从 parentFragment 解析本接口回传
        val target = when (kind) {
            GeoTagsPickerBottomSheet.TagKind.SITE -> geoTagPickerTarget ?: binding.etGeositeDirect
            GeoTagsPickerBottomSheet.TagKind.IP -> geoTagPickerTarget ?: binding.etGeoipDirect
        }
        target.setText(selected.joinToString(","))
    }

    private fun confirmDiscardOrNavigateHome() {
        if (isSaving) return
        if (hasUnsavedChanges()) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(CoreR.string.unsaved_changes_title)
                .setMessage(CoreR.string.unsaved_changes_msg)
                .setPositiveButton(CoreR.string.ok) { _, _ ->
                    (requireActivity() as MainActivity).navigateToHome()
                }
                .setNegativeButton(CoreR.string.cancel, null)
                .show()
        } else {
            (requireActivity() as MainActivity).navigateToHome()
        }
    }

    private fun hasUnsavedChanges(): Boolean {
        val initial = initialFormSnapshot ?: return false
        return captureFormState() != initial
    }

    private fun captureFormState(): String {
        val values = mutableListOf<String>()
        fun collect(view: View) {
            when (view) {
                is android.widget.CompoundButton -> values += "${view.id}:checked=${view.isChecked}"
                is android.widget.EditText -> values += "${view.id}:text=${view.text}"
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) collect(view.getChildAt(index))
            }
        }
        collect(binding.root)
        return values.sorted().joinToString("\u0000")
    }

    private fun findTextInputLayout(view: View): com.google.android.material.textfield.TextInputLayout? {
        var parent = view.parent
        while (parent is View) {
            if (parent is com.google.android.material.textfield.TextInputLayout) return parent
            parent = parent.parent
        }
        return null
    }

    /**
     * 把焦点框滚到键盘之上。
     *
     * 不能再用 requestRectangleOnScreen：NestedScrollView 认为"可视底边"就是自己的高度
     * （不扣 getPaddingBottom()），而 targetSdk 35 起窗口不再随键盘 resize，ScrollView 的底边
     * 正压在键盘下面就 —— 焦点框落进被键盘盖住的那块会被判成"已可见"，于是不再滚动。
     * 改按真实可视底边界自算差值。post 一层等本次布局完成。
     *
     * @param animate false 供键盘动画逐帧调用，避免每帧启动一个新动画。
     */
    private fun scrollToFocusedView(view: View, animate: Boolean = true) {
        val scroll = _binding?.scrollView ?: return
        view.post {
            if (!isAdded || _binding == null) return@post
            if (scroll !== _binding?.scrollView) return@post
            if (!view.isFocused) return@post
            revealAboveBottomPadding(scroll, view, animate)
        }
    }

    private fun configureImeNavigation(input: TextInputEditText) {
        if (input.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return
        input.imeOptions = if (findNextVisibleInput(input) != null) {
            EditorInfo.IME_ACTION_NEXT
        } else {
            EditorInfo.IME_ACTION_DONE
        }
        input.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.let {
                it.keyCode == KeyEvent.KEYCODE_ENTER && it.action == KeyEvent.ACTION_DOWN
            } == true
            when {
                actionId == EditorInfo.IME_ACTION_NEXT || enterPressed -> {
                    val next = findNextVisibleInput(input)
                    if (next != null) {
                        next.requestFocus()
                        scrollToFocusedView(next)
                    } else {
                        finishImeEditing(input)
                    }
                    true
                }
                actionId == EditorInfo.IME_ACTION_DONE -> {
                    finishImeEditing(input)
                    true
                }
                else -> false
            }
        }
    }

    private fun findNextVisibleInput(current: TextInputEditText): TextInputEditText? {
        val b = _binding ?: return null
        val inputs = mutableListOf<TextInputEditText>()
        collectVisibleInputs(b.scrollView, inputs)
        val currentIndex = inputs.indexOf(current)
        return if (currentIndex >= 0) inputs.drop(currentIndex + 1).firstOrNull() else null
    }

    private fun collectVisibleInputs(view: View, result: MutableList<TextInputEditText>) {
        when (view) {
            is TextInputEditText -> if (
                view.isShown && view.isEnabled && view.isFocusable &&
                view.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0
            ) {
                result += view
            }
            is ViewGroup -> for (index in 0 until view.childCount) {
                collectVisibleInputs(view.getChildAt(index), result)
            }
        }
    }

    private fun finishImeEditing(view: View) {
        (context?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun isDescendantOf(child: View, parent: View): Boolean {
        var p = child.parent
        while (p != null) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    override fun onDestroyView() {
        globalFocusChangeListener?.let {
            _binding?.scrollView?.viewTreeObserver?.removeOnGlobalFocusChangeListener(it)
        }
        globalFocusChangeListener = null
        imeVisible = false
        super.onDestroyView()
        _binding = null
    }
}
