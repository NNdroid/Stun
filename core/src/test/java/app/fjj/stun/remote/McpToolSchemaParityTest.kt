package app.fjj.stun.remote

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **MCP tool 的 inputSchema 与实现的一致性**护栏（覆盖全部 tool，不只 create_profile）。
 *
 * ## 为什么要钉这个
 * `McpTools` 里 `inputSchema` 是**对外契约**：AI 客户端（Claude / Codex）严格照它填参。
 * 若schema 声明了参数而服务端分支从不读取，用户传了会被**静默丢弃** —— 没有报错、
 * 没有警告，只表现为"设置没生效"。
 *
 * 本仓已经因此出过一次真实事故（2026-09-15，8 个字段被 JS payload 一直发送却服务端不受理），
 * 2026-10 又发现 4 个同类（`masqueAlpn` / `paddingMinBytes` / `udpCustomMaxPkt` /
 * `udpCustomMtuProbe`）。两次都只能靠人肉比对发现 —— 所以这里改成机器钉死。
 *
 * ## 断言为什么用「源码正则」而不是「跑一遍每个 tool」
 * 实现侧分散在 `StunMcpServer.handleToolsCall`（22 个tool）和 `McpProfileArgs`
 * （create/update 两个）两处，且大多需要真实 `Context` 才能跑。正则扫源码是
 * 唯一能在纯 JVM 里覆盖全部 tool 的办法。代价是**解析必须精确**—— 见下面的注释。
 */
class McpToolSchemaParityTest {

    // ─────────────────── 提取工具清单 ───────────────────

    private fun tools(): List<JsonObject> =
        McpTools.toolsList().getAsJsonArray("tools").map { it.asJsonObject }

    private fun toolsWithParams(): List<JsonObject> =
        tools().filter { schemaProperties(it).isNotEmpty() }

    /** 取某个 tool 的 `inputSchema.properties`，缺省返回空表。 */
    private fun schemaProperties(tool: JsonObject): Map<String, JsonObject> {
        val schema = tool.getAsJsonObject("inputSchema") ?: return emptyMap()
        val props = schema.getAsJsonObject("properties") ?: return emptyMap()
        return props.keySet().associateWith { props.getAsJsonObject(it) }
    }

    // ─────────────────── 实现侧「读了哪些 key」 ───────────────────

    /**
     * 实现侧读取的 key 全集。
     *
     * ⚠️ **扫三个文件，缺一不可**（收口后实测踩过）：
     *  - [StunMcpServer]：其余 22 个 tool 的分支直接读 `args.get("x")`
     *  - [ProfileFields]：**Profile 字段规则的唯一事实来源**，`createFrom`/`applyTo`
     *    里 78 个字段全部经由它。收口前这些字段在 `McpProfileArgs`，收口后不在 ——
     *    只扫 `McpProfileArgs` 会得到"schema 声明了 60+ 参数但实现一个都没读"的假红。
     *  - [McpProfileArgs]：适配层（掩码剔除 + 载体包装），本身不读字段。
     *
     * 只认这两种形态：
     *  - `args.get("x")` / `args.has("x")` / `args["x"]`（MCP 直接读）
     *  - `r.has("x")` / `text(r, "x")` / `str(r, "x")` / `bool(r, "x")` /
     *    `intNonNegative(r, "x")`（ProfileFields 的读取器调用，键名是**第二参数**）
     *
     * ⚠️ **必须先剥注释** —— 否则 KDoc 里作为举例写的 `args.get("x")` 会被当成真字段，
     * 断言消息里就会出现莫名其妙的 `x`（实测踩过）。
     */
    private fun readKeys(): Set<String> = readKeysIn(
        listOf(
            "core/src/main/java/app/fjj/stun/remote/StunMcpServer.kt",
            "core/src/main/java/app/fjj/stun/remote/McpProfileArgs.kt",
            // 字段规则收口后的唯一事实来源
            "core/src/main/java/app/fjj/stun/repo/ProfileFields.kt",
        ).joinToString("\n") { stripComments(repoFile(it)) }
    )

    private fun readKeysIn(src: String): Set<String> =
        (ARG_KEY.findAll(src).map { it.groupValues[1] } +
                READER_KEY_METHOD.findAll(src).map { it.groupValues[1] } +
                READER_KEY_FUNC.findAll(src).map { m ->
                    // ⚠️ 判据是 `groups[2] != null`（该组**是否参与匹配**），不是内容非空：
                    // `tunnelType(r)` 命中的是**空捕获组**，参与匹配但内容为 ""。
                    // 按内容判断会走错分支，把组 1（未参与匹配 ⇒ ""）当成键名，
                    // 字段集里混进空串 —— 而 `sorted()` 把空串渲染成 `[]`，
                    // 断言消息会显示"未声明的 key: []"却判定失败，极难定位。
                    if (m.groups[2] != null) TUNNEL_TYPE_KEY else m.groupValues[1]
                }
        ).toSet()

    // ─────────────────── 断言 ───────────────────

    @Test
    fun everySchemaPropertyIsActuallyRead() {
        val reads = readKeys()
        val problems = mutableListOf<String>()
        for (tool in toolsWithParams()) {
            val name = tool.get("name").asString
            val declared = schemaProperties(tool).keys
            // profileId / id 是定位参数，读在分支外层的 `args.get("profileId")`，
            // 上面的正则已能抓到；这里只排掉确实没有对应字段的极少数特例。
            val ignored = declared - reads - KNOWN_NOT_PROFILE_FIELDS
            if (ignored.isNotEmpty()) {
                problems += "$name 声明了但实现从未读取：${ignored.sorted()}"
            }
        }
        assertTrue(
            "以下 tool 的 inputSchema 声明了参数，但服务端实现从不读取 —— " +
                "客户端（尤其 AI 客户端）严格照 schema 填参，传了会被静默丢弃：\n" +
                problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    /**
     * 反事实：确认 [readKeys] 与 [schemaProperties] 都真的抓到了东西。
     * 若某天改了代码写法导致正则失配，上面那条会永远绿 —— 必须防这一手。
     */
    @Test
    fun bothExtractorsFindRealData() {
        assertTrue("应至少读到 40 个 args key，实际 ${readKeys().size}", readKeys().size >= 40)
        val withParams = toolsWithParams()
        assertTrue("应至少 15 个 tool 带 inputSchema 参数，实际 ${withParams.size}", withParams.size >= 15)
        val totalProps = withParams.sumOf { schemaProperties(it).size }
        assertTrue("schema 属性总数应 >= 80，实际 $totalProps", totalProps >= 80)
        // create_profile 是字段最密的那个
        val create = tools().first { it.get("name").asString == "create_profile" }
        assertTrue("create_profile 应有 50+ 属性，实际 ${schemaProperties(create).size}",
            schemaProperties(create).size >= 50)
    }

    /**
     * 反向：实现读了但 schema 没声明 —— 客户端根本没法知道能传这个参数。
     * 排除所有 `_` 前缀（框架字段）与已知的定位参数。
     */
    @Test
    fun schemaDeclaresEveryKeyTheImplementationReads() {
        val reads = readKeys()
        val declared = toolsWithParams()
            .flatMap { schemaProperties(it).keys }.toSet()
        val undeclared = reads - declared - KNOWN_NOT_PROFILE_FIELDS
        assertTrue(
            "实现读取了这些 key，但没有 tool 声明它们（客户端无从得知）：" +
                undeclared.sorted(),
            undeclared.isEmpty(),
        )
    }

    /**
     * 只对**操作既有节点**的 tool 要求定位字段。
     * `create_profile` / `import_profiles` 不需要 `id` —— 它们不指向已有节点。
     */
    @Test
    fun toolsTargetingExistingProfileRequireIdentifier() {
        val needsId = setOf(
            "update_profile", "delete_profile", "select_profile",
            "get_profile_detail", "test_node_latency", "start_vpn",
        )
        for (name in needsId) {
            val tool = tools().firstOrNull { it.get("name").asString == name }
                ?: throw AssertionError("工具清单里没有 $name（工具被改名/删除？）")
            val props = schemaProperties(tool).keys
            assertTrue("$name 应声明 profileId", "profileId" in props)
        }
        // 反事实：确认上面的集合真的命中了（而不是 names 拼错导致全体跳过）
        assertTrue("needsId 至少要命中一个存在的工具", needsId.size >= 5)
    }

    // ─────────────────── 精度自检（防正则失配） ───────────────────

    /**
     * 精确性检查：正则不得把 **enum 的取值**当成属性名。
     * `add("direction", JsonObject().apply { add("enum", JsonArray().apply { add("upload") ... }) })`
     * 里的 `upload/download/both` 是取值，不是参数名。
     */
    @Test
    fun schemaPropertyExtractorIgnoresEnumValues() {
        for (tool in toolsWithParams()) {
            val props = schemaProperties(tool).keys
            for (bogus in listOf("upload", "download", "both", "enum")) {
                assertTrue(
                    "把 enum 取值 \"$bogus\" 当成了 ${tool.get("name").asString} 的属性名",
                    bogus !in props,
                )
            }
        }
    }

    /** 每个属性的 schema 至少要有 type，否则客户端无法渲染表单。 */
    @Test
    fun everyPropertyDeclaresAType() {
        for (tool in toolsWithParams()) {
            val name = tool.get("name").asString
            for ((prop, def) in schemaProperties(tool)) {
                assertTrue("$name.$prop 缺 type 声明", def.has("type"))
            }
        }
    }

    private fun assertEquals(expected: Int, actual: Int, msg: String) =
        org.junit.Assert.assertEquals(msg, expected, actual)

    // ─────────────────── repo 定位 ───────────────────

    private val repoRoot: java.io.File by lazy {
        generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .firstOrNull {
                java.io.File(it, "settings.gradle").isFile ||
                    java.io.File(it, "settings.gradle.kts").isFile
            }
            ?: throw AssertionError("从 ${java.io.File(".").absolutePath} 往上找不到 settings.gradle")
    }

    private fun repoFile(rel: String): String =
        java.io.File(repoRoot, rel).readText()

    /** 去掉 `//` 行注释与 `/** */` 块注释；不处理字符串内的 `//`（本仓这几个文件里不存在这种写法）。 */
    private fun stripComments(src: String): String {
        var out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> { while (i < src.length && src[i] != '\n') i++ }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i = (i + 2).coerceAtMost(src.length)
                    out.append(' ')
                }
                src[i] == '"' -> {
                    // 字符串字面量整体跳过 —— 里面的 { } " 都不能当结构
                    val start = i; i++
                    while (i < src.length) {
                        if (src[i] == '\\') i++ else if (src[i] == '"') break
                        i++
                    }
                    i++
                    out.append(src, start, minOf(i, src.length))
                }
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private companion object {
        /** 匹配 `args.get("x")` / `args.has("x")` / `args["x"]` / `args.getAsJsonArray("x")`。 */
        val ARG_KEY = Regex("""args(?:\.get|\.has|\.getAsJsonArray)?\s*[\[(]\s*"(\w+)"""")

        /** 匹配 `r.has("x")` / `r.string("x")` / `r.int("x")` / `r.bool("x")`。 */
        val READER_KEY_METHOD = Regex("""\br\s*\.\s*(?:has|string|int|long|bool)\s*\(\s*"(\w+)"""")

        /**
         * 两个分支，**键名分别在捕获组 1 与组 2**（调用处用 `ifEmpty` 归一）：
         *  - `text(r, "x")` / `str(r, "x")` / `intNonNegative(r, "x")` / `publicKey(r, "x")`
         *  - `tunnelType(r)` —— 无参形态，键名由 [ProfileFields.TUNNEL_TYPE_KEY] 给定
         *
         * ⚠️ 两个分支的捕获组必须都存在，哪怕第二个不捕获任何字符 ——
         * 否则 `groupValues[2]` 抛 `IndexOutOfBoundsException`（实测踩过）。
         *
         * `r` 前置是**必须的**：否则 `CREDENTIAL_FIELDS` / `XHTTP_STREAM_MODES`
         * 这类"枚举取值集合"里的字面量会被当成被读取的字段（它们全是真实字段名，
         * 一命中就造成"读过但 schema 没声明"的假红）。
         */
        val READER_KEY_FUNC = Regex(
            """\b(?:text|str|strRaw|enum|bool|intNonNegative|intRaw|publicKey)\s*\(\s*r\s*,\s*"(\w+)""" +
                """|\btunnelType\s*\(\s*r\s*\)()"""
        )

        /** 与 [ProfileFields.TUNNEL_TYPE_KEY] 同源，避免这里硬编码一份。 */
        val TUNNEL_TYPE_KEY = app.fjj.stun.repo.ProfileFields.TUNNEL_TYPE_KEY

        /**
         * 不是 Profile 字段、但确实会被读取的 key（协议层/传输层参数）。
         * 列在这里是为了让两条断言的差异集只包含**真正的漏项**。
         *
         * ⚠️ 别把「实现读了但 schema 没声明」的 key塞进来 —— 那等于把缺陷藏起来。
         *   本文件曾在 `keyword`（`get_logs` 的日志过滤词）上这么干过一轮，
         *   正确做法是去 schema 里补声明（2026-10 已补）。
         */
        val KNOWN_NOT_PROFILE_FIELDS = setOf(
            "action",           // start_vpn / stop_vpn 的动作名
            "target",           // diagnostics 的探测目标
            "serverName",       // 与 Profile.serverName 同名但用于诊断
            "profileId",        // 定位参数
            "id",               // 定位参数（WebUI 风格）
            // ⚠️ 这两个是**有意的兼容别名**（[app.fjj.stun.repo.ProfileFields.applyTo] 显式
            // 同时接受驼峰与蛇形），不是漏声明。WebUI 前端历史上发过蛇形，
            // 去掉会让老页面的公钥设置静默失效 —— 兼容入口不能删，也就不能进 schema。
            "dns_tunnel_public_key",
            "udp_custom_public_key",
        )
    }
}