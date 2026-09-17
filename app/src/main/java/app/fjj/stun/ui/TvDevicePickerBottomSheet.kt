package app.fjj.stun.ui

import android.bluetooth.BluetoothDevice
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.remote.BluetoothSyncManager
import app.fjj.stun.remote.DiscoverySession
import app.fjj.stun.remote.PushResult
import app.fjj.stun.remote.RemoteDeviceInfo
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.remote.TvStatusResponse
import app.fjj.stun.repo.Profile
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class DeviceReachability(val priority: Int, val colorRes: Int) {
    REACHABLE(0, CoreR.color.status_connected),     // 0: Green (绿 - 在线/可用)
    TESTING(1, CoreR.color.status_connecting),       // 1: Orange (橙 - 正在测试)
    UNREACHABLE(2, CoreR.color.status_disconnected)  // 2: Grey/Red (灰 - 离线/超时)
}

/**
 * 设备列表行：把"当前 tab 决定的设备类型 + 设备 + 该设备的当前可达性"折进一个不可变模型。
 * 切 tab、按可达性排序、探测结果回填这三种变化因此都能统一经 ListAdapter/DiffUtil 驱动，
 * 不再需要整表 notifyDataSetChanged（可达性是外部 map，不进模型的话 DiffUtil 会漏判）。
 */
private sealed interface DeviceRow {
    val reachability: DeviceReachability

    data class Wifi(val device: RemoteDeviceInfo, override val reachability: DeviceReachability) : DeviceRow
    data class Bt(val device: BluetoothDevice, override val reachability: DeviceReachability) : DeviceRow
}

class TvDevicePickerBottomSheet : BottomSheetDialogFragment() {

    private var targetProfile: Profile? = null
    private var discoverySession: DiscoverySession? = null
    private val wifiDevicesList = mutableListOf<RemoteDeviceInfo>()
    private val btDevicesList = mutableListOf<BluetoothDevice>()

    private val btReachabilityMap = mutableMapOf<String, DeviceReachability>()
    private val wifiReachabilityMap = mutableMapOf<String, DeviceReachability>()

    private var currentTab = 0 // 0: Wi-Fi LAN, 1: Bluetooth Car
    private lateinit var adapter: CombinedDeviceAdapter

    private var tvScanStatus: TextView? = null
    private var progressIndicator: LinearProgressIndicator? = null
    private var layoutEmpty: View? = null
    private var rvDevices: RecyclerView? = null
    private var btnRefresh: MaterialButton? = null
    private var deviceListUpdateJob: kotlinx.coroutines.Job? = null
    private var scanStatusJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val ARG_PROFILE = "arg_profile"
        @Volatile private var cachedWifiDevices: List<RemoteDeviceInfo> = emptyList()

        fun newInstance(profile: Profile? = null): TvDevicePickerBottomSheet {
            return TvDevicePickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_PROFILE, profile)
                }
            }
        }
    }

    private val btPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshBluetoothDevices()
        } else if (isAdded) {
            updateEmptyState()
            Toast.makeText(
                requireContext(),
                getString(CoreR.string.bluetooth_permission_denied),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetProfile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable(ARG_PROFILE, Profile::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable(ARG_PROFILE) as? Profile
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_tv_picker, container, false)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        behavior.maxWidth = resources.getDimensionPixelSize(R.dimen.modal_max_width)
        val metrics = resources.displayMetrics
        val configuredHeight = resources.configuration.screenHeightDp
            .takeIf { it > 0 }
            ?.let { (it * metrics.density).toInt() }
            ?: metrics.heightPixels
        val maxH = (configuredHeight * 0.80f).toInt()
        behavior.maxHeight = maxH
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvScanStatus = view.findViewById(R.id.tv_scan_status)
        progressIndicator = view.findViewById(R.id.progress_indicator)
        layoutEmpty = view.findViewById(R.id.layout_empty)
        rvDevices = view.findViewById(R.id.rv_devices)
        btnRefresh = view.findViewById(R.id.btn_refresh)

        val tabLayout = view.findViewById<TabLayout>(R.id.tab_device_type)

        adapter = CombinedDeviceAdapter(
            targetProfile = targetProfile,
            onWifiPushClick = { device -> handlePushWifi(device) },
            onWifiControlClick = { device -> handleControlWifi(device) },
            onBtPushClick = { device -> handlePushBt(device) },
            onBtControlClick = { device -> handleControlBt(device) }
        )

        rvDevices?.layoutManager = LinearLayoutManager(requireContext())
        rvDevices?.adapter = adapter
        if (wifiDevicesList.isEmpty() && cachedWifiDevices.isNotEmpty()) {
            wifiDevicesList.addAll(cachedWifiDevices)
            wifiDevicesList.forEach { wifiReachabilityMap[it.host] = DeviceReachability.TESTING }
            submitCurrentDevices()
            updateEmptyState()
        }

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                if (currentTab == 1) {
                    checkBluetoothPermissionAndScan()
                }
                submitCurrentDevices()
                updateEmptyState()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnRefresh?.setOnClickListener {
            startScan()
        }

        startScan()
    }

    private fun checkBluetoothPermissionAndScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    requireContext(),
                    android.Manifest.permission.BLUETOOTH_CONNECT
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }
        refreshBluetoothDevices()
    }

    private fun refreshBluetoothDevices() {
        btDevicesList.clear()
        val pairedBt = BluetoothSyncManager.getPairedBluetoothDevices(requireContext())
        btDevicesList.addAll(pairedBt)

        // Mark all paired Bluetooth devices as TESTING (Orange dot) initially
        pairedBt.forEach { device ->
            btReachabilityMap[device.address] = DeviceReachability.TESTING
        }

        sortAndSubmitBtDevices()
        updateEmptyState()

        // Probe reachability concurrently
        pairedBt.forEach { device ->
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val reachable = BluetoothSyncManager.isDeviceReachable(device)
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    btReachabilityMap[device.address] = if (reachable) DeviceReachability.REACHABLE else DeviceReachability.UNREACHABLE
                    sortAndSubmitBtDevices()
                }
            }
        }
    }

    private fun sortAndSubmitBtDevices() {
        btDevicesList.sortWith(Comparator { d1, d2 ->
            val r1 = btReachabilityMap[d1.address] ?: DeviceReachability.UNREACHABLE
            val r2 = btReachabilityMap[d2.address] ?: DeviceReachability.UNREACHABLE
            r1.priority.compareTo(r2.priority)
        })
        submitCurrentDevices()
    }

    private fun sortAndSubmitWifiDevices() {
        wifiDevicesList.sortWith(Comparator { d1, d2 ->
            val r1 = wifiReachabilityMap[d1.host] ?: DeviceReachability.REACHABLE
            val r2 = wifiReachabilityMap[d2.host] ?: DeviceReachability.REACHABLE
            r1.priority.compareTo(r2.priority)
        })
        submitCurrentDevices()
    }

    /**
     * 按当前 tab 把"设备列表 + 可达性 map"折叠成行模型后交给 ListAdapter。
     * 切 tab / 扫描回填 / 探测结果更新都收敛到这一个入口，增量计算交给 DiffUtil。
     */
    private fun submitCurrentDevices() {
        val rows: List<DeviceRow> = if (currentTab == 0) {
            wifiDevicesList
                .map { DeviceRow.Wifi(it, wifiReachabilityMap[it.host] ?: DeviceReachability.REACHABLE) }
                .sortedBy { it.reachability.priority }
        } else {
            btDevicesList
                .map { DeviceRow.Bt(it, btReachabilityMap[it.address] ?: DeviceReachability.TESTING) }
                .sortedBy { it.reachability.priority }
        }
        adapter.submitList(rows)
    }

    private fun scheduleWifiListUpdate() {
        deviceListUpdateJob?.cancel()
        deviceListUpdateJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(100L)
            if (!isAdded) return@launch
            cachedWifiDevices = wifiDevicesList.toList()
            sortAndSubmitWifiDevices()
            updateEmptyState()
        }
    }

    private fun startScan() {
        discoverySession?.stop()
        btDevicesList.clear()
        btReachabilityMap.clear()
        wifiDevicesList.forEach { wifiReachabilityMap[it.host] = DeviceReachability.TESTING }
        scheduleWifiListUpdate()

        btnRefresh?.animate()?.rotationBy(360f)?.setDuration(600L)?.start()
        progressIndicator?.visibility = View.VISIBLE
        layoutEmpty?.visibility = View.GONE
        tvScanStatus?.text = getString(CoreR.string.tv_picker_scanning)

        // Bluetooth permission is requested only when the user opens that tab.
        // Opening the default Wi-Fi device picker should not show an unrelated
        // permission prompt.
        if (currentTab == 1) checkBluetoothPermissionAndScan()

        // Scan LAN Wi-Fi devices
        discoverySession = RemoteSyncManager.startDeviceDiscovery(requireContext()) { updatedList ->
            if (!isAdded) return@startDeviceDiscovery
            wifiDevicesList.clear()
            wifiDevicesList.addAll(updatedList)

            // Probe Wi-Fi devices asynchronously
            updatedList.forEach { device ->
                if (!wifiReachabilityMap.containsKey(device.host)) {
                    wifiReachabilityMap[device.host] = DeviceReachability.TESTING
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val status = RemoteSyncManager.getTvStatus(device.host, device.port)
                        withContext(Dispatchers.Main) {
                            if (!isAdded) return@withContext
                            wifiReachabilityMap[device.host] = if (status != null) DeviceReachability.REACHABLE else DeviceReachability.UNREACHABLE
                            scheduleWifiListUpdate()
                        }
                    }
                }
            }
            scheduleWifiListUpdate()
        }

        scanStatusJob?.cancel()
        scanStatusJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(3000L)
            if (isAdded) {
                wifiDevicesList.forEach { device ->
                    if (wifiReachabilityMap[device.host] == DeviceReachability.TESTING) {
                        wifiReachabilityMap[device.host] = DeviceReachability.UNREACHABLE
                    }
                }
                scheduleWifiListUpdate()
                progressIndicator?.visibility = View.INVISIBLE
                updateEmptyState()
            }
        }
    }

    private fun updateEmptyState() {
        val count = if (currentTab == 0) wifiDevicesList.size else btDevicesList.size
        if (count == 0) {
            layoutEmpty?.visibility = View.VISIBLE
            tvScanStatus?.text = if (currentTab == 0) {
                getString(CoreR.string.tv_picker_no_devices)
            } else {
                getString(CoreR.string.car_bt_no_devices)
            }
        } else {
            layoutEmpty?.visibility = View.GONE
            tvScanStatus?.text = getString(CoreR.string.device_found_count, count)
        }
    }

    private fun handlePushWifi(device: RemoteDeviceInfo) {
        val profile = targetProfile ?: return
        Toast.makeText(requireContext(), getString(CoreR.string.tv_waiting_confirmation), Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = RemoteSyncManager.pushProfileToDevice(device.host, device.port, profile)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (result == PushResult.SUCCESS) {
                    Toast.makeText(requireContext(), getString(CoreR.string.tv_push_success, device.name), Toast.LENGTH_LONG).show()
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), getString(CoreR.string.push_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handlePushBt(device: BluetoothDevice) {
        val profile = targetProfile ?: return
        Toast.makeText(requireContext(), getString(CoreR.string.car_bt_scanning), Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val payload = Gson().toJson(profile)
            val reqJson = JSONObject().apply {
                put("action", "push_profile")
                put("payload", payload)
            }.toString()

            val resStr = BluetoothSyncManager.sendBluetoothCommand(device, reqJson)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val resObj = try { JSONObject(resStr) } catch (_: Exception) { JSONObject() }
                if (resObj.optString("status") == "success") {
                    Toast.makeText(requireContext(), getString(CoreR.string.car_push_success), Toast.LENGTH_LONG).show()
                    dismiss()
                } else {
                    Toast.makeText(requireContext(), getString(CoreR.string.push_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleControlWifi(device: RemoteDeviceInfo) {
        showRemoteControlDialog(
            title = device.model.ifBlank { device.name },
            subtitle = getString(CoreR.string.host_port_format, device.host, device.port),
            transport = wifiTransport(device)
        )
    }

    private fun handleControlBt(device: BluetoothDevice) {
        // 蓝牙设备与 Wi-Fi 设备共用同一个远程控制面板（数据走 BT 的 get_tv_status）。
        // 旧实现这里只发一条 toggle_vpn/start —— 点「远程控制」等于直接把车机 VPN 拉起来，
        // 没有任何界面；车机端也没有 HTTP 服务器，蓝牙是它唯一的远程控制通道。
        val name = try {
            device.name ?: device.address
        } catch (_: Exception) {
            device.address
        }
        showRemoteControlDialog(
            title = name,
            subtitle = device.address ?: "",
            transport = btTransport(device)
        )
    }

    /**
     * 远程控制面板的数据通道：Wi-Fi 走 HTTP（[RemoteSyncManager]），蓝牙走 RFCOMM
     * （[BluetoothSyncManager]）。面板 UI 与交互只有一份，两条通道只是数据来源不同。
     */
    private class RemoteControlTransport(
        val fetchStatus: suspend () -> TvStatusResponse?,
        val sendCommand: suspend (action: String, profileId: String?) -> Boolean,
        val pushAndStart: suspend (profile: Profile) -> Boolean,
        /**
         * 定时轮询间隔；null = 不轮询。蓝牙每条命令都要新建一条 RFCOMM 连接，
         * 轮询的开销与失败率都不可接受，所以只在打开面板与每次操作后刷新。
         */
        val pollIntervalMs: Long?
    )

    private fun wifiTransport(device: RemoteDeviceInfo) = RemoteControlTransport(
        fetchStatus = { RemoteSyncManager.getTvStatus(device.host, device.port) },
        sendCommand = { action, profileId ->
            RemoteSyncManager.sendRemoteControl(device.host, device.port, action, profileId)
        },
        pushAndStart = { profile ->
            if (RemoteSyncManager.pushProfileToDevice(device.host, device.port, profile) == PushResult.SUCCESS) {
                // 用 start_vpn 真正启动（与 BT 通道对齐）：未连接的设备上 select_profile 只选中
                // 不启动，「推送并启动」会静默变「只推送」；并检查返回值 —— 推送成功但启动
                // 失败不能报成功。start_vpn 在接收端也会先把选中节点设为该 id。
                RemoteSyncManager.sendRemoteControl(device.host, device.port, "start_vpn", profile.id)
            } else {
                false
            }
        },
        pollIntervalMs = 1500L
    )

    private fun btTransport(device: BluetoothDevice) = RemoteControlTransport(
        fetchStatus = { fetchBtStatus(device) },
        sendCommand = { action, profileId -> sendBtControl(device, action, profileId) },
        pushAndStart = { profile -> pushBtAndStart(device, profile) },
        pollIntervalMs = null
    )

    /**
     * 蓝牙命令统一带超时：`BluetoothSyncManager.sendBluetoothCommand` 里的
     * `connect()` / `readLine()` 都可能**永久阻塞**，设备不在旁边时必须能放弃，
     * 否则面板会一直转圈、协程被无限挂住。
     */
    private suspend fun btCommand(device: BluetoothDevice, body: JSONObject): JSONObject? =
        kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            try {
                JSONObject(BluetoothSyncManager.sendBluetoothCommand(device, body.toString()))
            } catch (_: Exception) {
                null
            }
        }

    private suspend fun fetchBtStatus(device: BluetoothDevice): TvStatusResponse? {
        val res = btCommand(device, JSONObject().put("action", "get_tv_status")) ?: return null
        if (res.optString("status") != "ok") return null
        val payload = res.optJSONObject("tvStatus") ?: return null
        return try {
            Gson().fromJson(payload.toString(), TvStatusResponse::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun sendBtControl(device: BluetoothDevice, action: String, profileId: String?): Boolean {
        val body = JSONObject().put("action", action)
        if (!profileId.isNullOrBlank()) body.put("profileId", profileId)
        val res = btCommand(device, body) ?: return false
        return res.optString("status") == "success"
    }

    private suspend fun pushBtAndStart(device: BluetoothDevice, profile: Profile): Boolean {
        val pushed = btCommand(
            device,
            JSONObject()
                .put("action", "push_profile")
                .put("payload", Gson().toJson(profile))
        )?.optString("status") == "success"
        if (!pushed) return false
        // 车机端的 start_vpn 会走它自己的 VPN 授权/通知权限流程（与在车机上手动点连接一致）。
        return sendBtControl(device, "start_vpn", profile.id)
    }

    private fun showRemoteControlDialog(title: String, subtitle: String, transport: RemoteControlTransport) {
        val dialogBinding = app.fjj.stun.databinding.DialogTvRemoteControlBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvDialogDeviceName.text = title
        dialogBinding.tvDialogDeviceIp.text = subtitle

        var currentTvStatus: TvStatusResponse? = null
        var isPolling = true
        var commandInProgress = false
        var pollJob: kotlinx.coroutines.Job? = null

        fun updateUi(status: TvStatusResponse?) {
            currentTvStatus = status
            if (status == null) {
                dialogBinding.tvDialogVpnStatus.text = getString(CoreR.string.remote_device_unavailable)
                val redColor = androidx.core.content.ContextCompat.getColor(requireContext(), CoreR.color.status_disconnected)
                dialogBinding.tvDialogVpnStatus.setTextColor(redColor)
                dialogBinding.ivDialogStatusDot.imageTintList = android.content.res.ColorStateList.valueOf(redColor)
                dialogBinding.btnDialogToggleVpn.text = getString(CoreR.string.tv_remote_start_vpn)
                dialogBinding.btnDialogToggleVpn.isEnabled = false
                dialogBinding.btnDialogReconnect.isEnabled = false
                dialogBinding.btnDialogSwitchProfile.isEnabled = false
                dialogBinding.btnDialogPushCurrent.isEnabled = false
                dialogBinding.tvDialogActiveProfile.text = getString(CoreR.string.tv_remote_no_profiles)
                dialogBinding.tvDialogTunnelType.visibility = View.GONE
                dialogBinding.layoutDialogServer.visibility = View.GONE
                dialogBinding.layoutDialogPublicIp.visibility = View.GONE
                dialogBinding.layoutDialogTraffic.visibility = View.GONE
                return
            }

            val isConnected = status.vpnState == "CONNECTED"
            val isTransition = status.vpnState == "CONNECTING" || status.vpnState == "RECONNECTING" || status.vpnState == "DISCONNECTING"

            dialogBinding.tvDialogVpnStatus.text = when (status.vpnState) {
                "CONNECTED" -> getString(CoreR.string.tv_remote_connected)
                "CONNECTING", "RECONNECTING" -> getString(CoreR.string.tv_remote_reconnecting)
                "DISCONNECTING" -> getString(CoreR.string.tv_remote_disconnecting)
                else -> getString(CoreR.string.tv_remote_disconnected)
            }

            val statusColor = when {
                isConnected -> androidx.core.content.ContextCompat.getColor(requireContext(), CoreR.color.status_connected)
                isTransition -> androidx.core.content.ContextCompat.getColor(requireContext(), CoreR.color.status_connecting)
                else -> androidx.core.content.ContextCompat.getColor(requireContext(), CoreR.color.status_disconnected)
            }

            dialogBinding.tvDialogVpnStatus.setTextColor(statusColor)
            dialogBinding.ivDialogStatusDot.imageTintList = android.content.res.ColorStateList.valueOf(statusColor)

            val controlsEnabled = !isTransition && !commandInProgress
            dialogBinding.btnDialogToggleVpn.isEnabled = controlsEnabled
            dialogBinding.btnDialogReconnect.isEnabled = controlsEnabled
            dialogBinding.btnDialogPushCurrent.isEnabled = controlsEnabled
            dialogBinding.btnDialogToggleVpn.text = if (isConnected) {
                getString(CoreR.string.tv_remote_stop_vpn)
            } else {
                getString(CoreR.string.tv_remote_start_vpn)
            }

            dialogBinding.tvDialogActiveProfile.text = status.currentProfileName?.ifBlank { getString(CoreR.string.tv_remote_no_profiles) } ?: getString(CoreR.string.tv_remote_no_profiles)

            if (!status.currentProfileType.isNullOrBlank()) {
                dialogBinding.tvDialogTunnelType.visibility = View.VISIBLE
                dialogBinding.tvDialogTunnelType.text = status.currentProfileType
            } else {
                dialogBinding.tvDialogTunnelType.visibility = View.GONE
            }

            if (!status.currentProfileServer.isNullOrBlank()) {
                dialogBinding.layoutDialogServer.visibility = View.VISIBLE
                dialogBinding.tvDialogServerAddress.text = status.currentProfileServer
            } else {
                dialogBinding.layoutDialogServer.visibility = View.GONE
            }

            if (!status.publicIp.isNullOrBlank()) {
                dialogBinding.layoutDialogPublicIp.visibility = View.VISIBLE
                dialogBinding.tvDialogPublicIp.text = status.publicIp
            } else {
                dialogBinding.layoutDialogPublicIp.visibility = View.GONE
            }

            if (isConnected || status.txTotal > 0 || status.rxTotal > 0) {
                dialogBinding.layoutDialogTraffic.visibility = View.VISIBLE
                dialogBinding.tvDialogTrafficRate.text = "↑ ${app.fjj.stun.util.AppUtils.formatSpeed(status.txRate)}   ↓ ${app.fjj.stun.util.AppUtils.formatSpeed(status.rxRate)}"
                dialogBinding.tvDialogTrafficTotal.text = "Σ ${app.fjj.stun.util.AppUtils.formatBytes(status.txTotal + status.rxTotal)}"
            } else {
                dialogBinding.layoutDialogTraffic.visibility = View.GONE
            }

            dialogBinding.btnDialogSwitchProfile.isEnabled = controlsEnabled && !status.profiles.isNullOrEmpty()
        }

        fun fetchStatus() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val status = transport.fetchStatus()
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        updateUi(status)
                    }
                }
            }
        }

        // Wi-Fi 建连便宜，保持 1.5s 轮询让状态实时；蓝牙每条命令都要新建 RFCOMM 连接，
        // 不轮询 —— 打开面板时 fetchStatus() 拉一次，之后靠手动刷新与每次操作后的刷新。
        val pollInterval = transport.pollIntervalMs
        if (pollInterval != null) {
            pollJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                while (isPolling) {
                    val status = transport.fetchStatus()
                    withContext(Dispatchers.Main) {
                        if (dialog.isShowing) {
                            updateUi(status)
                        }
                    }
                    delay(pollInterval)
                }
            }
        }

        dialogBinding.btnDialogRefresh.setOnClickListener {
            fetchStatus()
        }

        dialogBinding.btnDialogToggleVpn.setOnClickListener {
            val status = currentTvStatus ?: return@setOnClickListener
            val isConnected = status.vpnState == "CONNECTED"
            val action = if (isConnected) "stop_vpn" else "start_vpn"

            commandInProgress = true
            dialogBinding.btnDialogToggleVpn.isEnabled = false
            dialogBinding.tvDialogVpnStatus.text = if (isConnected) getString(CoreR.string.tv_remote_disconnecting) else getString(CoreR.string.tv_remote_starting)

            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val sent = transport.sendCommand(action, null)
                delay(600L)
                val newStatus = transport.fetchStatus()
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        commandInProgress = false
                        updateUi(newStatus)
                        if (!sent) {
                            Toast.makeText(requireContext(), getString(CoreR.string.remote_command_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        dialogBinding.btnDialogReconnect.setOnClickListener {
            commandInProgress = true
            dialogBinding.btnDialogReconnect.isEnabled = false
            dialogBinding.tvDialogVpnStatus.text = getString(CoreR.string.tv_remote_reconnecting_action)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val sent = transport.sendCommand("restart_vpn", null)
                delay(800L)
                val newStatus = transport.fetchStatus()
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        commandInProgress = false
                        updateUi(newStatus)
                        if (!sent) {
                            Toast.makeText(requireContext(), getString(CoreR.string.remote_command_failed), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        dialogBinding.btnDialogSwitchProfile.setOnClickListener {
            val profiles = currentTvStatus?.profiles ?: emptyList()
            if (profiles.isEmpty()) {
                Toast.makeText(requireContext(), getString(CoreR.string.tv_remote_no_profiles), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val names = profiles.map { "${it.name} (${it.tunnelType})" }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(CoreR.string.tv_remote_switch_node))
                .setItems(names) { _, which ->
                    val selected = profiles[which]
                    commandInProgress = true
                    updateUi(currentTvStatus)
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        val sent = transport.sendCommand("select_profile", selected.id)
                        delay(600L)
                        val newStatus = transport.fetchStatus()
                        withContext(Dispatchers.Main) {
                            commandInProgress = false
                            if (dialog.isShowing) updateUi(newStatus)
                            Toast.makeText(
                                requireContext(),
                                if (sent) getString(CoreR.string.tv_remote_node_switched, selected.name) else getString(CoreR.string.remote_command_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .setNegativeButton(getString(CoreR.string.cancel), null)
                .show()
        }

        if (targetProfile != null) {
            dialogBinding.btnDialogPushCurrent.visibility = View.VISIBLE
            dialogBinding.btnDialogPushCurrent.text = getString(CoreR.string.tv_remote_push_and_start)
            dialogBinding.btnDialogPushCurrent.setOnClickListener {
                val profile = targetProfile ?: return@setOnClickListener
                commandInProgress = true
                dialogBinding.btnDialogPushCurrent.isEnabled = false
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    val pushed = transport.pushAndStart(profile)
                    delay(800L)
                    val newStatus = transport.fetchStatus()
                    withContext(Dispatchers.Main) {
                        commandInProgress = false
                        if (dialog.isShowing) {
                            updateUi(newStatus)
                        }
                        Toast.makeText(
                            requireContext(),
                            if (pushed) getString(CoreR.string.tv_push_success, title) else getString(CoreR.string.push_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        } else {
            dialogBinding.btnDialogPushCurrent.visibility = View.GONE
        }

        dialogBinding.btnDialogClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            isPolling = false
            pollJob?.cancel()
        }

        dialog.show()
        fetchStatus()
    }

    override fun onDestroyView() {
        deviceListUpdateJob?.cancel()
        deviceListUpdateJob = null
        scanStatusJob?.cancel()
        scanStatusJob = null
        discoverySession?.stop()
        discoverySession = null
        rvDevices?.adapter = null
        tvScanStatus = null
        progressIndicator = null
        layoutEmpty = null
        rvDevices = null
        btnRefresh = null
        super.onDestroyView()
    }

    private class DeviceRowDiffCallback : DiffUtil.ItemCallback<DeviceRow>() {
        override fun areItemsTheSame(oldItem: DeviceRow, newItem: DeviceRow): Boolean = when {
            oldItem is DeviceRow.Wifi && newItem is DeviceRow.Wifi ->
                oldItem.device.host == newItem.device.host && oldItem.device.port == newItem.device.port
            oldItem is DeviceRow.Bt && newItem is DeviceRow.Bt ->
                oldItem.device.address == newItem.device.address
            else -> false
        }

        override fun areContentsTheSame(oldItem: DeviceRow, newItem: DeviceRow): Boolean = oldItem == newItem
    }

    private class CombinedDeviceAdapter(
        private val targetProfile: Profile?,
        private val onWifiPushClick: (RemoteDeviceInfo) -> Unit,
        private val onWifiControlClick: (RemoteDeviceInfo) -> Unit,
        private val onBtPushClick: (BluetoothDevice) -> Unit,
        private val onBtControlClick: (BluetoothDevice) -> Unit
    ) : ListAdapter<DeviceRow, CombinedDeviceAdapter.ViewHolder>(DeviceRowDiffCallback()) {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivIcon: ImageView = view.findViewById(R.id.iv_device_icon)
            val tvName: TextView = view.findViewById(R.id.tv_device_name)
            val tvAddress: TextView = view.findViewById(R.id.tv_device_address)
            val btnPush: MaterialButton = view.findViewById(R.id.btn_push_profile)
            val btnControl: MaterialButton = view.findViewById(R.id.btn_remote_control)
            val dotOnline: View = view.findViewById(R.id.dot_online)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_device, parent, false)
            return ViewHolder(view)
        }

        @android.annotation.SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            when (val row = getItem(position)) {
                is DeviceRow.Wifi -> {
                    // ---- Wi-Fi LAN device ----
                    val device = row.device
                    val name = device.model.ifBlank { device.name }
                    holder.tvName.text = name
                    holder.tvAddress.text =
                        holder.itemView.context.getString(CoreR.string.host_port_format, device.host, device.port)
                    val isCar = name.contains("car", ignoreCase = true) || name.contains("auto", ignoreCase = true)
                    holder.ivIcon.setImageResource(if (isCar) CoreR.drawable.ic_dashboard else CoreR.drawable.ic_tv)

                    holder.dotOnline.visibility = View.VISIBLE
                    holder.dotOnline.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(
                        holder.itemView.context, row.reachability.colorRes
                    )

                    holder.btnPush.visibility = if (targetProfile == null) View.GONE else View.VISIBLE
                    holder.btnPush.setOnClickListener { onWifiPushClick(device) }
                    holder.btnControl.setOnClickListener { onWifiControlClick(device) }
                    holder.itemView.setOnClickListener { onWifiControlClick(device) }
                }
                is DeviceRow.Bt -> {
                    // ---- Bluetooth paired device ----
                    val device = row.device
                    val name = try { device.name ?: device.address } catch (_: Exception) { device.address }
                    holder.tvName.text = name
                    holder.tvAddress.text =
                        holder.itemView.context.getString(CoreR.string.bt_address_format, device.address)
                    holder.ivIcon.setImageResource(CoreR.drawable.ic_sync)

                    holder.dotOnline.visibility = View.VISIBLE
                    holder.dotOnline.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(
                        holder.itemView.context, row.reachability.colorRes
                    )

                    holder.btnPush.visibility = if (targetProfile == null) View.GONE else View.VISIBLE
                    holder.btnPush.setOnClickListener { onBtPushClick(device) }
                    holder.btnControl.setOnClickListener { onBtControlClick(device) }
                    holder.itemView.setOnClickListener { onBtControlClick(device) }
                }
            }
        }
    }
}
