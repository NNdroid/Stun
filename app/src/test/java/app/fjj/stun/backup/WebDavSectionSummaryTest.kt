package app.fjj.stun.backup

import android.app.Application
import app.fjj.stun.core.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 备份"到底备了什么"的回执文案链路：[BackupSection.labelRes] → 本地化名 → [WebDavBackupManager.sectionSummary]。
 *
 * 钉这条是因为它替代了原先写死的"节点 + 设置"：文案改成从分区注册表推导之后，
 * 新增/改名分区时必须**每个分区都声明 labelRes**，否则弹窗会漏报（用户以为订阅没备份），
 * 或者退化成把 `subscription_usage` 这种内部 id 直接甩到界面上。
 *
 * 需要真实资源（core 模块的 strings），所以挂 Robolectric 而不是裸 JUnit。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WebDavSectionSummaryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `每个已注册分区都声明了展示名`() {
        val missing = BackupSections.all.filter { it.labelRes == 0 }.map { it.id }
        assertTrue("这些分区没有 labelRes，备份完成弹窗会漏报：$missing", missing.isEmpty())
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `中文下按注册顺序用顿号拼出分区名`() {
        assertEquals(
            "全局设置、订阅列表、订阅流量",
            WebDavBackupManager.sectionSummary(context, listOf("settings", "subscription", "subscription_usage"))
        )
    }

    @Test
    @Config(qualifiers = "en-rUS")
    fun `英文下用逗号分隔_顺序不变`() {
        assertEquals(
            "global settings, subscriptions",
            WebDavBackupManager.sectionSummary(context, listOf("settings", "subscription"))
        )
    }

    @Test
    fun `未登记的 id 被丢掉_不把内部串甩给用户`() {
        assertEquals(
            context.getString(R.string.webdav_section_settings),
            WebDavBackupManager.sectionSummary(context, listOf("settings", "no_such_section"))
        )
    }

    @Test
    fun `空列表拼成空串_调用方据此走不含分区的短文案`() {
        assertEquals("", WebDavBackupManager.sectionSummary(context, emptyList()))
    }
}
