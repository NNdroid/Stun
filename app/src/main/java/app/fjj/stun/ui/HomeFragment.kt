package app.fjj.stun.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.BottomSheetConnectionDetailsBinding
import app.fjj.stun.databinding.FragmentHomeBinding
import app.fjj.stun.geo.*
import app.fjj.stun.repo.*
import app.fjj.stun.ui.view.GlobeView

import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.service.VpnControls
import app.fjj.stun.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.sidesheet.SideSheetDialog
import com.google.android.material.snackbar.Snackbar
import com.google.gson.Gson
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/** 底栏速率图表的槽位数：图表只有 74dp 宽，8 根柱子才够粗（对齐设计稿）。 */
private const val DOCK_CHART_SLOTS = 8

/**
 * 连接详情面板速率图表的槽位数。面板图带坐标轴，横轴刻度直接由槽位数推导
 * （slots / slots·2/3 / slots/3 / 0 秒），所以这个值同时决定了刻度文字——
 * 15 会画出「15s / 10s / 5s / 0」，与设计稿一致。
 */
private const val DETAIL_CHART_SLOTS = 15

/** 缺值占位符。底栏出口行与详情面板共用同一个符号，避免两处各写一份字面量。 */
private const val EMPTY_VALUE = "—"

/**
 * 拓扑地球的刷新周期。活跃连接会随时建立/关闭，弧上的脉冲快慢也跟速率走，
 * 所以面板开着时要持续刷新；域名解析有进程内缓存，第二轮起只是几十次 mmap 查询，很便宜。
 */
private const val GLOBE_REFRESH_MS = 2_000L

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ProfileAdapter

    private var isVpnRunning = false
    private var isStopping = false
    private var startRequestInProgress = false
    private var latencyTestJob: kotlinx.coroutines.Job? = null
    private var clipboardCheckJob: kotlinx.coroutines.Job? = null
    private var mainSideSheet: SideSheetDialog? = null
    private var mainBottomSheet: BottomSheetDialog? = null
    private var connectionDetailsDialog: BottomSheetDialog? = null
    private var connectionDetailsBinding: BottomSheetConnectionDetailsBinding? = null
    private var activeBottomProfile: Profile? = null
    private var latestLatencyLabel: String? = null
    private var latestExitLocationLabel: String? = null

    /**
     * 本次会话的出口探测是否已有结论（查到、或查完没拿到，都算有结论）。
     *
     * 底栏的出口行是**预留**的：可见性只跟随连接状态，所以在探测出结果之前要有东西顶上，
     * 否则结果回来那一刻底栏会凭空长出一行。没有这个标记就分不清「还在查」和「查了但没拿到」——
     * 两者都表现为 label 为空，但前者该显示占位、后者该显示 `—`。
     */
    private var exitProbeFinished = false
    private var latencyTestInProgress = false
    private var latencyTestGeneration = 0L

    // ──────────────────────────────────────────────────────────── 拓扑地球（连接详情面板）

    /** 节点列表的当前快照，由 [observeViewModel] 维护；地球要拿它当散点来源。 */
    private var allProfiles: List<Profile> = emptyList()

    /**
     * 地球的地理库句柄。**不随面板收起而关闭**：mmap 一次很便宜、但反复重开会在 GC 收掉旧
     * 映射之前堆起好几份，而且关闭与后台构建并发时语义不清。整页销毁时统一释放，
     * 中途换了库（用户刚下载完）用 [globeResolverStale] 触发重开。
     */
    private var globeResolver: GeoResolver? = null
    private var globeResolverStale = false
    private var globeJob: kotlinx.coroutines.Job? = null
    private var globeDownloadJob: kotlinx.coroutines.Job? = null
    private var globeDownloading = false
    private var globeDownloadFailed = false

    /** 同一时刻只允许一次构建（[globeRates] 不是线程安全的，串行也就不必加锁到构建粒度）。 */
    private var globeBuildInFlight = false

    /** 活跃连接的**上一次累计字节采样**（[ActiveConnection.id] → [累计字节, 时刻]），用来把累计量差分成瞬时速率。 */
    private val globeRates = HashMap<Long, LongArray>()
    private val globeRatesLock = Any()

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            // Notification permission was already handled before opening the
            // system VPN-consent screen. Start directly to avoid requesting it
            // a second time after the user returns.
            startVpnService()
        } else {
            finishStartRequest()
            Toast.makeText(requireContext(), getString(CoreR.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Snackbar.make(binding.root, getString(CoreR.string.notification_permission_denied_continue), Snackbar.LENGTH_LONG)
                .setAnchorView(binding.bottomContainer)
                .show()
        }
        // Android 13+ does not require POST_NOTIFICATIONS to run a foreground
        // service. Continue the user-requested connection even when notification
        // drawer visibility was declined.
        startSelectedService()
    }

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents != null) {
            if (ShareCryptoUtils.isEncryptedPayload(result.contents)) {
                showPinInputDialog(result.contents)
            } else {
                importProfileFromJsonBase64(result.contents)
            }
        }
    }

    private fun showPinInputDialog(encryptedPayload: String) {
        showPinDialog(
            title = getString(CoreR.string.enter_pin_title),
            hint = getString(CoreR.string.pin_hint)
        ) { pin, input ->
            val decryptedJson = ShareCryptoUtils.decrypt(encryptedPayload, pin)
            if (decryptedJson == null) {
                input.error = getString(CoreR.string.error_invalid_pin)
                false
            } else {
                importProfileFromJsonString(decryptedJson)
                true
            }
        }
    }

    private fun showPinDialog(
        title: String,
        hint: String,
        onSubmit: (String, android.widget.EditText) -> Boolean
    ) {
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            this.hint = hint
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(input)
            .setPositiveButton(getString(CoreR.string.ok), null)
            .setNegativeButton(getString(CoreR.string.cancel), null)
            .create()

        dialog.setOnShowListener {
            val submit = {
                input.error = null
                val pin = input.text?.toString().orEmpty()
                if (pin.isBlank()) {
                    input.error = getString(CoreR.string.error_pin_empty)
                    input.requestFocus()
                } else if (onSubmit(pin, input)) {
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            input.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    submit()
                    true
                } else {
                    false
                }
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    private fun importProfileFromJsonBase64(base64Str: String) {
        try {
            val payload = stripStunScheme(base64Str)
            // 加密 payload（{v,s,i,c}）需要 PIN 解密后导入。
            if (ShareCryptoUtils.isEncryptedPayload(payload)) {
                showPinInputDialog(payload)
                return
            }
            val clean = payload.filterNot { it.isWhitespace() }
            val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
            val jsonString = String(decodedBytes, Charsets.UTF_8)
            importProfileFromJsonString(jsonString)
        } catch (e: Exception) {
            StunLogger.e("HomeFragment", "Scan QR Code failed", e)
            Toast.makeText(requireContext(), getString(CoreR.string.invalid_qr), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stripStunScheme(raw: String): String {
        val text = raw.trim()
        return when {
            text.startsWith("stun://", ignoreCase = true) -> text.substring(7).trim()
            text.startsWith("stun:", ignoreCase = true) -> text.substring(5).removePrefix("//").trim()
            else -> text
        }
    }

    // 统一的导入分派：stun:// URI / 加密内容 / 明文 JSON / Base64 JSON。
    private fun handleImportText(raw: String) {
        val text = stripStunScheme(raw)
        when {
            ShareCryptoUtils.isEncryptedPayload(text) -> showPinInputDialog(text)
            text.startsWith("{") && text.endsWith("}") && text.contains("\"sshAddr\"") -> importProfileFromJsonString(text)
            else -> importProfileFromJsonBase64(text)
        }
    }

    private fun importProfileFromJsonString(jsonString: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val profile = Gson().fromJson(jsonString, Profile::class.java)
                if (profile.sshAddr.isBlank()) {
                    throw IllegalArgumentException("Missing sshAddr")
                }
                
                val existing = ProfileManager.getProfiles(requireContext())
                if (existing.any { it.name == profile.name && it.sshAddr == profile.sshAddr }) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(CoreR.string.profile_already_exists), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val newProfile = profile.copy(id = UUID.randomUUID().toString())
                ProfileManager.addProfile(requireContext(), newProfile)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(CoreR.string.profile_added, profile.name), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                StunLogger.e("HomeFragment", "Scan QR Code failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(CoreR.string.invalid_qr), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { exportProfilesToUri(it) }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importProfilesFromUri(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupToolbar()
        setupWindowInsets()
        setupRecyclerView()
        
        // 激活 TextView 的跑马灯（Marquee）滚动效果
        binding.tvStatus.isSelected = true
        binding.tvStatusSubtitle.isSelected = true

        observeViewModel()
        observeVpnState()
        setupListeners()

        // Warm the static tools sheet after the first frame, then reuse it for
        // the remainder of this view lifecycle.
        binding.root.post {
            if (_binding != null && isAdded) prepareMainMenu()
        }
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            (requireActivity() as MainActivity).openDrawer()
        }
        binding.toolbar.inflateMenu(R.menu.main_menu)
        // 「从剪贴板导入」已从本工具栏撤下（入口保留在统一配置面板里，见 menu/main_menu.xml 注释），
        // 故这里不再有对应分支。
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_tools -> { showMainMenu(); true }
                else -> false
            }
        }
        
        val filterItem = binding.toolbar.menu.findItem(R.id.action_filter)
        (filterItem?.actionView as? androidx.appcompat.widget.SearchView)?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter(newText ?: "")
                return true
            }
        })
    }

    private fun setupWindowInsets() {
        // The dock can grow for long names and large fonts; reserve its measured height.
        binding.bottomContainer.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val currentBinding = _binding ?: return@addOnLayoutChangeListener
            currentBinding.rvProfiles.updatePadding(bottom = view.height + (12 * resources.displayMetrics.density).toInt())
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            binding.bottomContainer.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private var lastCheckedClipboard: String? = null

    override fun onResume() {
        super.onResume()
        consumePendingVpnStart()
        consumePendingStunImport()
        checkClipboardForImport()
        // 从后台回来时面板可能还开着 —— 恢复地球刷新与自转（onPause 里停了）。
        connectionDetailsBinding?.let { startGlobe(it) }
    }

    /**
     * 界面不可见就停掉地球：自转是逐帧回调，面板收起后视图仍挂在窗口上
     * （`onDetachedFromWindow` 不触发），不停就是一直空转烧电。
     */
    override fun onPause() {
        super.onPause()
        stopGlobe()
    }

    fun consumePendingVpnStart() {
        val activity = requireActivity() as? MainActivity ?: return
        if (!activity.pendingVpnStart) return
        // 视图尚未创建（快捷方式在 onCreate 里经 executePendingTransactions 驱动本方法时，
        // onCreateView 还没跑，_binding 为 null）→ 保留标志，等 onResume 视图就绪后再消费。
        if (_binding == null || !isAdded) return
        activity.pendingVpnStart = false
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (state == VpnState.DISCONNECTED || state == VpnState.ERROR) {
            handleStartStop()
        }
    }

    fun consumePendingStunImport() {
        val activity = requireActivity() as? MainActivity ?: return
        val payload = activity.pendingStunImport ?: return
        activity.pendingStunImport = null
        handleImportText(payload)
    }

    private fun checkClipboardForImport() {
        try {
            val ctx = context ?: return
            val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
            if (!clipboard.hasPrimaryClip()) return
            val clipData = clipboard.primaryClip ?: return
            if (clipData.itemCount == 0) return
            val text = clipData.getItemAt(0).text?.toString()?.trim() ?: return

            if (text.isBlank() || text == lastCheckedClipboard) return
            lastCheckedClipboard = text

            clipboardCheckJob?.cancel()
            clipboardCheckJob = viewLifecycleOwner.lifecycleScope.launch {
                val (payload, shouldOfferImport) = withContext(Dispatchers.Default) {
                    val candidate = stripStunScheme(text)
                    val isStunUri = text.startsWith("stun:", ignoreCase = true)
                    val isPlainJson = candidate.startsWith("{") && candidate.endsWith("}") &&
                        candidate.contains("\"sshAddr\"")
                    val decodedJson = if (!isStunUri && !isPlainJson) {
                        runCatching {
                            String(
                                Base64.decode(candidate.filterNot { it.isWhitespace() }, Base64.DEFAULT),
                                Charsets.UTF_8
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    val decodedObject = decodedJson
                        ?.takeIf { it.startsWith("{") && it.endsWith("}") }
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val isEncrypted = decodedObject?.let {
                        it.has("v") && it.has("s") && it.has("i") && it.has("c")
                    } == true
                    val isProfileBase64 = decodedObject?.let {
                        it.has("sshAddr") || it.has("tunnelType")
                    } == true
                    candidate to (isStunUri || isPlainJson || isEncrypted || isProfileBase64)
                }

                if (_binding != null && isAdded && shouldOfferImport) {
                    Snackbar.make(binding.root, getString(CoreR.string.action_import) + "?", Snackbar.LENGTH_LONG)
                        .setAction(getString(CoreR.string.action_import)) {
                            handleImportText(payload)
                        }
                        .setAnchorView(binding.bottomContainer)
                        .show()
                }
            }
        } catch (_: Exception) {}
    }

    // 侧边栏「从剪贴板导入」：读取 JSON、Base64 或加密分享内容。
    private fun pasteImportFromClipboard() {
        try {
            val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (text.isNullOrBlank()) {
                Toast.makeText(requireContext(), getString(CoreR.string.clipboard_empty), Toast.LENGTH_SHORT).show()
                return
            }
            handleImportText(text)
        } catch (_: Exception) {}
    }

    private fun setupListeners() {
        binding.btnEmptyScanQr.setOnClickListener {
            barcodeLauncher.launch(ScanOptions().apply {
                setPrompt(getString(CoreR.string.scan_prompt))
                setBeepEnabled(true)
                setOrientationLocked(false)
            })
        }

        binding.btnEmptyAddProfile.setOnClickListener {
            val intent = Intent(requireContext(), ProfileEditActivity::class.java)
            startActivity(intent)
        }

        binding.fabStartStop.setOnClickListener {
            handleStartStop()
        }

        binding.bottomContainer.setOnClickListener { handleConnectionBarClick() }
        binding.bottomContainer.onSwipeUp = { expandConnectionFromDock() }
        // TalkBack users get an equivalent explicit action; ordinary taps remain tests.
        ViewCompat.replaceAccessibilityAction(
            binding.bottomContainer,
            androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND,
            getString(R.string.connection_details_expand)
        ) { _, _ -> expandConnectionFromDock() }

        StunLogger.errorListener = { tag, msg, _ ->
            activity?.runOnUiThread {
                val b = _binding ?: return@runOnUiThread
                Snackbar.make(
                    b.root,
                    if (app.fjj.stun.BuildConfig.DEBUG) "[$tag] $msg" else msg,
                    Snackbar.LENGTH_LONG
                ).setAnchorView(b.bottomContainer).show()
            }
        }
    }

    private fun handleConnectionBarClick() {
        testSelectedProfileLatency()
    }

    private fun expandConnectionFromDock(): Boolean {
        if (StunRepository.vpnState.value !in setOf(VpnState.CONNECTED, VpnState.RECONNECTING)) return false
        if (_binding == null || !isAdded || activeBottomProfile == null) return false
        showConnectionDetails()
        return true
    }

    private fun renderConnectionIdentity() {
        if (_binding == null || !isAdded) return
        val profile = activeBottomProfile
        val initial = profile?.name
            ?.trim()
            ?.firstOrNull()
            ?.uppercaseChar()
            ?.toString()
            ?: "S"
        binding.tvBottomAvatarLetter.text = initial

        val connected = StunRepository.vpnState.value == VpnState.CONNECTED
        val reconnecting = StunRepository.vpnState.value == VpnState.RECONNECTING
        // 进程重启后内存字段已丢，但设备态里还留着上次探测结果——处于连接态时回填一次
        if (latestExitLocationLabel == null && connected) {
            ExitInfoStore.label(requireContext())?.let { latestExitLocationLabel = it }
        }
        renderBottomExit(connected || reconnecting)
        if (connected || reconnecting) {
            binding.tvStatus.text = profile?.name?.takeIf { it.isNotBlank() }
                ?: getString(CoreR.string.main_connected)
            binding.tvStatusSubtitle.visibility = View.VISIBLE
            binding.tvStatusSubtitle.text = if (latencyTestInProgress) {
                getString(CoreR.string.main_testing_latency)
            } else if (reconnecting) {
                getString(CoreR.string.main_reconnecting)
            } else {
                latestLatencyLabel
                    ?: StunRepository.latencyMs.value?.takeIf { it >= 0L }?.let { "$it ms" }
                    ?: getString(CoreR.string.main_connected)
            }
            // 设计稿：连接正常时时延取状态绿，重连中取警示色；不跟随主题主色（橙色主题下会发棕）
            binding.tvStatusSubtitle.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (reconnecting) R.color.connection_state_pending
                    else R.color.connection_state_online
                )
            )
        }

        connectionDetailsBinding?.let { details ->
            // 名字/时延先各算一次，再发给顶栏与拓扑卡身份头两个位置 —— 同一个值算两遍必然慢慢跑偏。
            val name = profile?.name?.takeIf { it.isNotBlank() }
                ?: getString(CoreR.string.main_connected)
            val latency = latestLatencyLabel
                ?: StunRepository.latencyMs.value?.takeIf { it >= 0L }?.let { "$it ms" }
                ?: EMPTY_VALUE
            details.tvDetailAvatarLetter.text = initial
            details.tvDetailName.text = name
            details.tvDetailLatency.text = latency
            details.tvDetailExit.text = latestExitLocationLabel ?: EMPTY_VALUE
            details.tvGlobeAvatarLetter.text = initial
            details.tvGlobeName.text = name
            details.tvGlobeLatency.text = latency
            renderFavoriteState(details, profile)
            renderQuality(details)
        }
        updateStatusContentDescription()
    }

    /**
     * 底栏出口行（`203.0.113.8 · 🇸🇬 Singapore`）。
     *
     * **可见性只跟随连接状态**，不跟随探测结果 —— 与 [layoutTraffic] 同一套规则。探测结果是
     * 几秒后才异步回来的，如果让行本身跟着结果出现，底栏会在那一刻凭空长高一行；预留之后，
     * 结果回来只换文字，行高不变。
     *
     * 因此这一行必须能表达三种状态：
     * - 还没查到 → 占位文案（说明在查，不会让人以为坏了）
     * - 查到了 → `ip · 国旗 城市 国家`
     * - 查完没拿到 → [EMPTY_VALUE]（与详情面板缺值时的写法一致，不谎报数据）
     *
     * @param live 是否处于"隧道活着"的状态（CONNECTED / RECONNECTING）；否则整行收起
     */
    private fun renderBottomExit(live: Boolean) {
        if (_binding == null || !isAdded) return
        val text = if (!live) {
            null
        } else {
            latestExitLocationLabel
                ?: if (exitProbeFinished) EMPTY_VALUE else getString(R.string.connection_exit_pending)
        }
        binding.tvBottomExit.text = text.orEmpty()
        binding.tvBottomExit.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /**
     * 未连接态（含失败）的底栏身份行。
     *
     * 主文案给"当前选中的节点名"，副文案给状态或时延 —— 这样底栏左侧的信息密度与连接态对称，
     * 不会只剩"未连接"三个字、右边一整片空着。断开这件事由红点表达，不需要再用大字重复一遍。
     *
     * 时延永远走**副文案**（红点之后），不往主文案后面拼括号 —— 那是改版前的写法
     * （`"未连接 (500 ms)"`），会把状态和测量值挤成一句读不出重点的话。
     *
     * @param subtitle 副文案（如时延结果）；为空时回落成"未连接"
     */
    private fun renderDisconnectedIdentity(subtitle: String?) {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return
        val nodeName = activeBottomProfile?.name?.takeIf { it.isNotBlank() }
        binding.tvStatus.text = nodeName ?: ctx.getString(CoreR.string.main_disconnected)
        binding.tvStatusSubtitle.setTextColor(ContextCompat.getColor(ctx, R.color.connection_state_offline))
        // 一个节点都没有时主文案本身就是"未连接"，副文案再写一遍纯属重复 —— 那种情况交给
        // 空状态视图去引导，这里直接收起副文案。
        val caption = subtitle?.takeIf { it.isNotBlank() }
            ?: getString(CoreR.string.main_disconnected).takeIf { nodeName != null }
        binding.tvStatusSubtitle.text = caption.orEmpty()
        binding.tvStatusSubtitle.visibility = if (caption == null) View.GONE else View.VISIBLE
    }

    /**
     * 出口信息的单一写入口：内存字段（本页即时渲染）+ 设备态落盘（小组件、进程重启后回填）。
     *
     * 落盘是有必要的：小组件在 AppWidgetProvider 生命周期里刷新，可能没有 Activity 存活，
     * 只写内存的话进程一死小组件就再也拿不到出口 IP。
     * 另外只有内容真的变了才广播刷新组件 —— 状态机抖动不该带来无谓的 APPWIDGET_UPDATE。
     */
    private fun setExitLocation(result: ExitIpProbe.Result?) {
        latestExitLocationLabel = result?.displayText
        val ctx = context ?: return
        val changed = if (result == null) {
            ExitInfoStore.clear(ctx)
        } else {
            val c = ExitInfoStore.save(ctx, result.ip, result.location)
            // 出口 IP 真的换了（连接中 egress pop 切换/重连到不同出口）→ 计入出口稳定性，
            // 供连接质量综合评分的“出口稳定性”维度扣分。断开置空不计入。
            if (c) StunRepository.noteExitChanged()
            c
        }
        if (changed) app.fjj.stun.widget.StunWidgets.refreshAll(ctx)
    }

    private fun renderTrafficState() {
        if (_binding == null || !isAdded) return
        val txRate = StunRepository.txRate.value ?: 0L
        val rxRate = StunRepository.rxRate.value ?: 0L
        val history = StunRepository.rateHistorySnapshot()
        // 上下行强调色是语义色，出了夜景变体（values-night/colors.xml）——**不能**缓存进
        // Fragment 字段，否则夜间切换会留下白天的值。ContextCompat 走 Resources 的配置
        // 匹配，配置变更时 Resources 会清掉自己的色缓存，拿到的一定是当前主题的色。
        // 主题属性色则走 ThemeColors：属性 id 与解析结果都带缓存，重复调用是常数时间。
        val upColor = ContextCompat.getColor(requireContext(), R.color.widget_up_accent)
        val downColor = ContextCompat.getColor(requireContext(), R.color.widget_down_accent)
        val dockUnitColor = getThemeColor("colorOnSurfaceVariant", android.graphics.Color.GRAY)
        val panelUnitColor = getThemeColor("colorOnSurface", android.graphics.Color.BLACK)

        // 两路采样序列在这里**一次拆好、四张图共用**：原先 `history.map` 在一个 tick 内要跑
        // 4 遍（底栏两张 + 详情面板两张），每遍都新分配一个与历史等长的 List。
        val upSamples = history.map { it.first }
        val downSamples = history.map { it.second }

        // 底栏使用较少槽位，保持小图中柱子清晰；详情仍保留自己的历史长度。
        binding.ivCompactUpChart.slots = DOCK_CHART_SLOTS
        binding.ivCompactDownChart.slots = DOCK_CHART_SLOTS
        binding.tvUpRate.text = app.fjj.stun.ui.view.ConnectionRateLabel.format(txRate, true, upColor, dockUnitColor)
        binding.tvDownRate.text = app.fjj.stun.ui.view.ConnectionRateLabel.format(rxRate, false, downColor, dockUnitColor)
        binding.ivCompactUpChart.submitSamples(upSamples, upColor)
        binding.ivCompactDownChart.submitSamples(downSamples, downColor)

        connectionDetailsBinding?.let { details ->
            details.ivDetailUpChart.slots = DETAIL_CHART_SLOTS
            details.ivDetailDownChart.slots = DETAIL_CHART_SLOTS
            // 面板图带坐标轴：纵轴 0 / 中值 / 峰值，横轴由 slots 推导成「15s / 10s / 5s / 0」
            details.ivDetailUpChart.showAxes = true
            details.ivDetailDownChart.showAxes = true
            details.tvDetailUpRate.text =
                rateLabel("↑", AppUtils.formatBytes(txRate), upColor, panelUnitColor)
            details.tvDetailDownRate.text =
                rateLabel("↓", AppUtils.formatBytes(rxRate), downColor, panelUnitColor)
            details.ivDetailUpChart.submitSamples(upSamples, upColor)
            details.ivDetailDownChart.submitSamples(downSamples, downColor)
            // 累计流量与运行时长也跟着刷：它们随会话单调累加，不跟着速率走会显得"卡住"
            details.tvDetailTotal.text = getString(
                CoreR.string.tv_traffic_total_format,
                AppUtils.formatBytes(StunRepository.txTotal.value ?: 0L),
                AppUtils.formatBytes(StunRepository.rxTotal.value ?: 0L)
            )
            // 拓扑卡身份头里的累计收发：与「总流量」行共用同一份文案与同一个方向口径（write=上行）
            details.tvGlobeTraffic.text = details.tvDetailTotal.text
            // 复用组件侧的时长格式化：它已经把「天/小时/分钟」在 6 种语言里配好了，
            // 另起一份必然与组件里的显示慢慢跑偏。
            details.tvDetailUptime.text = app.fjj.stun.widget.StunWidgetProviderBase.formatUptime(
                requireContext(),
                StunRepository.sessionStartedAt()
            )
        }
    }

    /**
     * 速率文案三段着色：箭头取上/下行强调色，数值取正文色，单位（/s）取 [unitColor]。
     * 用 Spannable 而非 compound drawable：图表列容不下额外的图标宽度。
     */
    private fun rateLabel(arrow: String, value: String, arrowColor: Int, unitColor: Int): CharSequence {
        val suffix = "/s"
        val text = android.text.SpannableString("$arrow $value$suffix")
        text.setSpan(
            android.text.style.ForegroundColorSpan(arrowColor),
            0,
            arrow.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        text.setSpan(
            android.text.style.ForegroundColorSpan(unitColor),
            text.length - suffix.length,
            text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return text
    }

    /** 面板右上角星标：已收藏为实心暖色，未收藏为空心中性色。 */
    private fun renderFavoriteState(details: BottomSheetConnectionDetailsBinding, profile: Profile?) {
        val favorite = profile?.favorite == true
        details.btnDetailFavorite.setImageResource(
            if (favorite) R.drawable.ic_star else R.drawable.ic_star_border
        )
        details.btnDetailFavorite.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (favorite) R.color.connection_favorite_active else R.color.connection_favorite_inactive
            )
        )
        details.btnDetailFavorite.contentDescription = getString(
            if (favorite) R.string.connection_favorite_remove else R.string.connection_favorite_add
        )
    }

    /** 「上次连接」行：本地化的相对时间（如「0 分钟前」）；从未连接过显示占位符。 */
    private fun formatLastConnected(epochMs: Long): String {
        if (epochMs <= 0L) return EMPTY_VALUE
        return android.text.format.DateUtils.getRelativeTimeSpanString(
            epochMs,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

    /**
     * 把连接详情面板上当前可见的字段拼成纯文本放进剪贴板。
     *
     * 刻意读取视图当前值而不是重新计算：用户看到什么就复制什么，两处不会因为
     * 各自实现一遍取值逻辑而逐渐不一致。
     */
    private fun copyConnectionDetails(details: BottomSheetConnectionDetailsBinding) {
        val ctx = context ?: return
        val rows = listOf(
            ctx.getString(R.string.connection_server) to details.tvDetailServer.text,
            ctx.getString(CoreR.string.username) to details.tvDetailUser.text,
            ctx.getString(R.string.connection_last_connected) to details.tvDetailLastConnected.text,
            ctx.getString(R.string.connection_label_protocol) to details.tvDetailProtocol.text,
            ctx.getString(R.string.connection_exit) to details.tvDetailExit.text,
            ctx.getString(R.string.widget_label_sni) to details.tvDetailSni.text,
            ctx.getString(R.string.widget_label_host) to details.tvDetailHost.text,
            ctx.getString(R.string.connection_total_traffic) to details.tvDetailTotal.text,
            ctx.getString(R.string.widget_label_uptime) to details.tvDetailUptime.text,
        )
        val text = rows.joinToString("\n") { (label, value) -> "$label: $value" }
        val clipboard = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("StunConnection", text))
        Toast.makeText(ctx, getString(CoreR.string.copy_success), Toast.LENGTH_SHORT).show()
    }

    // ──────────────────────────────────────────────────────────── 拓扑地球

    /**
     * 开始「周期刷新 + 自转」。面板展开、以及从后台回到前台时调用；重复调用安全 ——
     * 刷新 job 已经在跑就直接返回，只把自转重新点着。
     */
    /** 应用拓扑星空模式到地球与切换按钮（选中态用 colorPrimary 高亮，未选 colorOnSurfaceVariant）。 */
    private fun applyGlobeMode(details: BottomSheetConnectionDetailsBinding, starry: Boolean) {
        details.globeTopology.starryMode = starry
        val attrId = resources.getIdentifier(
            if (starry) "colorPrimary" else "colorOnSurfaceVariant", "attr", requireContext().packageName
        )
        val color = if (attrId != 0) com.google.android.material.color.MaterialColors.getColor(
            details.btnTopologyMode, attrId, android.graphics.Color.GRAY
        ) else android.graphics.Color.GRAY
        details.btnTopologyMode.imageTintList = android.content.res.ColorStateList.valueOf(color)
        details.btnTopologyMode.alpha = if (starry) 1f else 0.7f
    }

    private fun startGlobe(details: BottomSheetConnectionDetailsBinding) {
        if (!isAdded || _binding == null) return
        details.globeTopology.setAnimating(true)
        details.globeTopology.onSelectionChanged = { selection ->
            updateGlobeBubble(details, selection)
        }
        if (globeJob?.isActive == true) return
        globeJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshGlobe(details)
                delay(GLOBE_REFRESH_MS)
            }
        }
    }

    /**
     * 停刷新 + 停自转。面板收起、页面 [onPause]、视图销毁三处都要调 ——
     * 漏掉任何一处，Choreographer 的逐帧回调都会在没有可见界面时继续空转。
     */
    private fun stopGlobe() {
        globeJob?.cancel()
        globeJob = null
        connectionDetailsBinding?.globeTopology?.let { globe ->
            globe.setAnimating(false)
            // 先清选中（会回调监听器把气泡收起），再摘监听器 —— 顺序反了气泡就留在屏幕上。
            globe.clearSelection()
            globe.onSelectionChanged = null
            globe.onZoomChanged = null
        }
    }

    /**
     * 取一份拓扑并提交给地球。地理库没下齐就不构建（构建必然空手而归），改在状态行给下载入口。
     *
     * 线程：DNS / mmdb 全在后台线程，UI 写回主线程。用 [globeBuildInFlight] 串行化 ——
     * [globeRates] 是普通 HashMap，串行之后连加锁粒度都不必操心。
     */
    private suspend fun refreshGlobe(details: BottomSheetConnectionDetailsBinding) {
        val ctx = context ?: return
        if (globeBuildInFlight) return
        globeBuildInFlight = true
        try {
            // 连接统计先算先画：它只依赖 Go 侧的连接快照，地理库在不在都能用。
            val connections = withContext(Dispatchers.IO) {
                val raw = runCatching { myssh.Myssh.getActiveConnectionsJSON() }.getOrNull()
                ActiveConnections.parse(raw)
            }
            renderConnectionStats(details, connections)

            if (!SettingsManager.geoCityAllReady(ctx)) {
                details.globeTopology.submitTopology(null)
                if (globeDownloading) {
                    // 进度文案由下载回调持续更新，这里不覆盖；按钮也让位给进度文案。
                    details.btnGlobeDownload.visibility = View.GONE
                } else {
                    details.tvGlobeStatus.text = getString(
                        if (globeDownloadFailed) R.string.connection_globe_download_failed
                        else R.string.connection_globe_need_library
                    )
                    details.btnGlobeDownload.text = getString(
                        if (globeDownloadFailed) R.string.connection_globe_retry
                        else R.string.connection_globe_download
                    )
                    details.btnGlobeDownload.isEnabled = true
                    details.btnGlobeDownload.visibility = View.VISIBLE
                }
                details.rowGlobeStatus.visibility = View.VISIBLE
                return
            }

            val profile = activeBottomProfile
            val currentNode = profile?.let { GlobeNode(globeAddressOf(it), it.name) }
            // 全逐个：订阅里的每个节点都单独出点，当前节点已在 hub 上，不重复灌进去。
            val nodes = allProfiles.asSequence()
                .filter { it.id != profile?.id }
                .map { GlobeNode(globeAddressOf(it), it.name) }
                .filter { it.address.isNotBlank() }
                .toList()

            pruneGlobeRates(connections)

            if (globeResolverStale) {
                globeResolver?.close()
                globeResolver = null
                globeResolverStale = false
            }
            val locator = GeoLocator(globeResolver ?: openGlobeResolver(ctx))
            val exitIp = ExitInfoStore.read(ctx)?.ip
            val topology = withContext(Dispatchers.IO) {
                GlobeTopologyBuilder(locator).build(
                    currentNode = currentNode,
                    nodes = nodes,
                    connections = connections,
                    exitIp = exitIp,
                    rateOf = ::globeRateOf,
                )
            }
            // 构建期间面板可能已经关了 —— 别往已经废弃的 binding 上写。
            if (connectionDetailsBinding !== details) return

            details.globeTopology.submitTopology(topology)
            details.globeTopology.contentDescription = globeAccessibilityLabel(topology)
            if (topology.isEmpty) {
                // 「库在、但一个点都没定位出来」与「库还没下」是两件事，文案必须分开。
                details.tvGlobeStatus.text = getString(R.string.connection_globe_empty)
                details.btnGlobeDownload.visibility = View.GONE
                details.rowGlobeStatus.visibility = View.VISIBLE
            } else {
                details.rowGlobeStatus.visibility = View.GONE
            }
        } finally {
            globeBuildInFlight = false
        }
    }

    /**
     * 拓扑卡下方的连接统计条：活跃连接数 + 这些连接生命周期内的累计收发字节。
     * 与「实时速率」不重复 —— 这里是不随连接结束而清零的累计口径，方向口径同
     * Go connInfoExport：write=上行、read=下行。
     */
    private fun renderConnectionStats(
        details: BottomSheetConnectionDetailsBinding,
        connections: List<ActiveConnection>,
    ) {
        details.tvConnStatCount.text = connections.size.toString()
        details.tvConnStatUp.text = AppUtils.formatBytes(connections.sumOf { it.writeBytes })
        details.tvConnStatDown.text = AppUtils.formatBytes(connections.sumOf { it.readBytes })
    }

    /**
     * 打开（或复用）地理库句柄。**每次都用新的**只有在库刚下完时才发生
     * （那时 [globeResolverStale] 为真，会把旧句柄关掉重开），平时一直复用同一个。
     */
    private fun openGlobeResolver(ctx: Context): GeoResolver {
        globeResolver?.takeIf { it.available }?.let { return it }
        globeResolver?.close()
        val v4 = SettingsManager.GeoCityDb.IPV4.let { db ->
            SettingsManager.geoCityFile(ctx, db).takeIf { SettingsManager.geoCityReady(ctx, db) }
        }
        val v6 = SettingsManager.GeoCityDb.IPV6.let { db ->
            SettingsManager.geoCityFile(ctx, db).takeIf { SettingsManager.geoCityReady(ctx, db) }
        }
        return MmdbGeoResolver.open(v4, v6).also { globeResolver = it }
    }

    /**
     * 节点落点取哪个地址：**SSH 服务器地址**。
     *
     * 它才是隧道真正的落地端（`proxyAddr` 只是前置/中继），而且与
     * [StunRepository.getSshHandshakeInfo] 判定节点身份用的是同一个字段 ——
     * 两处口径一致，面板上写的服务器与地球上的点才是同一台机器。
     */
    private fun globeAddressOf(profile: Profile): String =
        profile.sshAddr.ifBlank { profile.proxyAddr }

    /**
     * 把活跃连接的**累计字节**差分成瞬时速率（字节/秒）。
     *
     * Go 侧只给累计量，而弧上脉冲快慢要的是瞬时值，所以拿相邻两次刷新的差来算。
     * 第一次采样没有参照，返回 0 —— 那条弧先画成静态细线，下一轮才开始流动。
     */
    private fun globeRateOf(connection: ActiveConnection): Long = synchronized(globeRatesLock) {
        val now = System.currentTimeMillis()
        val previous = globeRates[connection.id]
        globeRates[connection.id] = longArrayOf(connection.totalBytes, now)
        if (previous == null) {
            0L
        } else {
            val elapsed = now - previous[1]
            val delta = connection.totalBytes - previous[0]
            if (elapsed <= 0L || delta <= 0L) 0L else delta * 1000L / elapsed
        }
    }

    /** 丢掉已经结束的连接留下的采样，免得这张表跟着会话数一直长。 */
    private fun pruneGlobeRates(connections: List<ActiveConnection>) {
        val alive = connections.mapTo(HashSet<Long>()) { it.id }
        synchronized(globeRatesLock) {
            if (globeRates.isNotEmpty()) globeRates.keys.retainAll(alive)
        }
    }

    /**
     * 地球的无障碍描述。这是张位图，读屏念不出内容，所以把「几个节点、几条活跃连接」
     * 归纳成一句话给它 —— 具体是哪些节点，面板下面的字段卡里有文本可读。
     */
    private fun globeAccessibilityLabel(topology: GlobeTopology): CharSequence {
        val nodeCount = topology.markers.size
        val activeCount = topology.markers.sumOf { it.connectionCount }
        val nodes = resources.getQuantityString(
            R.plurals.connection_globe_nodes, nodeCount, nodeCount
        )
        val active = resources.getQuantityString(
            R.plurals.connection_globe_connections, activeCount, activeCount
        )
        return getString(R.string.connection_globe_description_counts, nodes, active)
    }

    /**
     * 点选落点后的气泡。三行内容：
     * 1. 标题 —— 节点名，退而求其次是城市，纯出口落点两者皆无就用「出口位置」兜底；
     * 2. 副标题 —— 地址（用户核对节点的凭据）；坐标格聚合了多个节点时改说「此处聚合 N 个节点」，
     *    因为这时 [GlobeMarker.address] 只代表其中一个，单列一个地址反而误导；
     * 3. 状态行 —— 活跃连接数 · 瞬时速率，没有活跃连接就是「暂无活跃连接」。
     *
     * 2 秒一轮的刷新会带着新数据重新回调（相机冻结着，锚点不动），所以文案会原地更新而不是重建。
     */
    private fun updateGlobeBubble(
        details: BottomSheetConnectionDetailsBinding,
        selection: GlobeView.Selection?,
    ) {
        if (!isAdded) return
        val bubble = details.bubbleGlobeDetail
        if (selection == null) {
            bubble.isVisible = false
            return
        }
        val marker = selection.marker
        details.tvGlobeBubbleTitle.text = marker.label
            ?: marker.point.city
            ?: getString(R.string.connection_globe_bubble_exit)

        val subtitle = if (marker.nodeCount > 1) {
            getString(R.string.connection_globe_bubble_merged_format, marker.nodeCount)
        } else {
            marker.address ?: marker.point.city.orEmpty()
        }
        details.tvGlobeBubbleSubtitle.text = subtitle
        details.tvGlobeBubbleSubtitle.isVisible = subtitle.isNotBlank()

        details.tvGlobeBubbleStatus.text = globeBubbleStatusLine(marker)

        bubble.isVisible = true
        // 先量再钉：文字刚变完布局还没跑，post 一拍等量完尺寸再定位。
        bubble.post { positionGlobeBubble(details, selection) }
    }

    /** 气泡第三行：活跃连接复数 · 瞬时速率；零连接时是明确的「没有」而不是省略。 */
    private fun globeBubbleStatusLine(marker: GlobeMarker): CharSequence {
        if (marker.connectionCount <= 0) return getString(R.string.connection_globe_bubble_idle)
        val count = resources.getQuantityString(
            R.plurals.connection_globe_connections, marker.connectionCount, marker.connectionCount
        )
        return getString(
            R.string.connection_globe_description_counts,
            count, AppUtils.formatSpeed(marker.bytesPerSecond)
        )
    }

    /**
     * 把气泡钉在选中落点旁边。锚点在 [GlobeView] 坐标系里，而球在容器里顶着左上角放，
     * 所以坐标可以原样搬进容器。优先放落点**上方**，放不下（贴着球顶）就落到下方；
     * 横向始终夹在容器内，不允许气泡探出球卡。
     */
    private fun positionGlobeBubble(
        details: BottomSheetConnectionDetailsBinding,
        selection: GlobeView.Selection,
    ) {
        val container = details.globeContainer
        val bubble = details.bubbleGlobeDetail
        if (container.width == 0 || bubble.width == 0) return
        val density = resources.displayMetrics.density
        val gap = 14f * density          // 标记半径 + 呼吸空间
        val margin = 4f * density

        var x = selection.anchorX - bubble.width / 2f
        x = x.coerceIn(margin, (container.width - bubble.width - margin).coerceAtLeast(margin))

        var y = selection.anchorY - bubble.height - gap
        if (y < margin) y = selection.anchorY + gap
        y = y.coerceIn(margin, (container.height - bubble.height - margin).coerceAtLeast(margin))

        bubble.translationX = x
        bubble.translationY = y
    }

    /**
     * 补下缺失的离线地理库：只补[缺的那几份]，已下过的不会重下。
     *
     * 进度回调来自后台线程，切回主线程才写 UI。失败不静默 —— 文案与按钮留在原位供原地重试；
     * 下载是**允许部分成功**的（先下成的那份已经落盘），重试时 [SettingsManager.missingGeoCityDbs]
     * 会自动只补剩下的。
     */
    private fun startGlobeDownload(details: BottomSheetConnectionDetailsBinding) {
        val ctx = context ?: return
        if (globeDownloading || globeDownloadJob?.isActive == true) return
        val missing = SettingsManager.missingGeoCityDbs(ctx)
        if (missing.isEmpty()) return

        globeDownloading = true
        globeDownloadFailed = false
        details.btnGlobeDownload.visibility = View.GONE
        details.tvGlobeStatus.text = getString(R.string.connection_globe_downloading_format, 0)

        globeDownloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    SettingsManager.downloadGeoCitySync(ctx, missing) { _, read, total ->
                        if (total <= 0L) return@downloadGeoCitySync
                        val percent = ((read * 100L) / total).toInt().coerceIn(0, 100)
                        activity?.runOnUiThread {
                            if (connectionDetailsBinding === details) {
                                details.tvGlobeStatus.text = getString(
                                    R.string.connection_globe_downloading_format, percent
                                )
                            }
                        }
                    }
                }.exceptionOrNull()
            }
            globeDownloading = false
            // 不管成没成，句柄都要重开：部分成功时也得吃上刚落盘的那份。
            globeResolverStale = true
            if (connectionDetailsBinding !== details) return@launch
            globeDownloadFailed = error != null
            if (error == null) {
                details.rowGlobeStatus.visibility = View.GONE
            }
            // 立刻刷一轮，不必等下一个心跳；失败时 refreshGlobe 会把失败文案与「重试」放出来。
            refreshGlobe(details)
        }
    }

    private fun showConnectionDetails() {
        if (!isAdded || _binding == null) return
        connectionDetailsDialog?.takeIf { it.isShowing }?.let { return }
        val profile = activeBottomProfile ?: return
        val details = BottomSheetConnectionDetailsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(requireContext(), CoreR.style.Theme_App_BottomSheetDialog)
        connectionDetailsBinding = details
        connectionDetailsDialog = dialog

        // 拓扑星空模式：初始态取持久化设置，按钮翻转即落库 + 应用到 GlobeView。
        applyGlobeMode(details, SettingsManager.isGlobeStarryMode(requireContext()))
        details.btnTopologyMode.setOnClickListener {
            val next = !details.globeTopology.starryMode
            SettingsManager.setGlobeStarryMode(requireContext(), next)
            applyGlobeMode(details, next)
        }

        details.tvDetailServer.text = if (
            profile.tunnelType == Profile.TUNNEL_TYPE_RAW || profile.proxyAddr.isBlank()
        ) {
            profile.sshAddr
        } else {
            "${profile.proxyAddr} → ${profile.sshAddr}"
        }
        details.tvDetailUser.text = profile.user.ifBlank { EMPTY_VALUE }
        val protocol = profile.tunnelType.uppercase(Locale.ROOT)
        details.tvDetailProtocol.text = protocol
        //details.tvDetailMagic.text = profile.udpCustomMagic.ifBlank { "—" }
        details.tvDetailNote.text = profile.note.ifBlank { EMPTY_VALUE }

        // 拓扑卡的身份头与「节点详情」卡同源（同一个 profile），只是把最常用的几项提到球上方：
        // 服务器地址直接复用上面算好的那份文案，不在这里拼第二遍。
        details.tvGlobeServer.text = details.tvDetailServer.text
        details.tvGlobeProtocolChip.text = protocol
        // 标记魔数只对自定义隧道有意义：空值整枚标签收起，不写占位符。
        val magic = profile.udpCustomMagic.trim()
        details.tvGlobeMagicChip.isVisible = magic.isNotBlank()
        if (magic.isNotBlank()) {
            details.tvGlobeMagicChip.text = getString(R.string.connection_magic_format, magic)
        }
        details.tvGlobeLast.text = getString(
            R.string.connection_last_connected_short,
            formatLastConnected(profile.lastConnectedAt)
        )
        // SSH 服务器标识（版本标识行 + 认证阶段提示）：由 Go 引擎在真实握手时记录。
        // 只采纳来源地址与本节点一致的记录——引擎在地址无记录时会回退到「最近一次握手」，
        // 若不核对地址，未连接时就可能把别的节点的信息显示出来。
        val handshake = StunRepository.getSshHandshakeInfo(profile.sshAddr)
            ?.takeIf { it.address.equals(profile.sshAddr, ignoreCase = true) }
        details.tvDetailSshBanner.text =
            handshake?.serverVersion?.trim()?.takeIf { it.isNotBlank() }
                ?.let { SshBannerRenderer.render(it) } ?: EMPTY_VALUE
        val sshNotice = handshake?.banner?.trim().orEmpty()
        if (sshNotice.isNotBlank()) {
            // banner 可含 ANSI 颜色与白名单 HTML（含 http(s) 链接），消毒与渲染交给 SshBannerRenderer
            SshBannerRenderer.applyTo(details.tvDetailSshNotice, sshNotice)
            details.rowDetailSshNotice.visibility = View.VISIBLE
            details.dividerDetailSshNotice.visibility = View.VISIBLE
        } else {
            details.rowDetailSshNotice.visibility = View.GONE
            details.dividerDetailSshNotice.visibility = View.GONE
        }

        // 「辅助信息」两行：SNI / Host 的显示条件与节点列表（ProfileAdapter）用同一套规则，
        // 两处口径必须一致，否则同一个节点在组件与面板里显示不同。
        val detailIsTlsFixed = profile.tunnelType in listOf(
            Profile.TUNNEL_TYPE_QUIC, Profile.TUNNEL_TYPE_H3, Profile.TUNNEL_TYPE_MASQUE,
            Profile.TUNNEL_TYPE_WEBTRANSPORT
        )
        val detailIsTlsCapable = profile.tunnelType in listOf(
            Profile.TUNNEL_TYPE_RAW, Profile.TUNNEL_TYPE_WEBSOCKET, Profile.TUNNEL_TYPE_H2,
            Profile.TUNNEL_TYPE_GRPC, Profile.TUNNEL_TYPE_XHTTP
        )
        val detailIsTlsActive = detailIsTlsFixed || (detailIsTlsCapable && profile.tunnelTlsEnabled)
        details.tvDetailSni.text = profile.serverName
            .takeIf { detailIsTlsActive && it.isNotBlank() } ?: EMPTY_VALUE
        // ICMP_CUSTOM 不支持自定义 Host（VpnConfigBuilder 不发 custom_host），对其隐藏
        details.tvDetailHost.text = if (profile.tunnelType == Profile.TUNNEL_TYPE_ICMP_CUSTOM) {
            EMPTY_VALUE
        } else {
            profile.customHost.ifBlank { EMPTY_VALUE }
        }
        // 累计流量与运行时长由 renderTrafficState() 填充（随速率一起刷新），不在这里写死
        details.tvDetailLastConnected.text = formatLastConnected(profile.lastConnectedAt)

        // ⭐ 收藏：原地切换并落库，不关闭面板；LiveData 回流后 renderConnectionIdentity 会再校准一次
        renderFavoriteState(details, profile)
        details.btnDetailFavorite.setOnClickListener {
            val target = activeBottomProfile ?: return@setOnClickListener
            val next = target.copy(favorite = !target.favorite)
            activeBottomProfile = next
            renderFavoriteState(details, next)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                ProfileManager.updateProfile(requireContext(), next)
            }
        }

        details.btnDetailDisconnect.setOnClickListener {
            dialog.dismiss()
            if (StunRepository.vpnState.value == VpnState.CONNECTED ||
                StunRepository.vpnState.value == VpnState.RECONNECTING
            ) {
                handleStartStop()
            }
        }

        // 地球：点「下载」补离线地理库。进度与失败都写进卡片底部那行状态文案（见 refreshGlobe）。
        details.btnGlobeDownload.setOnClickListener { startGlobeDownload(details) }

        // ⋮ 只挂既有能力，不做"待实现"占位：编辑节点走既有的编辑页，复制详情把面板上
        // 看得见的字段拼成纯文本，方便贴进工单/聊天。原顶栏 ⋮ 已删，拓扑卡身份头是唯一入口。
        details.btnGlobeMore.setOnClickListener { anchor -> showDetailMoreMenu(details, profile, anchor) }

        // 右下角复位视角：倍率收回 1 + 相机重新对准当前节点 + 收掉点选气泡。
        // 默认倍率下它没有可复位的东西，故由 renderGlobeResetState 压暗（与星空模式按钮同一套表达）。
        details.btnGlobeReset.setOnClickListener { details.globeTopology.resetView() }
        details.globeTopology.onZoomChanged = { renderGlobeResetState(details) }
        renderGlobeResetState(details)
        val initialSheetBottomPadding = details.root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(details.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = initialSheetBottomPadding + bars.bottom)
            insets
        }
        dialog.setContentView(details.root)
        dialog.setOnShowListener {
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                BottomSheetBehavior.from(sheet).apply {
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
            renderConnectionIdentity()
            renderTrafficState()
            startGlobe(details)
        }
        dialog.setOnDismissListener {
            stopGlobe()
            connectionDetailsBinding = null
            connectionDetailsDialog = null
        }
        dialog.show()
    }

    /**
     * 详情面板的 ⋮ 菜单。顶栏那枚与拓扑卡身份头那枚**共用同一份** ——
     * 两处入口、一套行为，免得将来只加了一处，同一个图标点出两个不同的菜单。
     *
     * 只挂既有能力，不做"待实现"占位：编辑节点走既有编辑页，复制详情把面板上看得见的字段
     * 拼成纯文本，方便贴进工单/聊天。
     */
    private fun showDetailMoreMenu(
        details: BottomSheetConnectionDetailsBinding,
        profile: Profile,
        anchor: View,
    ) {
        android.widget.PopupMenu(requireContext(), anchor).apply {
            menu.add(getString(CoreR.string.edit_profile)).setOnMenuItemClickListener {
                connectionDetailsDialog?.dismiss()
                startActivity(
                    Intent(requireContext(), ProfileEditActivity::class.java)
                        .putExtra("EXTRA_PROFILE_ID", profile.id)
                )
                true
            }
            menu.add(getString(CoreR.string.copy)).setOnMenuItemClickListener {
                copyConnectionDetails(details)
                true
            }
            show()
        }
    }

    /**
     * 复位视角按钮的亮/暗：只有"放大过"才点亮。
     *
     * 相机被拖走**不算**亮灯条件 —— 复位会顺手把相机也带回 hub，但拖动随时都在发生（还有自转摆动），
     * 拿它当判据按钮会一直亮着，反而看不出当前是不是放大状态。
     */
    private fun renderGlobeResetState(details: BottomSheetConnectionDetailsBinding) {
        details.btnGlobeReset.alpha = if (details.globeTopology.zoomRatio > 1.001f) 1f else 0.55f
    }

    private fun observeViewModel() {
        val viewModel: app.fjj.stun.ui.viewmodel.MainViewModel by viewModels()
        viewModel.profilesLiveData.observe(viewLifecycleOwner) { profiles ->
            if (_binding == null || !isAdded) return@observe
            val ctx = context ?: return@observe
            binding.layoutEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            val selectedId = SettingsManager.getSelectedProfileId(ctx)
            val effectiveSelectedId = selectedId?.takeIf { id -> profiles.any { it.id == id } }
                ?: profiles.firstOrNull()?.id
            if (effectiveSelectedId != null && effectiveSelectedId != selectedId) {
                SettingsManager.setSelectedProfileId(ctx, effectiveSelectedId)
            }
            allProfiles = profiles
            adapter.updateProfiles(profiles, effectiveSelectedId)
            updateNodeTabCounts(profiles)
            activeBottomProfile = profiles.firstOrNull { it.id == effectiveSelectedId }
            renderConnectionIdentity()
            app.fjj.stun.widget.StunWidgets.refreshAll(ctx)
        }

        StunRepository.txRate.observe(viewLifecycleOwner) { _ ->
            if (_binding == null || !isAdded) return@observe
            if (isVpnRunning) renderTrafficState()
        }

        StunRepository.rxRate.observe(viewLifecycleOwner) { _ ->
            if (_binding == null || !isAdded) return@observe
            if (isVpnRunning) renderTrafficState()
        }

        StunRepository.latencyMs.observe(viewLifecycleOwner) { latency ->
            if (_binding == null || !isAdded || latency == null || latency < 0L) return@observe
            latestLatencyLabel = "$latency ms"
            if (isVpnRunning) renderConnectionIdentity()
        }
        // 连接质量综合评分（星级 + 颜色）：延迟 / 抖动 / 出口稳定性三维合成，
        // 详情面板里以“质量 ★★★★☆”展示，颜色随评分走绿/琥珀/红。
        StunRepository.connectionQuality.observe(viewLifecycleOwner) { _ ->
            connectionDetailsBinding?.let { renderQuality(it) }
        }
    }

    private fun renderQuality(details: BottomSheetConnectionDetailsBinding) {
        val q = StunRepository.connectionQuality.value ?: QualityScore.unknown
        if (q.stars <= 0) {
            details.tvDetailQuality.visibility = View.GONE
            return
        }
        details.tvDetailQuality.visibility = View.VISIBLE
        val stars = "★".repeat(q.stars) + "☆".repeat(5 - q.stars)
        details.tvDetailQuality.text = getString(R.string.connection_quality_format, stars)
        details.tvDetailQuality.setTextColor(q.colorInt)
    }

    private fun observeVpnState() {
        StunRepository.vpnState.observe(viewLifecycleOwner) { state ->
            if (_binding == null || !isAdded) return@observe
            updateUiState(state)
        }
        // Go 引擎上报的致命/连接错误直接以 Snackbar 提示，解决“报错不知道”
        StunRepository.engineError.observe(viewLifecycleOwner) { msg ->
            if (_binding == null || !isAdded) return@observe
            if (!msg.isNullOrEmpty()) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                StunRepository.engineError.postValue(null) // 仅提示一次
            }
        }
        // 🌟 核心引擎崩溃/Panic 拦截事件弹窗展示
        StunRepository.crashEvent.observe(viewLifecycleOwner) { crashLog ->
            if (_binding == null || !isAdded) return@observe
            if (!crashLog.isNullOrEmpty()) {
                showCrashDialog(crashLog, isPrevious = false)
                StunRepository.crashEvent.postValue(null)
            }
        }
    }

    private fun showCrashDialog(crashLog: String, isPrevious: Boolean = false) {
        val ctx = context ?: return
        val titleRes = if (isPrevious) CoreR.string.crash_dialog_title_prev else CoreR.string.crash_dialog_title

        val density = resources.displayMetrics.density
        val paddingH = (16 * density).toInt()
        val dialogHeight = (resources.displayMetrics.heightPixels * 0.80f).toInt()
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.95f).toInt()

        val textView = TextView(ctx).apply {
            text = crashLog
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(paddingH, paddingH / 2, paddingH, paddingH)
        }

        val scrollView = android.widget.ScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dialogHeight
            )
            clipToPadding = false
        }
        scrollView.addView(textView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(titleRes) + " (${crashLog.length} chars)")
            .setView(scrollView)
            .setPositiveButton(getString(CoreR.string.copy)) { _, _ ->
                val clipboard = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("CrashLog", crashLog)
                clipboard.setPrimaryClip(clip)
                Snackbar.make(binding.root, getString(CoreR.string.copy_success) + " (${crashLog.length} chars)", Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton(getString(CoreR.string.close), null)
            .create()

        dialog.show()
        dialog.window?.setLayout(dialogWidth, dialogHeight)
    }

    private fun updateUiState(state: VpnState?) {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return
        
        binding.fabStartStop.clearAnimation()
        
        val errorColor = ContextCompat.getColor(ctx, R.color.connection_state_offline)
        val successColor = ContextCompat.getColor(ctx, R.color.connection_state_online)
        val warningColor = ContextCompat.getColor(ctx, R.color.connection_state_pending)

        when (state) {
            VpnState.DISCONNECTED -> {
                startRequestInProgress = false
                isVpnRunning = false
                latestLatencyLabel = null
                setExitLocation(null)
                latencyTestInProgress = false
                latencyTestJob?.cancel()
                binding.fabStartStop.isEnabled = true
                setStartStopButton(R.drawable.ic_play, getString(CoreR.string.connect))
                binding.fabStartStop.contentDescription = ctx.getString(CoreR.string.connect)
                setFabColor(getThemeColor("colorPrimaryContainer", android.graphics.Color.LTGRAY),
                    getThemeColor("colorOnPrimaryContainer", android.graphics.Color.BLACK))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                renderDisconnectedIdentity(null)
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.GONE
                if (isStopping) isStopping = false
            }
            VpnState.CONNECTING -> {
                startRequestInProgress = false
                isVpnRunning = false
                latencyTestInProgress = false
                latencyTestJob?.cancel()
                binding.fabStartStop.isEnabled = false
                setStartStopButton(R.drawable.ic_sync, null)
                binding.fabStartStop.contentDescription = ctx.getString(CoreR.string.main_connecting)
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(warningColor)
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutTraffic.visibility = View.GONE
                binding.tvStatusSubtitle.visibility = View.GONE
                binding.tvStatusSubtitle.text = ""
                startFabLoadingAnimation()
                binding.tvStatus.text = ctx.getString(CoreR.string.main_connecting)
            }
            VpnState.CONNECTED -> {
                startRequestInProgress = false
                isVpnRunning = true
                binding.fabStartStop.isEnabled = true
                setStartStopButton(R.drawable.ic_stop_rounded, getString(R.string.connection_bar_action_stop))
                binding.fabStartStop.contentDescription = ctx.getString(CoreR.string.disconnect)
                // 断开/重连属破坏性操作 → 走主题的 error 角色（含动态取色）；兜底取 M3 基线值
                setFabColor(getThemeColor("colorErrorContainer", 0xFFFFDAD6.toInt()),
                    getThemeColor("colorError", 0xFFB3261E.toInt()))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                binding.tvStatus.text = activeBottomProfile?.name?.takeIf { it.isNotBlank() }
                    ?: ctx.getString(CoreR.string.main_connected)
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.VISIBLE
                playFabSuccessAnimation()
                // 新会话 = 新出口，旧结论作废（行本身已随连接状态占好位，这里只让它落回占位文案）
                exitProbeFinished = false
                testSelectedProfileLatency(delayMs = 3000L)
            }
            VpnState.RECONNECTING -> {
                startRequestInProgress = false
                isVpnRunning = false
                latencyTestInProgress = false
                setExitLocation(null)
                latencyTestJob?.cancel()
                binding.fabStartStop.isEnabled = true
                setStartStopButton(R.drawable.ic_stop_rounded, getString(R.string.connection_bar_action_stop))
                binding.fabStartStop.contentDescription = ctx.getString(CoreR.string.disconnect)
                // 断开/重连属破坏性操作 → 走主题的 error 角色（含动态取色）；兜底取 M3 基线值
                setFabColor(getThemeColor("colorErrorContainer", 0xFFFFDAD6.toInt()),
                    getThemeColor("colorError", 0xFFB3261E.toInt()))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(warningColor)
                binding.tvStatus.text = ctx.getString(CoreR.string.main_reconnecting)
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutTraffic.visibility = View.VISIBLE
            }
            VpnState.ERROR -> {
                startRequestInProgress = false
                isVpnRunning = false
                latencyTestInProgress = false
                setExitLocation(null)
                latencyTestJob?.cancel()
                binding.fabStartStop.isEnabled = true
                setStartStopButton(R.drawable.ic_play, getString(CoreR.string.connect))
                binding.fabStartStop.contentDescription = ctx.getString(CoreR.string.connect)
                setFabColor(getThemeColor("colorPrimaryContainer", android.graphics.Color.LTGRAY),
                    getThemeColor("colorOnPrimaryContainer", android.graphics.Color.BLACK))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                renderDisconnectedIdentity(ctx.getString(CoreR.string.main_connection_failed))
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.GONE
            }
            else -> {
                isVpnRunning = false
                latencyTestJob?.cancel()
                binding.tvStatusSubtitle.visibility = View.GONE
                binding.tvStatusSubtitle.text = ""
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.GONE
            }
        }
        if (state == VpnState.DISCONNECTED || state == VpnState.ERROR) {
            connectionDetailsDialog?.dismiss()
        }
        // 凹槽耳朵只在有连接详情可展开时出现（GONE 而非 INVISIBLE：耳朵在卡片外部，
        // 保留占位会在卡片上方留出一条空白）
        binding.ivExpandConnection.visibility = if (
            state == VpnState.CONNECTED || state == VpnState.RECONNECTING
        ) View.VISIBLE else View.GONE
        renderConnectionIdentity()
        if (state == VpnState.CONNECTED || state == VpnState.RECONNECTING) {
            renderTrafficState()
        }
        updateStatusContentDescription()
    }

    /**
     * 取主题属性颜色。实现在 [ThemeColors]：属性 id 只按名字解析一次，解析出的颜色按
     * Theme 实例缓存 —— 从前每调一次都要跨 JNI 按名字翻一遍资源表，而 `renderTrafficState`
     * 每秒要调两次（连接中 tx/rx 两个 observer 各触发一次）。
     * 这里只保留一层 `context == null`（Fragment 已脱附）的兜底。
     */
    private fun getThemeColor(attrName: String, default: Int): Int {
        val context = context ?: return default
        return ThemeColors.color(context, attrName, default)
    }

    private fun setFabColor(bg: Int, fg: Int) {
        binding.fabStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
        binding.fabStartStop.setTextColor(fg)
        binding.fabStartStop.iconTint = android.content.res.ColorStateList.valueOf(fg)
    }

    /**
     * 底栏主按钮：有文案时图标在上、文案在下（对齐设计稿的「断开」按钮）；
     * 无文案（连接中）时退回单图标居中，避免出现空文案的上下布局。
     */
    private fun setStartStopButton(iconRes: Int, label: CharSequence?) {
        val hasLabel = !label.isNullOrEmpty()
        binding.fabStartStop.setIconResource(iconRes)
        binding.fabStartStop.text = label ?: ""
        binding.fabStartStop.iconGravity = if (hasLabel) {
            com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_TOP
        } else {
            com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
        }
    }

    private fun startFabLoadingAnimation() {
        val alpha = android.view.animation.AlphaAnimation(1f, 0.6f).apply {
            duration = 600
            repeatCount = android.view.animation.Animation.INFINITE
            repeatMode = android.view.animation.Animation.REVERSE
        }
        val scale = android.view.animation.ScaleAnimation(1f, 0.95f, 1f, 0.95f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f, android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
            duration = 600
            repeatCount = android.view.animation.Animation.INFINITE
            repeatMode = android.view.animation.Animation.REVERSE
        }
        binding.fabStartStop.startAnimation(android.view.animation.AnimationSet(false).apply {
            addAnimation(alpha)
            addAnimation(scale)
        })
    }

    private fun playFabSuccessAnimation() {
        binding.fabStartStop.scaleX = 0.9f
        binding.fabStartStop.scaleY = 0.9f
        binding.fabStartStop.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).withEndAction {
            binding.fabStartStop.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
        }.start()
    }

    private suspend fun applyShizukuKeepAlive(): Boolean {
        return if (ShizukuUtils.isReady()) {
            val granted = ShizukuUtils.requestPermissionAwait()
            if (granted) {
                ShizukuUtils.addSelfToBatteryWhitelist(requireContext().packageName)
                ShizukuUtils.setStandbyBucketActive(requireContext().packageName)
            }
            granted
        } else {
            false
        }
    }

    /** 筛选 tab 计数：全部 / 收藏 / 最近（连过的）。列表全空时整行隐藏，只留空态插画。 */
    private fun updateNodeTabCounts(profiles: List<Profile>) {
        if (_binding == null) return
        binding.chipScrollTabs.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        binding.chipTabAll.text = getString(CoreR.string.node_tab_all, profiles.size)
        binding.chipTabFavorites.text =
            getString(CoreR.string.node_tab_favorites, profiles.count { it.favorite })
        binding.chipTabRecent.text =
            getString(CoreR.string.node_tab_recent, profiles.count { it.lastConnectedAt > 0 })
    }

    private fun setupRecyclerView() {
        val selectedId = SettingsManager.getSelectedProfileId(requireContext())
        adapter = ProfileAdapter(
            selectedProfileId = selectedId,
            onProfileClick = { profile ->
                val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
                if (state == VpnState.DISCONNECTED || state == VpnState.ERROR) {
                    latencyTestGeneration++
                    latencyTestJob?.cancel()
                    latencyTestInProgress = false
                    SettingsManager.setSelectedProfileId(requireContext(), profile.id)
                    adapter.updateProfiles(adapter.getProfiles(), profile.id)
                    activeBottomProfile = profile
                    latestLatencyLabel = null
                    setExitLocation(null)
                    renderConnectionIdentity()
                    app.fjj.stun.widget.StunWidgets.refreshAll(requireContext())
                    Toast.makeText(requireContext(), getString(CoreR.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(CoreR.string.main_profile_switch_disabled), Toast.LENGTH_SHORT).show()
                }
            },
            onEditClick = { profile ->
                val intent = Intent(requireContext(), ProfileEditActivity::class.java).apply { putExtra("EXTRA_PROFILE_ID", profile.id) }
                startActivity(intent)
            },
            onDeleteClick = { profile ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(CoreR.string.dialog_delete_title)
                    .setMessage(getString(CoreR.string.dialog_delete_message, profile.name))
                    .setPositiveButton(CoreR.string.delete) { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            ProfileManager.deleteProfile(requireContext(), profile)
                            withContext(Dispatchers.Main) {
                                if (_binding != null && isAdded) {
                                    Toast.makeText(requireContext(), getString(CoreR.string.toast_deleted), Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(CoreR.string.cancel, null)
                    .show()
            },
            onShareClick = { profile -> showShareDialog(profile) },
            onPushToTvClick = { profile ->
                TvDevicePickerBottomSheet.newInstance(profile).show(childFragmentManager, "TvDevicePicker")
            },
            onFavoriteToggle = { profile ->
                // 与详情面板星标同一套语义：原地取反落库，LiveData 回流后列表与 tab 计数一起刷新。
                val next = profile.copy(favorite = !profile.favorite)
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    ProfileManager.updateProfile(requireContext(), next)
                }
            },
            onOrderChanged = { newList ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    ProfileManager.updateProfileIndices(requireContext(), newList)
                }
            }
        )

        binding.chipGroupNodeTabs.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chip_tab_favorites -> ProfileAdapter.FILTER_FAVORITES
                R.id.chip_tab_recent -> ProfileAdapter.FILTER_RECENT
                else -> ProfileAdapter.FILTER_ALL
            }
            adapter.setFilterMode(mode)
        }

        val dpWidth = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        binding.rvProfiles.layoutManager = if (dpWidth >= 600) {
            GridLayoutManager(requireContext(), (dpWidth / 360).toInt().coerceAtLeast(2))
        } else {
            LinearLayoutManager(requireContext())
        }
        binding.rvProfiles.adapter = adapter
        (binding.rvProfiles.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        setupItemTouchHelper()
    }

    private fun setupItemTouchHelper() {
        val itemTouchHelper = androidx.recyclerview.widget.ItemTouchHelper(object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
            androidx.recyclerview.widget.ItemTouchHelper.UP or androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                    androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                target: androidx.recyclerview.widget.RecyclerView.ViewHolder
            ): Boolean {
                adapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.onDragFinished()
            }

            override fun isItemViewSwipeEnabled(): Boolean = false
            override fun isLongPressDragEnabled(): Boolean = true
        })
        itemTouchHelper.attachToRecyclerView(binding.rvProfiles)
    }

    private fun showShareDialog(profile: Profile) {
        val pin = ShareCryptoUtils.generateRandomPIN()

        // Keep the QR code usable in portrait, landscape, split-screen, and large-screen windows.
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val configuration = resources.configuration
        val windowWidth = if (configuration.screenWidthDp > 0) {
            (configuration.screenWidthDp * density).toInt()
        } else {
            displayMetrics.widthPixels
        }
        val windowHeight = if (configuration.screenHeightDp > 0) {
            (configuration.screenHeightDp * density).toInt()
        } else {
            displayMetrics.heightPixels
        }
        val minimumQrSize = (120 * density).toInt()
        val qrSize = minOf(
            (windowWidth - (128 * density).toInt()).coerceAtLeast(minimumQrSize),
            (windowHeight * 0.42f).toInt().coerceAtLeast(minimumQrSize),
            (360 * density).toInt()
        )
        val view = layoutInflater.inflate(R.layout.dialog_qr_code, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()
        val qrView = view.findViewById<ImageView>(R.id.iv_qr_code).apply {
            layoutParams = layoutParams.apply {
                width = qrSize
                height = qrSize
            }
        }
        view.findViewById<TextView>(R.id.tv_profile_name).text = profile.name

        // PIN 可自定义：预填随机值，用户改动后防抖重新加密并刷新二维码
        var sharedEncryptedPayload: String? = null
        val etSharePin = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_share_pin).apply {
            setText(pin)
        }
        val copyButton = view.findViewById<View>(R.id.btn_copy_uri).apply {
            isEnabled = false
        }
        val progress = view.findViewWithTag<View>("qr_progress")
        var pinEditJob: kotlinx.coroutines.Job? = null

        fun regenerateForPin(newPin: String) {
            pinEditJob?.cancel()
            progress.visibility = View.VISIBLE
            qrView.visibility = View.INVISIBLE
            pinEditJob = viewLifecycleOwner.lifecycleScope.launch {
                // 防抖：等待 300ms 无新输入再执行 PBKDF2 + QR 光栅化
                kotlinx.coroutines.delay(300)
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val json = Gson().toJson(profile)
                        val encrypted = ShareCryptoUtils.encrypt(json, newPin)
                        encrypted to QRUtils.generateQRCode(encrypted, qrSize, qrSize)
                    }.onFailure {
                        if (it !is kotlinx.coroutines.CancellationException) {
                            StunLogger.e("HomeFragment", "Failed to prepare share QR code", it)
                        }
                    }.getOrNull()
                }
                if (!dialog.isShowing) {
                    result?.second?.recycle()
                    return@launch
                }
                val encrypted = result?.first
                val bitmap = result?.second
                if (encrypted == null || bitmap == null) {
                    dialog.dismiss()
                    Toast.makeText(requireContext(), getString(CoreR.string.main_qr_fail), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                sharedEncryptedPayload = encrypted
                progress.visibility = View.GONE
                qrView.apply {
                    setImageBitmap(bitmap)
                    colorFilter = null
                    visibility = View.VISIBLE
                }
                copyButton.isEnabled = true
            }
        }

        etSharePin.doAfterTextChanged { text ->
            val newPin = text?.toString().orEmpty()
            if (newPin.length >= 4 && newPin != pin) {
                regenerateForPin(newPin)
            } else if (newPin.isEmpty()) {
                // 输入清空时禁用复制，防止拷贝旧密文
                copyButton.isEnabled = false
            }
        }

        regenerateForPin(pin)

        // 弹窗内"推送到 TV"与 ⋮ 菜单走同一个设备选择器；先收起分享弹窗，避免两个对话框叠层
        view.findViewById<View>(R.id.btn_push_to_tv).setOnClickListener {
            dialog.dismiss()
            TvDevicePickerBottomSheet.newInstance(profile).show(childFragmentManager, "TvDevicePicker")
        }

        view.findViewById<View>(R.id.btn_copy_uri).setOnClickListener {
            val payload = sharedEncryptedPayload
            if (payload != null) {
                val uri = "stun://$payload"
                val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Stun Node URI", uri))
                Toast.makeText(requireContext(), getString(CoreR.string.copy_success), Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun shouldUseBottomMenu(): Boolean {
        val config = resources.configuration
        return config.orientation != Configuration.ORIENTATION_LANDSCAPE &&
            config.smallestScreenWidthDp < 600
    }

    private fun prepareMainMenu() {
        if (shouldUseBottomMenu()) prepareBottomMenu() else prepareSideSheet()
    }

    private fun bindMainMenuActions(view: View, dismiss: () -> Unit) {
        view.findViewById<View>(R.id.item_add_manual).setOnClickListener {
            dismiss()
            startActivity(Intent(requireContext(), ProfileEditActivity::class.java))
        }
        view.findViewById<View>(R.id.item_scan_qr).setOnClickListener {
            dismiss()
            scanQRCode()
        }
        view.findViewById<View>(R.id.item_subscription)?.setOnClickListener {
            dismiss()
            SubscriptionBottomSheet().apply {
                setOnSyncedListener { testAllProfilesLatency() }
            }.show(childFragmentManager, "SubscriptionBottomSheet")
        }
        view.findViewById<View>(R.id.item_import).setOnClickListener {
            dismiss()
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }
        view.findViewById<View>(R.id.item_import_clipboard)?.setOnClickListener {
            dismiss()
            pasteImportFromClipboard()
        }
        view.findViewById<View>(R.id.item_speed_test).setOnClickListener {
            dismiss()
            testAllProfilesLatency()
        }
        view.findViewById<View>(R.id.item_bandwidth_test).setOnClickListener {
            dismiss()
            SpeedTestBottomSheet().show(childFragmentManager, "SpeedTestBottomSheet")
        }
        view.findViewById<View>(R.id.item_tv_remote)?.setOnClickListener {
            dismiss()
            val selected = try {
                ProfileManager.getSelectedProfile(requireContext())
            } catch (_: Exception) {
                null
            }
            TvDevicePickerBottomSheet.newInstance(selected)
                .show(childFragmentManager, "TvDevicePicker")
        }
        view.findViewById<View>(R.id.item_export).setOnClickListener {
            dismiss()
            exportLauncher.launch("stun_profiles_backup.json")
        }
        view.findViewById<View>(R.id.item_settings)?.setOnClickListener {
            dismiss()
            parentFragmentManager.commit(allowStateLoss = true) {
                replace(R.id.fragment_container, SettingsFragment())
            }
        }
    }

    private fun prepareBottomMenu(): BottomSheetDialog {
        mainBottomSheet?.let { return it }
        val bottomSheet = BottomSheetDialog(requireContext(), CoreR.style.Theme_App_BottomSheetDialog)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_main_menu, null)
        val content = view.findViewById<View>(R.id.main_menu_content)
        val initialBottomPadding = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bottom = initialBottomPadding + bars.bottom)
            insets
        }
        bindMainMenuActions(view) { bottomSheet.dismiss() }
        view.findViewById<View>(R.id.btn_close_main_menu).setOnClickListener {
            bottomSheet.dismiss()
        }
        bottomSheet.setContentView(view)
        bottomSheet.setOnShowListener {
            bottomSheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                BottomSheetBehavior.from(sheet).apply {
                    isFitToContents = true
                    skipCollapsed = true
                    state = BottomSheetBehavior.STATE_EXPANDED
                }
            }
        }
        mainBottomSheet = bottomSheet
        return bottomSheet
    }

    private fun prepareSideSheet(): SideSheetDialog {
        mainSideSheet?.let { return it }
        val sideSheet = SideSheetDialog(requireContext())
        sideSheet.setFitsSystemWindows(false)
        val config = resources.configuration
        val useWideLayout = config.smallestScreenWidthDp >= 600 && config.screenWidthDp >= 840
        val view = layoutInflater.inflate(
            if (useWideLayout) R.layout.side_sheet_main_wide else R.layout.side_sheet_main,
            null
        )
        val density = resources.displayMetrics.density
        if (useWideLayout) {
            val maxWidth = (680 * density).toInt()
            val responsiveWidth = (resources.displayMetrics.widthPixels * 0.64f).toInt()
            view.layoutParams = ViewGroup.LayoutParams(
                minOf(maxWidth, responsiveWidth),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val content = view.findViewById<View>(R.id.side_sheet_content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = bars.top + (16 * density).toInt(),
                bottom = bars.bottom + (20 * density).toInt()
            )
            insets
        }
        bindMainMenuActions(view) { sideSheet.dismiss() }
        view.findViewById<View>(R.id.btn_close_main_menu)?.setOnClickListener {
            sideSheet.dismiss()
        }
        sideSheet.setContentView(view)
        mainSideSheet = sideSheet
        return sideSheet
    }

    private fun showMainMenu() {
        if (shouldUseBottomMenu()) prepareBottomMenu().show() else prepareSideSheet().show()
    }

    private fun scanQRCode() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(CoreR.string.main_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }

    private fun testSelectedProfileLatency(delayMs: Long = 0L) {
        if (_binding == null || !isAdded || latencyTestInProgress) return
        val ctx = context ?: return
        val state = StunRepository.vpnState.value
        if (state == VpnState.CONNECTING || state == VpnState.RECONNECTING) return
        val testConnected = state == VpnState.CONNECTED
        val generation = ++latencyTestGeneration
        latencyTestJob?.cancel()
        latencyTestJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            if (delayMs > 0) delay(delayMs)
            if (!isActive) return@launch
            var profileId = ""
            try {
                val selectedProfile = ProfileManager.getSelectedProfile(ctx)
                profileId = selectedProfile.id
                if (profileId.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, getString(CoreR.string.error_no_profile_selected), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (_binding != null && isAdded) {
                        latencyTestInProgress = true
                        binding.tvStatusSubtitle.visibility = View.VISIBLE
                        binding.tvStatusSubtitle.text = getString(CoreR.string.main_testing_latency)
                        updateStatusContentDescription()
                    }
                }

                if (testConnected) {
                    // This measures the IP lookup request over current app routing, not SSH RTT.
                    val result = ExitIpProbe().run()
                    val delayLabel = result?.let { "${it.latencyMs} ms" }
                        ?: ctx.getString(CoreR.string.latency_network_error)
                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) {
                        if (_binding != null && isAdded && activeBottomProfile?.id == profileId &&
                            StunRepository.vpnState.value == VpnState.CONNECTED) {
                            adapter.updateDelay(profileId, delayLabel)
                            latestLatencyLabel = delayLabel
                            setExitLocation(result)
                            latencyTestInProgress = false
                            renderConnectionIdentity()
                        }
                    }
                } else {
                    // Disconnected: retain the existing native node-connectivity test.
                    val configJson = VpnConfigBuilder.buildMySshConfig(ctx, selectedProfile, 1080, 53)
                    val request = JSONArray().put(JSONObject().put("id", profileId).put("config", JSONObject(configJson)))
                    val response = StunRepository.proxy.pingNodes(request.toString(), "http://cp.cloudflare.com/generate_204", 8000L)
                    val result = parsePingResults(response)[profileId] ?: ctx.getString(CoreR.string.latency_network_error)
                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) {
                        if (_binding != null && isAdded && activeBottomProfile?.id == profileId && !isVpnRunning) {
                            adapter.updateDelay(profileId, result)
                            renderDisconnectedIdentity(result)
                            updateStatusContentDescription()
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                val result = ctx.getString(CoreR.string.latency_network_error)
                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    if (_binding != null && isAdded) {
                        if (profileId.isNotEmpty()) adapter.updateDelay(profileId, result)
                        latestLatencyLabel = result
                        if (testConnected) {
                            setExitLocation(null)
                        } else {
                            renderDisconnectedIdentity(result)
                        }
                        updateStatusContentDescription()
                    }
                }
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (generation == latencyTestGeneration) {
                        latencyTestInProgress = false
                        // 走到这里出口探测一定有结论了（查到 / 没查到 / 压根没跑起来）。
                        // 在这一处统一收口，好过在每条 return 分支上都记得标一记。
                        if (testConnected) exitProbeFinished = true
                        if (_binding != null && isAdded) renderConnectionIdentity()
                    }
                }
            }
        }
    }

    private fun testAllProfilesLatency() {
        val profiles = adapter.getProfiles()
        if (profiles.isEmpty()) return
        Toast.makeText(requireContext(), getString(CoreR.string.speed_test_started), Toast.LENGTH_SHORT).show()
        profiles.forEach { adapter.updateDelay(it.id, "...") }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reqArray = JSONArray()
                profiles.forEach { p ->
                    val configJson = VpnConfigBuilder.buildMySshConfig(requireContext(), p, 1080, 53)
                    reqArray.put(JSONObject().put("id", p.id).put("config", JSONObject(configJson)))
                }
                // 与“选定节点测速”走同一套 Go 测速，目标/超时/方法论完全一致
                val jsonResStr = StunRepository.proxy.pingNodes(reqArray.toString(), "http://cp.cloudflare.com/generate_204", 8000L)
                val results = parsePingResults(jsonResStr)
                withContext(Dispatchers.Main) {
                    profiles.forEach { p ->
                        adapter.updateDelay(p.id, results[p.id] ?: getString(CoreR.string.latency_network_error))
                    }
                    Toast.makeText(requireContext(), getString(CoreR.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), getString(CoreR.string.speed_test_error, e.message), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 将 Go PingNodes 返回的结构化 JSON（[]PingResult）解析为 id -> 展示字符串。
     * 实现已下沉到 :core 的 [PingResults]，与 car / tv / xr 共用同一份
     * （此前 car/xr 是「一律网络错误」的退化版，现已一并升级）。
     */
    private fun parsePingResults(jsonStr: String): Map<String, String> =
        PingResults.parse(requireContext(), jsonStr)


    private fun validateSelectedProfile(profile: Profile): Boolean {
        if (profile.id.isEmpty() || profile.sshAddr.isEmpty()) {
            Toast.makeText(requireContext(), getString(CoreR.string.error_no_profile_selected), Toast.LENGTH_SHORT).show()
            return false
        }
        if (profile.authType == Profile.AUTH_TYPE_PASSWORD && profile.pass.isEmpty()) {
            Toast.makeText(requireContext(), getString(CoreR.string.error_field_required), Toast.LENGTH_SHORT).show()
            return false
        }
        if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY) {
            if (profile.privateKey.isEmpty()) {
                Toast.makeText(requireContext(), getString(CoreR.string.error_field_required), Toast.LENGTH_SHORT).show()
                return false
            }
            val checkResult = myssh.Myssh.checkIfKeyEncrypted(profile.privateKey)
            if (checkResult == 1L) {
                val decryptedPass = KeystoreUtils.decrypt(profile.keyPass)
                if (decryptedPass.isEmpty() || !myssh.Myssh.validatePassphrase(profile.privateKey, decryptedPass)) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(CoreR.string.error_invalid_key_password)
                        .setMessage(CoreR.string.error_invalid_key_password)
                        .setPositiveButton(CoreR.string.ok, null)
                        .show()
                    return false
                }
            } else if (checkResult == 2L) {
                Toast.makeText(requireContext(), getString(CoreR.string.error_invalid_private_key), Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    private fun handleStartStop() {
        if (_binding == null) return // 防御：视图销毁后异步回调不应再触碰 binding
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (currentState == VpnState.CONNECTED || currentState == VpnState.RECONNECTING) {
            if (isStopping) {
                // 断开还在收尾（状态尚未落到 DISCONNECTED），用户又点了连接：
                // 把重连意图交给服务排队（MyVpnService.pendingStart），而不是再发一次 STOP ——
                // 旧行为会把这次点击吞掉，留下"UI 未连接但 VPN 图标还亮着"的窗口。
                if (SettingsManager.getServiceMode(requireContext()) == SettingsManager.SERVICE_MODE_TPROXY) startTProxyService() else startVpnService()
                return
            }
            isStopping = true
            binding.progressBar.visibility = View.VISIBLE
            if (SettingsManager.getServiceMode(requireContext()) == SettingsManager.SERVICE_MODE_TPROXY) stopTProxyService() else stopVpnService()
        } else if (currentState != VpnState.CONNECTING && !startRequestInProgress) {
            startRequestInProgress = true
            binding.fabStartStop.isEnabled = false
            binding.progressBar.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val profile = withContext(Dispatchers.IO) { ProfileManager.getSelectedProfile(requireContext()) }
                if (!validateSelectedProfile(profile)) {
                    finishStartRequest()
                    return@launch
                }
                
                isStopping = false
                if (SettingsManager.getServiceMode(requireContext()) == SettingsManager.SERVICE_MODE_TPROXY) {
                    if (!withContext(Dispatchers.IO) { ExecUtils.checkIsRootPermission() }) {
                        Snackbar.make(binding.root, getString(CoreR.string.error_root_required), Snackbar.LENGTH_LONG).show()
                        finishStartRequest()
                        return@launch
                    }
                }
                applyShizukuKeepAlive()
                checkAndRequestNotificationPermission()
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (VpnControls.needsNotificationPermission(requireContext())) {
            notificationPermissionLauncher.launch(VpnControls.notificationPermission)
        } else {
            startSelectedService()
        }
    }

    private fun startSelectedService() {
        val mode = SettingsManager.getServiceMode(requireContext())
        if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
            startTProxyService()
        } else {
            val intent = VpnService.prepare(requireContext())
            if (intent != null) vpnLauncher.launch(intent) else startVpnService()
        }
    }

    private fun finishStartRequest() {
        startRequestInProgress = false
        if (_binding == null || !isAdded) return
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        updateUiState(state)
    }

    private fun launchServiceWithAction(serviceClass: Class<*>, actionStr: String) {
        try {
            val intent = Intent(requireContext(), serviceClass).apply { action = actionStr }
            ContextCompat.startForegroundService(requireContext(), intent)
        } catch (e: Exception) {
            finishStartRequest()
            StunLogger.e("HomeFragment", "Unable to start service", e)
            if (_binding != null && isAdded) {
                Snackbar.make(
                    binding.root,
                    getString(CoreR.string.error_prefix, e.localizedMessage ?: e.javaClass.simpleName),
                    Snackbar.LENGTH_LONG
                ).setAnchorView(binding.bottomContainer).show()
            }
        }
    }

    private fun updateStatusContentDescription() {
        if (_binding == null) return
        binding.layoutStatus.contentDescription = listOfNotNull(
            binding.tvStatus.text?.toString()?.takeIf { it.isNotBlank() },
            binding.tvStatusSubtitle.text?.toString()?.takeIf {
                binding.tvStatusSubtitle.isVisible && it.isNotBlank()
            }
        ).joinToString(", ")
        binding.bottomContainer.contentDescription = binding.layoutStatus.contentDescription
        if (binding.tvBottomExit.visibility == View.VISIBLE) {
            binding.bottomContainer.contentDescription = "${binding.layoutStatus.contentDescription}, ${binding.tvBottomExit.text}"
        }
    }

    private fun stopVpnService() = launchServiceWithAction(MyVpnService::class.java, MyVpnService.ACTION_STOP)
    private fun startVpnService() = launchServiceWithAction(MyVpnService::class.java, MyVpnService.ACTION_START)
    private fun stopTProxyService() = launchServiceWithAction(MyTransparentProxyService::class.java, MyTransparentProxyService.ACTION_STOP)
    private fun startTProxyService() = launchServiceWithAction(MyTransparentProxyService::class.java, MyTransparentProxyService.ACTION_START)

    private fun exportProfilesToUri(uri: android.net.Uri) {
        showPinDialog(
            title = getString(CoreR.string.encrypt_backup_title),
            hint = getString(CoreR.string.pin_new_hint)
        ) { pin, _ ->
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val profiles = ProfileManager.getProfiles(requireContext())
                        val json = Gson().toJson(profiles)
                        val encryptedJson = ShareCryptoUtils.encrypt(json, pin)
                        requireContext().contentResolver.openOutputStream(uri)?.use { it.write(encryptedJson.toByteArray()) }
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(CoreR.string.export_success), Toast.LENGTH_SHORT).show() }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(CoreR.string.export_failed, e.message), Toast.LENGTH_SHORT).show() }
                    }
                }
            true
        }
    }

    private fun importProfilesFromUri(uri: android.net.Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileContent = requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
                if (fileContent == null) return@launch

                withContext(Dispatchers.Main) {
                    if (ShareCryptoUtils.isEncryptedPayload(fileContent)) {
                        showPinDialog(
                            title = getString(CoreR.string.decrypt_backup_title),
                            hint = getString(CoreR.string.pin_hint)
                        ) { pin, input ->
                                val decryptedJson = ShareCryptoUtils.decrypt(fileContent, pin)
                                if (decryptedJson != null) {
                                    processImportJson(decryptedJson)
                                    true
                                } else {
                                    input.error = getString(CoreR.string.error_invalid_pin)
                                    false
                                }
                        }
                    } else {
                        // User said they don't need backward compatibility, but in case they try to import an old plaintext file, we can still parse it or reject it.
                        // Since they said "I do not need backward compatibility", let's just reject it for strict security.
                        Toast.makeText(requireContext(), getString(CoreR.string.error_unsupported_backup), Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(CoreR.string.import_failed, e.message), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun processImportJson(json: String) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<Profile>>() {}.type
                val profiles: List<Profile> = Gson().fromJson(json, type)
                val existing = ProfileManager.getProfiles(requireContext())
                var count = 0
                profiles.forEach { profile ->
                    if (existing.none { it.name == profile.name && it.sshAddr == profile.sshAddr }) {
                        ProfileManager.addProfile(requireContext(), profile.copy(id = UUID.randomUUID().toString()))
                        count++
                    }
                }
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(CoreR.string.import_success, count), Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(CoreR.string.import_failed, e.message), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        latencyTestJob?.cancel()
        latencyTestJob = null
        clipboardCheckJob?.cancel()
        clipboardCheckJob = null
        stopGlobe()
        globeDownloadJob?.cancel()
        globeDownloadJob = null
        globeDownloading = false
        // 地理库句柄整页只开一次，销毁时统一释放（close 只关文件句柄，已建的 mmap 仍然有效，
        // 所以即便此刻还有后台构建在读也不会踩空 —— 映射由 GC 回收）。
        globeResolver?.close()
        globeResolver = null
        synchronized(globeRatesLock) { globeRates.clear() }
        mainSideSheet?.dismiss()
        mainSideSheet = null
        mainBottomSheet?.dismiss()
        mainBottomSheet = null
        connectionDetailsDialog?.dismiss()
        connectionDetailsDialog = null
        connectionDetailsBinding = null
        _binding?.bottomContainer?.onSwipeUp = null
        _binding = null
        StunLogger.errorListener = null
    }
}
