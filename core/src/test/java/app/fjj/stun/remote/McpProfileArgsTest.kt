package app.fjj.stun.remote

import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.ProfileFields
import com.google.gson.JsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [McpProfileArgs] 的行为锁定。
 *
 * 为什么要专门测：这两个函数是 60 余个 `args.get(x)?.asString ?: 默认值` 的平铺，
 * 而 `create`（具名构造，有默认值）与 `update`（`args.has` 增量赋值，**无默认值**）
 * 是两份必须逐字段对齐的表。历史上漏对齐的表现是**静默的**：
 * "建得出来但改不了"，或"改得了但新建时丢默认端口"。
 *
 * 断言必须带反事实（见 [updateAndCreateCoverTheSameFieldSet]），否则无法区分
 * "两表一致" 与 "两表都漏了同一个字段"。
 */
class McpProfileArgsTest {

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject =
        JsonObject().apply {
            for ((k, v) in pairs) {
                when (v) {
                    null -> {}                                  // 显式表示"没这个键"
                    is String -> addProperty(k, v)
                    is Boolean -> addProperty(k, v)
                    is Int -> addProperty(k, v)
                    else -> error("测试未覆盖的类型：${v::class}")
                }
            }
        }

    // ───────────────────────── create ─────────────────────────

    @Test
    fun fromArgsAppliesDefaultsForAbsentKeys() {
        val p = McpProfileArgs.fromArgs(JsonObject(), id = "fixed-id")
        // 期望值取自 [Profile] 的**声明默认值**（不是本测试杜撰的字面量）——
        // 收口前这里写的是 `ProfileFields.defaults()` 即 `Profile(id = "")`，
        // 与原生新建路径（`ProfileEditViewModel` 的 `Profile().apply {}`）同源。
        val d = ProfileFields.defaults()
        assertEquals("fixed-id", p.id)
        assertEquals(d.name, p.name)
        assertEquals(d.sshAddr, p.sshAddr)
        assertEquals(Profile.TUNNEL_TYPE_RAW, p.tunnelType)
        assertEquals(d.user, p.user)
        assertEquals(d.pass, p.pass)
        assertEquals(d.alpn, p.alpn)
        assertEquals("UDPC", p.udpCustomMagic)
        assertEquals("txt", p.dnsTunnelType)
        assertEquals(d.kcpCrypt, p.kcpCrypt)
        assertEquals(d.kcpMode, p.kcpMode)
        assertEquals(Profile.AUTH_TYPE_PASSWORD, p.authType)
    }

    @Test
    fun fromArgsReadsEveryProvidedKey() {
        val p = McpProfileArgs.fromArgs(args(
            "name" to "东京 01",
            "sshAddr" to "203.0.113.9:2222",
            "tunnelType" to "XHTTP",
            "user" to "ubuntu",
            "pass" to "s3cret",
            "alpn" to "h2",
            "serverName" to "cdn.example.com",
            "customHost" to "h.example.com",
            "customPath" to "/t",
            "heartbeatIntervalMs" to 15000,
            "xhttpChunkSizeKB" to 512,
            "icmpCustomPaceMS" to 30,
            "kcpCrypt" to "aes-256",
            "kcpMode" to "normal",
        ), id = "i")
        assertEquals("东京 01", p.name)
        assertEquals("203.0.113.9:2222", p.sshAddr)
        // tunnelType 必须小写化（协议常量是小写）
        assertEquals("xhttp", p.tunnelType)
        assertEquals("ubuntu", p.user)
        assertEquals("s3cret", p.pass)
        assertEquals("h2", p.alpn)
        assertEquals("cdn.example.com", p.serverName)
        assertEquals("h.example.com", p.customHost)
        assertEquals("/t", p.customPath)
        assertEquals(15000, p.heartbeatIntervalMs)
        assertEquals(512, p.xhttpChunkSizeKB)
        assertEquals(30, p.icmpCustomPaceMS)
        assertEquals("aes-256", p.kcpCrypt)
        assertEquals("normal", p.kcpMode)
    }

    /** `enableCustomPath` 只对 masque 生效，其他协议**必须**强制 false。 */
    @Test
    fun enableCustomPathIsMasqueOnly() {
        val masque = McpProfileArgs.fromArgs(args(
            "tunnelType" to "masque", "customPath" to "/p", "enableCustomPath" to true))
        assertTrue(masque.enableCustomPath)

        // 未显式给 enableCustomPath，但 customPath 非空 ⇒ masque 下应自动为 true
        val masqueAuto = McpProfileArgs.fromArgs(args(
            "tunnelType" to "masque", "customPath" to "/p"))
        assertTrue(masqueAuto.enableCustomPath)

        // 非 masque 协议即使显式传 true 也要被压成 false
        val raw = McpProfileArgs.fromArgs(args(
            "tunnelType" to "raw", "customPath" to "/p", "enableCustomPath" to true))
        assertEquals(false, raw.enableCustomPath)
    }

    /**
     * 两类整数字段的 clamp 规则**刻意不同**（判据来自 WebUI `/api/profiles/update` 的
     * 既有实现，不是"统一一下更整齐"）。
     *
     * - 会 clamp 到 0：窗口 / MTU / 超时这类参数，负值会让底层在运行时才炸。
     * - **不 clamp**：`kcpSmuxVer` / `kcpDataShards` / `kcpParityShards`
     *   （WebUI 388-391 行没有 coerce，`Profile` 里的 `10`/`3` 是默认值而非下限，
     *   由 myssh 运行时兜底）、`paddingMinBytes`（负值 = 关闭填充，schema 写明 `negative = off`）。
     *
     * ⚠️ 收口时 `createFrom` 曾对这三个 KCP 字段用 `intNonNegative`（clamp）而
     * `applyTo` 用 `r.int`（不 clamp）—— 同一字段两条路径行为不一致，
     * 正是本文件 `createAndUpdateCoverTheSameFieldSet` 想防的那类漂移。
     */
    @Test
    fun onlyWindowAndTimeoutFieldsAreClampedToZero() {
        val p = McpProfileArgs.fromArgs(args(
            "heartbeatIntervalMs" to -1,
            "xhttpChunkSizeKB" to -5,
            "udpCustomPaths" to -3,
            "icmpCustomMaxPayload" to -7,
            "kcpSndWnd" to -2,
            "kcpRcvWnd" to -2,
            "kcpMtu" to -2,
            "kcpKeepAlive" to -2,
            "kcpSmuxVer" to -2,
            "kcpDataShards" to -2,
            "kcpParityShards" to -2,
            "paddingMinBytes" to -1,
        ))
        for ((name, v) in listOf(
            "heartbeat" to p.heartbeatIntervalMs, "xhttpChunk" to p.xhttpChunkSizeKB,
            "udpPaths" to p.udpCustomPaths, "icmpPayload" to p.icmpCustomMaxPayload,
            "kcpSndWnd" to p.kcpSndWnd, "kcpRcvWnd" to p.kcpRcvWnd, "kcpMtu" to p.kcpMtu,
            "kcpKeepAlive" to p.kcpKeepAlive,
        )) {
            assertEquals("$name 应被压成 0", 0, v)
        }
        // 反向：不 clamp 的字段必须**原样保留负值**（压成 0 会把"关闭"变成"默认值"）
        for ((name, v) in listOf(
            "kcpSmuxVer" to p.kcpSmuxVer,
            "kcpDataShards" to p.kcpDataShards,
            "kcpParityShards" to p.kcpParityShards,
        )) {
            assertEquals("$name 不该被 clamp（WebUI 语义）", -2, v)
        }
        // paddingMinBytes 传的是 -1（不是 -2）：它的语义就是"关闭填充"，必须原样保留
        assertEquals("paddingMinBytes 不该被 clamp", -1, p.paddingMinBytes)
    }

    // ───────────────────────── update ─────────────────────────

    @Test
    fun mergeIntoOnlyTouchesPresentKeys() {
        val p = McpProfileArgs.fromArgs(JsonObject(), id = "i").apply { name = "旧名" }
        McpProfileArgs.mergeInto(args("user" to "root2"), p)
        assertEquals("root2", p.user)
        assertEquals("旧名", p.name)                          // 未出现的键不得被默认值覆盖
        assertEquals(ProfileFields.defaults().sshAddr, p.sshAddr) // 同上
    }

    @Test
    fun mergeIntoClampsNegativesToo() {
        val p = McpProfileArgs.fromArgs(JsonObject(), id = "i")
        McpProfileArgs.mergeInto(
            args("kcpMtu" to -3, "icmpCustomPaceMS" to -4, "kcpDataShards" to -5),
            p,
        )
        assertEquals(0, p.kcpMtu)
        assertEquals(0, p.icmpCustomPaceMS)
        // 不 clamp 的分片数：create 与 update 必须**同规则**（收口时这里曾不一致）
        assertEquals(-5, p.kcpDataShards)
    }

    @Test
    fun mergeIntoLowercasesTunnelType() {
        val p = McpProfileArgs.fromArgs(JsonObject(), id = "i")
        McpProfileArgs.mergeInto(args("tunnelType" to "MASQUE"), p)
        assertEquals("masque", p.tunnelType)
    }

    // ───────────────── 掩码凭据：静默损坏的头号来源 ─────────────────

    /**
     * 反事实核心用例。`get_profile_detail` 返回的是 `*****`，客户端很可能原样回传。
     * 若掩码键没被剔除，真实密码会被**整条覆盖成五个星号**——界面正常、节点连不上、
     * 用户完全看不出原因。
     */
    @Test
    fun maskedSecretsAreDroppedNotPersisted() {
        val created = McpProfileArgs.fromArgs(args("name" to "n"), id = "i")
        created.pass = "REAL_PASSWORD"
        created.privateKey = "REAL_KEY"
        created.keyPass = "REAL_KEYPASS"
        created.proxyAuthToken = "REAL_APITOKEN"

        // 客户端把读到的掩码原样写回
        McpProfileArgs.mergeInto(args(
            "name" to "改过名了",
            "pass" to "*****",
            "privateKey" to "*****",
            "keyPass" to "*****",
            "proxyAuthToken" to "*****",
        ), created)

        assertEquals("名称应正常更新", "改过名了", created.name)
        assertEquals("密码不能被覆盖成星号", "REAL_PASSWORD", created.pass)
        assertEquals("私钥不能被覆盖成星号", "REAL_KEY", created.privateKey)
        assertEquals("私钥口令不能被覆盖成星号", "REAL_KEYPASS", created.keyPass)
        assertEquals("代理令牌不能被覆盖成星号", "REAL_APITOKEN", created.proxyAuthToken)
    }

    @Test
    fun fromArgsAlsoDropsMaskedSecrets() {
        val p = McpProfileArgs.fromArgs(args("pass" to "*****", "user" to "u"), id = "i")
        // 掩码被剔除 ⇒ 该键不存在 ⇒ 取 [Profile] 的声明默认值（不是空串）。
        // 这正是收口后的语义：默认值只有一个来源（`Profile`），
        // 而不是一个 MCP 私有的字面量表。
        assertEquals("掩码不应落库", ProfileFields.defaults().pass, p.pass)
        assertNotEquals("掩码绝不能落库", "*****", p.pass)
        assertEquals("u", p.user)
    }

    /** 反事实：真值必须真的写进去，否则上面的"没被覆盖"是假绿。 */
    @Test
    fun nonMaskedValueStillWins() {
        val p = McpProfileArgs.fromArgs(args("pass" to "p0"), id = "i")
        McpProfileArgs.mergeInto(args("pass" to "newpass"), p)
        assertEquals("newpass", p.pass)
        assertNotEquals("*****", p.pass)
    }

    // ──────── schema 声明了但实现漏读的字段（2026-10 修复的真实缺陷）────────
    //
    // ⚠️ 本类**不再**自己钉 schema↔实现的对应关系：那条断言已移交给
    // `McpToolSchemaParityTest`（覆盖全部 15 个带参 tool，且字段实现已从
    // `McpProfileArgs` 搬到共享的 `ProfileFields`）。此处保留一份会造成
    // "两处正则、两套 allowlist"，任一处漏改就给出误导性的结论。
    /** 反事实：schema 提取器本身要能抓到东西，否则上面那条恒绿。 */
    @Test
    fun schemaExtractorFindsRealKeys() {
        val keys = createProfileSchemaKeys()
        assertTrue("应从 schema 抽出 40+ 个属性，实际 ${keys.size}", keys.size >= 40)
        assertTrue("schema 应含 name", "name" in keys)
    }

    /** 四个字段的取值规则，必须与 WebUI（`WebServer` 的 `/api/profiles/update`）一致。 */
    @Test
    fun lateAddedFieldsFollowWebUiRules() {
        val p = McpProfileArgs.fromArgs(args(
            "udpCustomMaxPkt" to 1450,
            "udpCustomMtuProbe" to "  on  ",
            "paddingMinBytes" to 512,
            "masqueAlpn" to "  h3  ",
        ), id = "i")
        assertEquals(1450, p.udpCustomMaxPkt)
        assertEquals("字符串要 trim", "on", p.udpCustomMtuProbe)
        assertEquals(512, p.paddingMinBytes)
        assertEquals("h3", p.masqueAlpn)

        // udpCustomMaxPkt 有 minimum:0 ⇒ 负值压成 0
        assertEquals(0, McpProfileArgs.fromArgs(args("udpCustomMaxPkt" to -1), id = "i").udpCustomMaxPkt)

        // ⚠️ paddingMinBytes 的 schema 写明 "negative = off" —— 负值是合法语义，
        // 这里若也压成 0，"关闭填充"会变成"用默认 1420"，等于把功能反了。
        assertEquals(
            "paddingMinBytes 必须保留负值（negative = off）",
            -1, McpProfileArgs.fromArgs(args("paddingMinBytes" to -1), id = "i").paddingMinBytes,
        )
    }

    @Test
    fun mergeIntoHandlesLateAddedFields() {
        val p = McpProfileArgs.fromArgs(JsonObject(), id = "i")
        McpProfileArgs.mergeInto(args(
            "udpCustomMaxPkt" to 1400,
            "udpCustomMtuProbe" to "off",
            "paddingMinBytes" to -1,
            "masqueAlpn" to "h2",
        ), p)
        assertEquals(1400, p.udpCustomMaxPkt)
        assertEquals("off", p.udpCustomMtuProbe)
        assertEquals(-1, p.paddingMinBytes)
        assertEquals("h2", p.masqueAlpn)
    }

    /** 从 `McpTools.toolsList()` 里取 create_profile 的 inputSchema 属性名。 */
    private fun createProfileSchemaKeys(): Set<String> {
        val tools = McpTools.toolsList().getAsJsonArray("tools")
        for (el in tools) {
            val o = el.asJsonObject
            if (o.get("name").asString == "create_profile") {
                return o.getAsJsonObject("inputSchema")
                    .getAsJsonObject("properties")
                    .keySet()
            }
        }
        throw AssertionError("工具清单里没有 create_profile")
    }

    /**
     * 2026-10 补齐的 20 个字段（WebUI / 原生页能设、MCP 此前一律丢弃）。
     *
     * 症状回顾：通过 MCP 建的节点"没有收藏、没有备注、geosite 直连规则为空"，
     * 而用户从 App 里看一切正常 ⇒ 极难归因。
     */
    @Test
    fun previouslyDroppedFieldsAreNowPersisted() {
        val p = McpProfileArgs.fromArgs(args(
            "favorite" to true,
            "note" to "  东京备用  ",
            "geositeDirect" to "  cn,netflix  ",
            "geoipDirect" to "  cn,private  ",
            "bindInterface" to "  wlan0  ",
            "xhttpStreamMode" to "  stream  ",
            "disableStatusCheck" to true,
            "verifyFingerprint" to true,
            "serverFingerprint" to "  SHA256:abc  ",
            "verifyCertFingerprint" to true,
            "serverCertFingerprint" to "  SHA256:def  ",
            "dnsOverride" to true,
            "remoteDns" to "  https://dns.example/dns-query  ",
            "localDns" to "  https://local.example/dns-query  ",
            "udpgwVersion" to "libc",
            "udpgwAddr" to "  10.0.0.1:7300  ",
            "appFilterOverride" to true,
            "filterMode" to 1,
            "filterApps" to "  com.tencent.mm,com.example.app  ",
        ), id = "i")

        assertTrue("favorite 必须落库", p.favorite)
        assertEquals("note 要 trim", "东京备用", p.note)
        assertEquals("cn,netflix", p.geositeDirect)
        assertEquals("cn,private", p.geoipDirect)
        assertEquals("wlan0", p.bindInterface)
        assertEquals("stream", p.xhttpStreamMode)
        assertTrue(p.disableStatusCheck)
        assertTrue(p.verifyFingerprint)
        assertEquals("SHA256:abc", p.serverFingerprint)
        assertTrue(p.verifyCertFingerprint)
        assertEquals("SHA256:def", p.serverCertFingerprint)
        assertTrue(p.dnsOverride)
        assertEquals("https://dns.example/dns-query", p.remoteDns)
        assertEquals("https://local.example/dns-query", p.localDns)
        assertEquals("libc", p.udpgwVersion)
        assertEquals("10.0.0.1:7300", p.udpgwAddr)
        assertTrue(p.appFilterOverride)
        assertEquals(1, p.filterMode)
        assertEquals("com.tencent.mm,com.example.app", p.filterApps)
    }

    /**
     * `udpgwVersion` 是**唯一不 trim** 的字符串字段 ——
     * 对齐 WebUI `/api/profiles/update` 的 `(body["udpgwVersion"] as? String) ?: ...`。
     * 其余字符串字段（`udpgwAddr` / `geositeDirect` / `note` …）都带 `?.trim()`。
     */
    @Test
    fun udpgwVersionIsTheOnlyUntrimmedString() {
        val p = McpProfileArgs.fromArgs(args(
            "udpgwVersion" to "  libc  ",
            "udpgwAddr" to "  10.0.0.1:7300  ",
            "note" to "  x  ",
            "bindInterface" to "  eth0  ",
        ), id = "i")
        assertEquals("刻意与 WebUI 一致：保留空白", "  libc  ", p.udpgwVersion)
        assertEquals("其余字符串字段都 trim", "10.0.0.1:7300", p.udpgwAddr)
        assertEquals("x", p.note)
        assertEquals("eth0", p.bindInterface)
    }

    /**
     * `xhttpStreamMode` 是枚举，非法值必须退回默认而不是原样写库
     * （与 WebUI 的 `takeIf { it in listOf("auto","stream","poll") }` 同规则）。
     *
     * 注意顺序：**先 trim 再校验**（与 WebUI 一致），所以 `" stream "` 是合法的。
     */
    @Test
    fun xhttpStreamModeRejectsIllegalValues() {
        for (ok in listOf("auto", "stream", "poll")) {
            assertEquals(
                "合法值 $ok 应被接受",
                ok, McpProfileArgs.fromArgs(args("xhttpStreamMode" to ok), id = "i").xhttpStreamMode,
            )
            assertEquals(
                "带空白的合法值应先 trim 再校验",
                ok, McpProfileArgs.fromArgs(args("xhttpStreamMode" to "  $ok  "), id = "i").xhttpStreamMode,
            )
        }
        for (bad in listOf("STREAM", "sse", "", "stream poll")) {
            assertEquals(
                "非法值 \"$bad\" 应退回默认",
                "", McpProfileArgs.fromArgs(args("xhttpStreamMode" to bad), id = "i").xhttpStreamMode,
            )
        }
    }

    /** update 侧：非法枚举值**不能**把已有的合法值冲掉（WebUI 用 `?: existing`）。 */
    @Test
    fun mergeIntoKeepsValidStreamModeWhenGivenIllegalOne() {
        val p = McpProfileArgs.fromArgs(args("xhttpStreamMode" to "stream"), id = "i")
        McpProfileArgs.mergeInto(args("xhttpStreamMode" to "sse"), p)
        assertEquals("stream", p.xhttpStreamMode)
    }

    /** 未传这些字段时，必须取 [Profile] 自己的声明默认值（而不是空串/false）。 */
    @Test
    fun omittedFieldsFallBackToProfileDefaults() {
        val p = McpProfileArgs.fromArgs(JsonObject(), id = "i")
        val d = Profile(id = "")
        assertEquals("httpPayload 应取 Profile 的默认值", d.httpPayload, p.httpPayload)
        assertEquals("remoteDns 应取 SettingsManager 的默认值", d.remoteDns, p.remoteDns)
        assertEquals("localDns", d.localDns, p.localDns)
        assertEquals("udpgwVersion", d.udpgwVersion, p.udpgwVersion)
        assertEquals("udpgwAddr", d.udpgwAddr, p.udpgwAddr)
        assertEquals("geositeDirect", d.geositeDirect, p.geositeDirect)
        assertEquals("geoipDirect", d.geoipDirect, p.geoipDirect)
        assertFalse("favorite 默认应为 false", p.favorite)
        assertEquals(0, p.filterMode)
    }

    @Test
    fun mergeIntoHandlesTheTwentyNewFields() {
        val p = McpProfileArgs.fromArgs(JsonObject(), id = "i")
        McpProfileArgs.mergeInto(args(
            "favorite" to true,
            "note" to " 备注 ",
            "geositeDirect" to " cn ",
            "filterMode" to 1,
            "filterApps" to " com.a ",
            "bindInterface" to " eth0 ",
            "xhttpStreamMode" to "poll",
            "dnsOverride" to true,
            "remoteDns" to " https://d ",
        ), p)
        assertTrue(p.favorite)
        assertEquals("备注", p.note)
        assertEquals("cn", p.geositeDirect)
        assertEquals(1, p.filterMode)
        assertEquals("com.a", p.filterApps)
        assertEquals("eth0", p.bindInterface)
        assertEquals("poll", p.xhttpStreamMode)
        assertTrue(p.dnsOverride)
        assertEquals("https://d", p.remoteDns)
    }

    // ─────────── create / update 字段集一致性（含反事实） ───────────

    /**
     * 钉住 `createFrom`（新建）与 `applyTo`（增量）覆盖**同一批** Profile 字段。
     *
     * ## 为什么这条断言在收口后依然必要
     * 收口前这里是**两份平铺**（`fromArgs` 用 `args.get` 取默认值、`mergeInto` 用
     * `args.has` 判存在），必须比对。收口后两者委托同一个 [app.fjj.stun.repo.ProfileFields]，
     * 但**两份平铺表仍然在 `ProfileFields.applyTo` / `createFrom` 里** ——
     * 收口只把"同一个文件的两个函数"从"三个文件的三份"换了个位置，
     * "建得出来却改不了"这个静默故障**一个都没消除**。
     *
     * 漏对齐的表现仍是**静默**的："建得出来但改不了" / "改得了但新建时丢默认值"。
     *
     * 反事实：若只断言"二者一致"，两表**同时**漏掉新加字段时测试仍绿。
     * 所以额外断言字段数下限 —— 加了字段却不更新两份表时这里会红。
     */
    @Test
    fun createAndUpdateCoverTheSameFieldSet() {
        val fields = repoPath("core/src/main/java/app/fjj/stun/repo/ProfileFields.kt")
        assertSameFieldSet(
            createSrc = readFunctionSource(fields, "fun createFrom("),
            updateSrc = readFunctionSource(fields, "fun applyTo("),
        )
    }

    /**
     * 抽一个函数里被读取的键名。
     *
     * 认三种形态：[ProfileFields] 的 `r.has("x")` / `text(r, "x")` / `r.string("x")` 等，
     * 以及收口前残留的 `args.get("x")`。
     */
    private fun fieldsIn(src: String): Set<String> =
        (KEY_METHOD.findAll(src).map { it.groupValues[1] } +
                KEY_FUNC.findAll(src).map { m ->
                    // ⚠️ 判据是 `groups[2] != null`（该组**是否参与匹配**），**不是**
                    // `groupValues[2].isNotEmpty()` —— `tunnelType(r)` 命中的是空捕获组，
                    // 参与匹配但内容为 ""，按内容判断会走错分支、把组 1（未参与 ⇒ ""）
                    // 当成键名，于是字段集里混进空串。
                    // 空串比想象中难查：`sorted()` 把它渲染成 `[]`，
                    // 断言消息会显示"未声明的 key: []"却判定失败。
                    if (m.groups[2] != null) ProfileFields.TUNNEL_TYPE_KEY else m.groupValues[1]
                }).toSet()

    /**
     * 每个键的**全部可接受拼写**（当前只有公钥有蛇形别名）。
     *
     * 别名由 [ProfileFields.snakeCase] 推导而不是在测试里另写一份 —— 那是第三个来源，
     * 而"别名规则"本身正是最容易悄悄漂移的东西（它决定老 WebUI 页面还能不能用）。
     */
    private fun acceptedKeys(key: String): Set<String> =
        if (key in PUBLIC_KEY_FIELDS) setOf(key, ProfileFields.snakeCase(key)) else setOf(key)

    /**
     * 断言 [createFrom] 与 [applyTo] 覆盖同一批键（按可接受拼写归一后比较）。
     */
    private fun assertSameFieldSet(createSrc: String, updateSrc: String) {
        val create = fieldsIn(createSrc).flatMap { acceptedKeys(it) }.toSet()
        val update = fieldsIn(updateSrc).flatMap { acceptedKeys(it) }.toSet()
        assertEquals(
            "两份字段表已漂移：update 独有 ${update - create}，create 独有 ${create - update}",
            create, update,
        )
        assertTrue(
            "字段数只有 ${create.size}，两份表可能同时漏了新字段（反事实失效）",
            create.size >= 50,
        )
    }

    /**
     * 读出某个成员函数的源码片段（不含注释 —— 否则本文件的 KDoc 里出现的
     * `args.get("x")` 字面量会被下面的正则当成真字段，测试静默失真）。
     *
     * ⚠️ 缩进要从**签名所在行的行首**算，不能用 `src.substring(0, start).takeLastWhile { ' ' }`
     * 往前数 —— [stripComments] 会把 KDoc 整段替换成**一个空格**，于是
     * `… 此说明 fun createFrom(` 里 `f` 之前的空白已不是真缩进（实测会算成 0），
     * 收尾查找 `\n}` 于是匹配到文件末尾，等于"函数体"取成了整个文件后半段，
     * 差异集被无关内容污染。
     */
    private fun readFunctionSource(file: String, signature: String): String {
        val src = stripComments(File(file).readText())
        val start = src.indexOf(signature)
        require(start >= 0) { "$file 里找不到 $signature" }
        val lineStart = src.lastIndexOf('\n', start).let { if (it < 0) 0 else it + 1 }
        val indent = src.substring(lineStart, start)
        require(indent.isBlank()) { "$signature 不在行首（实测 $file: ${indent.length} 列）" }
        // 收尾：与签名行**同列**的 `}`（缩进 4sp，对应 object 的成员函数）
        val end = src.indexOf("\n    }", start)
        require(end > start) { "$signature 未正常收尾（找 \\n    } 失败）" }
        return src.substring(start, end)
    }

    private val repoRoot: File by lazy {
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle").isFile || File(it, "settings.gradle.kts").isFile }
            ?: throw AssertionError("从 ${File(".").absolutePath} 往上找不到 settings.gradle")
    }

    /** 仓库相对路径 → 绝对路径串（供 [readFunctionSource] 用）。 */
    private fun repoPath(rel: String): String = File(repoRoot, rel).path

    /**
     * 去掉 `//` 行注释与 `/* */` 块注释。
     *
     * ⚠️ **必须同时跳过字符串字面量**（含转义 `\"`）。
     * 本文件的 KDoc 与 `Regex("…")` 里都写着 `args.get("x")`、`"tunnelType(r)"` 之类的
     * 举例；不跳字符串时它们会被当成真代码保留下来，提取出的"字段集"混进虚构字段，
     * 差异集里冒出 `""`（空串）这种一看就不该存在的成员。
     * （实测踩过：断言消息显示 `[]` 却判定非空 —— 那个成员就是空串，
     *  `sorted()` 渲染成 `[]`，比直接打印集合还难查。）
     */
    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            when {
                src.startsWith("//", i) -> {
                    while (i < src.length && src[i] != '\n') i++
                }
                src.startsWith("/*", i) -> {
                    i += 2
                    while (i < src.length && !src.startsWith("*/", i)) i++
                    i = (i + 2).coerceAtMost(src.length)
                    out.append(' ')
                }
                src[i] == '"' -> {
                    val start = i
                    i++
                    while (i < src.length) {
                        if (src[i] == '\\') i++ else if (src[i] == '"') break
                        i++
                    }
                    i++
                    out.append(src, start, minOf(i, src.length))
                }
                else -> {
                    out.append(src[i]); i++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        /** `r.has("x")` / `r.string("x")` / `r.int("x")` / `r.bool("x")` 形态。 */
        val KEY_METHOD = Regex("""\br\s*\.\s*(?:has|string|int|long|bool)\s*\(\s*"(\w+)"""")

        /**
         * 两个分支，键名分别在捕获组 1 与组 2（调用处用 `ifEmpty` 归一）：
         *  - `text(r, "x")` / `str(r, "x")` / `intNonNegative(r, "x")` / `publicKey(r, "x")`
         *  - `tunnelType(r)` —— 无参形态，键名由 [ProfileFields.TUNNEL_TYPE_KEY] 给定
         *
         * ⚠️ 两个分支的捕获组必须都存在（第二个用空捕获组 `()` 占位），
         * 否则 `groupValues[2]` 抛 `IndexOutOfBoundsException`。
         *
         * `r` 前置是必须的 —— 否则 `CREDENTIAL_FIELDS` 这类枚举集合里的
         * 字面量会被当成被读取的字段。
         */
        val KEY_FUNC = Regex(
            """\b(?:text|str|strRaw|enum|bool|intNonNegative|intRaw|publicKey)\s*\(\s*r\s*,\s*"(\w+)""" +
                """|\btunnelType\s*\(\s*r\s*\)()"""
        )

        /**
         * 有蛇形兼容别名的字段（[ProfileFields.publicKey] 处理）。
         * 别名**不是新字段**，只是同义拼写，所以比较时归一到驼峰。
         */
        val PUBLIC_KEY_FIELDS = setOf("udpCustomPublicKey", "dnsTunnelPublicKey")
    }
}