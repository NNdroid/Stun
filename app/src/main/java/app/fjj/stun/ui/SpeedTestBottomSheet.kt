package app.fjj.stun.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.fjj.stun.R
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.ui.view.TrafficBarChartView
import app.fjj.stun.util.SpeedTestManager
import app.fjj.stun.util.SpeedTestPhase
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 真正的带宽测速面板：下行 + 上行，并显示实时速率曲线。
 * 与“测速=延迟探测”完全独立——这里走 Go 侧 [SpeedTestManager]（myssh.speedTest），
 * 经节点隧道测真实吞吐。需先连接 VPN，否则隧道未建立无法测速。
 */
class SpeedTestBottomSheet : BottomSheetDialogFragment() {

    private var chart: TrafficBarChartView? = null
    private var tvDown: TextView? = null
    private var tvUp: TextView? = null
    private var tvStatus: TextView? = null
    private var progress: CircularProgressIndicator? = null
    private var btnStart: MaterialButton? = null
    private val downColor by lazy { ContextCompat.getColor(requireContext(), R.color.widget_down_accent) }
    private val upColor by lazy { ContextCompat.getColor(requireContext(), R.color.widget_up_accent) }

    /** 样本按 bps 传进来，轴上是 Mbps —— 与上面的 `"%.1f"` 同一口径（十进制兆）。 */
    private val unitDivisor = 1_000_000.0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_speed_test, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        chart = view.findViewById(R.id.chart_speed)
        tvDown = view.findViewById(R.id.tv_down_value)
        tvUp = view.findViewById(R.id.tv_up_value)
        tvStatus = view.findViewById(R.id.tv_speed_status)
        progress = view.findViewById(R.id.progress_speed)
        btnStart = view.findViewById(R.id.btn_start_speed_test)

        chart?.slots = 30
        chart?.showAxes = true
        chart?.unitLabel = getString(CoreR.string.bandwidth_unit_mbps)
        chart?.unitDivisor = unitDivisor
        btnStart?.setOnClickListener { startTest() }

        StunRepository.vpnState.observe(viewLifecycleOwner) { updateButtonState() }

        SpeedTestManager.running.observe(viewLifecycleOwner) { running ->
            updateButtonState()
            progress?.visibility = if (running) View.VISIBLE else View.GONE
            if (running) {
                tvDown?.setText(CoreR.string.value_dash)
                tvUp?.setText(CoreR.string.value_dash)
                tvStatus?.setText(CoreR.string.bandwidth_test_running)
                chart?.submitSamples(emptyList(), downColor)
            }
        }

        SpeedTestManager.phase.observe(viewLifecycleOwner) { phase ->
            if (SpeedTestManager.running.value != true) return@observe
            when (phase) {
                SpeedTestPhase.DOWNLOAD -> tvStatus?.setText(CoreR.string.bandwidth_test_downloading)
                SpeedTestPhase.UPLOAD -> tvStatus?.setText(CoreR.string.bandwidth_test_uploading)
                SpeedTestPhase.IDLE -> Unit
            }
        }
        SpeedTestManager.downProgressMbps.observe(viewLifecycleOwner) { mbps ->
            if (mbps != null && SpeedTestManager.running.value == true) tvDown?.text = formatMbps(mbps)
        }
        SpeedTestManager.upProgressMbps.observe(viewLifecycleOwner) { mbps ->
            if (mbps != null && SpeedTestManager.running.value == true) tvUp?.text = formatMbps(mbps)
        }

        SpeedTestManager.result.observe(viewLifecycleOwner) { res ->
            if (res == null) return@observe
            if (res.ok) {
                tvDown?.text = formatMbps(res.downMbps)
                tvUp?.text = formatMbps(res.upMbps)
                tvStatus?.text = getString(
                    CoreR.string.bandwidth_test_done,
                    formatMbps(res.downMbps),
                    formatMbps(res.upMbps)
                )
            } else {
                if (res.bytesDown > 0) tvDown?.text = formatMbps(res.downMbps)
                else tvDown?.setText(CoreR.string.value_dash)
                if (res.bytesUp > 0) tvUp?.text = formatMbps(res.upMbps)
                else tvUp?.setText(CoreR.string.value_dash)
                tvStatus?.text = getString(CoreR.string.speed_test_error, res.error.ifBlank { "unknown" })
            }
        }

        SpeedTestManager.samples.observe(viewLifecycleOwner) { samples ->
            val color = if (SpeedTestManager.phase.value == SpeedTestPhase.UPLOAD) upColor else downColor
            chart?.submitSamples(samples, color)
        }

        updateButtonState()
    }

    /**
     * 数值只写数字 —— 单位 "Mbps" 是布局里那个固定的小字（translatable=false），
     * 跟着数值一起走会让两个数字的字号无法区分，也就出不来效果图里「大号数值 + 小号单位」的层次。
     */
    private fun formatMbps(mbps: Double): String = "%.1f".format(mbps)

    private fun updateButtonState() {
        if (!isAdded) return
        val connected = StunRepository.vpnState.value == VpnState.CONNECTED
        val running = SpeedTestManager.running.value == true
        btnStart?.isEnabled = connected && !running
        // 未连接时状态行说的是「现在为什么不能测」，不是副标题那句「测的是什么」——
        // 两处共用一条串会让同一句话在同一屏里出现两次。
        if (!connected && !running) {
            tvStatus?.setText(CoreR.string.bandwidth_test_need_vpn)
        }
    }

    private fun startTest() {
        val ctx = context ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val profile = runCatching { ProfileManager.getSelectedProfile(ctx) }.getOrNull()
            if (profile == null || profile.id.isEmpty()) {
                withContext(Dispatchers.Main) {
                    if (isAdded) tvStatus?.setText(CoreR.string.error_no_profile_selected)
                }
                return@launch
            }
            SpeedTestManager.run(ctx, profile)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        chart = null
        tvDown = null
        tvUp = null
        tvStatus = null
        progress = null
        btnStart = null
    }
}
