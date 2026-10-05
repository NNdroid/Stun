package app.fjj.stun.service

import app.fjj.stun.core.BuildConfig

/**
 * tproxy 模式的两份配置文件生成器。
 *
 * 这两份配置**格式完全不同、消费方也不同**，原先却是两个同名重载
 * `buildHevSocks5TProxyConfig(...)`，只靠参数类型区分 —— 读代码时看到
 * `buildHevSocks5TProxyConfig(context, TPROXY_PORT, TPROXY_PORT, DNS_HIJACK_PORT)` 根本分不清
 * 自己拿到的是 shell 变量还是 YAML。现在按"谁消费它"命名：
 *
 * | 函数 | 写到哪 | 谁消费 | 格式 |
 * |---|---|---|---|
 * | [buildShellRules] | `cacheDir/tproxy_rules.conf` | `tproxy.sh` 第 172 行 `. `（source） | shell 变量赋值 |
 * | [buildCoreYaml] | `cacheDir/hev-socks5-tproxy.conf` | `hev-socks5-tproxy` 命令行 | YAML |
 *
 * 两个函数都不再接收 `Context` / `Profile`：它们一个都没用到（DNS 段在 YAML 里是注释状态，
 * profile 级调参目前不存在），去掉之后整个类变成纯函数，能被纯 JVM 单测直接断言 ——
 * 这在 tproxy 这条链上是第一次有测试覆盖。
 *
 * 可见性是 `internal`：全仓库只有 `MyTransparentProxyService` 用它（已跨模块确认），
 * 而它的入参 `AppFilter` 是 internal，收窄后不必把整个模型公开出去。
 */
internal object TransparentProxyConfigBuilder {

    /**
     * 生成 `tproxy.sh` 的 shell 变量配置。
     *
     * @param selfPackage 本 App 包名。用于把「用户手填的过滤列表」里的自己剔掉 ——
     *   真正防止自己流量回灌的是 [SOCKET_MARK] 那条 mark 规则，不是这个 uid。
     * @param tproxyPort tproxy 监听端口，tcp / udp 共用（`hev-socks5-tproxy` 的 yaml 也是同一端口）
     * @param dnsPort DNS 劫持重定向端口
     * @param appFilter 分应用代理配置，[AppFilter.EMPTY] 表示不过滤
     * @param socketMark 隧道 socket 的 SO_MARK 值（`TProxyPorts.SocketMark.MARK`）。
     *   非 0 时启用 mark 放行 —— 只让 myssh 的 SSH/隧道 socket 直连，App 内**其它**流量
     *   （WebUI / MCP / 出口 IP 探测）照常走隧道。
     */
    fun buildShellRules(
        selfPackage: String,
        tproxyPort: Int,
        dnsPort: Int,
        appFilter: AppFilter,
        socketMark: Int = TProxyPorts.SOCKET_MARK,
    ): String {
        // 与 tproxy.sh `setup_proxy_chain` 的两条链语义对齐（不是照字面翻译，是照行为）：
        //  - blacklist：列进 BYPASS_APPS_LIST 的 uid → `-j ACCEPT`（终止遍历 = 直连）；
        //    链尾 `-j RETURN`，其余包继续往下走 → 被 TPROXY 代理。
        //  - whitelist：列进 PROXY_APPS_LIST 的 uid → `-j RETURN`（继续遍历 → 被代理）；
        //    链尾 `-j ACCEPT`，**其余全部直连**。所以 whitelist 的 PROXY_APPS_LIST == Stun 的
        //    「只有这些应用走代理」。注意这条分支**完全不读** BYPASS_APPS_LIST。
        //
        // ⚠️ **自己不再进 bypass 列表**（旧版是 `listOf(selfPackage) + selected`）。
        // 旧做法按 uid 放行整个 App —— 代价是 App 内所有流量都直连，出口 IP 永远显示本机地址，
        // WebUI 之类的请求也出不去。现在改由 [socketMark] 精确放行隧道 socket：
        //  - socketMark != 0：myssh 的隧道 socket 由 `sockmark`(root) 打上 mark，tproxy 按 mark 放行；
        //    App 自身 uid 不在 bypass 里，其流量走隧道。
        //  - socketMark == 0：mark 通路不可用，回落到旧的 uid 放行 —— **死循环比显示本地 IP 严重**，
        //    所以这个降级必须存在，且必须明显大于「出口地址不准」的代价。
        //
        // ⚠️ whitelist 分支**不读** BYPASS_APPS_LIST（见 `tproxy.sh` 的 `setup_app_chain`：
        // 只有 blacklist 分支去读它加 `-j ACCEPT`，whitelist 分支只看 PROXY_APPS_LIST 加
        // `-j RETURN`，其余走链尾 `-j ACCEPT`）。所以降级不能靠 bypass 兜底。
        //   - socketMark != 0：mark 通路可用，自己不在任何列表 ⇒ 其流量走隧道。
        //   - socketMark == 0：mark 不可用，**必须把自己塞进 PROXY_APPS_LIST**，否则自己
        //     既不在 proxy 列表也无 bypass 兜底 ⇒ 链尾 `-j ACCEPT` ⇒ 整个 App 全直连
        //     （出口 IP 显示本机、WebUI/MCP 出不去），且没有任何告警。
        //     这一步让 whitelist 的降级语义与 blacklist 一致：自己被代理，其余按配置放行。
        // ── 自己（App 自身）在四种组合下该放进哪个列表 ─────────────────────────
        //
        // 设计原则（用户明确要求）：**只有底层连接能被精确 bypass 时，App 自身才进代理；
        // 否则整个 App bypass。** 隧道 socket 的放行粒度决定了 App 自身的命运：
        //  - mark 可用：tproxy 在 `PROXY_OUTPUT` 链**进入 APP_CHAIN 之前**就按 mark ACCEPT
        //    （tproxy.sh `setup_proxy_chain` 里 mark 规则加在 `_add_chain_jumps` 之前），
        //    所以隧道 socket 根本不参与 uid 匹配 ⇒ 放行是精确的 ⇒ App 自身可以走隧道。
        //  - mark 不可用：唯一可用的放行手段是 uid，而 App 内所有 socket 同属一个 uid
        //    ⇒ 放行必然连带整个 App ⇒ App 自身只能整个 bypass。
        //
        // whitelist 分支的坑：`-j RETURN` 语义是「继续往下走」，最终落到 PROXY_OUTPUT 链尾
        // 的 REDIRECT ⇒ **隧道 socket 会被抓回本地 socks5，死循环**。所以 mark 不可用时
        // 绝不能把 selfPackage 塞进 PROXY_APPS_LIST（那正是本文件上一版的错误改法）。
        //
        // ⚠️ whitelist 还有一个更难缠的点：`setup_app_chain` 的 whitelist 分支
        // **完全不读** BYPASS_APPS_LIST，所以「mark 不可用 + whitelist」这一格里，
        // shell 侧没有任何列表能承载 uid 放行。此时降级为 blacklist 模式
        // （BYPASS_APPS_LIST 才会被读），自己被放进 BYPASS 列表整体直连，代价是用户配的
        // 白名单语义暂时失效。但那属于配置偏好；App 自身直连属于功能性损坏，
        // 二者不可同日而语。
        //
        // 另注：mark **可用**时的 whitelist 不需要降级 —— 隧道 socket 在进 APP_CHAIN 前
        // 就被 mark ACCEPT 放行，自己留在 PROXY_APPS_LIST 里被正常代理，不会死循环。
        val selected = appFilter.packages.filterNot { it == selfPackage || it == "0:$selfPackage" }
        val requestedAllowList = appFilter.isAllowList
        val markAvailable = socketMark != 0

        // mark 不可用 + whitelist ⇒ 强制转 blacklist，让 BYPASS_APPS_LIST 真正生效。
        val effectiveAllowList = requestedAllowList && markAvailable

        // 自己该不该进列表，与「进哪个列表」是两个独立决策，别绑在一起：
        //  - whitelist：自己**必须**在 PROXY_APPS_LIST（链尾 `-j ACCEPT` 会让它直连），
        //    无论 mark 是否可用 —— mark 只决定隧道 socket 怎么被放行，不决定自己是否被代理。
        //  - blacklist：mark 可用时自己**不在** BYPASS（走隧道）；mark 不可用时**必须**在
        //    BYPASS（uid 放行粒度所限，只能连带整个 App）。
        val withSelfInList: List<String> = if (requestedAllowList || !markAvailable) {
            listOf(selfPackage) + selected
        } else {
            selected
        }
        val proxyApps = if (effectiveAllowList) withSelfInList.joinToString(" ") { "0:$it" } else ""
        val bypassApps = if (effectiveAllowList) "" else withSelfInList.joinToString(" ") { "0:$it" }
        // 供调用方判断「whitelist 被降级成 blacklist」并打日志。
        val allowListDowngraded = requestedAllowList && !effectiveAllowList
        val forceMarkBypass = if (socketMark != 0) 1 else 0
        val routingMark = if (socketMark != 0) "0x%x".format(socketMark) else ""

        return """
            # Auto-generated by TransparentProxyConfigBuilder — 请勿手改，重新连接时会覆盖
            # ${if (allowListDowngraded) "注意：SO_MARK 不可用，whitelist 已降级为 blacklist（否则 uid 放行无处生效，隧道会死循环）" else "App 自身流量${if (markAvailable) "走隧道" else "整个 App 直连（SO_MARK 不可用，uid 放行粒度所限）"}"}
            PROXY_TCP_PORT=$tproxyPort
            PROXY_UDP_PORT=$tproxyPort
            PROXY_MODE=1
            DNS_HIJACK_ENABLE=1
            DNS_PORT=$dnsPort
            BLOCK_QUIC=0
            BYPASS_CN_IP=0
            CN_IP_URL=https://push.4544.de/https://raw.githubusercontent.com/Hackl0us/GeoIP2-CN/release/CN-ip-cidr.txt
            CN_IPV6_URL=https://push.4544.de/https://ispip.clang.cn/all_cn_ipv6.txt
            BYPASS_IPv4_LIST="127.0.0.0/8"
            BYPASS_IPv6_LIST="::1/128"
            PROXY_IPV6=1
            APP_PROXY_ENABLE=1
            APP_PROXY_MODE=${if (effectiveAllowList) "whitelist" else "blacklist"}
            BYPASS_APPS_LIST="$bypassApps"
            PROXY_APPS_LIST="$proxyApps"
            FORCE_MARK_BYPASS=$forceMarkBypass
            ROUTING_MARK=$routingMark
            DRY_RUN=0
        """.trimIndent()
    }

    /**
     * 生成 `hev-socks5-tproxy` 的 YAML 配置（root 模式下由命令行直接读文件）。
     *
     * 目前只有日志级别随构建类型变；tproxy 侧没有 per-profile 的 core 调参，所以不收 `Profile`。
     * DNS 段保持注释：tproxy.sh 已经在 mangle 表里做了 DNS 劫持，core 再开一个 dns 段会与之冲突。
     */
    fun buildCoreYaml(socksPort: Int, tproxyPort: Int): String {
        return """
            main:
              workers: 1
            misc:
              log-level: ${if (BuildConfig.DEBUG) "debug" else "warn"}
            tcp:
              port: $tproxyPort
              address: '::' # Listen on all interfaces (IPv4/IPv6)
            udp:
              port: $tproxyPort
              address: '::'
            socks5:
              port: $socksPort
              address: '127.0.0.1'
              udp: udp
        """.trimIndent()
    }
}
