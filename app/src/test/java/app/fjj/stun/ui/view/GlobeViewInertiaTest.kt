package app.fjj.stun.ui.view

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import app.fjj.stun.R
import java.time.Duration
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * 松手后的**惯性**必须"接着手指的速度走"，而不是自己另起一套速度。
 *
 * 为什么要单独立一条：`rotateBy()` 把像素位移按 `ROTATE_DEG_PER_DP / density` 换成角度，
 * 而惯性用的是"像素/秒"。这两个量纲一旦在惯性那条路上漏掉换算，症状非常隐蔽 ——
 * 不崩、不报错，只是松手后球比手指快 `density / ROTATE_DEG_PER_DP` 倍（xhdpi 5 倍、
 * xxhdpi 7.5 倍）：甩一下能空转好几圈，最后停在一个跟手指动作毫无关系的经度上，
 * 看上去就像"贴图/朝向随机画错了"。
 *
 * 判据取**比值**而不是绝对值，正是为了让这条测试与密度无关地成立 —— 换算漏了的话，
 * 两个密度下会分别得到 5 和 7.5，一次就露馅。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlobeViewInertiaTest {

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-xhdpi")
    fun `松手后的惯性角速度与手指拖动的角速度一致_xhdpi`() {
        assertInertiaFollowsFinger(expectDensity = 2f)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w393dp-h851dp-xxhdpi")
    fun `松手后的惯性角速度与手指拖动的角速度一致_xxhdpi`() {
        assertInertiaFollowsFinger(expectDensity = 3f)
    }

    private fun assertInertiaFollowsFinger(expectDensity: Float) {
        val themed = themedContext()
        val view = newSizedView(themed)
        val density = view.resources.displayMetrics.density
        assertTrue(
            "测试前提：限定符要真的把密度定在 $expectDensity，实测 $density",
            abs(density - expectDensity) < 0.01f,
        )

        // ── 拖一段"快甩"：50ms 内 200px ⇒ 手指角速度 = 200 × (0.4/density) / 0.05 度/秒
        val claimPx = 20f * density
        touch(view, MotionEvent.ACTION_DOWN, START_X, START_Y)
        ShadowSystemClock.advanceBy(Duration.ofMillis(CLAIM_MS))
        touch(view, MotionEvent.ACTION_MOVE, START_X + claimPx, START_Y)

        val lonBeforeFlick = view.debugCameraLonDeg()
        ShadowSystemClock.advanceBy(Duration.ofMillis(FLICK_MS))
        touch(view, MotionEvent.ACTION_MOVE, START_X + claimPx + FLICK_PX, START_Y)
        val lonAfterFlick = view.debugCameraLonDeg()
        val fingerDegPerSec = (lonAfterFlick - lonBeforeFlick) / (FLICK_MS / 1000f)
        assertTrue(
            "测试前提：这一甩必须真的转了相机（实测 $lonBeforeFlick → $lonAfterFlick）",
            abs(fingerDegPerSec) > 50f,
        )

        // ── 松手，第一帧的位移就是惯性的起始角速度
        touch(view, MotionEvent.ACTION_UP, START_X + claimPx + FLICK_PX, START_Y)
        val lonAtRelease = view.debugCameraLonDeg()
        view.advance(FRAME_SECONDS)
        var inertialDelta = view.debugCameraLonDeg() - lonAtRelease
        if (inertialDelta > 180f) inertialDelta -= 360f
        if (inertialDelta < -180f) inertialDelta += 360f
        val inertiaDegPerSec = inertialDelta / FRAME_SECONDS

        val ratio = inertiaDegPerSec / fingerDegPerSec
        assertTrue(
            "松手后的惯性角速度必须与手指一致（density=$density 手指=${"%.1f".format(fingerDegPerSec)}°/s " +
                "惯性=${"%.1f".format(inertiaDegPerSec)}°/s 比值=${"%.2f".format(ratio)}；" +
                "期望 ≈ 1，量纲漏换算会是 ${"%.1f".format(expectDensity / GlobeView.ROTATE_DEG_PER_DP)}）",
            abs(ratio - 1f) < 0.05f,
        )

        // 方向也要一致：往右甩就继续往同一个方向转（符号不能翻）。
        assertTrue("惯性的转向必须与手指一致（比值 $ratio 应为正）", ratio > 0f)
    }

    private fun touch(view: GlobeView, action: Int, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        view.onTouchEvent(event)
        event.recycle()
    }

    private fun newSizedView(themed: Context): GlobeView {
        val view = GlobeView(themed)
        val width = (GLOBE_SIZE_DP * view.resources.displayMetrics.density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
        return view
    }

    private fun themedContext(): Context {
        val controller = Robolectric.buildActivity(Activity::class.java)
        val activity = controller.get()
        val config = Configuration(activity.resources.configuration).apply { fontScale = 1f }
        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Stun)
        controller.setup()
        return themed
    }

    private companion object {
        const val GLOBE_SIZE_DP = 300f
        const val START_X = 80f
        const val START_Y = 120f

        /** 建"接管"的那一步：20dp，必定超过 touch slop（8dp）。 */
        const val CLAIM_MS = 20L

        /** 被测的那一段：50ms 内拖 [FLICK_PX] 像素。 */
        const val FLICK_MS = 50L

        /** 用**像素**而不是 dp：甩的手感是按屏幕距离算的。 */
        const val FLICK_PX = 200f

        /** 与 [GlobeView] 的帧回调一致（60fps）。 */
        const val FRAME_SECONDS = 1f / 60f
    }
}
