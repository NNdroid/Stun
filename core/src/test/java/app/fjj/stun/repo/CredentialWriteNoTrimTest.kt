package app.fjj.stun.repo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「凭据在写入路径上**一律不得 trim**」的机械护栏（[ProfileSecrets] 掩码契约的写入侧对偶）。
 *
 * ## 为什么
 * 凭据（[ProfileSecrets.SECRET_FIELDS]）的首尾空白是**语义的一部分**。写入路径上一个多余的
 * `.trim()` 会造成两种静默故障：
 * 1. **真实凭据被悄悄改写** —— `" p@ss "` 落库成 `"p@ss"`，节点从此连不上，用户看不出原因；
 * 2. **「只输入空格」被 trim 成空串** ⇒ 在 WebUI 后端 `?: existing` / 原生 `.ifEmpty {}` 眼里
 *    就等于**显式清空** —— 用户以为改了值，实际把凭据删了，且没有任何提示。
 *
 * ## 与校验侧的分工（刻意不同，别"顺手统一"掉）
 * 校验侧（原生 `buildInputs()`、前端 `lvVal()`）**照常 trim**：那是刻意的"空白=未填"守卫 ——
 * `FieldRules` 对 `AUTH_PASS` / `AUTH_TOKEN` / `KCP_PASSWORD` 用的是 `isNotEmpty()`，靠这层
 * trim 才能把"只输入空格"判成未填并报「必填」。所以本护栏只盯**写入路径**（真正落库/落 payload
 * 的那一次读取），不动校验快照。
 *
 * 被钉住的四条写入路径：
 * 1. WebUI 前端提交 payload（`app.js` 的 `submitEditProfileImpl`）
 * 2. WebUI 保存接口（`WebServer.kt` 的 `POST /api/profiles/update`）
 * 3. 原生编辑页保存（`ProfileEditActivity.kt` 的 `currentProfile.copy(...)`）
 * 4. MCP `create_profile` / `update_profile`（`StunMcpServer.kt`）
 */
class CredentialWriteNoTrimTest {

    /** 落库 / 落 payload 用的凭据字段名（= payload 键 = 库列名）。直接取单一事实来源。 */
    private val fields = ProfileSecrets.SECRET_FIELDS

    /** 从 `core`（或仓库根）两种工作目录都能定位到源码。 */
    private fun repoFile(path: String): String {
        val candidates = listOf(File(path), File("../$path"))
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("找不到 $path：尝试过 ${candidates.map { it.absolutePath }}")
    }

    /** 抽 `const SECRET_INPUT_IDS = [ ... ];` 里的 DOM id 字面量。 */
    private fun secretInputIds(js: String): List<String> {
        val start = js.indexOf("const SECRET_INPUT_IDS = [")
        assertTrue("app.js 里找不到 SECRET_INPUT_IDS（被改名或删除了？本护栏已失效）", start >= 0)
        val end = js.indexOf("];", start)
        assertTrue("SECRET_INPUT_IDS 数组没有闭合", end > start)
        return Regex("'([^']+)'").findAll(js.substring(start, end))
            .map { it.groupValues[1] }
            .toList()
    }

    @Test
    fun `WebUI 提交 payload 的凭据不 trim`() {
        val js = repoFile("core/src/main/assets/web/app.js")
        val ids = secretInputIds(js)
        assertTrue("SECRET_INPUT_IDS 是空的，护栏没意义", ids.isNotEmpty())
        val offenders = ids.filter { js.contains("getElementById('$it').value.trim()") }
        assertTrue(
            "这些凭据框在提交 payload 时做了 .trim() —— 首尾空白会被抹掉，" +
                "且「只输入空格」会退化成空串（后端 `?: existing` 当成清空）: $offenders",
            offenders.isEmpty(),
        )
    }

    /**
     * WebUI 与 MCP 共用的写入路径（`ProfileFields.applyTo`）上，凭据一律不 trim。
     *
     * ## 为什么改成「跑真实行为」而不是扫源码
     * 收口前这条护栏扫 `WebServer.kt` 里的 `body["pass"].trim()`。规则搬到
     * [ProfileFields] 之后，同一个 trim 决策被浓缩成一行 `if (k in CREDENTIAL_FIELDS)`，
     * 正则再扫源码就只能匹配到那**一行**，而不是九个字段各自的表现 ——
     * 少一个字段进 `CREDENTIAL_FIELDS` 照样绿。
     *
     * 直接构造载荷跑一遍 [ProfileFields.applyTo]，断言首尾空白**原样落地**，
     * 才是"这九个字段真的不 trim"的可验证断言。
     */
    @Test
    fun `共用的写入路径对凭据不 trim`() {
        val trimmed = mutableListOf<String>()
        for (f in fields) {
            val p = Profile(id = "x").apply { pass = "keep"; privateKey = "keep"; keyPass = "keep" }
            val body = mutableMapOf<String, Any?>(
                "name" to "n",
                f to "  $f value  ",
            )
            ProfileFields.applyTo(ProfileArgReader.of(body), p)
            val written = when (f) {
                "pass" -> p.pass
                "privateKey" -> p.privateKey
                "keyPass" -> p.keyPass
                "proxyAuthToken" -> p.proxyAuthToken
                "proxyAuthPass" -> p.proxyAuthPass
                "icmpCustomPsk" -> p.icmpCustomPsk
                "udpCustomPsk" -> p.udpCustomPsk
                "dnsTunnelPsk" -> p.dnsTunnelPsk
                "kcpPassword" -> p.kcpPassword
                else -> error("SECRET_FIELDS 新增了 $f，本测试的 when 未覆盖（护栏失效）")
            }
            if (written != "  $f value  ") trimmed += "$f → '$written'"
        }
        assertTrue(
            "这些凭据在写入时被 trim 了 —— 首尾空白是密码语义的一部分，" +
                "且「只输入空格」会退化成空串被 `?: existing` 当成清空: $trimmed",
            trimmed.isEmpty(),
        )
        // 反事实：非凭据字段**确实**被 trim —— 否则上面那条可能是"根本没写进去"而误绿。
        val p = Profile(id = "x")
        ProfileFields.applyTo(ProfileArgReader.of(mapOf("note" to "  hi  ")), p)
        assertEquals("非凭据字段应当被 trim（证明上一条不是因为压根没赋值）", "hi", p.note)
    }

    @Test
    fun `原生编辑页保存路径的凭据不 trim`() {
        val kt = repoFile("app/src/main/java/app/fjj/stun/ui/ProfileEditActivity.kt")
        val start = kt.indexOf("currentProfile.copy(")
        assertTrue("找不到原生保存路径 currentProfile.copy(（被重构了？本护栏已失效）", start >= 0)
        // 保存块以 `isSaveInProgress = true` 收尾；只在这个区间内检查，避免误伤上面
        // buildInputs() 校验快照里**刻意保留**的 trim。
        val end = kt.indexOf("isSaveInProgress = true", start)
        assertTrue("找不到保存路径结尾 isSaveInProgress = true", end > start)
        val savePath = kt.substring(start, end)
        val offenders = fields.filter { f ->
            Regex("""\b${Regex.escape(f)} = [^\n]*\.trim\(\)""").containsMatchIn(savePath)
        }
        assertTrue("原生编辑页保存路径对凭据做了 .trim()（校验快照的 trim 不在此区间）: $offenders", offenders.isEmpty())
    }

    /**
     * MCP 写入路径的凭据不 trim。
     *
     * MCP 侧现在只是 [ProfileFields] 的一层适配（`McpProfileArgs`），
     * 真正的 trim 决策在共用表里，已由上面的用例覆盖；这里钉的是
     * **适配层没有偷偷加 trim**（它曾经是一个 235 行的平铺实现，正是漂移的源头）。
     */
    @Test
    fun `MCP 写入路径的凭据不 trim`() {
        val kt = repoFile("core/src/main/java/app/fjj/stun/remote/McpProfileArgs.kt")
        val offenders = fields.filter { f ->
            Regex("""args\.get\("${Regex.escape(f)}"\)[^\n]*\.trim\(\)""").containsMatchIn(kt)
        }
        assertTrue("MCP create/update 对凭据做了 .trim(): $offenders", offenders.isEmpty())
    }
}
