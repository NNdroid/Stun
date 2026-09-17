package app.fjj.stun.ui

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import app.fjj.stun.R
import app.fjj.stun.databinding.ActivityProfileEditBinding
import app.fjj.stun.databinding.ViewProfileKcpOptionsBinding
import app.fjj.stun.databinding.ViewProfileUdpCustomOptionsBinding
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.Profile
import app.fjj.stun.ui.viewmodel.ProfileEditViewModel
import app.fjj.stun.util.revealAboveBottomPadding
import androidx.activity.viewModels
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ProfileEditActivity : BaseActivity(), GeoTagsPickerBottomSheet.OnTagsConfirmedListener {

    private lateinit var binding: ActivityProfileEditBinding
    private val viewModel: ProfileEditViewModel by viewModels()
    private var profileId: String? = null
    private var currentProfile: Profile = Profile()
    private lateinit var filterModes: Array<String>
    private var globalFocusChangeListener: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null
    private var initialFormSnapshot: String? = null
    private var isSaveInProgress = false
    private var imeVisible = false
    private var geoTagPickerTarget: com.google.android.material.textfield.TextInputEditText? = null
    private var kcpOptionsBinding: ViewProfileKcpOptionsBinding? = null
    private var udpCustomOptionsBinding: ViewProfileUdpCustomOptionsBinding? = null
    private val authTypes = arrayOf(Profile.AUTH_TYPE_PASSWORD, Profile.AUTH_TYPE_PRIVATEKEY)
    private val udpgwVersions = arrayOf("tun2proxy", "badvpn")
    // docs/transports.md §5：alpn 字段仅 xhttp 生效；raw 固定 Chrome ALPN、
    // websocket 固定 http/1.1、quic 固定 h3，均不可配。
    private val alpnOptionsXhttp = arrayOf("h3,h2,http/1.1", "h3,h2", "h2,h3", "h3", "h2", "http/1.1", "h2,http/1.1")
    private val icmpMtuModeOptions = arrayOf("probe", "auto", "fixed")
    private val dnsRecordTypes = arrayOf("txt", "null", "cname", "a", "aaaa", "mx", "srv", "ns")
    private val kcpCryptOptions = arrayOf("none", "aes", "aes-128", "aes-192", "aes-128-gcm", "sm4", "tea", "xtea", "salsa20", "blowfish", "twofish", "cast5", "3des", "xor")
    private val kcpModeOptions = arrayOf("fast", "normal", "fast2", "fast3")
    private val xhttpStreamModeOptions = arrayOf("auto", "stream", "poll")
    // masque 承载 ALPN：SDK 侧只有 ""(auto) / "h3" / "h2" 三个取值（client_api.go 按严格字符串
    // 相等校验，不做逗号拆分），故这里只提供这三项。选 "auto" 时由 myssh 的 normalizeMasqueALPN
    // 翻成 SDK 空串（h3 优先，grace 失败后 pin h2）。
    private val masqueAlpnOptions = arrayOf("auto", "h3", "h2")
    // docs/transports.md §4.9：路径 MTU 探测 auto(默认=开)/on(强制开)/off(固定 max_pkt)
    private val udpMtuProbeOptions = arrayOf("auto", "on", "off")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupThemeAndNavigation()
        setupListeners()
        observeViewModel()

        supportFragmentManager.setFragmentResultListener(
            AppFilterDialogFragment.REQUEST_KEY,
            this
        ) { _, result ->
            binding.etFilterApps.setText(result.getString(AppFilterDialogFragment.RESULT_PACKAGES).orEmpty())
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmDiscardOrFinish()
        })
        
        viewModel.loadProfile(profileId)
    }

    private fun setupThemeAndNavigation() {
        filterModes = arrayOf(
            getString(CoreR.string.filter_disallow_mode),
            getString(CoreR.string.filter_allow_mode)
        )

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        profileId = intent.getStringExtra("EXTRA_PROFILE_ID")
        val isEdit = profileId != null
        supportActionBar?.title = if (isEdit) getString(CoreR.string.edit_profile) else getString(CoreR.string.add_profile)

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
            val padding = if (imeBottom > 0) imeBottom + bottomBreathingRoom else bottomBreathingRoom
            binding.scrollView.updatePadding(bottom = padding)
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime()) && ime.bottom > 0
            
            v.updatePadding(left = systemBars.left, right = systemBars.right, top = systemBars.top)

            // Typing needs vertical context more than a persistent Save button. Hide the
            // bottom action while the IME is open and reserve only the actual IME inset.
            bottomBar.isVisible = !imeVisible
            bottomBar.updatePadding(bottom = initialBottomBarPaddingBottom + systemBars.bottom)
            // 打字时整块底栏让位给内容，只按键盘实际占用的高度留白。
            applyScrollBottomSpacing(if (imeVisible) ime.bottom else 0)
            if (imeVisible) {
                binding.root.postDelayed({
                    currentFocus?.let { scrollToFocusedView(it) }
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
                    // 收尾两个方向都要做：onProgress 在 imeNow <= 0 时会提前 return，
                    // 关键盘这一程它不会再写终值。少了这一步，paddingBottom 会停在半途的中间值上
                    // —— 这正是"关掉输入法后页面末尾被保存按钮压住"的直接成因。
                    val imeBottom = ViewCompat.getRootWindowInsets(binding.root)
                        ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                    applyScrollBottomSpacing(imeBottom)
                    if (imeBottom > 0) {
                        currentFocus?.let { scrollToFocusedView(it) }
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
                    val imeNow = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    if (imeNow <= 0) return insets
                    binding.scrollView.updatePadding(bottom = imeNow + bottomBreathingRoom)
                    currentFocus?.takeIf { isDescendantOf(it, binding.scrollView) }?.let {
                        revealAboveBottomPadding(binding.scrollView, it, animate = false)
                    }
                    return insets
                }
            }
        )

        globalFocusChangeListener = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus != null && isDescendantOf(newFocus, binding.scrollView)) {
                if (newFocus is TextInputEditText) configureImeNavigation(newFocus)
                scrollToFocusedView(newFocus)
            }
        }
        binding.scrollView.viewTreeObserver.addOnGlobalFocusChangeListener(globalFocusChangeListener)

        // 输入法弹出时，若走的是"窗口 resize"而非 insets 上报，ScrollView 的高度会在获得焦点之后才变化，
        // 焦点监听那次滚动用的是旧高度 → 字段仍被键盘盖住。这里在高度变化时补滚一次。
        binding.scrollView.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if ((b - t) != (ob - ot)) {
                currentFocus?.let { if (isDescendantOf(it, binding.scrollView)) scrollToFocusedView(it) }
            }
        }
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

        val bottomBar = binding.btnSave.parent as android.view.ViewGroup
        bottomBar.doOnLayout { bar ->
            val availableWidth = (bar.width - bar.paddingLeft - bar.paddingRight).coerceAtLeast(0)
            binding.btnSave.updateLayoutParams<FrameLayout.LayoutParams> {
                width = minOf(availableWidth, maxWidth)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
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
        if (isFinishing || isDestroyed) return
        view.post {
            if (isFinishing || isDestroyed) return@post
            if (!view.isFocused) return@post
            revealAboveBottomPadding(binding.scrollView, view, animate)
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
        val inputs = mutableListOf<TextInputEditText>()
        collectVisibleInputs(binding.scrollView, inputs)
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
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
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

    private fun setupListeners() {
        // Dropdown changes
        binding.spinnerTunnelType.setOnItemClickListener { _, _, _, _ -> updateUIBasedOnSettings() }
        binding.switchTunnelTls.setOnCheckedChangeListener { _, _ -> updateUIBasedOnSettings() }
        binding.spinnerAuthType.setOnItemClickListener { _, _, position, _ -> updateAuthTypeVisibility(authTypes[position]) }
        binding.spinnerAlpn.setOnItemClickListener { _, _, _, _ -> /* Update logic if needed */ }

        // Switch changes
        binding.switchDnsOverride.setOnCheckedChangeListener { _, isChecked -> binding.layoutDnsOverride.isVisible = isChecked }
        binding.switchAuthRequired.setOnCheckedChangeListener { _, _ -> updateProxyAuthVisibility(); if (!bindingInProgress) applyLiveErrors(null) }
        binding.switchAppFilterOverride.setOnCheckedChangeListener { _, isChecked -> binding.layoutAppFilterOverride.isVisible = isChecked }
        binding.switchVerifySshFingerprint.setOnCheckedChangeListener { _, isChecked -> binding.layoutSshFingerprint.isVisible = isChecked; if (!bindingInProgress) applyLiveErrors(setOf(FieldKey.SSH_FINGERPRINT)) }
        binding.switchVerifyCertFingerprint.setOnCheckedChangeListener { _, isChecked -> binding.layoutCertFingerprint.isVisible = isChecked; if (!bindingInProgress) applyLiveErrors(setOf(FieldKey.CERT_FINGERPRINT)) }
        binding.spinnerDnsRecordType.setOnItemClickListener { _, _, _, _ -> if (!bindingInProgress) applyLiveErrors(setOf(FieldKey.DNS_RECORD_TYPE)) }
        binding.switchEnableCustomPath.setOnCheckedChangeListener { _, _ -> updateUIBasedOnSettings() }

        // Fetch Fingerprint Buttons
        binding.btnFetchSshFingerprint.setOnClickListener {
            val sshAddr = binding.etSshAddr.text.toString().trim()
            if (sshAddr.isBlank()) {
                binding.layoutSshAddr.error = getString(CoreR.string.error_missing_ssh_addr)
                binding.etSshAddr.requestFocus()
                return@setOnClickListener
            }

            binding.btnFetchSshFingerprint.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fp = myssh.Myssh.getSSHFingerprint(sshAddr)
                    withContext(Dispatchers.Main) {
                        binding.btnFetchSshFingerprint.isEnabled = true
                        binding.etSshFingerprint.setText(fp)
                        binding.switchVerifySshFingerprint.isChecked = true
                        binding.layoutSshFingerprint.isVisible = true
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.ssh_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnFetchSshFingerprint.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnFetchCertFingerprint.setOnClickListener {
            val target = binding.etProxyAddr.text.toString().trim().ifBlank { 
                binding.etSshAddr.text.toString().trim() 
            }
            if (target.isBlank()) {
                binding.layoutProxyAddr.error = getString(CoreR.string.error_missing_proxy_or_ssh_addr)
                binding.etProxyAddr.requestFocus()
                return@setOnClickListener
            }
            val serverName = binding.etServerName.text.toString().trim().ifBlank {
                binding.etCustomHost.text.toString().trim()
            }

            binding.btnFetchCertFingerprint.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fp = myssh.Myssh.getTLSCertFingerprint(target, serverName)
                    withContext(Dispatchers.Main) {
                        binding.btnFetchCertFingerprint.isEnabled = true
                        binding.etCertFingerprint.setText(fp)
                        binding.switchVerifyCertFingerprint.isChecked = true
                        binding.layoutCertFingerprint.isVisible = true
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.cert_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnFetchCertFingerprint.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // Details Buttons
        binding.btnDetailsSsh.setOnClickListener {
            val sshAddr = binding.etSshAddr.text.toString().trim()
            if (sshAddr.isBlank()) {
                binding.layoutSshAddr.error = getString(CoreR.string.error_missing_ssh_addr)
                binding.etSshAddr.requestFocus()
                return@setOnClickListener
            }

            binding.btnDetailsSsh.isEnabled = false
            Toast.makeText(this, getString(CoreR.string.fetching_details), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = myssh.Myssh.getSSHServerDetailsJSON(sshAddr)
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsSsh.isEnabled = true
                        showSSHDetailsDialog(jsonStr)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsSsh.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        binding.btnDetailsCert.setOnClickListener {
            val target = binding.etProxyAddr.text.toString().trim().ifBlank { 
                binding.etSshAddr.text.toString().trim() 
            }
            if (target.isBlank()) {
                binding.layoutProxyAddr.error = getString(CoreR.string.error_missing_proxy_or_ssh_addr)
                binding.etProxyAddr.requestFocus()
                return@setOnClickListener
            }
            val serverName = binding.etServerName.text.toString().trim().ifBlank {
                binding.etCustomHost.text.toString().trim()
            }

            binding.btnDetailsCert.isEnabled = false
            Toast.makeText(this, getString(CoreR.string.fetching_details), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val jsonStr = myssh.Myssh.getTLSCertDetailsJSON(target, serverName)
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsCert.isEnabled = true
                        showTLSDetailsDialog(jsonStr)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        binding.btnDetailsCert.isEnabled = true
                        val msg = e.localizedMessage ?: e.message ?: "Unknown error"
                        Toast.makeText(this@ProfileEditActivity, getString(CoreR.string.error_prefix, msg), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        // 实时校验：与保存共用 FieldRules.compute（同一规则单一来源）。字段被编辑过
        // 才纳入展示（touchedKeys），避免边输边报红的打扰；SSH 凭据的私钥/口令校验
        // 涉及原生调用，保留在保存路径。
        liveWatch(binding.etSshAddr, FieldKey.SSH_ADDR)
        liveWatch(binding.etProxyAddr, FieldKey.PROXY_ADDR)
        liveWatch(binding.etServerName, FieldKey.SERVER_NAME)
        liveWatch(binding.etCustomPath, FieldKey.CUSTOM_PATH)
        liveWatch(binding.etDnsTunnelServers, FieldKey.DNS_SERVERS)
        liveWatch(binding.etDnsTunnelDomain, FieldKey.DNS_DOMAIN)
        liveWatch(binding.etDnsTunnelPsk, FieldKey.DNS_PSK)
        liveWatch(binding.etDnsTunnelMarker, FieldKey.DNS_MARKER)
        liveWatch(binding.etNoisePublicKey, FieldKey.NOISE_KEY)
        liveWatch(binding.etHttpPayload, FieldKey.HTTP_PAYLOAD)
        liveWatch(binding.etXhttpChunkSize, FieldKey.CHUNK_SIZE)
        liveWatch(binding.etHeartbeatInterval, FieldKey.HEARTBEAT)
        // myssh 152c556
        liveWatch(binding.etPaddingMinBytes, FieldKey.PADDING_MIN_BYTES)
        liveWatch(binding.spinnerMasqueAlpn, FieldKey.MASQUE_ALPN)
        liveWatch(binding.etAuthUser, FieldKey.AUTH_USER)
        liveWatch(binding.etAuthPass, FieldKey.AUTH_PASS)
        liveWatch(binding.etAuthToken, FieldKey.AUTH_TOKEN)
        liveWatch(binding.etSshFingerprint, FieldKey.SSH_FINGERPRINT)
        liveWatch(binding.etCertFingerprint, FieldKey.CERT_FINGERPRINT)
        liveWatch(binding.etIcmpPsk, FieldKey.ICMP_PSK)
        liveWatch(binding.etIcmpMagic, FieldKey.ICMP_MAGIC)
        binding.etPrivateKey.doAfterTextChanged { binding.layoutPrivateKey.error = null }
        binding.etPass.doAfterTextChanged { binding.layoutPass.error = null }
        // App Filter Dialog
        binding.etFilterApps.setOnClickListener {
            val fragment = AppFilterDialogFragment.newInstance(binding.etFilterApps.text.toString())
            fragment.show(supportFragmentManager, "AppFilterDialog")
        }

        binding.btnSave.setOnClickListener { validateAndSave() }
    }

    private fun observeViewModel() {
        viewModel.profile.observe(this) { profile ->
            currentProfile = profile
            bindProfileToUI(profile)
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    initialFormSnapshot = captureFormState()
                }
            }
        }

        viewModel.saveResult.observe(this) { success ->
            isSaveInProgress = false
            binding.btnSave.isEnabled = true
            if (success) {
                initialFormSnapshot = captureFormState()
                Toast.makeText(this, getString(CoreR.string.profile_saved), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(CoreR.string.error_unknown), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun bindProfileToUI(profile: Profile) {
        // 回填期间挂起实时校验（setText 会触发 watcher），并结束统一计算一次
        bindingInProgress = true
        binding.apply {
            etName.setText(profile.name)
            etSshAddr.setText(profile.sshAddr)
            etNote.setText(profile.note)
            etUser.setText(profile.user)
            
            spinnerAuthType.setText(if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY) 
                getString(CoreR.string.auth_key) else getString(CoreR.string.auth_password), false)
            updateAuthTypeVisibility(profile.authType)
            
            etPrivateKey.setText(profile.privateKey)
            etKeyPass.setText("") // Clear for security

            spinnerTunnelType.setText(profile.tunnelType, false)
            spinnerDnsRecordType.setText(profile.dnsTunnelType.ifBlank { "txt" }, false)
            switchDnsTunnelEdns0.isChecked = profile.dnsTunnelEDNS0
            etHttpPayload.setText(profile.httpPayload)
            switchDisableStatusCheck.isChecked = profile.disableStatusCheck
            
            if (profile.tunnelType == Profile.TUNNEL_TYPE_KCP) ensureKcpOptions()
            if (profile.tunnelType == Profile.TUNNEL_TYPE_UDP_CUSTOM) ensureUdpCustomOptions()

            etIcmpPsk.setText(profile.icmpCustomPsk)
            etIcmpMagic.setText(profile.icmpCustomMagic)
            // icmpCustomFamily 已从 myssh 152c556 ProxyConfig 删除，spinner 已移除
            spinnerIcmpMtu.setText(profile.icmpCustomMtuMode.ifBlank { "probe" }, false)
            etIcmpMaxPayload.setText(profile.icmpCustomMaxPayload.takeIf { it > 0 }?.toString().orEmpty())
            etIcmpPaceMs.setText(profile.icmpCustomPaceMS.takeIf { it > 0 }?.toString().orEmpty())
            etIcmpIdRange.setText(profile.icmpCustomIdRange)
            val protocolPublicKey = when (profile.tunnelType) {
                Profile.TUNNEL_TYPE_DNS -> profile.dnsTunnelPublicKey
                Profile.TUNNEL_TYPE_UDP_CUSTOM -> profile.udpCustomPublicKey
                Profile.TUNNEL_TYPE_ICMP_CUSTOM -> profile.icmpCustomPublicKey
                else -> ""
            }
            etNoisePublicKey.setText(protocolPublicKey.ifBlank { profile.noisePublicKey })
            etXhttpChunkSize.setText(if (profile.xhttpChunkSizeKB > 0) profile.xhttpChunkSizeKB.toString() else "")
            spinnerXhttpStreamMode.setText(profile.xhttpStreamMode.ifBlank { "auto" }, false)
            etBindInterface.setText(profile.bindInterface)
            etHeartbeatInterval.setText(if (profile.heartbeatIntervalMs > 0) profile.heartbeatIntervalMs.toString() else "")
            // myssh 152c556
            etPaddingMinBytes.setText(if (profile.paddingMinBytes != 0) profile.paddingMinBytes.toString() else "")
            // 旧存档可能残留已废弃的 h3,h2 / h2,h3（曾是 auto 的别名），归一到 auto 再回填
            spinnerMasqueAlpn.setText(Profile.normalizeMasqueAlpn(profile.masqueAlpn), false)
            
            etProxyAddr.setText(profile.proxyAddr)
            etCustomHost.setText(profile.customHost)
            etDnsTunnelServers.setText(profile.dnsTunnelServers)
            etDnsTunnelDomain.setText(profile.dnsTunnelDomain)
            etDnsTunnelPsk.setText(profile.dnsTunnelPsk)
            etDnsTunnelMarker.setText(profile.dnsTunnelMarker)
            
            etServerName.setText(profile.serverName)
            switchEnableCustomPath.isChecked = profile.enableCustomPath
            etCustomPath.setText(profile.customPath)

            switchAuthRequired.isChecked = profile.proxyAuthRequired
            etAuthToken.setText(profile.proxyAuthToken)
            etAuthUser.setText(profile.proxyAuthUser)
            etAuthPass.setText(profile.proxyAuthPass)

            switchVerifySshFingerprint.isChecked = profile.verifyFingerprint
            layoutSshFingerprint.isVisible = profile.verifyFingerprint
            etSshFingerprint.setText(profile.serverFingerprint)

            switchVerifyCertFingerprint.isChecked = profile.verifyCertFingerprint
            layoutCertFingerprint.isVisible = profile.verifyCertFingerprint
            etCertFingerprint.setText(profile.serverCertFingerprint)

            binding.switchTunnelTls.isChecked = profile.tunnelTlsEnabled
            // alpn 仅 xhttp 消费（docs §5）；其他类型显示留空
            spinnerAlpn.setText(if (profile.tunnelType == Profile.TUNNEL_TYPE_XHTTP) profile.alpn else "", false)

            switchDnsOverride.isChecked = profile.dnsOverride
            layoutDnsOverride.isVisible = profile.dnsOverride
            etRemoteDns.setText(profile.remoteDns)
            etLocalDns.setText(profile.localDns)
            spinnerUdpgwVersion.setText(profile.udpgwVersion, false)
            etUdpgwAddr.setText(profile.udpgwAddr)
            etGeositeDirect.setText(profile.geositeDirect)
            etGeoipDirect.setText(profile.geoipDirect)

            switchAppFilterOverride.isChecked = profile.appFilterOverride
            layoutAppFilterOverride.isVisible = profile.appFilterOverride
            spinnerFilterMode.setText(if (profile.filterMode == 1) 
                getString(CoreR.string.filter_allow_mode) else getString(CoreR.string.filter_disallow_mode), false)
            etFilterApps.setText(profile.filterApps)

            updateUIBasedOnSettings()
            setupAdapters()
        }
        bindingInProgress = false
    }

    private fun setupAdapters() {
        binding.apply {
            // Geo direct tags：点击放大镜图标弹出搜索多选器，确认后回写逗号分隔串。
            // 输入框本身仍可手动编辑，两种方式互通。
            layoutGeositeDirect.setEndIconOnClickListener {
                openGeoTagPicker(GeoTagsPickerBottomSheet.TagKind.SITE, etGeositeDirect)
            }
            layoutGeoipDirect.setEndIconOnClickListener {
                openGeoTagPicker(GeoTagsPickerBottomSheet.TagKind.IP, etGeoipDirect)
            }
            spinnerTunnelType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, Profile.getAllTunnelTypes()))
            spinnerDnsRecordType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, dnsRecordTypes))
            spinnerXhttpStreamMode.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, xhttpStreamModeOptions))
            spinnerMasqueAlpn.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, masqueAlpnOptions))
            spinnerIcmpMtu.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, icmpMtuModeOptions))
            spinnerAuthType.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, authTypes.map {
                if (it == Profile.AUTH_TYPE_PASSWORD) getString(CoreR.string.auth_password) else getString(CoreR.string.auth_key)
            }))
            spinnerFilterMode.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, filterModes))
            spinnerUdpgwVersion.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, udpgwVersions))
            
            spinnerAlpn.setAdapter(ArrayAdapter(this@ProfileEditActivity, android.R.layout.simple_dropdown_item_1line, alpnOptionsXhttp))
        }
    }

    private fun ensureKcpOptions(): ViewProfileKcpOptionsBinding {
        kcpOptionsBinding?.let { return it }
        return ViewProfileKcpOptionsBinding.bind(binding.stubKcpContainer.inflate()).also { options ->
            kcpOptionsBinding = options
            options.spinnerKcpCrypt.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, kcpCryptOptions)
            )
            options.spinnerKcpMode.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, kcpModeOptions)
            )
            options.etKcpPassword.setText(currentProfile.kcpPassword)
            options.spinnerKcpCrypt.setText(currentProfile.kcpCrypt.ifBlank { "none" }, false)
            options.etKcpDataShards.setText(currentProfile.kcpDataShards.takeIf { it > 0 }?.toString().orEmpty())
            options.etKcpParityShards.setText(currentProfile.kcpParityShards.takeIf { it > 0 }?.toString().orEmpty())
            options.spinnerKcpMode.setText(currentProfile.kcpMode.ifBlank { "fast" }, false)
            options.etKcpSndwnd.setText(currentProfile.kcpSndWnd.takeIf { it > 0 }?.toString().orEmpty())
            options.etKcpRcvwnd.setText(currentProfile.kcpRcvWnd.takeIf { it > 0 }?.toString().orEmpty())
            options.etKcpMtu.setText(currentProfile.kcpMtu.takeIf { it > 0 }?.toString().orEmpty())
            options.switchKcpNocomp.isChecked = currentProfile.kcpNoComp
            options.etKcpSmuxver.setText(currentProfile.kcpSmuxVer.takeIf { it > 0 }?.toString().orEmpty())
            options.etKcpKeepalive.setText(currentProfile.kcpKeepAlive.takeIf { it > 0 }?.toString().orEmpty())
            liveWatch(options.etKcpPassword, FieldKey.KCP_PASSWORD)
            liveWatch(options.etKcpSndwnd, FieldKey.KCP_SNDWND)
            liveWatch(options.etKcpRcvwnd, FieldKey.KCP_RCVWND)
            liveWatch(options.etKcpMtu, FieldKey.KCP_MTU)
            liveWatch(options.etKcpSmuxver, FieldKey.KCP_SMUXVER)
            liveWatch(options.etKcpKeepalive, FieldKey.KCP_KEEPALIVE)
            liveWatch(options.etKcpDataShards, FieldKey.KCP_DATA_SHARDS)
            liveWatch(options.etKcpParityShards, FieldKey.KCP_PARITY_SHARDS)
        }
    }

    private fun ensureUdpCustomOptions(): ViewProfileUdpCustomOptionsBinding {
        udpCustomOptionsBinding?.let { return it }
        return ViewProfileUdpCustomOptionsBinding.bind(binding.stubUdpCustomContainer.inflate()).also { options ->
            udpCustomOptionsBinding = options
            options.etUdpCustomPsk.setText(currentProfile.udpCustomPsk)
            options.etUdpCustomMagic.setText(currentProfile.udpCustomMagic.ifBlank { "UDPC" })
            options.etUdpCustomPaths.setText(currentProfile.udpCustomPaths.takeIf { it > 0 }?.toString().orEmpty())
            options.etUdpCustomSockets.setText(currentProfile.udpCustomSockets.takeIf { it > 0 }?.toString().orEmpty())
            options.etUdpCustomSendWindow.setText(currentProfile.udpCustomSendWindow.takeIf { it > 0 }?.toString().orEmpty())
            // myssh 152c556：路径 MTU 探测
            options.etUdpCustomMaxPkt.setText(currentProfile.udpCustomMaxPkt.takeIf { it > 0 }?.toString().orEmpty())
            options.spinnerUdpCustomMtuProbe.setAdapter(
                ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, udpMtuProbeOptions)
            )
            options.spinnerUdpCustomMtuProbe.setText(currentProfile.udpCustomMtuProbe.ifBlank { "auto" }, false)
            liveWatch(options.etUdpCustomPsk, FieldKey.UDP_PSK)
            liveWatch(options.etUdpCustomMagic, FieldKey.UDP_MAGIC)
            liveWatch(options.etUdpCustomMaxPkt, FieldKey.UDP_MAX_PKT)
            liveWatch(options.spinnerUdpCustomMtuProbe, FieldKey.UDP_MTU_PROBE)
        }
    }

    private fun openGeoTagPicker(kind: GeoTagsPickerBottomSheet.TagKind, target: com.google.android.material.textfield.TextInputEditText) {
        val current = target.text?.toString()?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        geoTagPickerTarget = target
        GeoTagsPickerBottomSheet.newInstance(kind, current).show(supportFragmentManager, "GeoTagsPicker")
    }

    override fun onTagsConfirmed(kind: GeoTagsPickerBottomSheet.TagKind, selected: List<String>) {
        // Fragment 旋转恢复后从 parentFragment/activity 解析本接口回传
        val target = when (kind) {
            GeoTagsPickerBottomSheet.TagKind.SITE -> geoTagPickerTarget ?: binding.etGeositeDirect
            GeoTagsPickerBottomSheet.TagKind.IP -> geoTagPickerTarget ?: binding.etGeoipDirect
        }
        target.setText(selected.joinToString(","))
    }

    private fun updateUIBasedOnSettings() {
        val selected = binding.spinnerTunnelType.text.toString()
        // 字段显隐的唯一来源：TunnelFieldSpecs（myssh docs/transports.md 的字段消费矩阵）。
        // 过去各处手写 when 导致"字段该显示没显示"的漂移，勿再新增散落的类型判断。
        val spec = TunnelFieldSpecs.forType(selected)
        val isMasque = selected == Profile.TUNNEL_TYPE_MASQUE
        val tlsActive = spec.tlsActive(binding.switchTunnelTls.isChecked)

        kcpOptionsBinding?.root?.isVisible = false
        udpCustomOptionsBinding?.root?.isVisible = false
        if (spec.kcpOptions) ensureKcpOptions().root.isVisible = true
        if (spec.udpOptions) ensureUdpCustomOptions().root.isVisible = true

        binding.apply {
            switchTunnelTls.isVisible = spec.tlsCapable
            layoutHttpPayload.isVisible = spec.httpPayload
            // raw 关 TLS → myssh 直连 ssh_addr（纯 SSH，无隧道），proxy_addr 不再使用 → 隐藏
            layoutProxyAddr.isVisible = spec.proxyAddr != ProxyAddrMode.HIDDEN &&
                (!spec.proxyAddrWhenTlsOnly || tlsActive)
            layoutCustomHost.isVisible = spec.customHost
            layoutServerName.isVisible = tlsActive

            layoutDnsTunnelServers.isVisible = spec.dnsOptions
            layoutDnsTunnelDomain.isVisible = spec.dnsOptions
            layoutDnsRecordType.isVisible = spec.dnsOptions
            switchDnsTunnelEdns0.isVisible = spec.dnsOptions
            layoutDnsTunnelPsk.isVisible = spec.dnsOptions
            layoutDnsTunnelMarker.isVisible = spec.dnsOptions

            layoutNoisePublicKey.isVisible = spec.noisePublicKey
            layoutXhttpChunkSize.isVisible = spec.xhttpOptions
            layoutXhttpStreamMode.isVisible = spec.xhttpOptions
            layoutIcmpCustomContainer.isVisible = spec.icmpOptions
            layoutHeartbeatInterval.isVisible = spec.heartbeat
            // h2tunnel 出站帧最小填充（流量混淆）：h2 家族 + masque 才消费
            layoutPaddingMinBytes.isVisible = spec.paddingOptions
            // masque 独有 ALPN 选择
            layoutMasqueAlpn.isVisible = spec.masqueAlpnOptions

            // udp_custom 支持目标端口范围；icmp_custom 对端是纯 IP（ICMP 无端口概念，docs §4.11）
            layoutProxyAddr.hint = when (spec.proxyAddr) {
                ProxyAddrMode.BARE_HOST -> getString(CoreR.string.proxy_addr_icmp_hint)
                else -> getString(CoreR.string.proxy_address)
            }
            layoutProxyAddr.helperText = when (spec.proxyAddr) {
                ProxyAddrMode.HOST_PORT_RANGE -> getString(CoreR.string.proxy_addr_udp_custom_hint)
                else -> null
            }

            switchEnableCustomPath.isVisible = isMasque
            layoutCustomPath.isVisible = spec.customPath && (!isMasque || switchEnableCustomPath.isChecked)

            // ALPN：仅 xhttp 消费该字段（docs §5）；raw/ws/quic 的 ALPN 均固定不可配
            layoutAlpn.isVisible = spec.alpn

            switchDisableStatusCheck.isVisible = spec.disableStatusCheck
            // 代理鉴权与 TLS 无关：tunnel_ws.go / tunnel_h2.go 的凭据注入在公共路径上
            switchAuthRequired.isVisible = spec.proxyAuth != ProxyAuthMode.NONE

            updateProxyAuthVisibility()

            layoutRowVerifyCertFingerprint.isVisible = tlsActive
            layoutCertFingerprint.isVisible = tlsActive && switchVerifyCertFingerprint.isChecked
        }
        if (!bindingInProgress) applyLiveErrors(null)
    }

    private fun updateProxyAuthVisibility() {
        val spec = TunnelFieldSpecs.forType(binding.spinnerTunnelType.text.toString())
        val isEnabled = binding.switchAuthRequired.isVisible && binding.switchAuthRequired.isChecked

        binding.layoutAuthToken.isVisible = isEnabled && spec.proxyAuth == ProxyAuthMode.BEARER
        binding.layoutAuthUser.isVisible = isEnabled && spec.proxyAuth == ProxyAuthMode.USER_PASS
        binding.layoutAuthPass.isVisible = isEnabled && spec.proxyAuth == ProxyAuthMode.USER_PASS
    }

    private fun updateAuthTypeVisibility(authType: String) {
        val isKey = authType == Profile.AUTH_TYPE_PRIVATEKEY
        binding.apply {
            layoutPass.isVisible = !isKey
            layoutPrivateKey.isVisible = isKey
            layoutKeyPass.isVisible = isKey
        }
    }

    // ── 实时校验管线（与保存共用 FieldRules.compute，规则只写一遍） ──────────
    private var bindingInProgress = true
    private val touchedKeys = mutableSetOf<FieldKey>()

    private fun liveWatch(editText: android.widget.EditText?, key: FieldKey) {
        editText?.doAfterTextChanged {
            if (bindingInProgress) return@doAfterTextChanged
            touchedKeys += key
            applyLiveErrors(setOf(key))
        }
    }

    /** 规则字段 → TextInputLayout（选项组未 inflate 时返回 null，跳过该字段） */
    private fun layoutFor(key: FieldKey): com.google.android.material.textfield.TextInputLayout? = when (key) {
        FieldKey.SSH_ADDR -> binding.layoutSshAddr
        FieldKey.PROXY_ADDR -> binding.layoutProxyAddr
        FieldKey.SERVER_NAME -> binding.layoutServerName
        FieldKey.CUSTOM_PATH -> binding.layoutCustomPath
        FieldKey.DNS_SERVERS -> binding.layoutDnsTunnelServers
        FieldKey.DNS_DOMAIN -> binding.layoutDnsTunnelDomain
        FieldKey.DNS_RECORD_TYPE -> binding.layoutDnsRecordType
        FieldKey.DNS_PSK -> binding.layoutDnsTunnelPsk
        FieldKey.DNS_MARKER -> binding.layoutDnsTunnelMarker
        FieldKey.NOISE_KEY -> binding.layoutNoisePublicKey
        FieldKey.HTTP_PAYLOAD -> binding.layoutHttpPayload
        FieldKey.CHUNK_SIZE -> binding.layoutXhttpChunkSize
        FieldKey.HEARTBEAT -> binding.layoutHeartbeatInterval
        FieldKey.AUTH_USER -> binding.layoutAuthUser
        FieldKey.AUTH_PASS -> binding.layoutAuthPass
        FieldKey.AUTH_TOKEN -> binding.layoutAuthToken
        FieldKey.SSH_FINGERPRINT -> binding.layoutSshFingerprint
        FieldKey.CERT_FINGERPRINT -> binding.layoutCertFingerprint
        FieldKey.PADDING_MIN_BYTES -> binding.layoutPaddingMinBytes
        FieldKey.MASQUE_ALPN -> binding.layoutMasqueAlpn
        FieldKey.ICMP_PSK -> binding.layoutIcmpPsk
        FieldKey.ICMP_MAGIC -> binding.layoutIcmpMagic
        FieldKey.UDP_PSK -> udpCustomOptionsBinding?.layoutUdpCustomPsk
        FieldKey.UDP_MAGIC -> udpCustomOptionsBinding?.layoutUdpCustomMagic
        FieldKey.UDP_MAX_PKT -> udpCustomOptionsBinding?.layoutUdpCustomMaxPkt
        FieldKey.UDP_MTU_PROBE -> udpCustomOptionsBinding?.layoutUdpCustomMtuProbe
        FieldKey.KCP_PASSWORD -> kcpOptionsBinding?.layoutKcpPassword
        FieldKey.KCP_SNDWND -> kcpOptionsBinding?.layoutKcpSndwnd
        FieldKey.KCP_RCVWND -> kcpOptionsBinding?.layoutKcpRcvwnd
        FieldKey.KCP_MTU -> kcpOptionsBinding?.layoutKcpMtu
        FieldKey.KCP_SMUXVER -> kcpOptionsBinding?.layoutKcpSmuxver
        FieldKey.KCP_KEEPALIVE -> kcpOptionsBinding?.layoutKcpKeepalive
        FieldKey.KCP_DATA_SHARDS -> kcpOptionsBinding?.layoutKcpDataShards
        FieldKey.KCP_PARITY_SHARDS -> kcpOptionsBinding?.layoutKcpParityShards
    }

    /** keys=null → 刷新全部已 touched 字段；否则只刷新交集（隐藏字段顺带清错误）。 */
    private fun applyLiveErrors(keys: Set<FieldKey>?) {
        val verdict = FieldRules.compute(buildInputs())
        val targets = (keys ?: touchedKeys) + FieldKey.values().filter { layoutFor(it)?.error != null }.toSet()
        for (key in targets) {
            val layout = layoutFor(key) ?: continue
            if (key !in touchedKeys && verdict.errors[key] == null) continue
            layout.error = if (layout.isVisible) verdict.errors[key]?.let { getString(it) } else null
        }
    }

    private fun buildInputs(): FieldInputs {
        val b = binding
        val spec = TunnelFieldSpecs.forType(b.spinnerTunnelType.text.toString())
        val kcp = kcpOptionsBinding
        val udp = udpCustomOptionsBinding
        return FieldInputs(
            spec = spec,
            tlsActive = spec.tlsActive(b.switchTunnelTls.isChecked),
            sshAddr = b.etSshAddr.text.toString().trim(),
            proxyAddr = b.etProxyAddr.text.toString().trim(),
            serverName = b.etServerName.text.toString().trim(),
            customPath = b.etCustomPath.text.toString().trim(),
            dnsServers = b.etDnsTunnelServers.text.toString().trim(),
            dnsDomain = b.etDnsTunnelDomain.text.toString().trim(),
            dnsRecordType = b.spinnerDnsRecordType.text.toString(),
            dnsPsk = b.etDnsTunnelPsk.text.toString().trim(),
            dnsMarker = b.etDnsTunnelMarker.text.toString().trim(),
            noisePublicKey = b.etNoisePublicKey.text.toString().trim(),
            udpPsk = udp?.etUdpCustomPsk?.text?.toString()?.trim() ?: currentProfile.udpCustomPsk,
            udpMagic = udp?.etUdpCustomMagic?.text?.toString()?.trim() ?: currentProfile.udpCustomMagic,
            udpMaxPkt = udp?.etUdpCustomMaxPkt?.text?.toString()?.trim().orEmpty(),
            udpMtuProbe = udp?.spinnerUdpCustomMtuProbe?.text?.toString()?.trim().orEmpty(),
            icmpPsk = b.etIcmpPsk.text.toString().trim(),
            icmpMagic = b.etIcmpMagic.text.toString().trim(),
            kcpPassword = kcp?.etKcpPassword?.text?.toString()?.trim() ?: currentProfile.kcpPassword,
            kcpSndwnd = kcp?.etKcpSndwnd?.text?.toString()?.trim().orEmpty(),
            kcpRcvwnd = kcp?.etKcpRcvwnd?.text?.toString()?.trim().orEmpty(),
            kcpMtu = kcp?.etKcpMtu?.text?.toString()?.trim().orEmpty(),
            kcpSmuxver = kcp?.etKcpSmuxver?.text?.toString()?.trim().orEmpty(),
            kcpKeepalive = kcp?.etKcpKeepalive?.text?.toString()?.trim().orEmpty(),
            kcpDataShards = kcp?.etKcpDataShards?.text?.toString()?.trim().orEmpty(),
            kcpParityShards = kcp?.etKcpParityShards?.text?.toString()?.trim().orEmpty(),
            httpPayload = b.etHttpPayload.text.toString().trim(),
            authEnabled = b.switchAuthRequired.isChecked,
            authUser = b.etAuthUser.text.toString().trim(),
            authPass = b.etAuthPass.text.toString().trim(),
            authToken = b.etAuthToken.text.toString().trim(),
            verifySshFp = b.switchVerifySshFingerprint.isChecked,
            sshFingerprint = b.etSshFingerprint.text.toString().trim(),
            verifyCertFp = b.switchVerifyCertFingerprint.isChecked,
            certFingerprint = b.etCertFingerprint.text.toString().trim(),
            chunkSize = b.etXhttpChunkSize.text.toString().trim(),
            heartbeat = b.etHeartbeatInterval.text.toString().trim(),
            paddingMinBytes = b.etPaddingMinBytes.text.toString().trim(),
            masqueAlpn = b.spinnerMasqueAlpn.text.toString().trim(),
            sshPassFallbackAvailable = b.etPass.text.toString().isNotEmpty() || currentProfile.pass.isNotEmpty()
        )
    }

    private fun validateAndSave() {
        // 保存校验 = 实时校验同一份 FieldRules（全量应用，不筛 touched）
        val inputs = buildInputs()
        val verdict = FieldRules.compute(inputs)
        val selectedTunnel = binding.spinnerTunnelType.text.toString()
        val isDns = inputs.spec.dnsOptions
        val isUdpCustom = inputs.spec.udpOptions
        val isMasque = selectedTunnel == Profile.TUNNEL_TYPE_MASQUE
        val publicKey = binding.etNoisePublicKey.text.toString().trim()

        var firstErrorView: View? = null

        fun setError(layout: com.google.android.material.textfield.TextInputLayout, errorRes: Int) {
            layout.error = getString(errorRes)
            if (firstErrorView == null) firstErrorView = layout.editText ?: layout
        }

        for (key in FieldKey.values()) {
            val layout = layoutFor(key) ?: continue
            val res = verdict.errors[key]
            if (res != null) setError(layout, res) else layout.error = null
        }

        // SSH 凭据校验涉及原生调用（checkIfKeyEncrypted/validatePassphrase），在规则层之外
        val isKeyAuth = binding.spinnerAuthType.text.toString() == getString(CoreR.string.auth_key)
        if (isKeyAuth) {
            val privateKey = binding.etPrivateKey.text.toString()
            when {
                privateKey.isBlank() -> setError(binding.layoutPrivateKey, CoreR.string.error_field_required)
                !privateKey.contains("BEGIN") || !privateKey.contains("PRIVATE KEY") ->
                    setError(binding.layoutPrivateKey, CoreR.string.error_invalid_private_key)
                else -> {
                    val checkResult = myssh.Myssh.checkIfKeyEncrypted(privateKey)
                    if (checkResult == 1L) {
                        val inputPass = binding.etKeyPass.text.toString()
                        val actualPass = inputPass.ifEmpty { currentProfile.keyPass }
                        if (actualPass.isEmpty() || !myssh.Myssh.validatePassphrase(privateKey, actualPass)) {
                            setError(binding.layoutKeyPass, CoreR.string.error_invalid_key_password)
                        }
                    } else if (checkResult == 2L) {
                        setError(binding.layoutPrivateKey, CoreR.string.error_invalid_private_key)
                    }
                }
            }
        } else if (binding.etPass.text.toString().isBlank() && currentProfile.pass.isEmpty()) {
            setError(binding.layoutPass, CoreR.string.error_field_required)
        }

        firstErrorView?.let { errorView ->
            errorView.requestFocus()
            scrollToFocusedView(errorView)
            return
        }

        // myssh 仅告警不阻断的项（短 PSK、xhttp 无指纹）：保存放行后以 Toast 呈现
        val warningMsg = verdict.warnings.values.firstOrNull()?.let { getString(it) }

        if (isSaveInProgress) return

        // 4. Save
        val isEdit = profileId != null
        val kcpOptions = kcpOptionsBinding
        val udpOptions = udpCustomOptionsBinding
        val updatedProfile = currentProfile.copy(
            name = binding.etName.text.toString().trim().ifBlank { getString(CoreR.string.node_new_name) },
            sshAddr = binding.etSshAddr.text.toString().trim(),
            note = binding.etNote.text.toString().trim(),
            user = binding.etUser.text.toString().trim(),
            authType = if (isKeyAuth) Profile.AUTH_TYPE_PRIVATEKEY else Profile.AUTH_TYPE_PASSWORD,
            pass = binding.etPass.text.toString().ifEmpty { currentProfile.pass },
            privateKey = binding.etPrivateKey.text.toString(),
            keyPass = if (binding.etKeyPass.text.toString().isNotEmpty()) binding.etKeyPass.text.toString() else currentProfile.keyPass,
            tunnelType = binding.spinnerTunnelType.text.toString(),
            tunnelTlsEnabled = binding.switchTunnelTls.isVisible && binding.switchTunnelTls.isChecked,
            httpPayload = binding.etHttpPayload.text.toString(),
            disableStatusCheck = binding.switchDisableStatusCheck.isChecked,
            proxyAddr = binding.etProxyAddr.text.toString().trim(),
            customHost = binding.etCustomHost.text.toString().trim(),
            dnsTunnelDomain = binding.etDnsTunnelDomain.text.toString().trim(),
            dnsTunnelServers = binding.etDnsTunnelServers.text.toString().trim(),
            dnsTunnelType = if (isDns) binding.spinnerDnsRecordType.text.toString().ifBlank { "txt" } else currentProfile.dnsTunnelType,
            dnsTunnelPublicKey = if (isDns) publicKey else currentProfile.dnsTunnelPublicKey,
            dnsTunnelEDNS0 = if (isDns) binding.switchDnsTunnelEdns0.isChecked else currentProfile.dnsTunnelEDNS0,
            dnsTunnelPsk = if (isDns) binding.etDnsTunnelPsk.text.toString().trim() else currentProfile.dnsTunnelPsk,
            dnsTunnelMarker = if (isDns) binding.etDnsTunnelMarker.text.toString().trim() else currentProfile.dnsTunnelMarker,
            kcpPassword = kcpOptions?.etKcpPassword?.text?.toString() ?: currentProfile.kcpPassword,
            kcpCrypt = kcpOptions?.spinnerKcpCrypt?.text?.toString()?.ifBlank { "none" } ?: currentProfile.kcpCrypt,
            kcpMode = kcpOptions?.spinnerKcpMode?.text?.toString() ?: currentProfile.kcpMode,
            kcpSndWnd = kcpOptions?.etKcpSndwnd?.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: currentProfile.kcpSndWnd,
            kcpRcvWnd = kcpOptions?.etKcpRcvwnd?.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: currentProfile.kcpRcvWnd,
            kcpMtu = kcpOptions?.etKcpMtu?.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: currentProfile.kcpMtu,
            kcpNoComp = kcpOptions?.switchKcpNocomp?.isChecked ?: currentProfile.kcpNoComp,
            kcpSmuxVer = kcpOptions?.etKcpSmuxver?.text?.toString()?.toIntOrNull() ?: currentProfile.kcpSmuxVer,
            kcpKeepAlive = kcpOptions?.etKcpKeepalive?.text?.toString()?.toIntOrNull()?.coerceAtLeast(0) ?: currentProfile.kcpKeepAlive,
            kcpDataShards = kcpOptions?.let {
                it.etKcpDataShards.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: currentProfile.kcpDataShards,
            kcpParityShards = kcpOptions?.let {
                it.etKcpParityShards.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: currentProfile.kcpParityShards,
            udpCustomPsk = udpOptions?.etUdpCustomPsk?.text?.toString() ?: currentProfile.udpCustomPsk,
            udpCustomMagic = udpOptions?.etUdpCustomMagic?.text?.toString()?.ifBlank { "UDPC" }
                ?: currentProfile.udpCustomMagic,
            udpCustomPublicKey = if (isUdpCustom) publicKey else currentProfile.udpCustomPublicKey,
            icmpCustomPublicKey = if (selectedTunnel == Profile.TUNNEL_TYPE_ICMP_CUSTOM) publicKey else currentProfile.icmpCustomPublicKey,
            udpCustomPaths = udpOptions?.let {
                it.etUdpCustomPaths.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: currentProfile.udpCustomPaths,
            udpCustomSockets = udpOptions?.let {
                it.etUdpCustomSockets.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: currentProfile.udpCustomSockets,
            udpCustomSendWindow = udpOptions?.let {
                it.etUdpCustomSendWindow.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: currentProfile.udpCustomSendWindow,
            udpCustomMaxPkt = udpOptions?.let {
                it.etUdpCustomMaxPkt.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0
            } ?: currentProfile.udpCustomMaxPkt,
            udpCustomMtuProbe = udpOptions?.let {
                it.spinnerUdpCustomMtuProbe.text.toString().takeIf { it.isNotBlank() } ?: ""
            } ?: currentProfile.udpCustomMtuProbe,
            icmpCustomPsk = binding.etIcmpPsk.text.toString(),
            icmpCustomMagic = binding.etIcmpMagic.text.toString().trim(),
            // icmpCustomFamily 已从 myssh 152c556 删除（handler 不再读取），默认空串
            icmpCustomMtuMode = binding.spinnerIcmpMtu.text.toString(),
            icmpCustomMaxPayload = binding.etIcmpMaxPayload.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            icmpCustomPaceMS = binding.etIcmpPaceMs.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            icmpCustomIdRange = binding.etIcmpIdRange.text.toString().trim(),
            xhttpChunkSizeKB = binding.etXhttpChunkSize.text.toString().toIntOrNull()?.let { if (it > 0) it else 0 } ?: 0,
            // Dropdown carries the raw values ("auto"/"stream"/"poll"); unknown text falls back to auto
            xhttpStreamMode = binding.spinnerXhttpStreamMode.text.toString().takeIf { it in xhttpStreamModeOptions } ?: "auto",
            // Hidden on Android because the unprivileged myssh process cannot
            // use SO_BINDTODEVICE. Preserve imported/remote values verbatim.
            bindInterface = currentProfile.bindInterface,
            heartbeatIntervalMs = binding.etHeartbeatInterval.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 0,
            // myssh 152c556：h2tunnel 出站帧最小填充（负数=关闭，0=默认1420）
            paddingMinBytes = binding.etPaddingMinBytes.text.toString().toIntOrNull() ?: 0,
            // myssh 152c556：masque 独有 ALPN（"" = SDK 默认自动）
            masqueAlpn = binding.spinnerMasqueAlpn.text.toString().takeIf { it.isNotBlank() }.orEmpty(),
            serverName = binding.etServerName.text.toString().trim(),
            // Only MASQUE has an SDK default path that can optionally be overridden.
            // All other path-based tunnels always consume customPath directly.
            enableCustomPath = isMasque && binding.switchEnableCustomPath.isChecked,
            customPath = binding.etCustomPath.text.toString(),
            dnsOverride = binding.switchDnsOverride.isChecked,
            remoteDns = binding.etRemoteDns.text.toString(),
            localDns = binding.etLocalDns.text.toString(),
            udpgwVersion = binding.spinnerUdpgwVersion.text.toString(),
            udpgwAddr = binding.etUdpgwAddr.text.toString(),
            geositeDirect = binding.etGeositeDirect.text.toString(),
            geoipDirect = binding.etGeoipDirect.text.toString(),
            appFilterOverride = binding.switchAppFilterOverride.isChecked,
            filterMode = if (binding.spinnerFilterMode.text.toString() == getString(CoreR.string.filter_allow_mode)) 1 else 0,
            filterApps = binding.etFilterApps.text.toString(),
            verifyFingerprint = binding.switchVerifySshFingerprint.isChecked,
            serverFingerprint = binding.etSshFingerprint.text.toString(),
            verifyCertFingerprint = binding.switchVerifyCertFingerprint.isChecked,
            serverCertFingerprint = binding.etCertFingerprint.text.toString(),
            // ALPN 仅 xhttp 消费（docs §5）；其他类型恒清空，避免残留误导值
            alpn = if (selectedTunnel == Profile.TUNNEL_TYPE_XHTTP) binding.spinnerAlpn.text.toString().trim() else "",
            proxyAuthRequired = binding.switchAuthRequired.isChecked,
            proxyAuthToken = binding.etAuthToken.text.toString(),
            proxyAuthUser = binding.etAuthUser.text.toString(),
            proxyAuthPass = binding.etAuthPass.text.toString()
        )

        isSaveInProgress = true
        binding.btnSave.isEnabled = false
        viewModel.saveProfile(updatedProfile, isEdit)
        warningMsg?.let { Toast.makeText(this, "\u26A0 $it", Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        globalFocusChangeListener?.let {
            binding.scrollView.viewTreeObserver.removeOnGlobalFocusChangeListener(it)
        }
        globalFocusChangeListener = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        confirmDiscardOrFinish()
        return true
    }

    private fun confirmDiscardOrFinish() {
        if (isSaveInProgress) return
        if (hasUnsavedChanges()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(CoreR.string.unsaved_changes_title)
                .setMessage(CoreR.string.unsaved_changes_msg)
                .setPositiveButton(CoreR.string.ok) { _, _ -> finish() }
                .setNegativeButton(CoreR.string.cancel, null)
                .show()
        } else {
            finish()
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

    private fun showSSHDetailsDialog(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val addr = json.optString("address")
        val banner = json.optString("banner").ifBlank { getString(CoreR.string.not_available) }
        val keyType = json.optString("key_type")
        val fpSha256 = json.optString("fingerprint_sha256")
        val fpMd5 = json.optString("fingerprint_md5")
        val latencyMs = json.optLong("latency_ms")

        val sb = StringBuilder().apply {
            append("🌐 ${getString(CoreR.string.info_target_address)}: $addr\n")
            append("🏷️ ${getString(CoreR.string.info_server_banner)}: $banner\n")
            append("🔑 ${getString(CoreR.string.info_public_key_type)}: $keyType\n")
            append("⚡ ${getString(CoreR.string.info_handshake_latency)}: ${getString(CoreR.string.latency_format, latencyMs.toInt())}\n\n")
            append("🛡️ ${getString(CoreR.string.info_sha256_fingerprint)}:\n$fpSha256\n\n")
            append("🔒 ${getString(CoreR.string.info_md5_fingerprint)}:\n$fpMd5")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.ssh_server_details_title))
            .setMessage(sb.toString())
            .setPositiveButton(getString(CoreR.string.apply_fingerprint)) { _, _ ->
                binding.etSshFingerprint.setText(fpSha256)
                binding.switchVerifySshFingerprint.isChecked = true
                binding.layoutSshFingerprint.isVisible = true
                Toast.makeText(this, getString(CoreR.string.ssh_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(CoreR.string.copy_details)) { _, _ ->
                copyToClipboard(getString(CoreR.string.ssh_server_details_title), sb.toString())
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTLSDetailsDialog(jsonStr: String) {
        val json = JSONObject(jsonStr)
        val target = json.optString("target")
        val sni = json.optString("sni")
        val subject = json.optString("subject").ifBlank { getString(CoreR.string.not_available) }
        val issuer = json.optString("issuer").ifBlank { getString(CoreR.string.not_available) }
        val daysRemaining = json.optInt("days_remaining")
        val isExpired = json.optBoolean("is_expired")
        val sigAlg = json.optString("signature_algorithm")
        val pubKeyAlg = json.optString("public_key_algorithm")
        val fpSha256 = json.optString("fingerprint_sha256")
        val tlsVer = json.optString("tls_version")
        val proto = json.optString("negotiated_protocol").ifBlank { getString(CoreR.string.not_available) }
        val latencyMs = json.optLong("latency_ms")

        val dnsNamesArray = json.optJSONArray("dns_names")
        val sans = if (dnsNamesArray != null && dnsNamesArray.length() > 0) {
            val list = mutableListOf<String>()
            for (i in 0 until dnsNamesArray.length()) list.add(dnsNamesArray.getString(i))
            list.joinToString(", ")
        } else getString(CoreR.string.not_available)

        val expireStatus = if (isExpired) getString(CoreR.string.info_cert_expired) else getString(CoreR.string.info_days_remaining, daysRemaining)

        val sb = StringBuilder().apply {
            append("🌐 ${getString(CoreR.string.info_target_endpoint)}: $target\n")
            append("🏷️ ${getString(CoreR.string.info_sni_domain)}: $sni\n")
            append("🔒 ${getString(CoreR.string.info_protocol_negotiation)}: ${getString(CoreR.string.info_protocol_negotiation_format, tlsVer, proto)}\n")
            append("⚡ ${getString(CoreR.string.info_handshake_latency)}: ${getString(CoreR.string.latency_format, latencyMs.toInt())}\n\n")
            append("📜 ${getString(CoreR.string.info_subject)}:\n$subject\n\n")
            append("🏢 ${getString(CoreR.string.info_issuer)}:\n$issuer\n\n")
            append("📅 ${getString(CoreR.string.info_validity_status)}: $expireStatus\n")
            append("🌐 ${getString(CoreR.string.info_sans)}: $sans\n")
            append("🔐 ${getString(CoreR.string.info_algorithm)}: $pubKeyAlg / $sigAlg\n\n")
            append("🛡️ ${getString(CoreR.string.info_cert_sha256_fingerprint)}:\n$fpSha256")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(CoreR.string.tls_cert_details_title))
            .setMessage(sb.toString())
            .setPositiveButton(getString(CoreR.string.apply_fingerprint)) { _, _ ->
                binding.etCertFingerprint.setText(fpSha256)
                binding.switchVerifyCertFingerprint.isChecked = true
                binding.layoutCertFingerprint.isVisible = true
                Toast.makeText(this, getString(CoreR.string.cert_fingerprint_fetch_success), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(CoreR.string.copy_details)) { _, _ ->
                copyToClipboard(getString(CoreR.string.tls_cert_details_title), sb.toString())
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(this, getString(CoreR.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            val v = currentFocus
            if (v is android.widget.EditText) {
                val outRect = android.graphics.Rect()
                v.getGlobalVisibleRect(outRect)
                if (!outRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    v.clearFocus()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
