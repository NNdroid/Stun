package app.fjj.stun.repo

/**
 * **Profile 字段入参规则的唯一事实来源**。
 *
 * ## 为什么要有这个文件
 * 同一组Profile 字段此前有**三份平铺**，各写一遍 `取值 → trim → clamp`：
 *  1. `McpProfileArgs.fromArgs`（新建，MCP 入口）
 *  2. `McpProfileArgs.mergeInto`（增量，MCP 入口）
 *  3. `WebServer` 的 `POST /api/profiles/update`（增量，WebUI 入口）
 *
 * 加一个字段要改三处，而**漏改不报错**——本仓已因此出过两次静默丢参事故
 * （2026-09-15 漏 8 个、2026-10 漏 24 个）。三份平铺还有更隐蔽的问题：
 * 三处的 trim / clamp / 枚举校验会各自漂移，行为不一致时极难归因
 * （"WebUI 能设的字段 MCP 设不了"、"MCP 建的节点没有收藏"）。
 *
 * 现在规则只在这里写一遍，三个入口都调它。
 *
 * ## 规则的判据来自哪里
 * **不是"看起来对不对"，而是抄 WebUI 的既有实现**（`/api/profiles/update`）——
 * 它是本仓历史最久、验证最充分的实现。三条反直觉的例外：
 *
 * | 字段 | 反直觉之处 |
 * |---|---|
 * | `udpgwVersion` | **唯一不 trim** 的字符串（其余字符串都 trim） |
 * | `paddingMinBytes` | **负值是合法语义**（`negative = off`），不能 clamp |
 * | `xhttpStreamMode` | 枚举校验是**先 trim 再校验**（`" stream "` 合法） |
 *
 * ⚠️ 改这个文件前先读上面三条的注释，别顺手"统一"它们。
 *
 * ## 为什么放在 `repo` 包
 * 它同时被 MCP（`remote`）与 WebUI（`remote`）使用，且只依赖 [Profile] 与 [ProfileArgReader]，
 * 不含任何传输层类型 ⇒ 是纯领域逻辑，放在 repo 层最合适。
 * （`ui/TunnelFieldSpec.kt` 在 app 模块，core 不能反向依赖 app，故原生页不共用本表。
 *  它管的是"表单怎么渲染"，本表管的是"入参怎么取值"，职责不同。）
 */
object ProfileFields {

    /** `xhttpStreamMode` 的合法取值。非法值退回默认（"" = SDK 自适应），不原样写库。 */
    val XHTTP_STREAM_MODES: Set<String> = setOf("auto", "stream", "poll")

    /** `kcpMode` 的合法取值（WebUI 既有实现里的同一份清单）。 */
    val KCP_MODES: Set<String> = setOf("normal", "fast", "fast2", "fast3")

    /**
     * **凭据字段**：这些字段的值**不做 trim**。
     *
     * 首尾空白是密码语义的一部分，而 trim 会把"只输入空格"变成空串 ⇒ 被 `?: existing`
     * 判成"清空"，用户以为改了值、实际把凭据删了却看不出原因。清空只能靠显式提交空串。
     *
     * ⚠️ 直接引用 [ProfileSecrets.SECRET_FIELDS]，**不要**在这里重列一份 ——
     * 掩码侧（哪些字段会被 `*****` 替换）与写入侧（哪些字段不 trim）必须同步：
     * 少列一个 ⇒ 该凭据被掩码却仍被 trim，用户改密码时首尾空白被静默吃掉。
     * `ProfileFieldsCredentialParityTest` 钉住这一致性。
     */
    private val CREDENTIAL_FIELDS: Set<String> = ProfileSecrets.SECRET_FIELDS.toSet()

    /**
     * 不 trim 的**非凭据**字段：只有 `udpgwVersion`。
     *
     * 这条来自 WebUI 的既有实现（那行没有 `?.trim()`），不是本文档臆造。
     */
    private val NO_TRIM_FIELDS: Set<String> = setOf("udpgwVersion")

    /** 取 [Profile] 各字段的**声明默认值**。
     *
     *  不能复刻字面量 —— `httpPayload` 等字段的默认值是 [Profile] 里的内联字面量
     *  （不是 `const val`），抄一份就成了第二个来源，改了 [Profile] 忘了这里会带着
     *  过期默认值且**不报错**。现取一个实例是唯一能自动跟随的做法。
     */
    fun defaults(): Profile = Profile(id = "")

    // ───────────────────────────── 字符串 ─────────────────────────────

    /** 普通字符串：trim。 */
    fun str(r: ProfileArgReader, k: String): String? = r.string(k)?.trim()

    /** 同 [str]，但**不 trim** —— 用于凭据字段与 `udpgwVersion`。 */
    fun strRaw(r: ProfileArgReader, k: String): String? = r.string(k)

    /** 枚举：先 trim 再校验，非法值退回 null（调用方取 fallback）。 */
    fun enum(r: ProfileArgReader, k: String, allowed: Set<String>): String? =
        r.string(k)?.trim()?.takeIf { it in allowed }

    /**
     * 字符串字段的统一入口。
     *
     * [CREDENTIAL_FIELDS] 与 [NO_TRIM_FIELDS] 走 [strRaw]，其余 trim ——
     * 这条分界来自 WebUI 的既有实现，不是本文档臆造的。
     */
    fun text(r: ProfileArgReader, k: String): String? =
        if (k in CREDENTIAL_FIELDS || k in NO_TRIM_FIELDS) strRaw(r, k) else str(r, k)

    /** `tunnelType` 的键名（[tunnelType] 的默认值参数）。单列出来供护栏与调用点引用。 */
    const val TUNNEL_TYPE_KEY = "tunnelType"

    /** `tunnelType`：trim + 小写化（协议常量是小写）。 */
    fun tunnelType(r: ProfileArgReader, k: String = TUNNEL_TYPE_KEY): String? =
        r.string(k)?.trim()?.lowercase()

    // ───────────────────────────── 数值 ─────────────────────────────

    /** 非负整数：clamp 到 ≥0。负值会让 KCP/ICMP 底层在运行时才炸。 */
    fun intNonNegative(r: ProfileArgReader, k: String): Int? =
        r.int(k)?.coerceAtLeast(0)

    /**
     * 整数：不 clamp。
     *
     * 用于两类字段：
     *  - `paddingMinBytes`：**负值 = 关闭流量混淆填充**（schema description 明写 `negative = off`），
     *    压成 0 会把它变成"用默认填充量"，等于把功能反了。
     *  - `kcpSmuxVer` / `kcpDataShards` / `kcpParityShards`：WebUI 既有实现**不 clamp**
     *    （`Profile` 里 `10` / `3` 是默认值而非下限），由 myssh 运行时兜底。
     */
    fun intRaw(r: ProfileArgReader, k: String): Int? = r.int(k)

    // ───────────────────────────── 布尔 ─────────────────────────────

    /** 布尔读取。 */
    fun bool(r: ProfileArgReader, k: String): Boolean? = r.bool(k)

    /**
     * 公钥字段的取值：**驼峰键优先，蛇形键作为兼容别名**。
     *
     * WebUI 前端历史上发过 `udp_custom_public_key` / `dns_tunnel_public_key`
     * （`/api/profiles/update` 的既有实现就同时认两种），去掉兼容会让老页面的
     * 公钥设置**静默失效** —— 不报错，只是密钥不再更新。
     *
     * ⚠️ 别名只在这里写一遍：[applyTo] 与 [createFrom] 都调它。
     * 两处各写一次的话，新增字段时很容易只改一处（收口时 `createFrom` 就漏了）。
     */
    fun publicKey(r: ProfileArgReader, camel: String): String? =
        str(r, camel) ?: str(r, snakeCase(camel))

    /**
     * 驼峰 → 蛇形（`udpCustomPublicKey` → `udp_custom_public_key`）。
     *
     * 单独暴露是为了让「哪两个字段有别名」这件事**可被护栏验证**，而不是
     * 在测试里另写一份推导规则（那就是第三个来源）。
     */
    fun snakeCase(camel: String): String =
        camel.replace(Regex("(?<=.)([A-Z])"), "_$1").lowercase()

    // ──────────────────── 派生字段：enableCustomPath ────────────────────

    /**
     * `enableCustomPath` 只对 masque 生效，其他协议**强制 false**。
     *
     * 派生规则（与 WebUI 逐字对齐）：
     *  - 非 masque ⇒ `false`
     *  - masque 且显式传了 ⇒ 用传入值
     *  - masque 且只传了 `customPath` ⇒ `customPath` 非空即 true
     */
    fun enableCustomPath(
        r: ProfileArgReader,
        tunnelType: String,
        customPath: String?,
    ): Boolean {
        if (tunnelType != Profile.TUNNEL_TYPE_MASQUE) return false
        if (r.has("enableCustomPath")) return r.bool("enableCustomPath") ?: false
        return customPath?.isNotBlank() == true
    }

    // ─────────────────────── 增量应用（update 语义）───────────────────────

    /**
     * 把入参**增量**合并进 [existing]：只有 [r] 里出现的键才改。
     *
     * 这是三个入口里"改既有节点"的唯一实现 —— MCP 的 `update_profile`
     * 与 WebUI 的 `POST /api/profiles/update` 都调它。
     *
     * ## 掩码哨兵
     * 调用方**必须先**跑 `ProfileSecrets.dropMaskedSecrets(...)`：客户端可能把
     * `get_profile_detail` 读到的 `*****` 原样写回，不剔除就会把真实凭据整条覆盖成
     * 五个星号（节点连不上且用户看不出原因）。剔除后"键不存在"，
     * 于是自然退化成"保持原值"。
     *
     * ## 为什么不接收 `id`
     * 定位用的 `id` / `profileId` 不是 Profile 字段，由各入口自己读、自己报错。
     */
    fun applyTo(r: ProfileArgReader, existing: Profile) {
        // ── 派生先行：tunnelType / customPath 决定 enableCustomPath ──
        val newTunnelType = if (r.has("tunnelType")) {
            tunnelType(r) ?: existing.tunnelType
        } else {
            existing.tunnelType
        }
        val newCustomPath = if (r.has("customPath")) {
            str(r, "customPath") ?: existing.customPath
        } else {
            existing.customPath
        }
        if (r.has("tunnelType")) existing.tunnelType = newTunnelType
        if (r.has("customPath")) existing.customPath = newCustomPath
        // WebUI 是无条件赋值（非 masque 一律 false），保持一致
        existing.enableCustomPath = enableCustomPath(r, newTunnelType, newCustomPath)

        // ── name / sshAddr：空串不覆盖（WebUI 344-345 行用 ifBlank）──
        if (r.has("name")) existing.name = str(r, "name")?.ifBlank { existing.name } ?: existing.name
        if (r.has("sshAddr")) existing.sshAddr = str(r, "sshAddr")?.ifBlank { existing.sshAddr } ?: existing.sshAddr

        // ── 常规字段（规则与 WebUI 的 copy(...) 逐条对应）──
        if (r.has("pass")) existing.pass = text(r, "pass") ?: existing.pass   // 凭据：不 trim
        if (r.has("privateKey")) existing.privateKey = text(r, "privateKey") ?: existing.privateKey   // 凭据
        if (r.has("keyPass")) existing.keyPass = text(r, "keyPass") ?: existing.keyPass   // 凭据
        if (r.has("proxyAuthPass")) existing.proxyAuthPass = text(r, "proxyAuthPass") ?: existing.proxyAuthPass   // 凭据
        if (r.has("proxyAuthToken")) existing.proxyAuthToken = text(r, "proxyAuthToken") ?: existing.proxyAuthToken   // 凭据
        if (r.has("kcpPassword")) existing.kcpPassword = text(r, "kcpPassword") ?: existing.kcpPassword   // 凭据
        if (r.has("udpCustomPsk")) existing.udpCustomPsk = text(r, "udpCustomPsk") ?: existing.udpCustomPsk   // 凭据
        if (r.has("icmpCustomPsk")) existing.icmpCustomPsk = text(r, "icmpCustomPsk") ?: existing.icmpCustomPsk   // 凭据
        if (r.has("dnsTunnelPsk")) existing.dnsTunnelPsk = text(r, "dnsTunnelPsk") ?: existing.dnsTunnelPsk   // 凭据
        if (r.has("user")) existing.user = text(r, "user") ?: existing.user
        if (r.has("noisePublicKey")) existing.noisePublicKey = text(r, "noisePublicKey") ?: existing.noisePublicKey
        if (r.has("note")) existing.note = text(r, "note") ?: existing.note
        if (r.has("authType")) existing.authType = text(r, "authType") ?: existing.authType
        if (r.has("serverName")) existing.serverName = text(r, "serverName") ?: existing.serverName
        if (r.has("alpn")) existing.alpn = text(r, "alpn") ?: existing.alpn
        if (r.has("customHost")) existing.customHost = text(r, "customHost") ?: existing.customHost
        if (r.has("proxyAddr")) existing.proxyAddr = text(r, "proxyAddr") ?: existing.proxyAddr
        if (r.has("proxyAuthUser")) existing.proxyAuthUser = text(r, "proxyAuthUser") ?: existing.proxyAuthUser
        if (r.has("httpPayload")) existing.httpPayload = text(r, "httpPayload") ?: existing.httpPayload
        if (r.has("bindInterface")) existing.bindInterface = text(r, "bindInterface") ?: existing.bindInterface
        if (r.has("masqueAlpn")) existing.masqueAlpn = text(r, "masqueAlpn") ?: existing.masqueAlpn
        if (r.has("serverFingerprint")) existing.serverFingerprint = text(r, "serverFingerprint") ?: existing.serverFingerprint
        if (r.has("serverCertFingerprint")) existing.serverCertFingerprint = text(r, "serverCertFingerprint") ?: existing.serverCertFingerprint
        if (r.has("remoteDns")) existing.remoteDns = text(r, "remoteDns") ?: existing.remoteDns
        if (r.has("localDns")) existing.localDns = text(r, "localDns") ?: existing.localDns
        if (r.has("udpgwVersion")) existing.udpgwVersion = text(r, "udpgwVersion") ?: existing.udpgwVersion   // 唯一不 trim 的非凭据字段
        if (r.has("udpgwAddr")) existing.udpgwAddr = text(r, "udpgwAddr") ?: existing.udpgwAddr
        if (r.has("geositeDirect")) existing.geositeDirect = text(r, "geositeDirect") ?: existing.geositeDirect
        if (r.has("geoipDirect")) existing.geoipDirect = text(r, "geoipDirect") ?: existing.geoipDirect
        if (r.has("filterApps")) existing.filterApps = text(r, "filterApps") ?: existing.filterApps
        if (r.has("dnsTunnelDomain")) existing.dnsTunnelDomain = text(r, "dnsTunnelDomain") ?: existing.dnsTunnelDomain
        if (r.has("dnsTunnelServers")) existing.dnsTunnelServers = text(r, "dnsTunnelServers") ?: existing.dnsTunnelServers
        if (r.has("dnsTunnelType")) existing.dnsTunnelType = text(r, "dnsTunnelType") ?: existing.dnsTunnelType
        if (r.has("dnsTunnelMarker")) existing.dnsTunnelMarker = text(r, "dnsTunnelMarker") ?: existing.dnsTunnelMarker
        if (r.has("kcpCrypt")) existing.kcpCrypt = text(r, "kcpCrypt") ?: existing.kcpCrypt
        if (r.has("udpCustomMagic")) existing.udpCustomMagic = text(r, "udpCustomMagic") ?: existing.udpCustomMagic
        if (r.has("udpCustomMtuProbe")) existing.udpCustomMtuProbe = text(r, "udpCustomMtuProbe") ?: existing.udpCustomMtuProbe
        if (r.has("icmpCustomMtuMode")) existing.icmpCustomMtuMode = text(r, "icmpCustomMtuMode") ?: existing.icmpCustomMtuMode
        if (r.has("icmpCustomMagic")) existing.icmpCustomMagic = text(r, "icmpCustomMagic") ?: existing.icmpCustomMagic
        if (r.has("icmpCustomPublicKey")) existing.icmpCustomPublicKey = text(r, "icmpCustomPublicKey") ?: existing.icmpCustomPublicKey
        if (r.has("icmpCustomIdRange")) existing.icmpCustomIdRange = text(r, "icmpCustomIdRange") ?: existing.icmpCustomIdRange
        if (r.has("favorite")) existing.favorite = bool(r, "favorite") ?: existing.favorite
        if (r.has("proxyAuthRequired")) existing.proxyAuthRequired = bool(r, "proxyAuthRequired") ?: existing.proxyAuthRequired
        if (r.has("tunnelTlsEnabled")) existing.tunnelTlsEnabled = bool(r, "tunnelTlsEnabled") ?: existing.tunnelTlsEnabled
        if (r.has("disableStatusCheck")) existing.disableStatusCheck = bool(r, "disableStatusCheck") ?: existing.disableStatusCheck
        if (r.has("verifyFingerprint")) existing.verifyFingerprint = bool(r, "verifyFingerprint") ?: existing.verifyFingerprint
        if (r.has("verifyCertFingerprint")) existing.verifyCertFingerprint = bool(r, "verifyCertFingerprint") ?: existing.verifyCertFingerprint
        if (r.has("dnsOverride")) existing.dnsOverride = bool(r, "dnsOverride") ?: existing.dnsOverride
        if (r.has("dnsTunnelEDNS0")) existing.dnsTunnelEDNS0 = bool(r, "dnsTunnelEDNS0") ?: existing.dnsTunnelEDNS0
        if (r.has("kcpNoComp")) existing.kcpNoComp = bool(r, "kcpNoComp") ?: existing.kcpNoComp
        if (r.has("appFilterOverride")) existing.appFilterOverride = bool(r, "appFilterOverride") ?: existing.appFilterOverride
        if (r.has("heartbeatIntervalMs")) existing.heartbeatIntervalMs = intNonNegative(r, "heartbeatIntervalMs") ?: existing.heartbeatIntervalMs
        if (r.has("xhttpChunkSizeKB")) existing.xhttpChunkSizeKB = intNonNegative(r, "xhttpChunkSizeKB") ?: existing.xhttpChunkSizeKB
        if (r.has("udpCustomPaths")) existing.udpCustomPaths = intNonNegative(r, "udpCustomPaths") ?: existing.udpCustomPaths
        if (r.has("udpCustomSockets")) existing.udpCustomSockets = intNonNegative(r, "udpCustomSockets") ?: existing.udpCustomSockets
        if (r.has("udpCustomSendWindow")) existing.udpCustomSendWindow = intNonNegative(r, "udpCustomSendWindow") ?: existing.udpCustomSendWindow
        if (r.has("udpCustomMaxPkt")) existing.udpCustomMaxPkt = intNonNegative(r, "udpCustomMaxPkt") ?: existing.udpCustomMaxPkt
        if (r.has("icmpCustomMaxPayload")) existing.icmpCustomMaxPayload = intNonNegative(r, "icmpCustomMaxPayload") ?: existing.icmpCustomMaxPayload
        if (r.has("icmpCustomPaceMS")) existing.icmpCustomPaceMS = intNonNegative(r, "icmpCustomPaceMS") ?: existing.icmpCustomPaceMS
        if (r.has("kcpSndWnd")) existing.kcpSndWnd = intNonNegative(r, "kcpSndWnd") ?: existing.kcpSndWnd
        if (r.has("kcpRcvWnd")) existing.kcpRcvWnd = intNonNegative(r, "kcpRcvWnd") ?: existing.kcpRcvWnd
        if (r.has("kcpMtu")) existing.kcpMtu = intNonNegative(r, "kcpMtu") ?: existing.kcpMtu
        if (r.has("kcpKeepAlive")) existing.kcpKeepAlive = intNonNegative(r, "kcpKeepAlive") ?: existing.kcpKeepAlive
        if (r.has("kcpSmuxVer")) existing.kcpSmuxVer = r.int("kcpSmuxVer") ?: existing.kcpSmuxVer   // ⚠️ WebUI 388 行不 clamp
        if (r.has("kcpDataShards")) existing.kcpDataShards = r.int("kcpDataShards") ?: existing.kcpDataShards   // ⚠️ WebUI 390 行不 clamp
        if (r.has("kcpParityShards")) existing.kcpParityShards = r.int("kcpParityShards") ?: existing.kcpParityShards   // ⚠️ WebUI 391 行不 clamp
        if (r.has("paddingMinBytes")) existing.paddingMinBytes = r.int("paddingMinBytes") ?: existing.paddingMinBytes   // ⚠️ 负值=关闭填充
        if (r.has("filterMode")) existing.filterMode = r.int("filterMode") ?: existing.filterMode   // 0/1，WebUI 不校验

        // ── 公钥：驼峰键 + 蛇形别名，由 [publicKey] 统一处理 ──
        // ⚠️ 判据是"取值非 null"而不是 `has(驼峰) || has(蛇形)`：
        // 后者要在两处各写一遍两个键名字面量，蛇形那一半就成了只在 update 侧
        // 存在的孤例（收口时确实漏过一次）。[publicKey] 内已经读过两个键名。
        publicKey(r, "udpCustomPublicKey")?.let { existing.udpCustomPublicKey = it }
        publicKey(r, "dnsTunnelPublicKey")?.let { existing.dnsTunnelPublicKey = it }

        // ── 枚举：非法值**保持原值**（WebUI 用 `?: existing`，不是退回默认）──
        if (r.has("xhttpStreamMode")) {
            existing.xhttpStreamMode = enum(r, "xhttpStreamMode", XHTTP_STREAM_MODES) ?: existing.xhttpStreamMode
        }
        if (r.has("kcpMode")) {
            existing.kcpMode = enum(r, "kcpMode", KCP_MODES) ?: existing.kcpMode
        }
    }

    // ─────────────────────── 新建（create 语义）───────────────────────

    /**
     * 由入参构造一个新的 [Profile]。
     *
     * 与 [applyTo] 的区别：**缺失键取 [Profile] 的声明默认值**，而非"保持原值"。
     * 这样"默认值"只写在 [Profile] 一处（见 [defaults] 的说明）。
     *
     * @param id 由调用方给（生产用随机 UUID，测试可固定）。
     */
    fun createFrom(r: ProfileArgReader, id: String): Profile {
        val d = defaults()
        val p = Profile(id = id)

        p.tunnelType = tunnelType(r) ?: d.tunnelType
        p.sshAddr = str(r, "sshAddr") ?: d.sshAddr
        p.user = str(r, "user") ?: d.user

        p.pass = r.string("pass") ?: d.pass
        p.privateKey = r.string("privateKey") ?: d.privateKey
        p.keyPass = r.string("keyPass") ?: d.keyPass
        p.proxyAuthPass = r.string("proxyAuthPass") ?: d.proxyAuthPass
        p.proxyAuthToken = r.string("proxyAuthToken") ?: d.proxyAuthToken
        p.proxyAuthUser = str(r, "proxyAuthUser") ?: d.proxyAuthUser
        p.kcpPassword = r.string("kcpPassword") ?: d.kcpPassword
        p.udpCustomPsk = r.string("udpCustomPsk") ?: d.udpCustomPsk
        p.icmpCustomPsk = r.string("icmpCustomPsk") ?: d.icmpCustomPsk
        p.noisePublicKey = str(r, "noisePublicKey") ?: d.noisePublicKey

        p.name = str(r, "name") ?: d.name
        p.note = str(r, "note") ?: d.note
        p.favorite = bool(r, "favorite") ?: d.favorite
        p.serverName = str(r, "serverName") ?: d.serverName
        p.alpn = str(r, "alpn") ?: d.alpn
        p.customHost = str(r, "customHost") ?: d.customHost
        p.customPath = str(r, "customPath") ?: d.customPath
        p.enableCustomPath = enableCustomPath(r, p.tunnelType, p.customPath)
        p.authType = str(r, "authType") ?: d.authType
        p.proxyAddr = str(r, "proxyAddr") ?: d.proxyAddr

        p.tunnelTlsEnabled = bool(r, "tunnelTlsEnabled") ?: d.tunnelTlsEnabled
        p.proxyAuthRequired = bool(r, "proxyAuthRequired") ?: d.proxyAuthRequired
        p.httpPayload = str(r, "httpPayload") ?: d.httpPayload
        p.disableStatusCheck = bool(r, "disableStatusCheck") ?: d.disableStatusCheck
        p.heartbeatIntervalMs = intNonNegative(r, "heartbeatIntervalMs") ?: d.heartbeatIntervalMs
        p.xhttpChunkSizeKB = intNonNegative(r, "xhttpChunkSizeKB") ?: d.xhttpChunkSizeKB
        p.xhttpStreamMode = enum(r, "xhttpStreamMode", XHTTP_STREAM_MODES) ?: d.xhttpStreamMode
        p.bindInterface = str(r, "bindInterface") ?: d.bindInterface
        p.paddingMinBytes = intRaw(r, "paddingMinBytes") ?: d.paddingMinBytes
        p.masqueAlpn = str(r, "masqueAlpn") ?: d.masqueAlpn

        p.verifyFingerprint = bool(r, "verifyFingerprint") ?: d.verifyFingerprint
        p.serverFingerprint = str(r, "serverFingerprint") ?: d.serverFingerprint
        p.verifyCertFingerprint = bool(r, "verifyCertFingerprint") ?: d.verifyCertFingerprint
        p.serverCertFingerprint = str(r, "serverCertFingerprint") ?: d.serverCertFingerprint

        p.dnsOverride = bool(r, "dnsOverride") ?: d.dnsOverride
        p.remoteDns = str(r, "remoteDns") ?: d.remoteDns
        p.localDns = str(r, "localDns") ?: d.localDns

        p.udpgwVersion = strRaw(r, "udpgwVersion") ?: d.udpgwVersion
        p.udpgwAddr = str(r, "udpgwAddr") ?: d.udpgwAddr
        p.geositeDirect = str(r, "geositeDirect") ?: d.geositeDirect
        p.geoipDirect = str(r, "geoipDirect") ?: d.geoipDirect

        p.appFilterOverride = bool(r, "appFilterOverride") ?: d.appFilterOverride
        p.filterMode = r.int("filterMode") ?: d.filterMode
        p.filterApps = str(r, "filterApps") ?: d.filterApps

        p.dnsTunnelDomain = str(r, "dnsTunnelDomain") ?: d.dnsTunnelDomain
        p.dnsTunnelServers = str(r, "dnsTunnelServers") ?: d.dnsTunnelServers
        p.dnsTunnelType = str(r, "dnsTunnelType") ?: d.dnsTunnelType
        p.dnsTunnelPublicKey = publicKey(r, "dnsTunnelPublicKey") ?: d.dnsTunnelPublicKey
        p.dnsTunnelEDNS0 = bool(r, "dnsTunnelEDNS0") ?: d.dnsTunnelEDNS0
        p.dnsTunnelPsk = str(r, "dnsTunnelPsk") ?: d.dnsTunnelPsk
        p.dnsTunnelMarker = str(r, "dnsTunnelMarker") ?: d.dnsTunnelMarker

        p.udpCustomPublicKey = publicKey(r, "udpCustomPublicKey") ?: d.udpCustomPublicKey
        p.udpCustomMagic = str(r, "udpCustomMagic") ?: d.udpCustomMagic
        p.udpCustomPaths = intNonNegative(r, "udpCustomPaths") ?: d.udpCustomPaths
        p.udpCustomSockets = intNonNegative(r, "udpCustomSockets") ?: d.udpCustomSockets
        p.udpCustomSendWindow = intNonNegative(r, "udpCustomSendWindow") ?: d.udpCustomSendWindow
        p.udpCustomMaxPkt = intNonNegative(r, "udpCustomMaxPkt") ?: d.udpCustomMaxPkt
        p.udpCustomMtuProbe = str(r, "udpCustomMtuProbe") ?: d.udpCustomMtuProbe

        p.icmpCustomPublicKey = str(r, "icmpCustomPublicKey") ?: d.icmpCustomPublicKey
        p.icmpCustomMagic = str(r, "icmpCustomMagic") ?: d.icmpCustomMagic
        p.icmpCustomMtuMode = str(r, "icmpCustomMtuMode") ?: d.icmpCustomMtuMode
        p.icmpCustomMaxPayload = intNonNegative(r, "icmpCustomMaxPayload") ?: d.icmpCustomMaxPayload
        p.icmpCustomPaceMS = intNonNegative(r, "icmpCustomPaceMS") ?: d.icmpCustomPaceMS
        p.icmpCustomIdRange = str(r, "icmpCustomIdRange") ?: d.icmpCustomIdRange

        p.kcpCrypt = str(r, "kcpCrypt") ?: d.kcpCrypt
        p.kcpMode = str(r, "kcpMode") ?: d.kcpMode
        p.kcpSndWnd = intNonNegative(r, "kcpSndWnd") ?: d.kcpSndWnd
        p.kcpRcvWnd = intNonNegative(r, "kcpRcvWnd") ?: d.kcpRcvWnd
        p.kcpMtu = intNonNegative(r, "kcpMtu") ?: d.kcpMtu
        p.kcpNoComp = bool(r, "kcpNoComp") ?: d.kcpNoComp
        p.kcpSmuxVer = intRaw(r, "kcpSmuxVer") ?: d.kcpSmuxVer
        p.kcpKeepAlive = intNonNegative(r, "kcpKeepAlive") ?: d.kcpKeepAlive
        p.kcpDataShards = intRaw(r, "kcpDataShards") ?: d.kcpDataShards
        p.kcpParityShards = intRaw(r, "kcpParityShards") ?: d.kcpParityShards

        return p
    }
}