package app.fjj.stun.ui

import android.bluetooth.BluetoothDevice
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
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

class TvDevicePickerBottomSheet : BottomSheetDialogFragment() {

    private var targetProfile: Profile? = null
    private var discoverySession: DiscoverySession? = null
    private val wifiDevicesList = mutableListOf<RemoteDeviceInfo>()
    private val btDevicesList = mutableListOf<BluetoothDevice>()

    private var currentTab = 0 // 0: Wi-Fi LAN, 1: Bluetooth Car
    private lateinit var adapter: CombinedDeviceAdapter

    private var tvScanStatus: TextView? = null
    private var progressIndicator: LinearProgressIndicator? = null
    private var layoutEmpty: View? = null
    private var rvDevices: RecyclerView? = null

    companion object {
        private const val ARG_PROFILE = "arg_profile"

        fun newInstance(profile: Profile? = null): TvDevicePickerBottomSheet {
            return TvDevicePickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_PROFILE, profile)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        targetProfile = arguments?.getSerializable(ARG_PROFILE) as? Profile
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_tv_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvScanStatus = view.findViewById(R.id.tv_scan_status)
        progressIndicator = view.findViewById(R.id.progress_indicator)
        layoutEmpty = view.findViewById(R.id.layout_empty)
        rvDevices = view.findViewById(R.id.rv_devices)

        val tabLayout = view.findViewById<TabLayout>(R.id.tab_device_type)
        val btnRefresh = view.findViewById<MaterialButton>(R.id.btn_refresh)

        adapter = CombinedDeviceAdapter(
            wifiDevices = wifiDevicesList,
            btDevices = btDevicesList,
            targetProfile = targetProfile,
            getMode = { currentTab },
            onWifiPushClick = { device -> handlePushWifi(device) },
            onWifiControlClick = { device -> handleControlWifi(device) },
            onBtPushClick = { device -> handlePushBt(device) },
            onBtControlClick = { device -> handleControlBt(device) }
        )

        rvDevices?.layoutManager = LinearLayoutManager(requireContext())
        rvDevices?.adapter = adapter

        tabLayout?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTab = tab?.position ?: 0
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        btnRefresh.setOnClickListener {
            startScan()
        }

        startScan()
    }

    private fun startScan() {
        discoverySession?.stop()
        wifiDevicesList.clear()
        btDevicesList.clear()
        adapter.notifyDataSetChanged()

        progressIndicator?.visibility = View.VISIBLE
        layoutEmpty?.visibility = View.GONE
        tvScanStatus?.text = getString(CoreR.string.tv_picker_scanning)

        // Scan Bluetooth paired devices
        val pairedBt = BluetoothSyncManager.getPairedBluetoothDevices()
        btDevicesList.addAll(pairedBt)

        // Scan LAN Wi-Fi devices
        discoverySession = RemoteSyncManager.startDeviceDiscovery(requireContext()) { updatedList ->
            if (!isAdded) return@startDeviceDiscovery
            wifiDevicesList.clear()
            wifiDevicesList.addAll(updatedList)
            adapter.notifyDataSetChanged()
            updateEmptyState()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(3000L)
            if (isAdded) {
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
            tvScanStatus?.text = getString(CoreR.string.selected_count, count)
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
        // Handled via Wifi control dialog
        Toast.makeText(requireContext(), getString(CoreR.string.tv_remote_control) + ": " + device.name, Toast.LENGTH_SHORT).show()
    }

    private fun handleControlBt(device: BluetoothDevice) {
        Toast.makeText(requireContext(), getString(CoreR.string.car_bt_scanning), Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val reqJson = JSONObject().apply {
                put("action", "toggle_vpn")
                put("serviceMode", "start")
            }.toString()

            val resStr = BluetoothSyncManager.sendBluetoothCommand(device, reqJson)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(requireContext(), getString(CoreR.string.car_remote_success), Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        discoverySession?.stop()
        discoverySession = null
    }

    private class CombinedDeviceAdapter(
        private val wifiDevices: List<RemoteDeviceInfo>,
        private val btDevices: List<BluetoothDevice>,
        private val targetProfile: Profile?,
        private val getMode: () -> Int,
        private val onWifiPushClick: (RemoteDeviceInfo) -> Unit,
        private val onWifiControlClick: (RemoteDeviceInfo) -> Unit,
        private val onBtPushClick: (BluetoothDevice) -> Unit,
        private val onBtControlClick: (BluetoothDevice) -> Unit
    ) : RecyclerView.Adapter<CombinedDeviceAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_device_name)
            val tvAddress: TextView = view.findViewById(R.id.tv_device_address)
            val btnPush: MaterialButton = view.findViewById(R.id.btn_push_profile)
            val btnControl: MaterialButton = view.findViewById(R.id.btn_remote_control)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tv_device, parent, false)
            return ViewHolder(view)
        }

        @android.annotation.SuppressLint("MissingPermission")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val mode = getMode()
            if (mode == 0) {
                val device = wifiDevices[position]
                holder.tvName.text = device.model.ifBlank { device.name }
                holder.tvAddress.text = "${device.host}:${device.port}"

                holder.btnPush.visibility = if (targetProfile == null) View.GONE else View.VISIBLE
                holder.btnPush.setOnClickListener { onWifiPushClick(device) }
                holder.btnControl.setOnClickListener { onWifiControlClick(device) }
            } else {
                val device = btDevices[position]
                holder.tvName.text = try { device.name ?: device.address } catch (_: Exception) { device.address }
                holder.tvAddress.text = "Bluetooth: ${device.address}"

                holder.btnPush.visibility = if (targetProfile == null) View.GONE else View.VISIBLE
                holder.btnPush.setOnClickListener { onBtPushClick(device) }
                holder.btnControl.setOnClickListener { onBtControlClick(device) }
            }
        }

        override fun getItemCount(): Int {
            return if (getMode() == 0) wifiDevices.size else btDevices.size
        }
    }
}
