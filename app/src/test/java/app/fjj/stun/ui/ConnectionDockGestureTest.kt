package app.fjj.stun.ui

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import app.fjj.stun.R
import app.fjj.stun.ui.view.ConnectionDockLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class ConnectionDockGestureTest {
    @Test fun tapTestsLatencyWithoutOpeningSheet() = withDock { dock, counts ->
        gesture(dock, 140f, 120f, 140f, 120f)
        assertEquals(listOf(1, 0, 0), counts.toList())
    }
    @Test fun smallFingerJitterIsStillATap() = withDock { dock, counts ->
        gesture(dock, 140f, 120f, 141f, 119f)
        assertEquals(listOf(1, 0, 0), counts.toList())
    }
    @Test fun swipeOpensWithoutTapOrLongPress() = withDock { dock, counts ->
        gesture(dock, 140f, 140f, 140f, 40f, duration = 120)
        assertEquals(listOf(0, 1, 0), counts.toList())
    }
    @Test fun slowDirectDragAlsoOpens() = withDock { dock, counts ->
        gesture(dock, 140f, 140f, 140f, 40f, duration = 1200)
        assertEquals(listOf(0, 1, 0), counts.toList())
    }
    @Test fun downwardDragDoesNotTriggerEitherAction() = withDock { dock, counts ->
        gesture(dock, 140f, 60f, 140f, 150f)
        assertEquals(listOf(0, 0, 0), counts.toList())
    }
    @Test fun sidewaysDragDoesNotTriggerEitherAction() = withDock { dock, counts ->
        gesture(dock, 140f, 120f, 260f, 100f)
        assertEquals(listOf(0, 0, 0), counts.toList())
    }
    @Test fun shortDragDoesNotBecomeATap() = withDock { dock, counts ->
        val short = ViewConfiguration.get(dock.context).scaledTouchSlop + 1f
        gesture(dock, 140f, 120f, 140f, 120f - short)
        assertEquals(listOf(0, 0, 0), counts.toList())
    }
    @Test fun cancelledSwipeDoesNothing() = withDock { dock, counts ->
        gesture(dock, 140f, 140f, 140f, 40f, cancel = true)
        assertEquals(listOf(0, 0, 0), counts.toList())
    }
    @Test fun buttonTapOnlyDisconnects() = withDock { dock, counts ->
        val rect = buttonBounds(dock)
        gesture(dock, rect.exactCenterX(), rect.exactCenterY(), rect.exactCenterX(), rect.exactCenterY())
        assertEquals(listOf(0, 0, 1), counts.toList())
    }
    @Test fun swipeStartingOnButtonCancelsItsClick() = withDock { dock, counts ->
        val rect = buttonBounds(dock)
        gesture(dock, rect.exactCenterX(), rect.exactCenterY(), rect.exactCenterX(), rect.exactCenterY() - 100)
        assertEquals(listOf(0, 1, 0), counts.toList())
    }

    private fun withDock(test: (ConnectionDockLayout, IntArray) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java)
        controller.get().setTheme(R.style.Theme_Stun)
        controller.setup()
        val home = LayoutInflater.from(controller.get()).inflate(R.layout.fragment_home, null)
        val dock = home.findViewById<ConnectionDockLayout>(R.id.bottom_container)
        (dock.parent as ViewGroup).removeView(dock)
        controller.get().setContentView(dock)
        dock.measure(View.MeasureSpec.makeMeasureSpec(786, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        dock.layout(0, 0, dock.measuredWidth, dock.measuredHeight)
        val counts = IntArray(3) // latency click, expand, connect/disconnect
        dock.setOnClickListener { counts[0]++ }
        dock.onSwipeUp = { counts[1]++ }
        dock.findViewById<View>(R.id.fab_start_stop).setOnClickListener { counts[2]++ }
        try { test(dock, counts) } finally { controller.pause().stop().destroy() }
    }

    private fun buttonBounds(dock: ConnectionDockLayout): Rect {
        val button = dock.findViewById<View>(R.id.fab_start_stop)
        return Rect(0, 0, button.width, button.height).apply { dock.offsetDescendantRectToMyCoords(button, this) }
    }

    private fun gesture(dock: ConnectionDockLayout, x: Float, y: Float, endX: Float, endY: Float,
                        duration: Long = 200, cancel: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, px: Float, py: Float) {
            val event = MotionEvent.obtain(down, at, action, px, py, 0)
            dock.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, down, x, y)
        send(MotionEvent.ACTION_MOVE, down + duration / 2, endX, endY)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, down + duration, endX, endY)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
