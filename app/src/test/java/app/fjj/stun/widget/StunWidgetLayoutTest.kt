package app.fjj.stun.widget

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import app.fjj.stun.R
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h851dp-xhdpi")
class StunWidgetLayoutTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun everyWidgetLayoutCanBeAppliedAsRemoteViews() {
        listOf(
            R.layout.widget_stun_capsule,
            R.layout.widget_stun_mini,
            R.layout.widget_stun_standard,
            R.layout.widget_stun_detail
        ).forEach { layout ->
            val view = RemoteViews(context.packageName, layout).apply(context, FrameLayout(context))
            assertNotNull(view.findViewById<View>(R.id.widget_root))
            assertTrue(view.findViewById<TextView>(R.id.widget_tv_name).text.isNotBlank())
        }
    }

    @Test
    fun everyWidgetHasARealFallbackPreview() {
        listOf(
            R.xml.widget_stun_capsule_info,
            R.xml.widget_stun_mini_info,
            R.xml.widget_stun_compact_info,
            R.xml.widget_stun_standard_info,
            R.xml.widget_stun_wide_info,
            R.xml.widget_stun_detail_info
        ).forEach { info ->
            val parser = context.resources.getXml(info)
            while (parser.eventType != XmlPullParser.START_TAG) parser.next()
            val preview = parser.getAttributeResourceValue(ANDROID_NS, "previewImage", 0)
            assertNotEquals(0, preview)
            assertNotEquals(R.drawable.ic_dashboard, preview)
            parser.close()
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
