package app.fjj.stun.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.fjj.stun.core.R as CoreR
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunRepository
import app.fjj.stun.repo.VpnState
import app.fjj.stun.service.MyTransparentProxyService
import app.fjj.stun.service.MyVpnService
import app.fjj.stun.util.ExecUtils
import app.fjj.stun.util.KeystoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 快捷方式 / 桌面小组件 / QS 磁贴共用的透明执行页：不展示任何应用界面，
 * 直接完成启动或断开。能静默完成的（节点配置有效、VPN 授权已有、root 可用）
 * 就操作服务后立即 finish；只有必须用户交互的场景才转交 MainActivity 走完整流程：
 * - 节点缺失 / 密钥口令需要输入等校验失败（需要错误提示界面）
 * - TProxy 模式没有 root
 * 首次连接的 VPN 系统授权弹窗是系统界面，由本页直接拉起，不经过 MainActivity。
 */
class VpnQuickActionActivity : AppCompatActivity() {

    private val vpnConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startForegroundService(MyVpnService::class.java, MyVpnService.ACTION_START)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            MainActivity.ACTION_SHORTCUT_STOP_VPN -> {
                stopActiveService()
                finish()
            }
            MainActivity.ACTION_SHORTCUT_START_VPN -> handleStart()
            else -> finish()
        }
    }

    private fun handleStart() {
        val state = StunRepository.vpnState.value ?: VpnState.DISCONNECTED
        if (state == VpnState.CONNECTED || state == VpnState.CONNECTING || state == VpnState.RECONNECTING) {
            // 已连接时重复触发启动 → 无事可做
            finish()
            return
        }
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) {
                runCatching { ProfileManager.getSelectedProfile(this@VpnQuickActionActivity) }.getOrNull()
            }
            if (profile == null || !canConnectQuietly(profile)) {
                redirect(to = MainActivity::class.java)
                return@launch
            }
            val mode = SettingsManager.getServiceMode(this@VpnQuickActionActivity)
            if (mode == SettingsManager.SERVICE_MODE_TPROXY) {
                val rooted = withContext(Dispatchers.IO) {
                    runCatching { ExecUtils.checkIsRootPermission() }.getOrDefault(false)
                }
                if (!rooted) {
                    // 需要界面上的 root 错误提示
                    redirect(to = MainActivity::class.java)
                    return@launch
                }
                startForegroundService(MyTransparentProxyService::class.java, MyTransparentProxyService.ACTION_START)
                finish()
            } else {
                val consentIntent = runCatching { VpnService.prepare(this@VpnQuickActionActivity) }.getOrNull()
                when {
                    consentIntent == null -> {
                        startForegroundService(MyVpnService::class.java, MyVpnService.ACTION_START)
                        finish()
                    }
                    else -> {
                        // 首次授权：直接弹系统 VPN 授权框，不经过应用界面
                        runCatching { vpnConsentLauncher.launch(consentIntent) }.onFailure {
                            redirect(to = MainActivity::class.java)
                        }
                    }
                }
            }
        }
    }

    /**
     * 与 HomeFragment.validateSelectedProfile 对齐的静默校验子集。
     * 返回 false 表示无法在不弹界面（缺节点、口令缺失/错误）的情况下启动，
     * 调用方应回退到 MainActivity 的完整流程。
     */
    private suspend fun canConnectQuietly(profile: Profile): Boolean = withContext(Dispatchers.IO) {
        if (profile.id.isEmpty() || profile.sshAddr.isEmpty()) return@withContext false
        if (profile.authType == Profile.AUTH_TYPE_PASSWORD && profile.pass.isEmpty()) return@withContext false
        if (profile.authType == Profile.AUTH_TYPE_PRIVATEKEY) {
            if (profile.privateKey.isEmpty()) return@withContext false
            val encrypted = runCatching { myssh.Myssh.checkIfKeyEncrypted(profile.privateKey) }.getOrDefault(-1L)
            when (encrypted) {
                0L -> Unit
                1L -> {
                    val decrypted = KeystoreUtils.decrypt(profile.keyPass)
                    val valid = decrypted.isNotEmpty() &&
                        runCatching { myssh.Myssh.validatePassphrase(profile.privateKey, decrypted) }.getOrDefault(false)
                    if (!valid) return@withContext false
                }
                else -> return@withContext false
            }
        }
        true
    }

    private fun stopActiveService() {
        val mode = SettingsManager.getServiceMode(this)
        val isTProxy = mode == SettingsManager.SERVICE_MODE_TPROXY
        val serviceClass = if (isTProxy) MyTransparentProxyService::class.java else MyVpnService::class.java
        val serviceAction = if (isTProxy) MyTransparentProxyService.ACTION_STOP else MyVpnService.ACTION_STOP
        val stopResult = runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, serviceClass).apply { action = serviceAction }
            )
        }
        stopResult.onFailure {
            Toast.makeText(
                this,
                getString(CoreR.string.error_prefix, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }.onSuccess {
            Toast.makeText(this, getString(CoreR.string.shortcut_stop_vpn), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startForegroundService(serviceClass: Class<*>, action: String) {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, serviceClass).apply { this.action = action }
            )
        }.onFailure {
            Toast.makeText(
                this,
                getString(CoreR.string.error_prefix, it.localizedMessage ?: it.javaClass.simpleName),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun redirect(to: Class<*>) {
        startActivity(Intent(this, to).apply {
            action = this@VpnQuickActionActivity.intent?.action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
        finish()
    }
}
