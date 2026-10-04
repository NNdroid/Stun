package app.fjj.stun.remote

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「保活与后台」两个开关的机械护栏。
 *
 * 这块功能横跨 5 个文件（`index.html` / `app.js` ×2 处逻辑 / `KeepAliveManager.kt` /
 * `stun-keepalive.sh`），而**全部漏改都不会编译报错**，只会安静地变成"开关点了没反应"。
 * 这里把最容易漏的几条钉死：
 *
 * 1. **i18n 覆盖 6 语言** —— WebUI 的 `t()` 对缺失键**返回键名本身**，漏一个语言的表现是
 *    界面上直接冒出 `ka_fail_no_root` 这种字符串，而且**不报错**。
 * 2. **文案节点三处对齐** —— `index.html` 里的 `t-*` id ↔ `app.js` 的 `applyI18n` 绑定。
 * 3. **失败码与文案键对齐** —— 服务端只回 `ka_fail_<code>` 拼出来的键名，漏了就漏成键名。
 * 4. **开关必须走专用端点** —— 一旦被塞回 `saveAllSettings`，同步做 root 写入会拖挂整次保存，
 *    而且失败时开关没法立刻回滚。这条是刻意钉住设计决策的。
 * 5. **service.d 脚本的形态** —— 入口必须**立即 exit**（Magisk 的 service.d 是串行执行的，
 *    脚本不退出会把后面所有模块的启动脚本一起卡住）。
 */
class KeepAliveSettingsParityTest {

    private fun asset(name: String): String {
        val candidates = listOf(
            File("src/main/assets/web/$name"),
            File("src/main/assets/$name"),
            File("core/src/main/assets/web/$name"),
            File("core/src/main/assets/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $name：尝试过 ${candidates.map { it.absolutePath }}")
    }

    private val appJs get() = asset("app.js")
    private val indexHtml get() = asset("index.html")

    /** 必须 6 语言齐全的新增键。与 `applyI18n` / `renderKeepAliveStatus` 里的用法一一对应。 */
    private val keepAliveKeys = listOf(
        "settings_keepalive_title",
        "label_ka_magisk", "desc_ka_magisk",
        "label_ka_shizuku", "desc_ka_shizuku",
        "ka_enabled", "ka_disabled",
        "ka_fail_generic", "ka_fail_no_root", "ka_fail_script_write_failed",
        "ka_fail_shizuku_not_running", "ka_fail_shizuku_no_permission",
        "ka_magisk_active", "ka_magisk_off", "ka_magisk_script_lost", "ka_no_root",
        "ka_shizuku_active", "ka_shizuku_ready", "ka_shizuku_no_perm", "ka_shizuku_stopped",
    )

    /** i18n 区间（`const I18N = {` 到顶层闭合）。必须限定范围，别处的同缩进键会撞。 */
    private fun i18nRegion(js: String): String {
        val start = js.indexOf("const I18N = {")
        assertTrue("app.js 里找不到 const I18N", start >= 0)
        val end = js.indexOf("\n};", start)
        assertTrue("I18N 没有闭合", end > start)
        return js.substring(start, end)
    }

    /** 以基准键自校准语言块数，不把"6 个语言"硬编码在这里。 */
    private fun localeCount(i18n: String): Int {
        val n = Regex("""^\s{4}secret_saved_hint:\s*['"]""", RegexOption.MULTILINE).findAll(i18n).count()
        assertTrue("基准键 secret_saved_hint 只出现 $n 次，i18n 结构变了？", n >= 2)
        return n
    }

    private fun keyCount(i18n: String, key: String): Int =
        Regex("""^\s{4}${Regex.escape(key)}\s*:""", RegexOption.MULTILINE).findAll(i18n).count()

    // ── 1. i18n 覆盖 ────────────────────────────────────────────

    @Test
    fun `保活 i18n 键覆盖全部语言块`() {
        val i18n = i18nRegion(appJs)
        val locales = localeCount(i18n)
        val bad = keepAliveKeys.filter { keyCount(i18n, it) != locales }
            .map { "$it(${keyCount(i18n, it)}/$locales)" }
        assertTrue(
            "这些保活文案键没有覆盖全部 $locales 个语言块（漏语言时 `t()` 会**原样显示键名**且不报错）: $bad",
            bad.isEmpty(),
        )
    }

    @Test
    fun `失败码都有兜底文案`() {
        val js = appJs
        // 服务端只回 code，前端拼 'ka_fail_' + code。漏掉的码会显示成裸键名，
        // 所以 keepAliveFailMessage 必须有 generic 回落，且拼接逻辑必须在。
        assertTrue(
            "找不到 ka_fail_generic —— 未映射的失败码会把 `ka_fail_<code>` 裸键名显示给用户",
            keyCount(i18nRegion(js), "ka_fail_generic") == localeCount(i18nRegion(js)),
        )
        assertTrue(
            "keepAliveFailMessage 的兜底逻辑被删了：t() 对缺失键返回键名本身，必须显式回落",
            js.contains("return translated === key ? t('ka_fail_generic') : translated;"),
        )
    }

    // ── 2. 文案节点三处对齐 ───────────────────────────────────────

    @Test
    fun `保活卡片的文案节点在 index_html 与 applyI18n 中一一对应`() {
        val bound = Regex("""'(t-[a-z0-9-]+)\|(?:label|desc|settings)_[a-z0-9_]+'""")
            .findAll(appJs).map { it.groupValues[1] }.toSet()
        assertTrue("没解析出任何保活绑定（正则可能与 applyI18n 写法脱节了）", bound.isNotEmpty())

        val missingInHtml = bound.filter { id -> !indexHtml.contains("id=\"$id\"") }
        assertTrue("applyI18n 绑定的这些节点在 index.html 里不存在: $missingInHtml", missingInHtml.isEmpty())

        // 反向：HTML 里 t- 开头的保活节点（标题/标签/说明/两条状态行）都得被绑或被状态渲染接管
        val htmlKaIds = Regex("""id="(t-[a-z0-9-]*ka-[a-z0-9-]+)"""")
            .findAll(indexHtml).map { it.groupValues[1] }.toSet()
        assertTrue("index.html 里没找到保活卡片的节点", htmlKaIds.isNotEmpty())
        val stateIds = setOf("t-state-ka-magisk", "t-state-ka-shizuku")
        val unbound = htmlKaIds - bound - stateIds
        assertTrue(
            "这些保活节点既没被 applyI18n 绑定、也不是 renderKeepAliveStatus 接管的状态行: $unbound",
            unbound.isEmpty(),
        )
    }

    @Test
    fun `两个开关的 checkbox 存在且各自绑定 onchange`() {
        for (id in listOf("switch-ka-magisk", "switch-ka-shizuku")) {
            assertTrue("index.html 缺少开关 $id", indexHtml.contains("id=\"$id\""))
            val line = indexHtml.lineSequence().firstOrNull { it.contains("id=\"$id\"") } ?: ""
            assertTrue(
                "开关 $id 没有 onchange 回调 —— 这两个开关走专用端点，不经过批量保存",
                line.contains("onKeepAliveToggle("),
            )
        }
    }

    // ── 3. 开关走专用端点（设计决策）─────────────────────────────

    @Test
    fun `保活开关不得混进批量保存`() {
        val js = appJs
        val fnStart = js.indexOf("async function saveAllSettings() {")
        assertTrue("找不到 saveAllSettings", fnStart >= 0)
        // 只取 payload 对象本身：往后再切一刀会把紧跟其后的 renderKeepAliveStatus
        // （它合法地含有 magiskEnabled 等字段）也圈进来，断言就变得毫无意义。
        val payloadStart = js.indexOf("const payload = {", fnStart)
        assertTrue("saveAllSettings 里找不到 payload 对象", payloadStart >= 0)
        val payloadEnd = js.indexOf("};", payloadStart)
        assertTrue("payload 对象没有闭合", payloadEnd > payloadStart)
        val payload = js.substring(payloadStart, payloadEnd)
        for (k in listOf("ka-magisk", "ka-shizuku", "magiskEnabled", "shizukuEnabled")) {
            assertTrue(
                "saveAllSettings 的 payload 里出现了 `$k` —— 保活开关必须走 /api/keepalive/* 专用端点：" +
                    "root 写文件可能撞上 libsu 90s 超时把整次保存拖挂，且失败时无法立刻回滚开关",
                !payload.contains(k),
            )
        }
        assertTrue(
            "onKeepAliveToggle 应打专用端点 /api/keepalive/",
            js.contains("fetch('/api/keepalive/' + kind"),
        )
    }

    @Test
    fun `状态行不得在 applyI18n 里被写死`() {
        val js = appJs
        val bind = js.substringAfter("// Keep-alive Card (Magisk service.d / Shizuku)")
            .substringBefore("if (currentTab ===")
        // 判定用「这个块里根本不许出现 getElementById('t-state-ka-…')」而不是
        // 「同一行里 id 与 textContent 同时出现」：后者会被
        // `const el = getElementById(id); if (el) el.textContent = t(…)` 这种
        // 拆成两行的写法绕过（反事实实测：拆行写法曾让本护栏漏判）。
        // 两条状态行的文案只允许由 renderKeepAliveStatus 写（它要同时表达意图与现实）。
        for (id in listOf("t-state-ka-magisk", "t-state-ka-shizuku")) {
            assertTrue(
                "applyI18n 里出现了 getElementById('$id') —— 状态行要同时表达「开关意图」和" +
                    "「设备现实」（例如脚本已被刷机冲掉），只能由 renderKeepAliveStatus 重绘",
                !bind.contains("getElementById('$id')"),
            )
        }
        // 反向确认：重绘入口确实接上了，否则切语言后状态行会停留在旧语言。
        assertTrue(
            "applyI18n 末尾应调用 renderKeepAliveStatus 重绘状态行",
            bind.contains("renderKeepAliveStatus(lastKeepAliveStatus)"),
        )
    }

    // ── 4. service.d 脚本形态 ───────────────────────────────────

    @Test
    fun `service_d 脚本入口必须立即退出`() {
        val sh = asset("scripts/stun-keepalive.sh")
        for (ph in listOf("__PKG__", "__COMP__", "__INTERVAL__", "__BOOT_DELAY__")) {
            assertTrue("脚本缺少占位符 $ph（KeepAliveManager.renderScript 靠它注入）", sh.contains(ph))
        }
        assertTrue("脚本里找不到看门狗分支", sh.contains("--watchdog"))

        // 入口段（`# ── 入口` 之后）必须以 exit 0 收尾。
        // ⚠️ 不能写成"最后一个 exit 0 在 --watchdog 之后"：看门狗分支**自己**就有一个
        // exit 0，删掉入口那个它照样满足（反事实实测：这条弱断言曾漏判）。
        val entryAt = sh.indexOf("# ── 入口")
        assertTrue("脚本里找不到入口段标记", entryAt >= 0)
        val entry = sh.substring(entryAt).trimEnd()
        assertTrue(
            "service.d 入口段必须以 exit 0 收尾 —— Magisk/KernelSU/APatch 的 service.d 是" +
                "**串行执行**的，入口不退出会把后面所有模块的启动脚本一起卡住",
            entry.endsWith("exit 0"),
        )
        // 看门狗必须被丢到后台（&），否则入口等它结束就等于没退出。
        assertTrue(
            "看门狗没有用 `&` 放后台：入口会等它跑完才返回",
            Regex("""nohup\s+sh\s+"\$0"\s+--watchdog[^\n]*&""").containsMatchIn(sh),
        )
    }
}
