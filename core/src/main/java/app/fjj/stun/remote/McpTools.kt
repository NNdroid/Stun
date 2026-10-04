package app.fjj.stun.remote

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * MCP「工具清单」的**唯一事实来源**（27 个 tool 的 name / description / inputSchema）。
 *
 * ## 为什么要单独成文件
 * 原来这 277 行是 `StunMcpServer` 里的一个私有方法，而那个对象有 3300+ 行、同时管着
 * 证书、sniffer、路由、OAuth、tools 执行、resources、prompts。结果是：
 *  - 想确认"到底对外暴露了哪些能力、能传什么参数"要在一个 3300 行文件里翻 277 行；
 *  - 改一个工具描述符的 risk 评估成本极高（看上去像在动服务器主循环）。
 *
 * 现在它是**纯数据**（只依赖 Gson，零内部状态、零 Android 依赖），可以单独读、单独测。
 *
 * ## 一致性由测试保证
 * `McpToolsSchemaTest` 会校验：
 *  - 工具数量与名称集合（防止"删了一个工具"这种无声回归）；
 *  - 每个工具的 `inputSchema` 至少是 object（有 `type` 与 `properties`），
 *    `required` 非空时必须是数组 —— MCP 客户端据此做表单，缺了会直接报错。
 */
internal object McpTools {

    fun toolsList(): JsonObject {
        val toolsArray = JsonArray()

        fun addTool(name: String, desc: String, props: JsonObject, required: List<String> = emptyList()) {
            toolsArray.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("description", desc)
                add("inputSchema", JsonObject().apply {
                    addProperty("type", "object")
                    add("properties", props)
                    if (required.isNotEmpty()) {
                        val reqArr = JsonArray()
                        required.forEach { reqArr.add(it) }
                        add("required", reqArr)
                    }
                })
            })
        }

        // 1. get_vpn_status
        addTool("get_vpn_status", "Query live VPN connection state, active node name, protocol, server IP, public IP, uplink/downlink speed, and total traffic bytes.", JsonObject())

        // 2. start_vpn
        addTool("start_vpn", "Start VPN connection. Optionally pass profileId or profileName to connect to a specific node.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile ID") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile Name") })
        })

        // 3. stop_vpn
        addTool("stop_vpn", "Stop and disconnect the active VPN tunnel.", JsonObject())

        // 4. restart_vpn
        addTool("restart_vpn", "Restart and reconnect the VPN tunnel.", JsonObject())

        // 5. list_profiles
        addTool("list_profiles", "List all configured proxy nodes with summary details (ID, name, tunnel type, server, and selection status).", JsonObject())

        // 6. get_profile_detail
        addTool("get_profile_detail", "Get complete configuration parameters for a specific profile node. Credential fields (pass, privateKey, keyPass, proxyAuthToken, proxyAuthPass and the per-tunnel PSKs) are returned masked as \"*****\" — real secrets are never exposed. Sending \"*****\" back to update_profile means \"keep the current value\".", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile ID") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile Name") })
        })

        // 7. create_profile
        addTool("create_profile", "Create and save a new proxy node configuration with specific fields.", JsonObject().apply {
            add("name", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Node name") })
            add("tunnelType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Tunnel type: raw, websocket, h2, grpc, h3, webtransport, masque, quic, xhttp, http, kcptun, dns_custom, udp_custom, icmp_custom") })
            add("sshAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH server address (e.g. 1.2.3.4:22)") })
            add("user", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH username") })
            add("pass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH password") })
            add("proxyAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy or CDN server address (e.g. cdn.example.com:443)") })
            add("serverName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "TLS SNI Server Name") })
            add("customHost", JsonObject().apply { addProperty("type", "string"); addProperty("description", "HTTP Host Header / WebSocket Host") })
            add("customPath", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Path used directly by path-based tunnels; for MASQUE, empty keeps the SDK default and non-empty overrides it") })
            add("enableCustomPath", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "MASQUE only: explicitly enable or disable overriding its default path") })
            add("alpn", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated ALPN values") })
            add("proxyAuthRequired", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Require proxy authentication") })
            add("proxyAuthToken", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy authentication token") })
            add("heartbeatIntervalMs", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "h2-family heartbeat in milliseconds; 0 uses the SDK default") })
            add("xhttpChunkSizeKB", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "XHTTP upstream chunk size in KB; 0 uses the SDK default") })
            add("udpCustomPsk", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom pre-shared key") })
            add("udpCustomPublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom Noise server public key") })
            add("udpCustomMagic", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom 4-byte magic, for example UDPC") })
            add("udpCustomPaths", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom remote path count; 0 uses 32") })
            add("udpCustomSockets", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom local socket count; 0 uses 1") })
            add("udpCustomSendWindow", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom in-flight frame window; 0 uses 256") })
            add("dnsTunnelDomain", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel domain") })
            add("dnsTunnelServers", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated DNS tunnel resolvers") })
            add("dnsTunnelType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS record type") })
            add("dnsTunnelPublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel Noise server public key") })
            add("dnsTunnelEDNS0", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Enable 1232-byte EDNS0 answers") })
            add("dnsTunnelPsk", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel PSK (blank=anonymous, must match server)") })
            add("dnsTunnelMarker", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DNS tunnel custom marker (blank=default, must match server)") })
            add("tunnelTlsEnabled", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Enable TLS for toggle-capable types (raw/websocket/h2/grpc/xhttp)") })
            add("authType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "SSH auth type: password (default) or privatekey") })
            add("privateKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "PEM private key (when authType=privatekey)") })
            add("keyPass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Private key passphrase (optional)") })
            add("proxyAuthUser", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy basic auth username (websocket/http)") })
            add("proxyAuthPass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy basic auth password (websocket/http)") })
            add("noisePublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Shared Noise static public key (dns/udp/icmp)") })
            add("icmpCustomPsk", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP Custom PSK (required; falls back to SSH password if blank)") })
            add("icmpCustomMagic", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP Custom 4-byte magic hex (e.g. 49434D31) or 0x-prefixed; blank=default") })
            add("icmpCustomPublicKey", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP Custom Noise static public key (hex64 or base64)") })
            add("icmpCustomMtuMode", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP MTU mode: probe (default), auto, fixed") })
            add("icmpCustomMaxPayload", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "ICMP max payload; 0 = SDK default") })
            add("icmpCustomPaceMS", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "ICMP outbound pacing interval ms; 0 = SDK default") })
            add("icmpCustomIdRange", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ICMP echo identifier pool, e.g. 1000-1999") })
            add("udpCustomMaxPkt", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "UDP Custom max payload bytes; 0 = SDK default 1450") })
            add("udpCustomMtuProbe", JsonObject().apply { addProperty("type", "string"); addProperty("description", "UDP Custom path MTU probing: auto (default), on, off") })
            add("paddingMinBytes", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "h2tunnel min padding bytes; 0 = default 1420, negative = off") })
            add("masqueAlpn", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Masque-only ALPN choice: auto (default), h3 or h2") })
            add("kcpPassword", JsonObject().apply { addProperty("type", "string"); addProperty("description", "KCP (kcptun) password (required for kcptun type)") })
            add("kcpCrypt", JsonObject().apply { addProperty("type", "string"); addProperty("description", "KCP encryption cipher (e.g. aes-128, aes-256, sm4, none)") })
            add("kcpMode", JsonObject().apply { addProperty("type", "string"); addProperty("description", "KCP nodelay preset: normal, fast (default), fast2, fast3") })
            add("kcpSndWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP send window; 0 uses 128") })
            add("kcpRcvWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP receive window; 0 uses 512") })
            add("kcpMtu", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP MTU; 0 uses 1350") })
            add("kcpNoComp", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Disable KCP Snappy session compression") })
            add("kcpSmuxVer", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "SMUX version (1 or 2); 0 uses 2") })
            add("kcpKeepAlive", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP keepalive seconds; 0 uses 10") })
            add("kcpDataShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP FEC data shards; 0 uses 10") })
            add("kcpParityShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("description", "KCP FEC parity shards; 0 uses 3") })
            // ── 2026-10 补齐：以下 20 个字段 WebUI / 原生编辑页都能设，MCP 此前既没实现也没声明。
            // 后果是"通过 MCP 建的节点没有收藏、没有备注、直连规则为空"，而 App 里看一切正常。
            add("httpPayload", JsonObject().apply { addProperty("type", "string"); addProperty("description", "HTTP payload template for httpPayload tunnel type; [host] is substituted") })
            add("disableStatusCheck", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Skip SSH host-key / reachability pre-check on connect") })
            add("verifyFingerprint", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Verify server SSH host-key fingerprint") })
            add("serverFingerprint", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Expected SSH host-key fingerprint (required when verifyFingerprint is true)") })
            add("verifyCertFingerprint", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Verify TLS server certificate fingerprint") })
            add("serverCertFingerprint", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Expected TLS certificate fingerprint (required when verifyCertFingerprint is true)") })
            add("xhttpStreamMode", JsonObject().apply { addProperty("type", "string"); addProperty("description", "XHTTP downlink transport: auto (default, SDK adaptive), stream, poll. Values outside this set are ignored") })
            add("bindInterface", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Bind outbound socket to a network interface (e.g. wlan0); empty = no binding") })
            add("note", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Free-form note shown in the node list") })
            add("favorite", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Mark node as favorite") })
            add("dnsOverride", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Override the global DNS settings for this node") })
            add("remoteDns", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Remote DNS resolver, e.g. doh://8.8.8.8/dns-query (used when dnsOverride is true)") })
            add("localDns", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Local DNS resolver, e.g. doh://223.5.5.5/dns-query (used when dnsOverride is true)") })
            add("udpgwVersion", JsonObject().apply { addProperty("type", "string"); addProperty("description", "udpgw server version: tun2proxy (default), libc, gvisor, or auto") })
            add("udpgwAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "udpgw server address, e.g. 127.0.0.1:7300") })
            add("geositeDirect", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated geosite tags to route DIRECT; default cn,apple") })
            add("geoipDirect", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated geoip tags to route DIRECT; default cn,private") })
            add("appFilterOverride", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Override the global per-app VPN filter for this node") })
            add("filterMode", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("maximum", 1); addProperty("description", "App filter mode: 0 = Disallow list, 1 = Allow list") })
            add("filterApps", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Package-name filter list; comma-separated, e.g. com.tencent.mm,com.example.app") })
        }, listOf("name", "sshAddr"))

        // 8. update_profile
        addTool("update_profile", "Update parameters of an existing profile node.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile ID to update") })
            add("name", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New node name") })
            add("tunnelType", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New lowercase tunnel type ID") })
            add("sshAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New SSH server address") })
            add("user", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New SSH username") })
            add("pass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New SSH password") })
            add("proxyAddr", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New proxy address") })
            add("serverName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New TLS SNI") })
            add("customHost", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New Host header") })
            add("customPath", JsonObject().apply { addProperty("type", "string"); addProperty("description", "New path; for MASQUE, empty keeps the SDK default and non-empty overrides it") })
            add("enableCustomPath", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "MASQUE only: explicitly enable or disable overriding its default path") })
            add("alpn", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated ALPN values") })
            add("proxyAuthRequired", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Require proxy authentication") })
            add("proxyAuthToken", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Proxy authentication token") })
            add("heartbeatIntervalMs", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("xhttpChunkSizeKB", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomPsk", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomPublicKey", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomMagic", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomPaths", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomSockets", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomSendWindow", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("dnsTunnelDomain", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelServers", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelType", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelPublicKey", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelEDNS0", JsonObject().apply { addProperty("type", "boolean") })
            add("dnsTunnelPsk", JsonObject().apply { addProperty("type", "string") })
            add("dnsTunnelMarker", JsonObject().apply { addProperty("type", "string") })
            add("tunnelTlsEnabled", JsonObject().apply { addProperty("type", "boolean") })
            add("authType", JsonObject().apply { addProperty("type", "string") })
            add("privateKey", JsonObject().apply { addProperty("type", "string") })
            add("keyPass", JsonObject().apply { addProperty("type", "string") })
            add("proxyAuthUser", JsonObject().apply { addProperty("type", "string") })
            add("proxyAuthPass", JsonObject().apply { addProperty("type", "string") })
            add("noisePublicKey", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomPsk", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomMagic", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomPublicKey", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomMtuMode", JsonObject().apply { addProperty("type", "string") })
            add("icmpCustomMaxPayload", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("icmpCustomPaceMS", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("icmpCustomIdRange", JsonObject().apply { addProperty("type", "string") })
            add("udpCustomMaxPkt", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("udpCustomMtuProbe", JsonObject().apply { addProperty("type", "string") })
            add("paddingMinBytes", JsonObject().apply { addProperty("type", "integer") })
            add("masqueAlpn", JsonObject().apply { addProperty("type", "string") })
            add("kcpPassword", JsonObject().apply { addProperty("type", "string") })
            add("kcpCrypt", JsonObject().apply { addProperty("type", "string") })
            add("kcpMode", JsonObject().apply { addProperty("type", "string") })
            add("kcpSndWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpRcvWnd", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpMtu", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpNoComp", JsonObject().apply { addProperty("type", "boolean") })
            add("kcpSmuxVer", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpKeepAlive", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpDataShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            add("kcpParityShards", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0) })
            // ── 2026-10 与 create_profile 同批补齐（本节惯例：无 description，只声明类型）──
            add("httpPayload", JsonObject().apply { addProperty("type", "string") })
            add("disableStatusCheck", JsonObject().apply { addProperty("type", "boolean") })
            add("verifyFingerprint", JsonObject().apply { addProperty("type", "boolean") })
            add("serverFingerprint", JsonObject().apply { addProperty("type", "string") })
            add("verifyCertFingerprint", JsonObject().apply { addProperty("type", "boolean") })
            add("serverCertFingerprint", JsonObject().apply { addProperty("type", "string") })
            add("xhttpStreamMode", JsonObject().apply { addProperty("type", "string") })
            add("bindInterface", JsonObject().apply { addProperty("type", "string") })
            add("note", JsonObject().apply { addProperty("type", "string") })
            add("favorite", JsonObject().apply { addProperty("type", "boolean") })
            add("dnsOverride", JsonObject().apply { addProperty("type", "boolean") })
            add("remoteDns", JsonObject().apply { addProperty("type", "string") })
            add("localDns", JsonObject().apply { addProperty("type", "string") })
            add("udpgwVersion", JsonObject().apply { addProperty("type", "string") })
            add("udpgwAddr", JsonObject().apply { addProperty("type", "string") })
            add("geositeDirect", JsonObject().apply { addProperty("type", "string") })
            add("geoipDirect", JsonObject().apply { addProperty("type", "string") })
            add("appFilterOverride", JsonObject().apply { addProperty("type", "boolean") })
            add("filterMode", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 0); addProperty("maximum", 1) })
            add("filterApps", JsonObject().apply { addProperty("type", "string") })
        }, listOf("profileId"))

        // 9. delete_profile
        addTool("delete_profile", "Delete a profile node by ID or name.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile ID to delete") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Profile Name to delete") })
        })

        // 10. select_profile
        addTool("select_profile", "Select active profile node by profileId or profileName with auto-reconnection.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile ID") })
            add("profileName", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Target Profile Name") })
        })

        // 11. test_node_latency
        addTool("test_node_latency", "Execute node latency (ping) test on all nodes or a specific node. Latency is measured via the SSH node tunnel handshake, matching the bottom-bar latency source.", JsonObject().apply {
            add("profileId", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Optional profile ID to test") })
        })

        // 12. get_app_filter_list
        addTool("get_app_filter_list", "Get installed apps on device and their split-tunneling proxy/bypass status.", JsonObject())

        // 13. set_app_filter
        addTool("set_app_filter", "Configure split tunneling mode (0: Bypass selected, 1: Proxy only selected) and packages.", JsonObject().apply {
            add("mode", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "0 = Disallow / Bypass selected, 1 = Allow / Proxy only selected") })
            add("packages", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Comma-separated package names (e.g. org.telegram.messenger,com.android.chrome)") })
        }, listOf("mode", "packages"))

        // 14. update_geodata
        addTool("update_geodata", "Trigger immediate download and update of geosite.dat and geoip.dat rule databases.", JsonObject())

        // 15. get_settings
        addTool("get_settings", "Get all global application settings (DNS, UDPGW, Routing rules, Service Mode).", JsonObject())

        // 16. set_settings
        addTool("set_settings", "Update global application settings.", JsonObject().apply {
            add("remoteDns", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Remote DNS DoH URL (e.g. doh://8.8.8.8/dns-query)") })
            add("localDns", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Local DNS DoH URL (e.g. doh://223.5.5.5/dns-query)") })
            add("serviceMode", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "0 = VPN mode, 1 = Root TProxy mode") })
            add("logLevel", JsonObject().apply { addProperty("type", "string"); addProperty("description", "DEBUG, INFO, WARN, ERROR") })
            add("geositeDirect", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Geosite direct routing tags (e.g. cn,apple)") })
            add("geoipDirect", JsonObject().apply { addProperty("type", "string"); addProperty("description", "GeoIP direct routing tags (e.g. cn,private)") })
            add("mcpServerPort", JsonObject().apply { addProperty("type", "integer"); addProperty("description", "MCP Server listening port (e.g. 37180)") })
        })

        // 17. get_logs
        addTool("get_logs", "Retrieve recent system and Go tunnel logs with optional level filter, keyword and line count.", JsonObject().apply {
            // ⚠️ 描述里的默认值/上限必须与 `StunMcpServer` 的 `"get_logs"` 分支一致：
            // 实现是 `args.get("limit")?.asInt ?: 200` + `coerceIn(1, 2000)`。
            // 此前这里写的是 "default: 50, max: 500"，客户端照此调参会拿到两倍于预期的日志量。
            add("limit", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 1); addProperty("maximum", 2000); addProperty("description", "Max lines (default: 200, range 1..2000)") })
            add("level", JsonObject().apply { addProperty("type", "string"); addProperty("description", "ALL, INFO, WARN, ERROR, DEBUG") })
            // 2026-10：实现一直支持 keyword 过滤（磁盘日志与内存日志两条路径都生效），
            // 但 schema 从未声明 ⇒ 客户端根本不知道能传，日志检索能力等于不存在。
            add("keyword", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Case-insensitive substring filter matched against message + tag (in-memory path only when no disk log files exist)") })
        })

        // 18. get_device_info
        addTool("get_device_info", "Get hardware model, battery level, Android version, app & core versions, and local IP addresses.", JsonObject())

        // ── 2026-09-12: WebDAV / 订阅 / 导入导出 ──

        // 19. get_webdav_config
        addTool("get_webdav_config", "Get WebDAV cloud backup configuration (URL, account, auto-backup switch, interval, last backup time). Never echoes pass/pin.", JsonObject())

        // 20. set_webdav_config
        addTool("set_webdav_config", "Update WebDAV cloud backup configuration. Blank pass/pin means keep existing values.", JsonObject().apply {
            add("url", JsonObject().apply { addProperty("type", "string"); addProperty("description", "WebDAV server URL (e.g. https://dav.jianguoyun.com/dav/)") })
            add("user", JsonObject().apply { addProperty("type", "string"); addProperty("description", "WebDAV account username") })
            add("pass", JsonObject().apply { addProperty("type", "string"); addProperty("description", "WebDAV password / app password") })
            add("pin", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Backup PIN for encryption (letters+digits, ≥4)") })
            add("auto", JsonObject().apply { addProperty("type", "boolean"); addProperty("description", "Enable the scheduled WebDAV task") })
            add("intervalHours", JsonObject().apply { addProperty("type", "integer"); addProperty("minimum", 1); addProperty("maximum", 720); addProperty("description", "Auto-backup interval in hours (1–720)") })
            add("syncMode", JsonObject().apply {
                addProperty("type", "string")
                add("enum", com.google.gson.JsonArray().apply { add("upload"); add("download"); add("both") })
                addProperty("description", "Sync direction: upload (push only, default), download (pull only), both (pull newer side, then push)")
            })
        })

        // 21. list_backups
        addTool("list_backups", "List available WebDAV backup directories on the server, newest first (Stun/<timestamp>/ format).", JsonObject())

        // 22. backup_now
        addTool("backup_now", "Trigger an immediate WebDAV backup (nodes + settings). Requires a fully configured WebDAV.", JsonObject())

        // 22b. sync_now
        addTool("sync_now", "Run one WebDAV sync using the configured sync mode. 'upload' behaves exactly like backup_now; 'download'/'both' also pull partitions whose server-side content is newer. Requires a fully configured WebDAV.", JsonObject().apply {
            add("mode", JsonObject().apply {
                addProperty("type", "string")
                add("enum", com.google.gson.JsonArray().apply { add("upload"); add("download"); add("both") })
                addProperty("description", "Optional one-off override; defaults to the configured sync mode")
            })
        })

        // 23. restore_backup
        addTool("restore_backup", "Restore from a specific WebDAV backup directory. Nodes are merged by ID; global settings are overwritten (except the backup PIN). Requires the dir name from list_backups.", JsonObject().apply {
            add("dir", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Backup directory name (e.g. 20260912-063005)") })
        }, listOf("dir"))

        // 24. list_subscriptions
        addTool("list_subscriptions", "List all configured subscription links (URL + optional PIN) and last sync time.", JsonObject())

        // 25. sync_subscriptions
        addTool("sync_subscriptions", "Fetch and merge all configured subscriptions. Nodes are merged by ID; returns per-subscription results.", JsonObject())

        // 26. import_profiles
        addTool("import_profiles", "Import profiles from a PIN-encrypted payload (stun:// share format) or plaintext JSON array. Nodes are merged by ID.", JsonObject().apply {
            add("content", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Encrypted payload (base64 stun:// format) or plaintext JSON profile array") })
            add("pin", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Decryption PIN (required for encrypted content)") })
        }, listOf("content"))

        // 27. export_profiles
        addTool("export_profiles", "Export all profiles as a PIN-encrypted share payload (same format as stun:// share URIs).", JsonObject().apply {
            add("pin", JsonObject().apply { addProperty("type", "string"); addProperty("description", "Encryption PIN (letters+digits, ≥4)") })
        }, listOf("pin"))

        return JsonObject().apply { add("tools", toolsArray) }
    }

}
