package app.fjj.stun.service

/**
 * 本地代理端口的**单一事实来源**。
 *
 * 此前这些字面量散落在三处（`MyVpnService` / `MyTransparentProxyService` 的
 * companion，以及 `LatencyProber` / `SpeedTestManager` 各自私有的一份副本），
 * 于是 `LatencyProber` 与 `SpeedTestManager` 一直写的是 `1080` / `53`，
 * 而服务真正监听的是 `10808` / `10553`。
 *
 * 那两个错值目前**没有造成线上故障** —— 已核实 Go 侧 `DialNode`（延迟测试与
 * 测速走的路径）只读 `sshAddr` / `proxyAddr`，**不读** `local_addr`；后者只在
 * `engine.go` 起长期 SOCKS5 服务时才被消费。所以它是一枚定时炸弹：一旦
 * `DialNode` 改为尊重 `local_addr` 以复用连接，延迟测试会立刻因连不上
 * `127.0.0.1:1080` 而全量失败，且报错指向"网络不通"而非"端口写错"。
 *
 * 与 `tproxy.sh` 的 `DEFAULT_PROXY_TCP_PORT` / `DEFAULT_DNS_PORT` 是**成对**的：
 * 那边改了这边必须同步，`TProxyPortsContractTest` 会钉住这一致性。
 *
 * 可见性是 `internal`：端口不是对外 API，且 `[TProxyPorts]` 提供了两种模式的
 * 具名访问（VPN 只有 SOCKS/DNS，tproxy 另有 TPROXY 端口）。
 */
internal object TProxyPorts {

    /** 本地 SOCKS5 监听端口。两种模式相同，myssh 的 `local_addr` 与 tproxy 的 `socks5.port` 都用它。 */
    const val SOCKS: Int = 10808

    /** DNS 劫持端口。两种模式相同。 */
    const val DNS: Int = 10553

    /**
     * 透明代理的 TPROXY 监听端口（tcp / udp 共用），与 SOCKS 分离。
     *
     * 只有 tproxy 模式用到；VPN 模式不监听这个端口，所以 [VPN] 里没有它。
     */
    const val TPROXY: Int = 10812

    /**
     * VPN 模式（`MyVpnService`）用到的端口。
     *
     * 刻意重复字面量而不写 `= TProxyPorts.SOCKS`：嵌套 object 里引用外层 `const val`
     * 在 `internal` 可见性下会触发 "Unresolved reference"。真正的单一来源是**本文件**，
     * `TProxyPortsContractTest.vpnAndTproxyAgreeOnSocksAndDnsPorts` 钉住两处相等。
     */
    object VPN {
        const val SOCKS: Int = 10808
        const val DNS: Int = 10553
    }

    /** 透明代理模式（`MyTransparentProxyService`）用到的端口。同样理由不引用外层。 */
    object TProxy {
        const val SOCKS: Int = 10808
        const val DNS: Int = 10553
        const val TPROXY: Int = 10812
    }

    /**
     * 隧道 socket 的 SO_MARK 值（`FORCE_MARK_BYPASS` + `ROUTING_MARK`）。
     *
     * 存在的理由：tproxy 模式把出站流量 TPROXY 回本地 socks5，而那个 socks5 的上游正是
     * myssh 自己监听的 SOCKS5 服务 —— 隧道 socket 若也被抓走，流量就回到自己，死循环。
     * 旧解法是把整个 App 加进 `BYPASS_APPS_LIST`（按 uid 放行），代价是 App 内**所有**流量
     * 都直连（出口地址永远显示本机 IP、WebUI 出不去）。改为按 mark 精确放行隧道 socket。
     *
     * 为什么 App 自己设不了：`setsockopt(SO_MARK)` 需要 `CAP_NET_ADMIN`，普通 App 进程没有
     * （Linux 5.17 起也接受 `CAP_NET_RAW`，同样没有）⇒ 只能由 root 侧的 `sockmark`
     * 二进制代设（见 `core/jni/sockmark/main.c`）。
     *
     * 取值约定：非 0 的任意值都可以，但**必须与 tproxy.sh 收到的 `ROUTING_MARK` 一致**，
     * 也必须与传给 Go 侧 `RegisterSocketMarkHelper` 的值一致 —— 三处由本常量收口。
     * 挑 `0x5354`（"ST"）是因为它足够小、避开 Android 自己在用的 mark 区段。
     */
    const val SOCKET_MARK: Int = 0x5354
}
