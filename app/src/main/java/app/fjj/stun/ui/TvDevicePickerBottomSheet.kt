package app.fjj.stun.ui

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
import app.fjj.stun.remote.DiscoverySession
import app.fjj.stun.remote.PushResult
import app.fjj.stun.remote.RemoteDeviceInfo
import app.fjj.stun.remote.RemoteSyncManager
import app.fjj.stun.repo.Profile
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.fjj.stun.core.R as CoreR

class TvDevicePickerBottomSheet : BottomSheetDialogFragment() {

    private var targetProfile: Profile? = null
    private var discoverySession: DiscoverySession? = null
    private val devicesList = mutableListOf<RemoteDeviceInfo>()
    private lateinit var adapter: TvDeviceAdapter

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

        val btnRefresh = view.findViewById<MaterialButton>(R.id.btn_refresh)

        adapter = TvDeviceAdapter(
            devices = devicesList,
            targetProfile = targetProfile,
            onPushClick = { device -> handlePush(device) },
            onControlClick = { device -> handleControl(device) }
        )

        rvDevices?.layoutManager = LinearLayoutManager(requireContext())
        rvDevices?.adapter = adapter

        btnRefresh.setOnClickListener {
            startScan()
        }

        startScan()
    }

    private fun startScan() {
        discoverySession?.stop()
        devicesList.clear()
        adapter.notifyDataSetChanged()

        progressIndicator?.visibility = View.VISIBLE
        layoutEmpty?.visibility = View.GONE
        tvScanStatus?.text = getString(CoreR.string.tv_picker_scanning)

        discoverySession = RemoteSyncManager.startDeviceDiscovery(requireContext()) { updatedList ->
            if (!isAdded) return@startDeviceDiscovery
            devicesList.clear()
            devicesList.addAll(updatedList)
            adapter.notifyDataSetChanged()

            if (devicesList.isEmpty()) {
                layoutEmpty?.visibility = View.VISIBLE
                tvScanStatus?.text = getString(CoreR.string.tv_picker_no_devices)
            } else {
                layoutEmpty?.visibility = View.GONE
                tvScanStatus?.text = getString(CoreR.string.selected_count, devicesList.size)
            }
        }

        // Auto-hide progress indicator after 10 seconds of scanning
        viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(10000L)
            if (isAdded) {
                progressIndicator?.visibility = View.INVISIBLE
                if (devicesList.isEmpty()) {
                    layoutEmpty?.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun handlePush(device: RemoteDeviceInfo) {
        val profile = targetProfile
        if (profile == null) {
            Toast.makeText(requireContext(), getString(CoreR.string.error_no_profile_selected), Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), getString(CoreR.string.tv_waiting_confirmation), Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = RemoteSyncManager.pushProfileToDevice(device.host, device.port, profile)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                when (result) {
                    PushResult.SUCCESS -> {
                        Toast.makeText(
                            requireContext(),
                            getString(CoreR.string.tv_push_success, device.model.ifBlank { device.name }),
                            Toast.LENGTH_LONG
                        ).show()
                        dismiss()
                    }
                    PushResult.REJECTED -> {
                        Toast.makeText(
                            requireContext(),
                            getString(CoreR.string.tv_push_rejected_by_tv),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    PushResult.TIMEOUT -> {
                        Toast.makeText(
                            requireContext(),
                            getString(CoreR.string.tv_push_timeout),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    PushResult.ERROR -> {
                        Toast.makeText(
                            requireContext(),
                            getString(CoreR.string.push_failed),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun handleControl(device: RemoteDeviceInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_tv_remote_control, null)
        val tvName = dialogView.findViewById<TextView>(R.id.tv_dialog_device_name)
        val tvIp = dialogView.findViewById<TextView>(R.id.tv_dialog_device_ip)
        val tvVpnStatus = dialogView.findViewById<TextView>(R.id.tv_dialog_vpn_status)
        val tvActiveProfile = dialogView.findViewById<TextView>(R.id.tv_dialog_active_profile)
        val btnToggleVpn = dialogView.findViewById<MaterialButton>(R.id.btn_dialog_toggle_vpn)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btn_dialog_close)

        tvName.text = device.model.ifBlank { device.name }
        tvIp.text = "${device.host}:${device.port}"

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(dialogView)
            .create()

        var isConnected = false

        fun refreshStatus() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val status = RemoteSyncManager.getTvStatus(device.host, device.port)
                withContext(Dispatchers.Main) {
                    if (!dialog.isShowing) return@withContext
                    if (status != null) {
                        isConnected = status.vpnState == "CONNECTED"
                        tvVpnStatus.text = if (isConnected) getString(CoreR.string.tv_remote_connected) else getString(CoreR.string.tv_remote_disconnected)
                        tvVpnStatus.setTextColor(
                            requireContext().getColor(
                                if (isConnected) CoreR.color.status_connected else CoreR.color.status_disconnected
                            )
                        )
                        tvActiveProfile.text = status.currentProfileName ?: getString(CoreR.string.never)
                        btnToggleVpn.text = if (isConnected) getString(CoreR.string.tv_remote_stop_vpn) else getString(CoreR.string.tv_remote_start_vpn)
                    } else {
                        tvVpnStatus.text = getString(CoreR.string.main_disconnected)
                    }
                }
            }
        }

        btnToggleVpn.setOnClickListener {
            val action = if (isConnected) "stop_vpn" else "start_vpn"
            btnToggleVpn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val success = RemoteSyncManager.sendRemoteControl(device.host, device.port, action)
                withContext(Dispatchers.Main) {
                    btnToggleVpn.isEnabled = true
                    if (success) {
                        refreshStatus()
                    } else {
                        Toast.makeText(requireContext(), getString(CoreR.string.speed_test_error, "Remote failed"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        refreshStatus()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        discoverySession?.stop()
        discoverySession = null
    }

    // --- RecyclerView Adapter for TV devices ---
    private class TvDeviceAdapter(
        private val devices: List<RemoteDeviceInfo>,
        private val targetProfile: Profile?,
        private val onPushClick: (RemoteDeviceInfo) -> Unit,
        private val onControlClick: (RemoteDeviceInfo) -> Unit
    ) : RecyclerView.Adapter<TvDeviceAdapter.ViewHolder>() {

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

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val device = devices[position]
            holder.tvName.text = device.model.ifBlank { device.name }
            holder.tvAddress.text = "${device.host}:${device.port}"

            if (targetProfile == null) {
                holder.btnPush.visibility = View.GONE
            } else {
                holder.btnPush.visibility = View.VISIBLE
                holder.btnPush.setOnClickListener { onPushClick(device) }
            }

            holder.btnControl.setOnClickListener { onControlClick(device) }
        }

        override fun getItemCount(): Int = devices.size
    }
}
