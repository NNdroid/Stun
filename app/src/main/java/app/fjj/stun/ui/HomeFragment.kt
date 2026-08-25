package app.fjj.stun.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
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
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.databinding.FragmentHomeBinding
import app.fjj.stun.repo.*
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.service.VpnConfigBuilder
import app.fjj.stun.util.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
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
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.*

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: ProfileAdapter

    private var isVpnRunning = false
    private var isStopping = false
    private var latencyTestJob: kotlinx.coroutines.Job? = null

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            checkAndRequestNotificationPermission()
        } else {
            Toast.makeText(requireContext(), getString(CoreR.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSelectedService()
        } else {
            Snackbar.make(binding.root, getString(CoreR.string.notification_permission_required), Snackbar.LENGTH_LONG)
                .setAction(getString(CoreR.string.retry)) { checkAndRequestNotificationPermission() }
                .setAnchorView(binding.bottomContainer)
                .show()
        }
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
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = getString(CoreR.string.pin_hint)
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(CoreR.string.enter_pin_title))
            .setView(input)
            .setPositiveButton(getString(CoreR.string.ok)) { _, _ ->
                val pin = input.text.toString()
                val decryptedJson = ShareCryptoUtils.decrypt(encryptedPayload, pin)
                if (decryptedJson != null) {
                    importProfileFromJsonString(decryptedJson)
                } else {
                    Toast.makeText(requireContext(), getString(CoreR.string.error_invalid_pin), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(CoreR.string.cancel), null)
            .show()
    }

    private fun importProfileFromJsonBase64(base64Str: String) {
        try {
            val decodedBytes = Base64.decode(base64Str, Base64.DEFAULT)
            val jsonString = String(decodedBytes, Charsets.UTF_8)
            importProfileFromJsonString(jsonString)
        } catch (e: Exception) {
            StunLogger.e("HomeFragment", "Scan QR Code failed", e)
            Toast.makeText(requireContext(), getString(CoreR.string.invalid_qr), Toast.LENGTH_SHORT).show()
        }
    }

    private fun importProfileFromJsonString(jsonString: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val profile = Gson().fromJson(jsonString, Profile::class.java)
                
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
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            (requireActivity() as MainActivity).openDrawer()
        }
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_tools -> { showSideSheet(); true }
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
        val initialRvPadding = binding.rvProfiles.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(left = systemBars.left, right = systemBars.right)
            binding.appBar.updatePadding(top = systemBars.top)
            binding.rvProfiles.updatePadding(bottom = initialRvPadding + systemBars.bottom)
            binding.bottomContainer.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupListeners() {
        binding.fabStartStop.setOnClickListener {
            handleStartStop()
        }

        binding.tvStatus.setOnClickListener {
            testSelectedProfileLatency()
        }
        binding.tvStatusSubtitle.setOnClickListener {
            testSelectedProfileLatency()
        }
        binding.layoutStatus.setOnClickListener {
            testSelectedProfileLatency()
        }

        StunLogger.errorListener = { tag, msg, _ ->
            activity?.runOnUiThread {
                Snackbar.make(
                    binding.root,
                    if (app.fjj.stun.BuildConfig.DEBUG) "[$tag] $msg" else msg,
                    Snackbar.LENGTH_LONG
                ).setAnchorView(binding.bottomContainer).show()
            }
        }
    }

    private fun observeViewModel() {
        val viewModel: app.fjj.stun.ui.viewmodel.MainViewModel by viewModels()
        viewModel.profilesLiveData.observe(viewLifecycleOwner) { profiles ->
            if (_binding == null || !isAdded) return@observe
            val ctx = context ?: return@observe
            binding.layoutEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            val selectedId = SettingsManager.getSelectedProfileId(ctx)
            adapter.updateProfiles(profiles, selectedId)
        }

        StunRepository.txRate.observe(viewLifecycleOwner) { rate ->
            if (_binding == null || !isAdded) return@observe
            if (isVpnRunning) binding.tvUpRate.text = getString(CoreR.string.traffic_up_format, AppUtils.formatBytes(rate))
        }

        StunRepository.rxRate.observe(viewLifecycleOwner) { rate ->
            if (_binding == null || !isAdded) return@observe
            if (isVpnRunning) binding.tvDownRate.text = getString(CoreR.string.traffic_down_format, AppUtils.formatBytes(rate))
        }
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
    }

    private fun updateUiState(state: VpnState?) {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return
        
        binding.fabStartStop.clearAnimation()
        
        val errorColor = getThemeColor("colorError", android.graphics.Color.RED)
        val successColor = getThemeColor("colorPrimary", android.graphics.Color.GREEN)
        val warningColor = getThemeColor("colorTertiary", android.graphics.Color.YELLOW)

        when (state) {
            VpnState.DISCONNECTED -> {
                isVpnRunning = false
                latencyTestJob?.cancel()
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_play)
                binding.fabStartStop.text = ctx.getString(CoreR.string.connect)
                setFabColor(getThemeColor("colorPrimaryContainer", android.graphics.Color.LTGRAY),
                    getThemeColor("colorOnPrimaryContainer", android.graphics.Color.BLACK))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                binding.tvStatus.text = ctx.getString(CoreR.string.main_disconnected)
                binding.tvStatusSubtitle.visibility = View.GONE
                binding.tvStatusSubtitle.text = ""
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.GONE
                if (isStopping) isStopping = false
            }
            VpnState.CONNECTING -> {
                isVpnRunning = false
                latencyTestJob?.cancel()
                binding.fabStartStop.isEnabled = false
                binding.fabStartStop.setIconResource(R.drawable.ic_sync)
                binding.fabStartStop.text = ctx.getString(CoreR.string.main_connecting)
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(warningColor)
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutTraffic.visibility = View.GONE
                binding.tvStatusSubtitle.visibility = View.GONE
                binding.tvStatusSubtitle.text = ""
                startFabLoadingAnimation()
                binding.tvStatus.text = ctx.getString(CoreR.string.main_connecting)
            }
            VpnState.CONNECTED -> {
                isVpnRunning = true
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_pause)
                binding.fabStartStop.text = ctx.getString(CoreR.string.disconnect)
                setFabColor(getThemeColor("colorSecondaryContainer", android.graphics.Color.DKGRAY),
                    getThemeColor("colorOnSecondaryContainer", android.graphics.Color.WHITE))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                binding.tvStatus.text = ctx.getString(CoreR.string.main_connected)
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.VISIBLE
                playFabSuccessAnimation()
                testSelectedProfileLatency(delayMs = 3000L)
            }
            VpnState.RECONNECTING -> {
                isVpnRunning = false
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_pause)
                binding.fabStartStop.text = ctx.getString(CoreR.string.disconnect)
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(warningColor)
                binding.tvStatus.text = ctx.getString(CoreR.string.main_reconnecting)
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutTraffic.visibility = View.VISIBLE
            }
            VpnState.ERROR -> {
                isVpnRunning = false
                latencyTestJob?.cancel()
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_play)
                binding.fabStartStop.text = ctx.getString(CoreR.string.connect)
                setFabColor(getThemeColor("colorPrimaryContainer", android.graphics.Color.LTGRAY),
                    getThemeColor("colorOnPrimaryContainer", android.graphics.Color.BLACK))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                binding.tvStatus.text = ctx.getString(CoreR.string.main_connection_failed)
                binding.tvStatusSubtitle.visibility = View.GONE
                binding.tvStatusSubtitle.text = ""
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
    }

    private fun getThemeColor(attrName: String, default: Int): Int {
        val context = context ?: return default
        val attrId = context.resources.getIdentifier(attrName, "attr", context.packageName).takeIf { it != 0 }
            ?: context.resources.getIdentifier(attrName, "attr", "android").takeIf { it != 0 }
            ?: return default
            
        val typedValue = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attrId, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            default
        }
    }

    private fun setFabColor(bg: Int, fg: Int) {
        binding.fabStartStop.backgroundTintList = android.content.res.ColorStateList.valueOf(bg)
        binding.fabStartStop.setTextColor(fg)
        binding.fabStartStop.iconTint = android.content.res.ColorStateList.valueOf(fg)
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

    private fun setupRecyclerView() {
        val selectedId = SettingsManager.getSelectedProfileId(requireContext())
        adapter = ProfileAdapter(
            selectedProfileId = selectedId,
            onProfileClick = { profile ->
                if (!isVpnRunning) {
                    SettingsManager.setSelectedProfileId(requireContext(), profile.id)
                    adapter.updateProfiles(adapter.getProfiles(), profile.id)
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
                lifecycleScope.launch(Dispatchers.IO) { ProfileManager.deleteProfile(requireContext(), profile) }
            },
            onShareClick = { profile -> showShareDialog(profile) },
            onPushToTvClick = { profile ->
                TvDevicePickerBottomSheet.newInstance(profile).show(childFragmentManager, "TvDevicePicker")
            },
            onOrderChanged = { newList ->
                lifecycleScope.launch(Dispatchers.IO) {
                    ProfileManager.updateProfileIndices(requireContext(), newList)
                }
            }
        )

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
        val json = Gson().toJson(profile)
        val pin = ShareCryptoUtils.generateRandomPIN()
        val encryptedPayload = ShareCryptoUtils.encrypt(json, pin)
        
        // Increase QR size to occupy more space
        val displayMetrics = resources.displayMetrics
        val qrSize = (displayMetrics.widthPixels * 0.85).toInt()
        val bitmap = QRUtils.generateQRCode(encryptedPayload, qrSize, qrSize)

        if (bitmap != null) {
            val view = layoutInflater.inflate(R.layout.dialog_qr_code, null)
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setCancelable(true) // Explicitly allow click outside to dismiss
                .create()

            view.findViewById<TextView>(R.id.tv_profile_name).text = profile.name
            view.findViewById<TextView>(R.id.tv_share_pin).text = getString(CoreR.string.share_pin_format, pin)
            view.findViewById<ImageView>(R.id.iv_qr_code).apply {
                setImageBitmap(bitmap)
                colorFilter = null
            }

            view.findViewById<View>(R.id.btn_push_to_tv).setOnClickListener {
                dialog.dismiss()
                TvDevicePickerBottomSheet.newInstance(profile).show(childFragmentManager, "TvDevicePicker")
            }

            dialog.show()
        } else {
            Toast.makeText(requireContext(), getString(CoreR.string.main_qr_fail), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSideSheet() {
        val sideSheet = SideSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.side_sheet_main, null)
        
        view.findViewById<View>(R.id.item_add_manual).setOnClickListener {
            sideSheet.dismiss()
            startActivity(Intent(requireContext(), ProfileEditActivity::class.java))
        }
        
        view.findViewById<View>(R.id.item_scan_qr).setOnClickListener {
            sideSheet.dismiss()
            scanQRCode()
        }
        
        view.findViewById<View>(R.id.item_import).setOnClickListener {
            sideSheet.dismiss()
            importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
        }

        view.findViewById<View>(R.id.item_speed_test).setOnClickListener {
            sideSheet.dismiss()
            testAllProfilesLatency()
        }

        view.findViewById<View>(R.id.item_tv_remote)?.setOnClickListener {
            sideSheet.dismiss()
            val selected = try {
                ProfileManager.getSelectedProfile(requireContext())
            } catch (_: Exception) {
                null
            }
            TvDevicePickerBottomSheet.newInstance(selected).show(childFragmentManager, "TvDevicePicker")
        }
        
        view.findViewById<View>(R.id.item_export).setOnClickListener {
            sideSheet.dismiss()
            exportLauncher.launch("stun_profiles_backup.json")
        }
        
        sideSheet.setContentView(view)
        sideSheet.show()
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
        if (_binding == null || !isAdded) return
        val ctx = context ?: return

        latencyTestJob?.cancel()
        latencyTestJob = lifecycleScope.launch(Dispatchers.IO) {
            if (delayMs > 0) {
                delay(delayMs)
            }
            if (!isActive || _binding == null || !isAdded) return@launch
            var profileId = ""
            try {
                // 读取已选节点会查 Room 数据库，必须放在 IO 线程，不能留在主线程
                val selectedProfile = ProfileManager.getSelectedProfile(ctx)
                profileId = selectedProfile.id
                if (selectedProfile.id.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, getString(CoreR.string.error_no_profile_selected), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                withContext(Dispatchers.Main) {
                    if (_binding != null && isAdded) {
                        binding.tvStatusSubtitle.visibility = View.VISIBLE
                        binding.tvStatusSubtitle.text = getString(CoreR.string.main_testing_latency)
                    }
                }

                if (isVpnRunning) {
                    // VPN 已连接状态：通过当前建立的 VPN 隧道直接测速并获取真实出口公网 IP
                    val start = System.currentTimeMillis()
                    var publicIp = ""
                    var locationDesc = ""
                    var latencyMs = -1L

                    // 1. 优先使用 ip-api.com 查询详细公网 IP 与地理位置（国家、城市、国旗）
                    try {
                        val url = URL("http://ip-api.com/json")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "curl/7.88.1")
                        if (conn.responseCode == 200) {
                            val jsonText = conn.inputStream.bufferedReader().use { it.readText().trim() }
                            val obj = JSONObject(jsonText)
                            publicIp = obj.optString("query", "")
                            val country = obj.optString("country", "")
                            val countryCode = obj.optString("countryCode", "")
                            val city = obj.optString("city", "")
                            val flag = getCountryEmojiFlag(countryCode)
                            
                            val locParts = mutableListOf<String>()
                            if (flag.isNotEmpty()) locParts.add(flag)
                            if (city.isNotEmpty()) locParts.add(city)
                            if (country.isNotEmpty() && country != city) locParts.add(country)
                            locationDesc = locParts.joinToString(" ")
                            latencyMs = System.currentTimeMillis() - start
                        }
                    } catch (_: Exception) {}

                    // 2. 备用双栈 GeoIP 接口 (api.ip.sb)
                    if (publicIp.isEmpty()) {
                        try {
                            val start2 = System.currentTimeMillis()
                            val url2 = URL("https://api.ip.sb/geoip")
                            val conn2 = url2.openConnection() as HttpURLConnection
                            conn2.connectTimeout = 4000
                            conn2.readTimeout = 4000
                            conn2.setRequestProperty("User-Agent", "curl/7.88.1")
                            if (conn2.responseCode == 200) {
                                val jsonText = conn2.inputStream.bufferedReader().use { it.readText().trim() }
                                val obj = JSONObject(jsonText)
                                publicIp = obj.optString("ip", "")
                                val country = obj.optString("country", "")
                                val countryCode = obj.optString("country_code", "")
                                val city = obj.optString("city", "")
                                val flag = getCountryEmojiFlag(countryCode)
                                
                                val locParts = mutableListOf<String>()
                                if (flag.isNotEmpty()) locParts.add(flag)
                                if (city.isNotEmpty()) locParts.add(city)
                                if (country.isNotEmpty() && country != city) locParts.add(country)
                                locationDesc = locParts.joinToString(" ")
                                latencyMs = System.currentTimeMillis() - start2
                            }
                        } catch (_: Exception) {}
                    }

                    // 3. 备用纯 IP 双栈接口 (api64.ipify.org)
                    if (publicIp.isEmpty()) {
                        try {
                            val start3 = System.currentTimeMillis()
                            val url3 = URL("https://api64.ipify.org")
                            val conn3 = url3.openConnection() as HttpURLConnection
                            conn3.connectTimeout = 4000
                            conn3.readTimeout = 4000
                            if (conn3.responseCode == 200) {
                                publicIp = conn3.inputStream.bufferedReader().use { it.readText().trim() }
                                latencyMs = System.currentTimeMillis() - start3
                            }
                        } catch (_: Exception) {}
                    }

                    val delayStr = if (latencyMs > 0) "$latencyMs ms" else getString(CoreR.string.latency_network_error)
                    val ipDisplayStr = when {
                        publicIp.isNotEmpty() && locationDesc.isNotEmpty() -> "$publicIp · $locationDesc"
                        publicIp.isNotEmpty() -> publicIp
                        else -> ""
                    }

                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) {
                        if (_binding != null && isAdded) {
                            adapter.updateDelay(selectedProfile.id, delayStr)
                            binding.tvStatus.text = "${ctx.getString(CoreR.string.main_connected)} ($delayStr)"
                            if (ipDisplayStr.isNotEmpty()) {
                                binding.tvStatusSubtitle.visibility = View.VISIBLE
                                binding.tvStatusSubtitle.text = ipDisplayStr
                            } else {
                                binding.tvStatusSubtitle.visibility = View.GONE
                            }
                        }
                    }
                } else {
                    // 未连接状态：通过底层 Go 引擎 Ping 节点并测试节点连通性
                    val configJson = VpnConfigBuilder.buildMySshConfig(ctx, selectedProfile, 1080, 53)
                    val reqArray = JSONArray().put(
                        JSONObject().put("id", selectedProfile.id).put("config", JSONObject(configJson))
                    )
                    val jsonResStr = StunRepository.proxy.pingNodes(reqArray.toString(), "http://cp.cloudflare.com/generate_204", 8000L)
                    val results = parsePingResults(jsonResStr)
                    val result = results[selectedProfile.id] ?: ctx.getString(CoreR.string.latency_network_error)
                    
                    if (!isActive) return@launch
                    withContext(Dispatchers.Main) {
                        if (_binding != null && isAdded) {
                            adapter.updateDelay(selectedProfile.id, result)
                            binding.tvStatus.text = "${ctx.getString(CoreR.string.main_disconnected)} ($result)"
                            binding.tvStatusSubtitle.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val result = ctx.getString(CoreR.string.latency_network_error)
                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    if (_binding != null && isAdded) {
                        if (profileId.isNotEmpty()) adapter.updateDelay(profileId, result)
                        val statusPrefix = if (isVpnRunning) ctx.getString(CoreR.string.main_connected) else ctx.getString(CoreR.string.main_disconnected)
                        binding.tvStatus.text = "$statusPrefix ($result)"
                        binding.tvStatusSubtitle.visibility = View.GONE
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

        lifecycleScope.launch(Dispatchers.IO) {
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
     * 取代原先依赖 "result" 字段的脆弱字符串解析；错误按 errorType 本地化着色。
     */
    private fun parsePingResults(jsonStr: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "")
                if (id.isEmpty()) continue
                if (obj.optBoolean("ok", false)) {
                    map[id] = "${obj.optLong("latencyMs", 0)} ms"
                } else {
                    map[id] = when (obj.optString("errorType", "other")) {
                        "timeout" -> getString(CoreR.string.latency_timeout)
                        "connrefused" -> getString(CoreR.string.latency_conn_refused)
                        "tls" -> getString(CoreR.string.latency_ssl_error)
                        "dns" -> getString(CoreR.string.latency_dns_error)
                        "http" -> "HTTP ${obj.optString("error", "")}"
                        else -> getString(CoreR.string.latency_network_error)
                    }
                }
            }
        } catch (_: Exception) {
            // 解析失败时返回空 map，调用方按“网络错误”兜底
        }
        return map
    }

    private fun getCountryEmojiFlag(countryCode: String?): String {
        if (countryCode == null || countryCode.length != 2) return ""
        val code = countryCode.uppercase()
        val first = Character.codePointAt(code, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(code, 1) - 0x41 + 0x1F1E6
        if (first < 0x1F1E6 || first > 0x1F1FF || second < 0x1F1E6 || second > 0x1F1FF) return ""
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

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
        val currentState = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (currentState == VpnState.CONNECTED || currentState == VpnState.RECONNECTING) {
            isStopping = true
            binding.progressBar.visibility = View.VISIBLE
            if (SettingsManager.getServiceMode(requireContext()) == SettingsManager.SERVICE_MODE_TPROXY) stopTProxyService() else stopVpnService()
        } else if (currentState != VpnState.CONNECTING) {
            lifecycleScope.launch {
                val profile = withContext(Dispatchers.IO) { ProfileManager.getSelectedProfile(requireContext()) }
                if (!validateSelectedProfile(profile)) return@launch
                
                isStopping = false
                if (SettingsManager.getServiceMode(requireContext()) == SettingsManager.SERVICE_MODE_TPROXY) {
                    if (!withContext(Dispatchers.IO) { ExecUtils.checkIsRootPermission() }) {
                        Snackbar.make(binding.root, getString(CoreR.string.error_root_required), Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                }
                applyShizukuKeepAlive()
                checkAndRequestNotificationPermission()
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startSelectedService()
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

    private fun launchServiceWithAction(serviceClass: Class<*>, actionStr: String) {
        val intent = Intent(requireContext(), serviceClass).apply { action = actionStr }
        requireContext().startService(intent)
    }

    private fun stopVpnService() = launchServiceWithAction(MyVpnService::class.java, MyVpnService.ACTION_STOP)
    private fun startVpnService() = launchServiceWithAction(MyVpnService::class.java, MyVpnService.ACTION_START)
    private fun stopTProxyService() = launchServiceWithAction(MyTransparentProxyService::class.java, MyTransparentProxyService.ACTION_STOP)
    private fun startTProxyService() = launchServiceWithAction(MyTransparentProxyService::class.java, MyTransparentProxyService.ACTION_START)

    private fun exportProfilesToUri(uri: android.net.Uri) {
        val input = android.widget.EditText(requireContext())
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = getString(CoreR.string.pin_new_hint)
        val padding = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(padding, padding, padding, padding)
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(CoreR.string.encrypt_backup_title))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(CoreR.string.ok)) { _, _ ->
                val pin = input.text.toString()
                if (pin.isEmpty()) {
                    Toast.makeText(requireContext(), getString(CoreR.string.error_pin_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch(Dispatchers.IO) {
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
            }
            .setNegativeButton(getString(CoreR.string.cancel), null)
            .show()
    }

    private fun importProfilesFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fileContent = requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                }
                if (fileContent == null) return@launch

                withContext(Dispatchers.Main) {
                    if (ShareCryptoUtils.isEncryptedPayload(fileContent)) {
                        val input = android.widget.EditText(requireContext())
                        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        input.hint = "PIN"
                        val padding = (16 * resources.displayMetrics.density).toInt()
                        input.setPadding(padding, padding, padding, padding)
                        
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(CoreR.string.decrypt_backup_title))
                            .setView(input)
                            .setCancelable(false)
                            .setPositiveButton(getString(CoreR.string.ok)) { _, _ ->
                                val pin = input.text.toString()
                                val decryptedJson = ShareCryptoUtils.decrypt(fileContent, pin)
                                if (decryptedJson != null) {
                                    processImportJson(decryptedJson)
                                } else {
                                    Toast.makeText(requireContext(), getString(CoreR.string.error_invalid_pin), Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setNegativeButton(getString(CoreR.string.cancel), null)
                            .show()
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
        lifecycleScope.launch(Dispatchers.IO) {
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
        _binding = null
        StunLogger.errorListener = null
    }
}
