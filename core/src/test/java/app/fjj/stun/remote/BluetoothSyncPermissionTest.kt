package app.fjj.stun.remote

import android.Manifest
import android.app.Application
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 锁住 BLUETOOTH_CONNECT 的**运行时**授权判定。
 *
 * 背景是一个真实崩溃（TV / Car 端启动即报）：
 * ```
 * SecurityException: Need android.permission.BLUETOOTH_CONNECT permission ... getAddress
 *   at BluetoothAdapter.getAddress(BluetoothAdapter.java:1631)
 *   at BluetoothSocket.<init>
 *   at BluetoothServerSocket.<init>
 *   at BluetoothAdapter.listenUsingRfcommWithServiceRecord(BluetoothAdapter.java:3231)
 * ```
 * API 31（Android 12）把 BLUETOOTH_CONNECT 改成运行时权限，而
 * `listenUsingRfcommWithServiceRecord()` **内部**会构造 `BluetoothSocket` 去读本机适配器地址 ——
 * 于是"只在 manifest 声明、没在运行时申请"的调用方一定抛 SecurityException，
 * RFCOMM 服务端永远 bind 不上（手机推节点到 TV 静默失效）。
 *
 * 所以 [BluetoothSyncManager.startServer] 必须在**碰适配器之前**先判权限，见下测试 3。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = Application::class)
class BluetoothSyncPermissionTest {

    // 与 :core 其它测试保持一致：只用 junit + robolectric，不为一个 Context 引 androidx.test。
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val context: Context get() = app

    @Test
    fun `未授予BLUETOOTH_CONNECT时判定为未授权`() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertFalse(BluetoothSyncManager.hasBluetoothConnectPermission(context))
    }

    @Test
    fun `已授予BLUETOOTH_CONNECT时判定为已授权`() {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(BluetoothSyncManager.hasBluetoothConnectPermission(context))
    }

    @Test
    fun `未授权时startServer返回false且不去碰适配器`() {
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        // 关键：没授权时绝不能走到 listenUsingRfcommWithServiceRecord —— 那条路在真机上就是崩溃点。
        assertFalse(BluetoothSyncManager.startServer(context))
    }

    @Test
    @Config(sdk = [30])
    fun `API30及以下不检查运行时权限`() {
        // 30 及以下 BLUETOOTH_CONNECT 还不存在，旧 BLUETOOTH 是安装即授予的普通权限。
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertTrue(BluetoothSyncManager.hasBluetoothConnectPermission(context))
    }
}
