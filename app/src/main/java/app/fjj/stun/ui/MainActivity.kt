package app.fjj.stun.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import app.fjj.stun.databinding.ActivityMainBinding
import app.fjj.stun.repo.GostRepository
import app.fjj.stun.service.MyVpnService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isVpnRunning = false

    // 1. 定义 Result Launcher (替代 onActivityResult)
    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // 用户点击了“确定”，启动服务
            startVpnService()
        } else {
            Toast.makeText(this, "VPN 授权失败", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            binding.toolbar.updatePadding(top = systemBars.top)
            insets
        }

        // 让 TextView 支持滚动
        binding.sampleText.movementMethod = ScrollingMovementMethod()

        // --- 核心：观察日志变化 ---
        GostRepository.logData.observe(this) { newLine ->
            // 1. 先追加文字
            binding.sampleText.text = "$newLine"

            // 2. 安全地执行滚动：只有 layout 不为空时才计算滚动
            val layout = binding.sampleText.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(binding.sampleText.lineCount) - binding.sampleText.height
                if (scrollAmount > 0) {
                    binding.sampleText.scrollTo(0, scrollAmount)
                }
            }
        }

        binding.fabStartStop.setOnClickListener {
            prepareVpn()
        }

        // 观察 VPN 状态以更新 FAB 图标
        GostRepository.vpnStatus.observe(this) { running ->
            isVpnRunning = running
            if (running) {
                binding.fabStartStop.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                binding.fabStartStop.setImageResource(android.R.drawable.ic_media_play)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(app.fjj.stun.R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            app.fjj.stun.R.id.action_settings -> {
                startActivity(Intent(this, ConfigActivity::class.java))
                return true
            }
            app.fjj.stun.R.id.action_logs -> {
                startActivity(Intent(this, LogsActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun prepareVpn() {
        // 2. 检查 VPN 权限
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // 弹出系统授权对话框
            vpnLauncher.launch(intent)
        } else {
            isVpnRunning = GostRepository.vpnStatus.value ?: false
            // 已经授权过了，直接启动
            if (isVpnRunning) {
                stopVpnService()
                return
            }
            startVpnService()
        }
    }

    // 停止 VPN 的函数
    private fun stopVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = MyVpnService.ACTION_STOP // 发送自定义 Action
        startService(intent)

        isVpnRunning = false
    }

    // 修改启动逻辑，成功后更新状态
    private fun startVpnService() {
        val intent = Intent(this, MyVpnService::class.java)
        intent.action = MyVpnService.ACTION_START
        startService(intent)

        isVpnRunning = true
    }
}