package app.fjj.stun.service

import android.app.Application
import android.content.Intent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * TV 端「打开即闪退」回归（2026-10 线上事故）。
 *
 * TV 的 launcher 挂的是 `LEANBACK_LAUNCHER`（没有 `LAUNCHER` category），
 * [android.content.pm.PackageManager.getLaunchIntentForPackage] 在 TV 上返回 **null**；
 * 保活服务的通知构建曾把 null 直接塞进 [android.app.PendingIntent.getActivity] →
 * NPE → 服务在 `MainActivity.onCreate` 里被拉起时把整个 App 带崩。
 *
 * 本测试的 Robolectric 环境天然复刻 TV 条件：测试包没有 LAUNCHER activity，
 * `getLaunchIntentForPackage` 必为 null。修复后的实现应退化为「无点击动作的通知」，
 * `onStartCommand` 正常完成并进入前台。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebConsoleKeepAliveServiceTest {

    @Test
    fun `无 LAUNCHER 的环境（TV）下 onStartCommand 不崩并进入前台`() {
        val app = RuntimeEnvironment.getApplication()
        // 前置确认：测试环境确实拿不到 launch intent（= TV 场景），否则测试失去针对性
        assertTrue(
            "测试包应当没有 LAUNCHER activity（复刻 TV 场景的前提）",
            app.packageManager.getLaunchIntentForPackage(app.packageName) == null,
        )

        val service = Robolectric.setupService(WebConsoleKeepAliveService::class.java)
        // 修复前：startForegroundNow → PendingIntent.getActivity(null) → NPE（App 闪退）
        service.onStartCommand(null, 0, 1)

        // 必须真的调用了 startForeground（否则 5 秒后会换另一种崩法）
        assertTrue(
            "保活服务应已进入前台",
            Shadows.shadowOf(service).lastForegroundNotification != null,
        )
    }
}
