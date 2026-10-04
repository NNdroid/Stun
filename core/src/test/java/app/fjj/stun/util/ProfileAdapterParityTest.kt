package app.fjj.stun.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「三端节点列表必须共用 `ProfileRowAdapter`」的机械护栏。
 *
 * 三个 `ProfileAdapter{Car,XR,Wear}` 曾经各写一遍完整逻辑（60% 非空行逐字相同），
 * 而且**已经漂移出行为差异**：只有 XR 有 `updateDelays()`（批量回填），
 * car 逐个 `updateDelay()` ⇒ 一轮测速触发 N 次 `notifyDataSetChanged()`。
 *
 * 钉住三条：
 *  1. 三端适配器都继承 [app.fjj.stun.ui.ProfileRowAdapter]，各自只剩 `createRowViews`；
 *  2. 列表数据与延迟的持有字段只在基类里（不许在子类复制一份 `profiles` / `delayMap`）；
 *  3. 测速回填一律走 `updateDelays` 批量版。
 */
class ProfileAdapterParityTest {

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
            ?: throw AssertionError("从 ${File(".").absolutePath} 往上找不到 settings.gradle")
    }

    /** 剥掉注释只看代码：这些文件的 KDoc 里大量解释"为什么不自己写"（护栏存在的理由）。 */
    private fun code(relative: String): String {
        val f = File(repoRoot, relative)
        if (!f.isFile) throw AssertionError("找不到 ${relative}（解析为 ${f.absolutePath}）")
        val src = f.readText()
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src[i] == '"' -> {
                    val start = i; i++
                    while (i < src.length && src[i] != '"') { if (src[i] == '\\') i++; i++ }
                    i = (i + 1).coerceAtMost(src.length)
                    out.append(src, start, i)
                }
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i = (i + 2).coerceAtMost(src.length)
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private val adapters = listOf(
        "car" to "car/src/main/java/app/fjj/stun/car/ProfileAdapterCar.kt",
        "xr" to "xr/src/main/java/app/fjj/stun/xr/ProfileAdapterXR.kt",
        "wear" to "wear/src/main/java/app/fjj/stun/wear/ProfileAdapterWear.kt",
    )

    @Test
    fun allThreeAdaptersExtendTheSharedBase() {
        for ((name, path) in adapters) {
            val c = code(path)
            assertTrue("$name 适配器应继承 ProfileRowAdapter", c.contains("ProfileRowAdapter("))
            assertTrue("$name 应只实现 createRowViews", c.contains("override fun createRowViews"))
        }
    }

    /**
     * 子类不许自带数据源。
     *
     * 反事实：若有人图方便在子类里加一个 `private val profiles = mutableListOf<Profile>()`
     * 准备做局部优化，基类的 `getItemCount()` 与它就会脱节 —— 表现为"清空后列表还显示旧数据"
     * 这类极难查的问题。所以直接禁掉字段名。
     */
    @Test
    fun noAdapterKeepsItsOwnDataSource() {
        for ((name, path) in adapters) {
            val c = code(path)
            assertFalse("$name 不该自带 profiles 列表", c.contains("mutableListOf<Profile>"))
            assertFalse("$name 不该自带 delayMap", c.contains("mutableMapOf<String, String>"))
            assertFalse("$name 不该自带 selectedProfileId", c.contains("selectedProfileId"))
        }
    }

    /** 基类必须提供批量回填，且**不提供**单个版 —— 否则"逐个刷"这个坑会被再踩一次。 */
    @Test
    fun sharedBaseProvidesBatchDelayUpdateOnly() {
        val c = code("core/src/main/java/app/fjj/stun/ui/ProfileRowAdapter.kt")
        assertTrue("基类应提供 updateDelays 批量回填", c.contains("fun updateDelays("))
        assertFalse(
            "基类不该再提供单个 updateDelay（那是 N 次全量重绘的来源）",
            c.contains("fun updateDelay("),
        )
    }

    /**
     * 测速回填必须走批量版 —— **只针对共用基类的三端**。
     *
     * 这是本护栏存在的直接原因：car 曾是 `profiles.forEach { adapter.updateDelay(...) }`
     * ⇒ 一轮测速 N 次 `notifyDataSetChanged()`（整表重绘）；XR 早就是 `updateDelays(filled)`。
     *
     * ⚠️ **不要把 app 的 `ProfileAdapter` 与 tv 的 `ProfileAdapterTV` 也列进来**：
     * 那两个是独立的 `ListAdapter` + DiffUtil 实现，`updateDelay` 内部是
     * `notifyItemChanged(index, PAYLOAD_DELAY)`（**只刷一行**，且带 payload 避免整行重绑）。
     * 那里逐个调用是正确且高效的实现，不是漂移 —— 第一版把五个文件全列进去，
     * 测试直接变红，核对后确认是护栏范围划错，不是代码有问题。
     */
    @Test
    fun theThreeSharedBaseAdaptersDontPingNodesOneByOne() {
        val activities = listOf(
            "car" to "car/src/main/java/app/fjj/stun/car/CarMainActivity.kt",
            "xr" to "xr/src/main/java/app/fjj/stun/xr/XRMainActivity.kt",
            "wear" to "wear/src/main/java/app/fjj/stun/wear/WearMainActivity.kt",
        )
        val offenders = activities
            .map { (name, path) -> name to code(path) }
            // 形态是 forEach 里出现 updateDelay( —— 那就是逐次 notifyDataSetChanged
            .filter { (_, c) -> Regex("""forEach\s*\{[^}]*\.updateDelay\(""").containsMatchIn(c) }
            .map { it.first }
        assertTrue("这些地方逐个回填延迟，应改用 updateDelays 批量版：$offenders", offenders.isEmpty())
    }

    /**
     * 布局**刻意不合并** —— 这是有意决策，护栏钉住它以防将来有人"顺手去重"。
     *
     * `item_profile_{car,xr,wear}.xml` 看着像重复，实则是真实端差异：
     * 车机 64dp 带地址+延迟两行；手表 48dp 只有名称+协议徽章。
     * 合并要引入一堆 `visibility` 开关 + `?attr/` 条件，比留三份更难维护。
     */
    @Test
    fun rowLayoutsStayPerModule() {
        val modules = listOf("car", "xr", "wear")
        val layouts = modules.map { File(repoRoot, "$it/src/main/res/layout/item_profile_$it.xml") }
        assertEquals("三端各留一份自己的行布局（端差异，不要合并）", 3, layouts.count { it.isFile })
        // 确认真的是端差异：手表那份没有地址/延迟控件
        val wear = File(repoRoot, "wear/src/main/res/layout/item_profile_wear.xml").readText()
        assertFalse("手表布局本就没有地址控件", wear.contains("item_addr"))
        assertFalse("手表布局本就没有延迟控件", wear.contains("item_delay"))
    }
}
