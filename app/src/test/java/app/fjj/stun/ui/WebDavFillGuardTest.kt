package app.fjj.stun.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebDAV 设置卡片「回填绝不落盘」护栏。
 *
 * 回填（fillWebDavUi）曾把「自动备份开关」的 setChecked 放在分区复选框回填**之前**，
 * 而该开关的监听链（setWebDavAutoBackup → saveWebDavConfigIfComplete →
 * captureWebDavSyncSections）会读三个分区复选框的**即时**状态并整体落盘 ——
 * 回填中途被它读到 XML 默认的全未勾选，就把空选集持久化：
 * 用户上次保存的勾选在**每次打开设置页**时被当场清空，
 * 症状正是「同步内容勾选无法保存，重开回显默认」。
 * 这里钉死两条不变量：守卫先于开关回填生效、监听链在守卫期内直接返回。
 */
class WebDavFillGuardTest {

    private val fragment: String =
        File("src/main/java/app/fjj/stun/ui/SettingsFragment.kt").readText()

    @Test
    fun `回填期间自动备份开关的监听链必须被守卫拦截`() {
        val fn = fragment.substringAfter("private fun setWebDavAutoBackup")
            .substringBefore("\n    private fun")
        assertTrue(
            "setWebDavAutoBackup 必须在回填期间直接返回，不得落盘",
            fn.contains("if (isLoadingWebDavSections) return")
        )
    }

    @Test
    fun `fillWebDavUi 的守卫必须先于自动备份开关的回填生效`() {
        val fill = fragment.substringAfter("private fun fillWebDavUi")
        val guardPos = fill.indexOf("isLoadingWebDavSections = true")
        val switchPos = fill.indexOf("switchWebdavAuto.isChecked")
        assertTrue("fillWebDavUi 必须置回填守卫", guardPos >= 0)
        assertTrue("fillWebDavUi 必须回填 switchWebdavAuto", switchPos >= 0)
        assertTrue(
            "守卫必须先于 switchWebdavAuto.isChecked —— 否则监听链在守卫生效前就把未回填的复选框落盘",
            guardPos < switchPos
        )
    }

    @Test
    fun `分区复选框的回填必须在守卫生效期内完成`() {
        val fill = fragment.substringAfter("private fun fillWebDavUi")
            .substringBefore("val modes =")
        val guardSet = fill.indexOf("isLoadingWebDavSections = true")
        val guardReset = fill.indexOf("isLoadingWebDavSections = false")
        val profiles = fill.indexOf("cbWebdavSectionProfiles.isChecked")
        assertTrue("守卫必须置位", guardSet >= 0)
        assertTrue("守卫必须复位", guardReset > guardSet)
        assertTrue(
            "分区复选框回填必须发生在守卫置位之后",
            profiles > guardSet
        )
    }
}
