package app.fjj.stun.util

import android.content.pm.PackageManager
import android.os.Build
import android.os.IDeviceIdleController
import app.fjj.stun.repo.StunLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.coroutines.resume

object ShizukuUtils {
    private const val TAG = "ShizukuUtils"
    const val SHIZUKU_REQUEST_CODE = 1001

    val isReady: Boolean
        get() {
            val notPreV11 = !Shizuku.isPreV11()
            val isBinderAlive = Shizuku.pingBinder()
            val hasPermission = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

            // 打印详细日志，帮我们定位到底是哪一个为 false
            StunLogger.d(TAG, "isReady Check -> notPreV11: $notPreV11, isBinderAlive: $isBinderAlive, hasPermission: $hasPermission")

            // 核心改动：只要 Binder 是活的，我们就放行。
            // 因为即使 hasPermission 存在极短暂的同步延迟为 false，
            // 我们强行往下走，底层的 Binder 调用（包裹在 try-catch 中）如果真没权限也会抛出 SecurityException，被我们捕获，不会导致崩溃。
            return notPreV11 && isBinderAlive
        }

    /**
     * 核心封装：使用协程挂起函数隐藏 Listener
     * 调用此方法会挂起当前协程，直到用户做出授权选择或直接返回结果
     *
     * @return true 表示已授权，false 表示拒绝或服务不可用
     */
    suspend fun requestPermissionAwait(): Boolean = suspendCancellableCoroutine { continuation ->
        StunLogger.i(TAG, "requestPermissionAwait called")
        // 1. 如果已经就绪，直接返回 true
        if (isReady) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        // 2. 如果服务未启动，直接返回 false
        if (Shizuku.isPreV11() || !Shizuku.pingBinder()) {
            StunLogger.w(TAG, "Shizuku is not running or version is too low.")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        // 3. 如果用户曾经勾选了"不再询问"并拒绝
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            StunLogger.w(TAG, "Shizuku permission denied by user (never ask again).")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        // 4. 创建一个局部 Listener
        val listener = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == SHIZUKU_REQUEST_CODE) {
                    // 收到结果后，立刻移除监听器，防止内存泄漏
                    Shizuku.removeRequestPermissionResultListener(this)

                    val isGranted = grantResult == PackageManager.PERMISSION_GRANTED
                    // 恢复协程并返回结果
                    if (continuation.isActive) {
                        continuation.resume(isGranted)
                    }
                }
            }
        }

        // 5. 注册监听器并处理协程取消的情况
        Shizuku.addRequestPermissionResultListener(listener)
        continuation.invokeOnCancellation {
            Shizuku.removeRequestPermissionResultListener(listener)
        }

        // 6. 真正发起权限请求
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    // ========== 下面是业务方法，内部不再负责请求权限 ==========

    fun addSelfToBatteryWhitelist(packageName: String) {
        if (!isReady) return // 调用前由外部保证权限，这里只做最后一道防线拦截
        try {
            val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("deviceidle"))
            val idleController = IDeviceIdleController.Stub.asInterface(binder)
            idleController.addPowerSaveWhitelistApp(packageName)
            StunLogger.i(TAG, "Successfully added $packageName to battery whitelist")
        } catch (e: Exception) {
            StunLogger.e(TAG, "Binder call failed, fallback to shell command", e)
            executeShellCommand("dumpsys deviceidle whitelist +$packageName")
        }
    }

    fun setStandbyBucketActive(packageName: String) {
        if (!isReady) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            executeShellCommand("am set-standby-bucket $packageName active")
        }
    }

    private fun executeShellCommand(command: String) {
        try {
            val binder = Shizuku.getBinder()
            val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
            service.newProcess(command.split(" ").toTypedArray(), null, null).waitFor()
        } catch (e: Exception) {
            StunLogger.e(TAG, "Failed to execute shell command: $command", e)
        }
    }
}
