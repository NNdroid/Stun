package app.fjj.stun.ui

import app.fjj.stun.core.R as CoreR
import java.util.Base64

/**
 * 节点编辑字段的纯校验规则。实时校验（输入时）与保存校验复用同一个
 * [FieldRules.compute]，差异只是调用方拿全量结果（保存）还是单字段结果（实时）。
 *
 * 规则来源：myssh docs/transports.md §6「建连前即拒绝（硬错误）」+ §4 逐类型
 * 校验；warn 级 = myssh 仅告警（短 PSK、xhttp 无指纹 MITM），不阻断保存。
 */
enum class FieldKey {
    SSH_ADDR, PROXY_ADDR,
    DNS_SERVERS, DNS_DOMAIN, DNS_RECORD_TYPE, DNS_PSK, DNS_MARKER,
    NOISE_KEY,
    UDP_PSK, UDP_MAGIC, UDP_MAX_PKT, UDP_MTU_PROBE,
    ICMP_PSK, ICMP_MAGIC,
    KCP_PASSWORD, KCP_SNDWND, KCP_RCVWND, KCP_MTU, KCP_SMUXVER, KCP_KEEPALIVE, KCP_DATA_SHARDS, KCP_PARITY_SHARDS,
    HTTP_PAYLOAD,
    AUTH_USER, AUTH_PASS, AUTH_TOKEN,
    SSH_FINGERPRINT, CERT_FINGERPRINT,
    SERVER_NAME, CUSTOM_PATH,
    CHUNK_SIZE, HEARTBEAT, PADDING_MIN_BYTES, MASQUE_ALPN
}

/** 编辑界面当前的字段快照（全部已 trim 的字符串；开关为布尔）。 */
data class FieldInputs(
    val spec: TunnelFieldSpec,
    val tlsActive: Boolean,
    val sshAddr: String,
    val proxyAddr: String,
    val serverName: String,
    val customPath: String,
    val dnsServers: String,
    val dnsDomain: String,
    val dnsRecordType: String,
    val dnsPsk: String,
    val dnsMarker: String,
    val noisePublicKey: String,
    val udpPsk: String,
    val udpMagic: String,
    val udpMaxPkt: String,
    val udpMtuProbe: String,
    val icmpPsk: String,
    val icmpMagic: String,
    val kcpPassword: String,
    val kcpSndwnd: String,
    val kcpRcvwnd: String,
    val kcpMtu: String,
    val kcpSmuxver: String,
    val kcpKeepalive: String,
    val kcpDataShards: String,
    val kcpParityShards: String,
    val httpPayload: String,
    val authEnabled: Boolean,
    val authUser: String,
    val authPass: String,
    val authToken: String,
    val verifySshFp: Boolean,
    val sshFingerprint: String,
    val verifyCertFp: Boolean,
    val certFingerprint: String,
    val chunkSize: String,
    val heartbeat: String,
    val paddingMinBytes: String,
    val masqueAlpn: String,
    /** udp/icmp 的 PSK 允许回退到 SSH 密码（VpnConfigBuilder 有同回退） */
    val sshPassFallbackAvailable: Boolean
)

/** @param errors 阻断保存的硬错误（resId）；@param warnings 仅提示（resId）。 */
data class FieldVerdict(
    val errors: Map<FieldKey, Int>,
    val warnings: Map<FieldKey, Int>
)

object FieldRules {

    // ── 纯谓词 ────────────────────────────────────────────────

    private val HOST_PORT = Regex("""^([^\s:/]+|\[[0-9a-fA-F:.]+\]):(\d{1,5})$""")
    private val HOST_PORT_RANGE = Regex("""^([^\s:/]+|\[[0-9a-fA-F:.]+\]):(\d{1,5}(?:-\d{1,5})?(?:,\d{1,5}(?:-\d{1,5})?)*)$""")
    private val BARE_HOST = Regex("""^(\[[0-9a-fA-F:.]+\]|[A-Za-z0-9][A-Za-z0-9._\-]*)$""")

    fun isHostPort(value: String): Boolean =
        HOST_PORT.matches(value) && value.substringAfterLast(':').toIntOrNull()?.inRange1to65535() == true

    fun isHostPortRange(value: String): Boolean {
        val m = HOST_PORT_RANGE.matchEntire(value) ?: return false
        return m.groupValues[2].split(',').all { part ->
            val bounds = part.split('-').mapNotNull { it.toIntOrNull() }
            bounds.isNotEmpty() && bounds.all { it.inRange1to65535() } &&
                (bounds.size == 1 || bounds[0] <= bounds[1])
        }
    }

    fun isBareHost(value: String): Boolean = BARE_HOST.matches(value)

    /**
     * SSH 主机密钥的 SHA-256 指纹。x/crypto/ssh 使用 OpenSSH 的
     * `SHA256:<unpadded-base64>` 表示；同时接受旧版 Android UI 曾允许保存的
     * 64 位 hex，VpnConfigBuilder 会在交给 myssh 前将其转换为 OpenSSH 格式。
     */
    fun isSshFingerprint(value: String): Boolean {
        val trimmed = value.trim()
        if (isFingerprint(trimmed)) return true
        if (!trimmed.startsWith("SHA256:")) return false
        val encoded = trimmed.removePrefix("SHA256:")
        if (!Regex("^[A-Za-z0-9+/]{43}=?$").matches(encoded)) return false
        return runCatching {
            Base64.getDecoder().decode(encoded.padEnd(44, '=')).size == 32
        }.getOrDefault(false)
    }

    /** TLS 证书 SHA-256 指纹：64 位 hex，可含冒号/空格分隔。 */
    fun isFingerprint(value: String): Boolean {
        val stripped = value.replace(":", "").replace(" ", "")
        return stripped.length == 64 && stripped.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }

    /** Noise 静态公钥：hex(64) 或 base64(32 字节)（与 udp_custom/dns/icmp SDK 解析一致） */
    fun isNoisePublicKey(value: String): Boolean {
        if (value.length == 64 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return true
        return runCatching {
            android.util.Base64.decode(value, android.util.Base64.DEFAULT).size == 32
        }.getOrDefault(false)
    }

    /** udp_custom magic：空 / 0x1~8hex / 恰好 4 字节原文（parseMagicSDK allowRaw=true） */
    fun isUdpMagic(value: String): Boolean =
        value.isEmpty() || isHexMagic(value) || value.toByteArray(Charsets.UTF_8).size == 4

    /** icmp_custom magic：空 / 0x1~8hex / 8hex（allowRaw=false，拒绝 ASCII 字面） */
    fun isIcmpMagic(value: String): Boolean = value.isEmpty() || isHexMagic(value)

    /** udp_custom MTU 探测模式：空 / auto（默认开启） / on, true, 1（强制开启） / off, false, 0（关闭） */
    fun isUdpMtuProbe(value: String): Boolean =
        value.isEmpty() || value.lowercase().trim() in setOf("auto", "on", "true", "1", "off", "false", "0")

    /**
     * masque ALPN：空 / auto（SDK 默认）/ h3 / h2。
     * h2tunnel SDK 只接受 ""(auto)/"h3"/"h2" 三个取值（client_api.go 严格字符串相等，不做逗号拆分），
     * 故不再接受 "h3,h2" / "h2,h3" 这类多值写法（那是已移除的历史别名，见 Profile.normalizeMasqueAlpn）。
     */
    fun isMasqueAlpn(value: String): Boolean {
        val v = value.lowercase().replace(" ", "")
        return v.isEmpty() || v in setOf("auto", "h3", "h2")
    }

    private fun isHexMagic(value: String): Boolean {
        val digits = if (value.lowercase().startsWith("0x")) value.substring(2) else value
        return digits.isNotEmpty() && digits.length <= 8 && digits.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
    }

    /** 空=默认；否则整数且 min<=v<=max。 */
    fun intOrBlankInRange(value: String, min: Int, max: Int): Boolean =
        value.isEmpty() || (value.toIntOrNull()?.let { it in min..max } == true)

    /** 空=默认；**0 也合法**（= 回落默认 25000，与错误文案「只能为 0，或 5000 到 300000」及 WebUI 口径一致）；其余同上。 */
    fun intOrBlankOrZeroInRange(value: String, min: Int, max: Int): Boolean =
        value.isEmpty() || (value.toIntOrNull()?.let { it == 0 || it in min..max } == true)

    /** 分块大小特例：空/0=默认256，否则必须 16..900（myssh 硬拒 <0/>900） */
    fun isChunkSize(value: String): Boolean =
        value.isEmpty() || (value.toIntOrNull()?.let { it == 0 || it in 16..900 } == true)

    /** DNS 上游列表：逗号/空格/换行分隔，允许 scheme 前缀 udp/tcp/tls/dot/https */
    fun isDnsServerList(value: String): Boolean {
        val items = value.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return false
        return items.all { entry ->
            val hostPart = entry.substringAfter("://", entry)
            hostPart.isNotBlank() && !hostPart.contains(' ')
        }
    }

    private fun Int.inRange1to65535(): Boolean = this in 1..65535

    // ── 统一 compute ──────────────────────────────────────────

    fun compute(i: FieldInputs): FieldVerdict {
        val errors = LinkedHashMap<FieldKey, Int>()
        val warnings = LinkedHashMap<FieldKey, Int>()
        val req = CoreR.string.error_field_required

        fun require(key: FieldKey, cond: Boolean, res: Int = req) {
            if (!cond) errors[key] = res
        }
        fun warn(key: FieldKey, cond: Boolean, res: Int) {
            if (cond) warnings[key] = res
        }

        // §1 全局
        require(FieldKey.SSH_ADDR, i.sshAddr.isNotEmpty() && isHostPort(i.sshAddr), CoreR.string.error_invalid_address_single_port)

        // raw 关 TLS 时 myssh 直连 ssh_addr，proxy_addr 不被读取 → 不校验（与显隐一致）
        if (!(i.spec.proxyAddrWhenTlsOnly && !i.tlsActive)) {
            when (i.spec.proxyAddr) {
                ProxyAddrMode.HOST_PORT ->
                    require(FieldKey.PROXY_ADDR, i.proxyAddr.isNotEmpty() && isHostPort(i.proxyAddr), CoreR.string.error_invalid_address_single_port)
                ProxyAddrMode.HOST_PORT_RANGE ->
                    require(FieldKey.PROXY_ADDR, i.proxyAddr.isNotEmpty() && isHostPortRange(i.proxyAddr), CoreR.string.error_invalid_address)
                ProxyAddrMode.BARE_HOST ->
                    require(FieldKey.PROXY_ADDR, i.proxyAddr.isNotEmpty() && isBareHost(i.proxyAddr), CoreR.string.error_invalid_host_only)
                ProxyAddrMode.HIDDEN -> Unit
            }
        }

        // TLS 相关（§2）
        if (i.tlsActive && i.serverName.isNotEmpty() && i.serverName.any { it.isWhitespace() }) {
            errors[FieldKey.SERVER_NAME] = CoreR.string.error_invalid_server_name
        }
        if (i.spec.customPath && i.customPath.isNotEmpty() && !i.customPath.startsWith("/")) {
            errors[FieldKey.CUSTOM_PATH] = CoreR.string.error_invalid_path
        }
        if (i.verifySshFp) require(FieldKey.SSH_FINGERPRINT, i.sshFingerprint.isNotEmpty() && isSshFingerprint(i.sshFingerprint), CoreR.string.error_invalid_ssh_fingerprint)
        if (i.tlsActive && i.verifyCertFp) require(FieldKey.CERT_FINGERPRINT, i.certFingerprint.isNotEmpty() && isFingerprint(i.certFingerprint), CoreR.string.error_invalid_fingerprint)
        // xhttp HTTPS 无指纹 → MITM 告警（docs §6 Warn）
        warn(FieldKey.CERT_FINGERPRINT, i.spec.xhttpOptions && i.tlsActive && !i.verifyCertFp, CoreR.string.warn_xhttp_no_fingerprint)

        // 代理鉴权（§2/§4：required=true 但对应凭据空 = 硬错误）
        if (i.spec.proxyAuth != ProxyAuthMode.NONE && i.authEnabled) {
            when (i.spec.proxyAuth) {
                ProxyAuthMode.USER_PASS -> {
                    require(FieldKey.AUTH_USER, i.authUser.isNotEmpty())
                    require(FieldKey.AUTH_PASS, i.authPass.isNotEmpty())
                }
                ProxyAuthMode.BEARER -> require(FieldKey.AUTH_TOKEN, i.authToken.isNotEmpty())
                ProxyAuthMode.NONE -> Unit
            }
        }

        // 类型专属组（§4）
        if (i.spec.httpPayload) require(FieldKey.HTTP_PAYLOAD, i.httpPayload.isNotEmpty())

        if (i.spec.dnsOptions) {
            require(FieldKey.DNS_SERVERS, i.dnsServers.isNotEmpty() && isDnsServerList(i.dnsServers), CoreR.string.error_invalid_dns_servers)
            require(FieldKey.DNS_DOMAIN, i.dnsDomain.isNotEmpty())
            if (i.noisePublicKey.isNotEmpty() && i.dnsRecordType.lowercase() in setOf("a", "aaaa")) {
                errors[FieldKey.DNS_RECORD_TYPE] = CoreR.string.error_dns_noise_record_type
            }
            warn(FieldKey.DNS_PSK, i.dnsPsk.isNotEmpty() && i.dnsPsk.length < 16, CoreR.string.warn_psk_short)
            if (i.dnsMarker.isNotEmpty() && (i.dnsMarker.any { it.isWhitespace() } || i.dnsMarker.length > 32)) {
                errors[FieldKey.DNS_MARKER] = CoreR.string.error_invalid_marker
            }
        }

        if (i.spec.noisePublicKey && i.noisePublicKey.isNotEmpty()) {
            require(FieldKey.NOISE_KEY, isNoisePublicKey(i.noisePublicKey), CoreR.string.error_invalid_noise_public_key)
        }

        if (i.spec.udpOptions) {
            require(FieldKey.UDP_PSK, i.udpPsk.isNotEmpty() || i.sshPassFallbackAvailable)
            warn(FieldKey.UDP_PSK, i.udpPsk.isNotEmpty() && i.udpPsk.length < 16, CoreR.string.warn_psk_short)
            require(FieldKey.UDP_MAGIC, isUdpMagic(i.udpMagic), CoreR.string.error_udp_custom_magic)
            // myssh 152c556：路径 MTU 探测与最大包长
            require(FieldKey.UDP_MAX_PKT, intOrBlankInRange(i.udpMaxPkt, 0, 65535), CoreR.string.error_invalid_number)
            require(FieldKey.UDP_MTU_PROBE, isUdpMtuProbe(i.udpMtuProbe), CoreR.string.error_invalid_mtu_probe)
        }

        if (i.spec.icmpOptions) {
            require(FieldKey.ICMP_PSK, i.icmpPsk.isNotEmpty() || i.sshPassFallbackAvailable)
            warn(FieldKey.ICMP_PSK, i.icmpPsk.isNotEmpty() && i.icmpPsk.length < 16, CoreR.string.warn_psk_short)
            require(FieldKey.ICMP_MAGIC, isIcmpMagic(i.icmpMagic), CoreR.string.error_icmp_magic)
        }

        if (i.spec.kcpOptions) {
            require(FieldKey.KCP_PASSWORD, i.kcpPassword.isNotEmpty())
            require(FieldKey.KCP_SNDWND, intOrBlankInRange(i.kcpSndwnd, 0, 65535), CoreR.string.error_invalid_number)
            require(FieldKey.KCP_RCVWND, intOrBlankInRange(i.kcpRcvwnd, 0, 65535), CoreR.string.error_invalid_number)
            require(FieldKey.KCP_MTU, intOrBlankInRange(i.kcpMtu, 0, 65535), CoreR.string.error_invalid_number)
            require(FieldKey.KCP_SMUXVER, intOrBlankInRange(i.kcpSmuxver, 0, 2), CoreR.string.error_invalid_number)
            require(FieldKey.KCP_KEEPALIVE, intOrBlankInRange(i.kcpKeepalive, 0, 86400), CoreR.string.error_invalid_number)
            require(FieldKey.KCP_DATA_SHARDS, intOrBlankInRange(i.kcpDataShards, 0, 255), CoreR.string.error_invalid_number)
            require(FieldKey.KCP_PARITY_SHARDS, intOrBlankInRange(i.kcpParityShards, 0, 255), CoreR.string.error_invalid_number)
        }

        if (i.spec.xhttpOptions) {
            require(FieldKey.CHUNK_SIZE, isChunkSize(i.chunkSize), CoreR.string.error_xhttp_chunk_size)
        }

        if (i.spec.heartbeat) {
            require(FieldKey.HEARTBEAT, intOrBlankOrZeroInRange(i.heartbeat, 5000, 300000), CoreR.string.error_heartbeat_interval)
        }

        // myssh 152c556：h2tunnel 出站帧最小填充（流量混淆，负数=关闭，0=默认1420）
        if (i.spec.paddingOptions) {
            require(FieldKey.PADDING_MIN_BYTES, intOrBlankInRange(i.paddingMinBytes, -1, 65535), CoreR.string.error_invalid_number)
        }

        // myssh 152c556：masque 独有 ALPN 选择（SDK 仅在 masque transport 上接受此字段）
        if (i.spec.masqueAlpnOptions) {
            require(FieldKey.MASQUE_ALPN, isMasqueAlpn(i.masqueAlpn), CoreR.string.error_invalid_masque_alpn)
        }

        return FieldVerdict(errors, warnings)
    }
}
