package app.fjj.stun.service

import android.content.Context
import app.fjj.stun.repo.Profile
import app.fjj.stun.repo.SettingsManager
import app.fjj.stun.repo.StunLogger
import java.io.File

object TransparentProxyConfigBuilder {

    /**
     * Generates the shell script configuration content (tproxy.conf).
     *
     * @param context Context to get package name
     * @param tcpPort TProxy TCP listening port
     * @param udpPort TProxy UDP listening port
     * @param dnsPort DNS hijack redirection port
     * @param extraBypassApps Additional app package names to bypass
     * @return Generated configuration string
     */
    fun buildShellConfig(
        context: Context,
        tcpPort: Int,
        udpPort: Int,
        dnsPort: Int,
        extraBypassApps: List<String> = listOf()
    ): String {
        // 1. Build bypass list
        val bypassList = mutableListOf("0:${context.packageName}")
        extraBypassApps.forEach { bypassList.add("0:$it") }
        val bypassString = bypassList.joinToString(" ")

        // 2. Build configuration content
        return """
            # Auto-generated tproxy.conf
            PROXY_TCP_PORT=$tcpPort
            PROXY_UDP_PORT=$udpPort
            PROXY_MODE=1
            DNS_HIJACK_ENABLE=1
            DNS_PORT=$dnsPort
            BLOCK_QUIC=1
            BYPASS_CN_IP=0
            CN_IP_URL=https://push.4544.de/https://raw.githubusercontent.com/Hackl0us/GeoIP2-CN/release/CN-ip-cidr.txt
            CN_IPV6_URL=https://push.4544.de/https://ispip.clang.cn/all_cn_ipv6.txt
            BYPASS_IPv4_LIST="0.0.0.0/8 10.0.0.0/8 100.0.0.0/8 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.0.0.0/24 192.0.2.0/24 192.88.99.0/24 192.168.0.0/16 198.51.100.0/24 203.0.113.0/24 224.0.0.0/4 240.0.0.0/4 255.255.255.255/32"
            BYPASS_IPv6_LIST="::/128 ::1/128 ::ffff:0:0/96 100::/64 64:ff9b::/96 2001::/32 2001:10::/28 2001:20::/28 2001:db8::/32 2002::/16 fe80::/10 ff00::/8"
            PROXY_IPV6=1
            APP_PROXY_ENABLE=1
            APP_PROXY_MODE=blacklist
            BYPASS_APPS_LIST="$bypassString"
            DRY_RUN=0
            # End of config
        """.trimIndent()
    }

    /**
     * Builds the configuration for hev-socks5-tproxy (Root Mode).
     * Supports dual-stack IPv4/IPv6 and DNS hijacking.
     */
    fun buildTProxyConfig(
        context: Context,
        profile: Profile,
        socksPort: Int,
        tproxyPort: Int,
        dnsPort: Int
    ): String {
        return """
            main:
              workers: 1
            misc:
              log-level: debug
            tcp:
              port: $tproxyPort
              address: '::' # Listen on all interfaces (IPv4/IPv6)
            udp:
              port: $tproxyPort
              address: '::'
            dns:
              port: $dnsPort
              address: '::'
              upstream: '8.8.8.8'
            socks5:
              port: $socksPort
              address: '127.0.0.1'
              udp: udp
        """.trimIndent()
    }
}
