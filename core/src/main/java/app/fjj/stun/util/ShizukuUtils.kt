package app.fjj.stun.util

import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import app.fjj.stun.repo.StunLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/** Shizuku 的三态。**唯一**判定入口是 [ShizukuUtils.state]，不要在调用点重新拼 `isAvailable/isReady`。 */
enum class ShizukuState {
    /** 服务在跑且已授权 —— 可以执行命令。 */
    READY,

    /** 服务在跑但没授权 —— 需要用户在设备上确认（弹窗只显示在设备屏幕上）。 */
    NO_PERMISSION,

    /** Shizuku 没运行 / 版本过低 —— 任何操作都不可能成功。 */
    NOT_RUNNING,
}

/**
 * **Shizuku 能力的唯一入口。**
 *
 * 这里只放"和 Shizuku 本身打交道"的三件事：状态判定、授权、执行命令。
 * **刻意不放**"给 app 加省电豁免"这类业务意图 —— 那属于 [BackgroundExemptions]，
 * 因为同一意图还有一条 root 通道（见那里的说明）。原先两者混在一起，导致"加白名单"这段
 * 在仓库里被复制了 4 份（两个 Service 各一份、`KeepAliveManager` 一份、`HomeFragment` 一份），
 * 而且 4 份都各自包了一层 `if (isReady())` —— 而真正执行的两个函数内部还会再判一次。
 *
 * Shizuku API 的三个坑（都在这里被兜住了，别在调用点重复处理）：
 *  - `pingBinder()` 在极少数 ROM 上抛的是 `Error`/链接错误，不是 `Exception`；
 *  - 授权结果只能靠 listener 回调，没有同步查询接口；
 *  - `requestPermission()` / `addRequestPermissionResultListener()` 在 binder 死亡时抛
 *    `RuntimeException` 而不是返回值，必须兜住并 `resume(false)`，否则 continuation 永远不被
 *    恢复（见 [requestPermissionInternal]）。
 */
object ShizukuUtils {
    private const val TAG = "ShizukuUtils"
    const val SHIZUKU_REQUEST_CODE = 1001

    /**
     * 授权弹窗最长等待。弹窗是显示在**设备屏幕**上的，用户可能根本不在跟前
     *（远程 WebUI），也可能就在屏幕前却忽略了弹窗 —— 无论哪种都不能无限挂着调用方。
     * 超时收在**这里**而不是各调用点：Shizuku 只有 listener 回调、没有同步查询接口，
     * 谁调都有挂死的可能，所以由这个唯一封装兜住（见 [requestPermissionAwait]）。
     */
    private const val PERMISSION_REQUEST_TIMEOUT_MS = 60_000L

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
    fun isReady(): Boolean = state() == ShizukuState.READY

    /**
     * 三态判定。这是全仓库唯一一处"Shizuku 到底能不能用"的推断。
     *
     * ⚠️ 顺序有讲究：先 `isAvailable()`（服务在跑吗），再 `checkSelfPermission()`（授权了吗）。
     * 反过来会在 Shizuku 没启动时直接去问权限，那一步会抛。
     */
    fun state(): ShizukuState {
        if (!isAvailable()) return ShizukuState.NOT_RUNNING
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
        return if (granted) ShizukuState.READY else ShizukuState.NO_PERMISSION
    }

    /**
     * 使用协程挂起函数隐藏 Listener
     * 调用此方法会挂起当前协程，直到用户做出授权选择或直接返回结果。
     *
     * **保证在 [PERMISSION_REQUEST_TIMEOUT_MS] 内返回**：Shizuku 只有 listener 回调、没有同步
     * 查询接口，用户不理弹窗（或根本不在设备前）时会一直挂住调用方。所以超时收在这一层统一兜住，
     * 而不是让每个调用点各自包一遍 `withTimeoutOrNull`。
     *
     * @return true 表示已授权，false 表示拒绝、服务不可用或等待超时
     */
    suspend fun requestPermissionAwait(): Boolean =
        withTimeoutOrNull(PERMISSION_REQUEST_TIMEOUT_MS) { requestPermissionInternal() } ?: false

    /** [requestPermissionAwait] 的实际实现。不做超时，由外层统一兜。 */
    private suspend fun requestPermissionInternal(): Boolean = suspendCancellableCoroutine { continuation ->
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

        // 刻意**不**先查 `shouldShowRequestPermissionRationale()`：它的返回值语义随 Shizuku
        // 服务端实现变化（13.1.5 客户端只透传 binder attach 时下发的一个布尔，语义无法从
        // 客户端 jar 确认），拿它做「问不了」的判定会误杀首问场景。要挡的「勾了不再询问」
        // Shizuku 自己也不会回调，下方 `withTimeoutOrNull` 的超时已经兜住。

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

        try {
            // 注册监听器并处理协程取消的情况
            Shizuku.addRequestPermissionResultListener(listener)
            continuation.invokeOnCancellation {
                Shizuku.removeRequestPermissionResultListener(listener)
            }

            // 真正发起权限请求
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (e: Exception) {
            // addRequestPermissionResultListener / requestPermission 在 binder 死亡时抛的是
            // RuntimeException，不是返回值。这里必须 resume(false)：continuation 一旦不被
            // resume，调用方会一直挂到 PERMISSION_REQUEST_TIMEOUT_MS 超时——在启动链路里那
            // 不只是 Shizuku 没授权，VPN 也起不来。
            // 走到 catch 时 listener 可能已注册；removeRequestPermissionResultListener 幂等。
            Shizuku.removeRequestPermissionResultListener(listener)
            StunLogger.e(TAG, "Failed to request Shizuku permission: ${e.message}")
            continuation.resume(false)
        }
    }

    /**
     * Shizuku 命令执行器（**阻塞**版，等命令跑完并返回退出码）。
     *
     * 「做完要确认结果」的路径必须用这个：拿不到退出码就只能盲写设置，一旦命令实际失败，
     * 设置页上的开关就会撒谎（显示已开启，实际什么都没生效）。返回 -1 = 异常/取不到退出码。
     */
    fun executeShellCommand(command: Array<String>): Int {
        var remoteProcess: moe.shizuku.server.IRemoteProcess? = null
        return try {
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
                StunLogger.i(TAG, "✅ Executed: ${command.joinToString(" ")}")
            } else {
                StunLogger.e(TAG, "❌ Execution failed (exit code $exitCode): ${command.joinToString(" ")}")
            }
            exitCode
        } catch (e: Exception) {
            StunLogger.e(TAG, "Exception while running Shizuku command: ${command.joinToString(" ")}", e)
            -1
        } finally {
            remoteProcess?.destroy()
        }
    }

    /**
     * 一次性 fire-and-forget 版：丢到后台线程跑，只记日志，不等结果、也不返回退出码。
     *
     * 给"写了就行、不需要确认"的路径用（如省电豁免这种"重复断言无害"的加固动作）。
     * ⚠️ 涉及开关/设置的写入**不要**用它 —— 那必须用 [executeShellCommand] 拿退出码，
     * 否则失败时开关会撒谎。
     */
    fun executeShellCommandAsync(command: Array<String>) {
        thread(name = "ShizukuShellWorker") {
            executeShellCommand(command)
        }
    }

    /**
     * API 级别门槛。低于门槛的机制根本不存在，直接跳过并记日志 ——
     * 调用点不必再各自写一遍 `Build.VERSION.SDK_INT` 判断。
     */
    fun isAtLeast(api: Int): Boolean = Build.VERSION.SDK_INT >= api
}
