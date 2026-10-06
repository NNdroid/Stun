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
     * @param sshServerEntry 隧道服务端地址，格式 `host:port`（TCP+UDP）或裸 `host`（ICMP）。
     *   由调用方从选中 profile 解析。**自动注入 BYPASS_DST 链首位** —— 这是「mark 死亡时
     *   运行时回落避免回环」的 PRIMARY 路径：隧道 socket 命中目的地址直接放行，不再依赖
     *   SO_MARK / pidfd_getfd（Linux 5.6+）。空串 = 不注入（mark / uid 兜底）。
     * @param bypassDstList 用户自定义的目的地址绕过三元组（空格分隔），可选。三种格式：
     *   `ip`（ICMP）/ `ip:port`（TCP+UDP）/ `ip:lo-hi`（TCP+UDP 端口区间）。
     */
    fun buildShellRules(
        selfPackage: String,
        tproxyPort: Int,
        dnsPort: Int,
        appFilter: AppFilter,
        socketMark: Int = TProxyPorts.SOCKET_MARK,
        sshServerEntry: String = "",
        bypassDstList: String = "",
    ): String {
        // 与 `tproxy.sh` 的 `setup_app_chain` 两条分支对齐（照行为，不照字面）：
        //  - bypass 名单里的 uid → `-j ACCEPT`（终止遍历 = 直连），排在 proxy 名单**之前**；
        //  - proxy 名单里的 uid → `-j RETURN`（继续遍历 → 被代理）；
        //  - 链尾 `-j RETURN`（blacklist）或 `-j ACCEPT`（whitelist）。
        // 两种模式现在都读 BYPASS_APPS_LIST，且 bypass 优先 —— 所以本 App 无论在哪种
        // 模式下走 uid 放行都能生效，不必把 whitelist 降级成 blacklist。
        //
        // 绝不能把 selfPackage 写进 PROXY_APPS_LIST 作为「让 App 进代理」的手段：
        // whitelist 分支对它加的是 `-j RETURN`（继续往下走），最终落到 `PROXY_OUTPUT`
        // 链尾的 REDIRECT ⇒ 隧道 socket 被抓回本地 socks5 ⇒ **死循环**。
        // 用户手填的过滤列表里可能出现自己的三种形式，都得剔掉：裸包名 / `0:` 前缀 /
        // `user:` 前缀（`parsePackageList` 对后两种原样透传）。`user:` 漏掉的话，
        // blacklist + mark 模式下它会以 self 的 uid 命中 bypass 的 `-j ACCEPT` ——
        // 整个 App 直连，mark 精确放行整个失效。
        val selected = appFilter.packages.filterNot {
            it == selfPackage || it == "0:$selfPackage" || it == "user:$selfPackage"
        }
        val isAllowList = appFilter.isAllowList
        val markAvailable = socketMark != 0

        // 自己该放进哪个列表：
        //  - **whitelist**：自己**必须**在 `PROXY_APPS_LIST`。链尾 `-j ACCEPT` 作用于 mangle
        //    表 = 终止整条 OUTPUT 链 ⇒ 不在 proxy 名单里就等于直连。与 mark 无关：mark 放行
        //    规则加在 APP_CHAIN 之前（929 行早于 961 行的 `_add_chain_jumps`），
        //    隧道 socket 走不到 APP_CHAIN，因此把自己放进 proxy 名单不会造成回灌。
        //  - **blacklist**：链尾 `-j RETURN`（继续往下走 → 被 REDIRECT 代理），所以
        //    mark 可用时自己**不在任何列表**，其流量照常走隧道。
        //  - 两种模式在 **mark 不可用** 时都把自己放进 `BYPASS_APPS_LIST`：此时只剩 uid
        //    放行，同一 uid 下无法区分 App 的其他 socket ⇒ 整个 App 必须直连。
        //    bypass 是两种模式**唯一**的载体 —— `setup_app_chain` 的 whitelist 分支已改为
        //    先读 bypass 再读 proxy（bypass 的 ACCEPT 排在 proxy 的 RETURN 之前），
        //    所以不必把 whitelist 降级成 blacklist（那会误伤用户白名单里的应用）。
        //
        // ⚠️ mark 不可用时绝不能只把自己写进 `PROXY_APPS_LIST`：whitelist 分支对它加的是
        // `-j RETURN`（继续往下走），最终落到 PROXY_OUTPUT 链尾的 REDIRECT
        // ⇒ 隧道 socket 被抓回本地 socks5 ⇒ **死循环**。比显示真实 IP 严重得多。
        //
        // ⚠️ whitelist 模式下 bypass 列表**只能装自己**：bypass 的 ACCEPT 终止遍历 = 直连，
        // 把用户白名单里的应用也写进去，等于把它们静默改成直连 —— 这正是「误伤用户白名单」，
        // 与降级成 blacklist 是同一种事故。用户的名单只留在 PROXY_APPS_LIST 里。
        val withSelfInList: List<String> =
            if (isAllowList || !markAvailable) listOf(selfPackage) + selected else selected
        // `find_packages_uid` 认两种条目：裸包名（按 user 0 解析）与 `uid:包名`（`0:com.foo`
        // / `user:com.foo` / `10:com.foo`，包名本身不含冒号）。`parsePackageList` 对带前缀
        // 形式原样透传，所以只给裸包名补 `0:`；已带前缀的绝不能再拼一层 —— `0:user:com.foo`
        // 会被解析成「user 0 里一个叫 user 的包」，规则永远配不上，条目静默失效。
        val uidEntry: (String) -> String = { if (it.contains(':')) it else "0:$it" }
        val bypassApps = when {
            isAllowList -> if (markAvailable) emptyList() else listOf(selfPackage)
            else -> withSelfInList
        }.joinToString(" ", transform = uidEntry)
        val proxyApps = if (isAllowList) withSelfInList.joinToString(" ", transform = uidEntry) else ""
        val forceMarkBypass = if (socketMark != 0) 1 else 0
        val routingMark = if (socketMark != 0) "0x%x".format(socketMark) else ""

        return """
            # Auto-generated by TransparentProxyConfigBuilder — 请勿手改，重新连接时会覆盖
            # App 自身流量${if (markAvailable) "走隧道（隧道 socket 由 SO_MARK 精确放行）" else "整体直连（SO_MARK 不可用，只能按 uid 放行，粒度连带整个 App）"}
            PROXY_TCP_PORT=$tproxyPort
            PROXY_UDP_PORT=$tproxyPort
            PROXY_MODE=1
            DNS_HIJACK_ENABLE=1
            DNS_PORT=$dnsPort
            BLOCK_QUIC=0
            BYPASS_CN_IP=0
            CN_IP_URL=https://push.4544.de/https://raw.githubusercontent.com/Hackl0us/GeoIP2-CN/release/CN-ip-cidr.txt
            CN_IPV6_URL=https://push.4544.de/https://ispip.clang.cn/all_cn_ipv6.txt
            # 不覆盖 BYPASS_IPv4_LIST / BYPASS_IPv6_LIST —— 交回 tproxy.sh 的 DEFAULT_*。
            # 这里曾显式写成只含回环的 "127.0.0.0/8" / "::1/128"，把脚本默认值里的
            # 224.0.0.0/4 240.0.0.0/4（多播）、10/8 172.16/12 192.168/16 169.254/16（内网与链路本地）
            # 全部挤掉：多播地址落入 TPROXY 之后由 socks5 去直连，而 UDP 多播地址不能
            # connect()，于是每几秒刷一条 `[ROUTER-Direct] ❌ Failed to establish direct UDP:
            # dial udp [ff02::fb]:5353: connect: invalid argument`。
            # 注意 tproxy.sh 对每个键都用 bash 的 VAR:-DEFAULT 回退 —— **空串也会取默认值**：
            # 写成空串 BYPASS_IPv4_LIST="" 取到的是默认全表而不是空表，本生成器对 IP 旁路表
            # 的"留空"方式是**整键不写**。APPS_LIST 的空串同样会落回默认（DEFAULT_BYPASS 里
            # 是 App 自己 = uid 粒度防回环的最后防线）—— 这是**刻意**的：SO_MARK 会死、
            # BYPASS_DST 可能对域名型服务端失配，只有 uid 旁路从不失效。2026-10 曾试图让
            # 空串保持为空（App 自身流量进隧道），在 mark 已死的设备上直接回环、服务端连不上，
            # 已回滚。App 自身流量的代理通道是显式走本地 SOCKS5（ExitIpProbe.contextAwareFetch）。
            PROXY_IPV6=1
            APP_PROXY_ENABLE=1
            APP_PROXY_MODE=${if (isAllowList) "whitelist" else "blacklist"}
            BYPASS_APPS_LIST="$bypassApps"
            PROXY_APPS_LIST="$proxyApps"
            BYPASS_DST_LIST="$bypassDstList"
            SSH_SERVER_ENTRY="$sshServerEntry"
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
