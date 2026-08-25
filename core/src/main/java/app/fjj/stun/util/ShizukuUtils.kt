package app.fjj.stun.util

import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import app.fjj.stun.repo.StunLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlin.coroutines.resume

object ShizukuUtils {
    private const val TAG = "ShizukuUtils"
    const val SHIZUKU_REQUEST_CODE = 1001

    /**
     * 检查 Shizuku 服务是否在后台真正运行
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
     * isReady：先查服务，再查权限
     */
    fun isReady(): Boolean {
        // 如果没有安装或未启动，直接返回 false，防止崩溃
        if (!isAvailable()) {
            return false
        }

        // 服务可用时，再检查是否被授权
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 使用协程挂起函数隐藏 Listener
     * 调用此方法会挂起当前协程，直到用户做出授权选择或直接返回结果
     *
     * @return true 表示已授权，false 表示拒绝或服务不可用
     */
    suspend fun requestPermissionAwait(): Boolean = suspendCancellableCoroutine { continuation ->
        StunLogger.i(TAG, "requestPermissionAwait called")
        // 如果 Shizuku 根本没运行，直接回调失败
        if (!isAvailable()) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        // 如果已经有权限了，直接回调成功
        if (isReady()) {
            continuation.resume(true)
            return@suspendCancellableCoroutine
        }

        // 如果服务未启动，直接返回 false
        if (Shizuku.isPreV11() || !Shizuku.pingBinder()) {
            StunLogger.w(TAG, "Shizuku is not running or version is too low.")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        // 如果用户曾经勾选了"不再询问"并拒绝
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            StunLogger.w(TAG, "Shizuku permission denied by user (never ask again).")
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }

        // 创建一个局部 Listener
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

        // 注册监听器并处理协程取消的情况
        Shizuku.addRequestPermissionResultListener(listener)
        continuation.invokeOnCancellation {
            Shizuku.removeRequestPermissionResultListener(listener)
        }

        // 真正发起权限请求
        Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
    }

    fun addSelfToBatteryWhitelist(packageName: String) {
        if (!isReady()) return

        // Doze 模式 (deviceidle) 是从 Android 6.0 (API 23 / M) 引入s的
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 彻底抛弃反射，改用极度稳定的 cmd 指令
            val command = arrayOf("cmd", "deviceidle", "whitelist", "+$packageName")
            executeShellCommandSafely(command)
        } else {
            // Android 6.0 以下没有此机制，直接跳过
            StunLogger.i("ShizukuUtils", "系统版本低于 Android 6.0，无需添加电池白名单")
        }
    }

    fun setStandbyBucketActive(packageName: String) {
        if (!isReady()) return

        // 应用活跃桶 (Standby Buckets) 是从 Android 9.0 (API 28 / P) 引入的
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val command = arrayOf("am", "set-standby-bucket", packageName, "active")
            executeShellCommandSafely(command)
        } else {
            // Android 9.0 以下没有此机制，直接跳过
            StunLogger.i("ShizukuUtils", "系统版本低于 Android 9.0，无需设置活跃桶")
        }
    }

    /**
     * Shizuku 命令执行器
     */
    private fun executeShellCommandSafely(command: Array<String>) {
        thread(name = "ShizukuShellWorker") {
            var remoteProcess: moe.shizuku.server.IRemoteProcess? = null
            try {
                val binder = Shizuku.getBinder()
                val service = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
                remoteProcess = service.newProcess(command, null, null)

                remoteProcess?.inputStream?.let { pfd ->
                    val reader = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd)))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // StunLogger.d(TAG, "Shell Output: $line")
                    }
                }

                // 同时消耗错误流，万无一失
                remoteProcess?.errorStream?.let { pfd ->
                    val reader = BufferedReader(InputStreamReader(ParcelFileDescriptor.AutoCloseInputStream(pfd)))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        // StunLogger.e(TAG, "Shell Error: $line")
                    }
                }

                // 阻塞等待执行完毕
                val exitCode = remoteProcess?.waitFor() ?: -1

                if (exitCode == 0) {
                    StunLogger.i(TAG, "✅ 成功执行: ${command.joinToString(" ")}")
                } else {
                    StunLogger.e(TAG, "❌ 执行失败 (ExitCode $exitCode): ${command.joinToString(" ")}")
                }
            } catch (e: Exception) {
                StunLogger.e(TAG, "执行 Shizuku 命令时发生异常: ${command.joinToString(" ")}", e)
            } finally {
                remoteProcess?.destroy()
            }
        }
    }
}
