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

    /**
     * 检查 Shizuku 服务是否在后台真正运行 (安全无异常)
     */
    fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            // 极少数情况下可能会报 LinkageError 或其他异常，做个兜底
            false
        }
    }

    /**
     * 修复后的 isReady：先查服务，再查权限
     */
    fun isReady(): Boolean {
        // 第一道防线：如果没有安装或未启动，直接返回 false，防止崩溃
        if (!isAvailable()) {
            return false
        }

        // 第二道防线：服务可用时，再检查是否被授权
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 核心封装：使用协程挂起函数隐藏 Listener
     * 调用此方法会挂起当前协程，直到用户做出授权选择或直接返回结果
     *
     * @return true 表示已授权，false 表示拒绝或服务不可用
     */
    suspend fun requestPermissionAwait(): Boolean = suspendCancellableCoroutine { continuation ->
        StunLogger.i(TAG, "requestPermissionAwait called")
        // 拦截 1：如果 Shizuku 根本没运行，直接回调失败
        if (!isAvailable()) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        // 拦截 2：如果已经有权限了，直接回调成功
        if (isReady()) {
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
        if (!isReady()) return // 调用前由外部保证权限，这里只做最后一道防线拦截
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
        if (!isReady()) return
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
