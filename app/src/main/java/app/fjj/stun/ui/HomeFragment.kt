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
import app.fjj.stun.databinding.FragmentHomeBinding
import app.fjj.stun.repo.*
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

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            checkAndRequestNotificationPermission()
        } else {
            Toast.makeText(requireContext(), getString(R.string.vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSelectedService()
        } else {
            Snackbar.make(binding.root, getString(R.string.notification_permission_required), Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.retry)) { checkAndRequestNotificationPermission() }
                .setAnchorView(binding.bottomContainer)
                .show()
        }
    }

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result ->
        if (result.contents != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val decodedBytes = Base64.decode(result.contents, Base64.DEFAULT)
                    val jsonString = String(decodedBytes, Charsets.UTF_8)
                    val profile = Gson().fromJson(jsonString, Profile::class.java)
                    
                    val existing = ProfileManager.getProfiles(requireContext())
                    if (existing.any { it.name == profile.name && it.sshAddr == profile.sshAddr }) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), getString(R.string.profile_already_exists), Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }

                    val newProfile = profile.copy(id = UUID.randomUUID().toString())
                    ProfileManager.addProfile(requireContext(), newProfile)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.profile_added, profile.name), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    StunLogger.e("HomeFragment", "Scan QR Code failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), getString(R.string.invalid_qr), Toast.LENGTH_SHORT).show()
                    }
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
            binding.layoutEmpty.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
            val selectedId = SettingsManager.getSelectedProfileId(requireContext())
            adapter.updateProfiles(profiles, selectedId)
        }

        StunRepository.txRate.observe(viewLifecycleOwner) { rate ->
            if (isVpnRunning) binding.tvUpRate.text = "▲ ${AppUtils.formatBytes(rate)}/s"
        }

        StunRepository.rxRate.observe(viewLifecycleOwner) { rate ->
            if (isVpnRunning) binding.tvDownRate.text = "▼ ${AppUtils.formatBytes(rate)}/s"
        }
    }

    private fun observeVpnState() {
        StunRepository.vpnState.observe(viewLifecycleOwner) { state ->
            updateUiState(state)
        }
    }

    private fun updateUiState(state: VpnState?) {
        binding.fabStartStop.clearAnimation()
        
        val errorColor = getThemeColor("colorError", android.graphics.Color.RED)
        val successColor = getThemeColor("colorPrimary", android.graphics.Color.GREEN)
        val warningColor = getThemeColor("colorTertiary", android.graphics.Color.YELLOW)

        when (state) {
            VpnState.DISCONNECTED -> {
                isVpnRunning = false
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_play)
                binding.fabStartStop.text = getString(R.string.connect)
                setFabColor(getThemeColor("colorPrimaryContainer", android.graphics.Color.LTGRAY),
                    getThemeColor("colorOnPrimaryContainer", android.graphics.Color.BLACK))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                binding.tvStatus.text = getString(R.string.main_disconnected)
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.GONE
                if (isStopping) isStopping = false
            }
            VpnState.CONNECTING -> {
                isVpnRunning = false
                binding.fabStartStop.isEnabled = false
                binding.fabStartStop.setIconResource(R.drawable.ic_sync)
                binding.fabStartStop.text = getString(R.string.main_connecting)
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(warningColor)
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutTraffic.visibility = View.GONE
                startFabLoadingAnimation()
                binding.tvStatus.text = getString(R.string.main_connecting)
            }
            VpnState.CONNECTED -> {
                isVpnRunning = true
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_pause)
                binding.fabStartStop.text = getString(R.string.disconnect)
                setFabColor(getThemeColor("colorSecondaryContainer", android.graphics.Color.DKGRAY),
                    getThemeColor("colorOnSecondaryContainer", android.graphics.Color.WHITE))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(successColor)
                binding.tvStatus.text = getString(R.string.main_connected)
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.VISIBLE
                playFabSuccessAnimation()
            }
            VpnState.RECONNECTING -> {
                isVpnRunning = false
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_pause)
                binding.fabStartStop.text = getString(R.string.disconnect)
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(warningColor)
                binding.tvStatus.text = getString(R.string.main_reconnecting)
                binding.progressBar.visibility = View.VISIBLE
                binding.layoutTraffic.visibility = View.VISIBLE
            }
            VpnState.ERROR -> {
                isVpnRunning = false
                binding.fabStartStop.isEnabled = true
                binding.fabStartStop.setIconResource(R.drawable.ic_play)
                binding.fabStartStop.text = getString(R.string.connect)
                setFabColor(getThemeColor("colorPrimaryContainer", android.graphics.Color.LTGRAY),
                    getThemeColor("colorOnPrimaryContainer", android.graphics.Color.BLACK))
                binding.statusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(errorColor)
                binding.tvStatus.text = getString(R.string.main_connection_failed)
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.GONE
            }
            else -> {
                isVpnRunning = false
                binding.progressBar.visibility = View.GONE
                binding.layoutTraffic.visibility = View.GONE
            }
        }
    }

    private fun getThemeColor(attrName: String, default: Int): Int {
        val context = requireContext()
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
                    Toast.makeText(requireContext(), getString(R.string.main_selected, profile.name), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.main_profile_switch_disabled), Toast.LENGTH_SHORT).show()
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
        val base64String = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        
        // Increase QR size to occupy more space
        val displayMetrics = resources.displayMetrics
        val qrSize = (displayMetrics.widthPixels * 0.85).toInt()
        val bitmap = QRUtils.generateQRCode(base64String, qrSize, qrSize)

        if (bitmap != null) {
            val view = layoutInflater.inflate(R.layout.dialog_qr_code, null)
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setCancelable(true) // Explicitly allow click outside to dismiss
                .create()

            view.findViewById<TextView>(R.id.tv_profile_name).text = profile.name
            view.findViewById<ImageView>(R.id.iv_qr_code).apply {
                setImageBitmap(bitmap)
                // Remove any tint to keep QR black/white
                colorFilter = null
            }

            dialog.show()
        } else {
            Toast.makeText(requireContext(), getString(R.string.main_qr_fail), Toast.LENGTH_SHORT).show()
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
            setPrompt(getString(R.string.main_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }

    private fun testSelectedProfileLatency() {
        if (!isVpnRunning) return
        binding.tvStatus.text = getString(R.string.main_testing_latency)
        lifecycleScope.launch(Dispatchers.IO) {
            val selectedProfile = ProfileManager.getSelectedProfile(requireContext())
            var result = "Timeout"
            try {
                val start = System.nanoTime()
                val url = URL("https://www.google.com/generate_204")
                val connection = if (isVpnRunning) {
                    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", MyVpnService.SOCKS_PORT))
                    url.openConnection(proxy) as HttpURLConnection
                } else {
                    url.openConnection() as HttpURLConnection
                }

                connection.apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    instanceFollowRedirects = false
                    useCaches = false
                }

                kotlinx.coroutines.runInterruptible {
                    connection.connect()
                    val code = connection.responseCode
                    val latency = (System.nanoTime() - start) / 1_000_000
                    result = if (code in 200..399) "$latency ms" else "HTTP $code"
                    connection.disconnect()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                result = "Error"
            }
            if (!isActive) return@launch
            withContext(Dispatchers.Main) {
                adapter.updateDelay(selectedProfile.id, result)
                binding.tvStatus.text = getString(if (isVpnRunning) R.string.main_connected else R.string.main_disconnected) + " ($result)"
            }
        }
    }

    private fun testAllProfilesLatency() {
        val profiles = adapter.getProfiles()
        if (profiles.isEmpty()) return
        Toast.makeText(requireContext(), getString(R.string.speed_test_started), Toast.LENGTH_SHORT).show()
        profiles.forEach { adapter.updateDelay(it.id, "...") }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val reqArray = JSONArray()
                profiles.forEach { p ->
                    val configJson = VpnConfigBuilder.buildMySshConfig(requireContext(), p, 1080, 53)
                    reqArray.put(JSONObject().put("id", p.id).put("config", JSONObject(configJson)))
                }
                val jsonResStr = myssh.Myssh.pingNodes(reqArray.toString(), "http://cp.cloudflare.com/generate_204", 8000L)
                val resArray = JSONArray(jsonResStr)
                withContext(Dispatchers.Main) {
                    for (i in 0 until resArray.length()) {
                        val resObj = resArray.getJSONObject(i)
                        adapter.updateDelay(resObj.getString("id"), resObj.getString("result"))
                    }
                    Toast.makeText(requireContext(), getString(R.string.speed_test_completed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(R.string.speed_test_error, e.message), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun validateSelectedProfile(profile: Profile): Boolean {
        if (profile.id.isEmpty() || profile.sshAddr.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.error_no_profile_selected), Toast.LENGTH_SHORT).show()
            return false
        }
        if (profile.authType == Profile.AUTH_TYPE_PASSWORD && profile.pass.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.error_field_required), Toast.LENGTH_SHORT).show()
            return false
        }
        if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY) {
            if (profile.privateKey.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.error_field_required), Toast.LENGTH_SHORT).show()
                return false
            }
            val checkResult = myssh.Myssh.checkIfKeyEncrypted(profile.privateKey)
            if (checkResult == 1L) {
                val decryptedPass = KeystoreUtils.decrypt(profile.keyPass)
                if (decryptedPass.isEmpty() || !myssh.Myssh.validatePassphrase(profile.privateKey, decryptedPass)) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.error_invalid_key_password)
                        .setMessage(R.string.error_invalid_key_password)
                        .setPositiveButton(R.string.ok, null)
                        .show()
                    return false
                }
            } else if (checkResult == 2L) {
                Toast.makeText(requireContext(), getString(R.string.error_invalid_private_key), Toast.LENGTH_SHORT).show()
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
                        Snackbar.make(binding.root, getString(R.string.error_root_required), Snackbar.LENGTH_LONG).show()
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
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val profiles = ProfileManager.getProfiles(requireContext())
                requireContext().contentResolver.openOutputStream(uri)?.use { it.write(Gson().toJson(profiles).toByteArray()) }
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(R.string.export_success), Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(R.string.export_failed, e.message), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun importProfilesFromUri(uri: android.net.Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                    val json = inputStream.bufferedReader().use { it.readText() }
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
                    withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(R.string.import_success, count), Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(requireContext(), getString(R.string.import_failed, e.message), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        StunLogger.errorListener = null
    }
}
