let urlParams = new URLSearchParams(window.location.search);
let token = urlParams.get('token') || localStorage.getItem('web_auth_token') || '';
if (urlParams.get('token')) {
  localStorage.setItem('web_auth_token', urlParams.get('token'));
}

let currentLang = 'zh-CN';
let currentTheme = 'dark';
let currentTab = 'overview';
let autoScroll = true;
let allApps = [];
let allNodeApps = [];
let allProfiles = [];
// 列表筛选 tab：'all' | 'fav' | 'recent'（与手机端 chip_group_node_tabs 同一套语义）。
let profileFilter = 'all';
let allConnections = [];
let connsCurrentPage = 1;
const CONNS_PAGE_SIZE = 25;
let currentVpnState = 'DISCONNECTED';
let profileDelays = {}; // map of profileId -> { ok, latencyMs, display, testing }

let settingsTokens = {
  randomToken: '',
  permanentToken: '',
  customToken: ''
};

const I18N = {
  'zh-CN': {
    edit_sec_1_title: "🖥️ 基础连接与 SSH 认证",
    edit_sec_2_title: "🚀 传输协议与专属配置",
    edit_sec_3_title: "🛡️ 安全加密与指纹校验",
    edit_sec_4_title: "🌐 节点独立分流与规则覆盖",
    edit_name_placeholder: "例如：东京高速节点",
    edit_ssh_addr_placeholder: "IP:Port 或 域名:端口",
    edit_user_placeholder: "例如：root",
    edit_pass_placeholder: "留空则保持原密码不变",
    edit_key_pass_placeholder: "无口令可留空",
    edit_proxy_addr_placeholder: "IP:Port 或 域名:端口",
    edit_custom_host_placeholder: "例如：cloudflare.com",
    edit_server_name_placeholder: "TLS 握手 SNI 域名",
    edit_custom_path_placeholder: "/path/to/stream",
    edit_udp_psk_placeholder: "预共享密钥",
    edit_noise_pk_placeholder: "Base64 或 Hex 格式公钥",
    edit_ssh_fp_placeholder: "SHA256:... 或 MD5:...",
    edit_cert_fp_placeholder: "AA:BB:CC:DD...",
    edit_auth_token_placeholder: "例如：Token_Secret_888",
    btn_fetch_ssh_fp_title: "一键查询服务器 SSH 主机公钥指纹并填入",
    btn_details_ssh_title: "查询 SSH 主机密钥详情",
    btn_fetch_cert_fp_title: "一键查询服务器 TLS 证书指纹并填入",
    btn_details_cert_title: "查询 TLS 证书详细信息",

    info_target_address: "目标地址",
    info_server_banner: "服务端 Banner",
    info_public_key_type: "公钥算法类型",
    info_handshake_latency: "握手耗时",
    info_sha256_fingerprint: "SHA-256 指纹",
    info_md5_fingerprint: "MD5 指纹",
    info_sha1_fingerprint: "SHA-1 指纹",
    info_sni: "SNI 域名",
    info_subject: "主题 (Subject)",
    info_issuer: "颁发者 (Issuer)",
    info_sans: "备用名称 (SANs)",
    info_cert_validity: "证书有效期",
    info_cert_expired: "❌ 已过期",
    info_days_remaining: "✓ 剩余 {days} 天",
    info_signature_alg: "签名算法",
    info_public_key_alg: "公钥算法",
    info_tls_version: "TLS 版本",
    info_alpn_negotiation: "ALPN 协商",
    info_duration_ms: "耗时 {ms} ms",

    opt_tunnel_tls: "TLS (标准 TLS 加密隧道)",
    opt_tunnel_ws: "WS (WebSocket 明文)",
    opt_tunnel_wss: "WSS (WebSocket over TLS)",
    opt_tunnel_h2: "H2 (HTTP/2 over TLS)",
    opt_tunnel_h2c: "H2C (HTTP/2 Cleartext)",
    opt_tunnel_http: "HTTP (HTTP 代理隧道)",
    opt_tunnel_base: "BASE (直连 TCP 纯净隧道)",
    opt_tunnel_quic: "QUIC (QUIC 快速数据报)",
    opt_tunnel_grpc: "gRPC (gRPC over TLS)",
    opt_tunnel_grpcc: "gRPCC (gRPC Cleartext)",
    opt_tunnel_h3: "H3 (HTTP/3 over QUIC)",
    opt_tunnel_wt: "WebTransport",
    opt_tunnel_masque: "MASQUE (HTTP/3 IP 代理)",
    opt_tunnel_xhttp: "XHTTP (流式多路复用 HTTP)",
    opt_tunnel_xhttpc: "XHTTPC (XHTTP Cleartext)",
    opt_tunnel_dns: "DNS Tunnel (SSH-over-DNS)",
    opt_tunnel_kcp: "KCP (SSH-over-KCP 抗丢包)",
    opt_tunnel_udpc: "UDP Custom (自定义混淆 UDP 隧道)",
    opt_alpn_h3_h2: "h3,h2 (H3 优先 / 自动回退)",
    opt_alpn_h2_h3: "h2,h3 (H2 优先 / 自动回退)",
    opt_alpn_h3: "h3 (仅 HTTP/3)",
    opt_alpn_h2: "h2 (仅 HTTP/2)",
    opt_dns_txt: "TXT (推荐)",
    details_modal_title: "ℹ️ 详细信息",
    btn_details_copy: "📋 复制详情",
    btn_details_apply: "✓ 应用指纹",
    btn_details_close: "关闭",
    label_edit_noise_public_key: "Noise 服务端公钥 (可选，Curve25519 Hex/Base64)：",

    label_edit_verify_ssh_fp: '🔑 校验 SSH 主机公钥指纹 (Verify Host Key)',
    error_invalid_address: "地址格式无效 (host:port 或 host:端口范围)",
    error_invalid_address_single_port: "地址格式无效 (host:port)。该协议不支持端口范围。",
    hint_proxy_range: "UDP Custom 可填写端口范围（如 1.1.1.1:1024-23000），每个包发往不同端口，规避单端口限速。",
    error_udp_psk_required: "UDP Custom 必须填写 PSK",
    error_udp_magic: "Magic 必须恰好为 4 个 UTF-8 字节，例如 UDPC",
    error_noise_public_key: "公钥必须是 64 位十六进制或 32 字节 Base64",
    error_dns_noise_record_type: "Noise 加密不能使用 A/AAAA 记录类型",
    error_xhttp_chunk_size: "分块大小只能为 0，或 16 到 900 KB",
    error_invalid_host_only: "请填写对端 IP（ICMP 无端口，格式如 1.1.1.1 或 IPv6）",
    error_invalid_dns_servers: "请填写至少一个 DNS 服务器（逗号或换行分隔，可用 udp/tcp/tls/dot/https:// 前缀）",
    error_invalid_path: "路径必须以 / 开头",
    error_invalid_number: "请输入合法的整数",
    error_invalid_mtu_probe: "MTU 探测只能留空、auto、on/true/1 或 off/false/0",
    error_invalid_masque_alpn: "Masque ALPN 只能是 auto、h3 或 h2",
    warn_psk_short: "PSK 少于 16 字符，安全性偏弱",
    warn_xhttp_no_fingerprint: "xHTTP 未校验证书指纹，存在中间人风险",
    label_edit_padding_min_bytes: "最小填充字节数 (0 = 默认 1420，负数关闭)：",
    label_edit_masque_alpn: "Masque ALPN（仅 masque）：",
    error_heartbeat_interval: "心跳间隔只能为 0，或 5000 到 300000 ms",
    label_edit_ssh_fp: 'SSH 公钥指纹 (SHA256 / MD5)：',
    btn_fetch_fp: '🔍 获取指纹',
    btn_details: 'ℹ️ 详情',
    ssh_details_title: 'ℹ️ SSH 服务器主机公钥详情',
    tls_details_title: 'ℹ️ TLS 服务器证书详细信息',
    toast_ssh_fp_success: '✓ 成功获取 SSH 主机指纹！',
    toast_cert_fp_success: '✓ 成功获取 TLS 证书指纹！',
    toast_missing_ssh_addr: '✕ 请先填写 SSH 服务器地址',
    toast_missing_proxy_addr: '✕ 请先填写代理目标地址或 SSH 地址',
    toast_fetch_failed: '✕ 获取信息失败: {msg}',
    toast_details_copied: '✓ 详细信息已复制到剪贴板！',
    toast_fp_applied: '✓ 已应用指纹到配置并开启校验！',

    page_title: '🦊 Stun · Web 控制台',
    theme_toggle_title: '切换亮暗主题',
    toast_vpn_connecting: '正在连接 VPN...',
    toast_vpn_disconnected: '已断开 VPN 连接',
    toast_switched_node: '✓ 已切换至节点 "{name}"',
    toast_deleted_node: '✓ 已删除节点 "{name}"',
    toast_enter_pin: '✕ 请输入 PIN 码',
    toast_export_failed: '✕ 导出失败，请重试',
    toast_logs_cleared: '✓ 已清空控制台日志',
    toast_profile_updated: '✓ 节点 "{name}" 配置已保存！',
    toast_profile_update_failed: '✕ 保存节点配置失败，请重试',
    file_loaded_toast: '✓ 已成功载入文件 "{name}"',
    btn_test_latency_all: '⚡ 测速全部',
    btn_ping: '⚡ 测速',
    toast_latency_tested: '✓ 节点 "{name}" 延迟测试: {delay}',
    toast_all_latency_tested: '✓ 已完成全部节点延迟测试！',
    toast_latency_failed: '✕ 测速失败，请检查网络',
    tab_overview: '📊 概览看板',
    tab_profiles: '🚀 节点管理',
    tab_conntrack: '🔍 连接跟踪',
    tab_settings: '⚙️ 系统设置',
    tab_mcp: '🤖 MCP 智能体',
    tab_logs: '📜 实时日志',
    stat_selected: '🎯 当前选中节点',
    stat_speed: '⚡ 实时速率 (↑ / ↓)',
    stat_total: 'Σ 累计传输总量',
    stat_filter: '🔀 应用分流状态',
    server_banner_title: '🖥️ 服务端 Banner',
    server_banner_version_label: 'SSH 服务器标识',
    stat_active_conns: '🔌 当前活跃连接数',
    stat_total_conns: '📊 累计建立连接总数',
    stat_route_hit_rate: '🎯 路由缓存命中率',
    conntrack_title: '🔍 实时活跃连接跟踪',
    domain_ranking_title: '🌐 域名实时流量榜 (Top Domains)',
    search_conns_placeholder: '搜索连接目标、域名或端口...',
    no_active_conns: '当前暂无活跃的网络连接',
    no_domain_activity: '暂无活跃域名流量数据',
    pagination_info: '第 {page} / {totalPages} 页 (共 {total} 条连接)',
    btn_page_first: '⏮ 首页',
    btn_page_prev: '◀ 上一页',
    btn_page_next: '下一页 ▶',
    btn_page_last: '末页 ⏭',
    btn_refresh: '🔄 刷新',
    th_target: '目标地址 (Target)',
    th_proxy: '出站路由 (Outbound)',
    th_traffic: '传输流量 (↑ / ↓)',
    th_duration: '持续时间',
    stat_unselected: '未选择节点',
    stat_not_configured: '未配置',
    quick_profiles: '🚀 快捷节点列表',
    btn_manage_all: '管理全部',
    btn_export_backup: '📦 导出加密备份',
    btn_add_profile: '➕ 导入 / 添加节点',
    profiles_title: '🚀 已保存节点',
    no_profiles: '暂无节点，点击上方“导入 / 添加节点”导入',
    btn_select_this: '选用此节点',
    badge_selected: '✓ 当前选中',
    btn_edit_profile: '✏️ 编辑',
    btn_delete: '删除',
    confirm_delete: '确定要删除节点 "{name}" 吗？',
    modal_add_title: '➕ 导入 / 添加节点',
    file_drop_title: '点击选择或拖放配置文件到此处导入',
    file_drop_desc: '支持 .json, .txt, .bak 或加密备份文件',
    or_paste_text: '或者直接粘贴文本',
    import_placeholder: '粘贴手机端加密分享码、备份文本或JSON节点配置...',
    import_pin_label: '🔒 解密 PIN 码：',
    import_pin_placeholder: '若为加密分享码/备份，请输入 PIN（明文配置可留空）',
    pin_detected_badge: '✓ 检测到加密数据',
    btn_cancel: '取消',
    btn_import_now: '导入',
    import_success: '✓ 成功导入 {count} 个节点！',
    import_failed: '✕ 导入失败，请检查内容或 PIN 码',
    import_empty_error: '✕ 请粘贴或载入导入内容',
    modal_export_title: '📦 导出节点加密备份',
    export_pin_label: '设定加密保护 PIN（字母+数字，至少 4 位）：',
    export_pin_placeholder: '输入 PIN（字母+数字，至少 4 位）',
    btn_gen_pin: '🎲 随机生成',
    export_result_label: '加密备份数据（Base64）：',
    btn_export_cancel: '关闭',
    btn_export_exec: '立即生成加密备份',
    btn_export_copy: '📋 复制备份',
    export_success: '✓ 已成功生成加密备份！PIN 码为：{pin}',
    export_copied: '✓ 加密备份内容已复制到剪贴板！',
    modal_edit_title: '✏️ 编辑节点配置',
    label_edit_name: '节点名称：',
    label_edit_note: "备注：",
    label_edit_favorite: "⭐ 收藏此节点",
    edit_note_placeholder: "可选，仅本机显示",
    badge_from_subscription: "📡 订阅",
    filter_tab_all: "全部 ({count})",
    filter_tab_favorites: "收藏 ({count})",
    filter_tab_recent: "最近 ({count})",
    filter_empty: "当前筛选下没有节点",
    fav_add: "收藏",
    fav_remove: "取消收藏",
    label_edit_ssh_addr: 'SSH 服务器地址：',
    label_edit_auth_type: 'SSH 认证方式：',
    opt_auth_password: '密码认证 (Password)',
    opt_auth_key: '私钥认证 (Private Key)',
    label_edit_user: 'SSH 用户名：',
    label_edit_pass: 'SSH 密码：',
    label_edit_key_pass: '私钥口令 (Key Passphrase)：',
    label_edit_private_key: '私钥内容 (OpenSSH / RSA / Ed25519)：',
    label_edit_tunnel_type: '传输协议 (Tunnel Type)：',
    label_edit_proxy_addr: '代理目标地址 (Proxy Addr)：',
    label_edit_proxy_icmp: 'ICMP 对端 IP（无端口）：',
    label_edit_custom_host: '伪装域名 (Custom Host)：',
    label_edit_server_name: 'SNI 域名 (Server Name)：',
    label_edit_custom_path: '自定义路径 (Custom Path)：',
    label_edit_alpn: 'ALPN 协商 (ALPN)：',
    label_edit_http_payload: 'HTTP Payload (自定义请求头模版)：',
    label_edit_disable_status_check: '禁用 HTTP 200 响应状态码严格检查',
    label_edit_verify_cert: '🔒 校验服务器证书 SHA-256 指纹',
    label_edit_cert_fp: '证书 SHA-256 指纹 (Hex)：',
    label_edit_proxy_auth: '🔑 启用上游代理身份验证 (Proxy Auth)',
    label_edit_auth_token: 'Proxy Auth Token：',
    label_edit_auth_user: 'Proxy 用户名：',
    label_edit_auth_pass: 'Proxy 密码：',
    label_edit_dns_servers: 'DNS 服务器 (多个逗号分隔)：',
    label_edit_dns_domain: 'DNS 域名 (Tunnel Domain)：',
    label_edit_dns_type: 'DNS 记录类型 (Record Type)：',
    label_edit_dns_psk: 'DNS 隧道 PSK：',
    label_edit_dns_marker: '隧道标记 (Marker)：',
    error_field_required: '此项不能为空',
    error_invalid_server_name: 'SNI 不能包含空格',
    error_invalid_fingerprint: '无效的 SHA-256 指纹（64 位十六进制，可含冒号）',
    error_invalid_marker: '标记不能包含空格（最长 32 字符）',
    error_icmp_psk: 'ICMP PSK 必填（或填写 SSH 密码作为回退）',
    error_icmp_magic: '魔数需为 8 位十六进制或 0x 前缀',
    error_kcp_password: 'KCP 密码不能为空',
    label_edit_kcp_pass: 'KCP 密码 (Password)：',
    label_edit_kcp_crypt: 'KCP 加密方式：',
    label_edit_kcp_data_shards: 'Data Shards (数据分片)：',
    label_edit_kcp_parity_shards: 'Parity Shards (校验分片)：',
    label_edit_kcp_nodelay: '启用 KCP NoDelay 极速低延迟模式',
    label_kcp_mode: 'KCP 模式 (fast = 默认)：',
    label_kcp_sndwnd: '发送窗口 (0 = 默认 128)：',
    label_kcp_rcvwnd: '接收窗口 (0 = 默认 512)：',
    label_kcp_mtu: 'MTU (0 = 默认 1350)：',
    label_kcp_nocomp: 'Snappy 压缩：',
    label_kcp_smuxver: 'SMUX 版本 (0 = 默认 2)：',
    label_kcp_keepalive: 'KeepAlive 秒 (0 = 默认 10)：',
    opt_kcp_mode_fast: 'Fast (默认)',
    opt_kcp_mode_normal: 'Normal',
    opt_kcp_mode_fast2: 'Fast2',
    opt_kcp_mode_fast3: 'Fast3',
    opt_kcp_nocomp_off: '启用压缩',
    opt_kcp_nocomp_on: '禁用压缩',
    label_edit_udp_custom_psk: 'UDP Custom PSK 密码：',
    label_edit_udp_custom_magic: 'Magic 识别码（4 个 UTF-8 字节）：',
    label_edit_dns_edns0: 'EDNS0 扩展（1232 字节应答，需与服务端一致）',
    label_edit_udp_custom_sockets: 'UDP 套接字数量 (0 = 默认 1)：',
    label_edit_udp_custom_paths: 'UDP 多路径并发数（随机目标端口，0 = 默认 32）：',
    label_edit_udp_custom_send_window: '发送窗口 (0 = 默认 256)：',
    label_edit_xhttp_chunk: '分块大小 KB (0 = 默认 256，范围 16-900)：',
    label_edit_xhttp_stream_mode: 'XHTTP 流模式：',
    label_edit_bind_interface: '绑定网络接口（如 wlan0，留空 = 默认）：',
    webdav_title: '☁️ WebDAV 云备份',
opt_tunnel_raw: 'RAW (直连 TCP，可开 TLS)',
opt_tunnel_websocket: 'WebSocket (可开 TLS)',
opt_tunnel_webtransport: 'WebTransport',
label_tunnel_tls: '🔒 TLS 加密',
    label_webdav_url: 'WebDAV 地址（备份自动存入其 Stun 目录）：',
    label_webdav_user: '账号：',
    label_webdav_pass: '密码 / 应用密码：',
    label_webdav_pin: '备份 PIN（字母+数字，至少 4 位）：',
    label_webdav_auto: '每日自动备份：',
    label_webdav_last: '上次备份：',
    label_webdav_interval: '自动备份间隔 (小时)：',
    opt_webdav_auto_off: '关闭',
    opt_webdav_auto_on: '开启',
    btn_webdav_backup: '☁️ 立即备份',
    btn_webdav_save: '💾 保存配置',
    btn_webdav_restore: '📥 从云恢复',
    webdav_saved: '✓ WebDAV 配置已保存',
    webdav_backup_ok: '✓ WebDAV 备份完成（{count} 个节点；{sections}）',
    webdav_backup_ok_plain: '✓ WebDAV 备份完成（{count} 个节点）',
    webdav_pin_too_short: '备份 PIN 至少 4 位',
    webdav_restore_ok: '✓ 已从 WebDAV 恢复 {count} 个节点',
    webdav_restore_ok_full: '✓ 已从 WebDAV 恢复 {count} 个节点；{sections}',
    webdav_picker_title: '📥 选择要恢复的备份',
    webdav_no_backups: '服务器上没有找到备份',
    webdav_restore_confirm: '将从云端恢复节点（按 id 合并）与全局设置（不含备份 PIN），继续？',
    opt_tunnel_icmp: 'ICMP Custom (SSH-over-ICMP)',
    label_icmp_psk: 'PSK（预共享密钥）：',
    label_icmp_magic: 'Magic（8位十六进制，留空默认）：',
    ph_icmp_custom_magic: '留空=默认（8 位十六进制 或 0x）',
    label_icmp_family: 'IP 协议族：',
    label_icmp_mtu: 'MTU 模式：',
    label_icmp_max_payload: 'Max Payload (0 = 默认)：',
    label_icmp_pace: '发包间隔 ms (0 = 默认)：',
    label_icmp_id_range: 'Echo ID 池 (如 1000-1999)：',
    opt_icmp_family_auto: '自动',
    opt_icmp_family_v4: 'IPv4',
    opt_icmp_family_v6: 'IPv6',
    opt_icmp_mtu_probe: 'Probe（探测）',
    opt_icmp_mtu_auto: 'Auto',
    opt_icmp_mtu_fixed: 'Fixed（固定）',
    webdav_failed_generic: '✕ WebDAV 操作失败',
    opt_stream_auto: '自动（流式优先，轮询回退）',
    opt_stream_stream: '流式（强制流式下行）',
    opt_stream_poll: '轮询（强制轮询下行）',
    label_edit_heartbeat_interval: '心跳间隔 ms (0 = 默认 25000)：',
    label_edit_dns_override: '🌐 为此节点启用独立 DNS 与直连分流规则',
    label_edit_remote_dns: '节点专用远程 DNS：',
    label_edit_local_dns: '节点专用本地 DNS：',
    label_edit_udpgw_version: '节点专用 UDPGW 引擎：',
    label_edit_udpgw_addr: '节点专用 UDPGW 地址：',
    label_edit_geosite_direct: 'GeoSite 直连标签：',
    label_edit_geoip_direct: 'GeoIP 直连标签：',
    label_edit_app_override: '🔀 为此节点启用独立应用分流规则',
    node_mode_disallow: '🚫 <b>黑名单模式</b> (仅绕过勾选的应用)',
    node_mode_allow: '🚀 <b>白名单模式</b> (仅代理勾选的应用)',
    btn_save_node_edit: '💾 保存节点配置',
    filter_title: '🔀 全局已安装应用分流',
    btn_save_filter: '💾 仅保存应用分流',
    mode_disallow: '🚫 <b>黑名单模式</b> (仅绕过勾选的应用)',
    mode_allow: '🚀 <b>白名单模式</b> (仅代理勾选的应用)',
    search_placeholder: '搜索应用名称或包名...',
    btn_select_all: '全选 / 全不选',
    loading_apps: '加载已安装应用...',
    no_apps: '未找到已安装应用',
    filter_save_success: '✓ 应用分流设置已保存！',
    filter_save_failed: '✕ 保存失败，请重试',
    filter_status_allow: '白名单代理 ({count} 个)',
    filter_status_disallow: '黑名单绕过 ({count} 个)',
    btn_save_all_settings: '💾 保存全部设置',
    settings_core_title: '🚀 工作模式与网络核心',
    label_service_mode: '工作模式 (Service Mode)：',
    opt_mode_vpn: 'VPN 模式 (标准虚拟网卡)',
    opt_mode_tproxy: '透明代理模式 (TProxy 模式)',
    label_log_level: '日志等级 (Log Level)：',
    opt_log_debug: 'DEBUG (详细调试)',
    opt_log_info: 'INFO (常规运行 - 推荐)',
    opt_log_warn: 'WARN (警告提示)',
    opt_log_error: 'ERROR (仅错误)',
    label_remote_dns: '远程 DNS (Remote DNS)：',
    label_local_dns: '本地直连 DNS (Local DNS)：',
    settings_udpgw_title: '📡 UDP 网关设置 (UDPGW)',
    label_udpgw_version: 'UDP 网关引擎：',
    opt_udpgw_tun2proxy: 'tun2proxy (高性能 Rust 模块 - 默认)',
    opt_udpgw_badvpn: 'badvpn (传统兼容模式)',
    label_udpgw_addr: 'UDP 网关地址：',
    settings_geodata_title: '🌐 地理数据与直连分流 (GeoData & Direct Routing)',
    btn_update_geodata: '🔄 立即更新 Geo 规则库',
    label_geosite_direct: 'GeoSite 直连域名标签：',
    label_geoip_direct: 'GeoIP 直连 IP 标签：',
    label_geosite_url: 'GeoSite 规则库下载地址：',
    label_geoip_url: 'GeoIP 规则库下载地址：',
    label_update_interval: '规则库自动更新周期 (秒)：',
    label_last_update: '上次更新时间：',
    never_updated: '从未更新',
    geodata_updating: '正在更新 Geo 规则库...',
    geodata_update_success: '✓ Geo 规则库更新成功！',
    geodata_update_failed: '✕ Geo 规则库更新失败，请检查网络',
    settings_system_title: '🔔 系统与状态通知',
    label_show_speed: '在系统通知栏中显示实时网速',
    settings_title: '🛡️ Web 控制台访问安全与 Token 认证',
    auth_mode_0_title: '<b>🎲 每次重启随机生成 (Random on Start)</b>',
    auth_mode_0_desc: '最高安全性。每次重启都会生成全新的 8 位随机 Token。',
    auth_mode_1_title: '<b>🔒 固定生成一次 / 永久保持 (Fixed Once / Permanent)</b>',
    auth_mode_1_desc: '生成后永久固定保存，适合加入手机/PC浏览器书签或桌面快捷方式，无需频繁扫码。',
    auth_mode_2_title: '<b>✏️ 自定义 Token 访问密码 (Custom Token)</b>',
    auth_mode_2_desc: '自行设定个性化访问口令（例如：foxvpn, 888888），方便记忆与多设备直接访问。',
    auth_mode_3_title: '<b>🌐 关闭认证 (免 Token 局域网直连)</b>',
    auth_mode_3_desc: '同 Wi-Fi 局域网内设备可直接输入 IP 与端口打开控制台，无需携带 ?token= 参数。',
    custom_token_label: '自定义访问密码 (Custom Token)：',
    custom_token_placeholder: '输入自定义 Token 口令，如 123456 或 mysecret',
    current_url_label: '🔗 当前生效访问完整地址 (Live Access URL)',
    btn_copy_url: '📋 复制链接',
    url_copied: '✓ 完整访问链接已复制到剪贴板！',
    settings_save_success: '✓ 全部设置已保存！',
    settings_save_failed: '✕ 保存设置失败，请重试',
    btn_autoscroll_on: '⬇ 自动滚动: 开',
    btn_autoscroll_off: '⬇ 自动滚动: 关',
    btn_copy_logs: '📋 复制日志',
    btn_clear_logs: '🗑 清屏',
    logs_copied: '✓ 日志已复制到剪贴板！',
    vpn_connected: '✓ 已连接 (点击断开)',
    vpn_disconnected: '未连接 (点击连接)',
    vpn_connecting: '⚡ 连接中...',
    lines_unit: ' 行',
    toast_read_file_failed: '✕ 读取文件失败',
    badge_encrypted_detected: '🔒 检测到加密数据 (需输入PIN)',
    badge_plain_detected: '✓ 已载入明文配置 ({count} 个节点)',
    err_empty_content: '✕ 导入内容不能为空',
    err_pin_required: '✕ 检测到加密分享码/备份，请输入 PIN',
    err_invalid_pin: '✕ PIN 码错误或解密失败',
    err_invalid_format: '✕ 未能识别有效的节点配置格式',

    // MCP Settings & Dashboard
    settings_mcp_title: '🤖 Model Context Protocol (MCP) 智能体服务设置',
    label_mcp_enable: '启用 MCP 智能体服务 (Enable MCP Server)',
    desc_mcp_enable: '允许 Claude Desktop、Cursor、Gemini 等 AI 智能体通过标准 MCP 协议或 Gemini Function Calling 远程控制本设备',
    label_mcp_port: 'MCP 统一服务端口 (HTTP/HTTPS 共用)：',
    label_mcp_auth_mode: 'MCP 访问安全认证模式：',
    opt_mcp_auth_none: '🟢 免认证 (局域网免密直连)',
    opt_mcp_auth_apikey: '🔑 API Key (Bearer Token 密钥)',
    opt_mcp_auth_basic: '👤 HTTP Basic (账号密码验证)',
    opt_mcp_auth_oauth: '🛡️ OAuth 2.0 (Token / Code 授权)',
    label_mcp_secret: 'MCP 访问密钥 / 密码 (Secret / Key)：',
    mcp_secret_placeholder: '留空则免密或使用默认密钥',
    mcp_stat_status: '📡 MCP 服务状态',
    mcp_stat_port: '🔌 统一监听端口',
    mcp_stat_proto: '⚡ 通信协议与模式',
    mcp_stat_tools: '🛠️ 可用 AI 工具总数',
    mcp_dash_title: '🚀 独立 Stun MCP & Gemini 交互控制台',
    mcp_dash_desc: '包含 SSL CA 根证书下载、OAuth 2.0 授权、实时 SSE 监控以及多语言支持',
    btn_open_mcp_dash: '🚀 打开 MCP 控制台',
    mcp_claude_title: '💻 Claude Desktop / Cursor / Windsurf 接入配置',
    mcp_claude_desc: '将以下内容直接粘贴至您的 <code>claude_desktop_config.json</code> 中：',
    btn_copy_mcp_config: '📋 复制配置',
    mcp_gemini_title: '🤖 Google Gemini 2.0 Flash / 1.5 Pro 直连 (Python)',
    mcp_gemini_desc: '利用原生 Function Calling 接口，3 行 Python 直接控制 Stun：',
    btn_copy_gemini_snippet: '📋 复制代码',
    mcp_tools_title: '🛠️ 已注册的 18 项 AI 核心控制能力',
    mcp_gemini_c1: '# 1. 动态拉取 Stun MCP 声明的 18 个工具',
    mcp_gemini_c2: '# 2. 初始化 Gemini 2.0 Flash / Pro 自动工具调用',
    mcp_gemini_c3: '# 3. 发送指令由 AI 自动调用 Stun 控制功能',
    mcp_gemini_prompt: '检查 Stun VPN 状态，并测试所有节点延迟',
    toast_mcp_config_copied: '✓ MCP JSON 配置已复制到剪贴板！',
    toast_gemini_snippet_copied: '✓ Gemini Python 代码片段已复制！',
    subscription_title: '📡 订阅管理',
    subscription_url_placeholder: 'https://example.com/sub 或订阅链接',
    btn_sub_sync: '🔄 立即同步',
    label_sub_last_sync: '上次同步：',
    sub_never_synced: '从未同步',
    subscription_syncing: '同步中...',
    subscription_sync_success: '✓ 订阅同步成功，导入 {count} 个节点',
    subscription_sync_error: '✕ 订阅同步失败：{msg}',
    subscription_url_empty: '✕ 请输入订阅链接',
    subscription_pin_hint: 'PIN（可选，加密订阅用）',
    subscription_pin_short: 'PIN',
    subscription_pin_required: '✕ 该订阅为 PIN 加密内容，请输入 PIN',
    subscription_pin_invalid: '✕ PIN 错误或解密失败',
    subscription_add: '➕ 添加订阅',
    subscription_sync_partial: '✓ 已同步 {ok}/{total} 个订阅，导入 {count} 个节点',

  },
  'zh-TW': {
    edit_sec_1_title: "🖥️ 基礎連線與 SSH 認證",
    edit_sec_2_title: "🚀 傳輸協議與專屬配置",
    edit_sec_3_title: "🛡️ 安全加密與指紋校驗",
    edit_sec_4_title: "🌐 節點獨立分流與規則覆蓋",
    edit_name_placeholder: "例如：東京高速節點",
    edit_ssh_addr_placeholder: "IP:Port 或 網域名稱:連接埠",
    edit_user_placeholder: "例如：root",
    edit_pass_placeholder: "留空則保持原密碼不變",
    edit_key_pass_placeholder: "無口令可留空",
    edit_proxy_addr_placeholder: "IP:Port 或 網域名稱:連接埠",
    edit_custom_host_placeholder: "例如：cloudflare.com",
    edit_server_name_placeholder: "TLS 交握 SNI 網域名稱",
    edit_custom_path_placeholder: "/path/to/stream",
    edit_udp_psk_placeholder: "預先共用金鑰",
    edit_noise_pk_placeholder: "Base64 或 Hex 格式公鑰",
    edit_ssh_fp_placeholder: "SHA256:... 或 MD5:...",
    edit_cert_fp_placeholder: "AA:BB:CC:DD...",
    edit_auth_token_placeholder: "例如：Token_Secret_888",
    btn_fetch_ssh_fp_title: "一鍵查詢伺服器 SSH 主機公鑰指紋並填入",
    btn_details_ssh_title: "查詢 SSH 主機密鑰詳情",
    btn_fetch_cert_fp_title: "一鍵查詢伺服器 TLS 憑證指紋並填入",
    btn_details_cert_title: "查詢 TLS 憑證詳細資訊",

    info_target_address: "目標位址",
    info_server_banner: "伺服端 Banner",
    info_public_key_type: "公鑰演算法類型",
    info_handshake_latency: "交握耗時",
    info_sha256_fingerprint: "SHA-256 指紋",
    info_md5_fingerprint: "MD5 指紋",
    info_sha1_fingerprint: "SHA-1 指紋",
    info_sni: "SNI 網域名稱",
    info_subject: "主體 (Subject)",
    info_issuer: "發行者 (Issuer)",
    info_sans: "主體備用名稱 (SANs)",
    info_cert_validity: "憑證有效期限",
    info_cert_expired: "❌ 已過期",
    info_days_remaining: "✓ 剩餘 {days} 天",
    info_signature_alg: "簽章演算法",
    info_public_key_alg: "公鑰演算法",
    info_tls_version: "TLS 版本",
    info_alpn_negotiation: "ALPN 協商",
    info_duration_ms: "耗時 {ms} ms",

    opt_tunnel_tls: "TLS (標準 TLS 加密隧道)",
    opt_tunnel_ws: "WS (WebSocket 明文)",
    opt_tunnel_wss: "WSS (WebSocket over TLS)",
    opt_tunnel_h2: "H2 (HTTP/2 over TLS)",
    opt_tunnel_h2c: "H2C (HTTP/2 Cleartext)",
    opt_tunnel_http: "HTTP (HTTP 代理隧道)",
    opt_tunnel_base: "BASE (直連 TCP 純淨隧道)",
    opt_tunnel_quic: "QUIC (QUIC 快速數據報)",
    opt_tunnel_grpc: "gRPC (gRPC over TLS)",
    opt_tunnel_grpcc: "gRPCC (gRPC Cleartext)",
    opt_tunnel_h3: "H3 (HTTP/3 over QUIC)",
    opt_tunnel_wt: "WebTransport",
    opt_tunnel_masque: "MASQUE (HTTP/3 IP 代理)",
    opt_tunnel_xhttp: "XHTTP (流式多路複用 HTTP)",
    opt_tunnel_xhttpc: "XHTTPC (XHTTP Cleartext)",
    opt_tunnel_dns: "DNS Tunnel (SSH-over-DNS)",
    opt_tunnel_kcp: "KCP (SSH-over-KCP 抗丟包)",
    opt_tunnel_udpc: "UDP Custom (自定義混淆 UDP 隧道)",
    opt_alpn_h3_h2: "h3,h2 (H3 優先 / 自動回退)",
    opt_alpn_h2_h3: "h2,h3 (H2 優先 / 自動回退)",
    opt_alpn_h3: "h3 (僅 HTTP/3)",
    opt_alpn_h2: "h2 (僅 HTTP/2)",
    opt_dns_txt: "TXT (推薦)",
    details_modal_title: "ℹ️ 詳細資訊",
    btn_details_copy: "📋 複製詳情",
    btn_details_apply: "✓ 應用指紋",
    btn_details_close: "關閉",
    label_edit_noise_public_key: "Noise 伺服器公鑰 (可選，Curve25519 Hex/Base64)：",

    label_edit_verify_ssh_fp: '🔑 校驗 SSH 主機公鑰指紋 (Verify Host Key)',
    error_invalid_address: "位址格式無效 (host:port 或 host:連接埠範圍)",
    error_invalid_address_single_port: "位址格式無效 (host:port)。該協定不支援連接埠範圍。",
    hint_proxy_range: "UDP Custom 可填寫連接埠範圍（如 1.1.1.1:1024-23000），每個封包送往不同連接埠，規避單連接埠限速。",
    error_udp_psk_required: "UDP Custom 必須填寫 PSK",
    error_udp_magic: "Magic 必須恰好為 4 個 UTF-8 位元組，例如 UDPC",
    error_noise_public_key: "公鑰必須是 64 位十六進位或 32 位元組 Base64",
    error_dns_noise_record_type: "Noise 加密不能使用 A/AAAA 記錄類型",
    error_xhttp_chunk_size: "分塊大小只能為 0，或 16 到 900 KB",
    error_invalid_host_only: "請填寫對端 IP（ICMP 無連接埠，格式如 1.1.1.1 或 IPv6）",
    error_invalid_dns_servers: "請填寫至少一個 DNS 伺服器（逗號或換行分隔，可用 udp/tcp/tls/dot/https:// 前綴）",
    error_invalid_path: "路徑必須以 / 開頭",
    error_invalid_number: "請輸入合法的整數",
    error_invalid_mtu_probe: "MTU 探測只能留空、auto、on/true/1 或 off/false/0",
    error_invalid_masque_alpn: "Masque ALPN 只能是 auto、h3 或 h2",
    warn_psk_short: "PSK 少於 16 字元，安全性偏弱",
    warn_xhttp_no_fingerprint: "xHTTP 未校驗憑證指紋，存在中介人風險",
    label_edit_padding_min_bytes: "最小填充位元組數 (0 = 預設 1420，負數關閉)：",
    label_edit_masque_alpn: "Masque ALPN（僅 masque）：",
    error_heartbeat_interval: "心跳間隔只能為 0，或 5000 到 300000 ms",
    label_edit_ssh_fp: 'SSH 公鑰指紋 (SHA256 / MD5)：',
    btn_fetch_fp: '🔍 獲取指紋',
    btn_details: 'ℹ️ 詳情',
    ssh_details_title: 'ℹ️ SSH 伺服器主機公鑰詳情',
    tls_details_title: 'ℹ️ TLS 伺服器證書詳細資訊',
    toast_ssh_fp_success: '✓ 成功獲取 SSH 主機指紋！',
    toast_cert_fp_success: '✓ 成功獲取 TLS 證書指紋！',
    toast_missing_ssh_addr: '✕ 請先填寫 SSH 伺服器位址',
    toast_missing_proxy_addr: '✕ 請先填寫代理目標位址或 SSH 位址',
    toast_fetch_failed: '✕ 獲取資訊失敗: {msg}',
    toast_details_copied: '✓ 詳細資訊已複製到剪貼簿！',
    toast_fp_applied: '✓ 已應用指紋到配置並開啟校驗！',

    page_title: '🦊 Stun · Web 控制台',
    theme_toggle_title: '切換亮暗主題',
    toast_vpn_connecting: '正在連線 VPN...',
    toast_vpn_disconnected: '已中斷 VPN 連線',
    toast_switched_node: '✓ 已切換至節點 "{name}"',
    toast_deleted_node: '✓ 已刪除節點 "{name}"',
    toast_enter_pin: '✕ 請輸入 PIN 碼',
    toast_export_failed: '✕ 匯出失敗，請重試',
    toast_logs_cleared: '✓ 已清除控制台日誌',
    toast_profile_updated: '✓ 節點 "{name}" 設定已儲存！',
    toast_profile_update_failed: '✕ 儲存節點設定失敗，請重試',
    file_loaded_toast: '✓ 已成功載入檔案 "{name}"',
    btn_test_latency_all: '⚡ 測速全部',
    btn_ping: '⚡ 測速',
    toast_latency_tested: '✓ 節點 "{name}" 延遲測試: {delay}',
    toast_all_latency_tested: '✓ 已完成全部節點延遲測試！',
    toast_latency_failed: '✕ 測速失敗，請檢查網路',
    tab_overview: '📊 概覽看板',
    tab_profiles: '🚀 節點管理',
    tab_conntrack: '🔍 連線追蹤',
    tab_settings: '⚙️ 系統設定',
    tab_mcp: '🤖 MCP 智慧體',
    tab_logs: '📜 即時日誌',
    stat_selected: '🎯 當前選中節點',
    stat_speed: '⚡ 即時速率 (↑ / ↓)',
    stat_total: 'Σ 累計傳輸總量',
    stat_filter: '🔀 應用分流狀態',
    server_banner_title: '🖥️ 伺服端 Banner',
    server_banner_version_label: 'SSH 伺服器識別',
    stat_active_conns: '🔌 當前活躍連線數',
    stat_total_conns: '📊 累計建立連線總數',
    stat_route_hit_rate: '🎯 路由快取命中率',
    conntrack_title: '🔍 即時活躍連線追蹤',
    domain_ranking_title: '🌐 網域名稱即時流量榜 (Top Domains)',
    search_conns_placeholder: '搜尋連線目標、網域名稱或連接埠...',
    no_active_conns: '目前暫無活躍的網路連線',
    no_domain_activity: '暫無活躍網域名稱流量資料',
    pagination_info: '第 {page} / {totalPages} 頁 (共 {total} 條連線)',
    btn_page_first: '⏮ 首頁',
    btn_page_prev: '◀ 上一頁',
    btn_page_next: '下一頁 ▶',
    btn_page_last: '末頁 ⏭',
    btn_refresh: '🔄 重新整理',
    th_target: '目標位址 (Target)',
    th_proxy: '出站路由 (Outbound)',
    th_traffic: '傳輸流量 (↑ / ↓)',
    th_duration: '持續時間',
    stat_unselected: '未選擇節點',
    stat_not_configured: '未配置',
    quick_profiles: '🚀 快捷節點列表',
    btn_manage_all: '管理全部',
    btn_export_backup: '📦 匯出加密備份',
    btn_add_profile: '➕ 匯入 / 新增節點',
    profiles_title: '🚀 已儲存節點',
    no_profiles: '暫無節點，點擊上方“匯入 / 新增節點”匯入',
    btn_select_this: '選用此節點',
    badge_selected: '✓ 當前選中',
    btn_edit_profile: '✏️ 編輯',
    btn_delete: '刪除',
    confirm_delete: '確定要刪除節點 "{name}" 嗎？',
    modal_add_title: '➕ 匯入 / 新增節點',
    file_drop_title: '點擊選擇或拖放設定檔至此處匯入',
    file_drop_desc: '支援 .json, .txt, .bak 或加密備份檔案',
    or_paste_text: '或者直接貼上文字',
    import_placeholder: '貼上手機端加密分享碼、備份文字或JSON節點設定...',
    import_pin_label: '🔒 解密 PIN 碼：',
    import_pin_placeholder: '若為加密分享碼/備份，請輸入 PIN（明文設定可留空）',
    pin_detected_badge: '✓ 偵測到加密資料',
    btn_cancel: '取消',
    btn_import_now: '匯入',
    import_success: '✓ 成功匯入 {count} 個節點！',
    import_failed: '✕ 匯入失敗，請檢查內容或 PIN 碼',
    import_empty_error: '✕ 請貼上或載入匯入內容',
    modal_export_title: '📦 匯出節點加密備份',
    export_pin_label: '設定加密保護 PIN（字母+數字，至少 4 位）：',
    export_pin_placeholder: '輸入 PIN（字母+數字，至少 4 位）',
    btn_gen_pin: '🎲 隨機生成',
    export_result_label: '加密備份資料（Base64）：',
    btn_export_cancel: '關閉',
    btn_export_exec: '立即生成加密備份',
    btn_export_copy: '📋 複製備份',
    export_success: '✓ 已成功生成加密備份！PIN 碼為：{pin}',
    export_copied: '✓ 加密備份內容已複製到剪貼簿！',
    modal_edit_title: '✏️ 編輯節點設定',
    label_edit_name: '節點名稱：',
    label_edit_note: "備註：",
    label_edit_favorite: "⭐ 收藏此節點",
    edit_note_placeholder: "可選，僅本機顯示",
    badge_from_subscription: "📡 訂閱",
    filter_tab_all: "全部 ({count})",
    filter_tab_favorites: "收藏 ({count})",
    filter_tab_recent: "最近 ({count})",
    filter_empty: "目前篩選下沒有節點",
    fav_add: "收藏",
    fav_remove: "取消收藏",
    label_edit_ssh_addr: 'SSH 伺服器位址：',
    label_edit_auth_type: 'SSH 認證方式：',
    opt_auth_password: '密碼認證 (Password)',
    opt_auth_key: '私鑰認證 (Private Key)',
    label_edit_user: 'SSH 使用者名稱：',
    label_edit_pass: 'SSH 密碼：',
    label_edit_key_pass: '私鑰口令 (Key Passphrase)：',
    label_edit_private_key: '私鑰內容 (OpenSSH / RSA / Ed25519)：',
    label_edit_tunnel_type: '傳輸協定 (Tunnel Type)：',
    label_edit_proxy_addr: '代理目標位址 (Proxy Addr)：',
    label_edit_proxy_icmp: 'ICMP 對端 IP（無連接埠）：',
    label_edit_custom_host: '偽裝網域名稱 (Custom Host)：',
    label_edit_server_name: 'SNI 網域名稱 (Server Name)：',
    label_edit_custom_path: '自訂路徑 (Custom Path)：',
    label_edit_alpn: 'ALPN 協商 (ALPN)：',
    label_edit_http_payload: 'HTTP Payload (自訂請求標頭範本)：',
    label_edit_disable_status_check: '停用 HTTP 200 回應狀態碼嚴格檢查',
    label_edit_verify_cert: '🔒 驗證伺服器憑證 SHA-256 指紋',
    label_edit_cert_fp: '憑證 SHA-256 指紋 (Hex)：',
    label_edit_proxy_auth: '🔑 啟用上游代理身份驗證 (Proxy Auth)',
    label_edit_auth_token: 'Proxy Auth Token：',
    label_edit_auth_user: 'Proxy 使用者名稱：',
    label_edit_auth_pass: 'Proxy 密碼：',
    label_edit_dns_servers: 'DNS 伺服器 (多個逗號分隔)：',
    label_edit_dns_domain: 'DNS 網域名稱 (Tunnel Domain)：',
    label_edit_dns_type: 'DNS 記錄類型 (Record Type)：',
    label_edit_dns_psk: 'DNS 隧道 PSK：',
    label_edit_dns_marker: '隧道標記 (Marker)：',
    error_field_required: '此項不能為空',
    error_invalid_server_name: 'SNI 不能包含空格',
    error_invalid_fingerprint: '無效的 SHA-256 指紋（64 位十六進位，可含冒號）',
    error_invalid_marker: '標記不能包含空格（最長 32 字元）',
    error_icmp_psk: 'ICMP PSK 必填（或填寫 SSH 密碼作為回退）',
    error_icmp_magic: '魔數需為 8 位十六進位或 0x 前綴',
    error_kcp_password: 'KCP 密碼不能為空',
    label_edit_kcp_pass: 'KCP 密碼 (Password)：',
    label_edit_kcp_crypt: 'KCP 加密方式：',
    label_edit_kcp_data_shards: 'Data Shards (資料分片)：',
    label_edit_kcp_parity_shards: 'Parity Shards (校驗分片)：',
    label_edit_kcp_nodelay: '啟用 KCP NoDelay 極速低延遲模式',
    label_kcp_mode: 'KCP 模式 (fast = 預設)：',
    label_kcp_sndwnd: '傳送視窗 (0 = 預設 128)：',
    label_kcp_rcvwnd: '接收視窗 (0 = 預設 512)：',
    label_kcp_mtu: 'MTU (0 = 預設 1350)：',
    label_kcp_nocomp: 'Snappy 壓縮：',
    label_kcp_smuxver: 'SMUX 版本 (0 = 預設 2)：',
    label_kcp_keepalive: 'KeepAlive 秒 (0 = 預設 10)：',
    opt_kcp_mode_fast: 'Fast (預設)',
    opt_kcp_mode_normal: 'Normal',
    opt_kcp_mode_fast2: 'Fast2',
    opt_kcp_mode_fast3: 'Fast3',
    opt_kcp_nocomp_off: '啟用壓縮',
    opt_kcp_nocomp_on: '停用壓縮',
    label_edit_udp_custom_psk: 'UDP Custom PSK 密碼：',
    label_edit_udp_custom_magic: 'Magic 識別碼（4 個 UTF-8 位元組）：',
    label_edit_dns_edns0: 'EDNS0 擴充（1232 位元組應答，需與服務端一致）',
    label_edit_udp_custom_sockets: 'UDP 插槽數量 (0 = 預設 1)：',
    label_edit_udp_custom_paths: 'UDP 多路徑並行數（隨機目標連接埠，0 = 預設 32）：',
    label_edit_udp_custom_send_window: '發送視窗 (0 = 預設 256)：',
    label_edit_xhttp_chunk: '分塊大小 KB (0 = 預設 256，範圍 16-900)：',
    label_edit_xhttp_stream_mode: 'XHTTP 串流模式：',
    label_edit_bind_interface: '綁定網路介面（如 wlan0，留空 = 預設）：',
    webdav_title: '☁️ WebDAV 雲端備份',
opt_tunnel_raw: 'RAW (直連 TCP，可開 TLS)',
opt_tunnel_websocket: 'WebSocket (可開 TLS)',
opt_tunnel_webtransport: 'WebTransport',
label_tunnel_tls: '🔒 TLS 加密',
    label_webdav_url: 'WebDAV 位址（備份會自動存入其 Stun 目錄）：',
    label_webdav_user: '帳號：',
    label_webdav_pass: '密碼 / 應用程式密碼：',
    label_webdav_pin: '備份 PIN（字母+數字，至少 4 位）：',
    label_webdav_auto: '每日自動備份：',
    label_webdav_last: '上次備份：',
    label_webdav_interval: '自動備份間隔 (小時)：',
    opt_webdav_auto_off: '關閉',
    opt_webdav_auto_on: '開啟',
    btn_webdav_backup: '☁️ 立即備份',
    btn_webdav_save: '💾 儲存設定',
    btn_webdav_restore: '📥 從雲還原',
    webdav_saved: '✓ WebDAV 設定已儲存',
    webdav_backup_ok: '✓ WebDAV 備份完成（{count} 個節點；{sections}）',
    webdav_backup_ok_plain: '✓ WebDAV 備份完成（{count} 個節點）',
    webdav_pin_too_short: '備份 PIN 至少 4 位',
    webdav_restore_ok: '✓ 已從 WebDAV 還原 {count} 個節點',
    webdav_restore_ok_full: '✓ 已從 WebDAV 還原 {count} 個節點；{sections}',
    webdav_picker_title: '📥 選擇要還原的備份',
    webdav_no_backups: '伺服器上找不到備份',
    webdav_restore_confirm: '將從雲端還原節點（依 id 合併）與全域設定（不含備份 PIN），繼續？',
    opt_tunnel_icmp: 'ICMP Custom (SSH-over-ICMP)',
    label_icmp_psk: 'PSK（預共用金鑰）：',
    label_icmp_magic: 'Magic（8位十六進位，留空預設）：',
    ph_icmp_custom_magic: '留空=預設（8 位十六進位 或 0x）',
    label_icmp_family: 'IP 協議族：',
    label_icmp_mtu: 'MTU 模式：',
    label_icmp_max_payload: 'Max Payload (0 = 預設)：',
    label_icmp_pace: '發包間隔 ms (0 = 預設)：',
    label_icmp_id_range: 'Echo ID 池 (如 1000-1999)：',
    opt_icmp_family_auto: '自動',
    opt_icmp_family_v4: 'IPv4',
    opt_icmp_family_v6: 'IPv6',
    opt_icmp_mtu_probe: 'Probe（探測）',
    opt_icmp_mtu_auto: 'Auto',
    opt_icmp_mtu_fixed: 'Fixed（固定）',
    webdav_failed_generic: '✕ WebDAV 操作失敗',
    opt_stream_auto: '自動（串流優先，輪詢回退）',
    opt_stream_stream: '串流（強制串流下行）',
    opt_stream_poll: '輪詢（強制輪詢下行）',
    label_edit_heartbeat_interval: '心跳間隔 ms (0 = 預設 25000)：',
    label_edit_dns_override: '🌐 為此節點啟用獨立 DNS 與直連分流規則',
    label_edit_remote_dns: '節點專用遠端 DNS：',
    label_edit_local_dns: '節點專用本地 DNS：',
    label_edit_udpgw_version: '節點專用 UDPGW 引擎：',
    label_edit_udpgw_addr: '節點專用 UDPGW 位址：',
    label_edit_geosite_direct: 'GeoSite 直連標籤：',
    label_edit_geoip_direct: 'GeoIP 直連標籤：',
    label_edit_app_override: '🔀 為此節點啟用獨立應用分流規則',
    node_mode_disallow: '🚫 <b>黑名單模式</b> (僅繞過勾選的應用)',
    node_mode_allow: '🚀 <b>白名單模式</b> (僅代理勾選的應用)',
    btn_save_node_edit: '💾 儲存節點設定',
    filter_title: '🔀 全局已安裝應用分流',
    btn_save_filter: '💾 僅儲存應用分流',
    mode_disallow: '🚫 <b>黑名單模式</b> (僅繞過勾選的應用)',
    mode_allow: '🚀 <b>白名單模式</b> (僅代理勾選的應用)',
    search_placeholder: '搜尋應用名稱或套件名稱...',
    btn_select_all: '全選 / 全不選',
    loading_apps: '載入已安裝應用...',
    no_apps: '未找到已安裝應用',
    filter_save_success: '✓ 應用分流設定已儲存！',
    filter_save_failed: '✕ 儲存失敗，請重試',
    filter_status_allow: '白名單代理 ({count} 個)',
    filter_status_disallow: '黑名單繞過 ({count} 個)',
    btn_save_all_settings: '💾 儲存全部設定',
    settings_core_title: '🚀 工作模式與網路核心',
    label_service_mode: '工作模式 (Service Mode)：',
    opt_mode_vpn: 'VPN 模式 (標準虛擬網卡)',
    opt_mode_tproxy: '透明代理模式 (TProxy 模式)',
    label_log_level: '日誌等級 (Log Level)：',
    opt_log_debug: 'DEBUG (詳細偵錯)',
    opt_log_info: 'INFO (常規運行 - 推薦)',
    opt_log_warn: 'WARN (警告提示)',
    opt_log_error: 'ERROR (僅錯誤)',
    label_remote_dns: '遠端 DNS (Remote DNS)：',
    label_local_dns: '本地直連 DNS (Local DNS)：',
    settings_udpgw_title: '📡 UDP 閘道設定 (UDPGW)',
    label_udpgw_version: 'UDP 閘道引擎：',
    opt_udpgw_tun2proxy: 'tun2proxy (高性能 Rust 引擎 - 預設)',
    opt_udpgw_badvpn: 'badvpn (傳統相容模式)',
    label_udpgw_addr: 'UDP 閘道位址：',
    settings_geodata_title: '🌐 地理資料與直連分流 (GeoData & Direct Routing)',
    btn_update_geodata: '🔄 立即更新 Geo 規則庫',
    label_geosite_direct: 'GeoSite 直連網域名稱標籤：',
    label_geoip_direct: 'GeoIP 直連 IP 標籤：',
    label_geosite_url: 'GeoSite 規則庫下載網址：',
    label_geoip_url: 'GeoIP 規則庫下載網址：',
    label_update_interval: '規則庫自動更新週期 (秒)：',
    label_last_update: '上次更新時間：',
    never_updated: '從未更新',
    geodata_updating: '正在更新 Geo 規則庫...',
    geodata_update_success: '✓ Geo 規則庫更新成功！',
    geodata_update_failed: '✕ Geo 規則庫更新失敗，請檢查網路',
    settings_system_title: '🔔 系統與狀態通知',
    label_show_speed: '在系統通知欄中顯示即時網速',
    settings_title: '🛡️ Web 控制台安全與 Token 認證',
    auth_mode_0_title: '<b>🎲 每次重啟隨機生成 (Random on Start)</b>',
    auth_mode_0_desc: '最高安全性。每次重啟都會生成全新的 8 位隨機 Token。',
    auth_mode_1_title: '<b>🔒 固定生成一次 / 永久保持 (Fixed Once / Permanent)</b>',
    auth_mode_1_desc: '生成後永久固定儲存，適合加入手機/PC瀏覽器書籤或桌面捷徑，無需頻繁掃碼。',
    auth_mode_2_title: '<b>✏️ 自訂 Token 訪問密碼 (Custom Token)</b>',
    auth_mode_2_desc: '自行設定個性化訪問密碼（例如：foxvpn, 888888），方便記憶與多裝置直接連線。',
    auth_mode_3_title: '<b>🌐 關閉認證 (免 Token 區域網路直連)</b>',
    auth_mode_3_desc: '同 Wi-Fi 區域網路內裝置可直接輸入 IP 與連接埠開啟控制台，無需攜帶 ?token= 參數。',
    custom_token_label: '自訂訪問密碼 (Custom Token)：',
    custom_token_placeholder: '輸入自訂 Token 密碼，如 123456 或 mysecret',
    current_url_label: '🔗 當前生效訪問完整網址 (Live Access URL)',
    btn_copy_url: '📋 複製連結',
    url_copied: '✓ 完整訪問連結已複製到剪貼簿！',
    settings_save_success: '✓ 全部設定已儲存！',
    settings_save_failed: '✕ 儲存設定失敗，請重試',
    btn_autoscroll_on: '⬇ 自動捲動: 開',
    btn_autoscroll_off: '⬇ 自動捲動: 關',
    btn_copy_logs: '📋 複製日誌',
    btn_clear_logs: '🗑 清除',
    logs_copied: '✓ 日誌已複製到剪貼簿！',
    vpn_connected: '✓ 已連線 (點擊中斷)',
    vpn_disconnected: '未連線 (點擊連線)',
    vpn_connecting: '⚡ 連線中...',
    lines_unit: ' 行',
    toast_read_file_failed: '✕ 讀取檔案失敗',
    badge_encrypted_detected: '🔒 檢測到加密數據 (需輸入PIN)',
    badge_plain_detected: '✓ 已載入明文設定 ({count} 個節點)',
    err_empty_content: '✕ 匯入內容不能為空',
    err_pin_required: '✕ 檢測到加密分享碼/備份，請輸入 PIN',
    err_invalid_pin: '✕ PIN 碼錯誤或解密失敗',
    err_invalid_format: '✕ 未能識別有效的節點設定格式',

    // MCP 設定與看板
    settings_mcp_title: '🤖 Model Context Protocol (MCP) 智慧體服務設定',
    label_mcp_enable: '啟用 MCP 智慧體服務 (Enable MCP Server)',
    desc_mcp_enable: '允許 Claude Desktop、Cursor、Gemini 等 AI 智慧體透過標準 MCP 協議或 Gemini Function Calling 遠端控制本裝置',
    label_mcp_port: 'MCP 統一服務連接埠 (HTTP/HTTPS 共用)：',
    label_mcp_auth_mode: 'MCP 存取安全認證模式：',
    opt_mcp_auth_none: '🟢 免認證 (區域網路免密直連)',
    opt_mcp_auth_apikey: '🔑 API Key (Bearer Token 金鑰)',
    opt_mcp_auth_basic: '👤 HTTP Basic (帳號密碼驗證)',
    opt_mcp_auth_oauth: '🛡️ OAuth 2.0 (Token / Code 授權)',
    label_mcp_secret: 'MCP 存取金鑰 / 密碼 (Secret / Key)：',
    mcp_secret_placeholder: '留空則免密或使用預設金鑰',
    mcp_stat_status: '📡 MCP 服務狀態',
    mcp_stat_port: '🔌 統一監聽連接埠',
    mcp_stat_proto: '⚡ 通訊協議與模式',
    mcp_stat_tools: '🛠️ 可用 AI 工具總數',
    mcp_dash_title: '🚀 獨立 Stun MCP & Gemini 互動控制台',
    mcp_dash_desc: '包含 SSL CA 根憑證下載、OAuth 2.0 授權、即時 SSE 監控以及多語言支援',
    btn_open_mcp_dash: '🚀 開啟 MCP 控制台',
    mcp_claude_title: '💻 Claude Desktop / Cursor / Windsurf 接入配置',
    mcp_claude_desc: '將以下內容直接貼至您的 <code>claude_desktop_config.json</code> 中：',
    btn_copy_mcp_config: '📋 複製配置',
    mcp_gemini_title: '🤖 Google Gemini 2.0 Flash / 1.5 Pro 直連 (Python)',
    mcp_gemini_desc: '利用原生 Function Calling 介面，3 行 Python 直接控制 Stun：',
    btn_copy_gemini_snippet: '📋 複製程式碼',
    mcp_tools_title: '🛠️ 已註冊的 18 項 AI 核心控制能力',
    mcp_gemini_c1: '# 1. 動態獲取 Stun MCP 聲明的 18 個工具',
    mcp_gemini_c2: '# 2. 初始化 Gemini 2.0 Flash / Pro 自動工具調用',
    mcp_gemini_c3: '# 3. 發送指令由 AI 自動調用 Stun 控制功能',
    mcp_gemini_prompt: '檢查 Stun VPN 狀態，並測試所有節點延遲',
    toast_mcp_config_copied: '✓ MCP JSON 配置已複製到剪貼簿！',
    toast_gemini_snippet_copied: '✓ Gemini Python 程式碼片段已複製！',
    subscription_title: '📡 訂閱管理',
    subscription_url_placeholder: 'https://example.com/sub 或訂閱連結',
    btn_sub_sync: '🔄 立即同步',
    label_sub_last_sync: '上次同步：',
    sub_never_synced: '從未同步',
    subscription_syncing: '同步中...',
    subscription_sync_success: '✓ 訂閱同步成功，匯入 {count} 個節點',
    subscription_sync_error: '✕ 訂閱同步失敗：{msg}',
    subscription_url_empty: '✕ 請輸入訂閱連結',
    subscription_pin_hint: 'PIN（選填，加密訂閱用）',
    subscription_pin_short: 'PIN',
    subscription_pin_required: '✕ 該訂閱為 PIN 加密內容，請輸入 PIN',
    subscription_pin_invalid: '✕ PIN 錯誤或解密失敗',
    subscription_add: '➕ 新增訂閱',
    subscription_sync_partial: '✓ 已同步 {ok}/{total} 個訂閱，匯入 {count} 個節點',

  },
  'en': {
    edit_sec_1_title: "🖥️ Basic Connection & SSH Auth",
    edit_sec_2_title: "🚀 Transport Protocol & Parameters",
    edit_sec_3_title: "🛡️ Security & Fingerprint Verification",
    edit_sec_4_title: "🌐 Routing & App Filter Overrides",
    edit_name_placeholder: "e.g. Tokyo Fast Node",
    edit_ssh_addr_placeholder: "IP:Port or Domain:Port",
    edit_user_placeholder: "e.g. root",
    edit_pass_placeholder: "Leave empty to keep existing password",
    edit_key_pass_placeholder: "Leave empty if unencrypted",
    edit_proxy_addr_placeholder: "IP:Port or Domain:Port",
    edit_custom_host_placeholder: "e.g. cloudflare.com",
    edit_server_name_placeholder: "TLS Handshake SNI Domain",
    edit_custom_path_placeholder: "/path/to/stream",
    edit_udp_psk_placeholder: "Pre-Shared Key",
    edit_noise_pk_placeholder: "Base64 or Hex public key",
    edit_ssh_fp_placeholder: "SHA256:... or MD5:...",
    edit_cert_fp_placeholder: "AA:BB:CC:DD...",
    edit_auth_token_placeholder: "e.g. Token_Secret_888",
    btn_fetch_ssh_fp_title: "Query SSH host key fingerprint and auto-fill",
    btn_details_ssh_title: "View detailed SSH host key information",
    btn_fetch_cert_fp_title: "Query TLS cert fingerprint and auto-fill",
    btn_details_cert_title: "View detailed TLS certificate information",

    info_target_address: "Target Address",
    info_server_banner: "Server Banner",
    info_public_key_type: "Public Key Type",
    info_handshake_latency: "Handshake Latency",
    info_sha256_fingerprint: "SHA-256 Fingerprint",
    info_md5_fingerprint: "MD5 Fingerprint",
    info_sha1_fingerprint: "SHA-1 Fingerprint",
    info_sni: "SNI Domain",
    info_subject: "Subject",
    info_issuer: "Issuer",
    info_sans: "SANs (Alt Names)",
    info_cert_validity: "Certificate Validity",
    info_cert_expired: "❌ Expired",
    info_days_remaining: "✓ {days} days remaining",
    info_signature_alg: "Signature Algorithm",
    info_public_key_alg: "Public Key Algorithm",
    info_tls_version: "TLS Version",
    info_alpn_negotiation: "ALPN Protocol",
    info_duration_ms: "Duration: {ms} ms",

    opt_tunnel_tls: "TLS (Standard TLS Encrypted Tunnel)",
    opt_tunnel_ws: "WS (WebSocket Plaintext)",
    opt_tunnel_wss: "WSS (WebSocket over TLS)",
    opt_tunnel_h2: "H2 (HTTP/2 over TLS)",
    opt_tunnel_h2c: "H2C (HTTP/2 Cleartext)",
    opt_tunnel_http: "HTTP (HTTP Proxy Tunnel)",
    opt_tunnel_base: "BASE (Direct TCP Pure Tunnel)",
    opt_tunnel_quic: "QUIC (QUIC Datagram Tunnel)",
    opt_tunnel_grpc: "gRPC (gRPC over TLS)",
    opt_tunnel_grpcc: "gRPCC (gRPC Cleartext)",
    opt_tunnel_h3: "H3 (HTTP/3 over QUIC)",
    opt_tunnel_wt: "WebTransport",
    opt_tunnel_masque: "MASQUE (HTTP/3 IP Proxy)",
    opt_tunnel_xhttp: "XHTTP (Streaming Multiplexed HTTP)",
    opt_tunnel_xhttpc: "XHTTPC (XHTTP Cleartext)",
    opt_tunnel_dns: "DNS Tunnel (SSH-over-DNS)",
    opt_tunnel_kcp: "KCP (SSH-over-KCP Anti-Packet Loss)",
    opt_tunnel_udpc: "UDP Custom (Custom Obfuscated UDP Tunnel)",
    opt_alpn_h3_h2: "h3,h2 (H3 Preferred / Auto Fallback)",
    opt_alpn_h2_h3: "h2,h3 (H2 Preferred / Auto Fallback)",
    opt_alpn_h3: "h3 (HTTP/3 Only)",
    opt_alpn_h2: "h2 (HTTP/2 Only)",
    opt_dns_txt: "TXT (Recommended)",
    details_modal_title: "ℹ️ Detailed Information",
    btn_details_copy: "📋 Copy Details",
    btn_details_apply: "✓ Apply Fingerprint",
    btn_details_close: "Close",
    label_edit_noise_public_key: "Noise Server Public Key (Optional, Curve25519 Hex/Base64):",

    label_edit_verify_ssh_fp: '🔑 Verify SSH Host Key Fingerprint',
    error_invalid_address: "Invalid address format (host:port or host:port-range)",
    error_invalid_address_single_port: "Invalid address format (host:port). Port range not supported for this protocol.",
    hint_proxy_range: "UDP Custom supports port ranges (e.g. 1.1.1.1:1024-23000). Each packet is sent to a different port to bypass per-port rate limits.",
    error_udp_psk_required: "UDP Custom PSK is required",
    error_udp_magic: "Magic must be exactly 4 UTF-8 bytes, for example UDPC",
    error_noise_public_key: "Public key must be 64 hex characters or 32-byte Base64",
    error_dns_noise_record_type: "Noise encryption cannot use A or AAAA records",
    error_xhttp_chunk_size: "Chunk size must be 0, or from 16 to 900 KB",
    error_invalid_host_only: "Enter the peer IP (ICMP has no port, e.g. 1.1.1.1 or IPv6)",
    error_invalid_dns_servers: "Enter at least one DNS server (comma or newline separated; udp/tcp/tls/dot/https:// prefixes allowed)",
    error_invalid_path: "Path must start with /",
    error_invalid_number: "Enter a valid integer",
    error_invalid_mtu_probe: "MTU probe must be empty, auto, on/true/1, or off/false/0",
    error_invalid_masque_alpn: "Masque ALPN must be auto, h3 or h2",
    warn_psk_short: "PSK is shorter than 16 characters - weaker security",
    warn_xhttp_no_fingerprint: "xHTTP has no certificate fingerprint pinned - MITM risk",
    label_edit_padding_min_bytes: "Min Padding Bytes (0 = default 1420, negative = off):",
    label_edit_masque_alpn: "Masque ALPN (masque only):",
    error_heartbeat_interval: "Heartbeat must be 0, or from 5000 to 300000 ms",
    label_edit_ssh_fp: 'SSH Host Key Fingerprint (SHA256 / MD5):',
    btn_fetch_fp: '🔍 Fetch Fingerprint',
    btn_details: 'ℹ️ Details',
    ssh_details_title: 'ℹ️ SSH Server Host Key Details',
    tls_details_title: 'ℹ️ TLS Server Certificate Details',
    toast_ssh_fp_success: '✓ SSH host fingerprint fetched successfully!',
    toast_cert_fp_success: '✓ TLS certificate fingerprint fetched successfully!',
    toast_missing_ssh_addr: '✕ Please provide SSH Server Address first',
    toast_missing_proxy_addr: '✕ Please provide Proxy Target Address or SSH Address first',
    toast_fetch_failed: '✕ Failed to fetch info: {msg}',
    toast_details_copied: '✓ Details copied to clipboard!',
    toast_fp_applied: '✓ Fingerprint applied and verification enabled!',

    page_title: '🦊 Stun · Web Console',
    theme_toggle_title: 'Toggle Theme',
    toast_vpn_connecting: 'Connecting to VPN...',
    toast_vpn_disconnected: 'VPN disconnected',
    toast_switched_node: '✓ Switched to node "{name}"',
    toast_deleted_node: '✓ Deleted node "{name}"',
    toast_enter_pin: '✕ Please enter PIN code',
    toast_export_failed: '✕ Export failed, please try again',
    toast_logs_cleared: '✓ Console logs cleared',
    toast_profile_updated: '✓ Node "{name}" configuration saved!',
    toast_profile_update_failed: '✕ Failed to save node configuration.',
    file_loaded_toast: '✓ Loaded file "{name}" successfully',
    btn_test_latency_all: '⚡ Test Latency All',
    btn_ping: '⚡ Ping',
    toast_latency_tested: '✓ Node "{name}" latency: {delay}',
    toast_all_latency_tested: '✓ All node latency tests completed!',
    toast_latency_failed: '✕ Latency test failed, please check network.',
    tab_overview: '📊 Dashboard',
    tab_profiles: '🚀 Nodes',
    tab_conntrack: '🔍 Connections',
    tab_settings: '⚙️ Settings',
    tab_mcp: '🤖 MCP & AI',
    tab_logs: '📜 Live Logs',
    stat_selected: '🎯 Active Node',
    stat_speed: '⚡ Live Speed (↑ / ↓)',
    stat_total: 'Σ Total Traffic',
    stat_filter: '🔀 App Routing',
    server_banner_title: '🖥️ Server Banner',
    server_banner_version_label: 'SSH Server Identity',
    stat_active_conns: '🔌 Active Conns',
    stat_total_conns: '📊 Total Conns',
    stat_route_hit_rate: '🎯 Route Cache Hit',
    conntrack_title: '🔍 Live Active Connections',
    domain_ranking_title: '🌐 Top Domain Activity',
    search_conns_placeholder: 'Search target domain, IP or port...',
    no_active_conns: 'No active network connections currently',
    no_domain_activity: 'No active domain traffic data',
    pagination_info: 'Page {page} of {totalPages} ({total} connections)',
    btn_page_first: '⏮ First',
    btn_page_prev: '◀ Prev',
    btn_page_next: 'Next ▶',
    btn_page_last: 'Last ⏭',
    btn_refresh: '🔄 Refresh',
    th_target: 'Target Address',
    th_proxy: 'Outbound Route',
    th_traffic: 'Traffic (↑ / ↓)',
    th_duration: 'Duration',
    stat_unselected: 'None selected',
    stat_not_configured: 'Not configured',
    quick_profiles: '🚀 Quick Nodes',
    btn_manage_all: 'Manage All',
    btn_export_backup: '📦 Export Encrypted Backup',
    btn_add_profile: '➕ Add / Import Node',
    profiles_title: '🚀 Saved Nodes',
    no_profiles: 'No nodes found. Click "Add / Import Node" above.',
    btn_select_this: 'Use This Node',
    badge_selected: '✓ Active',
    btn_edit_profile: '✏️ Edit',
    btn_delete: 'Delete',
    confirm_delete: 'Are you sure you want to delete node "{name}"?',
    modal_add_title: '➕ Import / Add Node',
    file_drop_title: 'Click to select or drop config file here to import',
    file_drop_desc: 'Supports .json, .txt, .bak or encrypted backup files',
    or_paste_text: 'Or paste raw text directly',
    import_placeholder: 'Paste mobile encrypted share code, backup text or JSON node config...',
    import_pin_label: '🔒 Decryption PIN:',
    import_pin_placeholder: 'Enter the PIN if encrypted (leave empty for plain JSON)',
    pin_detected_badge: '✓ Encrypted data detected',
    btn_cancel: 'Cancel',
    btn_import_now: 'Import',
    import_success: '✓ Successfully imported {count} node(s)!',
    import_failed: '✕ Import failed, please verify content or PIN code.',
    import_empty_error: '✕ Please paste or load import payload.',
    modal_export_title: '📦 Export Encrypted Backup',
    export_pin_label: 'Set an encryption PIN (letters/digits, min 4):',
    export_pin_placeholder: 'Enter PIN (letters/digits, min 4)',
    btn_gen_pin: '🎲 Random PIN',
    export_result_label: 'Encrypted Payload (Base64):',
    btn_export_cancel: 'Close',
    btn_export_exec: 'Generate Encrypted Backup',
    btn_export_copy: '📋 Copy Backup',
    export_success: '✓ Encrypted backup generated! PIN is: {pin}',
    export_copied: '✓ Encrypted backup copied to clipboard!',
    modal_edit_title: '✏️ Edit Node Configuration',
    label_edit_name: 'Node Name:',
    label_edit_note: "Note:",
    label_edit_favorite: "⭐ Favorite this node",
    edit_note_placeholder: "Optional, shown only on this device",
    badge_from_subscription: "📡 Sub",
    filter_tab_all: "All ({count})",
    filter_tab_favorites: "Favorites ({count})",
    filter_tab_recent: "Recent ({count})",
    filter_empty: "No nodes match this filter",
    fav_add: "Favorite",
    fav_remove: "Unfavorite",
    label_edit_ssh_addr: 'SSH Server Address:',
    label_edit_auth_type: 'SSH Auth Type:',
    opt_auth_password: 'Password Authentication',
    opt_auth_key: 'Private Key Authentication',
    label_edit_user: 'SSH Username:',
    label_edit_pass: 'SSH Password:',
    label_edit_key_pass: 'Key Passphrase:',
    label_edit_private_key: 'Private Key (OpenSSH / RSA / Ed25519):',
    label_edit_tunnel_type: 'Tunnel Type:',
    label_edit_proxy_addr: 'Proxy Address (Proxy Addr):',
    label_edit_proxy_icmp: 'ICMP Peer IP (no port):',
    label_edit_custom_host: 'Custom Host:',
    label_edit_server_name: 'SNI Domain (Server Name):',
    label_edit_custom_path: 'Custom Path:',
    label_edit_alpn: 'ALPN Negotiation (ALPN):',
    label_edit_http_payload: 'HTTP Payload (Custom Request Template):',
    label_edit_disable_status_check: 'Disable Strict HTTP 200 Status Check',
    label_edit_verify_cert: '🔒 Verify Server Certificate SHA-256 Fingerprint',
    label_edit_cert_fp: 'Certificate SHA-256 Fingerprint (Hex):',
    label_edit_proxy_auth: '🔑 Enable Upstream Proxy Authentication',
    label_edit_auth_token: 'Proxy Auth Token:',
    label_edit_auth_user: 'Proxy Username:',
    label_edit_auth_pass: 'Proxy Password:',
    label_edit_dns_servers: 'DNS Servers (comma separated):',
    label_edit_dns_domain: 'DNS Domain (Tunnel Domain):',
    label_edit_dns_type: 'DNS Record Type:',
    label_edit_dns_psk: 'DNS tunnel PSK:',
    label_edit_dns_marker: 'Tunnel marker:',
    error_field_required: 'This field is required',
    error_invalid_server_name: 'Server name cannot contain whitespace',
    error_invalid_fingerprint: 'Invalid SHA-256 fingerprint (64 hex digits, colons optional)',
    error_invalid_marker: 'Marker cannot contain whitespace (max 32 characters)',
    error_icmp_psk: 'ICMP PSK is required (or keep the SSH password as fallback)',
    error_icmp_magic: 'Magic must be 8 hex digits or 0x-prefixed hex',
    error_kcp_password: 'KCP password is required',
    label_edit_kcp_pass: 'KCP Password:',
    label_edit_kcp_crypt: 'KCP Encryption:',
    label_edit_kcp_data_shards: 'Data Shards:',
    label_edit_kcp_parity_shards: 'Parity Shards:',
    label_edit_kcp_nodelay: 'Enable KCP NoDelay Fast Low-latency Mode',
    label_kcp_mode: 'KCP mode (fast = default):',
    label_kcp_sndwnd: 'Send window (0 = default 128):',
    label_kcp_rcvwnd: 'Receive window (0 = default 512):',
    label_kcp_mtu: 'MTU (0 = default 1350):',
    label_kcp_nocomp: 'Snappy compression:',
    label_kcp_smuxver: 'SMUX version (0 = default 2):',
    label_kcp_keepalive: 'KeepAlive seconds (0 = default 10):',
    opt_kcp_mode_fast: 'Fast (default)',
    opt_kcp_mode_normal: 'Normal',
    opt_kcp_mode_fast2: 'Fast2',
    opt_kcp_mode_fast3: 'Fast3',
    opt_kcp_nocomp_off: 'Compression on',
    opt_kcp_nocomp_on: 'Compression off',
    label_edit_udp_custom_psk: 'UDP Custom PSK:',
    label_edit_udp_custom_magic: 'Magic Header (exactly 4 UTF-8 bytes):',
    label_edit_dns_edns0: 'EDNS0 (1232-byte answers, server must match)',
    label_edit_udp_custom_sockets: 'UDP Sockets (0 = default 1):',
    label_edit_udp_custom_paths: 'UDP Multipath Concurrency (random destination ports, 0 = default 32):',
    label_edit_udp_custom_send_window: 'Send Window (0 = default 256):',
    label_edit_xhttp_chunk: 'Chunk Size KB (0 = default 256, range 16-900):',
    label_edit_xhttp_stream_mode: 'XHTTP Stream Mode:',
    label_edit_bind_interface: 'Bind Interface (e.g. wlan0, blank = default):',
    webdav_title: '☁️ WebDAV Cloud Backup',
opt_tunnel_raw: 'RAW (direct TCP, TLS optional)',
opt_tunnel_websocket: 'WebSocket (TLS optional)',
opt_tunnel_webtransport: 'WebTransport',
label_tunnel_tls: '🔒 TLS encryption',
    label_webdav_url: 'WebDAV URL (backups go into its Stun folder):',
    label_webdav_user: 'Account:',
    label_webdav_pass: 'Password / app password:',
    label_webdav_pin: 'Backup PIN (letters/digits, min 4):',
    label_webdav_auto: 'Daily auto backup:',
    label_webdav_last: 'Last backup:',
    label_webdav_interval: 'Auto backup interval (hours):',
    opt_webdav_auto_off: 'Off',
    opt_webdav_auto_on: 'On',
    btn_webdav_backup: '☁️ Back up now',
    btn_webdav_save: '💾 Save config',
    btn_webdav_restore: '📥 Restore from cloud',
    webdav_saved: '✓ WebDAV config saved',
    webdav_backup_ok: '✓ WebDAV backup complete ({count} nodes; {sections})',
    webdav_backup_ok_plain: '✓ WebDAV backup complete ({count} nodes)',
    webdav_pin_too_short: 'Backup PIN must be at least 4 characters',
    webdav_restore_ok: '✓ Restored {count} nodes from WebDAV',
    webdav_restore_ok_full: '✓ Restored {count} nodes and {sections} from WebDAV',
    webdav_picker_title: '📥 Select a backup to restore',
    webdav_no_backups: 'No backups found on the server',
    webdav_restore_confirm: 'Restore nodes (merged by id) and global settings (except the backup PIN) from the cloud? Continue?',
    opt_tunnel_icmp: 'ICMP Custom (SSH-over-ICMP)',
    label_icmp_psk: 'PSK (pre-shared key):',
    label_icmp_magic: 'Magic (8 hex chars, blank = default):',
    ph_icmp_custom_magic: 'empty = default (8 hex or 0x)',
    label_icmp_family: 'IP family:',
    label_icmp_mtu: 'MTU mode:',
    label_icmp_max_payload: 'Max payload (0 = default):',
    label_icmp_pace: 'Packet pace ms (0 = default):',
    label_icmp_id_range: 'Echo ID pool (e.g. 1000-1999):',
    opt_icmp_family_auto: 'Auto',
    opt_icmp_family_v4: 'IPv4',
    opt_icmp_family_v6: 'IPv6',
    opt_icmp_mtu_probe: 'Probe',
    opt_icmp_mtu_auto: 'Auto',
    opt_icmp_mtu_fixed: 'Fixed',
    webdav_failed_generic: '✕ WebDAV operation failed',
    opt_stream_auto: 'Auto (streaming with polling fallback)',
    opt_stream_stream: 'Stream (forced streaming downlink)',
    opt_stream_poll: 'Poll (forced polling downlink)',
    label_edit_heartbeat_interval: 'Heartbeat Interval ms (0 = default 25000):',
    label_edit_dns_override: '🌐 Enable Custom DNS & Direct Routing for this Node',
    label_edit_remote_dns: 'Node Remote DNS:',
    label_edit_local_dns: 'Node Local DNS:',
    label_edit_udpgw_version: 'Node UDPGW Engine:',
    label_edit_udpgw_addr: 'Node UDPGW Address:',
    label_edit_geosite_direct: 'GeoSite Direct Tags:',
    label_edit_geoip_direct: 'GeoIP Direct Tags:',
    label_edit_app_override: '🔀 Enable Custom App Split Tunneling for this Node',
    node_mode_disallow: '🚫 <b>Bypass Mode</b> (Bypass selected apps)',
    node_mode_allow: '🚀 <b>Proxy Mode</b> (Only proxy selected apps)',
    btn_save_node_edit: '💾 Save Node Config',
    filter_title: '🔀 Global App Split Tunneling',
    btn_save_filter: '💾 Save App Filter Only',
    mode_disallow: '🚫 <b>Bypass Mode</b> (Bypass selected apps)',
    mode_allow: '🚀 <b>Proxy Mode</b> (Only proxy selected apps)',
    search_placeholder: 'Search app name or package...',
    btn_select_all: 'Select / Deselect All',
    loading_apps: 'Loading installed apps...',
    no_apps: 'No installed apps found',
    filter_save_success: '✓ Split tunneling settings saved!',
    filter_save_failed: '✕ Save failed, please try again.',
    filter_status_allow: 'Proxy Mode ({count} apps)',
    filter_status_disallow: 'Bypass Mode ({count} apps)',
    btn_save_all_settings: '💾 Save All Settings',
    settings_core_title: '🚀 Service Mode & Core Network',
    label_service_mode: 'Service Mode:',
    opt_mode_vpn: 'VPN Mode (Standard VpnService)',
    opt_mode_tproxy: 'Transparent Proxy Mode (TProxy)',
    label_log_level: 'Log Level:',
    opt_log_debug: 'DEBUG (Verbose Debug)',
    opt_log_info: 'INFO (Normal - Recommended)',
    opt_log_warn: 'WARN (Warnings Only)',
    opt_log_error: 'ERROR (Errors Only)',
    label_remote_dns: 'Remote DNS Server:',
    label_local_dns: 'Local Direct DNS Server:',
    settings_udpgw_title: '📡 UDP Gateway (UDPGW)',
    label_udpgw_version: 'UDPGW Engine:',
    opt_udpgw_tun2proxy: 'tun2proxy (High Performance Rust - Default)',
    opt_udpgw_badvpn: 'badvpn (Legacy Compatibility)',
    label_udpgw_addr: 'UDPGW Address:',
    settings_geodata_title: '🌐 GeoData & Direct Routing Rules',
    btn_update_geodata: '🔄 Update GeoData Now',
    label_geosite_direct: 'GeoSite Direct Domain Tags:',
    label_geoip_direct: 'GeoIP Direct IP Tags:',
    label_geosite_url: 'GeoSite Rule Database URL:',
    label_geoip_url: 'GeoIP Rule Database URL:',
    label_update_interval: 'Auto-update Interval (seconds):',
    label_last_update: 'Last Updated:',
    never_updated: 'Never updated',
    geodata_updating: 'Updating GeoData rules...',
    geodata_update_success: '✓ GeoData updated successfully!',
    geodata_update_failed: '✕ Failed to update GeoData. Check network.',
    settings_system_title: '🔔 System & Notifications',
    label_show_speed: 'Show live network speed in system notification',
    settings_title: '🛡️ Web Console Security & Token Authentication',
    auth_mode_0_title: '<b>🎲 Random on Every Restart (Recommended)</b>',
    auth_mode_0_desc: 'Maximum security. Generates a new 8-character random token on each restart.',
    auth_mode_1_title: '<b>🔒 Fixed Once / Permanent Token</b>',
    auth_mode_1_desc: 'Permanent token saved across reboots. Great for browser bookmarks & home screen shortcuts.',
    auth_mode_2_title: '<b>✏️ Custom Access Token / Password</b>',
    auth_mode_2_desc: 'Define your own memorable access password (e.g. foxvpn, 888888).',
    auth_mode_3_title: '<b>🌐 Disable Authentication (Open LAN)</b>',
    auth_mode_3_desc: 'Anyone on your local Wi-Fi can directly open http://IP:PORT without ?token= parameter.',
    custom_token_label: 'Custom Access Token:',
    custom_token_placeholder: 'Enter custom token password (e.g. 123456 or mysecret)',
    current_url_label: '🔗 Live Web Access URL',
    btn_copy_url: '📋 Copy URL',
    url_copied: '✓ Full access URL copied to clipboard!',
    settings_save_success: '✓ All settings saved!',
    settings_save_failed: '✕ Failed to save settings.',
    btn_autoscroll_on: '⬇ Auto-scroll: ON',
    btn_autoscroll_off: '⬇ Auto-scroll: OFF',
    btn_copy_logs: '📋 Copy Logs',
    btn_clear_logs: '🗑 Clear',
    logs_copied: '✓ Logs copied to clipboard!',
    vpn_connected: '✓ Connected (Tap to disconnect)',
    vpn_disconnected: 'Disconnected (Tap to connect)',
    vpn_connecting: '⚡ Connecting...',
    lines_unit: ' lines',
    toast_read_file_failed: '✕ Failed to read file',
    badge_encrypted_detected: '🔒 Encrypted payload detected (PIN required)',
    badge_plain_detected: '✓ Plain config loaded ({count} node(s))',
    err_empty_content: '✕ Import content cannot be empty',
    err_pin_required: '✕ Encrypted payload detected, please enter the PIN',
    err_invalid_pin: '✕ Invalid PIN code or decryption failed',
    err_invalid_format: '✕ Unrecognized node configuration format',

    // MCP Settings & Dashboard
    settings_mcp_title: '🤖 Model Context Protocol (MCP) AI Agent Server',
    label_mcp_enable: 'Enable MCP AI Agent Server',
    desc_mcp_enable: 'Allow Claude Desktop, Cursor, Gemini and other AI agents to remotely control this device via MCP protocol or Gemini Function Calling',
    label_mcp_port: 'MCP Unified Service Port (HTTP/HTTPS):',
    label_mcp_auth_mode: 'MCP Access Authentication Mode:',
    opt_mcp_auth_none: '🟢 No Auth (LAN open access)',
    opt_mcp_auth_apikey: '🔑 API Key (Bearer Token)',
    opt_mcp_auth_basic: '👤 HTTP Basic (Username & Password)',
    opt_mcp_auth_oauth: '🛡️ OAuth 2.0 (Token / Code grant)',
    label_mcp_secret: 'MCP Access Secret / Key:',
    mcp_secret_placeholder: 'Leave empty for no auth or default key',
    mcp_stat_status: '📡 MCP Service Status',
    mcp_stat_port: '🔌 Listening Port',
    mcp_stat_proto: '⚡ Protocol & Mode',
    mcp_stat_tools: '🛠️ Available AI Tools',
    mcp_dash_title: '🚀 Stun MCP & Gemini Interactive Dashboard',
    mcp_dash_desc: 'Includes SSL CA certificate download, OAuth 2.0 authorization, live SSE monitor and multilingual support',
    btn_open_mcp_dash: '🚀 Open MCP Dashboard',
    mcp_claude_title: '💻 Claude Desktop / Cursor / Windsurf Integration',
    mcp_claude_desc: 'Paste the following into your <code>claude_desktop_config.json</code>:',
    btn_copy_mcp_config: '📋 Copy Config',
    mcp_gemini_title: '🤖 Google Gemini 2.0 Flash / 1.5 Pro Direct (Python)',
    mcp_gemini_desc: 'Control Stun with just 3 lines of Python via native Function Calling:',
    btn_copy_gemini_snippet: '📋 Copy Code',
    mcp_tools_title: '🛠️ 18 Registered AI Control Capabilities',
    mcp_gemini_c1: '# 1. Dynamically fetch the 18 tools declared by Stun MCP',
    mcp_gemini_c2: '# 2. Initialize Gemini 2.0 Flash / Pro automatic function calling',
    mcp_gemini_c3: '# 3. Send prompt for AI to automatically execute Stun control functions',
    mcp_gemini_prompt: 'Check Stun VPN status and test all node latencies',
    toast_mcp_config_copied: '✓ MCP JSON config copied to clipboard!',
    toast_gemini_snippet_copied: '✓ Gemini Python snippet copied!',
    subscription_title: '📡 Subscription',
    subscription_url_placeholder: 'https://example.com/sub or subscription link',
    btn_sub_sync: '🔄 Sync Now',
    label_sub_last_sync: 'Last sync: ',
    sub_never_synced: 'Never synced',
    subscription_syncing: 'Syncing...',
    subscription_sync_success: '✓ Subscription synced, {count} node(s) imported',
    subscription_sync_error: '✕ Subscription sync failed: {msg}',
    subscription_url_empty: '✕ Please enter a subscription URL',
    subscription_pin_hint: 'PIN (optional, for encrypted subscription)',
    subscription_pin_short: 'PIN',
    subscription_pin_required: '✕ This subscription is PIN-encrypted. Enter the PIN',
    subscription_pin_invalid: '✕ Wrong PIN or decryption failed',
    subscription_add: '➕ Add Subscription',
    subscription_sync_partial: '✓ Synced {ok}/{total} subscription(s), {count} node(s) imported',

  },
  'ja': {
    edit_sec_1_title: "🖥️ 基本接続 & SSH 認証",
    edit_sec_2_title: "🚀 トランスポート & プロトコル設定",
    edit_sec_3_title: "🛡️ セキュリティ & フィンガープリント検証",
    edit_sec_4_title: "🌐 ルーティング & アプリ分離オーバーライド",
    edit_name_placeholder: "例: 東京高速ノード",
    edit_ssh_addr_placeholder: "IP:ポート または ドメイン:ポート",
    edit_user_placeholder: "例: root",
    edit_pass_placeholder: "変更しない場合は空のまま",
    edit_key_pass_placeholder: "パスフレーズがない場合は空欄",
    edit_proxy_addr_placeholder: "IP:ポート または ドメイン:ポート",
    edit_custom_host_placeholder: "例: cloudflare.com",
    edit_server_name_placeholder: "TLS SNI ドメイン名",
    edit_custom_path_placeholder: "/path/to/stream",
    edit_udp_psk_placeholder: "事前共有キー (PSK)",
    edit_noise_pk_placeholder: "Base64 または Hex 形式の公開鍵",
    edit_ssh_fp_placeholder: "SHA256:... または MD5:...",
    edit_cert_fp_placeholder: "AA:BB:CC:DD...",
    edit_auth_token_placeholder: "例: Token_Secret_888",
    btn_fetch_ssh_fp_title: "SSH ホスト鍵フィンガープリントを取得して自動入力",
    btn_details_ssh_title: "SSH ホスト鍵の詳細情報を表示",
    btn_fetch_cert_fp_title: "TLS 証明書フィンガープリントを取得して自動入力",
    btn_details_cert_title: "TLS 証明書の詳細情報を表示",

    info_target_address: "ターゲットアドレス",
    info_server_banner: "サーバー Banner",
    info_public_key_type: "公開鍵アルゴリズム",
    info_handshake_latency: "ハンドシェイク所要時間",
    info_sha256_fingerprint: "SHA-256 フィンガープリント",
    info_md5_fingerprint: "MD5 フィンガープリント",
    info_sha1_fingerprint: "SHA-1 フィンガープリント",
    info_sni: "SNI ドメイン",
    info_subject: "サブジェクト (Subject)",
    info_issuer: "発行者 (Issuer)",
    info_sans: "サブジェクト別名 (SANs)",
    info_cert_validity: "証明書の有効性",
    info_cert_expired: "❌ 期限切れ",
    info_days_remaining: "✓ 残り {days} 日",
    info_signature_alg: "署名アルゴリズム",
    info_public_key_alg: "公開鍵アルゴリズム",
    info_tls_version: "TLS バージョン",
    info_alpn_negotiation: "ALPN ネゴシエーション",
    info_duration_ms: "所要時間: {ms} ms",

    opt_tunnel_tls: "TLS (標準 TLS 暗号化トンネル)",
    opt_tunnel_ws: "WS (WebSocket 平文)",
    opt_tunnel_wss: "WSS (WebSocket over TLS)",
    opt_tunnel_h2: "H2 (HTTP/2 over TLS)",
    opt_tunnel_h2c: "H2C (HTTP/2 Cleartext)",
    opt_tunnel_http: "HTTP (HTTP プロキシトンネル)",
    opt_tunnel_base: "BASE (直接 TCP トンネル)",
    opt_tunnel_quic: "QUIC (QUIC データグラム)",
    opt_tunnel_grpc: "gRPC (gRPC over TLS)",
    opt_tunnel_grpcc: "gRPCC (gRPC Cleartext)",
    opt_tunnel_h3: "H3 (HTTP/3 over QUIC)",
    opt_tunnel_wt: "WebTransport",
    opt_tunnel_masque: "MASQUE (HTTP/3 IP プロキシ)",
    opt_tunnel_xhttp: "XHTTP (ストリーミング多重化 HTTP)",
    opt_tunnel_xhttpc: "XHTTPC (XHTTP Cleartext)",
    opt_tunnel_dns: "DNS Tunnel (SSH-over-DNS)",
    opt_tunnel_kcp: "KCP (SSH-over-KCP パケットロス防止)",
    opt_tunnel_udpc: "UDP Custom (カスタム難読化 UDP トンネル)",
    opt_alpn_h3_h2: "h3,h2 (H3 優先 / 自動フォールバック)",
    opt_alpn_h2_h3: "h2,h3 (H2 優先 / 自動フォールバック)",
    opt_alpn_h3: "h3 (HTTP/3 のみ)",
    opt_alpn_h2: "h2 (HTTP/2 のみ)",
    opt_dns_txt: "TXT (推奨)",
    details_modal_title: "ℹ️ 詳細情報",
    btn_details_copy: "📋 コピー",
    btn_details_apply: "✓ 適用",
    btn_details_close: "閉じる",
    label_edit_noise_public_key: "Noise サーバー公開鍵 (任意、Curve25519 Hex/Base64):",

    label_edit_verify_ssh_fp: '🔑 SSH ホスト公開鍵フィンガープリントを検証',
    error_invalid_address: "無効なアドレス形式です (ホスト:ポート または ホスト:ポート範囲)",
    error_invalid_address_single_port: "無効なアドレス形式です (ホスト:ポート)。このプロトコルではポート範囲はサポートされていません。",
    hint_proxy_range: "UDP Custom ではポート範囲を指定できます（例: 1.1.1.1:1024-23000）。パケットごとに異なるポートへ送信し、単一ポートのレート制限を回避します。",
    label_edit_ssh_fp: 'SSH 公開鍵フィンガープリント (SHA256 / MD5):',
    btn_fetch_fp: '🔍 取得',
    btn_details: 'ℹ️ 詳細',
    ssh_details_title: 'ℹ️ SSH ホスト鍵の詳細情報',
    tls_details_title: 'ℹ️ TLS 証明書の詳細情報',
    toast_ssh_fp_success: '✓ SSH ホスト鍵を取得しました！',
    toast_cert_fp_success: '✓ TLS 証明書フィンガープリントを取得しました！',
    toast_missing_ssh_addr: '✕ SSH サーバーアドレスを入力してください',
    toast_missing_proxy_addr: '✕ プロキシアドレスまたは SSH アドレスを入力してください',
    toast_fetch_failed: '✕ 取得に失敗しました: {msg}',
    toast_details_copied: '✓ 詳細情報をクリップボードにコピーしました！',
    toast_fp_applied: '✓ フィンガープリントを適用し検証を有効化しました！',

    page_title: '🦊 Stun · Web コンソール',
    theme_toggle_title: 'テーマ切り替え',
    toast_vpn_connecting: 'VPNに接続中...',
    toast_vpn_disconnected: 'VPNを切断しました',
    toast_switched_node: '✓ ノード「{name}」に切り替えました',
    toast_deleted_node: '✓ ノード「{name}」を削除しました',
    toast_enter_pin: '✕ PINコードを入力してください',
    toast_export_failed: '✕ エクスポートに失敗しました。再試行してください',
    toast_logs_cleared: '✓ ログをクリアしました',
    toast_profile_updated: '✓ ノード「{name}」の設定を保存しました！',
    toast_profile_update_failed: '✕ ノード設定の保存に失敗しました。',
    error_udp_psk_required: "UDP Custom には PSK が必要です",
    error_udp_magic: "Magic は 4 UTF-8 バイトちょうどである必要があります（例：UDPC）",
    error_noise_public_key: "公開鍵は 64 文字の 16 進数または 32 バイト Base64 である必要があります",
    error_dns_noise_record_type: "Noise 暗号化では A/AAAA レコードを使用できません",
    error_xhttp_chunk_size: "チャンクサイズは 0、または 16〜900 KB である必要があります",
    error_invalid_host_only: "対向 IP を入力してください（ICMP にポートはありません。例: 1.1.1.1 または IPv6）",
    error_invalid_dns_servers: "DNS サーバーを 1 つ以上入力してください（カンマまたは改行区切り、udp/tcp/tls/dot/https:// 接頭辞可）",
    error_invalid_path: "パスは / で始める必要があります",
    error_invalid_number: "有効な整数を入力してください",
    error_invalid_mtu_probe: "MTU プロブは空、auto、on/true/1、または off/false/0 のみ可能です",
    error_invalid_masque_alpn: "Masque ALPN は auto、h3、h2 のみ可能です",
    warn_psk_short: "PSK が 16 文字未満のため、セキュリティが弱くなります",
    warn_xhttp_no_fingerprint: "xHTTP は証明書フィンガープリント未検証のため、MITM のリスクがあります",
    label_edit_padding_min_bytes: "最小填充バイト数 (0 = 既定 1420、負数で無効)：",
    label_edit_masque_alpn: "Masque ALPN（masque のみ）：",
    error_heartbeat_interval: "ハートビートは 0、または 5000〜300000 ms である必要があります",
    file_loaded_toast: '✓ ファイル「{name}」を読み込みました',
    btn_test_latency_all: '⚡ 一括速度測定',
    btn_ping: '⚡ 測定',
    toast_latency_tested: '✓ ノード「{name}」の遅延: {delay}',
    toast_all_latency_tested: '✓ 全ノードの速度測定が完了しました！',
    toast_latency_failed: '✕ 速度測定に失敗しました',
    tab_overview: '📊 概要',
    tab_profiles: '🚀 ノード管理',
    tab_conntrack: '🔍 接続追跡',
    tab_settings: '⚙️ 設定',
    tab_mcp: '🤖 MCP & AI',
    tab_logs: '📜 リアルタイムログ',
    stat_selected: '🎯 選択中ノード',
    stat_speed: '⚡ リアルタイム速度',
    stat_total: 'Σ 総通信量',
    stat_filter: '🔀 分割トンネル状態',
    server_banner_title: '🖥️ サーバー Banner',
    server_banner_version_label: 'SSH サーバー識別子',
    stat_active_conns: '🔌 アクティブ接続',
    stat_total_conns: '📊 累計接続数',
    stat_route_hit_rate: '🎯 ルートキャッシュ率',
    conntrack_title: '🔍 リアルタイムアクティブ接続',
    domain_ranking_title: '🌐 リアルタイムドメインランキング',
    search_conns_placeholder: '接続先、ドメイン、ポートを検索...',
    no_active_conns: '現在アクティブな接続はありません',
    no_domain_activity: 'ドメインアクティビティデータはありません',
    pagination_info: 'ページ {page} / {totalPages} (全 {total} 件)',
    btn_page_first: '⏮ 最初',
    btn_page_prev: '◀ 前へ',
    btn_page_next: '次へ ▶',
    btn_page_last: '最後 ⏭',
    btn_refresh: '🔄 更新',
    th_target: '接続先アドレス',
    th_proxy: '送信プロキシ',
    th_traffic: '通信量 (↑ / ↓)',
    th_duration: '接続時間',
    stat_unselected: '未選択',
    stat_not_configured: '未設定',
    quick_profiles: '🚀 クイックノード',
    btn_manage_all: 'すべて管理',
    btn_export_backup: '📦 暗号化バックアップ出力',
    btn_add_profile: '➕ ノード追加 / インポート',
    profiles_title: '🚀 保存済みノード',
    no_profiles: 'ノードがありません。「ノード追加」をクリックしてください。',
    btn_select_this: 'このノードを使用',
    badge_selected: '✓ 選択中',
    btn_edit_profile: '✏️ 編集',
    btn_delete: '削除',
    confirm_delete: 'ノード「{name}」を削除してもよろしいですか？',
    modal_add_title: '➕ ノード追加 / インポート',
    file_drop_title: 'クリックして選択またはファイルをドロップ',
    file_drop_desc: '.json, .txt, .bak または暗号化バックアップに対応',
    or_paste_text: 'またはテキストを直接貼り付け',
    import_placeholder: 'スマホの暗号化共有コード、バックアップ、またはJSON設定を貼り付け...',
    import_pin_label: '🔒 復号PINコード：',
    import_pin_placeholder: '暗号化データの場合はPINを入力（平文は空欄可）',
    pin_detected_badge: '✓ 暗号化データを検出',
    btn_cancel: 'キャンセル',
    btn_import_now: 'インポート',
    import_success: '✓ {count} 個のノードをインポートしました！',
    import_failed: '✕ インポートに失敗しました。PINまたは内容を確認してください。',
    import_empty_error: '✕ インポート内容を貼り付けまたは読み込んでください',
    modal_export_title: '📦 暗号化バックアップのエクスポート',
    export_pin_label: '保護PIN（英数字・4文字以上）を設定：',
    export_pin_placeholder: 'PINを入力（英数字4文字以上）',
    btn_gen_pin: '🎲 ランダムPIN',
    export_result_label: '暗号化バックアップ（Base64）：',
    btn_export_cancel: '閉じる',
    btn_export_exec: 'バックアップを生成',
    btn_export_copy: '📋 コピー',
    export_success: '✓ 暗号化バックアップを生成しました！PIN: {pin}',
    export_copied: '✓ クリップボードにコピーしました！',
    modal_edit_title: '✏️ ノード設定の編集',
    label_edit_name: 'ノード名：',
    label_edit_note: "備考：",
    label_edit_favorite: "⭐ このノードをお気に入りに追加",
    edit_note_placeholder: "任意、この端末にのみ表示",
    badge_from_subscription: "📡 サブスク",
    filter_tab_all: "すべて ({count})",
    filter_tab_favorites: "お気に入り ({count})",
    filter_tab_recent: "最近 ({count})",
    filter_empty: "この条件に一致するノードはありません",
    fav_add: "お気に入り",
    fav_remove: "解除",
    label_edit_ssh_addr: 'SSH サーバーアドレス：',
    label_edit_auth_type: 'SSH 認証方式：',
    opt_auth_password: 'パスワード認証 (Password)',
    opt_auth_key: '公開鍵認証 (Private Key)',
    label_edit_user: 'SSH ユーザー名：',
    label_edit_pass: 'SSH パスワード：',
    label_edit_key_pass: '秘密鍵パスフレーズ：',
    label_edit_private_key: '秘密鍵 (OpenSSH / RSA / Ed25519)：',
    label_edit_tunnel_type: '通信プロトコル (Tunnel Type)：',
    label_edit_proxy_addr: 'プロキシアドレス (Proxy Addr)：',
    label_edit_proxy_icmp: 'ICMP 接続先 IP（ポート不要）：',
    label_edit_custom_host: '偽装ドメイン (Custom Host)：',
    label_edit_server_name: 'SNI ドメイン (Server Name)：',
    label_edit_custom_path: 'カスタムパス (Custom Path)：',
    label_edit_alpn: 'ALPN 協商 (ALPN)：',
    label_edit_http_payload: 'HTTP Payload (リクエストヘッダー)：',
    label_edit_disable_status_check: 'HTTP 200 応答チェックを無効化',
    label_edit_verify_cert: '🔒 サーバー証明書 SHA-256 フィンガープリント検証',
    label_edit_cert_fp: '証明書 SHA-256 フィンガープリント：',
    label_edit_proxy_auth: '🔑 プロキシ認証を有効化 (Proxy Auth)',
    label_edit_auth_token: 'Proxy Auth Token：',
    label_edit_auth_user: 'Proxy ユーザー名：',
    label_edit_auth_pass: 'Proxy パスワード：',
    label_edit_dns_servers: 'DNS サーバー (カンマ区切り)：',
    label_edit_dns_domain: 'DNS ドメイン (Tunnel Domain)：',
    label_edit_dns_type: 'DNS レコードタイプ：',
    label_edit_dns_psk: 'DNS トンネル PSK：',
    label_edit_dns_marker: 'トンネルマーカー：',
    error_field_required: 'この項目は必須です',
    error_invalid_server_name: 'SNI に空白は使えません',
    error_invalid_fingerprint: '無効な SHA-256 フィンガープリント（16 進 64 桁、コロン可）',
    error_invalid_marker: 'マーカーに空白は使えません（最大 32 文字）',
    error_icmp_psk: 'ICMP PSK は必須です（SSH パスワードでの代替も可）',
    error_icmp_magic: 'マジックは 16 進 8 桁または 0x 接頭辞で入力',
    error_kcp_password: 'KCP パスワードは必須です',
    label_edit_kcp_pass: 'KCP パスワード：',
    label_edit_kcp_crypt: 'KCP 暗号化方式：',
    label_edit_kcp_data_shards: 'Data Shards (データ分割)：',
    label_edit_kcp_parity_shards: 'Parity Shards (パリティ分割)：',
    label_edit_kcp_nodelay: 'KCP NoDelay 低遅延モードを有効化',
    label_kcp_mode: 'KCP モード (fast = デフォルト)：',
    label_kcp_sndwnd: '送信ウィンドウ (0 = デフォルト 128)：',
    label_kcp_rcvwnd: '受信ウィンドウ (0 = デフォルト 512)：',
    label_kcp_mtu: 'MTU (0 = デフォルト 1350)：',
    label_kcp_nocomp: 'Snappy 圧縮：',
    label_kcp_smuxver: 'SMUX バージョン (0 = デフォルト 2)：',
    label_kcp_keepalive: 'KeepAlive 秒 (0 = デフォルト 10)：',
    opt_kcp_mode_fast: 'Fast (デフォルト)',
    opt_kcp_mode_normal: 'Normal',
    opt_kcp_mode_fast2: 'Fast2',
    opt_kcp_mode_fast3: 'Fast3',
    opt_kcp_nocomp_off: '圧縮オン',
    opt_kcp_nocomp_on: '圧縮オフ',
    label_edit_udp_custom_psk: 'UDP Custom PSK：',
    label_edit_udp_custom_magic: 'Magic ヘッダー（UTF-8 で正確に4バイト）：',
    label_edit_dns_edns0: 'EDNS0（1232バイト応答、サーバー側と一致が必要）',
    label_edit_udp_custom_sockets: 'UDPソケット数 (0 = デフォルト 1)：',
    label_edit_udp_custom_paths: 'リモート UDP パス数 (0 = デフォルト 32)：',
    label_edit_udp_custom_send_window: '送信ウィンドウ (0 = デフォルト 256)：',
    label_edit_xhttp_chunk: 'チャンクサイズ KB (0 = デフォルト 256、範囲 16-900)：',
    label_edit_xhttp_stream_mode: 'XHTTP ストリームモード：',
    label_edit_bind_interface: 'インターフェースバインド（例：wlan0、空 = デフォルト）：',
    webdav_title: '☁️ WebDAV クラウドバックアップ',
opt_tunnel_raw: 'RAW (直接 TCP、TLS 切替可)',
opt_tunnel_websocket: 'WebSocket (TLS 切替可)',
opt_tunnel_webtransport: 'WebTransport',
label_tunnel_tls: '🔒 TLS 暗号化',
    label_webdav_url: 'WebDAV URL（バックアップは Stun フォルダに保存）：',
    label_webdav_user: 'アカウント：',
    label_webdav_pass: 'パスワード / アプリパスワード：',
    label_webdav_pin: 'バックアップ PIN（英数字4文字以上）：',
    label_webdav_auto: '毎日自動バックアップ：',
    label_webdav_last: '前回のバックアップ：',
    label_webdav_interval: '自動バックアップ間隔 (時間)：',
    opt_webdav_auto_off: 'オフ',
    opt_webdav_auto_on: 'オン',
    btn_webdav_backup: '☁️ 今すぐバックアップ',
    btn_webdav_save: '💾 設定を保存',
    btn_webdav_restore: '📥 クラウドから復元',
    webdav_saved: '✓ WebDAV 設定を保存しました',
    webdav_backup_ok: '✓ WebDAV バックアップ完了（{count} ノード；{sections}）',
    webdav_backup_ok_plain: '✓ WebDAV バックアップ完了（{count} ノード）',
    webdav_pin_too_short: 'バックアップ PIN は 4 文字以上必要です',
    webdav_restore_ok: '✓ WebDAV から {count} ノードを復元しました',
    webdav_restore_ok_full: '✓ WebDAV から {count} ノードと {sections} を復元しました',
    webdav_picker_title: '📥 復元するバックアップを選択',
    webdav_no_backups: 'サーバー上にバックアップがありません',
    webdav_restore_confirm: 'クラウドからノード（ID でマージ）と全局設定（バックアップ PIN を除く）を復元します。続行しますか？',
    opt_tunnel_icmp: 'ICMP Custom (SSH-over-ICMP)',
    label_icmp_psk: 'PSK（事前共有キー）：',
    label_icmp_magic: 'Magic（8桁の16進数、空 = デフォルト）：',
    ph_icmp_custom_magic: '空 = デフォルト（8桁16進 または 0x）',
    label_icmp_family: 'IP ファミリー：',
    label_icmp_mtu: 'MTU モード：',
    label_icmp_max_payload: '最大ペイロード (0 = デフォルト)：',
    label_icmp_pace: '送信間隔 ms (0 = デフォルト)：',
    label_icmp_id_range: 'Echo ID プール (例: 1000-1999)：',
    opt_icmp_family_auto: '自動',
    opt_icmp_family_v4: 'IPv4',
    opt_icmp_family_v6: 'IPv6',
    opt_icmp_mtu_probe: 'Probe（探索）',
    opt_icmp_mtu_auto: 'Auto',
    opt_icmp_mtu_fixed: 'Fixed（固定）',
    webdav_failed_generic: '✕ WebDAV 操作に失敗しました',
    opt_stream_auto: '自動（ストリーミング＋ポーリングフォールバック）',
    opt_stream_stream: 'Stream（ストリーミング強制）',
    opt_stream_poll: 'Poll（ポーリング強制）',
    label_edit_heartbeat_interval: 'ハートビート間隔 ms (0 = デフォルト 25000)：',
    label_edit_dns_override: '🌐 このノード専用の DNS & 直接ルーティングを有効化',
    label_edit_remote_dns: '専用リモート DNS：',
    label_edit_local_dns: '専用ローカル DNS：',
    label_edit_udpgw_version: '専用 UDPGW エンジン：',
    label_edit_udpgw_addr: '専用 UDPGW アドレス：',
    label_edit_geosite_direct: 'GeoSite 直接タグ：',
    label_edit_geoip_direct: 'GeoIP 直接 IP タグ：',
    label_edit_app_override: '🔀 このノード専用のアプリ分割トンネルを有効化',
    node_mode_disallow: '🚫 <b>バイパスモード</b> (選択アプリをバイパス)',
    node_mode_allow: '🚀 <b>プロキシモード</b> (選択アプリのみプロキシ)',
    btn_save_node_edit: '💾 ノード設定を保存',
    filter_title: '🔀 全体アプリ分割トンネル',
    btn_save_filter: '💾 アプリ設定のみ保存',
    mode_disallow: '🚫 <b>バイパスモード</b> (選択アプリをバイパス)',
    mode_allow: '🚀 <b>プロキシモード</b> (選択アプリのみプロキシ)',
    search_placeholder: 'アプリ名またはパッケージ名を検索...',
    btn_select_all: '全選択 / 全解除',
    loading_apps: 'アプリ一覧を読み込み中...',
    no_apps: 'アプリが見つかりません',
    filter_save_success: '✓ 分割トンネル設定を保存しました！',
    filter_save_failed: '✕ 保存に失敗しました。再試行してください。',
    filter_status_allow: 'プロキシモード ({count} 件)',
    filter_status_disallow: 'バイパスモード ({count} 件)',
    btn_save_all_settings: '💾 すべての設定を保存',
    settings_core_title: '🚀 動作モードとコアネットワーク',
    label_service_mode: '動作モード (Service Mode)：',
    opt_mode_vpn: 'VPNモード (標準)',
    opt_mode_tproxy: '透明プロキシモード (TProxy)',
    label_log_level: 'ログレベル (Log Level)：',
    opt_log_debug: 'DEBUG (詳細デバッグ)',
    opt_log_info: 'INFO (通常 - 推奨)',
    opt_log_warn: 'WARN (警告のみ)',
    opt_log_error: 'ERROR (エラーのみ)',
    label_remote_dns: 'リモート DNS：',
    label_local_dns: 'ローカル直接 DNS：',
    settings_udpgw_title: '📡 UDP ゲートウェイ設定 (UDPGW)',
    label_udpgw_version: 'UDPGW エンジン：',
    opt_udpgw_tun2proxy: 'tun2proxy (高性能 Rust エンジン - デフォルト)',
    opt_udpgw_badvpn: 'badvpn (従来互換モード)',
    label_udpgw_addr: 'UDPGW アドレス：',
    settings_geodata_title: '🌐 地理データと直接ルーティング',
    btn_update_geodata: '🔄 Geoルールを今すぐ更新',
    label_geosite_direct: 'GeoSite 直接ドメインタグ：',
    label_geoip_direct: 'GeoIP 直接 IP タグ：',
    label_geosite_url: 'GeoSite データベースURL：',
    label_geoip_url: 'GeoIP データベースURL：',
    label_update_interval: '自動更新間隔 (秒)：',
    label_last_update: '最終更新日時：',
    never_updated: '未更新',
    geodata_updating: 'Geoルールを更新中...',
    geodata_update_success: '✓ Geoルールを更新しました！',
    geodata_update_failed: '✕ Geoルールの更新に失敗しました',
    settings_system_title: '🔔 システムと通知',
    label_show_speed: 'システム通知にリアルタイム速度を表示',
    settings_title: '🛡️ Webコンソール認証とToken設定',
    auth_mode_0_title: '<b>🎲 起動毎にランダム生成 (Random on Start)</b>',
    auth_mode_0_desc: '最高レベルのセキュリティ。起動毎に新しい8桁Tokenを生成します。',
    auth_mode_1_title: '<b>🔒 固定Token / 永続保持 (Fixed Once / Permanent)</b>',
    auth_mode_1_desc: '一度生成したTokenを永続保存します。ブックマークやホーム画面追加に最適です。',
    auth_mode_2_title: '<b>✏️ カスタムTokenパスワード (Custom Token)</b>',
    auth_mode_2_desc: '任意のアクセスパスワードを設定できます（例: 123456, foxvpn）。',
    auth_mode_3_title: '<b>🌐 認証無効 (Token不要 LAN直接接続)</b>',
    auth_mode_3_desc: '同一Wi-Fi LAN内の端末からTokenなしで直接アクセスできます。',
    custom_token_label: 'カスタムアクセスToken：',
    custom_token_placeholder: 'パスワードを入力（例: 123456）',
    current_url_label: '🔗 現在のアクセスURL',
    btn_copy_url: '📋 URLをコピー',
    url_copied: '✓ URLをクリップボードにコピーしました！',
    settings_save_success: '✓ すべての設定を保存しました！',
    settings_save_failed: '✕ 設定の保存に失敗しました。',
    btn_autoscroll_on: '⬇ 自動スクロール: ON',
    btn_autoscroll_off: '⬇ 自動スクロール: OFF',
    btn_copy_logs: '📋 ログをコピー',
    btn_clear_logs: '🗑 クリア',
    logs_copied: '✓ ログをクリップボードにコピーしました！',
    vpn_connected: '✓ 接続中 (タップで切断)',
    vpn_disconnected: '未接続 (タップで接続)',
    vpn_connecting: '⚡ 接続中...',
    lines_unit: ' 行',
    toast_read_file_failed: '✕ ファイルの読み込みに失敗しました',
    badge_encrypted_detected: '🔒 暗号化データを検出（PINが必要）',
    badge_plain_detected: '✓ 平文設定を読み込みました（{count} 個のノード）',
    err_empty_content: '✕ インポート内容を入力してください',
    err_pin_required: '✕ 暗号化データを検出しました。PINを入力してください',
    err_invalid_pin: '✕ PINコードが正しくないか復号に失敗しました',
    err_invalid_format: '✕ 有効なノード設定フォーマットを認識できませんでした',

    // MCP 設定とダッシュボード
    settings_mcp_title: '🤖 Model Context Protocol (MCP) AI エージェントサーバー',
    label_mcp_enable: 'MCP AI エージェントサーバーを有効化',
    desc_mcp_enable: 'Claude Desktop、Cursor、Gemini などの AI エージェントが MCP プロトコルまたは Gemini Function Calling でデバイスをリモート制御できます',
    label_mcp_port: 'MCP 統合サービスポート (HTTP/HTTPS 共用)：',
    label_mcp_auth_mode: 'MCP アクセス認証モード：',
    opt_mcp_auth_none: '🟢 認証なし (LAN 直接アクセス)',
    opt_mcp_auth_apikey: '🔑 API Key (Bearer Token)',
    opt_mcp_auth_basic: '👤 HTTP Basic (ユーザー名 & パスワード)',
    opt_mcp_auth_oauth: '🛡️ OAuth 2.0 (Token / Code 認可)',
    label_mcp_secret: 'MCP アクセスキー / シークレット：',
    mcp_secret_placeholder: '空欄でパスワードなし・デフォルトキー使用',
    mcp_stat_status: '📡 MCP サービス状態',
    mcp_stat_port: '🔌 リスニングポート',
    mcp_stat_proto: '⚡ プロトコルとモード',
    mcp_stat_tools: '🛠️ 利用可能な AI ツール数',
    mcp_dash_title: '🚀 Stun MCP & Gemini インタラクティブダッシュボード',
    mcp_dash_desc: 'SSL CA 証明書ダウンロード・OAuth 2.0 認可・リアルタイム SSE モニター・多言語対応を含む',
    btn_open_mcp_dash: '🚀 MCP ダッシュボードを開く',
    mcp_claude_title: '💻 Claude Desktop / Cursor / Windsurf 連携設定',
    mcp_claude_desc: '以下を <code>claude_desktop_config.json</code> に貼り付けてください：',
    btn_copy_mcp_config: '📋 設定をコピー',
    mcp_gemini_title: '🤖 Google Gemini 2.0 Flash / 1.5 Pro 直接接続 (Python)',
    mcp_gemini_desc: 'ネイティブ Function Calling で Python 3 行から Stun を制御：',
    btn_copy_gemini_snippet: '📋 コードをコピー',
    mcp_tools_title: '🛠️ 登録済み 18 種の AI コア制御機能',
    mcp_gemini_c1: '# 1. Stun MCP が宣言した 18 個のツールを動的に取得',
    mcp_gemini_c2: '# 2. Gemini 2.0 Flash / Pro 自動ツール呼び出しの初期化',
    mcp_gemini_c3: '# 3. AI が Stun 制御機能を自動的に呼び出す命令を送信',
    mcp_gemini_prompt: 'Stun VPN の状態を確認し、全ノードの遅延を測定してください',
    toast_mcp_config_copied: '✓ MCP JSON 設定をクリップボードにコピーしました！',
    toast_gemini_snippet_copied: '✓ Gemini Python スニペットをコピーしました！',
    subscription_title: '📡 購読管理',
    subscription_url_placeholder: 'https://example.com/sub または購読リンク',
    btn_sub_sync: '🔄 今すぐ同期',
    label_sub_last_sync: '前回の同期：',
    sub_never_synced: '未同期',
    subscription_syncing: '同期中...',
    subscription_sync_success: '✓ 購読を同期しました、{count} ノードをインポート',
    subscription_sync_error: '✕ 購読の同期に失敗しました：{msg}',
    subscription_url_empty: '✕ 購読リンクを入力してください',
    subscription_pin_hint: 'PIN（任意、暗号化された購読用）',
    subscription_pin_short: 'PIN',
    subscription_pin_required: '✕ この購読はPINで暗号化されています。PINを入力してください',
    subscription_pin_invalid: '✕ PINが違うか、復号に失敗しました',
    subscription_add: '➕ 購読を追加',
    subscription_sync_partial: '✓ {ok}/{total} 件の購読を同期、{count} ノードをインポート',

  },
  'de': {
    edit_sec_1_title: "🖥️ Grundverbindung & SSH-Authentifizierung",
    edit_sec_2_title: "🚀 Transportprotokoll & Parameter",
    edit_sec_3_title: "🛡️ Sicherheit & Fingerabdruckprüfung",
    edit_sec_4_title: "🌐 Routing- & App-Filter-Überschreibungen",
    edit_name_placeholder: "z.B. Tokio Highspeed-Knoten",
    edit_ssh_addr_placeholder: "IP:Port oder Domain:Port",
    edit_user_placeholder: "z.B. root",
    edit_pass_placeholder: "Leer lassen für unverändertes Passwort",
    edit_key_pass_placeholder: "Leer lassen wenn unverschlüsselt",
    edit_proxy_addr_placeholder: "IP:Port oder Domain:Port",
    edit_custom_host_placeholder: "z.B. cloudflare.com",
    edit_server_name_placeholder: "TLS-Handshake SNI-Domain",
    edit_custom_path_placeholder: "/pfad/zum/stream",
    edit_udp_psk_placeholder: "Vorinstallierter Schlüssel (PSK)",
    edit_noise_pk_placeholder: "Base64 oder Hex Öffentlicher Schlüssel",
    edit_ssh_fp_placeholder: "SHA256:... oder MD5:...",
    edit_cert_fp_placeholder: "AA:BB:CC:DD...",
    edit_auth_token_placeholder: "z.B. Token_Secret_888",
    btn_fetch_ssh_fp_title: "SSH-Fingerabdruck abrufen und automatisch eintragen",
    btn_details_ssh_title: "Detaillierte SSH-Hostschlüssel-Informationen anzeigen",
    btn_fetch_cert_fp_title: "TLS-Zertifikatsfingerabdruck abrufen und eintragen",
    btn_details_cert_title: "Detaillierte TLS-Zertifikatsinformationen anzeigen",

    info_target_address: "Zieladresse",
    info_server_banner: "Server-Banner",
    info_public_key_type: "Öffentlicher Schlüsseltyp",
    info_handshake_latency: "Handshake-Latenz",
    info_sha256_fingerprint: "SHA-256-Fingerabdruck",
    info_md5_fingerprint: "MD5-Fingerabdruck",
    info_sha1_fingerprint: "SHA-1-Fingerabdruck",
    info_sni: "SNI-Domain",
    info_subject: "Betreff (Subject)",
    info_issuer: "Aussteller (Issuer)",
    info_sans: "Alternative Namen (SANs)",
    info_cert_validity: "Zertifikatsgültigkeit",
    info_cert_expired: "❌ Abgelaufen",
    info_days_remaining: "✓ {days} Tage verbleibend",
    info_signature_alg: "Signaturalgorithmus",
    info_public_key_alg: "Schlüsselalgorithmus",
    info_tls_version: "TLS-Version",
    info_alpn_negotiation: "ALPN-Aushandlung",
    info_duration_ms: "Dauer: {ms} ms",

    opt_tunnel_tls: "TLS (Standard-TLS-Verschlüsselungstunnel)",
    opt_tunnel_ws: "WS (WebSocket-Klartext)",
    opt_tunnel_wss: "WSS (WebSocket über TLS)",
    opt_tunnel_h2: "H2 (HTTP/2 über TLS)",
    opt_tunnel_h2c: "H2C (HTTP/2 Klartext)",
    opt_tunnel_http: "HTTP (HTTP-Proxy-Tunnel)",
    opt_tunnel_base: "BASE (Direkter TCP-Tunnel)",
    opt_tunnel_quic: "QUIC (QUIC-Datagramm)",
    opt_tunnel_grpc: "gRPC (gRPC über TLS)",
    opt_tunnel_grpcc: "gRPCC (gRPC Klartext)",
    opt_tunnel_h3: "H3 (HTTP/3 über QUIC)",
    opt_tunnel_wt: "WebTransport",
    opt_tunnel_masque: "MASQUE (HTTP/3 IP-Proxy)",
    opt_tunnel_xhttp: "XHTTP (Streaming-gemultiplextes HTTP)",
    opt_tunnel_xhttpc: "XHTTPC (XHTTP Klartext)",
    opt_tunnel_dns: "DNS Tunnel (SSH-über-DNS)",
    opt_tunnel_kcp: "KCP (SSH-über-KCP Paketverlustschutz)",
    opt_tunnel_udpc: "UDP Custom (Benutzerdefinierter verschleierter UDP-Tunnel)",
    opt_alpn_h3_h2: "h3,h2 (H3 bevorzugt / Automatischer Fallback)",
    opt_alpn_h2_h3: "h2,h3 (H2 bevorzugt / Automatischer Fallback)",
    opt_alpn_h3: "h3 (Nur HTTP/3)",
    opt_alpn_h2: "h2 (Nur HTTP/2)",
    opt_dns_txt: "TXT (Empfohlen)",
    details_modal_title: "ℹ️ Detaillierte Informationen",
    btn_details_copy: "📋 Kopieren",
    btn_details_apply: "✓ Anwenden",
    btn_details_close: "Schließen",
    label_edit_noise_public_key: "Noise-Server-Öffentlicher-Schlüssel (Optional, Curve25519 Hex/Base64):",

    label_edit_verify_ssh_fp: '🔑 SSH-Hostschlüssel-Fingerabdruck verifizieren',
    label_edit_ssh_fp: 'SSH-Fingerabdruck (SHA256 / MD5):',
    btn_fetch_fp: '🔍 Abrufen',
    btn_details: 'ℹ️ Details',
    ssh_details_title: 'ℹ️ SSH-Server-Hostschlüssel-Details',
    tls_details_title: 'ℹ️ TLS-Serverzertifikat-Details',
    toast_ssh_fp_success: '✓ SSH-Host-Fingerabdruck erfolgreich abgerufen!',
    toast_cert_fp_success: '✓ TLS-Zertifikat-Fingerabdruck erfolgreich abgerufen!',
    toast_missing_ssh_addr: '✕ Bitte geben Sie zuerst die SSH-Serveradresse an',
    toast_missing_proxy_addr: '✕ Bitte geben Sie zuerst die Proxy- oder SSH-Adresse an',
    toast_fetch_failed: '✕ Abruf fehlgeschlagen: {msg}',
    toast_details_copied: '✓ Details in die Zwischenablage kopiert!',
    toast_fp_applied: '✓ Fingerabdruck angewendet und Prüfung aktiviert!',

    page_title: '🦊 Stun · Web-Konsole',
    theme_toggle_title: 'Thema wechseln',
    toast_vpn_connecting: 'Verbindung zu VPN wird hergestellt...',
    toast_vpn_disconnected: 'VPN-Verbindung getrennt',
    toast_switched_node: '✓ Zu Knoten "{name}" gewechselt',
    toast_deleted_node: '✓ Knoten "{name}" gelöscht',
    toast_enter_pin: '✕ Bitte PIN-Code eingeben',
    toast_export_failed: '✕ Export fehlgeschlagen, bitte erneut versuchen',
    toast_logs_cleared: '✓ Protokolle gelöscht',
    toast_profile_updated: '✓ Knotenkonfiguration "{name}" gespeichert!',
    error_invalid_address: "Ungültiges Adressformat (Host:Port oder Host:Portbereich)",
    error_invalid_address_single_port: "Ungültiges Adressformat (Host:Port). Portbereich wird für dieses Protokoll nicht unterstützt.",
    hint_proxy_range: "UDP Custom unterstützt Portbereiche (z. B. 1.1.1.1:1024-23000). Jedes Paket wird an einen anderen Port gesendet, um Einzel-Port-Ratenbegrenzungen zu umgehen.",
    toast_profile_update_failed: '✕ Speichern des Knotens fehlgeschlagen.',
    error_udp_psk_required: "UDP Custom PSK ist erforderlich",
    error_udp_magic: "Magic muss genau 4 UTF-8-Bytes enthalten, z. B. UDPC",
    error_noise_public_key: "Der Public Key muss 64 Hex-Zeichen oder 32-Byte-Base64 sein",
    error_dns_noise_record_type: "Noise-Verschlüsselung kann keine A-/AAAA-Records nutzen",
    error_xhttp_chunk_size: "Chunk-Größe muss 0 oder zwischen 16 und 900 KB liegen",
    error_invalid_host_only: "Gegenüber-IP eingeben (ICMP hat keinen Port, z. B. 1.1.1.1 oder IPv6)",
    error_invalid_dns_servers: "Mindestens einen DNS-Server eingeben (durch Komma oder Zeilenumbruch getrennt; Präfixe udp/tcp/tls/dot/https:// erlaubt)",
    error_invalid_path: "Pfad muss mit / beginnen",
    error_invalid_number: "Gültige Ganzzahl eingeben",
    error_invalid_mtu_probe: "MTU-Erkennung muss leer, auto, on/true/1 oder off/false/0 sein",
    error_invalid_masque_alpn: "Masque-ALPN muss auto, h3 oder h2 sein",
    warn_psk_short: "PSK kürzer als 16 Zeichen - schwächere Sicherheit",
    warn_xhttp_no_fingerprint: "xHTTP hat keinen gepinnten Zertifikats-Fingerabdruck - MITM-Risiko",
    label_edit_padding_min_bytes: "Min. Füllbytes (0 = Standard 1420, negativ = aus):",
    label_edit_masque_alpn: "Masque-ALPN (nur masque):",
    error_heartbeat_interval: "Heartbeat muss 0 oder zwischen 5000 und 300000 ms liegen",
    file_loaded_toast: '✓ Datei "{name}" erfolgreich geladen',
    btn_test_latency_all: '⚡ Alle Latenzen testen',
    btn_ping: '⚡ Ping',
    toast_latency_tested: '✓ Knoten "{name}" Latenz: {delay}',
    toast_all_latency_tested: '✓ Alle Latenztests abgeschlossen!',
    toast_latency_failed: '✕ Latenztest fehlgeschlagen.',
    tab_overview: '📊 Übersicht',
    tab_profiles: '🚀 Knoten',
    tab_conntrack: '🔍 Verbindungen',
    tab_settings: '⚙️ Einstellungen',
    tab_mcp: '🤖 MCP & KI',
    tab_logs: '📜 Live-Protokolle',
    stat_selected: '🎯 Aktiver Knoten',
    stat_speed: '⚡ Live-Geschwindigkeit',
    stat_total: 'Σ Gesamtverkehr',
    stat_filter: '🔀 App-Routing',
    server_banner_title: '🖥️ Server-Banner',
    server_banner_version_label: 'SSH-Server-Kennung',
    stat_active_conns: '🔌 Aktive Verbindungen',
    stat_total_conns: '📊 Gesamtverbindungen',
    stat_route_hit_rate: '🎯 Routing-Trefferrate',
    conntrack_title: '🔍 Aktive Verbindungsüberwachung',
    domain_ranking_title: '🌐 Top-Domain-Aktivität',
    search_conns_placeholder: 'Nach Ziel, Domain oder Port suchen...',
    no_active_conns: 'Derzeit keine aktiven Verbindungen',
    no_domain_activity: 'Keine Domain-Aktivitätsdaten',
    pagination_info: 'Seite {page} von {totalPages} (Gesamt {total})',
    btn_page_first: '⏮ Erste',
    btn_page_prev: '◀ Zurück',
    btn_page_next: 'Weiter ▶',
    btn_page_last: 'Letzte ⏭',
    btn_refresh: '🔄 Aktualisieren',
    th_target: 'Zieladresse',
    th_proxy: 'Ausgangs-Proxy',
    th_traffic: 'Verkehr (↑ / ↓)',
    th_duration: 'Dauer',
    stat_unselected: 'Keiner ausgewählt',
    stat_not_configured: 'Nicht konfiguriert',
    quick_profiles: '🚀 Schnellknoten',
    btn_manage_all: 'Alle verwalten',
    btn_export_backup: '📦 Verschlüsseltes Backup',
    btn_add_profile: '➕ Knoten hinzufügen',
    profiles_title: '🚀 Gespeicherte Knoten',
    no_profiles: 'Keine Knoten gefunden. Klicken Sie auf "Knoten hinzufügen".',
    btn_select_this: 'Knoten verwenden',
    badge_selected: '✓ Aktiv',
    btn_edit_profile: '✏️ Bearbeiten',
    btn_delete: 'Löschen',
    confirm_delete: 'Knoten "{name}" wirklich löschen?',
    modal_add_title: '➕ Knoten hinzufügen / importieren',
    file_drop_title: 'Klicken zum Auswählen oder Datei hierher ziehen',
    file_drop_desc: 'Unterstützt .json, .txt, .bak oder verschlüsselte Backups',
    or_paste_text: 'Oder Text direkt einfügen',
    import_placeholder: 'Verschlüsselten Freigabecode, Backup-Text oder JSON-Konfiguration einfügen...',
    import_pin_label: '🔒 Entschlüsselungs-PIN:',
    import_pin_placeholder: 'PIN bei verschlüsselten Daten eingeben',
    pin_detected_badge: '✓ Verschlüsselte Daten erkannt',
    btn_cancel: 'Abbrechen',
    btn_import_now: 'Importieren',
    import_success: '✓ {count} Knoten erfolgreich importiert!',
    import_failed: '✕ Import fehlgeschlagen. Bitte PIN oder Inhalt prüfen.',
    import_empty_error: '✕ Bitte Daten einfügen oder Datei laden.',
    modal_export_title: '📦 Verschlüsseltes Backup exportieren',
    export_pin_label: 'Verschlüsselungs-PIN festlegen (Buchstaben/Ziffern, min. 4):',
    export_pin_placeholder: 'PIN eingeben (Buchstaben/Ziffern, min. 4)',
    btn_gen_pin: '🎲 Zufällige PIN',
    export_result_label: 'Verschlüsselte Daten (Base64):',
    btn_export_cancel: 'Schließen',
    btn_export_exec: 'Backup erstellen',
    btn_export_copy: '📋 Kopieren',
    export_success: '✓ Backup erstellt! PIN: {pin}',
    export_copied: '✓ In Zwischenablage kopiert!',
    modal_edit_title: '✏️ Knotenkonfiguration bearbeiten',
    label_edit_name: 'Knotenname:',
    label_edit_note: "Notiz:",
    label_edit_favorite: "⭐ Diesen Knoten favorisieren",
    edit_note_placeholder: "Optional, nur auf diesem Gerät sichtbar",
    badge_from_subscription: "📡 Abo",
    filter_tab_all: "Alle ({count})",
    filter_tab_favorites: "Favoriten ({count})",
    filter_tab_recent: "Zuletzt ({count})",
    filter_empty: "Keine Knoten für diesen Filter",
    fav_add: "Favorisieren",
    fav_remove: "Aus Favoriten entfernen",
    label_edit_ssh_addr: 'SSH-Serveradresse:',
    label_edit_auth_type: 'SSH-Authentifizierung:',
    opt_auth_password: 'Passwort-Authentifizierung',
    opt_auth_key: 'Schlüssel-Authentifizierung (Private Key)',
    label_edit_user: 'SSH-Benutzername:',
    label_edit_pass: 'SSH-Passwort:',
    label_edit_key_pass: 'Schlüssel-Passphrase:',
    label_edit_private_key: 'Privater Schlüssel (OpenSSH / RSA / Ed25519):',
    label_edit_tunnel_type: 'Tunnelprotokoll (Tunnel Type):',
    label_edit_proxy_addr: 'Proxy-Adresse (Proxy Addr):',
    label_edit_proxy_icmp: 'ICMP-Peer-IP (ohne Port):',
    label_edit_custom_host: 'Benutzerdefinierter Host:',
    label_edit_server_name: 'SNI-Domain (Server Name):',
    label_edit_custom_path: 'Benutzerdefinierter Pfad (Custom Path):',
    label_edit_alpn: 'ALPN-Verhandlung (ALPN):',
    label_edit_http_payload: 'HTTP-Payload (Anfrage-Template):',
    label_edit_disable_status_check: 'Strenge HTTP 200 Prüfung deaktivieren',
    label_edit_verify_cert: '🔒 Server-Zertifikat SHA-256 Fingerabdruck prüfen',
    label_edit_cert_fp: 'Zertifikat SHA-256 Fingerabdruck:',
    label_edit_proxy_auth: '🔑 Proxy-Authentifizierung aktivieren',
    label_edit_auth_token: 'Proxy-Auth-Token:',
    label_edit_auth_user: 'Proxy-Benutzername:',
    label_edit_auth_pass: 'Proxy-Passwort:',
    label_edit_dns_servers: 'DNS-Server (kommagetrennt):',
    label_edit_dns_domain: 'DNS-Domain (Tunnel-Domain):',
    label_edit_dns_type: 'DNS-Eintragstyp (Record Type):',
    label_edit_dns_psk: 'DNS-Tunnel-PSK:',
    label_edit_dns_marker: 'Tunnel-Marker:',
    error_field_required: 'Pflichtfeld',
    error_invalid_server_name: 'Servername darf keine Leerzeichen enthalten',
    error_invalid_fingerprint: 'Ungültiger SHA-256-Fingerabdruck (64 Hex-Zeichen, Doppelpunkte optional)',
    error_invalid_marker: 'Marker darf keine Leerzeichen enthalten (max. 32 Zeichen)',
    error_icmp_psk: 'ICMP-PSK erforderlich (oder SSH-Passwort als Ersatz)',
    error_icmp_magic: 'Magic muss 8 Hex-Ziffern oder 0x-Präfix sein',
    error_kcp_password: 'KCP-Passwort ist erforderlich',
    label_edit_kcp_pass: 'KCP-Passwort:',
    label_edit_kcp_crypt: 'KCP-Verschlüsselung:',
    label_edit_kcp_data_shards: 'Data Shards (Daten-Shards):',
    label_edit_kcp_parity_shards: 'Parity Shards (Paritäts-Shards):',
    label_edit_kcp_nodelay: 'KCP NoDelay Schnellmodus aktivieren',
    label_kcp_mode: 'KCP-Modus (fast = Standard):',
    label_kcp_sndwnd: 'Sendefenster (0 = Standard 128):',
    label_kcp_rcvwnd: 'Empfangsfenster (0 = Standard 512):',
    label_kcp_mtu: 'MTU (0 = Standard 1350):',
    label_kcp_nocomp: 'Snappy-Kompression:',
    label_kcp_smuxver: 'SMUX-Version (0 = Standard 2):',
    label_kcp_keepalive: 'KeepAlive Sekunden (0 = Standard 10):',
    opt_kcp_mode_fast: 'Fast (Standard)',
    opt_kcp_mode_normal: 'Normal',
    opt_kcp_mode_fast2: 'Fast2',
    opt_kcp_mode_fast3: 'Fast3',
    opt_kcp_nocomp_off: 'Kompression an',
    opt_kcp_nocomp_on: 'Kompression aus',
    label_edit_udp_custom_psk: 'UDP Custom PSK:',
    label_edit_udp_custom_magic: 'Magic-Header (genau 4 UTF-8-Bytes):',
    label_edit_dns_edns0: 'EDNS0 (1232-Byte-Antworten, muss zum Server passen)',
    label_edit_udp_custom_sockets: 'UDP-Sockets (0 = Standard 1):',
    label_edit_udp_custom_paths: 'Remote-UDP-Pfade (0 = Standard 32):',
    label_edit_udp_custom_send_window: 'Sendefenster (0 = Standard 256):',
    label_edit_xhttp_chunk: 'Chunk-Größe KB (0 = Standard 256, Bereich 16-900):',
    label_edit_xhttp_stream_mode: 'XHTTP-Stream-Modus:',
    label_edit_bind_interface: 'Interface-Bindung (z. B. wlan0, leer = Standard):',
    webdav_title: '☁️ WebDAV-Cloud-Backup',
opt_tunnel_raw: 'RAW (direktes TCP, TLS optional)',
opt_tunnel_websocket: 'WebSocket (TLS optional)',
opt_tunnel_webtransport: 'WebTransport',
label_tunnel_tls: '🔒 TLS-Verschlüsselung',
    label_webdav_url: 'WebDAV-URL (Sicherungen im Ordner Stun):',
    label_webdav_user: 'Konto:',
    label_webdav_pass: 'Passwort / App-Passwort:',
    label_webdav_pin: 'Backup-PIN (Buchstaben/Ziffern, min. 4):',
    label_webdav_auto: 'Tägliche automatische Sicherung:',
    label_webdav_last: 'Letztes Backup:',
    label_webdav_interval: 'Auto-Backup-Intervall (Stunden):',
    opt_webdav_auto_off: 'Aus',
    opt_webdav_auto_on: 'Ein',
    btn_webdav_backup: '☁️ Jetzt sichern',
    btn_webdav_save: '💾 Konfiguration speichern',
    btn_webdav_restore: '📥 Aus der Cloud wiederherstellen',
    webdav_saved: '✓ WebDAV-Konfiguration gespeichert',
    webdav_backup_ok: '✓ WebDAV-Backup abgeschlossen ({count} Knoten; {sections})',
    webdav_backup_ok_plain: '✓ WebDAV-Backup abgeschlossen ({count} Knoten)',
    webdav_pin_too_short: 'Der Backup-PIN muss mindestens 4 Zeichen lang sein',
    webdav_restore_ok: '✓ {count} Knoten aus WebDAV wiederhergestellt',
    webdav_restore_ok_full: '✓ {count} Knoten und {sections} aus WebDAV wiederhergestellt',
    webdav_picker_title: '📥 Sicherung zum Wiederherstellen auswählen',
    webdav_no_backups: 'Keine Sicherungen auf dem Server gefunden',
    webdav_restore_confirm: 'Knoten (nach ID zusammengeführt) und globale Einstellungen (ohne Backup-PIN) aus der Cloud wiederherstellen? Fortfahren?',
    opt_tunnel_icmp: 'ICMP Custom (SSH-over-ICMP)',
    label_icmp_psk: 'PSK (Pre-Shared Key):',
    label_icmp_magic: 'Magic (8 Hex-Zeichen, leer = Standard):',
    ph_icmp_custom_magic: 'leer = Standard (8 Hex oder 0x)',
    label_icmp_family: 'IP-Familie:',
    label_icmp_mtu: 'MTU-Modus:',
    label_icmp_max_payload: 'Max. Payload (0 = Standard):',
    label_icmp_pace: 'Sendepause ms (0 = Standard):',
    label_icmp_id_range: 'Echo-ID-Pool (z. B. 1000-1999):',
    opt_icmp_family_auto: 'Auto',
    opt_icmp_family_v4: 'IPv4',
    opt_icmp_family_v6: 'IPv6',
    opt_icmp_mtu_probe: 'Probe',
    opt_icmp_mtu_auto: 'Auto',
    opt_icmp_mtu_fixed: 'Fixed',
    webdav_failed_generic: '✕ WebDAV-Vorgang fehlgeschlagen',
    opt_stream_auto: 'Auto (Streaming mit Polling-Fallback)',
    opt_stream_stream: 'Stream (erzwungenes Streaming)',
    opt_stream_poll: 'Poll (erzwungenes Polling)',
    label_edit_heartbeat_interval: 'Heartbeat-Intervall ms (0 = Standard 25000):',
    label_edit_dns_override: '🌐 Eigenes DNS & Direktes Routing für diesen Knoten',
    label_edit_remote_dns: 'Eigener Remote-DNS:',
    label_edit_local_dns: 'Eigener lokaler DNS:',
    label_edit_udpgw_version: 'Eigene UDPGW-Engine:',
    label_edit_udpgw_addr: 'Eigene UDPGW-Adresse:',
    label_edit_geosite_direct: 'GeoSite Direkt-Tags:',
    label_edit_geoip_direct: 'GeoIP Direkt-Tags:',
    label_edit_app_override: '🔀 Eigenes App-Split-Tunneling für diesen Knoten',
    node_mode_disallow: '🚫 <b>Bypass-Modus</b> (Ausgewählte Apps umgehen)',
    node_mode_allow: '🚀 <b>Proxy-Modus</b> (Nur ausgewählte Apps weiterleiten)',
    btn_save_node_edit: '💾 Knoten speichern',
    filter_title: '🔀 Globales App-Split-Tunneling',
    btn_save_filter: '💾 Nur App-Filter speichern',
    mode_disallow: '🚫 <b>Bypass-Modus</b> (Ausgewählte Apps umgehen)',
    mode_allow: '🚀 <b>Proxy-Modus</b> (Nur ausgewählte Apps weiterleiten)',
    search_placeholder: 'App-Name oder Paket suchen...',
    btn_select_all: 'Alle auswählen / abwählen',
    loading_apps: 'Installierte Apps laden...',
    no_apps: 'Keine Apps gefunden',
    filter_save_success: '✓ Split-Tunneling-Einstellungen gespeichert!',
    filter_save_failed: '✕ Speichern fehlgeschlagen, bitte erneut versuchen.',
    filter_status_allow: 'Proxy-Modus ({count} Apps)',
    filter_status_disallow: 'Bypass-Modus ({count} Apps)',
    btn_save_all_settings: '💾 Alle Einstellungen speichern',
    settings_core_title: '🚀 Betriebsmodus & Kernnetzwerk',
    label_service_mode: 'Betriebsmodus:',
    opt_mode_vpn: 'VPN-Modus (Standard)',
    opt_mode_tproxy: 'Transparenter Proxy-Modus (TProxy)',
    label_log_level: 'Protokollstufe:',
    opt_log_debug: 'DEBUG (Ausführlich)',
    opt_log_info: 'INFO (Normal - Empfohlen)',
    opt_log_warn: 'WARN (Nur Warnungen)',
    opt_log_error: 'ERROR (Nur Fehler)',
    label_remote_dns: 'Remote-DNS-Server:',
    label_local_dns: 'Lokaler DNS-Server:',
    settings_udpgw_title: '📡 UDP-Gateway-Einstellungen',
    label_udpgw_version: 'UDPGW-Engine:',
    opt_udpgw_tun2proxy: 'tun2proxy (Hochleistungs-Rust - Standard)',
    opt_udpgw_badvpn: 'badvpn (Legacy-Kompatibilität)',
    label_udpgw_addr: 'UDPGW-Adresse:',
    settings_geodata_title: '🌐 GeoData & Direktes Routing',
    btn_update_geodata: '🔄 GeoData jetzt aktualisieren',
    label_geosite_direct: 'GeoSite Direkt-Tags:',
    label_geoip_direct: 'GeoIP Direkt-Tags:',
    label_geosite_url: 'GeoSite-Datenbank-URL:',
    label_geoip_url: 'GeoIP-Datenbank-URL:',
    label_update_interval: 'Aktualisierungsintervall (Sekunden):',
    label_last_update: 'Zuletzt aktualisiert:',
    never_updated: 'Nie aktualisiert',
    geodata_updating: 'GeoData wird aktualisiert...',
    geodata_update_success: '✓ GeoData erfolgreich aktualisiert!',
    geodata_update_failed: '✕ GeoData-Aktualisierung fehlgeschlagen.',
    settings_system_title: '🔔 System & Benachrichtigungen',
    label_show_speed: 'Live-Geschwindigkeit in Benachrichtigung anzeigen',
    settings_title: '🛡️ Web-Konsole Sicherheit & Token-Authentifizierung',
    auth_mode_0_title: '<b>🎲 Zufällig bei jedem Neustart (Random on Start)</b>',
    auth_mode_0_desc: 'Höchste Sicherheit. Erzeugt bei jedem Start ein neues 8-stelliges Token.',
    auth_mode_1_title: '<b>🔒 Fester Token / Dauerhaft (Fixed Once / Permanent)</b>',
    auth_mode_1_desc: 'Dauerhaft gespeichertes Token, ideal für Lesezeichen und Startbildschirm-Verknüpfungen.',
    auth_mode_2_title: '<b>✏️ Benutzerdefinierter Token (Custom Token)</b>',
    auth_mode_2_desc: 'Eigenes Passwort festlegen (z. B. foxvpn, 888888).',
    auth_mode_3_title: '<b>🌐 Authentifizierung deaktivieren (Offenes WLAN)</b>',
    auth_mode_3_desc: 'Zugriff im lokalen WLAN ohne ?token= Parameter möglich.',
    custom_token_label: 'Benutzerdefiniertes Token:',
    custom_token_placeholder: 'Passwort eingeben (z. B. 123456)',
    current_url_label: '🔗 Aktuelle Web-Zugriffs-URL',
    btn_copy_url: '📋 URL kopieren',
    url_copied: '✓ URL in die Zwischenablage kopiert!',
    settings_save_success: '✓ Alle Einstellungen wurden gespeichert!',
    settings_save_failed: '✕ Speichern fehlgeschlagen.',
    btn_autoscroll_on: '⬇ Auto-Scroll: AN',
    btn_autoscroll_off: '⬇ Auto-Scroll: AUS',
    btn_copy_logs: '📋 Protokolle kopieren',
    btn_clear_logs: '🗑 Leeren',
    logs_copied: '✓ Protokolle in Zwischenablage kopiert!',
    vpn_connected: '✓ Verbunden (Tippen zum Trennen)',
    vpn_disconnected: 'Getrennt (Tippen zum Verbinden)',
    vpn_connecting: '⚡ Verbinde...',
    lines_unit: ' Zeilen',
    toast_read_file_failed: '✕ Fehler beim Lesen der Datei',
    badge_encrypted_detected: '🔒 Verschlüsselte Daten erkannt (PIN erforderlich)',
    badge_plain_detected: '✓ Klartext-Konfiguration geladen ({count} Knoten)',
    err_empty_content: '✕ Import-Inhalt darf nicht leer sein',
    err_pin_required: '✕ Verschlüsselte Daten erkannt. Bitte PIN eingeben',
    err_invalid_pin: '✕ Ungültige PIN oder Entschlüsselung fehlgeschlagen',
    err_invalid_format: '✕ Ungültiges Knotenkonfigurationsformat',

    // MCP-Einstellungen & Dashboard
    settings_mcp_title: '🤖 Model Context Protocol (MCP) KI-Agenten-Server',
    label_mcp_enable: 'MCP KI-Agenten-Server aktivieren',
    desc_mcp_enable: 'Erlaubt Claude Desktop, Cursor, Gemini und anderen KI-Agenten, dieses Gerät über MCP oder Gemini Function Calling fernzusteuern',
    label_mcp_port: 'MCP Dienstport (HTTP/HTTPS gemeinsam):',
    label_mcp_auth_mode: 'MCP Zugriffsauthentifizierungsmodus:',
    opt_mcp_auth_none: '🟢 Keine Authentifizierung (LAN offener Zugang)',
    opt_mcp_auth_apikey: '🔑 API-Schlüssel (Bearer Token)',
    opt_mcp_auth_basic: '👤 HTTP Basic (Benutzername & Passwort)',
    opt_mcp_auth_oauth: '🛡️ OAuth 2.0 (Token / Code-Autorisierung)',
    label_mcp_secret: 'MCP Zugriffsschlüssel / Geheimnis:',
    mcp_secret_placeholder: 'Leer lassen für keinen Auth oder Standardschlüssel',
    mcp_stat_status: '📡 MCP Dienststatus',
    mcp_stat_port: '🔌 Abhör-Port',
    mcp_stat_proto: '⚡ Protokoll & Modus',
    mcp_stat_tools: '🛠️ Verfügbare KI-Werkzeuge',
    mcp_dash_title: '🚀 Stun MCP & Gemini Interaktives Dashboard',
    mcp_dash_desc: 'Enthält SSL-CA-Zertifikat-Download, OAuth 2.0-Autorisierung, Live-SSE-Monitor und Mehrsprachunterstützung',
    btn_open_mcp_dash: '🚀 MCP-Dashboard öffnen',
    mcp_claude_title: '💻 Claude Desktop / Cursor / Windsurf Integration',
    mcp_claude_desc: 'Fügen Sie Folgendes in Ihre <code>claude_desktop_config.json</code> ein:',
    btn_copy_mcp_config: '📋 Konfiguration kopieren',
    mcp_gemini_title: '🤖 Google Gemini 2.0 Flash / 1.5 Pro Direkt (Python)',
    mcp_gemini_desc: 'Steuern Sie Stun mit nur 3 Python-Zeilen über native Function Calling:',
    btn_copy_gemini_snippet: '📋 Code kopieren',
    mcp_tools_title: '🛠️ 18 registrierte KI-Kernsteuerungsfähigkeiten',
    mcp_gemini_c1: '# 1. Dynamisches Abrufen der 18 von Stun MCP deklarierten Werkzeuge',
    mcp_gemini_c2: '# 2. Initialisieren des automatischen Werkzeugaufrufs für Gemini 2.0 Flash / Pro',
    mcp_gemini_c3: '# 3. Anweisung senden, damit die KI Stun-Steuerungsfunktionen automatisch ausführt',
    mcp_gemini_prompt: 'Stun-VPN-Status prüfen und Latenz aller Knoten testen',
    toast_mcp_config_copied: '✓ MCP JSON-Konfiguration in Zwischenablage kopiert!',
    toast_gemini_snippet_copied: '✓ Gemini Python-Snippet kopiert!',
    subscription_title: '📡 Abonnement',
    subscription_url_placeholder: 'https://example.com/sub oder Abonnement-Link',
    btn_sub_sync: '🔄 Jetzt synchronisieren',
    label_sub_last_sync: 'Letzte Synchronisierung: ',
    sub_never_synced: 'Nie synchronisiert',
    subscription_syncing: 'Synchronisiere...',
    subscription_sync_success: '✓ Abonnement synchronisiert, {count} Knoten importiert',
    subscription_sync_error: '✕ Synchronisierung fehlgeschlagen: {msg}',
    subscription_url_empty: '✕ Bitte Abonnement-Link eingeben',
    subscription_pin_hint: 'PIN (optional, für verschlüsseltes Abonnement)',
    subscription_pin_short: 'PIN',
    subscription_pin_required: '✕ Dieses Abonnement ist PIN-verschlüsselt. Bitte PIN eingeben',
    subscription_pin_invalid: '✕ Falscher PIN oder Entschlüsselung fehlgeschlagen',
    subscription_add: '➕ Abonnement hinzufügen',
    subscription_sync_partial: '✓ {ok}/{total} Abonnements synchronisiert, {count} Knoten importiert',

  },
  'fr': {
    edit_sec_1_title: "🖥️ Connexion de base & Authentification SSH",
    edit_sec_2_title: "🚀 Protocole de transport & Paramètres",
    edit_sec_3_title: "🛡️ Sécurité & Vérification d'empreinte",
    edit_sec_4_title: "🌐 Routage & Remplacement de filtre d'app",
    edit_name_placeholder: "Ex: Nœud haut débit Tokyo",
    edit_ssh_addr_placeholder: "IP:Port ou Domaine:Port",
    edit_user_placeholder: "Ex: root",
    edit_pass_placeholder: "Laisser vide pour conserver le mot de passe",
    edit_key_pass_placeholder: "Laisser vide si non chiffré",
    edit_proxy_addr_placeholder: "IP:Port ou Domaine:Port",
    edit_custom_host_placeholder: "Ex: cloudflare.com",
    edit_server_name_placeholder: "Domaine SNI TLS",
    edit_custom_path_placeholder: "/chemin/du/flux",
    edit_udp_psk_placeholder: "Clé pré-partagée (PSK)",
    edit_noise_pk_placeholder: "Clé publique Base64 ou Hex",
    edit_ssh_fp_placeholder: "SHA256:... ou MD5:...",
    edit_cert_fp_placeholder: "AA:BB:CC:DD...",
    edit_auth_token_placeholder: "Ex: Token_Secret_888",
    btn_fetch_ssh_fp_title: "Récupérer et renseigner automatiquement l'empreinte SSH",
    btn_details_ssh_title: "Afficher les détails de la clé d'hôte SSH",
    btn_fetch_cert_fp_title: "Récupérer et renseigner l'empreinte du certificat TLS",
    btn_details_cert_title: "Afficher les détails du certificat TLS",

    info_target_address: "Adresse cible",
    info_server_banner: "Bannière du serveur",
    info_public_key_type: "Type de clé publique",
    info_handshake_latency: "Latence de négociation",
    info_sha256_fingerprint: "Empreinte SHA-256",
    info_md5_fingerprint: "Empreinte MD5",
    info_sha1_fingerprint: "Empreinte SHA-1",
    info_sni: "Domaine SNI",
    info_subject: "Sujet (Subject)",
    info_issuer: "Émetteur (Issuer)",
    info_sans: "Noms alternatifs (SANs)",
    info_cert_validity: "Validité du certificat",
    info_cert_expired: "❌ Expiré",
    info_days_remaining: "✓ {days} jours restants",
    info_signature_alg: "Algorithme de signature",
    info_public_key_alg: "Algorithme de clé publique",
    info_tls_version: "Version TLS",
    info_alpn_negotiation: "Négociation ALPN",
    info_duration_ms: "Durée: {ms} ms",

    opt_tunnel_tls: "TLS (Tunnel chiffré TLS standard)",
    opt_tunnel_ws: "WS (WebSocket en texte clair)",
    opt_tunnel_wss: "WSS (WebSocket sur TLS)",
    opt_tunnel_h2: "H2 (HTTP/2 sur TLS)",
    opt_tunnel_h2c: "H2C (HTTP/2 Texte clair)",
    opt_tunnel_http: "HTTP (Tunnel proxy HTTP)",
    opt_tunnel_base: "BASE (Tunnel TCP direct épuré)",
    opt_tunnel_quic: "QUIC (Datagramme QUIC)",
    opt_tunnel_grpc: "gRPC (gRPC sur TLS)",
    opt_tunnel_grpcc: "gRPCC (gRPC Texte clair)",
    opt_tunnel_h3: "H3 (HTTP/3 sur QUIC)",
    opt_tunnel_wt: "WebTransport",
    opt_tunnel_masque: "MASQUE (Proxy IP HTTP/3)",
    opt_tunnel_xhttp: "XHTTP (HTTP multiplexé en continu)",
    opt_tunnel_xhttpc: "XHTTPC (XHTTP Texte clair)",
    opt_tunnel_dns: "DNS Tunnel (SSH-sur-DNS)",
    opt_tunnel_kcp: "KCP (SSH-sur-KCP anti-perte de paquets)",
    opt_tunnel_udpc: "UDP Custom (Tunnel UDP personnalisé et obfusqué)",
    opt_alpn_h3_h2: "h3,h2 (H3 préféré / Repli automatique)",
    opt_alpn_h2_h3: "h2,h3 (H2 préféré / Repli automatique)",
    opt_alpn_h3: "h3 (HTTP/3 uniquement)",
    opt_alpn_h2: "h2 (HTTP/2 uniquement)",
    opt_dns_txt: "TXT (Recommandé)",
    details_modal_title: "ℹ️ Informations détaillées",
    btn_details_copy: "📋 Copier",
    btn_details_apply: "✓ Appliquer",
    btn_details_close: "Fermer",
    label_edit_noise_public_key: "Clé publique du serveur Noise (Optionnelle, Curve25519 Hex/Base64):",

    label_edit_verify_ssh_fp: "🔑 Vérifier l'empreinte de clé d'hôte SSH",
    label_edit_ssh_fp: 'Empreinte de clé SSH (SHA256 / MD5):',
    btn_fetch_fp: '🔍 Récupérer',
    btn_details: 'ℹ️ Détails',
    ssh_details_title: "ℹ️ Détails de la clé d'hôte du serveur SSH",
    tls_details_title: 'ℹ️ Détails du certificat de serveur TLS',
    toast_ssh_fp_success: "✓ Empreinte d'hôte SSH récupérée avec succès!",
    toast_cert_fp_success: "✓ Empreinte du certificat TLS récupérée avec succès!",
    toast_missing_ssh_addr: "✕ Veuillez d'abord fournir l'adresse du serveur SSH",
    toast_missing_proxy_addr: "✕ Veuillez d'abord fournir l'adresse du proxy ou SSH",
    toast_fetch_failed: '✕ Échec de la récupération: {msg}',
    toast_details_copied: "✓ Détails copiés dans le presse-papiers!",
    toast_fp_applied: "✓ Empreinte appliquée et vérification activée!",

    page_title: '🦊 Stun · Console Web',
    theme_toggle_title: 'Changer de thème',
    toast_vpn_connecting: 'Connexion au VPN...',
    toast_vpn_disconnected: 'VPN déconnecté',
    toast_switched_node: '✓ Basculé sur le nœud "{name}"',
    toast_deleted_node: '✓ Nœud "{name}" supprimé',
    toast_enter_pin: '✕ Veuillez entrer le code PIN',
    toast_export_failed: "✕ Échec de l\'exportation, veuillez réessayer",
    toast_logs_cleared: '✓ Journaux de console effacés',
    toast_profile_updated: '✓ Configuration du nœud "{name}" enregistrée !',
    toast_profile_update_failed: "✕ Échec de l\'enregistrement du nœud.",
    error_invalid_address: "Format d\'adresse invalide (hôte:port ou hôte:plage-de-ports)",
    error_invalid_address_single_port: "Format d\'adresse invalide (hôte:port). La plage de ports n\'est pas prise en charge pour ce protocole.",
    hint_proxy_range: "UDP Custom prend en charge les plages de ports (ex. 1.1.1.1:1024-23000). Chaque paquet est envoyé vers un port différent pour contourner les limites de débit par port.",
    error_udp_psk_required: "Le PSK UDP Custom est requis",
    error_udp_magic: "Magic doit contenir exactement 4 octets UTF-8, par ex. UDPC",
    error_noise_public_key: "La clé publique doit être 64 caractères hex ou Base64 de 32 octets",
    error_dns_noise_record_type: "Le chiffrement Noise ne peut pas utiliser d\'enregistrements A ou AAAA",
    error_xhttp_chunk_size: "La taille de chunk doit être 0, ou entre 16 et 900 Ko",
    error_invalid_host_only: "Saisissez l\'IP de la passerelle (ICMP n\'a pas de port, ex. 1.1.1.1 ou IPv6)",
    error_invalid_dns_servers: "Saisissez au moins un serveur DNS (séparés par virgule ou saut de ligne ; préfixes udp/tcp/tls/dot/https:// admis)",
    error_invalid_path: "Le chemin doit commencer par /",
    error_invalid_number: "Saisissez un entier valide",
    error_invalid_mtu_probe: "La sonde MTU doit être vide, auto, on/true/1 ou off/false/0",
    error_invalid_masque_alpn: "ALPN Masque doit être auto, h3 ou h2",
    warn_psk_short: "PSK de moins de 16 caractères - sécurité affaiblie",
    warn_xhttp_no_fingerprint: "xHTTP sans empreinte de certificat épinglée - risque d\'interception (MITM)",
    label_edit_padding_min_bytes: "Octets de remplissage min (0 = 1420 par défaut, négatif = désactivé) :",
    label_edit_masque_alpn: "ALPN Masque (masque uniquement) :",
    error_heartbeat_interval: "Le heartbeat doit être 0, ou entre 5000 et 300000 ms",
    file_loaded_toast: '✓ Fichier "{name}" chargé avec succès',
    btn_test_latency_all: '⚡ Tester toutes les latences',
    btn_ping: '⚡ Ping',
    toast_latency_tested: '✓ Nœud "{name}" latence : {delay}',
    toast_all_latency_tested: '✓ Test de latence terminé pour tous les nœuds !',
    toast_latency_failed: '✕ Échec du test de latence.',
    tab_overview: '📊 Tableau de bord',
    tab_profiles: '🚀 Nœuds',
    tab_conntrack: '🔍 Connexions',
    tab_settings: '⚙️ Paramètres',
    tab_mcp: '🤖 MCP & IA',
    tab_logs: '📜 Journaux en direct',
    stat_selected: '🎯 Nœud actif',
    stat_speed: '⚡ Débit en direct',
    stat_total: 'Σ Trafic total',
    stat_filter: '🔀 Routage des applications',
    server_banner_title: '🖥️ Bannière du serveur',
    server_banner_version_label: 'Identité du serveur SSH',
    stat_active_conns: '🔌 Connexions actives',
    stat_total_conns: '📊 Total connexions',
    stat_route_hit_rate: '🎯 Taux de cache de routage',
    conntrack_title: '🔍 Suivi des connexions actives',
    domain_ranking_title: '🌐 Activité des domaines',
    search_conns_placeholder: 'Rechercher domaine, IP ou port...',
    no_active_conns: 'Aucune connexion active pour le moment',
    no_domain_activity: 'Aucune donnée d\'activité de domaine',
    pagination_info: 'Page {page} sur {totalPages} ({total} connexions)',
    btn_page_first: '⏮ Première',
    btn_page_prev: '◀ Précédent',
    btn_page_next: 'Suivant ▶',
    btn_page_last: 'Dernière ⏭',
    btn_refresh: '🔄 Actualiser',
    th_target: 'Adresse cible',
    th_proxy: 'Proxy sortant',
    th_traffic: 'Trafic (↑ / ↓)',
    th_duration: 'Durée',
    stat_unselected: 'Aucun sélectionné',
    stat_not_configured: 'Non configuré',
    quick_profiles: '🚀 Nœuds rapides',
    btn_manage_all: 'Tout gérer',
    btn_export_backup: '📦 Sauvegarde chiffrée',
    btn_add_profile: '➕ Ajouter / Importer',
    profiles_title: '🚀 Nœuds enregistrés',
    no_profiles: 'Aucun nœud trouvé. Cliquez sur "Ajouter / Importer" ci-dessus.',
    btn_select_this: 'Utiliser ce nœud',
    badge_selected: '✓ Actif',
    btn_edit_profile: '✏️ Modifier',
    btn_delete: 'Supprimer',
    confirm_delete: 'Supprimer le nœud "{name}" ?',
    modal_add_title: '➕ Ajouter / Importer un nœud',
    file_drop_title: 'Cliquer pour sélectionner ou déposer le fichier ici',
    file_drop_desc: 'Prend en charge .json, .txt, .bak ou sauvegarde chiffrée',
    or_paste_text: 'Ou coller le texte directement',
    import_placeholder: 'Coller le code partagé chiffré, le texte de sauvegarde ou la config JSON...',
    import_pin_label: '🔒 Code PIN de déchiffrement :',
    import_pin_placeholder: 'Entrez le PIN pour les données chiffrées',
    pin_detected_badge: '✓ Données chiffrées détectées',
    btn_cancel: 'Annuler',
    btn_import_now: 'Importer',
    import_success: '✓ {count} nœud(s) importé(s) avec succès !',
    import_failed: '✕ Échec de l\'importation. Vérifiez le contenu ou le PIN.',
    import_empty_error: '✕ Veuillez coller ou charger le contenu à importer.',
    modal_export_title: '📦 Exporter la sauvegarde chiffrée',
    export_pin_label: 'Définir un code PIN de chiffrement (lettres/chiffres, 4 min.) :',
    export_pin_placeholder: 'Saisissez le code PIN (lettres/chiffres, 4 min.)',
    btn_gen_pin: '🎲 PIN aléatoire',
    export_result_label: 'Données chiffrées (Base64) :',
    btn_export_cancel: 'Fermer',
    btn_export_exec: 'Générer la sauvegarde',
    btn_export_copy: '📋 Copier',
    export_success: '✓ Sauvegarde générée ! PIN : {pin}',
    export_copied: '✓ Copié dans le presse-papiers !',
    modal_edit_title: '✏️ Modifier la configuration du nœud',
    label_edit_name: 'Nom du nœud :',
    label_edit_note: "Remarque :",
    label_edit_favorite: "⭐ Mettre ce nœud en favori",
    edit_note_placeholder: "Facultatif, affiché uniquement sur cet appareil",
    badge_from_subscription: "📡 Abonnement",
    filter_tab_all: "Tous ({count})",
    filter_tab_favorites: "Favoris ({count})",
    filter_tab_recent: "Récents ({count})",
    filter_empty: "Aucun nœud pour ce filtre",
    fav_add: "Mettre en favori",
    fav_remove: "Retirer des favoris",
    label_edit_ssh_addr: 'Adresse du serveur SSH :',
    label_edit_auth_type: "Type d\'authentification SSH :",
    opt_auth_password: 'Authentification par mot de passe',
    opt_auth_key: 'Authentification par clé privée',
    label_edit_user: "Nom d\'utilisateur SSH :",
    label_edit_pass: 'Mot de passe SSH :',
    label_edit_key_pass: 'Phrase secrète de la clé :',
    label_edit_private_key: 'Clé privée (OpenSSH / RSA / Ed25519) :',
    label_edit_tunnel_type: 'Protocole de tunnel (Tunnel Type) :',
    label_edit_proxy_addr: 'Adresse proxy (Proxy Addr) :',
    label_edit_proxy_icmp: 'IP distante ICMP (sans port) :',
    label_edit_custom_host: 'Hôte personnalisé (Custom Host) :',
    label_edit_server_name: 'Domaine SNI (Server Name) :',
    label_edit_custom_path: 'Chemin personnalisé (Custom Path) :',
    label_edit_alpn: 'Négociation ALPN (ALPN) :',
    label_edit_http_payload: 'HTTP Payload (Modèle de requête) :',
    label_edit_disable_status_check: 'Désactiver la vérification stricte du code HTTP 200',
    label_edit_verify_cert: "🔒 Vérifier l\'empreinte SHA-256 du certificat",
    label_edit_cert_fp: 'Empreinte SHA-256 du certificat :',
    label_edit_proxy_auth: "🔑 Activer l\'authentification proxy",
    label_edit_auth_token: 'Token Auth Proxy :',
    label_edit_auth_user: "Nom d\'utilisateur Proxy :",
    label_edit_auth_pass: 'Mot de passe Proxy :',
    label_edit_dns_servers: 'Serveurs DNS (séparés par des virgules) :',
    label_edit_dns_domain: 'Domaine DNS (Tunnel Domain) :',
    label_edit_dns_type: "Type d\'enregistrement DNS :",
    label_edit_dns_psk: 'PSK du tunnel DNS :',
    label_edit_dns_marker: 'Marqueur de tunnel :',
    error_field_required: 'Champ obligatoire',
    error_invalid_server_name: 'Le nom du serveur ne peut pas contenir d\'espaces',
    error_invalid_fingerprint: 'Empreinte SHA-256 invalide (64 caractères hex, deux-points optionnels)',
    error_invalid_marker: 'Le marqueur ne peut pas contenir d\'espaces (32 caractères max)',
    error_icmp_psk: 'PSK ICMP requis (ou mot de passe SSH en secours)',
    error_icmp_magic: 'Le nombre magique doit être 8 chiffres hex ou préfixé 0x',
    error_kcp_password: 'Le mot de passe KCP est obligatoire',
    label_edit_kcp_pass: 'Mot de passe KCP :',
    label_edit_kcp_crypt: 'Chiffrement KCP :',
    label_edit_kcp_data_shards: 'Data Shards (Fragments de données) :',
    label_edit_kcp_parity_shards: 'Parity Shards (Fragments de parité) :',
    label_edit_kcp_nodelay: 'Activer le mode KCP NoDelay à faible latence',
    label_kcp_mode: 'Mode KCP (fast = défaut) :',
    label_kcp_sndwnd: "Fenêtre d'émission (0 = 128 défaut) :",
    label_kcp_rcvwnd: 'Fenêtre de réception (0 = 512 défaut) :',
    label_kcp_mtu: 'MTU (0 = 1350 défaut) :',
    label_kcp_nocomp: 'Compression Snappy :',
    label_kcp_smuxver: 'Version SMUX (0 = 2 défaut) :',
    label_kcp_keepalive: 'KeepAlive secondes (0 = 10 défaut) :',
    opt_kcp_mode_fast: 'Fast (défaut)',
    opt_kcp_mode_normal: 'Normal',
    opt_kcp_mode_fast2: 'Fast2',
    opt_kcp_mode_fast3: 'Fast3',
    opt_kcp_nocomp_off: 'Compression activée',
    opt_kcp_nocomp_on: 'Compression désactivée',
    label_edit_udp_custom_psk: 'UDP Custom PSK :',
    label_edit_udp_custom_magic: 'En-tête Magic (exactement 4 octets UTF-8) :',
    label_edit_dns_edns0: 'EDNS0 (réponses 1232 octets, doit correspondre au serveur)',
    label_edit_udp_custom_sockets: 'Sockets UDP (0 = par défaut 1) :',
    label_edit_udp_custom_paths: 'Chemins UDP distants (0 = 32 par défaut) :',
    label_edit_udp_custom_send_window: 'Fenêtre d\'envoi (0 = par défaut 256) :',
    label_edit_xhttp_chunk: 'Taille de chunk Ko (0 = par défaut 256, plage 16-900) :',
    label_edit_xhttp_stream_mode: 'Mode de flux XHTTP :',
    label_edit_bind_interface: 'Interface liée (ex. wlan0, vide = par défaut) :',
    webdav_title: '☁️ Sauvegarde cloud WebDAV',
opt_tunnel_raw: 'RAW (TCP direct, TLS optionnel)',
opt_tunnel_websocket: 'WebSocket (TLS optionnel)',
opt_tunnel_webtransport: 'WebTransport',
label_tunnel_tls: '🔒 Chiffrement TLS',
    label_webdav_url: 'URL WebDAV (sauvegardes dans le dossier Stun) :',
    label_webdav_user: 'Compte :',
    label_webdav_pass: 'Mot de passe / mot de passe applicatif :',
    label_webdav_pin: 'Code PIN de sauvegarde (lettres/chiffres, 4 min.) :',
    label_webdav_auto: 'Sauvegarde auto quotidienne :',
    label_webdav_last: 'Dernière sauvegarde :',
    label_webdav_interval: 'Intervalle de sauvegarde auto (heures) :',
    opt_webdav_auto_off: 'Désactivé',
    opt_webdav_auto_on: 'Activé',
    btn_webdav_backup: '☁️ Sauvegarder maintenant',
    btn_webdav_save: '💾 Enregistrer la config',
    btn_webdav_restore: '📥 Restaurer depuis le cloud',
    webdav_saved: '✓ Configuration WebDAV enregistrée',
    webdav_backup_ok: '✓ Sauvegarde WebDAV terminée ({count} nœuds ; {sections})',
    webdav_backup_ok_plain: '✓ Sauvegarde WebDAV terminée ({count} nœuds)',
    webdav_pin_too_short: 'Le PIN de sauvegarde doit contenir au moins 4 caractères',
    webdav_restore_ok: '✓ {count} nœuds restaurés depuis WebDAV',
    webdav_restore_ok_full: '✓ {count} nœuds et {sections} restaurés depuis WebDAV',
    webdav_picker_title: '📥 Choisir une sauvegarde à restaurer',
    webdav_no_backups: 'Aucune sauvegarde sur ce serveur',
    webdav_restore_confirm: 'Restaurer depuis le cloud les nœuds (fusion par id) et les réglages globaux (hors code PIN de sauvegarde) ? Continuer ?',
    opt_tunnel_icmp: 'ICMP Custom (SSH-over-ICMP)',
    label_icmp_psk: 'PSK (clé pré-partagée) :',
    label_icmp_magic: 'Magic (8 caractères hex, vide = défaut) :',
    ph_icmp_custom_magic: 'vide = valeur par défaut (8 hex ou 0x)',
    label_icmp_family: 'Famille IP :',
    label_icmp_mtu: 'Mode MTU :',
    label_icmp_max_payload: 'Charge utile max (0 = défaut) :',
    label_icmp_pace: 'Interval d\'émission ms (0 = défaut) :',
    label_icmp_id_range: 'Pool d\'ID echo (ex. 1000-1999) :',
    opt_icmp_family_auto: 'Auto',
    opt_icmp_family_v4: 'IPv4',
    opt_icmp_family_v6: 'IPv6',
    opt_icmp_mtu_probe: 'Probe',
    opt_icmp_mtu_auto: 'Auto',
    opt_icmp_mtu_fixed: 'Fixe',
    webdav_failed_generic: '✕ Échec de l\'opération WebDAV',
    opt_stream_auto: 'Auto (streaming avec repli par polling)',
    opt_stream_stream: 'Stream (streaming forcé)',
    opt_stream_poll: 'Poll (polling forcé)',
    label_edit_heartbeat_interval: 'Intervalle de heartbeat ms (0 = par défaut 25000) :',
    label_edit_dns_override: '🌐 Activer le DNS et le routage dédiés pour ce nœud',
    label_edit_remote_dns: 'DNS distant dédié :',
    label_edit_local_dns: 'DNS local dédié :',
    label_edit_udpgw_version: 'Moteur UDPGW dédié :',
    label_edit_udpgw_addr: 'Adresse UDPGW dédiée :',
    label_edit_geosite_direct: 'Balises directes GeoSite :',
    label_edit_geoip_direct: 'Balises directes GeoIP :',
    label_edit_app_override: "🔀 Activer le tunneling d\'application dédié pour ce nœud",
    node_mode_disallow: '🚫 <b>Mode Contournement</b> (Contourner les applications sélectionnées)',
    node_mode_allow: '🚀 <b>Mode Proxy</b> (Uniquement pour les applications sélectionnées)',
    btn_save_node_edit: '💾 Enregistrer le nœud',
    filter_title: '🔀 Tunneling d\'applications global',
    btn_save_filter: '💾 Enregistrer uniquement le filtrage',
    mode_disallow: '🚫 <b>Mode Contournement</b> (Contourner les applications sélectionnées)',
    mode_allow: '🚀 <b>Mode Proxy</b> (Uniquement pour les applications sélectionnées)',
    search_placeholder: 'Rechercher une application ou un package...',
    btn_select_all: 'Tout sélectionner / désélectionner',
    loading_apps: 'Chargement des applications...',
    no_apps: 'Aucune application trouvée',
    filter_save_success: '✓ Paramètres de tunneling enregistrés !',
    filter_save_failed: '✕ Échec de l\'enregistrement, veuillez réessayer.',
    filter_status_allow: 'Mode Proxy ({count} apps)',
    filter_status_disallow: 'Mode Contournement ({count} apps)',
    btn_save_all_settings: '💾 Enregistrer tous les paramètres',
    settings_core_title: '🚀 Mode de service et réseau principal',
    label_service_mode: 'Mode de service :',
    opt_mode_vpn: 'Mode VPN (Standard)',
    opt_mode_tproxy: 'Mode Proxy transparent (TProxy)',
    label_log_level: 'Niveau de journal :',
    opt_log_debug: 'DEBUG (Débogage détaillé)',
    opt_log_info: 'INFO (Normal - Recommandé)',
    opt_log_warn: 'WARN (Avertissements uniquement)',
    opt_log_error: 'ERROR (Erreurs uniquement)',
    label_remote_dns: 'Serveur DNS distant :',
    label_local_dns: 'Serveur DNS local :',
    settings_udpgw_title: '📡 Passerelle UDP (UDPGW)',
    label_udpgw_version: 'Moteur UDPGW :',
    opt_udpgw_tun2proxy: 'tun2proxy (Rust haute performance - Par défaut)',
    opt_udpgw_badvpn: 'badvpn (Compatibilité héritée)',
    label_udpgw_addr: 'Adresse UDPGW :',
    settings_geodata_title: '🌐 Données géographiques et routage direct',
    btn_update_geodata: '🔄 Mettre à jour GeoData',
    label_geosite_direct: 'Balises directes GeoSite :',
    label_geoip_direct: 'Balises directes GeoIP :',
    label_geosite_url: 'URL de la base GeoSite :',
    label_geoip_url: 'URL de la base GeoIP :',
    label_update_interval: 'Intervalle de mise à jour (secondes) :',
    label_last_update: 'Dernière mise à jour :',
    never_updated: 'Jamais mis à jour',
    geodata_updating: 'Mise à jour de GeoData...',
    geodata_update_success: '✓ GeoData mis à jour avec succès !',
    geodata_update_failed: '✕ Échec de la mise à jour de GeoData.',
    settings_system_title: '🔔 Système et notifications',
    label_show_speed: 'Afficher la vitesse en direct dans la notification',
    settings_title: '🛡️ Sécurité de la console Web et Token',
    auth_mode_0_title: '<b>🎲 Aléatoire à chaque démarrage (Random on Start)</b>',
    auth_mode_0_desc: 'Sécurité maximale. Génère un nouveau token à chaque redémarrage.',
    auth_mode_1_title: '<b>🔒 Token fixe / Permanent (Fixed Once / Permanent)</b>',
    auth_mode_1_desc: 'Token permanent, idéal pour les favoris et raccourcis d\'écran d\'accueil.',
    auth_mode_2_title: '<b>✏️ Mot de passe personnalisé (Custom Token)</b>',
    auth_mode_2_desc: 'Définissez votre propre mot de passe (ex. foxvpn, 888888).',
    auth_mode_3_title: '<b>🌐 Désactiver l\'authentification (LAN ouvert)</b>',
    auth_mode_3_desc: 'Accès direct sans paramètre ?token= sur le réseau Wi-Fi local.',
    custom_token_label: 'Token personnalisé :',
    custom_token_placeholder: 'Entrez un mot de passe (ex. 123456)',
    current_url_label: '🔗 URL d\'accès Web active',
    btn_copy_url: "📋 Copier l\'URL",
    url_copied: '✓ URL copiée dans le presse-papiers !',
    settings_save_success: '✓ Tous les paramètres ont été enregistrés !',
    settings_save_failed: '✕ Échec de l\'enregistrement.',
    btn_autoscroll_on: '⬇ Défilement auto : OUI',
    btn_autoscroll_off: '⬇ Défilement auto : NON',
    btn_copy_logs: '📋 Copier les journaux',
    btn_clear_logs: '🗑 Effacer',
    logs_copied: '✓ Journaux copiés dans le presse-papiers !',
    vpn_connected: '✓ Connecté (Appuyer pour déconnecter)',
    vpn_disconnected: 'Déconnecté (Appuyer pour connecter)',
    vpn_connecting: '⚡ Connexion...',
    lines_unit: ' lignes',
    toast_read_file_failed: '✕ Échec de la lecture du fichier',
    badge_encrypted_detected: '🔒 Données chiffrées détectées (PIN requis)',
    badge_plain_detected: '✓ Configuration en texte clair chargée ({count} nœud(s))',
    err_empty_content: '✕ Le contenu de l\'importation ne peut pas être vide',
    err_pin_required: '✕ Données chiffrées détectées, veuillez saisir le code PIN',
    err_invalid_pin: '✕ Code PIN invalide ou échec du déchiffrement',
    err_invalid_format: '✕ Format de configuration de nœud non reconnu',

    // Paramètres MCP & Tableau de bord
    settings_mcp_title: '🤖 Model Context Protocol (MCP) Serveur agent IA',
    label_mcp_enable: 'Activer le serveur agent MCP IA',
    desc_mcp_enable: 'Permet à Claude Desktop, Cursor, Gemini et d\'autres agents IA de contrôler à distance cet appareil via MCP ou Gemini Function Calling',
    label_mcp_port: 'Port du service MCP unifié (HTTP/HTTPS) :',
    label_mcp_auth_mode: 'Mode d\'authentification d\'accès MCP :',
    opt_mcp_auth_none: '🟢 Sans auth (accès LAN ouvert)',
    opt_mcp_auth_apikey: '🔑 Clé API (Bearer Token)',
    opt_mcp_auth_basic: '👤 HTTP Basic (identifiant & mot de passe)',
    opt_mcp_auth_oauth: '🛡️ OAuth 2.0 (Token / Code grant)',
    label_mcp_secret: 'Clé secrète / accès MCP :',
    mcp_secret_placeholder: 'Laisser vide pour sans auth ou clé par défaut',
    mcp_stat_status: '📡 Statut du service MCP',
    mcp_stat_port: '🔌 Port d\'écoute',
    mcp_stat_proto: '⚡ Protocole & Mode',
    mcp_stat_tools: '🛠️ Outils IA disponibles',
    mcp_dash_title: '🚀 Tableau de bord Stun MCP & Gemini interactif',
    mcp_dash_desc: 'Inclut téléchargement CA SSL, autorisation OAuth 2.0, moniteur SSE en direct et support multilingue',
    btn_open_mcp_dash: '🚀 Ouvrir le tableau de bord MCP',
    mcp_claude_title: '💻 Intégration Claude Desktop / Cursor / Windsurf',
    mcp_claude_desc: 'Collez ce qui suit dans votre <code>claude_desktop_config.json</code> :',
    btn_copy_mcp_config: '📋 Copier la config',
    mcp_gemini_title: '🤖 Google Gemini 2.0 Flash / 1.5 Pro Direct (Python)',
    mcp_gemini_desc: 'Contrôlez Stun en 3 lignes Python via Function Calling natif :',
    btn_copy_gemini_snippet: '📋 Copier le code',
    mcp_tools_title: '🛠️ 18 capacités de contrôle IA enregistrées',
    mcp_gemini_c1: '# 1. Récupérer dynamiquement les 18 outils déclarés par Stun MCP',
    mcp_gemini_c2: '# 2. Initialiser l\'appel automatique d\'outils Gemini 2.0 Flash / Pro',
    mcp_gemini_c3: '# 3. Envoyer une instruction pour exécuter automatiquement les fonctions de contrôle Stun',
    mcp_gemini_prompt: 'Vérifier le statut du VPN Stun et tester la latence de tous les nœuds',
    toast_mcp_config_copied: '✓ Configuration JSON MCP copiée dans le presse-papiers !',
    toast_gemini_snippet_copied: '✓ Extrait Python Gemini copié !',
    subscription_title: '📡 Abonnement',
    subscription_url_placeholder: 'https://example.com/sub ou lien d\'abonnement',
    btn_sub_sync: '🔄 Synchroniser maintenant',
    label_sub_last_sync: 'Dernière synchro : ',
    sub_never_synced: 'Jamais synchronisé',
    subscription_syncing: 'Synchronisation...',
    subscription_sync_success: '✓ Abonnement synchronisé, {count} nœuds importés',
    subscription_sync_error: '✕ Échec de la synchronisation : {msg}',
    subscription_url_empty: '✕ Veuillez saisir un lien d\'abonnement',
    subscription_pin_hint: 'PIN (optionnel, pour abonnement chiffré)',
    subscription_pin_short: 'PIN',
    subscription_pin_required: '✕ Cet abonnement est chiffré par PIN. Saisissez le PIN',
    subscription_pin_invalid: '✕ PIN incorrect ou échec du déchiffrement',
    subscription_add: '➕ Ajouter un abonnement',
    subscription_sync_partial: '✓ {ok}/{total} abonnements synchronisés, {count} nœuds importés',

  }
};

function t(key, params = {}) {
  const dict = I18N[currentLang] || I18N['en'] || I18N['zh-CN'];
  let str = dict[key] || (I18N['en'] && I18N['en'][key]) || key;
  for (const [k, v] of Object.entries(params)) {
    str = str.replace(new RegExp('\\{' + k + '\\}', 'g'), v);
  }
  return str;
}

function showToast(msg) {
  const box = document.getElementById('toast-box');
  const toast = document.createElement('div');
  toast.className = 'toast-item';
  toast.textContent = msg;
  box.appendChild(toast);
  setTimeout(() => {
    toast.style.transition = 'opacity .3s, transform .3s';
    toast.style.opacity = '0';
    toast.style.transform = 'translateY(10px)';
    setTimeout(() => {
      if (box.contains(toast)) box.removeChild(toast);
    }, 300);
  }, 2800);
}

function copyToClipboard(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text);
  } else {
    const textArea = document.createElement("textarea");
    textArea.value = text;
    textArea.style.position = "fixed";
    textArea.style.left = "-9999px";
    textArea.style.top = "0";
    document.body.appendChild(textArea);
    textArea.focus();
    textArea.select();
    try {
      document.execCommand('copy');
    } catch (err) {
      console.error('Fallback copy failed', err);
    }
    document.body.removeChild(textArea);
  }
}

function applyI18n() {
  document.title = t('page_title');
  document.getElementById('theme-btn').title = t('theme_toggle_title');

  document.getElementById('t-tab-overview').textContent = t('tab_overview');
  document.getElementById('t-tab-profiles').textContent = t('tab_profiles');
  document.getElementById('t-tab-conntrack').textContent = t('tab_conntrack');
  document.getElementById('t-tab-settings').textContent = t('tab_settings');
  if (document.getElementById('t-tab-mcp')) document.getElementById('t-tab-mcp').textContent = t('tab_mcp');
  document.getElementById('t-tab-logs').textContent = t('tab_logs');

  document.getElementById('t-stat-selected').textContent = t('stat_selected');
  document.getElementById('t-stat-speed').textContent = t('stat_speed');
  document.getElementById('t-stat-total').textContent = t('stat_total');
  document.getElementById('t-stat-filter').textContent = t('stat_filter');

  // 服务端 Banner 卡片
  const bannerTitleEl = document.getElementById('t-server-banner-title');
  if (bannerTitleEl) bannerTitleEl.textContent = t('server_banner_title');
  const bannerVerEl = document.getElementById('server-banner-version');
  if (bannerVerEl) bannerVerEl.title = t('server_banner_version_label');

  document.getElementById('t-stat-active-conns').textContent = t('stat_active_conns');
  document.getElementById('t-stat-total-conns').textContent = t('stat_total_conns');
  document.getElementById('t-stat-route-hit-rate').textContent = t('stat_route_hit_rate');
  document.getElementById('t-conntrack-title').textContent = t('conntrack_title');
  document.getElementById('t-domain-ranking-title').textContent = t('domain_ranking_title');
  document.getElementById('conns-search').placeholder = t('search_conns_placeholder');
  document.getElementById('t-btn-refresh-conns').textContent = t('btn_refresh');
  document.getElementById('th-target').textContent = t('th_target');
  document.getElementById('th-proxy').textContent = t('th_proxy');
  document.getElementById('th-traffic').textContent = t('th_traffic');
  document.getElementById('th-duration').textContent = t('th_duration');

  document.getElementById('t-btn-page-first').textContent = t('btn_page_first');
  document.getElementById('t-btn-page-prev').textContent = t('btn_page_prev');
  document.getElementById('t-btn-page-next').textContent = t('btn_page_next');
  document.getElementById('t-btn-page-last').textContent = t('btn_page_last');

  document.getElementById('t-quick-profiles').textContent = t('quick_profiles');
  document.getElementById('t-btn-manage-all').textContent = t('btn_manage_all');
  const btnQuickTest = document.getElementById('t-btn-quick-test-latency');
  if (btnQuickTest) btnQuickTest.textContent = t('btn_test_latency_all');
  
  document.getElementById('t-profiles-title').textContent = t('profiles_title');
  const btnTestAll = document.getElementById('t-btn-test-latency-all');
  if (btnTestAll) btnTestAll.textContent = t('btn_test_latency_all');
  document.getElementById('t-btn-add-profile').textContent = t('btn_add_profile');
  document.getElementById('t-btn-export-backup').textContent = t('btn_export_backup');

  // Subscription
  const subTitle = document.getElementById('t-subscription-title');
  if (subTitle) subTitle.textContent = t('subscription_title');
  const subSyncBtn = document.getElementById('t-btn-sub-sync');
  if (subSyncBtn) subSyncBtn.textContent = t('btn_sub_sync');
  document.querySelectorAll('#subscription-list .sub-row-url').forEach(el => { el.placeholder = t('subscription_url_placeholder'); });
  document.querySelectorAll('#subscription-list .sub-row-pin').forEach(el => { el.placeholder = t('subscription_pin_short'); el.title = t('subscription_pin_hint'); });
  const subAddBtn = document.getElementById('t-btn-sub-add');
  if (subAddBtn) subAddBtn.textContent = t('subscription_add');

  const tlsToggleLabel = document.getElementById('t-label-edit-tls');
  if (tlsToggleLabel) tlsToggleLabel.textContent = t('label_tunnel_tls');

  // WebDAV
  const wdTitle = document.getElementById('t-webdav-title');
  if (wdTitle) wdTitle.textContent = t('webdav_title');
  [['t-label-webdav-url','label_webdav_url'],['t-label-webdav-user','label_webdav_user'],
   ['t-label-webdav-pass','label_webdav_pass'],['t-label-webdav-pin','label_webdav_pin'],
   ['t-label-webdav-auto','label_webdav_auto'],['t-label-webdav-last','label_webdav_last'],
   ['t-btn-webdav-backup','btn_webdav_backup'],['t-btn-webdav-save','btn_webdav_save'],
   ['t-btn-webdav-restore','btn_webdav_restore']].forEach(([id, key]) => {
    const el = document.getElementById(id);
    if (el) el.textContent = t(key);
  });
  const autoOff = document.getElementById('opt-webdav-auto-off');
  if (autoOff) autoOff.textContent = t('opt_webdav_auto_off');
  const autoOn = document.getElementById('opt-webdav-auto-on');
  if (autoOn) autoOn.textContent = t('opt_webdav_auto_on');
  const subLastSyncLabel = document.getElementById('t-label-sub-last-sync');
  if (subLastSyncLabel) subLastSyncLabel.textContent = t('label_sub_last_sync');
  updateSubscriptionLastSyncDisplay(null);

  // Settings
  document.getElementById('t-btn-save-all-settings').textContent = t('btn_save_all_settings');
  document.getElementById('t-settings-core-title').textContent = t('settings_core_title');
  document.getElementById('t-label-service-mode').textContent = t('label_service_mode');
  document.getElementById('opt-mode-vpn').textContent = t('opt_mode_vpn');
  document.getElementById('opt-mode-tproxy').textContent = t('opt_mode_tproxy');
  document.getElementById('t-label-log-level').textContent = t('label_log_level');
  document.getElementById('opt-log-debug').textContent = t('opt_log_debug');
  document.getElementById('opt-log-info').textContent = t('opt_log_info');
  document.getElementById('opt-log-warn').textContent = t('opt_log_warn');
  document.getElementById('opt-log-error').textContent = t('opt_log_error');
  document.getElementById('t-label-remote-dns').textContent = t('label_remote_dns');
  document.getElementById('t-label-local-dns').textContent = t('label_local_dns');

  document.getElementById('t-settings-udpgw-title').textContent = t('settings_udpgw_title');
  document.getElementById('t-label-udpgw-version').textContent = t('label_udpgw_version');
  document.getElementById('opt-udpgw-tun2proxy').textContent = t('opt_udpgw_tun2proxy');
  document.getElementById('opt-udpgw-badvpn').textContent = t('opt_udpgw_badvpn');
  document.getElementById('t-label-udpgw-addr').textContent = t('label_udpgw_addr');

  document.getElementById('t-settings-geodata-title').textContent = t('settings_geodata_title');
  document.getElementById('t-btn-update-geodata').textContent = t('btn_update_geodata');
  document.getElementById('t-label-geosite-direct').textContent = t('label_geosite_direct');
  document.getElementById('t-label-geoip-direct').textContent = t('label_geoip_direct');
  document.getElementById('t-label-geosite-url').textContent = t('label_geosite_url');
  document.getElementById('t-label-geoip-url').textContent = t('label_geoip_url');
  document.getElementById('t-label-update-interval').textContent = t('label_update_interval');
  document.getElementById('t-label-last-update').textContent = t('label_last_update');

  document.getElementById('t-filter-title').textContent = t('filter_title');
  document.getElementById('t-btn-save-filter').textContent = t('btn_save_filter');
  document.getElementById('t-mode-disallow').innerHTML = t('mode_disallow');
  document.getElementById('t-mode-allow').innerHTML = t('mode_allow');
  document.getElementById('app-search').placeholder = t('search_placeholder');
  document.getElementById('t-btn-select-all').textContent = t('btn_select_all');
  const appsRefreshBtn = document.getElementById('t-btn-apps-refresh');
  if (appsRefreshBtn) appsRefreshBtn.textContent = t('btn_refresh');
  document.getElementById('t-settings-system-title').textContent = t('settings_system_title');
  document.getElementById('t-label-show-speed').textContent = t('label_show_speed');

  document.getElementById('t-settings-title').textContent = t('settings_title');
  document.getElementById('t-auth-mode-0-title').innerHTML = t('auth_mode_0_title');
  document.getElementById('t-auth-mode-0-desc').textContent = t('auth_mode_0_desc');
  document.getElementById('t-auth-mode-1-title').innerHTML = t('auth_mode_1_title');
  document.getElementById('t-auth-mode-1-desc').textContent = t('auth_mode_1_desc');
  document.getElementById('t-auth-mode-2-title').innerHTML = t('auth_mode_2_title');
  document.getElementById('t-auth-mode-2-desc').textContent = t('auth_mode_2_desc');
  document.getElementById('t-auth-mode-3-title').innerHTML = t('auth_mode_3_title');
  document.getElementById('t-auth-mode-3-desc').textContent = t('auth_mode_3_desc');
  document.getElementById('t-custom-token-label').textContent = t('custom_token_label');
  document.getElementById('input-custom-token').placeholder = t('custom_token_placeholder');
  document.getElementById('t-current-url-label').textContent = t('current_url_label');
  document.getElementById('t-btn-copy-url').textContent = t('btn_copy_url');

  // Modals
  document.getElementById('t-modal-add-title').textContent = t('modal_add_title');
  document.getElementById('t-file-drop-title').textContent = t('file_drop_title');
  document.getElementById('t-file-drop-desc').textContent = t('file_drop_desc');
  document.getElementById('t-or-paste-text').textContent = t('or_paste_text');
  document.getElementById('profile-json-input').placeholder = t('import_placeholder');
  document.getElementById('t-import-pin-label').textContent = t('import_pin_label');
  document.getElementById('input-import-pin').placeholder = t('import_pin_placeholder');
  document.getElementById('pin-detected-badge').textContent = t('pin_detected_badge');
  document.getElementById('t-btn-cancel').textContent = t('btn_cancel');
  document.getElementById('t-btn-import-now').textContent = t('btn_import_now');

  const dpTitle = document.getElementById('t-webdav-picker-title');
  if (dpTitle) dpTitle.textContent = t('webdav_picker_title');
  const dpCancel = document.getElementById('t-btn-webdav-picker-cancel');
  if (dpCancel) dpCancel.textContent = t('btn_cancel');

  document.getElementById('t-modal-export-title').textContent = t('modal_export_title');
  document.getElementById('t-export-pin-label').textContent = t('export_pin_label');
  document.getElementById('input-export-pin').placeholder = t('export_pin_placeholder');
  document.getElementById('t-btn-gen-pin').textContent = t('btn_gen_pin');
  document.getElementById('t-export-result-label').textContent = t('export_result_label');
  document.getElementById('t-btn-export-cancel').textContent = t('btn_export_cancel');
  document.getElementById('t-btn-export-exec').textContent = t('btn_export_exec');
  document.getElementById('t-btn-export-copy').textContent = t('btn_export_copy');

  // Edit Modal
  document.getElementById('t-modal-edit-title').textContent = t('modal_edit_title');
  document.getElementById('t-label-edit-name').textContent = t('label_edit_name');
  document.getElementById('t-label-edit-ssh-addr').textContent = t('label_edit_ssh_addr');
  document.getElementById('t-label-edit-note').textContent = t('label_edit_note');
  document.getElementById('t-label-edit-favorite').textContent = t('label_edit_favorite');
  document.getElementById('t-label-edit-auth-type').textContent = t('label_edit_auth_type');
  document.getElementById('opt-auth-password').textContent = t('opt_auth_password');
  document.getElementById('opt-auth-key').textContent = t('opt_auth_key');
  document.getElementById('t-label-edit-user').textContent = t('label_edit_user');
  document.getElementById('t-label-edit-pass').textContent = t('label_edit_pass');
  document.getElementById('t-label-edit-key-pass').textContent = t('label_edit_key_pass');
  document.getElementById('t-label-edit-private-key').textContent = t('label_edit_private_key');
  document.getElementById('t-label-edit-tunnel-type').textContent = t('label_edit_tunnel_type');
  document.getElementById('t-label-edit-proxy-addr').textContent = t('label_edit_proxy_addr');
  document.getElementById('t-label-edit-custom-host').textContent = t('label_edit_custom_host');
  document.getElementById('t-label-edit-server-name').textContent = t('label_edit_server_name');
  document.getElementById('t-label-edit-custom-path').textContent = t('label_edit_custom_path');
  document.getElementById('t-label-edit-alpn').textContent = t('label_edit_alpn');
  document.getElementById('t-label-edit-http-payload').textContent = t('label_edit_http_payload');
  document.getElementById('t-label-edit-disable-status-check').textContent = t('label_edit_disable_status_check');
  document.getElementById('t-label-edit-verify-cert').textContent = t('label_edit_verify_cert');
  document.getElementById('t-label-edit-cert-fp').textContent = t('label_edit_cert_fp');
  document.getElementById('t-label-edit-proxy-auth').textContent = t('label_edit_proxy_auth');
  document.getElementById('t-label-edit-auth-token').textContent = t('label_edit_auth_token');
  document.getElementById('t-label-edit-auth-user').textContent = t('label_edit_auth_user');
  document.getElementById('t-label-edit-auth-pass').textContent = t('label_edit_auth_pass');
  document.getElementById('t-label-edit-dns-servers').textContent = t('label_edit_dns_servers');
  document.getElementById('t-label-edit-dns-domain').textContent = t('label_edit_dns_domain');
  document.getElementById('t-label-edit-dns-type').textContent = t('label_edit_dns_type');
  document.getElementById('t-label-edit-dns-psk').textContent = t('label_edit_dns_psk');
  document.getElementById('t-label-edit-dns-marker').textContent = t('label_edit_dns_marker');
  // ICMP labels
  const lblIcmpPsk = document.getElementById('t-label-icmp-psk');
  if (lblIcmpPsk) lblIcmpPsk.textContent = t('label_icmp_psk');
  const lblIcmpMagic = document.getElementById('t-label-icmp-magic');
  if (lblIcmpMagic) lblIcmpMagic.textContent = t('label_icmp_magic');
  const lblIcmpMtu = document.getElementById('t-label-icmp-mtu');
  if (lblIcmpMtu) lblIcmpMtu.textContent = t('label_icmp_mtu');
  const lblIcmpMaxPayload = document.getElementById('t-label-icmp-max-payload');
  if (lblIcmpMaxPayload) lblIcmpMaxPayload.textContent = t('label_icmp_max_payload');
  const lblIcmpPace = document.getElementById('t-label-icmp-pace');
  if (lblIcmpPace) lblIcmpPace.textContent = t('label_icmp_pace');
  const lblIcmpIdRange = document.getElementById('t-label-icmp-id-range');
  if (lblIcmpIdRange) lblIcmpIdRange.textContent = t('label_icmp_id_range');
  const icmpMagicInput = document.getElementById('edit-node-icmp-magic');
  if (icmpMagicInput) icmpMagicInput.placeholder = t('ph_icmp_custom_magic');
  document.getElementById('t-label-edit-kcp-pass').textContent = t('label_edit_kcp_pass');
  document.getElementById('t-label-edit-kcp-crypt').textContent = t('label_edit_kcp_crypt');
  document.getElementById('t-label-edit-kcp-data-shards').textContent = t('label_edit_kcp_data_shards');
  document.getElementById('t-label-edit-kcp-parity-shards').textContent = t('label_edit_kcp_parity_shards');
  document.getElementById('t-label-kcp-mode').textContent = t('label_edit_kcp_mode');
  document.getElementById('t-label-edit-udp-custom-psk').textContent = t('label_edit_udp_custom_psk');
  document.getElementById('t-label-edit-udp-custom-magic').textContent = t('label_edit_udp_custom_magic');
  document.getElementById('t-label-edit-udp-custom-paths').textContent = t('label_edit_udp_custom_paths');
  document.getElementById('t-label-edit-dns-edns0').textContent = t('label_edit_dns_edns0');
  document.getElementById('t-label-edit-udp-custom-sockets').textContent = t('label_edit_udp_custom_sockets');
  document.getElementById('t-label-edit-udp-custom-send-window').textContent = t('label_edit_udp_custom_send_window');
  document.getElementById('t-label-edit-xhttp-chunk').textContent = t('label_edit_xhttp_chunk');
  const xhttpStreamLabel = document.getElementById('t-label-edit-xhttp-stream-mode');
  if (xhttpStreamLabel) xhttpStreamLabel.textContent = t('label_edit_xhttp_stream_mode');
  const bindIfaceLabel = document.getElementById('t-label-edit-bind-interface');
  if (bindIfaceLabel) bindIfaceLabel.textContent = t('label_edit_bind_interface');
  document.getElementById('t-label-edit-heartbeat-interval').textContent = t('label_edit_heartbeat_interval');
  const lblPadMin = document.getElementById('t-label-edit-padding-min-bytes');
  if (lblPadMin) lblPadMin.textContent = t('label_edit_padding_min_bytes');
  const lblMasqueAlpn = document.getElementById('t-label-edit-masque-alpn');
  if (lblMasqueAlpn) lblMasqueAlpn.textContent = t('label_edit_masque_alpn');
  document.getElementById('t-label-edit-dns-override').textContent = t('label_edit_dns_override');
  document.getElementById('t-label-edit-remote-dns').textContent = t('label_edit_remote_dns');
  document.getElementById('t-label-edit-local-dns').textContent = t('label_edit_local_dns');
  document.getElementById('t-label-edit-udpgw-version').textContent = t('label_edit_udpgw_version');
  document.getElementById('t-label-edit-udpgw-addr').textContent = t('label_edit_udpgw_addr');
  document.getElementById('t-label-edit-geosite-direct').textContent = t('label_edit_geosite_direct');
  document.getElementById('t-label-edit-geoip-direct').textContent = t('label_edit_geoip_direct');
  document.getElementById('t-label-edit-app-override').textContent = t('label_edit_app_override');
  document.getElementById('t-node-mode-disallow').innerHTML = t('node_mode_disallow');
  document.getElementById('t-node-mode-allow').innerHTML = t('node_mode_allow');
  document.getElementById('node-app-search').placeholder = t('search_placeholder');
  document.getElementById('t-btn-node-select-all').textContent = t('btn_select_all');
  document.getElementById('t-btn-edit-cancel').textContent = t('btn_cancel');
  document.getElementById('t-btn-save-node-edit').textContent = t('btn_save_node_edit');

  document.getElementById('btn-autoscroll').textContent = autoScroll ? t('btn_autoscroll_on') : t('btn_autoscroll_off');
  document.getElementById('t-btn-copy-logs').textContent = t('btn_copy_logs');
  document.getElementById('t-btn-clear-logs').textContent = t('btn_clear_logs');

  // Tunnel & Security & Diagnostic Elements
  const elemTitle = document.getElementById('t-page-title');
  if (elemTitle) elemTitle.textContent = t('page_title');
  const elemVerifySshFp = document.getElementById('t-label-edit-verify-ssh-fp');
  if (elemVerifySshFp) elemVerifySshFp.textContent = t('label_edit_verify_ssh_fp');
  const elemEditSshFp = document.getElementById('t-label-edit-ssh-fp');
  if (elemEditSshFp) elemEditSshFp.textContent = t('label_edit_ssh_fp');
  const btnFetchSshFp = document.getElementById('t-btn-fetch-ssh-fp');
  if (btnFetchSshFp) btnFetchSshFp.textContent = t('btn_fetch_fp');
  const btnDetailsSsh = document.getElementById('t-btn-details-ssh');
  if (btnDetailsSsh) btnDetailsSsh.textContent = t('btn_details');

  const btnFetchCertFp = document.getElementById('t-btn-fetch-cert-fp');
  if (btnFetchCertFp) btnFetchCertFp.textContent = t('btn_fetch_fp');
  const btnDetailsCert = document.getElementById('t-btn-details-cert');
  if (btnDetailsCert) btnDetailsCert.textContent = t('btn_details');

  const elemNoisePk = document.getElementById('t-label-edit-noise-public-key');
  if (elemNoisePk) elemNoisePk.textContent = t('label_edit_noise_public_key');

  const modalDetailsTitle = document.getElementById('details-modal-title');
  if (modalDetailsTitle) modalDetailsTitle.textContent = t('details_modal_title');
  const btnDetailsCopy = document.getElementById('t-btn-details-copy');
  if (btnDetailsCopy) btnDetailsCopy.textContent = t('btn_details_copy');
  const btnDetailsApply = document.getElementById('t-btn-details-apply');
  if (btnDetailsApply) btnDetailsApply.textContent = t('btn_details_apply');
  const btnDetailsClose = document.getElementById('t-btn-details-close');
  if (btnDetailsClose) btnDetailsClose.textContent = t('btn_details_close');

  // Option text translations
  const optionIds = [
    'opt-tunnel-raw', 'opt-tunnel-websocket', 'opt-tunnel-http', 'opt-tunnel-h2',
    'opt-tunnel-quic', 'opt-tunnel-grpc', 'opt-tunnel-h3', 'opt-tunnel-masque',
    'opt-tunnel-webtransport', 'opt-tunnel-xhttp', 'opt-tunnel-icmp',
    'opt-tunnel-dns', 'opt-tunnel-kcp', 'opt-tunnel-udpc',
    'opt-alpn-h3-h2', 'opt-alpn-h2-h3', 'opt-alpn-h3', 'opt-alpn-h2', 'opt-dns-txt',
    'opt-stream-auto', 'opt-stream-stream', 'opt-stream-poll',
    'opt-webdav-auto-off', 'opt-webdav-auto-on',
    'opt-kcp-mode-fast', 'opt-kcp-mode-normal', 'opt-kcp-mode-fast2', 'opt-kcp-mode-fast3',
    'opt-kcp-nocomp-off', 'opt-kcp-nocomp-on'
  ];
  optionIds.forEach(id => {
    const el = document.getElementById(id);
    const key = id.replace(/-/g, '_');
    if (el && I18N[currentLang] && I18N[currentLang][key]) {
      el.textContent = t(key);
    }
  });


  // Section Headers
  const sec1 = document.getElementById('t-edit-sec-1-title');
  if (sec1) sec1.textContent = t('edit_sec_1_title');
  const sec2 = document.getElementById('t-edit-sec-2-title');
  if (sec2) sec2.textContent = t('edit_sec_2_title');
  const sec3 = document.getElementById('t-edit-sec-3-title');
  if (sec3) sec3.textContent = t('edit_sec_3_title');
  const sec4 = document.getElementById('t-edit-sec-4-title');
  if (sec4) sec4.textContent = t('edit_sec_4_title');

  // Placeholders
  const phMap = {
    'edit-node-name': 'edit_name_placeholder',
    'edit-node-ssh-addr': 'edit_ssh_addr_placeholder',
    'edit-node-note': 'edit_note_placeholder',
    'edit-node-user': 'edit_user_placeholder',
    'edit-node-pass': 'edit_pass_placeholder',
    'edit-node-key-pass': 'edit_key_pass_placeholder',
    'edit-node-proxy-addr': 'edit_proxy_addr_placeholder',
    'edit-node-custom-host': 'edit_custom_host_placeholder',
    'edit-node-server-name': 'edit_server_name_placeholder',
    'edit-node-custom-path': 'edit_custom_path_placeholder',
    'edit-node-udp-custom-psk': 'edit_udp_psk_placeholder',
    'edit-node-noise-public-key': 'edit_noise_pk_placeholder',
    'edit-node-ssh-fingerprint': 'edit_ssh_fp_placeholder',
    'edit-node-cert-fingerprint': 'edit_cert_fp_placeholder',
    'edit-node-auth-token': 'edit_auth_token_placeholder',
    'node-app-search': 'search_placeholder',
    'input-custom-token': 'custom_token_placeholder',
    'profile-json-input': 'import_placeholder',
    'input-import-pin': 'import_pin_placeholder',
    'input-export-pin': 'export_pin_placeholder',
    'conns-search': 'search_conns_placeholder',
    'app-search': 'search_placeholder'
  };
  for (const [id, key] of Object.entries(phMap)) {
    const el = document.getElementById(id);
    if (el) el.placeholder = t(key);
  }

  // Button Titles & Tooltips
  const btnFetchSsh = document.getElementById('t-btn-fetch-ssh-fp');
  if (btnFetchSsh) btnFetchSsh.title = t('btn_fetch_ssh_fp_title');
  const btnDetSsh = document.getElementById('t-btn-details-ssh');
  if (btnDetSsh) btnDetSsh.title = t('btn_details_ssh_title');
  const btnFetchCert = document.getElementById('t-btn-fetch-cert-fp');
  if (btnFetchCert) btnFetchCert.title = t('btn_fetch_cert_fp_title');
  const btnDetCert = document.getElementById('t-btn-details-cert');
  if (btnDetCert) btnDetCert.title = t('btn_details_cert_title');

  // MCP Settings Card
  const elMcpSettingsTitle = document.getElementById('t-settings-mcp-title');
  if (elMcpSettingsTitle) elMcpSettingsTitle.textContent = t('settings_mcp_title');
  const elMcpLabelEnable = document.getElementById('t-label-mcp-enable');
  if (elMcpLabelEnable) elMcpLabelEnable.textContent = t('label_mcp_enable');
  const elMcpDescEnable = document.getElementById('t-desc-mcp-enable');
  if (elMcpDescEnable) elMcpDescEnable.textContent = t('desc_mcp_enable');
  const elMcpLabelPort = document.getElementById('t-label-mcp-port');
  if (elMcpLabelPort) elMcpLabelPort.textContent = t('label_mcp_port');
  const elMcpLabelAuthMode = document.getElementById('t-label-mcp-auth-mode');
  if (elMcpLabelAuthMode) elMcpLabelAuthMode.textContent = t('label_mcp_auth_mode');
  const elMcpLabelSecret = document.getElementById('t-label-mcp-secret');
  if (elMcpLabelSecret) elMcpLabelSecret.textContent = t('label_mcp_secret');
  const elMcpSecretInput = document.getElementById('input-mcp-secret');
  if (elMcpSecretInput) elMcpSecretInput.placeholder = t('mcp_secret_placeholder');

  // MCP Auth Mode Options
  const mcpAuthOptIds = ['opt-mcp-auth-none', 'opt-mcp-auth-apikey', 'opt-mcp-auth-basic', 'opt-mcp-auth-oauth'];
  const mcpAuthOptKeys = ['opt_mcp_auth_none', 'opt_mcp_auth_apikey', 'opt_mcp_auth_basic', 'opt_mcp_auth_oauth'];
  mcpAuthOptIds.forEach((id, i) => {
    const el = document.getElementById(id);
    if (el) el.textContent = t(mcpAuthOptKeys[i]);
  });

  // MCP Dashboard Tab
  const elMcpStatStatus = document.getElementById('t-mcp-stat-status');
  if (elMcpStatStatus) elMcpStatStatus.textContent = t('mcp_stat_status');
  const elMcpStatPort = document.getElementById('t-mcp-stat-port');
  if (elMcpStatPort) elMcpStatPort.textContent = t('mcp_stat_port');
  const elMcpStatProto = document.getElementById('t-mcp-stat-proto');
  if (elMcpStatProto) elMcpStatProto.textContent = t('mcp_stat_proto');
  const elMcpStatTools = document.getElementById('t-mcp-stat-tools');
  if (elMcpStatTools) elMcpStatTools.textContent = t('mcp_stat_tools');
  const elMcpDashTitle = document.getElementById('t-mcp-dash-title');
  if (elMcpDashTitle) elMcpDashTitle.textContent = t('mcp_dash_title');
  const elMcpDashDesc = document.getElementById('t-mcp-dash-desc');
  if (elMcpDashDesc) elMcpDashDesc.textContent = t('mcp_dash_desc');
  const elBtnOpenMcpDash = document.getElementById('btn-open-mcp-dashboard');
  if (elBtnOpenMcpDash) elBtnOpenMcpDash.textContent = t('btn_open_mcp_dash');
  const elMcpClaudeTitle = document.getElementById('t-mcp-claude-title');
  if (elMcpClaudeTitle) elMcpClaudeTitle.textContent = t('mcp_claude_title');
  const elMcpClaudeDesc = document.getElementById('t-mcp-claude-desc');
  if (elMcpClaudeDesc) elMcpClaudeDesc.innerHTML = t('mcp_claude_desc');
  const elBtnCopyMcpConfig = document.getElementById('btn-copy-mcp-config');
  if (elBtnCopyMcpConfig) elBtnCopyMcpConfig.textContent = t('btn_copy_mcp_config');
  const elMcpGeminiTitle = document.getElementById('t-mcp-gemini-title');
  if (elMcpGeminiTitle) elMcpGeminiTitle.textContent = t('mcp_gemini_title');
  const elMcpGeminiDesc = document.getElementById('t-mcp-gemini-desc');
  if (elMcpGeminiDesc) elMcpGeminiDesc.innerHTML = t('mcp_gemini_desc');
  const elBtnCopyGeminiSnippet = document.getElementById('btn-copy-gemini-snippet');
  if (elBtnCopyGeminiSnippet) elBtnCopyGeminiSnippet.textContent = t('btn_copy_gemini_snippet');
  const elMcpToolsTitle = document.getElementById('t-mcp-tools-title');
  if (elMcpToolsTitle) elMcpToolsTitle.textContent = t('mcp_tools_title');


  if (currentTab === 'conntrack') renderConnections();
  renderProfiles(allProfiles);
}

function changeLang(lang) {
  currentLang = lang;
  localStorage.setItem('preferred_lang', lang);
  applyI18n();
  fetchStatus();
  loadProfiles();
  if (currentTab === 'conntrack') loadConntrack();
  if (currentTab === 'settings') { loadSettings(); loadWebDav(); }
}

function initLang() {
  const saved = localStorage.getItem('preferred_lang');
  if (saved && I18N[saved]) {
    currentLang = saved;
  } else {
    const navLang = (navigator.language || navigator.userLanguage || 'zh-CN');
    if (navLang.startsWith('zh-TW') || navLang.startsWith('zh-HK')) currentLang = 'zh-TW';
    else if (navLang.startsWith('zh')) currentLang = 'zh-CN';
    else if (navLang.startsWith('ja')) currentLang = 'ja';
    else if (navLang.startsWith('de')) currentLang = 'de';
    else if (navLang.startsWith('fr')) currentLang = 'fr';
    else currentLang = 'en';
  }
  document.getElementById('lang-select').value = currentLang;
  applyI18n();
}

function initTheme() {
  const saved = localStorage.getItem('preferred_theme');
  if (saved) {
    currentTheme = saved;
  } else {
    currentTheme = (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) ? 'light' : 'dark';
  }
  applyTheme();
}

function toggleTheme() {
  currentTheme = currentTheme === 'dark' ? 'light' : 'dark';
  localStorage.setItem('preferred_theme', currentTheme);
  applyTheme();
}

function applyTheme() {
  document.documentElement.setAttribute('data-theme', currentTheme);
  const btn = document.getElementById('theme-btn');
  if (btn) btn.textContent = currentTheme === 'dark' ? '🌙' : '☀️';
}

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return (bytes / Math.pow(1024, i)).toFixed(1) + ' ' + units[i];
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function switchTab(tabId) {
  currentTab = tabId;
  document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
  
  event?.target?.classList.add('active');
  document.getElementById('tab-' + tabId)?.classList.add('active');

  if (tabId === 'profiles') { loadProfiles(); loadSubscription(); }
  if (tabId === 'conntrack') loadConntrack();
  if (tabId === 'settings') {
    loadSettings();
    loadApps();
  }
  if (tabId === 'mcp') {
    loadMcpTab();
  }
}

// ── 服务端 Banner 渲染 ──
// 握手 banner（RFC 4252 §5.4）本质是无格式 UTF-8 文本，颜色/HTML 由客户端自行决定。
// 管线：① 清洗控制序列 → ② 按 HTML 解析 → ③ 遍历 DOM 重建：标签走白名单、属性只回填
// 校验过的 href 与 style、文本节点内的 ANSI SGR 拆成 <span style>。
// 注意：不能先把整段文本转义再解析（那样 HTML 永远不渲染），必须让解析器先认出标签。
// 背景：WebUI 的 URL 携带可控制 VPN 的 token，innerHTML 直接注入等于 token 泄露。
const BANNER_ALLOWED_TAGS = new Set(['B','STRONG','I','EM','U','S','STRIKE','DEL','BR','SPAN','A','CODE','PRE','SMALL','SUB','SUP','P','DIV','UL','OL','LI']);
const BANNER_ALLOWED_STYLES = new Set(['color','background-color','font-weight','font-style','text-decoration']);
const BANNER_DROP_TAGS = new Set(['SCRIPT','IFRAME','OBJECT','EMBED','FORM','INPUT','BUTTON','TEXTAREA','SELECT','OPTION','STYLE','LINK','META','BASE','SVG','MATH','AUDIO','VIDEO','IMG','PICTURE','SOURCE','TRACK','TEMPLATE','NOSCRIPT','CANVAS','DIALOG','FRAME','FRAMESET','APPLET']);
const ANSI_COLORS = {
  30:'#5c6b7a', 31:'#e74c3c', 32:'#2ecc71', 33:'#f1c40f', 34:'#3498db', 35:'#9b59b6', 36:'#1abc9c', 37:'#bdc3c7',
  90:'#7f8c8d', 91:'#ff6b6b', 92:'#58d68d', 93:'#ffd93d', 94:'#5dade2', 95:'#c39bd3', 96:'#48c9b0', 97:'#f4f6f7'
};
let lastBannerKey = '';

// 预处理：去掉 OSC、非 SGR 的 CSI、字符集指定与控制字符；保留 \t \n 以及 SGR 的 ESC（供拆色）
function preCleanBanner(raw) {
  return String(raw)
    .replace(/\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g, '')
    .replace(/\x1b\[[0-9;?]*[ -\/]*[@-ln-~]/g, '')
    .replace(/\x1b[()*+\/.,-][0-9A-Za-z]?/g, '')
    .replace(/[\x00-\x08\x0b-\x1a\x1c-\x1f\x7f]/g, '');
}

function filterBannerStyle(raw) {
  if (!raw) return '';
  const out = [];
  for (const decl of String(raw).split(';')) {
    const i = decl.indexOf(':');
    if (i < 0) continue;
    const prop = decl.slice(0, i).trim().toLowerCase();
    const val = decl.slice(i + 1).trim();
    if (!BANNER_ALLOWED_STYLES.has(prop)) continue;
    if (!val || /url\s*\(|expression|javascript:|@import|[<>{}]/i.test(val)) continue;
    out.push(prop + ':' + val);
  }
  return out.join(';');
}

// 把一段纯文本按 ANSI SGR 切段；initial 为进入该文本节点时已生效的样式声明。
// 返回 { runs, styles }：styles 是走完这段后的 ANSI 状态，供后续兄弟/子节点继承
// （使 "\x1b[31m<a>L</a>" 这种「色码在标签外、内容在标签内」也能正确着色）。
function ansiRunsFrom(raw, initial) {
  const re = /\x1b\[([0-9;]*)m/g;
  const runs = [];
  let styles = initial.slice(), last = 0, m;
  const flush = s => {
    const txt = s.replace(/\x1b/g, '');        // 残留的裸 ESC 一并抹掉
    if (txt) runs.push({ text: txt, styles: styles.slice() });
  };
  while ((m = re.exec(raw)) !== null) {
    flush(raw.slice(last, m.index));
    last = re.lastIndex;
    const codes = m[1] === '' ? [0] : m[1].split(';').map(c => parseInt(c, 10) || 0);
    for (const c of codes) {
      if (c === 0) styles = [];
      else if (c === 1) styles.push('font-weight:bold');
      else if (c === 3) styles.push('font-style:italic');
      else if (c === 4) styles.push('text-decoration:underline');
      else if (ANSI_COLORS[c]) styles.push('color:' + ANSI_COLORS[c]);
    }
  }
  flush(raw.slice(last));
  return { runs, styles };
}

function appendAnsiText(frag, text, styles) {
  if (!text) return;
  const st = filterBannerStyle(styles.join(';'));
  if (!st) { frag.appendChild(document.createTextNode(text)); return; }
  const sp = document.createElement('span');
  sp.setAttribute('style', st);
  sp.appendChild(document.createTextNode(text));
  frag.appendChild(sp);
}

// 递归重建：白名单标签用新元素重建（属性全部丢弃，只回填校验过的 href/style），
// 危险标签整棵丢弃，其余未知标签丢壳留内容。ansi 为当前继承中的 ANSI 样式声明。
function sanitizeBannerNode(src, ansi) {
  const frag = document.createDocumentFragment();
  for (const child of src.childNodes) {
    if (child.nodeType === 3) {                        // TEXT_NODE
      const val = child.nodeValue || '';
      if (val.indexOf('\x1b') >= 0) {
        const r = ansiRunsFrom(val, ansi);
        ansi = r.styles;
        for (const run of r.runs) appendAnsiText(frag, run.text, run.styles);
      } else {
        appendAnsiText(frag, val, ansi);
      }
      continue;
    }
    if (child.nodeType !== 1) continue;                // 注释 / PI 等一律丢弃
    const tag = child.tagName;
    if (BANNER_DROP_TAGS.has(tag)) continue;
    if (BANNER_ALLOWED_TAGS.has(tag)) {
      const el = document.createElement(tag.toLowerCase());
      if (tag === 'A') {
        const href = (child.getAttribute('href') || '').trim();
        if (/^https?:\/\//i.test(href) || /^\/\//.test(href) || href.startsWith('#')) {
          el.setAttribute('href', href);
          el.setAttribute('rel', 'noopener noreferrer');
          el.setAttribute('target', '_blank');
        }
      }
      const style = filterBannerStyle(child.getAttribute('style'));
      if (style) el.setAttribute('style', style);
      el.appendChild(sanitizeBannerNode(child, ansi));
      frag.appendChild(el);
    } else {
      frag.appendChild(sanitizeBannerNode(child, ansi));   // 未知标签：丢壳留内容
    }
  }
  return frag;
}

function sanitizeBannerHtml(raw) {
  if (!raw) return '';
  const tpl = document.createElement('template');   // template 内容惰性：脚本不执行、资源不加载
  tpl.innerHTML = preCleanBanner(raw);
  const box = document.createElement('div');
  box.appendChild(sanitizeBannerNode(tpl.content, []));
  return box.innerHTML;
}

async function fetchStatus() {
  try {
    const res = await fetch('/api/status?token=' + token);
    if (!res.ok) return;
    const data = await res.json();
    currentVpnState = data.vpnState;
    
    document.getElementById('tv-device-name').textContent = data.deviceName || 'Stun Client';
    
    const vpnPill = document.getElementById('vpn-pill');
    const vpnText = document.getElementById('vpn-text');
    if (data.vpnState === 'CONNECTED') {
      vpnPill.className = 'vpn-pill connected';
      vpnText.textContent = t('vpn_connected');
    } else if (data.vpnState === 'CONNECTING' || data.vpnState === 'RECONNECTING') {
      vpnPill.className = 'vpn-pill connecting';
      vpnText.textContent = t('vpn_connecting');
    } else {
      vpnPill.className = 'vpn-pill disconnected';
      vpnText.textContent = t('vpn_disconnected');
    }

    document.getElementById('stat-selected-node').textContent = data.selectedProfileName || t('stat_unselected');
    document.getElementById('stat-speed').textContent = '↑ ' + formatBytes(data.txRate) + '/s   ↓ ' + formatBytes(data.rxRate) + '/s';
    document.getElementById('stat-total').textContent = '↑ ' + formatBytes(data.txTotal) + '   ↓ ' + formatBytes(data.rxTotal);
    document.getElementById('stat-filter').textContent = data.filterMode === 1 ? t('filter_status_allow', {count: data.filterAppsCount}) : t('filter_status_disallow', {count: data.filterAppsCount});

    // 服务端 Banner：SSH 标识 + 认证 banner，两者皆空则隐藏整卡
    const bannerCard = document.getElementById('server-banner-card');
    if (bannerCard) {
      const bVer = (data.sshServerVersion || '').trim();
      const bMsg = (data.sshBanner || '').trim();
      if (!bVer && !bMsg) {
        bannerCard.style.display = 'none';
        lastBannerKey = '';
      } else {
        bannerCard.style.display = '';
        const bKey = bVer + '\u0000' + bMsg;
        if (bKey !== lastBannerKey) {   // 仅在内容变化时重建，避免 2s 轮询打断文本选中/引发闪烁
          lastBannerKey = bKey;
          const verEl = document.getElementById('server-banner-version');
          if (verEl) { verEl.textContent = bVer; verEl.style.display = bVer ? '' : 'none'; }
          const bodyEl = document.getElementById('server-banner-content');
          if (bodyEl) {
            const safe = sanitizeBannerHtml(bMsg);
            bodyEl.innerHTML = safe;
            bodyEl.style.display = safe ? '' : 'none';
          }
        }
      }
    }
  } catch (_) {}
}

async function toggleVpn() {
  const action = currentVpnState === 'CONNECTED' ? 'stop' : 'start';
  await fetch('/api/vpn/toggle?token=' + token, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({action})
  });
  showToast(t(action === 'start' ? 'toast_vpn_connecting' : 'toast_vpn_disconnected'));
  setTimeout(fetchStatus, 500);
}

// ── 连接跟踪 Conntrack 逻辑与分页 ──
async function loadConntrack() {
  try {
    const res = await fetch('/api/conntrack?token=' + token);
    if (!res.ok) return;
    const data = await res.json();
    
    document.getElementById('stat-active-conns').textContent = data.activeConns || 0;
    document.getElementById('stat-total-conns').textContent = data.totalConns || 0;
    document.getElementById('stat-route-hit-rate').textContent = (data.routeHitRate || 0).toFixed(1) + '%';

    allConnections = Array.isArray(data.connections) ? data.connections : [];
    renderConnections();
    renderDomainActivity(Array.isArray(data.domains) ? data.domains : []);
  } catch (_) {}
}

function onConnsSearchChange() {
  connsCurrentPage = 1;
  renderConnections();
}

function gotoConnsPage(page) {
  connsCurrentPage = page;
  renderConnections();
}

function prevConnsPage() {
  if (connsCurrentPage > 1) {
    connsCurrentPage--;
    renderConnections();
  }
}

function nextConnsPage() {
  connsCurrentPage++;
  renderConnections();
}

function gotoConnsLastPage() {
  const q = (document.getElementById('conns-search')?.value || '').toLowerCase().trim();
  const filtered = allConnections.filter(c => {
    const target = (c.target_addr || c.TargetAddr || '').toLowerCase();
    const host = (c.target_host || c.TargetHost || '').toLowerCase();
    const proxy = (c.proxy_addr || c.ProxyAddr || '').toLowerCase();
    return !q || target.includes(q) || host.includes(q) || proxy.includes(q);
  });
  const totalPages = Math.max(1, Math.ceil(filtered.length / CONNS_PAGE_SIZE));
  connsCurrentPage = totalPages;
  renderConnections();
}

function renderConnections() {
  const tbody = document.getElementById('conns-table-body');
  const q = (document.getElementById('conns-search')?.value || '').toLowerCase().trim();
  const filtered = allConnections.filter(c => {
    const target = (c.target_addr || c.TargetAddr || '').toLowerCase();
    const host = (c.target_host || c.TargetHost || '').toLowerCase();
    const proxy = (c.proxy_addr || c.ProxyAddr || '').toLowerCase();
    return !q || target.includes(q) || host.includes(q) || proxy.includes(q);
  });

  const total = filtered.length;
  const totalPages = Math.max(1, Math.ceil(total / CONNS_PAGE_SIZE));
  if (connsCurrentPage > totalPages) connsCurrentPage = totalPages;
  if (connsCurrentPage < 1) connsCurrentPage = 1;

  const pageInfo = document.getElementById('conns-page-info');
  if (pageInfo) {
    pageInfo.textContent = t('pagination_info', {
      page: connsCurrentPage,
      totalPages: totalPages,
      total: total
    });
  }

  const btnFirst = document.getElementById('t-btn-page-first');
  const btnPrev = document.getElementById('t-btn-page-prev');
  const btnNext = document.getElementById('t-btn-page-next');
  const btnLast = document.getElementById('t-btn-page-last');

  if (btnFirst) btnFirst.disabled = (connsCurrentPage <= 1);
  if (btnPrev) btnPrev.disabled = (connsCurrentPage <= 1);
  if (btnNext) btnNext.disabled = (connsCurrentPage >= totalPages);
  if (btnLast) btnLast.disabled = (connsCurrentPage >= totalPages);

  if (!filtered.length) {
    tbody.innerHTML = '<tr><td colspan="4" style="text-align:center;color:var(--text-muted);padding:24px">' + t('no_active_conns') + '</td></tr>';
    return;
  }

  const startIdx = (connsCurrentPage - 1) * CONNS_PAGE_SIZE;
  const pageItems = filtered.slice(startIdx, startIdx + CONNS_PAGE_SIZE);

  const now = Math.floor(Date.now() / 1000);
  const html = pageItems.map(c => {
    const target = c.target_addr || c.TargetAddr || '-';
    const proxy = c.proxy_addr || c.ProxyAddr || 'Direct';
    const rb = c.read_bytes || c.ReadBytes || 0;
    const wb = c.write_bytes || c.WriteBytes || 0;
    
    let durationStr = '< 1s';
    let startTime = c.start_time || c.StartTime;
    if (startTime) {
      let startSec = typeof startTime === 'number' ? startTime : Math.floor(new Date(startTime).getTime() / 1000);
      let diff = Math.max(0, now - startSec);
      let m = Math.floor(diff / 60);
      let s = diff % 60;
      durationStr = (m > 0 ? m + 'm ' : '') + s + 's';
    }

    return `
      <tr>
        <td><span style="color:var(--text);font-weight:700">🌐 ${target}</span></td>
        <td><span style="color:var(--text-muted);font-size:0.75rem">${proxy}</span></td>
        <td><span style="color:var(--primary)">↑ ${formatBytes(wb)}</span>  <span style="color:var(--success)">↓ ${formatBytes(rb)}</span></td>
        <td><span style="color:var(--accent);font-size:0.75rem">${durationStr}</span></td>
      </tr>
    `;
  }).join('');

  tbody.innerHTML = html;
}

function renderDomainActivity(domains) {
  const list = document.getElementById('domain-ranking-list');
  if (!domains.length) {
    list.innerHTML = '<div style="color:var(--text-muted);font-size:0.85rem;padding:8px">' + t('no_domain_activity') + '</div>';
    return;
  }

  const html = domains.map(d => {
    const dom = d.domain || d.Domain || '-';
    const tx = d.tx_rate || d.TxRate || 0;
    const rx = d.rx_rate || d.RxRate || 0;
    return `
      <div class="domain-rank-item">
        <span class="domain-name">🌐 ${dom}</span>
        <span class="domain-rates">↑ ${formatBytes(tx)}/s   ↓ ${formatBytes(rx)}/s</span>
      </div>
    `;
  }).join('');

  list.innerHTML = html;
}

async function loadProfiles() {
  try {
    const res = await fetch('/api/profiles?token=' + token);
    if (!res.ok) return;
    allProfiles = await res.json();
    renderProfiles(allProfiles);
  } catch (_) {}
}

// ── 订阅管理 (Subscription Management, 多订阅) ──
let subLastSync = 0;

function updateSubscriptionLastSyncDisplay(lastSync) {
  // lastSync 为 null 时沿用已缓存的时间戳（语言切换时保持显示）
  if (lastSync !== null) subLastSync = lastSync || 0;
  const el = document.getElementById('display-sub-last-sync');
  if (!el) return;
  el.textContent = (subLastSync > 0) ? new Date(subLastSync).toLocaleString() : t('sub_never_synced');
}

function addSubscriptionRow(url = '', pin = '') {
  const list = document.getElementById('subscription-list');
  if (!list) return;
  const row = document.createElement('div');
  row.style.cssText = 'display:flex;gap:8px;align-items:center;flex-wrap:wrap';

  const urlInput = document.createElement('input');
  urlInput.type = 'text';
  urlInput.className = 'text-input sub-row-url';
  urlInput.placeholder = t('subscription_url_placeholder');
  urlInput.style.cssText = 'flex:1;min-width:240px';
  urlInput.value = url || '';

  const pinInput = document.createElement('input');
  pinInput.type = 'text';
  pinInput.className = 'text-input sub-row-pin';
  pinInput.maxLength = 6;
  pinInput.placeholder = t('subscription_pin_short');
  pinInput.title = t('subscription_pin_hint');
  pinInput.style.cssText = 'width:170px';
  pinInput.value = pin || '';

  const delBtn = document.createElement('button');
  delBtn.type = 'button';
  delBtn.className = 'btn btn-sm';
  delBtn.textContent = '🗑';
  delBtn.title = t('btn_delete');
  delBtn.onclick = () => { row.remove(); if (!document.querySelector('#subscription-list > div')) addSubscriptionRow(); };

  row.append(urlInput, pinInput, delBtn);
  list.appendChild(row);
}

function collectSubscriptions() {
  return Array.from(document.querySelectorAll('#subscription-list > div')).map(row => ({
    url: (row.querySelector('.sub-row-url')?.value || '').trim(),
    pin: (row.querySelector('.sub-row-pin')?.value || '').trim()
  })).filter(s => s.url);
}

async function loadSubscription() {
  try {
    const res = await fetch('/api/subscription?token=' + token);
    if (!res.ok) return;
    const data = await res.json();
    const subs = data.subscriptions || [];
    const list = document.getElementById('subscription-list');
    if (list) {
      list.innerHTML = '';
      subs.forEach(s => addSubscriptionRow(s.url || '', s.pin || ''));
      if (!subs.length) addSubscriptionRow();
    }
    updateSubscriptionLastSyncDisplay(data.lastSync);
  } catch (_) {}
}

async function syncSubscriptionNow() {
  const btn = document.getElementById('t-btn-sub-sync');
  const subs = collectSubscriptions();
  if (!subs.length) {
    showToast(t('subscription_url_empty'));
    return;
  }

  const originalText = btn ? btn.textContent : '';
  if (btn) {
    btn.disabled = true;
    btn.textContent = t('subscription_syncing');
  }

  try {
    const res = await fetch('/api/subscription/sync?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({subscriptions: subs})
    });
    const data = await res.json().catch(() => ({}));
    if (res.ok && data.status === 'success') {
      updateSubscriptionLastSyncDisplay(data.lastSync);
      const total = (data.importedCount || 0) + (data.updatedCount || 0);
      if ((data.failedCount || 0) > 0) {
        showToast(t('subscription_sync_partial', {ok: data.okCount || 0, total: (data.okCount || 0) + (data.failedCount || 0), count: total}));
        // 逐条 errorCode 里挑一个可本地化的失败原因提示（pin_required/pin_invalid 最常见）。
        const failed = (data.results || []).find(r => !r.ok && r.errorCode);
        if (failed && (failed.errorCode === 'pin_required' || failed.errorCode === 'pin_invalid')) {
          showToast(t(failed.errorCode));
        }
      } else {
        showToast(t('subscription_sync_success', {count: total}));
      }
      loadProfiles();
      loadSubscription();
    } else if (data.error === 'pin_required') {
      showToast(t('subscription_pin_required'));
    } else if (data.error === 'pin_invalid') {
      showToast(t('subscription_pin_invalid'));
    } else {
      showToast(t('subscription_sync_error', {msg: data.message || ('HTTP ' + res.status)}));
    }
  } catch (_) {
    showToast(t('subscription_sync_error', {msg: 'network error'}));
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = originalText;
    }
  }
}

function getLatencyBadgeHtml(id) {
  const d = profileDelays[id];
  if (!d) return `<span class="latency-badge" id="delay-badge-${id}">—</span>`;
  if (d.testing) return `<span class="latency-badge testing" id="delay-badge-${id}">...</span>`;
  
  let cls = 'bad';
  if (d.ok) {
    if (d.latencyMs < 150) cls = 'good';
    else if (d.latencyMs < 350) cls = 'mid';
    else cls = 'bad';
  }
  return `<span class="latency-badge ${cls}" id="delay-badge-${id}">⚡ ${escapeHtml(d.display || '—')}</span>`;
}

function renderProfiles(profiles) {
  updateProfileFilterLabels();
  const container = document.getElementById('profiles-list');
  const quickContainer = document.getElementById('quick-profiles');
  if (!profiles.length) {
    const emptyHtml = '<div style="color:var(--text-muted);font-size:0.85rem">' + t('no_profiles') + '</div>';
    container.innerHTML = emptyHtml;
    quickContainer.innerHTML = emptyHtml;
    return;
  }
  const shown = profileFilter === 'fav' ? profiles.filter(p => p.favorite)
    : profileFilter === 'recent' ? profiles.filter(p => (p.lastConnectedAt || 0) > 0)
        .sort((a, b) => (b.lastConnectedAt || 0) - (a.lastConnectedAt || 0))
    : profiles;
  if (!shown.length) {
    const emptyHtml = '<div style="color:var(--text-muted);font-size:0.85rem">' + t('filter_empty') + '</div>';
    container.innerHTML = emptyHtml;
    quickContainer.innerHTML = emptyHtml;
    return;
  }

  const html = shown.map(p => `
    <div class="profile-card ${p.isSelected ? 'selected' : ''}">
      <div class="profile-header">
        <button class="fav-star ${p.favorite ? 'on' : ''}" onclick="toggleFavorite('${p.id}')" title="${t(p.favorite ? 'fav_remove' : 'fav_add')}">${p.favorite ? '⭐' : '☆'}</button>
        <span class="profile-name">${escapeHtml(p.name)}</span>
        <span class="profile-badge">${((p.tunnelType === 'kcptun' ? 'KCP' : (p.tunnelType || 'TLS'))).toUpperCase()}</span>
        ${p.sourceSubscriptionUrl ? `<span class="profile-badge" title="${escapeHtml(p.sourceSubscriptionUrl)}">${t('badge_from_subscription')}</span>` : ''}
      </div>
      <div class="profile-addr">🌐 ${escapeHtml(p.sshAddr)}</div>
      ${p.note ? `<div class="profile-addr" style="color:var(--text-muted)">📝 ${escapeHtml(p.note)}</div>` : ''}
      <div class="profile-meta">
        <span class="profile-traffic">↑ ${formatBytes(p.totalTx)}  ↓ ${formatBytes(p.totalRx)}</span>
        ${getLatencyBadgeHtml(p.id)}
      </div>
      <div class="profile-actions">
        ${p.isSelected ? '<span style="color:var(--primary);font-size:0.82rem;font-weight:800">' + t('badge_selected') + '</span>' : `<button class="btn btn-sm btn-primary" onclick="selectProfile('${p.id}', '${escapeHtml(p.name)}')">${t('btn_select_this')}</button>`}
        <button class="btn btn-sm" id="btn-ping-${p.id}" onclick="testSingleProfileLatency('${p.id}', '${escapeHtml(p.name)}')">${t('btn_ping')}</button>
        <button class="btn btn-sm" onclick="openEditModal('${p.id}')">${t('btn_edit_profile')}</button>
        <button class="btn btn-sm btn-danger" onclick="deleteProfile('${p.id}', '${escapeHtml(p.name)}')">${t('btn_delete')}</button>
      </div>
    </div>
  `).join('');

  container.innerHTML = html;
  quickContainer.innerHTML = html;
}

function setProfileFilter(f) {
  profileFilter = f;
  renderProfiles(allProfiles);
}

/** 三个筛选 tab 的文案（带计数）与选中态。计数口径与手机端 updateNodeTabCounts 一致。 */
function updateProfileFilterLabels() {
  const all = allProfiles.length;
  const fav = allProfiles.filter(p => p.favorite).length;
  const recent = allProfiles.filter(p => (p.lastConnectedAt || 0) > 0).length;
  const set = (id, text, active) => {
    const el = document.getElementById(id);
    if (!el) return;
    el.textContent = text;
    el.classList.toggle('active', active);
  };
  set('pfilter-all', t('filter_tab_all', {count: all}), profileFilter === 'all');
  set('pfilter-fav', '⭐ ' + t('filter_tab_favorites', {count: fav}), profileFilter === 'fav');
  set('pfilter-recent', '🕐 ' + t('filter_tab_recent', {count: recent}), profileFilter === 'recent');
}

/** 列表级收藏切换：只发 {id, favorite}，服务端 copy(...) 其余字段原样保留。 */
async function toggleFavorite(id) {
  const p = allProfiles.find(item => item.id === id);
  if (!p) return;
  try {
    const res = await fetch('/api/profiles/update?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({id: id, favorite: !p.favorite})
    });
    if (res.ok) { loadProfiles(); } else { showToast(t('toast_profile_update_failed')); }
  } catch (_) { showToast(t('toast_profile_update_failed')); }
}

// ── 测速逻辑 (Ping / Latency Test) ──
async function testAllProfilesLatency() {
  if (!allProfiles.length) return;
  allProfiles.forEach(p => {
    profileDelays[p.id] = { testing: true, display: '...' };
    const badge = document.getElementById('delay-badge-' + p.id);
    if (badge) {
      badge.className = 'latency-badge testing';
      badge.textContent = '...';
    }
  });

  const btnAll = document.getElementById('t-btn-test-latency-all');
  const btnQuick = document.getElementById('t-btn-quick-test-latency');
  if (btnAll) btnAll.disabled = true;
  if (btnQuick) btnQuick.disabled = true;

  try {
    const res = await fetch('/api/profiles/ping?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({})
    });
    const data = await res.json();
    if (res.ok && data.status === 'success' && data.results) {
      for (const [id, r] of Object.entries(data.results)) {
        profileDelays[id] = {
          ok: r.ok,
          latencyMs: r.latencyMs,
          display: r.display,
          testing: false
        };
      }
      showToast(t('toast_all_latency_tested'));
    } else {
      showToast(t('toast_latency_failed'));
    }
  } catch (_) {
    showToast(t('toast_latency_failed'));
  } finally {
    if (btnAll) btnAll.disabled = false;
    if (btnQuick) btnQuick.disabled = false;
    renderProfiles(allProfiles);
  }
}

async function testSingleProfileLatency(id, name) {
  profileDelays[id] = { testing: true, display: '...' };
  const badge = document.getElementById('delay-badge-' + id);
  if (badge) {
    badge.className = 'latency-badge testing';
    badge.textContent = '...';
  }
  const btn = document.getElementById('btn-ping-' + id);
  if (btn) btn.disabled = true;

  try {
    const res = await fetch('/api/profiles/ping?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({id: id})
    });
    const data = await res.json();
    if (res.ok && data.status === 'success' && data.results && data.results[id]) {
      const r = data.results[id];
      profileDelays[id] = {
        ok: r.ok,
        latencyMs: r.latencyMs,
        display: r.display,
        testing: false
      };
      if (r.ok) {
        showToast(t('toast_latency_tested', {name: name, delay: r.display}));
      } else {
        showToast((name || 'Node') + ': ' + (r.error ? r.error : r.display));
      }
    } else {
      profileDelays[id] = { ok: false, latencyMs: -1, display: 'Error', testing: false };
      showToast(t('toast_latency_failed'));
    }
  } catch (_) {
    profileDelays[id] = { ok: false, latencyMs: -1, display: 'Error', testing: false };
    showToast(t('toast_latency_failed'));
  } finally {
    if (btn) btn.disabled = false;
    renderProfiles(allProfiles);
  }
}

async function selectProfile(id, name) {
  await fetch('/api/profiles/select?token=' + token, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({id})
  });
  showToast(t('toast_switched_node', {name: name}));
  loadProfiles();
  fetchStatus();
}

async function deleteProfile(id, name) {
  if (!confirm(t('confirm_delete', {name}))) return;
  await fetch('/api/profiles/delete?token=' + token, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({id})
  });
  showToast(t('toast_deleted_node', {name: name}));
  loadProfiles();
  fetchStatus();
}

// ── 编辑节点 Edit Modal 逻辑 (与 Android 手机端完全一致的动态条件显示) ──
// 字段显隐唯一来源：docs/transports.md 的字段消费矩阵。
// 与 app 端 TunnelFieldSpec.kt 必须同步维护——过去各处手写 if 导致 wss/h2-TLS 等
// 场景下"字段该显示没显示"的漂移，勿再新增散落的类型判断。
function tunnelFieldSpec(type) {
  const base = {
    proxyAddr: 'host',      // host | range | bare | hidden
    customHost: false, customPath: false,
    proxyAuth: 'none',      // none | userpass | bearer
    tlsCapable: false, tlsFixed: false,
    alpn: false, httpPayload: false, statusCheck: false,
    dnsOptions: false, noise: false, heartbeat: false, xhttpOptions: false,
    kcpOptions: false, udpOptions: false, icmpOptions: false,
    paddingOptions: false, masqueAlpnOptions: false, proxyAddrWhenTlsOnly: false
  };
  const t = (o) => Object.assign({}, base, o);
  switch (type) {
    case 'raw': return t({ tlsCapable: true, proxyAddrWhenTlsOnly: true });
    case 'websocket': return t({ customHost: true, customPath: true, proxyAuth: 'userpass', tlsCapable: true });
    case 'http': return t({ customHost: true, httpPayload: true, proxyAuth: 'userpass', statusCheck: true });
    case 'h2': case 'grpc': return t({ customHost: true, customPath: true, proxyAuth: 'bearer', tlsCapable: true, heartbeat: true, paddingOptions: true });
    case 'h3': case 'webtransport': return t({ customHost: true, customPath: true, proxyAuth: 'bearer', tlsFixed: true, heartbeat: true, paddingOptions: true });
    case 'masque': return t({ customHost: true, customPath: true, proxyAuth: 'bearer', tlsFixed: true, heartbeat: true, paddingOptions: true, masqueAlpnOptions: true });
    case 'quic': return t({ tlsFixed: true });
    case 'xhttp': return t({ customHost: true, customPath: true, proxyAuth: 'bearer', tlsCapable: true, alpn: true, xhttpOptions: true });
    case 'kcptun': return t({ kcpOptions: true });
    case 'udp_custom': return t({ proxyAddr: 'range', udpOptions: true, noise: true });
    case 'dns_custom': return t({ proxyAddr: 'hidden', dnsOptions: true, noise: true });
    case 'icmp_custom': return t({ proxyAddr: 'bare', icmpOptions: true, noise: true });
    default: return t();
  }
}

function onEditAuthTypeChange() {
  const isKey = document.getElementById('edit-node-auth-type').value === 'privatekey';
  document.getElementById('edit-group-pass').style.display = isKey ? 'none' : 'flex';
  document.getElementById('edit-group-key-pass').style.display = isKey ? 'flex' : 'none';
  document.getElementById('edit-group-private-key').style.display = isKey ? 'flex' : 'none';
}

// ── 节点编辑统一校验（镜像 native FieldRules.compute，勿在此之外再写类型分支）──
// 单一事实来源：谓词 + computeFieldVerdict。输入变化只刷新已触碰字段，保存时全量刷新
// 并聚焦首个错误；warn 级（短 PSK、xhttp 无指纹）不阻断，保存后以 toast 呈现。
let LV_TOUCHED = new Set();
let lvWired = false;

const LV_KEY_TO_ID = {
  SSH_ADDR: 'edit-node-ssh-addr', PROXY_ADDR: 'edit-node-proxy-addr',
  SERVER_NAME: 'edit-node-server-name', CUSTOM_PATH: 'edit-node-custom-path',
  DNS_SERVERS: 'edit-node-dns-servers', DNS_DOMAIN: 'edit-node-dns-domain',
  DNS_RECORD_TYPE: 'edit-node-dns-type', DNS_PSK: 'edit-node-dns-psk', DNS_MARKER: 'edit-node-dns-marker',
  NOISE_KEY: 'edit-node-noise-public-key',
  UDP_PSK: 'edit-node-udp-custom-psk', UDP_MAGIC: 'edit-node-udp-custom-magic',
  UDP_MAX_PKT: 'edit-node-udp-custom-max-pkt', UDP_MTU_PROBE: 'edit-node-udp-custom-mtu-probe',
  ICMP_PSK: 'edit-node-icmp-psk', ICMP_MAGIC: 'edit-node-icmp-magic',
  KCP_PASSWORD: 'edit-node-kcp-pass', KCP_SNDWND: 'edit-node-kcp-sndwnd', KCP_RCVWND: 'edit-node-kcp-rcvwnd',
  KCP_MTU: 'edit-node-kcp-mtu', KCP_SMUXVER: 'edit-node-kcp-smuxver', KCP_KEEPALIVE: 'edit-node-kcp-keepalive',
  KCP_DATA_SHARDS: 'edit-node-kcp-data-shards', KCP_PARITY_SHARDS: 'edit-node-kcp-parity-shards',
  HTTP_PAYLOAD: 'edit-node-http-payload',
  AUTH_USER: 'edit-node-auth-user', AUTH_PASS: 'edit-node-auth-pass', AUTH_TOKEN: 'edit-node-auth-token',
  SSH_FINGERPRINT: 'edit-node-ssh-fingerprint', CERT_FINGERPRINT: 'edit-node-cert-fingerprint',
  CHUNK_SIZE: 'edit-node-xhttp-chunk-size', HEARTBEAT: 'edit-node-heartbeat-interval',
  PADDING_MIN_BYTES: 'edit-node-padding-min-bytes', MASQUE_ALPN: 'edit-node-masque-alpn'
};

const lvVal = id => { const e = document.getElementById(id); return e ? (e.value || '').trim() : ''; };

// 谓词（逐条对应 native）
function isHostPortJP(v){ const m=/^([^\s:/]+|\[[0-9a-fA-F:.]+\]):(\d{1,5})$/.exec(v); return !!m && +m[2]>=1 && +m[2]<=65535; }
function isHostPortRangeJP(v){ const m=/^([^\s:/]+|\[[0-9a-fA-F:.]+\]):(\d{1,5}(?:-\d{1,5})?(?:,\d{1,5}(?:-\d{1,5})?)*)$/.exec(v); if(!m) return false; return m[2].split(',').every(part=>{ const b=part.split('-').map(Number); return b.every(n=>n>=1&&n<=65535) && (b.length===1 || b[0]<=b[1]); }); }
function isBareHostJP(v){ return /^(\[[0-9a-fA-F:.]+\]|[A-Za-z0-9][A-Za-z0-9._\-]*)$/.test(v); }
function isFingerprintJP(v){ const s=v.replace(/[:\s]/g,''); return s.length===64 && /^[0-9a-fA-F]+$/.test(s); }
// SSH 主机指纹：64-hex 或 OpenSSH 的 SHA256:<unpadded-base64>（与 Kotlin isSshFingerprint 一致）。
// 43 位 base64（可选补位 =）恰好解码 32 字节，与 App 的解码长度校验等价。
function isSshFingerprintJP(v){
  const s=v.trim();
  if(isFingerprintJP(s)) return true;
  if(!s.startsWith('SHA256:')) return false;
  return /^[A-Za-z0-9+/]{43}=?$/.test(s.slice(7));
}
function isNoisePublicKeyJP(v){ if(!v) return true; if(/^[0-9a-fA-F]{64}$/.test(v)) return true; if(!/^[A-Za-z0-9+/]+={0,2}$/.test(v)) return false; try { return atob(v+'='.repeat((4-v.length%4)%4)).length===32; } catch(_){ return false; } }
function isHexMagicJP(v){ const d=/^0x/i.test(v)?v.slice(2):v; return d.length>0 && d.length<=8 && /^[0-9a-fA-F]+$/.test(d); }
function isUdpMagicJP(v){ return v==='' || isHexMagicJP(v) || new TextEncoder().encode(v).length===4; }
function isIcmpMagicJP(v){ return v==='' || isHexMagicJP(v); }
function isUdpMtuProbeJP(v){ return v==='' || ['auto','on','true','1','off','false','0'].includes(v.toLowerCase()); }
// masque ALPN：SDK 只有 ""(auto)/"h3"/"h2" 三个取值（client_api.go 严格相等，不拆逗号）。
// 历史别名 h3,h2 / h2,h3 已移除（行为与 auto 完全相同，不表达任何 SDK 能力）。
function isMasqueAlpnJP(v){ const s=v.toLowerCase().replace(/\s/g,''); return s==='' || ['auto','h3','h2'].includes(s); }
function isIntJP(v){ return /^[+-]?\d+$/.test(v); }
function intOrBlankInRangeJP(v,min,max){ return v==='' || (isIntJP(v) && +v>=min && +v<=max); }
function intOrBlankOrZeroInJP(v,min,max){ return v==='' || (isIntJP(v) && (+v===0 || (+v>=min && +v<=max))); }
function isChunkSizeJP(v){ return v==='' || (isIntJP(v) && (+v===0 || (+v>=16 && +v<=900))); }
// DNS 上游列表：与 Kotlin isDnsServerList 一致 —— 除了要有条目，
// 每条在 scheme（udp/tcp/tls/dot/https）前缀之后还必须有 host（udp:// 会放行到 host 为空）
function isDnsServerListJP(v){
  const items=v.split(/[,\s]+/).filter(Boolean);
  if(items.length===0) return false;
  return items.every(e=>{
    const i=e.indexOf('://');
    const host=i>=0?e.slice(i+3):e;
    return host.length>0;
  });
}

function readEditorSnapshot(){
  const type = lvVal('edit-node-tunnel-type');
  const spec = tunnelFieldSpec(type);
  let proxyAddr = lvVal('edit-node-proxy-addr');
  if (spec.proxyAddr === 'bare') proxyAddr = proxyAddr.replace(/:\d+$/, ''); // ICMP 无端口：剥掉误输的 :port
  return {
    spec, tlsActive: spec.tlsFixed || (spec.tlsCapable && document.getElementById('edit-node-tls').checked),
    sshAddr: lvVal('edit-node-ssh-addr'), proxyAddr,
    serverName: lvVal('edit-node-server-name'), customPath: lvVal('edit-node-custom-path'),
    dnsServers: lvVal('edit-node-dns-servers'), dnsDomain: lvVal('edit-node-dns-domain'),
    dnsRecordType: lvVal('edit-node-dns-type'), dnsPsk: lvVal('edit-node-dns-psk'), dnsMarker: lvVal('edit-node-dns-marker'),
    noisePublicKey: lvVal('edit-node-noise-public-key'),
    udpPsk: lvVal('edit-node-udp-custom-psk'), udpMagic: lvVal('edit-node-udp-custom-magic'),
    udpMaxPkt: lvVal('edit-node-udp-custom-max-pkt'), udpMtuProbe: lvVal('edit-node-udp-custom-mtu-probe'),
    icmpPsk: lvVal('edit-node-icmp-psk'), icmpMagic: lvVal('edit-node-icmp-magic'),
    kcpPassword: lvVal('edit-node-kcp-pass'), kcpSndwnd: lvVal('edit-node-kcp-sndwnd'), kcpRcvwnd: lvVal('edit-node-kcp-rcvwnd'),
    kcpMtu: lvVal('edit-node-kcp-mtu'), kcpSmuxver: lvVal('edit-node-kcp-smuxver'), kcpKeepalive: lvVal('edit-node-kcp-keepalive'),
    kcpDataShards: lvVal('edit-node-kcp-data-shards'), kcpParityShards: lvVal('edit-node-kcp-parity-shards'),
    httpPayload: lvVal('edit-node-http-payload'),
    authEnabled: document.getElementById('edit-node-proxy-auth').checked,
    authUser: lvVal('edit-node-auth-user'), authPass: lvVal('edit-node-auth-pass'), authToken: lvVal('edit-node-auth-token'),
    verifySshFp: document.getElementById('edit-node-verify-ssh-fp').checked, sshFingerprint: lvVal('edit-node-ssh-fingerprint'),
    verifyCertFp: document.getElementById('edit-node-verify-cert').checked, certFingerprint: lvVal('edit-node-cert-fingerprint'),
    chunkSize: lvVal('edit-node-xhttp-chunk-size'), heartbeat: lvVal('edit-node-heartbeat-interval'),
    paddingMinBytes: lvVal('edit-node-padding-min-bytes'), masqueAlpn: lvVal('edit-node-masque-alpn'),
    sshPassFallbackAvailable: lvVal('edit-node-pass') !== ''
  };
}

function computeFieldVerdict(s){
  const errors = {}, warnings = {};
  const req = 'error_field_required';
  const need = (k,cond,res)=>{ if(!cond) errors[k]=res||req; };
  const warn = (k,cond,res)=>{ if(cond) warnings[k]=res; };
  need('SSH_ADDR', !!s.sshAddr && isHostPortJP(s.sshAddr), 'error_invalid_address_single_port');
  // raw 关 TLS → 直连 ssh_addr，proxy_addr 不校验（与显隐一致）
  if (!(s.spec.proxyAddrWhenTlsOnly && !s.tlsActive)) {
    if(s.spec.proxyAddr==='host') need('PROXY_ADDR', !!s.proxyAddr && isHostPortJP(s.proxyAddr), 'error_invalid_address_single_port');
    else if(s.spec.proxyAddr==='range') need('PROXY_ADDR', !!s.proxyAddr && isHostPortRangeJP(s.proxyAddr), 'error_invalid_address');
    else if(s.spec.proxyAddr==='bare') need('PROXY_ADDR', !!s.proxyAddr && isBareHostJP(s.proxyAddr), 'error_invalid_host_only');
  }
  if(s.tlsActive && s.serverName && /\s/.test(s.serverName)) errors.SERVER_NAME='error_invalid_server_name';
  if(s.spec.customPath && s.customPath && !s.customPath.startsWith('/')) errors.CUSTOM_PATH='error_invalid_path';
  if(s.verifySshFp) need('SSH_FINGERPRINT', !!s.sshFingerprint && isSshFingerprintJP(s.sshFingerprint), 'error_invalid_fingerprint');
  if(s.tlsActive && s.verifyCertFp) need('CERT_FINGERPRINT', !!s.certFingerprint && isFingerprintJP(s.certFingerprint), 'error_invalid_fingerprint');
  warn('CERT_FINGERPRINT', s.spec.xhttpOptions && s.tlsActive && !s.verifyCertFp, 'warn_xhttp_no_fingerprint');
  if(s.spec.proxyAuth!=='none' && s.authEnabled){
    if(s.spec.proxyAuth==='userpass'){ need('AUTH_USER', !!s.authUser); need('AUTH_PASS', !!s.authPass); }
    else need('AUTH_TOKEN', !!s.authToken);
  }
  if(s.spec.httpPayload) need('HTTP_PAYLOAD', !!s.httpPayload);
  if(s.spec.dnsOptions){
    need('DNS_SERVERS', !!s.dnsServers && isDnsServerListJP(s.dnsServers), 'error_invalid_dns_servers');
    need('DNS_DOMAIN', !!s.dnsDomain);
    if(s.noisePublicKey && ['a','aaaa'].includes(s.dnsRecordType.toLowerCase())) errors.DNS_RECORD_TYPE='error_dns_noise_record_type';
    warn('DNS_PSK', s.dnsPsk && s.dnsPsk.length<16, 'warn_psk_short');
    if(s.dnsMarker && (/\s/.test(s.dnsMarker) || s.dnsMarker.length>32)) errors.DNS_MARKER='error_invalid_marker';
  }
  if(s.spec.noise && s.noisePublicKey) need('NOISE_KEY', isNoisePublicKeyJP(s.noisePublicKey), 'error_noise_public_key');
  if(s.spec.udpOptions){
    need('UDP_PSK', !!s.udpPsk || s.sshPassFallbackAvailable, 'error_udp_psk_required');
    warn('UDP_PSK', s.udpPsk && s.udpPsk.length<16, 'warn_psk_short');
    need('UDP_MAGIC', isUdpMagicJP(s.udpMagic), 'error_udp_magic');
    need('UDP_MAX_PKT', intOrBlankInRangeJP(s.udpMaxPkt,0,65535), 'error_invalid_number');
    need('UDP_MTU_PROBE', isUdpMtuProbeJP(s.udpMtuProbe), 'error_invalid_mtu_probe');
  }
  if(s.spec.icmpOptions){
    need('ICMP_PSK', !!s.icmpPsk || s.sshPassFallbackAvailable, 'error_icmp_psk');
    warn('ICMP_PSK', s.icmpPsk && s.icmpPsk.length<16, 'warn_psk_short');
    need('ICMP_MAGIC', isIcmpMagicJP(s.icmpMagic), 'error_icmp_magic');
  }
  if(s.spec.kcpOptions){
    need('KCP_PASSWORD', !!s.kcpPassword, 'error_kcp_password');
    need('KCP_SNDWND', intOrBlankInRangeJP(s.kcpSndwnd,0,65535), 'error_invalid_number');
    need('KCP_RCVWND', intOrBlankInRangeJP(s.kcpRcvwnd,0,65535), 'error_invalid_number');
    need('KCP_MTU', intOrBlankInRangeJP(s.kcpMtu,0,65535), 'error_invalid_number');
    need('KCP_SMUXVER', intOrBlankInRangeJP(s.kcpSmuxver,0,2), 'error_invalid_number');
    need('KCP_KEEPALIVE', intOrBlankInRangeJP(s.kcpKeepalive,0,86400), 'error_invalid_number');
    need('KCP_DATA_SHARDS', intOrBlankInRangeJP(s.kcpDataShards,0,255), 'error_invalid_number');
    need('KCP_PARITY_SHARDS', intOrBlankInRangeJP(s.kcpParityShards,0,255), 'error_invalid_number');
  }
  if(s.spec.xhttpOptions) need('CHUNK_SIZE', isChunkSizeJP(s.chunkSize), 'error_xhttp_chunk_size');
  if(s.spec.heartbeat) need('HEARTBEAT', intOrBlankOrZeroInJP(s.heartbeat,5000,300000), 'error_heartbeat_interval');
  if(s.spec.paddingOptions) need('PADDING_MIN_BYTES', intOrBlankInRangeJP(s.paddingMinBytes,-1,65535), 'error_invalid_number');
  if(s.spec.masqueAlpnOptions) need('MASQUE_ALPN', isMasqueAlpnJP(s.masqueAlpn), 'error_invalid_masque_alpn');
  return {errors, warnings};
}

// ── 字段级 DOM 渲染 ──
function lvGroupFor(key){ const id=LV_KEY_TO_ID[key]; const el=id&&document.getElementById(id); return el?el.closest('.form-group'):null; }
function lvIsVisible(el){ return !!el && el.offsetParent!==null; }
function lvEnsureNodes(g){
  let err=g.querySelector(':scope > .field-error');
  if(!err){ err=document.createElement('span'); err.className='field-error'; g.appendChild(err); }
  let wr=g.querySelector(':scope > .field-warn');
  if(!wr){ wr=document.createElement('span'); wr.className='field-warn'; g.appendChild(wr); }
  return {err,wr};
}
function lvPaint(key, force, verdict){
  const g=lvGroupFor(key); if(!g) return;
  const el=document.getElementById(LV_KEY_TO_ID[key]);
  if(!lvIsVisible(el)){ g.classList.remove('is-invalid','is-warn'); return; } // 隐藏字段：顺带清错误
  const {err,wr}=lvEnsureNodes(g);
  const ekey=verdict.errors[key], wkey=verdict.warnings[key];
  const show=force || LV_TOUCHED.has(key);
  const showErr=!!ekey && show;
  g.classList.toggle('is-invalid', showErr);
  if(showErr) err.textContent=t(ekey);
  if(!showErr && wkey && show){ g.classList.add('is-warn'); wr.textContent=t(wkey); }
  else g.classList.remove('is-warn');
}
function lvPaintAll(keys, force){
  const v=computeFieldVerdict(readEditorSnapshot());
  const target=keys?Array.from(keys):Object.keys(LV_KEY_TO_ID);
  for(const k of target) lvPaint(k, force, v);
  return v;
}
function wireLiveValidation(){
  if(lvWired) return; lvWired=true;
  for(const [key,id] of Object.entries(LV_KEY_TO_ID)){
    const el=document.getElementById(id); if(!el) continue;
    const evt=(el.tagName==='SELECT'||el.type==='checkbox')?'change':'input';
    el.addEventListener(evt, ()=>{ LV_TOUCHED.add(key); lvPaint(key,false); });
  }
  const structural=[['edit-node-tunnel-type',null],['edit-node-tls',null],
    ['edit-node-proxy-auth',null],['edit-node-verify-ssh-fp',null],
    ['edit-node-verify-cert',null],['edit-node-dns-type','DNS_RECORD_TYPE']];
  for(const [id,key] of structural){
    const el=document.getElementById(id); if(!el) continue;
    el.addEventListener('change', ()=>{ if(key) LV_TOUCHED.add(key); lvPaintAll(null,false); });
  }
}
function lvResetTouched(){ LV_TOUCHED=new Set(); lvPaintAll(null,false); }

function editAlpnOptions() {
  // 仅 xhttp 会用到（updateEditModalTunnelFields 按 spec.alpn 门控）。
  // docs/transports.md §5：raw/ws/quic/masque 的 ALPN 均固定不可配。
  return [['h3,h2,http/1.1', null], ['h3,h2', 'opt_alpn_h3_h2'], ['h2,h3', 'opt_alpn_h2_h3'],
    ['h3', 'opt_alpn_h3'], ['h2', 'opt_alpn_h2'], ['http/1.1', null], ['h2,http/1.1', null]];
}

function tlsActiveOf(spec, tunnelTlsEnabled) {
  return spec.tlsFixed || (spec.tlsCapable && tunnelTlsEnabled);
}

function updateEditModalTunnelFields() {
  const selected = document.getElementById('edit-node-tunnel-type').value;
  const spec = tunnelFieldSpec(selected);
  const tlsToggle = document.getElementById('edit-group-tls-toggle');
  if (tlsToggle) tlsToggle.style.display = spec.tlsCapable ? 'flex' : 'none';
  const tlsOn = spec.tlsFixed || (spec.tlsCapable && document.getElementById('edit-node-tls').checked);

  // 1. Proxy Addr（ICMP 对端只有 IP；DNS 隧道不消费此字段）
  // raw 关 TLS → myssh 直连 ssh_addr（纯 SSH，无隧道），proxy_addr 不再使用 → 隐藏
  const proxyAddrVisible = spec.proxyAddr !== 'hidden' && (!spec.proxyAddrWhenTlsOnly || tlsOn);
  document.getElementById('edit-group-proxy-addr').style.display = proxyAddrVisible ? 'flex' : 'none';
  const proxyLabel = document.getElementById('t-label-edit-proxy-addr');
  if (proxyLabel) proxyLabel.textContent = spec.proxyAddr === 'bare' ? t('label_edit_proxy_icmp') : t('label_edit_proxy_addr');
  // Show port-range helper hint only for range-capable types (udp_custom)
  const rangeHint = document.getElementById('t-hint-proxy-range');
  if (rangeHint) {
    rangeHint.textContent = t('hint_proxy_range');
    rangeHint.style.display = spec.proxyAddr === 'range' ? 'block' : 'none';
  }

  // 2. Custom Host（按消费矩阵；与 TLS 无关——tunnel_ws.go/tunnel_h2.go 公共路径）
  document.getElementById('edit-group-custom-host').style.display = spec.customHost ? 'flex' : 'none';

  // 3. Server Name（仅 TLS 生效时）
  document.getElementById('edit-group-server-name').style.display = tlsOn ? 'flex' : 'none';

  // 4. Custom Path（webui 无 masque 启用开关；保存时非空即启用 enableCustomPath）
  document.getElementById('edit-group-custom-path').style.display = spec.customPath ? 'flex' : 'none';

  // 5. ALPN：仅 xhttp 消费（docs §5：raw/ws/quic/masque 全部固定不可配）
  const alpnEl = document.getElementById('edit-node-alpn');
  const prevAlpn = alpnEl.value;
  alpnEl.innerHTML = spec.alpn ? editAlpnOptions().map(o =>
    `<option value="${o[0]}">${o[1] ? t(o[1]) : o[0]}</option>`).join('') : '';
  if ([...alpnEl.options].some(opt => opt.value === prevAlpn)) alpnEl.value = prevAlpn;
  document.getElementById('edit-group-alpn').style.display = spec.alpn ? 'flex' : 'none';

  // 6. HTTP Payload
  document.getElementById('edit-group-http-payload-container').style.display = spec.httpPayload ? 'flex' : 'none';

  // 7. Certificate Fingerprint（TLS 生效时）
  document.getElementById('edit-group-cert-fingerprint-row').style.display = tlsOn ? 'flex' : 'none';
  toggleCertFingerprintInput();

  // 8. Proxy Auth（凭据注入在公共路径，与 TLS 无关）
  document.getElementById('edit-group-proxy-auth-container').style.display = spec.proxyAuth !== 'none' ? 'flex' : 'none';
  toggleProxyAuthInputs();

  // 9. DNS Container
  document.getElementById('edit-group-dns-container').style.display = spec.dnsOptions ? 'grid' : 'none';

  // 10. KCP Container
  document.getElementById('edit-group-kcp-container').style.display = spec.kcpOptions ? 'grid' : 'none';

  // 11. UDP Custom Container
  document.getElementById('edit-group-udp-custom-container').style.display = spec.udpOptions ? 'grid' : 'none';

  // 12. Noise Public Key Container (DNS & UDP Custom & ICMP Custom)
  document.getElementById('edit-group-noise-container').style.display = spec.noise ? 'flex' : 'none';

  // 12b. ICMP Custom Container (SSH-over-ICMP)
  document.getElementById('edit-group-icmp-container').style.display = spec.icmpOptions ? 'grid' : 'none';

  // 13. XHTTP Chunk Size Container (XHTTP)
  document.getElementById('edit-group-xhttp-chunk-container').style.display = spec.xhttpOptions ? 'grid' : 'none';

  // 14. Stream Heartbeat Container (h2 family tunnels)
  document.getElementById('edit-group-heartbeat-container').style.display = spec.heartbeat ? 'grid' : 'none';

  // 14a. Padding Min Bytes (h2 family tunnels + masque)；与 heartbeat 解耦，按 spec.paddingOptions 显隐
  const paddingGroup = document.getElementById('edit-group-padding-min-bytes');
  if (paddingGroup) paddingGroup.style.display = spec.paddingOptions ? 'grid' : 'none';

  // 14b. Masque ALPN selector (masque only)
  const masqueAlpnGroup = document.getElementById('edit-group-masque-alpn');
  if (masqueAlpnGroup) masqueAlpnGroup.style.display = spec.masqueAlpnOptions ? 'grid' : 'none';

  // 15. HTTP 状态检测开关（仅 http）
  const statusRow = document.getElementById('edit-group-disable-status-check');
  if (statusRow) statusRow.style.display = spec.statusCheck ? 'flex' : 'none';

  // 16. 显隐变化后刷新已触碰字段的校验（隐藏字段顺带清除错误）
  lvPaintAll(null, false);}


function toggleSSHFingerprintInput() {
  const checked = document.getElementById('edit-node-verify-ssh-fp').checked;
  document.getElementById('edit-group-ssh-fingerprint').style.display = checked ? 'block' : 'none';
}

let currentDetailsApplyFp = '';
let currentDetailsTargetId = '';
let currentDetailsCheckboxId = '';

async function fetchSSHFingerprint() {
  const sshAddr = document.getElementById('edit-node-ssh-addr').value.trim();
  if (!sshAddr) {
    showToast(t('toast_missing_ssh_addr'));
    document.getElementById('edit-node-ssh-addr').focus();
    return;
  }
  const btn = document.getElementById('t-btn-fetch-ssh-fp');
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('/api/diagnostics/ssh-fingerprint?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ sshAddr })
    });
    const data = await res.json();
    if (res.ok && data.status === 'success' && data.fingerprint) {
      document.getElementById('edit-node-ssh-fingerprint').value = data.fingerprint;
      document.getElementById('edit-node-verify-ssh-fp').checked = true;
      document.getElementById('edit-group-ssh-fingerprint').style.display = 'block';
      showToast(t('toast_ssh_fp_success'));
    } else {
      showToast(t('toast_fetch_failed', { msg: data.error || 'Unknown error' }));
    }
  } catch (e) {
    showToast(t('toast_fetch_failed', { msg: e.message }));
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function fetchSSHDetails() {
  const sshAddr = document.getElementById('edit-node-ssh-addr').value.trim();
  if (!sshAddr) {
    showToast(t('toast_missing_ssh_addr'));
    document.getElementById('edit-node-ssh-addr').focus();
    return;
  }
  const btn = document.getElementById('t-btn-details-ssh');
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('/api/diagnostics/ssh-fingerprint?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ sshAddr })
    });
    const data = await res.json();
    if (res.ok && data.status === 'success' && data.detailsJson) {
      const d = JSON.parse(data.detailsJson);
      const text = `🌐 ${t('info_target_address')}: ${d.address || sshAddr}\n` +
        `🏷️ ${t('info_server_banner')}: ${d.banner || 'N/A'}\n` +
        `🔑 ${t('info_public_key_type')}: ${d.key_type || 'N/A'}\n` +
        `⚡ ${t('info_handshake_latency')}: ${d.latency_ms || 0} ms\n\n` +
        `🛡️ ${t('info_sha256_fingerprint')}:\n${d.fingerprint_sha256 || 'N/A'}\n\n` +
        `🔒 ${t('info_md5_fingerprint')}:\n${d.fingerprint_md5 || 'N/A'}`;
      openDetailsModal(t('ssh_details_title'), text, d.fingerprint_sha256 || data.fingerprint, 'edit-node-ssh-fingerprint', 'edit-node-verify-ssh-fp');
    } else {
      showToast(t('toast_fetch_failed', { msg: data.error || 'Unknown error' }));
    }
  } catch (e) {
    showToast(t('toast_fetch_failed', { msg: e.message }));
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function fetchCertFingerprint() {
  let target = document.getElementById('edit-node-proxy-addr').value.trim();
  if (!target) target = document.getElementById('edit-node-ssh-addr').value.trim();
  if (!target) {
    showToast(t('toast_missing_proxy_addr'));
    document.getElementById('edit-node-proxy-addr').focus();
    return;
  }
  let serverName = document.getElementById('edit-node-server-name').value.trim();
  if (!serverName) serverName = document.getElementById('edit-node-custom-host').value.trim();

  const btn = document.getElementById('t-btn-fetch-cert-fp');
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('/api/diagnostics/tls-fingerprint?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ target, serverName })
    });
    const data = await res.json();
    if (res.ok && data.status === 'success' && data.fingerprint) {
      document.getElementById('edit-node-cert-fingerprint').value = data.fingerprint;
      document.getElementById('edit-node-verify-cert').checked = true;
      document.getElementById('edit-group-cert-fingerprint').style.display = 'block';
      showToast(t('toast_cert_fp_success'));
    } else {
      showToast(t('toast_fetch_failed', { msg: data.error || 'Unknown error' }));
    }
  } catch (e) {
    showToast(t('toast_fetch_failed', { msg: e.message }));
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function fetchCertDetails() {
  let target = document.getElementById('edit-node-proxy-addr').value.trim();
  if (!target) target = document.getElementById('edit-node-ssh-addr').value.trim();
  if (!target) {
    showToast(t('toast_missing_proxy_addr'));
    document.getElementById('edit-node-proxy-addr').focus();
    return;
  }
  let serverName = document.getElementById('edit-node-server-name').value.trim();
  if (!serverName) serverName = document.getElementById('edit-node-custom-host').value.trim();

  const btn = document.getElementById('t-btn-details-cert');
  if (btn) btn.disabled = true;
  try {
    const res = await fetch('/api/diagnostics/tls-fingerprint?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ target, serverName })
    });
    const data = await res.json();
    if (res.ok && data.status === 'success' && data.detailsJson) {
      const d = JSON.parse(data.detailsJson);
      const sans = (d.dns_names && d.dns_names.length) ? d.dns_names.join(', ') : 'N/A';
      const expireStatus = d.is_expired ? t('info_cert_expired') : t('info_days_remaining', {days: d.days_remaining});
      const durationStr = t('info_duration_ms', {ms: d.latency_ms || 0});
      const text = `🌐 ${t('info_target_address')}: ${d.target || target}\n` +
        `🏷️ ${t('info_sni')}: ${d.sni || 'N/A'}\n` +
        `📜 ${t('info_subject')}: ${d.subject || 'N/A'}\n` +
        `🏢 ${t('info_issuer')}: ${d.issuer || 'N/A'}\n` +
        `🌍 ${t('info_sans')}: ${sans}\n` +
        `⏳ ${t('info_cert_validity')}: ${expireStatus}\n` +
        `🔒 ${t('info_signature_alg')}: ${d.signature_algorithm || 'N/A'}\n` +
        `🔑 ${t('info_public_key_alg')}: ${d.public_key_algorithm || 'N/A'}\n` +
        `🛡️ ${t('info_tls_version')}: ${d.tls_version || 'N/A'}\n` +
        `⚡ ${t('info_alpn_negotiation')}: ${d.negotiated_protocol || 'N/A'} (${durationStr})\n\n` +
        `🛡️ ${t('info_sha256_fingerprint')}:\n${d.fingerprint_sha256 || 'N/A'}\n\n` +
        `🔒 ${t('info_sha1_fingerprint')}:\n${d.fingerprint_sha1 || 'N/A'}`;
      openDetailsModal(t('tls_details_title'), text, d.fingerprint_sha256 || data.fingerprint, 'edit-node-cert-fingerprint', 'edit-node-verify-cert');
    } else {
      showToast(t('toast_fetch_failed', { msg: data.error || 'Unknown error' }));
    }
  } catch (e) {
    showToast(t('toast_fetch_failed', { msg: e.message }));
  } finally {
    if (btn) btn.disabled = false;
  }
}

function openDetailsModal(title, content, applyFp, targetInputId, targetCheckboxId) {
  currentDetailsApplyFp = applyFp;
  currentDetailsTargetId = targetInputId;
  currentDetailsCheckboxId = targetCheckboxId;
  document.getElementById('details-modal-title').textContent = title;
  document.getElementById('details-modal-content').textContent = content;
  document.getElementById('details-modal').classList.add('active');
}

function closeDetailsModal() {
  document.getElementById('details-modal').classList.remove('active');
}

function copyDetailsContent() {
  const text = document.getElementById('details-modal-content').textContent;
  copyToClipboard(text);
  showToast(t('toast_details_copied'));
}

function applyDetailsFingerprint() {
  if (currentDetailsApplyFp && currentDetailsTargetId) {
    document.getElementById(currentDetailsTargetId).value = currentDetailsApplyFp;
    if (currentDetailsCheckboxId) {
      document.getElementById(currentDetailsCheckboxId).checked = true;
      if (currentDetailsCheckboxId === 'edit-node-verify-ssh-fp') {
        document.getElementById('edit-group-ssh-fingerprint').style.display = 'block';
      } else if (currentDetailsCheckboxId === 'edit-node-verify-cert') {
        document.getElementById('edit-group-cert-fingerprint').style.display = 'block';
      }
    }
    showToast(t('toast_fp_applied'));
    closeDetailsModal();
  }
}

function toggleCertFingerprintInput() {
  const isChecked = document.getElementById('edit-node-verify-cert').checked;
  const isRowVisible = document.getElementById('edit-group-cert-fingerprint-row').style.display !== 'none';
  document.getElementById('edit-group-cert-fingerprint').style.display = (isChecked && isRowVisible) ? 'flex' : 'none';
}

function toggleProxyAuthInputs() {
  const spec = tunnelFieldSpec(document.getElementById('edit-node-tunnel-type').value);
  const isContainerVisible = document.getElementById('edit-group-proxy-auth-container').style.display !== 'none';
  const isAuthEnabled = document.getElementById('edit-node-proxy-auth').checked && isContainerVisible;

  // 凭据形态由消费矩阵决定（tunnel_ws.go/h2/xhttp），与 TLS 开关无关
  document.getElementById('edit-group-auth-token').style.display = (isAuthEnabled && spec.proxyAuth === 'bearer') ? 'flex' : 'none';
  document.getElementById('edit-group-auth-userpass').style.display = (isAuthEnabled && spec.proxyAuth === 'userpass') ? 'grid' : 'none';
}

async function openEditModal(id) {
  const p = allProfiles.find(item => item.id === id);
  if (!p) return;

  if (!allApps.length) {
    await loadApps();
  }

  // 1. Basic SSH
  document.getElementById('edit-profile-id').value = p.id;
  document.getElementById('edit-node-name').value = p.name || '';
  document.getElementById('edit-node-ssh-addr').value = p.sshAddr || '';
  document.getElementById('edit-node-note').value = p.note || '';
  document.getElementById('edit-node-favorite').checked = !!p.favorite;
  document.getElementById('edit-node-auth-type').value = p.authType || 'password';
  document.getElementById('edit-node-user').value = p.user || '';
  document.getElementById('edit-node-pass').value = p.pass || '';
  document.getElementById('edit-node-key-pass').value = p.keyPass || '';
  document.getElementById('edit-node-private-key').value = p.privateKey || '';
  onEditAuthTypeChange();

  // 2. Tunnel Type & Dynamic Fields
  document.getElementById('edit-node-tunnel-type').value = p.tunnelType || 'raw';
  document.getElementById('edit-node-proxy-addr').value = p.proxyAddr || '';
  document.getElementById('edit-node-custom-host').value = p.customHost || '';
  document.getElementById('edit-node-server-name').value = p.serverName || '';
  document.getElementById('edit-node-custom-path').value = p.customPath || '';
  const tlsCb = document.getElementById('edit-node-tls');
  if (tlsCb) tlsCb.checked = !!p.tunnelTlsEnabled;
  // ALPN 目标值先暂存，等 updateEditModalTunnelFields 重建完候选项再赋值
  const alpnFill = p.tunnelType === 'raw'
    ? (['h1', 'h2'].includes(p.alpn) ? p.alpn : 'auto')
    : (p.alpn || 'h2,http/1.1');
  document.getElementById('edit-node-icmp-psk').value = p.icmpCustomPsk || '';
  document.getElementById('edit-node-icmp-magic').value = p.icmpCustomMagic || '';
  // icmpCustomFamily removed in myssh 152c556; select hidden, always empty.
  document.getElementById('edit-node-icmp-mtu').value = ['', 'probe', 'auto', 'fixed'].includes(p.icmpCustomMtuMode) ? p.icmpCustomMtuMode : '';
  document.getElementById('edit-node-icmp-max-payload').value = p.icmpCustomMaxPayload || '';
  document.getElementById('edit-node-icmp-pace').value = p.icmpCustomPaceMS || '';
  document.getElementById('edit-node-icmp-id-range').value = p.icmpCustomIdRange || '';
  document.getElementById('edit-node-http-payload').value = p.httpPayload || '';
  document.getElementById('edit-node-disable-status-check').checked = !!p.disableStatusCheck;

  // 3. Cert Fingerprint & Proxy Auth
  document.getElementById('edit-node-verify-cert').checked = !!p.verifyCertFingerprint;
  document.getElementById('edit-node-cert-fingerprint').value = p.serverCertFingerprint || '';

  document.getElementById('edit-node-proxy-auth').checked = !!p.proxyAuthRequired;
  document.getElementById('edit-node-auth-token').value = p.proxyAuthToken || '';
  document.getElementById('edit-node-auth-user').value = p.proxyAuthUser || '';
  document.getElementById('edit-node-auth-pass').value = p.proxyAuthPass || '';

  // 4. DNS, KCP, UDP Custom
  document.getElementById('edit-node-dns-servers').value = p.dnsTunnelServers || '';
  document.getElementById('edit-node-dns-domain').value = p.dnsTunnelDomain || '';
  document.getElementById('edit-node-dns-type').value = p.dnsTunnelType || 'txt';
  document.getElementById('edit-node-dns-edns0').checked = !!p.dnsTunnelEDNS0;
  document.getElementById('edit-node-dns-psk').value = p.dnsTunnelPsk || '';
  document.getElementById('edit-node-dns-marker').value = p.dnsTunnelMarker || '';

  document.getElementById('edit-node-kcp-pass').value = p.kcpPassword || '';
  document.getElementById('edit-node-kcp-crypt').value = p.kcpCrypt || 'none';
  document.getElementById('edit-node-kcp-data-shards').value = p.kcpDataShards ?? 10;
  document.getElementById('edit-node-kcp-mode').value = ['fast', 'normal', 'fast2', 'fast3'].includes(p.kcpMode) ? p.kcpMode : 'fast';
  document.getElementById('edit-node-kcp-sndwnd').value = p.kcpSndWnd || '';
  document.getElementById('edit-node-kcp-rcvwnd').value = p.kcpRcvWnd || '';
  document.getElementById('edit-node-kcp-mtu').value = p.kcpMtu || '';
  document.getElementById('edit-node-kcp-nocomp').value = p.kcpNoComp ? '1' : '0';
  document.getElementById('edit-node-kcp-smuxver').value = p.kcpSmuxVer || '';
  document.getElementById('edit-node-kcp-keepalive').value = p.kcpKeepAlive || '';
  document.getElementById('edit-node-kcp-parity-shards').value = p.kcpParityShards ?? 3;

  document.getElementById('edit-node-udp-custom-psk').value = p.udpCustomPsk || '';
  document.getElementById('edit-node-udp-custom-magic').value = p.udpCustomMagic || '';
  document.getElementById('edit-node-udp-custom-paths').value = p.udpCustomPaths || '';
  document.getElementById('edit-node-udp-custom-sockets').value = p.udpCustomSockets || '';
  document.getElementById('edit-node-udp-custom-send-window').value = p.udpCustomSendWindow || '';
  document.getElementById('edit-node-udp-custom-max-pkt').value = p.udpCustomMaxPkt || '';
  document.getElementById('edit-node-udp-custom-mtu-probe').value = ['auto', 'on', 'off'].includes(p.udpCustomMtuProbe) ? p.udpCustomMtuProbe : 'auto';
  document.getElementById('edit-node-noise-public-key').value = p.tunnelType === 'dns_custom'
    ? (p.dnsTunnelPublicKey || p.noisePublicKey || '')
    : (p.udpCustomPublicKey || p.noisePublicKey || '');
  document.getElementById('edit-node-xhttp-chunk-size').value = p.xhttpChunkSizeKB || '';
  document.getElementById('edit-node-xhttp-stream-mode').value = ['auto', 'stream', 'poll'].includes(p.xhttpStreamMode) ? p.xhttpStreamMode : 'auto';
  document.getElementById('edit-node-bind-interface').value = p.bindInterface || '';
  document.getElementById('edit-node-heartbeat-interval').value = p.heartbeatIntervalMs || '';
  document.getElementById('edit-node-padding-min-bytes').value = p.paddingMinBytes || '';
  // 旧存档可能残留已废弃的 h3,h2 / h2,h3（曾是 auto 的别名）：不在候选集内 => 归一到 auto
  const _mav = (p.masqueAlpn || '').trim().toLowerCase();
  document.getElementById('edit-node-masque-alpn').value =
      (['h3', 'h2'].includes(_mav)) ? _mav : 'auto';

  updateEditModalTunnelFields();
  // 重建完成后再落 ALPN 目标值；不在候选集中则回退到首项
  const alpnEl = document.getElementById('edit-node-alpn');
  alpnEl.value = alpnFill;
  if (alpnEl.value !== alpnFill) alpnEl.selectedIndex = 0;

  // 5. DNS Override
  const dnsOverride = !!p.dnsOverride;
  document.getElementById('edit-node-dns-override').checked = dnsOverride;
  document.getElementById('edit-node-remote-dns').value = p.remoteDns || '';
  document.getElementById('edit-node-local-dns').value = p.localDns || '';
  document.getElementById('edit-node-udpgw-version').value = p.udpgwVersion || 'tun2proxy';
  document.getElementById('edit-node-udpgw-addr').value = p.udpgwAddr || '127.0.0.1:7300';
  document.getElementById('edit-node-geosite-direct').value = p.geositeDirect || '';
  document.getElementById('edit-node-geoip-direct').value = p.geoipDirect || '';
  toggleNodeDnsOverrideGroup();

  // 6. App Filter Override
  const appOverride = !!p.appFilterOverride;
  document.getElementById('edit-node-app-override').checked = appOverride;
  const isAllow = (p.filterMode === 1);
  document.getElementById('mode-node-allow').checked = isAllow;
  document.getElementById('mode-node-disallow').checked = !isAllow;

  const selectedPkgs = new Set((p.filterApps || '').split(',').map(s => s.trim()).filter(Boolean));
  allNodeApps = allApps.map(a => ({
    ...a,
    isSelected: selectedPkgs.has(a.packageName)
  }));
  renderNodeApps(allNodeApps);
  toggleNodeAppOverrideGroup();

  document.getElementById('edit-modal').classList.add('active');

  // 初始化实时校验：首次打开挂载监听，重开清空已触碰标记与既有错误
  wireLiveValidation();
  lvResetTouched();
}

function closeEditModal() {
  document.getElementById('edit-modal').classList.remove('active');
}

function toggleNodeDnsOverrideGroup() {
  const isChecked = document.getElementById('edit-node-dns-override').checked;
  document.getElementById('node-dns-override-group').style.display = isChecked ? 'grid' : 'none';
}

function toggleNodeAppOverrideGroup() {
  const isChecked = document.getElementById('edit-node-app-override').checked;
  document.getElementById('node-app-override-group').style.display = isChecked ? 'flex' : 'none';
}

function renderNodeApps(apps) {
  const container = document.getElementById('node-app-list-container');
  if (!apps.length) {
    container.innerHTML = '<div style="color:var(--text-muted);font-size:0.85rem;padding:12px">' + t('no_apps') + '</div>';
    return;
  }
  container.innerHTML = apps.map(a => {
    const versionStr = a.versionName ? `v${a.versionName}` : '';
    const subText = [a.packageName, versionStr].filter(Boolean).join(' • ');
    return `
    <div class="app-item ${a.isSelected ? 'selected' : ''}" onclick="toggleNodeAppSelection('${a.packageName}')">
      <img class="app-icon" src="/api/app-icon?pkg=${encodeURIComponent(a.packageName)}&token=${token}" loading="lazy" alt="icon" onerror="this.style.opacity='0.2'" />
      <div class="app-info">
        <div class="app-header">
          <span class="app-name">${a.appName}</span>
          ${a.isSystem ? '<span class="app-badge-sys">SYSTEM</span>' : ''}
        </div>
        <span class="app-sub">${subText}</span>
      </div>
      <input type="checkbox" ${a.isSelected ? 'checked' : ''} onclick="event.stopPropagation(); toggleNodeAppSelection('${a.packageName}')">
    </div>
  `}).join('');
}

function toggleNodeAppSelection(pkg) {
  const item = allNodeApps.find(a => a.packageName === pkg);
  if (item) {
    item.isSelected = !item.isSelected;
    filterNodeAppList();
  }
}

function filterNodeAppList() {
  const q = (document.getElementById('node-app-search')?.value || '').toLowerCase();
  const filtered = allNodeApps.filter(a => a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q));
  renderNodeApps(filtered);
}

function toggleSelectAllNodeApps() {
  const anyUnselected = allNodeApps.some(a => !a.isSelected);
  allNodeApps.forEach(a => a.isSelected = anyUnselected);
  filterNodeAppList();
}

async function submitEditProfile() {
  const id = document.getElementById('edit-profile-id').value;
  const name = document.getElementById('edit-node-name').value.trim();
  const sshAddr = document.getElementById('edit-node-ssh-addr').value.trim();
  const note = document.getElementById('edit-node-note').value.trim();
  const favorite = document.getElementById('edit-node-favorite').checked;
  const authType = document.getElementById('edit-node-auth-type').value;
  const user = document.getElementById('edit-node-user').value.trim();
  const pass = document.getElementById('edit-node-pass').value;
  const keyPass = document.getElementById('edit-node-key-pass').value;
  const privateKey = document.getElementById('edit-node-private-key').value;

  const tunnelType = document.getElementById('edit-node-tunnel-type').value;
  const spec = tunnelFieldSpec(tunnelType);
  let proxyAddr = document.getElementById('edit-node-proxy-addr').value.trim();
  const customHost = document.getElementById('edit-node-custom-host').value.trim();
  const serverName = document.getElementById('edit-node-server-name').value.trim();
  const customPath = document.getElementById('edit-node-custom-path').value.trim();
  // MASQUE has a built-in SDK path: an empty value keeps that default, while a
  // non-empty value explicitly overrides it. Other path-based tunnels always
  // consume customPath directly and have no enable/disable switch.
  const enableCustomPath = tunnelType === 'masque' ? customPath.length > 0 : undefined;
  const tunnelTlsEnabled = document.getElementById('edit-node-tls').checked;
  // ALPN 仅 xhttp 消费（docs §5）；其他类型恒清空
  const alpn = spec.alpn ? document.getElementById('edit-node-alpn').value : '';
  const httpPayload = document.getElementById('edit-node-http-payload').value.trim();
  const disableStatusCheck = document.getElementById('edit-node-disable-status-check').checked;

  const verifyFingerprint = document.getElementById('edit-node-verify-ssh-fp').checked;
  const serverFingerprint = document.getElementById('edit-node-ssh-fingerprint').value.trim();
  const verifyCertFingerprint = document.getElementById('edit-node-verify-cert').checked;
  const serverCertFingerprint = document.getElementById('edit-node-cert-fingerprint').value.trim();

  const proxyAuthRequired = document.getElementById('edit-node-proxy-auth').checked;
  const proxyAuthToken = document.getElementById('edit-node-auth-token').value.trim();
  const proxyAuthUser = document.getElementById('edit-node-auth-user').value.trim();
  const proxyAuthPass = document.getElementById('edit-node-auth-pass').value;

  // ── 统一校验（与实时校验同源：computeFieldVerdict）──
  // 全量强制渲染每个字段的红字，聚焦并阻断首个错误；告警（warn 级）收集后在保存成功后提示。
  if (spec.proxyAddr === 'bare') proxyAddr = proxyAddr.replace(/:\d+$/, ''); // ICMP 无端口：剥掉误输的 :port
  const verdict = lvPaintAll(null, true);
  // 告警：可见字段已用 field-warn 实时呈现，无需重复；仅对无法在字段上呈现的告警（如
  // 关闭证书固定时的 CERT_FINGERPRINT，其输入框隐藏）在保存成功后以 toast 兜底。
  const warnMsgs = Object.entries(verdict.warnings)
      .filter(([k]) => !lvIsVisible(document.getElementById(LV_KEY_TO_ID[k])))
      .map(([, v]) => v);
  const firstErr = Object.keys(LV_KEY_TO_ID).find(k => verdict.errors[k]);
  if (firstErr) {
    const fel = document.getElementById(LV_KEY_TO_ID[firstErr]);
    if (fel) { fel.focus(); fel.scrollIntoView({ behavior: 'smooth', block: 'center' }); }
    showToast(t(verdict.errors[firstErr]));
    return;
  }

  const dnsTunnelServers = document.getElementById('edit-node-dns-servers').value.trim();
  const dnsTunnelDomain = document.getElementById('edit-node-dns-domain').value.trim();
  const dnsTunnelType = document.getElementById('edit-node-dns-type').value;
  const dnsTunnelEDNS0 = document.getElementById('edit-node-dns-edns0').checked;

  const kcpPassword = document.getElementById('edit-node-kcp-pass').value;
  const kcpCrypt = document.getElementById('edit-node-kcp-crypt').value;
  const kcpDataShards = parseInt(document.getElementById('edit-node-kcp-data-shards').value) || 10;
  const kcpParityShards = parseInt(document.getElementById('edit-node-kcp-parity-shards').value) || 3;
  const kcpMode = document.getElementById('edit-node-kcp-mode').value;
  const kcpSndWnd = Math.max(0, parseInt(document.getElementById('edit-node-kcp-sndwnd').value) || 0);
  const kcpRcvWnd = Math.max(0, parseInt(document.getElementById('edit-node-kcp-rcvwnd').value) || 0);
  const kcpMtu = Math.max(0, parseInt(document.getElementById('edit-node-kcp-mtu').value) || 0);
  const kcpNoComp = document.getElementById('edit-node-kcp-nocomp').value === '1';
  const kcpSmuxVer = Math.max(0, parseInt(document.getElementById('edit-node-kcp-smuxver').value) || 0);
  const kcpKeepAlive = Math.max(0, parseInt(document.getElementById('edit-node-kcp-keepalive').value) || 0);

  const udpCustomPsk = document.getElementById('edit-node-udp-custom-psk').value;
  const udpCustomMagic = document.getElementById('edit-node-udp-custom-magic').value.trim();
  const udpCustomPaths = Math.max(0, parseInt(document.getElementById('edit-node-udp-custom-paths').value) || 0);
  const udpCustomSockets = Math.max(0, parseInt(document.getElementById('edit-node-udp-custom-sockets').value) || 0);
  const udpCustomSendWindow = Math.max(0, parseInt(document.getElementById('edit-node-udp-custom-send-window').value) || 0);
  const udpCustomMaxPkt = Math.max(0, parseInt(document.getElementById('edit-node-udp-custom-max-pkt').value) || 0);
  const udpCustomMtuProbe = document.getElementById('edit-node-udp-custom-mtu-probe').value;

  // ICMP Custom（此前 payload 引用了未声明变量 → 所有 webui 保存 ReferenceError）
  const icmpCustomPsk = document.getElementById('edit-node-icmp-psk').value.trim();
  const icmpCustomMagicRaw = document.getElementById('edit-node-icmp-magic').value.trim();
  const icmpCustomMagic = icmpCustomMagicRaw;
  // icmpCustomFamily removed in myssh 152c556
  const icmpCustomMtuMode = document.getElementById('edit-node-icmp-mtu').value;
  const icmpCustomMaxPayload = Math.max(0, parseInt(document.getElementById('edit-node-icmp-max-payload').value) || 0);
  const icmpCustomPaceMS = Math.max(0, parseInt(document.getElementById('edit-node-icmp-pace').value) || 0);
  const icmpCustomIdRange = document.getElementById('edit-node-icmp-id-range').value.trim();

  const dnsTunnelPsk = document.getElementById('edit-node-dns-psk').value.trim();
  const dnsTunnelMarker = document.getElementById('edit-node-dns-marker').value.trim();

  const noisePublicKey = document.getElementById('edit-node-noise-public-key').value.trim();
  const dnsTunnelPublicKey = tunnelType === 'dns_custom' ? noisePublicKey : undefined;
  const udpCustomPublicKey = tunnelType === 'udp_custom' ? noisePublicKey : undefined;
  const icmpCustomPublicKey = tunnelType === 'icmp_custom' ? noisePublicKey : undefined;
  const xhttpChunkRaw = document.getElementById('edit-node-xhttp-chunk-size').value.trim();
  const xhttpStreamMode = document.getElementById('edit-node-xhttp-stream-mode').value;
  const bindInterface = document.getElementById('edit-node-bind-interface').value.trim();
  const heartbeatRaw = document.getElementById('edit-node-heartbeat-interval').value.trim();
  const xhttpChunkSizeKB = xhttpChunkRaw === '' ? 0 : Number(xhttpChunkRaw);
  const heartbeatIntervalMs = heartbeatRaw === '' ? 0 : Number(heartbeatRaw);
  const paddingMinBytes = parseInt(document.getElementById('edit-node-padding-min-bytes').value) || 0;
  const masqueAlpn = document.getElementById('edit-node-masque-alpn').value.trim();

  const dnsOverride = document.getElementById('edit-node-dns-override').checked;
  const remoteDns = document.getElementById('edit-node-remote-dns').value.trim();
  const localDns = document.getElementById('edit-node-local-dns').value.trim();
  const udpgwVersion = document.getElementById('edit-node-udpgw-version').value;
  const udpgwAddr = document.getElementById('edit-node-udpgw-addr').value.trim();
  const geositeDirect = document.getElementById('edit-node-geosite-direct').value.trim();
  const geoipDirect = document.getElementById('edit-node-geoip-direct').value.trim();

  const appFilterOverride = document.getElementById('edit-node-app-override').checked;
  const filterMode = document.getElementById('mode-node-allow').checked ? 1 : 0;
  const filterApps = allNodeApps.filter(a => a.isSelected).map(a => a.packageName).join(',');

  const payload = {
    id, name, sshAddr, note, favorite, authType, user, pass, keyPass, privateKey,
    tunnelType, tunnelTlsEnabled, proxyAddr, customHost, serverName, customPath, enableCustomPath, alpn, httpPayload, disableStatusCheck,
    verifyFingerprint, serverFingerprint,
    verifyCertFingerprint, serverCertFingerprint,
    proxyAuthRequired, proxyAuthToken, proxyAuthUser, proxyAuthPass,
    dnsTunnelServers, dnsTunnelDomain, dnsTunnelType, dnsTunnelPublicKey, dnsTunnelEDNS0,
    dnsTunnelPsk, dnsTunnelMarker,
    kcpPassword, kcpCrypt, kcpMode, kcpDataShards, kcpParityShards, kcpSndWnd, kcpRcvWnd, kcpMtu, kcpNoComp, kcpSmuxVer, kcpKeepAlive,
    udpCustomPsk, udpCustomMagic, udpCustomPublicKey, udpCustomPaths, udpCustomSockets, udpCustomSendWindow, udpCustomMaxPkt, udpCustomMtuProbe,
    icmpCustomPsk, icmpCustomMagic, icmpCustomPublicKey, icmpCustomMtuMode,
    icmpCustomMaxPayload, icmpCustomPaceMS, icmpCustomIdRange,
    xhttpChunkSizeKB, xhttpStreamMode, bindInterface, heartbeatIntervalMs, paddingMinBytes, masqueAlpn,
    dnsOverride, remoteDns, localDns, udpgwVersion, udpgwAddr, geositeDirect, geoipDirect,
    appFilterOverride, filterMode, filterApps
  };

  try {
    const res = await fetch('/api/profiles/update?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    if (res.ok) {
      showToast(t('toast_profile_updated', {name: name || 'Node'}));
      if (warnMsgs.length) setTimeout(() => showToast(t(warnMsgs[0])), 900);
      closeEditModal();
      loadProfiles();
      fetchStatus();
    } else {
      showToast(t('toast_profile_update_failed'));
    }
  } catch (_) {
    showToast(t('toast_profile_update_failed'));
  }
}

let loadedFilePayload = '';

// ── 导入 Modal 逻辑 (支持文件直接选择与拖放) ──
function openAddModal() {
  loadedFilePayload = '';
  document.getElementById('add-modal').classList.add('active');
  document.getElementById('profile-json-input').value = '';
  document.getElementById('input-import-pin').value = '';
  const badge = document.getElementById('pin-detected-badge');
  if (badge) {
    badge.style.display = 'none';
    badge.style.color = 'var(--primary)';
    badge.textContent = t('pin_detected_badge');
  }
  const dropTitle = document.getElementById('t-file-drop-title');
  if (dropTitle) dropTitle.textContent = t('file_drop_title');
  const fileInput = document.getElementById('file-import-input');
  if (fileInput) fileInput.value = '';
}
function closeAddModal() {
  document.getElementById('add-modal').classList.remove('active');
  loadedFilePayload = '';
  const fileInput = document.getElementById('file-import-input');
  if (fileInput) fileInput.value = '';
}

function onFileDragOver(e) {
  e.preventDefault();
  e.stopPropagation();
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy';
  document.getElementById('file-drop-zone')?.classList.add('dragover');
}

function onFileDragLeave(e) {
  e.preventDefault();
  e.stopPropagation();
  document.getElementById('file-drop-zone')?.classList.remove('dragover');
}

function onFileDrop(e) {
  e.preventDefault();
  e.stopPropagation();
  document.getElementById('file-drop-zone')?.classList.remove('dragover');
  const files = e.dataTransfer?.files;
  if (files && files.length > 0) {
    readFileContent(files[0]);
  }
}

function handleFileSelect(input) {
  const files = input.files;
  if (files && files.length > 0) {
    readFileContent(files[0], input);
  }
}

function readFileContent(file, inputElement) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function(e) {
    let content = (e.target && e.target.result) ? e.target.result : '';
    if (content.charCodeAt(0) === 0xFEFF) {
      content = content.slice(1);
    }
    loadedFilePayload = content;
    const textarea = document.getElementById('profile-json-input');
    if (textarea) textarea.value = content;

    const dropTitle = document.getElementById('t-file-drop-title');
    if (dropTitle) {
      dropTitle.innerHTML = `✓ <b>${escapeHtml(file.name)}</b> (${formatBytes(file.size)})`;
    }

    checkPayloadEncryption();
    showToast(t('file_loaded_toast', {name: file.name}));
    if (inputElement) inputElement.value = '';
  };
  reader.onerror = function() {
    showToast(t('toast_read_file_failed'));
    if (inputElement) inputElement.value = '';
  };
  reader.readAsText(file, 'UTF-8');
}

function checkPayloadEncryption() {
  const text = (document.getElementById('profile-json-input')?.value || loadedFilePayload || '').trim();
  const badge = document.getElementById('pin-detected-badge');
  const pinInput = document.getElementById('input-import-pin');
  if (!badge) return;

  if (!text) {
    badge.style.display = 'none';
    return;
  }

  try {
    const clean = text.replace(/^stun:\/\//i, '').replace(/[\r\n\s]/g, '');
    const decoded = atob(clean);
    const json = JSON.parse(decoded);
    if (json.v && json.s && json.i && json.c) {
      badge.style.display = 'inline';
      badge.style.color = 'var(--primary)';
      badge.textContent = t('badge_encrypted_detected');
      if (pinInput && !pinInput.value) {
        pinInput.focus();
      }
      return;
    }
  } catch (_) {}

  try {
    const parsed = JSON.parse(text);
    const count = Array.isArray(parsed) ? parsed.length : (parsed.profiles && Array.isArray(parsed.profiles)) ? parsed.profiles.length : 1;
    badge.style.display = 'inline';
    badge.style.color = '#4caf50';
    badge.textContent = t('badge_plain_detected', {count});
    return;
  } catch (_) {}

  badge.style.display = 'none';
}

async function submitImportProfile() {
  const text = (document.getElementById('profile-json-input')?.value || loadedFilePayload || '').trim();
  if (!text) return showToast(t('import_empty_error'));
  const pin = document.getElementById('input-import-pin').value.trim();

  const btn = document.getElementById('t-btn-import-now');
  const originalText = btn ? btn.textContent : '';
  if (btn) {
    btn.disabled = true;
    btn.textContent = '...';
  }

  try {
    const res = await fetch('/api/profiles/import?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({content: text, pin: pin})
    });
    
    let data = {};
    try {
      data = await res.json();
    } catch (_) {}

    if (res.ok && data.status === 'success') {
      showToast(t('import_success', {count: data.importedCount || 1}));
      closeAddModal();
      loadProfiles();
      fetchStatus();
    } else {
      const errKey = data.error ? ('err_' + data.error) : '';
      const localizedErr = (errKey && I18N[currentLang] && I18N[currentLang][errKey]) ? t(errKey) : '';
      showToast(localizedErr || data.message || t('import_failed'));
    }
  } catch (e) {
    showToast(t('import_failed'));
  } finally {
    if (btn) {
      btn.disabled = false;
      btn.textContent = originalText;
    }
  }
}

// ── 导出 Modal 逻辑 ──
function openExportModal() {
  document.getElementById('export-modal').classList.add('active');
  generateRandomExportPin();
  document.getElementById('export-result-group').style.display = 'none';
  document.getElementById('t-btn-export-copy').style.display = 'none';
  document.getElementById('t-btn-export-exec').style.display = 'inline-flex';
}
function closeExportModal() {
  document.getElementById('export-modal').classList.remove('active');
}

function generateRandomExportPin() {
  const pin = String(Math.floor(100000 + Math.random() * 900000));
  document.getElementById('input-export-pin').value = pin;
}

async function submitExportProfiles() {
  const pin = document.getElementById('input-export-pin').value.trim();
  if (!pin) return showToast(t('toast_enter_pin'));

  try {
    const res = await fetch('/api/profiles/export?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({pin: pin})
    });
    const data = await res.json();
    if (res.ok && data.status === 'success') {
      document.getElementById('export-payload-output').value = data.payload;
      document.getElementById('export-result-group').style.display = 'flex';
      document.getElementById('t-btn-export-exec').style.display = 'none';
      document.getElementById('t-btn-export-copy').style.display = 'inline-flex';
      showToast(t('export_success', {pin: data.pin}));
    } else {
      showToast(data.error || t('toast_export_failed'));
    }
  } catch (_) {
    showToast(t('toast_export_failed'));
  }
}

function copyExportPayload() {
  const text = document.getElementById('export-payload-output').value;
  copyToClipboard(text);
  showToast(t('export_copied'));
}

// ── 设置页签数据缓存 ──
// 打开「系统设置」要拉 /api/settings，还要拉 /api/apps（后端需枚举全部已安装应用，前端再为每个应用各发一次图标请求）。
// 原实现每次切回该页签都重来一遍，卡片要等数据齐才可用，是明显的卡顿点。这里做三层改进：
//   ① 启动后在空闲时段预取 → 首次打开即命中缓存；
//   ② 打开时先用内存缓存即时渲染，再按 TTL 决定是否后台静默重取；
//   ③ 用户尚未保存的勾选改动不会被后台重取覆盖。
const SETTINGS_TTL_MS = 5 * 60 * 1000;
const APPS_TTL_MS = 5 * 60 * 1000;

const settingsCache = { data: null, at: 0, inflight: null };
const appsCache = { data: null, at: 0, inflight: null };
let appsDirty = false;            // 应用勾选存在未保存改动
let appsRenderedSig = null;       // 当前已渲染列表的指纹，用于跳过无意义重绘
let settingsPrefetched = false;

// 带缓存与并发去重的 GET（同一资源同时只允许一个请求在飞）。
// force=true 时附加 refresh=1，让后端丢掉自己的清单缓存一起重建。
async function fetchCached(cache, path, ttlMs, force) {
  if (!force && cache.data != null && (Date.now() - cache.at) < ttlMs) return cache.data;
  if (cache.inflight) return cache.inflight;
  cache.inflight = (async () => {
    try {
      const res = await fetch(path + '?token=' + token + (force ? '&refresh=1' : ''));
      if (!res.ok) return cache.data;
      const data = await res.json();
      if (data != null) { cache.data = data; cache.at = Date.now(); }
      return cache.data;
    } catch (_) {
      return cache.data;          // 失败退回旧数据，别把页面搞空
    } finally {
      cache.inflight = null;
    }
  })();
  return cache.inflight;
}

function appsSignature(apps) {
  return apps.map(a => a.packageName + (a.isSelected ? '1' : '0')).join(',');
}

// 有未保存改动时，把本地勾选状态叠加回服务端数据上，避免刷新/切页丢用户操作
function mergeLocalSelections(apps) {
  if (!appsDirty) return apps;
  const sel = new Map(allApps.map(a => [a.packageName, a.isSelected]));
  return apps.map(a => sel.has(a.packageName) ? Object.assign({}, a, { isSelected: sel.get(a.packageName) }) : a);
}

function applyApps(apps) {
  allApps = apps;
  appsRenderedSig = appsSignature(apps);
  // 尊重用户当前的搜索词，重取数据后不要把筛选结果刷掉
  const search = document.getElementById('app-search');
  if (search && search.value.trim()) filterAppList(); else renderApps(allApps);
}

async function loadApps(force) {
  const cached = appsCache.data;
  if (cached) applyApps(mergeLocalSelections(cached));        // 先出内容，不等网络
  if (!force && cached && (Date.now() - appsCache.at) < APPS_TTL_MS) return;
  const fetched = await fetchCached(appsCache, '/api/apps', APPS_TTL_MS, force);
  if (!fetched) return;
  const data = mergeLocalSelections(fetched);
  if (!force && cached && appsSignature(data) === appsRenderedSig) return;   // 无变化不重绘
  applyApps(data);
}

function renderApps(apps) {
  const container = document.getElementById('app-list-container');
  if (!apps.length) {
    container.innerHTML = '<div style="color:var(--text-muted);font-size:0.85rem;padding:12px">' + t('no_apps') + '</div>';
    return;
  }
  container.innerHTML = apps.map(a => {
    const versionStr = a.versionName ? `v${a.versionName}` : '';
    const subText = [a.packageName, versionStr].filter(Boolean).join(' • ');
    return `
    <div class="app-item ${a.isSelected ? 'selected' : ''}" onclick="toggleAppSelection('${a.packageName}')">
      <img class="app-icon" src="/api/app-icon?pkg=${encodeURIComponent(a.packageName)}&token=${token}" loading="lazy" alt="icon" onerror="this.style.opacity='0.2'" />
      <div class="app-info">
        <div class="app-header">
          <span class="app-name">${a.appName}</span>
          ${a.isSystem ? '<span class="app-badge-sys">SYSTEM</span>' : ''}
        </div>
        <span class="app-sub">${subText}</span>
      </div>
      <input type="checkbox" ${a.isSelected ? 'checked' : ''} onclick="event.stopPropagation(); toggleAppSelection('${a.packageName}')">
    </div>
  `}).join('');
}

// 应用清单强制重取（跳过 TTL，并让后端一起失效自己的清单缓存）
async function refreshApps() {
  await loadApps(true);
}

function toggleAppSelection(pkg) {
  const item = allApps.find(a => a.packageName === pkg);
  if (item) {
    item.isSelected = !item.isSelected;
    appsDirty = true;
    filterAppList();
  }
}

function filterAppList() {
  const q = document.getElementById('app-search').value.toLowerCase();
  const filtered = allApps.filter(a => a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q));
  renderApps(filtered);
}

function toggleSelectAllApps() {
  const anyUnselected = allApps.some(a => !a.isSelected);
  allApps.forEach(a => a.isSelected = anyUnselected);
  appsDirty = true;
  filterAppList();
}

async function saveAppFilter() {
  const mode = document.getElementById('mode-allow').checked ? 1 : 0;
  const selectedPkgs = allApps.filter(a => a.isSelected).map(a => a.packageName).join(',');
  const res = await fetch('/api/apps/save?token=' + token, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({filterMode: mode, filterApps: selectedPkgs})
  });
  if (res.ok) {
    showToast(t('filter_save_success'));
    appsDirty = false;      // 已落库，之后的后台刷新可以放心覆盖本地列表
    appsRenderedSig = appsSignature(allApps);
    // 顺手把已保存的状态写回缓存，否则下次切回页签会被保存前的旧缓存回退
    if (appsCache.data) appsCache.data = allApps.map(a => Object.assign({}, a));
    fetchStatus();
  } else {
    showToast(t('filter_save_failed'));
  }
}

// ── 全局综合设置 (Settings) 逻辑 ──
// ── WebDAV 云备份 (WebDAV Cloud Backup) ──
async function loadWebDav() {
  try {
    const res = await fetch('/api/webdav?token=' + token);
    if (!res.ok) return;
    const d = await res.json();
    document.getElementById('input-webdav-url').value = d.url || '';
    document.getElementById('input-webdav-user').value = d.user || '';
    document.getElementById('input-webdav-pass').value = '';
    document.getElementById('input-webdav-pass').placeholder = d.hasPass ? '••••••••' : '';
    document.getElementById('input-webdav-pin').value = '';
    document.getElementById('input-webdav-pin').placeholder = d.hasPin ? '••••••' : '';
    document.getElementById('input-webdav-auto').value = d.auto ? '1' : '0';
    document.getElementById('input-webdav-interval').value = d.intervalHours || 24;
    const last = document.getElementById('display-webdav-last');
    if (last) last.textContent = d.lastBackup > 0 ? new Date(d.lastBackup).toLocaleString() : t('never_updated');
  } catch (_) {}
}

async function webdavSaveConfig() {
  const pin = document.getElementById('input-webdav-pin').value.trim();
  // 与 Android 侧对齐：PIN 太短就拦住。输入框标签早就写着"至少 4 位"，这里把它落实
  // （空 PIN 不在此列 —— 那是"配置没填完"，由备份/恢复动作各自的配置校验去报）
  if (pin && pin.length < 4) {
    showToast(t('webdav_pin_too_short'));
    return false;
  }
  try {
    const res = await fetch('/api/webdav/config?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({
        url: document.getElementById('input-webdav-url').value.trim(),
        user: document.getElementById('input-webdav-user').value.trim(),
        pass: document.getElementById('input-webdav-pass').value,
        pin: pin,
        auto: document.getElementById('input-webdav-auto').value === '1',
        intervalHours: parseInt(document.getElementById('input-webdav-interval').value) || 24
      })
    });
    if (res.ok) showToast(t('webdav_saved'));
    else showToast(t('webdav_failed_generic'));
  } catch (_) {
    showToast(t('webdav_failed_generic'));
  }
  // 保存请求本身失败不拦住后续动作（旧行为就是继续跑），只有 PIN 不合规才 return false
  return true;
}

async function webdavBackupNow() {
  if (!await webdavSaveConfig()) return;
  await webdavAction('/api/webdav/backup', 'webdav_backup_ok', {count: 0});
}

async function webdavRestoreNow() {
  if (!await webdavSaveConfig()) return;
  // 拉取服务器上的备份列表 → 弹窗选择 → 二次确认 → 恢复所选
  try {
    const res = await fetch('/api/webdav/backups?token=' + token);
    const d = await res.json().catch(() => ({}));
    if (!res.ok) {
      showToast(t('webdav_failed_generic') + (d.error ? (': ' + d.error) : ''));
      return;
    }
    const backups = d.backups || [];
    if (!backups.length) { showToast(t('webdav_no_backups')); return; }
    openWebDavPicker(backups);
  } catch (_) {
    showToast(t('webdav_failed_generic'));
  }
}

function openWebDavPicker(backups) {
  const list = document.getElementById('webdav-picker-list');
  list.innerHTML = backups.map(b =>
    `<button class="btn" style="text-align:left" onclick="webdavPickRestore('${b}')">📦 ${formatBackupDir(b)}</button>`
  ).join('');
  document.getElementById('webdav-picker-modal').classList.add('active');
}

function closeWebDavPicker() {
  document.getElementById('webdav-picker-modal').classList.remove('active');
}

// 备份目录名是 UTC 时间戳 yyyyMMdd-HHmmss，按本机时区展示
function formatBackupDir(name) {
  const m = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})$/.exec(name);
  if (!m) return name;
  const dt = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]));
  const p = (n) => String(n).padStart(2, '0');
  return dt.getFullYear() + '-' + p(dt.getMonth() + 1) + '-' + p(dt.getDate()) +
    ' ' + p(dt.getHours()) + ':' + p(dt.getMinutes());
}

async function webdavPickRestore(dir) {
  // 恢复会合并节点并覆盖设置 —— 必须二次确认
  if (!confirm(t('webdav_restore_confirm'))) return;
  closeWebDavPicker();
  await webdavAction('/api/webdav/restore', 'webdav_restore_ok', {count: 0}, {dir: dir});
}

async function webdavAction(path, okKey, okParams, body = {}) {
  try {
    const res = await fetch(path + '?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(body)
    });
    const d = await res.json().catch(() => ({}));
    if (res.ok && d.status === 'success') {
      okParams.count = d.count || 0;
      // 分区名由后端本地化后回传：前端不再自己维护一份"备份都包含什么"的翻译，
      // 免得后端加了新分区、前端的提示语还停在"节点 + 设置"。
      okParams.sections = d.sectionsText || '';
      let key = okKey;
      if (okParams.sections) {
        if (path.includes('restore')) key = 'webdav_restore_ok_full';
      } else if (path.includes('backup')) {
        key = 'webdav_backup_ok_plain';
      }
      showToast(t(key, okParams));
      if (path.includes('backup')) loadWebDav();
      if (path.includes('restore')) { loadProfiles(); loadWebDav(); }
    } else {
      showToast(t('webdav_failed_generic') + (d.error ? (': ' + d.error) : ''));
    }
  } catch (_) {
    showToast(t('webdav_failed_generic'));
  }
}

async function loadSettings(force) {
  const data = await fetchCached(settingsCache, '/api/settings', SETTINGS_TTL_MS, force);
  if (data) applySettingsFields(data);
}

function applySettingsFields(data) {
  try {
    
    // 1. Core & DNS
    document.getElementById('select-service-mode').value = String(data.serviceMode ?? 0);
    document.getElementById('select-log-level').value = data.logLevel || 'INFO';
    document.getElementById('input-remote-dns').value = data.remoteDns || '';
    document.getElementById('input-local-dns').value = data.localDns || '';

    // 2. UDPGW
    document.getElementById('select-udpgw-version').value = data.udpgwVersion || 'tun2proxy';
    document.getElementById('input-udpgw-addr').value = data.udpgwAddr || '127.0.0.1:7300';

    // 3. GeoData & Direct Rules
    document.getElementById('input-geosite-direct').value = data.geositeDirect || '';
    document.getElementById('input-geoip-direct').value = data.geoipDirect || '';
    document.getElementById('input-geosite-url').value = data.geositeUrl || '';
    document.getElementById('input-geoip-url').value = data.geoipUrl || '';
    document.getElementById('input-update-interval').value = data.updateInterval || 86400;
    
    if (data.lastUpdateTime && data.lastUpdateTime > 0) {
      const d = new Date(data.lastUpdateTime * 1000);
      document.getElementById('display-last-update').textContent = d.toLocaleString();
    } else {
      document.getElementById('display-last-update').textContent = t('never_updated');
    }

    // 4. System
    document.getElementById('switch-show-speed').checked = (data.showNotificationSpeed !== false);

    // 5. MCP Server
    if (document.getElementById('switch-mcp-enabled')) {
      document.getElementById('switch-mcp-enabled').checked = (data.mcpServerEnabled !== false);
    }
    if (document.getElementById('input-mcp-port')) {
      document.getElementById('input-mcp-port').value = data.mcpServerPort || 37180;
    }
    if (document.getElementById('select-mcp-auth-mode')) {
      document.getElementById('select-mcp-auth-mode').value = String(data.mcpAuthMode ?? 0);
    }
    if (document.getElementById('input-mcp-secret')) {
      document.getElementById('input-mcp-secret').value = data.mcpAuthSecret || '';
    }
    loadMcpTab(data);

    // 6. Auth Token
    settingsTokens.randomToken = data.randomToken || token;
    settingsTokens.permanentToken = data.permanentToken || '';
    settingsTokens.customToken = data.customToken || '';
    if (data.effectiveToken) {
      token = data.effectiveToken;
      localStorage.setItem('web_auth_token', token);
    }

    const mode = data.authMode ?? 0;
    const modeRadio = document.getElementById('auth-mode-' + mode);
    if (modeRadio) modeRadio.checked = true;

    if (data.customToken) {
      document.getElementById('input-custom-token').value = data.customToken;
    }

    onAuthModeChange(mode);
  } catch (_) {}
}

function loadMcpTab(settingsData) {
  const host = window.location.hostname || '127.0.0.1';
  const port = document.getElementById('input-mcp-port')?.value || settingsData?.mcpServerPort || 37180;

  const portVal = document.getElementById('mcp-stat-port-val');
  if (portVal) portVal.textContent = port;

  const isOnline = (settingsData?.mcpIsRunning !== false);
  const badge = document.getElementById('mcp-stat-status-badge');
  if (badge) {
    badge.textContent = isOnline ? 'ONLINE' : 'STOPPED';
    badge.className = 'stat-value ' + (isOnline ? 'success' : 'danger');
  }

  const mcpDashUrl = 'http://' + host + ':' + port;
  const mcpHttpUrl = 'http://' + host + ':' + port + '/mcp';
  const dashBtn = document.getElementById('btn-open-mcp-dashboard');
  if (dashBtn) dashBtn.href = mcpDashUrl;

  const jsonSnippet = document.getElementById('mcp-json-snippet');
  if (jsonSnippet) {
    jsonSnippet.textContent = JSON.stringify({
      mcpServers: {
        "stun-device": {
          type: 'http',
          url: mcpHttpUrl
        }
      }
    }, null, 2);
  }

  const geminiSnippet = document.getElementById('mcp-gemini-snippet');
  if (geminiSnippet) {
    geminiSnippet.textContent = `import google.generativeai as genai, requests

${t('mcp_gemini_c1')}
tools = requests.get("http://${host}:${port}/gemini/declarations").json()

${t('mcp_gemini_c2')}
genai.configure(api_key="YOUR_GEMINI_API_KEY")
chat = genai.GenerativeModel("gemini-2.0-flash", tools=tools["functionDeclarations"]).start_chat(enable_automatic_function_calling=True)

${t('mcp_gemini_c3')}
response = chat.send_message("${t('mcp_gemini_prompt')}")
print(response.text)`;
  }
}

function copyMcpJsonConfig() {
  const el = document.getElementById('mcp-json-snippet');
  if (el) {
    copyToClipboard(el.textContent);
    showToast(t('toast_mcp_config_copied'));
  }
}

function copyMcpGeminiSnippet() {
  const el = document.getElementById('mcp-gemini-snippet');
  if (el) {
    copyToClipboard(el.textContent);
    showToast(t('toast_gemini_snippet_copied'));
  }
}

function onAuthModeChange(mode) {
  const customGroup = document.getElementById('custom-token-group');
  if (customGroup) customGroup.style.display = (mode === 2) ? 'flex' : 'none';
  updateAccessUrlPreview();
}

function updateAccessUrlPreview() {
  const host = window.location.host;
  const protocol = window.location.protocol;
  const pathname = window.location.pathname;
  const base = `${protocol}//${host}${pathname}`;

  let selectedMode = 0;
  for (let i = 0; i <= 3; i++) {
    if (document.getElementById('auth-mode-' + i)?.checked) {
      selectedMode = i;
      break;
    }
  }

  let activeToken = '';
  if (selectedMode === 0) {
    activeToken = settingsTokens.randomToken || token;
  } else if (selectedMode === 1) {
    activeToken = settingsTokens.permanentToken || token;
  } else if (selectedMode === 2) {
    const inputVal = document.getElementById('input-custom-token')?.value?.trim();
    activeToken = inputVal || settingsTokens.customToken || settingsTokens.permanentToken || token;
  } else if (selectedMode === 3) {
    activeToken = '';
  }

  const liveUrl = activeToken ? `${base}?token=${encodeURIComponent(activeToken)}` : base;
  const urlDisplay = document.getElementById('display-access-url');
  if (urlDisplay) {
    urlDisplay.value = liveUrl;
  }
}

async function saveAllSettings() {
  let mode = 0;
  for (let i = 0; i <= 3; i++) {
    if (document.getElementById('auth-mode-' + i)?.checked) {
      mode = i;
      break;
    }
  }
  const customToken = document.getElementById('input-custom-token').value.trim();

  const payload = {
    serviceMode: parseInt(document.getElementById('select-service-mode').value) || 0,
    logLevel: document.getElementById('select-log-level').value,
    remoteDns: document.getElementById('input-remote-dns').value.trim(),
    localDns: document.getElementById('input-local-dns').value.trim(),
    udpgwVersion: document.getElementById('select-udpgw-version').value,
    udpgwAddr: document.getElementById('input-udpgw-addr').value.trim(),
    geositeDirect: document.getElementById('input-geosite-direct').value.trim(),
    geoipDirect: document.getElementById('input-geoip-direct').value.trim(),
    geositeUrl: document.getElementById('input-geosite-url').value.trim(),
    geoipUrl: document.getElementById('input-geoip-url').value.trim(),
    updateInterval: parseInt(document.getElementById('input-update-interval').value) || 86400,
    showNotificationSpeed: document.getElementById('switch-show-speed').checked,
    mcpServerEnabled: document.getElementById('switch-mcp-enabled') ? document.getElementById('switch-mcp-enabled').checked : true,
    mcpServerPort: parseInt(document.getElementById('input-mcp-port')?.value) || 37180,
    mcpAuthMode: parseInt(document.getElementById('select-mcp-auth-mode')?.value) || 0,
    mcpAuthSecret: document.getElementById('input-mcp-secret')?.value?.trim() || '',
    authMode: mode,
    customToken: customToken
  };

  try {
    const res = await fetch('/api/settings/save?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify(payload)
    });
    if (res.ok) {
      const data = await res.json();
      token = data.effectiveToken || '';
      localStorage.setItem('web_auth_token', token);
      settingsTokens.randomToken = data.randomToken || token;
      settingsTokens.permanentToken = data.permanentToken || '';
      settingsTokens.customToken = data.customToken || '';

      updateAccessUrlPreview();
      loadMcpTab(data);
      showToast(t('settings_save_success'));
      try {
        const newUrlObj = new URL(data.effectiveUrl || document.getElementById('display-access-url').value);
        window.history.replaceState({}, '', newUrlObj.pathname + newUrlObj.search);
      } catch (_) {}
      fetchStatus();
    } else {
      showToast(t('settings_save_failed'));
    }
  } catch (_) {
    showToast(t('settings_save_failed'));
  }
}

async function updateGeoDataNow() {
  const btn = document.getElementById('t-btn-update-geodata');
  const originalText = btn.textContent;
  btn.disabled = true;
  btn.textContent = t('geodata_updating');

  try {
    const res = await fetch('/api/settings/update-geodata?token=' + token, {
      method: 'POST'
    });
    if (res.ok) {
      const data = await res.json();
      if (data.lastUpdateTime) {
        const d = new Date(data.lastUpdateTime * 1000);
        document.getElementById('display-last-update').textContent = d.toLocaleString();
      }
      showToast(t('geodata_update_success'));
    } else {
      showToast(t('geodata_update_failed'));
    }
  } catch (_) {
    showToast(t('geodata_update_failed'));
  } finally {
    btn.disabled = false;
    btn.textContent = originalText;
  }
}

function copyAccessUrl() {
  const url = document.getElementById('display-access-url').value;
  copyToClipboard(url);
  showToast(t('url_copied'));
}

let logLines = 0;
const logBox = document.getElementById('log-box');

function initLogStream() {
  const sse = new EventSource('/logs/stream?token=' + token);
  sse.onmessage = e => {
    if (!e.data || e.data.startsWith(':')) return;
    logLines++;
    const div = document.createElement('div');
    let level = 'I';
    let content = e.data;

    // 🌟 结构化前缀毫秒级解析：E|..., W|..., I|..., D|...（零正则，零误判）
    if (e.data.length >= 2 && e.data[1] === '|') {
      level = e.data[0];
      content = e.data.substring(2);
    } else {
      const match = e.data.match(/^\S+\s+(\w+)/);
      const tagLevel = match ? match[1].toUpperCase() : '';
      if (tagLevel === 'ERROR' || tagLevel === 'FATAL') level = 'E';
      else if (tagLevel === 'WARN' || tagLevel === 'WARNING') level = 'W';
      else if (tagLevel === 'DEBUG') level = 'D';
    }

    div.className = 'log-line ' + level;
    div.textContent = content;
    if (!matchesWebLogLevel(level, currentWebFilterLevel)) {
      div.style.display = 'none';
    }
    logBox.appendChild(div);

    if (logBox.children.length > 1000) logBox.removeChild(logBox.firstChild);
    if (autoScroll) logBox.scrollTop = logBox.scrollHeight;
    document.getElementById('log-count').textContent = logLines + t('lines_unit');
  };
}

let currentWebFilterLevel = 'ALL';

function filterWebLogs() {
  const sel = document.getElementById('select-filter-level');
  currentWebFilterLevel = sel ? sel.value : 'ALL';
  const lines = logBox.querySelectorAll('.log-line');
  lines.forEach(line => {
    line.style.display = matchesWebLogLevel(line, currentWebFilterLevel) ? '' : 'none';
  });
}

function matchesWebLogLevel(elOrClass, filterLevel) {
  if (filterLevel === 'ALL') return true;
  const isE = typeof elOrClass === 'string' ? elOrClass === 'E' : elOrClass.classList.contains('E');
  const isW = typeof elOrClass === 'string' ? elOrClass === 'W' : elOrClass.classList.contains('W');
  const isI = typeof elOrClass === 'string' ? elOrClass === 'I' : elOrClass.classList.contains('I');
  const isD = typeof elOrClass === 'string' ? elOrClass === 'D' : elOrClass.classList.contains('D');

  if (filterLevel === 'ERROR') return isE;
  if (filterLevel === 'WARN') return isW || isE;
  if (filterLevel === 'INFO') return isI || isW || isE;
  if (filterLevel === 'DEBUG') return true;
  return true;
}

async function onLogLevelChanged(newLevel) {
  try {
    const res = await fetch('/api/settings/save?token=' + token, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'},
      body: JSON.stringify({ logLevel: newLevel })
    });
    if (res.ok) {
      showToast((t('settings_save_success') || 'Saved') + ' (' + newLevel + ')');
    }
  } catch (e) {
    console.error('Failed to change log level dynamically', e);
  }
}

function toggleAutoScroll() {
  autoScroll = !autoScroll;
  document.getElementById('btn-autoscroll').textContent = autoScroll ? t('btn_autoscroll_on') : t('btn_autoscroll_off');
}

async function clearLogs() {
  await fetch('/logs/clear?token=' + token);
  logBox.innerHTML = '';
  logLines = 0;
  document.getElementById('log-count').textContent = '0' + t('lines_unit');
  showToast(t('toast_logs_cleared'));
}

function copyLogs() {
  const text = Array.from(logBox.children).map(c => c.textContent).join('\n');
  copyToClipboard(text);
  showToast(t('logs_copied'));
}

initTheme();
initLang();
fetchStatus();
loadProfiles();
loadSubscription();
initLogStream();
setInterval(() => {
  fetchStatus();
  if (currentTab === 'conntrack') loadConntrack();
}, 2000);

// 设置页签数据预取：排在首屏关键请求之后、用空闲时段补齐，这样第一次点「系统设置」时
// 数据已在内存里，直接渲染即可。只预热数据缓存，不碰 DOM，因此不会影响首屏。
function scheduleSettingsPrefetch() {
  if (settingsPrefetched) return;
  settingsPrefetched = true;
  fetchCached(settingsCache, '/api/settings', SETTINGS_TTL_MS, false).catch(() => {});
  fetchCached(appsCache, '/api/apps', APPS_TTL_MS, false).catch(() => {});
}
if (typeof requestIdleCallback === 'function') {
  requestIdleCallback(scheduleSettingsPrefetch, { timeout: 3000 });
} else {
  setTimeout(scheduleSettingsPrefetch, 1200);
}
