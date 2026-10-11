package app.fjj.stun.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.util.LauncherIconManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 桌面图标外观切换的契约。分两块断：
 *
 * 1. **manifest 侧**（桌面到底会看到什么）：用 [PackageManager.queryIntentActivities] 去看
 *    启动查询的真实结果，而不是去读 alias 的字段 —— 前者就是 launcher 实际会拿到的东西。
 *    带上 [PackageManager.MATCH_DISABLED_COMPONENTS] 才能把 `enabled="false"` 的四个 alias
 *    也列出来，否则永远只看到一个入口，「五套图标都在」这件事根本没法验证。
 * 2. **运行时侧**（切换有没有真的生效）：`setComponentEnabledSetting` 写下去的覆盖值要逐个
 *    读回核对。不靠 `queryIntentActivities` 反映覆盖 —— Robolectric 的启动查询走原生实现，
 *    不读 shadow 里那份覆盖表，用它判会误报。
 *
 * 判据选「恰好一个 ENABLED」而不是「选中的那个 ENABLED」：如果两处都漏禁，
 * 桌面上会同时出现两个 Stun 图标，用户会以为自己点了两次。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LauncherIconThemeTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val pm: PackageManager get() = ctx.packageManager
    private val launchIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private fun component(alias: String) = ComponentName(ctx.packageName, alias)
    private fun full(alias: String) = "${ctx.packageName}.$alias"

    private fun enabledState(alias: String) = pm.getComponentEnabledSetting(component(alias))

    /** 桌面启动查询的结果（保留 manifest 里 `enabled="false"` 的条目）。 */
    private fun allLauncherAliases() = pm.queryIntentActivities(
        launchIntent, PackageManager.MATCH_DISABLED_COMPONENTS
    ).map { it.activityInfo }

    // ─────────────────────────── 1. 五档外观的静态表本身要自洽 ───────────────────────────

    @Test
    fun `五档外观各有独立 id 与可用资源`() {
        assertEquals("外观档数应与 manifest 里的 alias 数一致", 5, LauncherIconManager.THEMES.size)
        assertEquals("id 不能重复", 5, LauncherIconManager.THEMES.map { it.id }.distinct().size)
        assertEquals("alias 短类名不能重复", 5, LauncherIconManager.THEMES.map { it.aliasClassName }.distinct().size)

        for (theme in LauncherIconManager.THEMES) {
            assertTrue("id 必须是合法取值之一：${theme.id}", theme.id in SettingsManager.ICON_THEMES)
            assertTrue("预览图资源不能是 0：${theme.id}", theme.iconRes != 0)
            // labelRes 走运行时解析 —— 编译期只保证 R 里有这个字段，不保证它被合并进资源表。
            assertTrue("标签文案必须非空：${theme.id}", ctx.getString(theme.labelRes).isNotBlank())
            assertEquals("byId 应按 id 精确命中", theme, LauncherIconManager.byId(theme.id))
        }
        assertEquals("未知 id 兜回默认档", LauncherIconManager.THEMES.first(), LauncherIconManager.byId("bogus"))
    }

    @Test
    fun `脏取值兜回默认档而不是抛错`() {
        // 云端快照回传、历史版本或误写入都可能带来不认识的值。曾经这里用 first {}，
        // 不认识的值会直接 NoSuchElementException —— 冷启动那步被 startupStep 吞掉，
        // 设置页点选则是当场崩。
        for (bad in listOf("", "aurora ", "AURORA", "glass_aurora")) {
            LauncherIconManager.apply(ctx, bad)
            assertEquals("脏值应兜回默认档：$bad", SettingsManager.ICON_THEME_DEFAULT, SettingsManager.getIconTheme(ctx))
        }
        // 写端同样兜底：不认识的值不按原样落盘。
        SettingsManager.saveIconTheme(ctx, "not_a_theme")
        assertEquals(SettingsManager.ICON_THEME_DEFAULT, SettingsManager.getIconTheme(ctx))
    }

    // ─────────────────────────── 2. manifest 侧：桌面启动查询能看到的五个入口 ───────────────────────────

    @Test
    fun `manifest 合并后五个启动 alias 都在启动查询里`() {
        val names = allLauncherAliases().map { it.name.substringAfterLast('.') }
        assertEquals(
            "五个 alias 都应能作为桌面启动入口",
            LauncherIconManager.THEMES.map { it.aliasClassName }.sorted(), names.sorted()
        )
    }

    @Test
    fun `默认状态只有一个 alias 是启用的`() {
        val enabled = LauncherIconManager.THEMES.map { it.aliasClassName }
            .filter { pm.getComponentEnabledSetting(component(it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        assertTrue("初始不应有任何覆盖写入（实际：$enabled）", enabled.isEmpty())
        assertEquals(
            "按 manifest 默认值只有一个入口，默认应是经典款",
            1, allLauncherAliases().filter { it.enabled }.size
        )
    }

    @Test
    fun `MainActivity 自身不能再带启动过滤器`() {
        // 还带的话，桌面会出现 6 个图标（5 个外观 + 1 个真身）。
        val entries = allLauncherAliases().map { it.name }
        assertTrue(
            "MainActivity 出现在桌面启动查询里：$entries",
            entries.none { it.endsWith(".ui.MainActivity") }
        )
    }

    @Test
    fun `五个 alias 各有一套图标且与设置页预览图一致`() {
        // 预览图与桌面实际用的图必须是同一个资源，否则设置页那张预览就是骗人的。
        val seenIcons = mutableSetOf<Int>()
        for (theme in LauncherIconManager.THEMES) {
            val aliasName = full(theme.aliasClassName)
            val info = allLauncherAliases().firstOrNull { it.name == aliasName }
            assertTrue("alias ${theme.aliasClassName} 不在启动查询里", info != null)
            val icon = info!!.icon
            assertTrue("${theme.aliasClassName} 必须导出", info.exported)
            assertTrue("${theme.aliasClassName} 必须指定图标", icon != 0)
            assertEquals("${theme.aliasClassName} 预览图与桌面图标不一致", theme.iconRes, icon)
            seenIcons.add(icon)
        }
        assertEquals("五档图标必须是五个不同资源", 5, seenIcons.size)
    }

    @Test
    fun `静态快捷方式 meta-data 必须在每个 alias 上重复声明`() {
        // 静态快捷方式按「启动组件」查 meta-data。MainActivity 不再带 MAIN/LAUNCHER 之后，
        // 只把 meta-data 留在 Activity 上会让长按菜单的静态项在所有外观下都丢。
        //
        // 这里读合并后的 manifest 而不是走 PackageManager：Robolectric 的启动查询走原生实现，
        // 不把 alias 的 meta-data 填进 ActivityInfo（恒为 null），用 PM 判会假红。
        // 合并产物恰是最终打进 APK 的那份，比读源码更贴近实际。
        val blocks = aliasBlocks(mergedUnitTestManifest().readText())
        for (theme in LauncherIconManager.THEMES) {
            val block = blocks[theme.aliasClassName]
            assertTrue(
                "${theme.aliasClassName} 的 activity-alias 块没找到",
                block != null
            )
            assertTrue(
                "${theme.aliasClassName} 缺 android.app.shortcuts（长按静态快捷方式会丢）",
                "android.app.shortcuts" in block!!
            )
        }
    }

    /** 把 manifest 里的 activity-alias 声明（含子节点）按 alias 短类名拆开。 */
    private fun aliasBlocks(manifest: String): Map<String, String> {
        val blocks = mutableMapOf<String, String>()
        var pos = 0
        while (true) {
            val open = manifest.indexOf("<activity-alias", pos)
            if (open < 0) break
            val end = manifest.indexOf("</activity-alias>", open)
            if (end < 0) break
            val block = manifest.substring(open, end)
            pos = end
            Regex("android:name=\"[^\"]*\\.([A-Za-z0-9_]+)\"").find(block)
                ?.groupValues?.get(1)?.let { blocks[it] = block }
        }
        return blocks
    }

    private fun mergedUnitTestManifest(): File {
        val rel = "build/intermediates/merged_manifest/debugUnitTest/mergeDebugUnitTestManifest/AndroidManifest.xml"
        listOf(File(rel), File("../$rel"))
            .firstOrNull { it.isFile }
            ?.let { return it }
        throw AssertionError("找不到合并后的单元测试 manifest（工作目录 ${File(".").absolutePath}）")
    }

    // ─────────────────────────── 3. 运行时侧：任何时刻恰好一个 ENABLED ───────────────────────────

    @Test
    fun `切换后每个 alias 的组件状态覆盖都写对了`() {
        val target = LauncherIconManager.THEMES[3]
        LauncherIconManager.apply(ctx, target.id)

        for (theme in LauncherIconManager.THEMES) {
            val expected = if (theme == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            assertEquals(
                "${theme.aliasClassName} 的覆盖值不对（选中 ${target.aliasClassName}）",
                expected, enabledState(theme.aliasClassName)
            )
        }
        assertEquals("偏好应落盘", target.id, SettingsManager.getIconTheme(ctx))
    }

    @Test
    fun `来回切换后覆盖状态不漂移`() {
        // 覆盖「切到当前已启用档」这种幂等情况，以及来回切导致的中间状态。
        val t = LauncherIconManager.THEMES
        listOf(t[1].id, t[4].id, t[0].id, t[1].id, t[2].id).forEach { LauncherIconManager.apply(ctx, it) }

        for (theme in LauncherIconManager.THEMES) {
            val expected = if (theme == t[2]) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            assertEquals("${theme.aliasClassName} 的覆盖值不对", expected, enabledState(theme.aliasClassName))
        }
        assertEquals("偏好应跟着最后一次选择走", t[2].id, SettingsManager.getIconTheme(ctx))
    }

    @Test
    fun `冷启动会按已保存偏好重写组件状态`() {
        // 组件状态可能被降级安装或第三方清组件工具改掉，所以每次冷启动都重算一遍。
        SettingsManager.saveIconTheme(ctx, SettingsManager.ICON_THEME_GRAPE)
        LauncherIconManager.applyOnStartup(ctx)

        assertEquals(
            "冷启动后应只有 LauncherGrape 被启用",
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED, enabledState("LauncherGrape")
        )
        assertEquals(
            "经典款应被禁用",
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED, enabledState("LauncherDefault")
        )
    }
}
