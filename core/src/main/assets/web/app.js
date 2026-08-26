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
    tab_logs: '📜 实时日志',
    stat_selected: '🎯 当前选中节点',
    stat_speed: '⚡ 实时速率 (↑ / ↓)',
    stat_total: 'Σ 累计传输总量',
    stat_filter: '🔀 应用分流状态',
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
    import_pin_label: '🔒 6位解密 PIN 码：',
    import_pin_placeholder: '若为加密分享码/备份，请输入6位数字PIN码（明文配置可留空）',
    pin_detected_badge: '✓ 检测到加密数据',
    btn_cancel: '取消',
    btn_import_now: '导入',
    import_success: '✓ 成功导入 {count} 个节点！',
    import_failed: '✕ 导入失败，请检查内容或 PIN 码',
    import_empty_error: '✕ 请粘贴或载入导入内容',
    modal_export_title: '📦 导出节点加密备份',
    export_pin_label: '设定 6 位加密保护 PIN 码：',
    export_pin_placeholder: '输入6位数字PIN码',
    btn_gen_pin: '🎲 随机生成',
    export_result_label: '加密备份数据（Base64）：',
    btn_export_cancel: '关闭',
    btn_export_exec: '立即生成加密备份',
    btn_export_copy: '📋 复制备份',
    export_success: '✓ 已成功生成加密备份！PIN 码为：{pin}',
    export_copied: '✓ 加密备份内容已复制到剪贴板！',
    modal_edit_title: '✏️ 编辑节点配置',
    label_edit_name: '节点名称：',
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
    label_edit_kcp_pass: 'KCP 密码 (Password)：',
    label_edit_kcp_crypt: 'KCP 加密方式：',
    label_edit_kcp_data_shards: 'Data Shards (数据分片)：',
    label_edit_kcp_parity_shards: 'Parity Shards (校验分片)：',
    label_edit_kcp_nodelay: '启用 KCP NoDelay 极速低延迟模式',
    label_edit_udp_custom_psk: 'UDP Custom PSK 密码：',
    label_edit_udp_custom_magic: 'Magic 识别码 (十六进制)：',
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
    lines_unit: ' 行'
  },
  'zh-TW': {
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
    tab_logs: '📜 即時日誌',
    stat_selected: '🎯 當前選中節點',
    stat_speed: '⚡ 即時速率 (↑ / ↓)',
    stat_total: 'Σ 累計傳輸總量',
    stat_filter: '🔀 應用分流狀態',
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
    import_pin_label: '🔒 6位解密 PIN 碼：',
    import_pin_placeholder: '若為加密分享碼/備份，請輸入6位數字PIN碼（明文設定可留空）',
    pin_detected_badge: '✓ 偵測到加密資料',
    btn_cancel: '取消',
    btn_import_now: '匯入',
    import_success: '✓ 成功匯入 {count} 個節點！',
    import_failed: '✕ 匯入失敗，請檢查內容或 PIN 碼',
    import_empty_error: '✕ 請貼上或載入匯入內容',
    modal_export_title: '📦 匯出節點加密備份',
    export_pin_label: '設定 6 位加密保護 PIN 碼：',
    export_pin_placeholder: '輸入6位數字PIN碼',
    btn_gen_pin: '🎲 隨機生成',
    export_result_label: '加密備份資料（Base64）：',
    btn_export_cancel: '關閉',
    btn_export_exec: '立即生成加密備份',
    btn_export_copy: '📋 複製備份',
    export_success: '✓ 已成功生成加密備份！PIN 碼為：{pin}',
    export_copied: '✓ 加密備份內容已複製到剪貼簿！',
    modal_edit_title: '✏️ 編輯節點設定',
    label_edit_name: '節點名稱：',
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
    label_edit_kcp_pass: 'KCP 密碼 (Password)：',
    label_edit_kcp_crypt: 'KCP 加密方式：',
    label_edit_kcp_data_shards: 'Data Shards (資料分片)：',
    label_edit_kcp_parity_shards: 'Parity Shards (校驗分片)：',
    label_edit_kcp_nodelay: '啟用 KCP NoDelay 極速低延遲模式',
    label_edit_udp_custom_psk: 'UDP Custom PSK 密碼：',
    label_edit_udp_custom_magic: 'Magic 識別碼 (十六進位)：',
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
    lines_unit: ' 行'
  },
  'en': {
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
    tab_logs: '📜 Live Logs',
    stat_selected: '🎯 Active Node',
    stat_speed: '⚡ Live Speed (↑ / ↓)',
    stat_total: 'Σ Total Traffic',
    stat_filter: '🔀 App Routing',
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
    import_pin_label: '🔒 6-digit Decryption PIN:',
    import_pin_placeholder: 'Enter 6-digit PIN if encrypted (leave empty for plain JSON)',
    pin_detected_badge: '✓ Encrypted data detected',
    btn_cancel: 'Cancel',
    btn_import_now: 'Import',
    import_success: '✓ Successfully imported {count} node(s)!',
    import_failed: '✕ Import failed, please verify content or PIN code.',
    import_empty_error: '✕ Please paste or load import payload.',
    modal_export_title: '📦 Export Encrypted Backup',
    export_pin_label: 'Set 6-digit encryption PIN:',
    export_pin_placeholder: 'Enter 6-digit PIN code',
    btn_gen_pin: '🎲 Random PIN',
    export_result_label: 'Encrypted Payload (Base64):',
    btn_export_cancel: 'Close',
    btn_export_exec: 'Generate Encrypted Backup',
    btn_export_copy: '📋 Copy Backup',
    export_success: '✓ Encrypted backup generated! PIN is: {pin}',
    export_copied: '✓ Encrypted backup copied to clipboard!',
    modal_edit_title: '✏️ Edit Node Configuration',
    label_edit_name: 'Node Name:',
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
    label_edit_kcp_pass: 'KCP Password:',
    label_edit_kcp_crypt: 'KCP Encryption:',
    label_edit_kcp_data_shards: 'Data Shards:',
    label_edit_kcp_parity_shards: 'Parity Shards:',
    label_edit_kcp_nodelay: 'Enable KCP NoDelay Fast Low-latency Mode',
    label_edit_udp_custom_psk: 'UDP Custom PSK:',
    label_edit_udp_custom_magic: 'Magic Header (Hex):',
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
    lines_unit: ' lines'
  },
  'ja': {
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
    tab_logs: '📜 リアルタイムログ',
    stat_selected: '🎯 選択中ノード',
    stat_speed: '⚡ リアルタイム速度',
    stat_total: 'Σ 総通信量',
    stat_filter: '🔀 分割トンネル状態',
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
    import_pin_label: '🔒 6桁の復号PINコード：',
    import_pin_placeholder: '暗号化データの場合は6桁PINを入力（平文は空欄可）',
    pin_detected_badge: '✓ 暗号化データを検出',
    btn_cancel: 'キャンセル',
    btn_import_now: 'インポート',
    import_success: '✓ {count} 個のノードをインポートしました！',
    import_failed: '✕ インポートに失敗しました。PINまたは内容を確認してください。',
    import_empty_error: '✕ インポート内容を貼り付けまたは読み込んでください',
    modal_export_title: '📦 暗号化バックアップのエクスポート',
    export_pin_label: '6桁の保護PINコードを設定：',
    export_pin_placeholder: '6桁のPINコードを入力',
    btn_gen_pin: '🎲 ランダムPIN',
    export_result_label: '暗号化バックアップ（Base64）：',
    btn_export_cancel: '閉じる',
    btn_export_exec: 'バックアップを生成',
    btn_export_copy: '📋 コピー',
    export_success: '✓ 暗号化バックアップを生成しました！PIN: {pin}',
    export_copied: '✓ クリップボードにコピーしました！',
    modal_edit_title: '✏️ ノード設定の編集',
    label_edit_name: 'ノード名：',
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
    label_edit_kcp_pass: 'KCP パスワード：',
    label_edit_kcp_crypt: 'KCP 暗号化方式：',
    label_edit_kcp_data_shards: 'Data Shards (データ分割)：',
    label_edit_kcp_parity_shards: 'Parity Shards (パリティ分割)：',
    label_edit_kcp_nodelay: 'KCP NoDelay 低遅延モードを有効化',
    label_edit_udp_custom_psk: 'UDP Custom PSK：',
    label_edit_udp_custom_magic: 'Magic ヘッダー (Hex)：',
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
    lines_unit: ' 行'
  },
  'de': {
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
    toast_profile_update_failed: '✕ Speichern des Knotens fehlgeschlagen.',
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
    tab_logs: '📜 Live-Protokolle',
    stat_selected: '🎯 Aktiver Knoten',
    stat_speed: '⚡ Live-Geschwindigkeit',
    stat_total: 'Σ Gesamtverkehr',
    stat_filter: '🔀 App-Routing',
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
    import_pin_label: '🔒 6-stellige Entschlüsselungs-PIN:',
    import_pin_placeholder: 'PIN bei verschlüsselten Daten eingeben',
    pin_detected_badge: '✓ Verschlüsselte Daten erkannt',
    btn_cancel: 'Abbrechen',
    btn_import_now: 'Importieren',
    import_success: '✓ {count} Knoten erfolgreich importiert!',
    import_failed: '✕ Import fehlgeschlagen. Bitte PIN oder Inhalt prüfen.',
    import_empty_error: '✕ Bitte Daten einfügen oder Datei laden.',
    modal_export_title: '📦 Verschlüsseltes Backup exportieren',
    export_pin_label: '6-stellige Sicherheits-PIN festlegen:',
    export_pin_placeholder: '6-stelligen PIN-Code eingeben',
    btn_gen_pin: '🎲 Zufällige PIN',
    export_result_label: 'Verschlüsselte Daten (Base64):',
    btn_export_cancel: 'Schließen',
    btn_export_exec: 'Backup erstellen',
    btn_export_copy: '📋 Kopieren',
    export_success: '✓ Backup erstellt! PIN: {pin}',
    export_copied: '✓ In Zwischenablage kopiert!',
    modal_edit_title: '✏️ Knotenkonfiguration bearbeiten',
    label_edit_name: 'Knotenname:',
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
    label_edit_kcp_pass: 'KCP-Passwort:',
    label_edit_kcp_crypt: 'KCP-Verschlüsselung:',
    label_edit_kcp_data_shards: 'Data Shards (Daten-Shards):',
    label_edit_kcp_parity_shards: 'Parity Shards (Paritäts-Shards):',
    label_edit_kcp_nodelay: 'KCP NoDelay Schnellmodus aktivieren',
    label_edit_udp_custom_psk: 'UDP Custom PSK:',
    label_edit_udp_custom_magic: 'Magic Header (Hex):',
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
    lines_unit: ' Zeilen'
  },
  'fr': {
    page_title: '🦊 Stun · Console Web',
    theme_toggle_title: 'Changer de thème',
    toast_vpn_connecting: 'Connexion au VPN...',
    toast_vpn_disconnected: 'VPN déconnecté',
    toast_switched_node: '✓ Basculé sur le nœud "{name}"',
    toast_deleted_node: '✓ Nœud "{name}" supprimé',
    toast_enter_pin: '✕ Veuillez entrer le code PIN',
    toast_export_failed: '✕ Échec de l\'exportation, veuillez réessayer',
    toast_logs_cleared: '✓ Journaux de console effacés',
    toast_profile_updated: '✓ Configuration du nœud "{name}" enregistrée !',
    toast_profile_update_failed: '✕ Échec de l\'enregistrement du nœud.',
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
    tab_logs: '📜 Journaux en direct',
    stat_selected: '🎯 Nœud actif',
    stat_speed: '⚡ Débit en direct',
    stat_total: 'Σ Trafic total',
    stat_filter: '🔀 Routage des applications',
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
    import_pin_label: '🔒 Code PIN de déchiffrement à 6 chiffres :',
    import_pin_placeholder: 'Entrez le PIN pour les données chiffrées',
    pin_detected_badge: '✓ Données chiffrées détectées',
    btn_cancel: 'Annuler',
    btn_import_now: 'Importer',
    import_success: '✓ {count} nœud(s) importé(s) avec succès !',
    import_failed: '✕ Échec de l\'importation. Vérifiez le contenu ou le PIN.',
    import_empty_error: '✕ Veuillez coller ou charger le contenu à importer.',
    modal_export_title: '📦 Exporter la sauvegarde chiffrée',
    export_pin_label: 'Définir un PIN à 6 chiffres :',
    export_pin_placeholder: 'Entrez le code PIN à 6 chiffres',
    btn_gen_pin: '🎲 PIN aléatoire',
    export_result_label: 'Données chiffrées (Base64) :',
    btn_export_cancel: 'Fermer',
    btn_export_exec: 'Générer la sauvegarde',
    btn_export_copy: '📋 Copier',
    export_success: '✓ Sauvegarde générée ! PIN : {pin}',
    export_copied: '✓ Copié dans le presse-papiers !',
    modal_edit_title: '✏️ Modifier la configuration du nœud',
    label_edit_name: 'Nom du nœud :',
    label_edit_ssh_addr: 'Adresse du serveur SSH :',
    label_edit_auth_type: 'Type d\'authentification SSH :',
    opt_auth_password: 'Authentification par mot de passe',
    opt_auth_key: 'Authentification par clé privée',
    label_edit_user: 'Nom d\'utilisateur SSH :',
    label_edit_pass: 'Mot de passe SSH :',
    label_edit_key_pass: 'Phrase secrète de la clé :',
    label_edit_private_key: 'Clé privée (OpenSSH / RSA / Ed25519) :',
    label_edit_tunnel_type: 'Protocole de tunnel (Tunnel Type) :',
    label_edit_proxy_addr: 'Adresse proxy (Proxy Addr) :',
    label_edit_custom_host: 'Hôte personnalisé (Custom Host) :',
    label_edit_server_name: 'Domaine SNI (Server Name) :',
    label_edit_custom_path: 'Chemin personnalisé (Custom Path) :',
    label_edit_alpn: 'Négociation ALPN (ALPN) :',
    label_edit_http_payload: 'HTTP Payload (Modèle de requête) :',
    label_edit_disable_status_check: 'Désactiver la vérification stricte du code HTTP 200',
    label_edit_verify_cert: '🔒 Vérifier l\'empreinte SHA-256 du certificat',
    label_edit_cert_fp: 'Empreinte SHA-256 du certificat :',
    label_edit_proxy_auth: '🔑 Activer l\'authentification proxy',
    label_edit_auth_token: 'Token Auth Proxy :',
    label_edit_auth_user: 'Nom d\'utilisateur Proxy :',
    label_edit_auth_pass: 'Mot de passe Proxy :',
    label_edit_dns_servers: 'Serveurs DNS (séparés par des virgules) :',
    label_edit_dns_domain: 'Domaine DNS (Tunnel Domain) :',
    label_edit_dns_type: 'Type d\'enregistrement DNS :',
    label_edit_kcp_pass: 'Mot de passe KCP :',
    label_edit_kcp_crypt: 'Chiffrement KCP :',
    label_edit_kcp_data_shards: 'Data Shards (Fragments de données) :',
    label_edit_kcp_parity_shards: 'Parity Shards (Fragments de parité) :',
    label_edit_kcp_nodelay: 'Activer le mode KCP NoDelay à faible latence',
    label_edit_udp_custom_psk: 'UDP Custom PSK :',
    label_edit_udp_custom_magic: 'En-tête Magic (Hex) :',
    label_edit_dns_override: '🌐 Activer le DNS et le routage dédiés pour ce nœud',
    label_edit_remote_dns: 'DNS distant dédié :',
    label_edit_local_dns: 'DNS local dédié :',
    label_edit_udpgw_version: 'Moteur UDPGW dédié :',
    label_edit_udpgw_addr: 'Adresse UDPGW dédiée :',
    label_edit_geosite_direct: 'Balises directes GeoSite :',
    label_edit_geoip_direct: 'Balises directes GeoIP :',
    label_edit_app_override: '🔀 Activer le tunneling d\'application dédié pour ce nœud',
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
    btn_copy_url: '📋 Copier l\'URL',
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
    lines_unit: ' lignes'
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

function applyI18n() {
  document.title = t('page_title');
  document.getElementById('theme-btn').title = t('theme_toggle_title');

  document.getElementById('t-tab-overview').textContent = t('tab_overview');
  document.getElementById('t-tab-profiles').textContent = t('tab_profiles');
  document.getElementById('t-tab-conntrack').textContent = t('tab_conntrack');
  document.getElementById('t-tab-settings').textContent = t('tab_settings');
  document.getElementById('t-tab-logs').textContent = t('tab_logs');

  document.getElementById('t-stat-selected').textContent = t('stat_selected');
  document.getElementById('t-stat-speed').textContent = t('stat_speed');
  document.getElementById('t-stat-total').textContent = t('stat_total');
  document.getElementById('t-stat-filter').textContent = t('stat_filter');

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
  document.getElementById('t-label-edit-kcp-pass').textContent = t('label_edit_kcp_pass');
  document.getElementById('t-label-edit-kcp-crypt').textContent = t('label_edit_kcp_crypt');
  document.getElementById('t-label-edit-kcp-data-shards').textContent = t('label_edit_kcp_data_shards');
  document.getElementById('t-label-edit-kcp-parity-shards').textContent = t('label_edit_kcp_parity_shards');
  document.getElementById('t-label-edit-kcp-nodelay').textContent = t('label_edit_kcp_nodelay');
  document.getElementById('t-label-edit-udp-custom-psk').textContent = t('label_edit_udp_custom_psk');
  document.getElementById('t-label-edit-udp-custom-magic').textContent = t('label_edit_udp_custom_magic');
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
  if (currentTab === 'settings') loadSettings();
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

  if (tabId === 'profiles') loadProfiles();
  if (tabId === 'conntrack') loadConntrack();
  if (tabId === 'settings') {
    loadSettings();
    loadApps();
  }
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
  const container = document.getElementById('profiles-list');
  const quickContainer = document.getElementById('quick-profiles');
  if (!profiles.length) {
    const emptyHtml = '<div style="color:var(--text-muted);font-size:0.85rem">' + t('no_profiles') + '</div>';
    container.innerHTML = emptyHtml;
    quickContainer.innerHTML = emptyHtml;
    return;
  }

  const html = profiles.map(p => `
    <div class="profile-card ${p.isSelected ? 'selected' : ''}">
      <div class="profile-header">
        <span class="profile-name">${escapeHtml(p.name)}</span>
        <span class="profile-badge">${(p.tunnelType || 'TLS').toUpperCase()}</span>
      </div>
      <div class="profile-addr">🌐 ${escapeHtml(p.sshAddr)}</div>
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
      showToast(t('toast_latency_tested', {name: name, delay: r.display}));
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
function onEditAuthTypeChange() {
  const isKey = document.getElementById('edit-node-auth-type').value === 'privatekey';
  document.getElementById('edit-group-pass').style.display = isKey ? 'none' : 'flex';
  document.getElementById('edit-group-key-pass').style.display = isKey ? 'flex' : 'none';
  document.getElementById('edit-group-private-key').style.display = isKey ? 'flex' : 'none';
}

function updateEditModalTunnelFields() {
  const selected = document.getElementById('edit-node-tunnel-type').value;
  const isDns = (selected === 'dns');
  const isHttp = (selected === 'http');
  const isBase = (selected === 'base');
  const isKcp = (selected === 'kcp');
  const isUdpCustom = (selected === 'udp_custom');
  const isMasque = (selected === 'masque');
  const isXhttp = (selected === 'xhttp' || selected === 'xhttpc');

  const isCustomPathSupported = ['ws', 'wss', 'h2', 'h2c', 'grpc', 'grpcc', 'h3', 'wt', 'xhttp', 'xhttpc', 'masque'].includes(selected);
  const isServerNameSupported = ['tls', 'wss', 'h2', 'quic', 'grpc', 'h3', 'wt', 'masque', 'xhttp'].includes(selected);
  const isAuthSupported = isHttp || ['ws', 'wss', 'h2', 'h2c', 'grpc', 'grpcc', 'h3', 'wt', 'masque', 'xhttp', 'xhttpc'].includes(selected);

  // 1. Proxy Addr
  document.getElementById('edit-group-proxy-addr').style.display = (!isBase && !isDns) ? 'flex' : 'none';

  // 2. Custom Host
  const showCustomHost = !isBase && !isDns && !isKcp && !isUdpCustom && selected !== 'tls' && selected !== 'quic';
  document.getElementById('edit-group-custom-host').style.display = showCustomHost ? 'flex' : 'none';

  // 3. Server Name
  document.getElementById('edit-group-server-name').style.display = isServerNameSupported ? 'flex' : 'none';

  // 4. Custom Path
  document.getElementById('edit-group-custom-path').style.display = isCustomPathSupported ? 'flex' : 'none';

  // 5. ALPN
  document.getElementById('edit-group-alpn').style.display = isXhttp ? 'flex' : 'none';

  // 6. HTTP Payload
  document.getElementById('edit-group-http-payload-container').style.display = isHttp ? 'flex' : 'none';

  // 7. Certificate Fingerprint
  document.getElementById('edit-group-cert-fingerprint-row').style.display = isServerNameSupported ? 'flex' : 'none';
  toggleCertFingerprintInput();

  // 8. Proxy Auth
  document.getElementById('edit-group-proxy-auth-container').style.display = isAuthSupported ? 'flex' : 'none';
  toggleProxyAuthInputs();

  // 9. DNS Container
  document.getElementById('edit-group-dns-container').style.display = isDns ? 'grid' : 'none';

  // 10. KCP Container
  document.getElementById('edit-group-kcp-container').style.display = isKcp ? 'grid' : 'none';

  // 11. UDP Custom Container
  document.getElementById('edit-group-udp-custom-container').style.display = isUdpCustom ? 'grid' : 'none';
}

function toggleCertFingerprintInput() {
  const isChecked = document.getElementById('edit-node-verify-cert').checked;
  const isRowVisible = document.getElementById('edit-group-cert-fingerprint-row').style.display !== 'none';
  document.getElementById('edit-group-cert-fingerprint').style.display = (isChecked && isRowVisible) ? 'flex' : 'none';
}

function toggleProxyAuthInputs() {
  const selected = document.getElementById('edit-node-tunnel-type').value;
  const isContainerVisible = document.getElementById('edit-group-proxy-auth-container').style.display !== 'none';
  const isAuthEnabled = document.getElementById('edit-node-proxy-auth').checked && isContainerVisible;

  const isTokenMode = ['h2', 'h2c', 'grpc', 'grpcc', 'h3', 'wt', 'masque', 'xhttp', 'xhttpc'].includes(selected);
  const isUserPassMode = ['ws', 'wss', 'http'].includes(selected);

  document.getElementById('edit-group-auth-token').style.display = (isAuthEnabled && isTokenMode) ? 'flex' : 'none';
  document.getElementById('edit-group-auth-userpass').style.display = (isAuthEnabled && isUserPassMode) ? 'grid' : 'none';
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
  document.getElementById('edit-node-auth-type').value = p.authType || 'password';
  document.getElementById('edit-node-user').value = p.user || '';
  document.getElementById('edit-node-pass').value = p.pass || '';
  document.getElementById('edit-node-key-pass').value = p.keyPass || '';
  document.getElementById('edit-node-private-key').value = p.privateKey || '';
  onEditAuthTypeChange();

  // 2. Tunnel Type & Dynamic Fields
  document.getElementById('edit-node-tunnel-type').value = p.tunnelType || 'tls';
  document.getElementById('edit-node-proxy-addr').value = p.proxyAddr || '';
  document.getElementById('edit-node-custom-host').value = p.customHost || '';
  document.getElementById('edit-node-server-name').value = p.serverName || '';
  document.getElementById('edit-node-custom-path').value = p.customPath || '';
  document.getElementById('edit-node-alpn').value = p.alpn || 'h2,http/1.1';
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

  document.getElementById('edit-node-kcp-pass').value = p.kcpPassword || '';
  document.getElementById('edit-node-kcp-crypt').value = p.kcpCrypt || 'aes';
  document.getElementById('edit-node-kcp-data-shards').value = p.kcpDataShards ?? 10;
  document.getElementById('edit-node-kcp-parity-shards').value = p.kcpParityShards ?? 3;
  document.getElementById('edit-node-kcp-nodelay').checked = !!p.kcpNoDelay;

  document.getElementById('edit-node-udp-custom-psk').value = p.udpCustomPsk || '';
  document.getElementById('edit-node-udp-custom-magic').value = p.udpCustomMagic || '';

  updateEditModalTunnelFields();

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
  const authType = document.getElementById('edit-node-auth-type').value;
  const user = document.getElementById('edit-node-user').value.trim();
  const pass = document.getElementById('edit-node-pass').value;
  const keyPass = document.getElementById('edit-node-key-pass').value;
  const privateKey = document.getElementById('edit-node-private-key').value;

  const tunnelType = document.getElementById('edit-node-tunnel-type').value;
  const proxyAddr = document.getElementById('edit-node-proxy-addr').value.trim();
  const customHost = document.getElementById('edit-node-custom-host').value.trim();
  const serverName = document.getElementById('edit-node-server-name').value.trim();
  const customPath = document.getElementById('edit-node-custom-path').value.trim();
  const alpn = document.getElementById('edit-node-alpn').value;
  const httpPayload = document.getElementById('edit-node-http-payload').value.trim();
  const disableStatusCheck = document.getElementById('edit-node-disable-status-check').checked;

  const verifyCertFingerprint = document.getElementById('edit-node-verify-cert').checked;
  const serverCertFingerprint = document.getElementById('edit-node-cert-fingerprint').value.trim();

  const proxyAuthRequired = document.getElementById('edit-node-proxy-auth').checked;
  const proxyAuthToken = document.getElementById('edit-node-auth-token').value.trim();
  const proxyAuthUser = document.getElementById('edit-node-auth-user').value.trim();
  const proxyAuthPass = document.getElementById('edit-node-auth-pass').value;

  const dnsTunnelServers = document.getElementById('edit-node-dns-servers').value.trim();
  const dnsTunnelDomain = document.getElementById('edit-node-dns-domain').value.trim();
  const dnsTunnelType = document.getElementById('edit-node-dns-type').value;

  const kcpPassword = document.getElementById('edit-node-kcp-pass').value;
  const kcpCrypt = document.getElementById('edit-node-kcp-crypt').value;
  const kcpDataShards = parseInt(document.getElementById('edit-node-kcp-data-shards').value) || 10;
  const kcpParityShards = parseInt(document.getElementById('edit-node-kcp-parity-shards').value) || 3;
  const kcpNoDelay = document.getElementById('edit-node-kcp-nodelay').checked;

  const udpCustomPsk = document.getElementById('edit-node-udp-custom-psk').value;
  const udpCustomMagic = document.getElementById('edit-node-udp-custom-magic').value.trim();

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
    id, name, sshAddr, authType, user, pass, keyPass, privateKey,
    tunnelType, proxyAddr, customHost, serverName, customPath, alpn, httpPayload, disableStatusCheck,
    verifyCertFingerprint, serverCertFingerprint,
    proxyAuthRequired, proxyAuthToken, proxyAuthUser, proxyAuthPass,
    dnsTunnelServers, dnsTunnelDomain, dnsTunnelType,
    kcpPassword, kcpCrypt, kcpDataShards, kcpParityShards, kcpNoDelay,
    udpCustomPsk, udpCustomMagic,
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

// ── 导入 Modal 逻辑 (支持文件直接选择与拖放) ──
function openAddModal() {
  document.getElementById('add-modal').classList.add('active');
  document.getElementById('profile-json-input').value = '';
  document.getElementById('input-import-pin').value = '';
  document.getElementById('pin-detected-badge').style.display = 'none';
  const dropTitle = document.getElementById('t-file-drop-title');
  if (dropTitle) dropTitle.textContent = t('file_drop_title');
  const fileInput = document.getElementById('file-import-input');
  if (fileInput) fileInput.value = '';
}
function closeAddModal() {
  document.getElementById('add-modal').classList.remove('active');
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
    readFileContent(files[0]);
  }
  input.value = '';
}

function readFileContent(file) {
  if (!file) return;
  const reader = new FileReader();
  reader.onload = function(e) {
    const content = (e.target && e.target.result) ? e.target.result : '';
    const textarea = document.getElementById('profile-json-input');
    if (textarea) textarea.value = content;

    const dropTitle = document.getElementById('t-file-drop-title');
    if (dropTitle) {
      dropTitle.innerHTML = `✓ <b>${escapeHtml(file.name)}</b> (${formatBytes(file.size)})`;
    }

    checkPayloadEncryption();
    showToast(t('file_loaded_toast', {name: file.name}));
  };
  reader.onerror = function() {
    showToast('✕ 读取文件失败');
  };
  reader.readAsText(file);
}

function checkPayloadEncryption() {
  const text = document.getElementById('profile-json-input').value.trim();
  const badge = document.getElementById('pin-detected-badge');
  const pinInput = document.getElementById('input-import-pin');
  try {
    const clean = text.replace(/[\r\n\s]/g, '');
    const decoded = atob(clean);
    const json = JSON.parse(decoded);
    if (json.v && json.s && json.i && json.c) {
      badge.style.display = 'inline';
      if (pinInput && !pinInput.value) {
        pinInput.focus();
      }
      return;
    }
  } catch (_) {}
  badge.style.display = 'none';
}

async function submitImportProfile() {
  const text = document.getElementById('profile-json-input').value.trim();
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
      showToast(data.message || data.error || t('import_failed'));
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
  navigator.clipboard.writeText(text);
  showToast(t('export_copied'));
}

async function loadApps() {
  try {
    const res = await fetch('/api/apps?token=' + token);
    if (!res.ok) return;
    allApps = await res.json();
    renderApps(allApps);
  } catch (_) {}
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

function toggleAppSelection(pkg) {
  const item = allApps.find(a => a.packageName === pkg);
  if (item) {
    item.isSelected = !item.isSelected;
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
    fetchStatus();
  } else {
    showToast(t('filter_save_failed'));
  }
}

// ── 全局综合设置 (Settings) 逻辑 ──
async function loadSettings() {
  try {
    const res = await fetch('/api/settings?token=' + token);
    if (!res.ok) return;
    const data = await res.json();
    
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

    // 5. Auth Token
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
  navigator.clipboard.writeText(url);
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
    if (/ERROR|FATAL/.test(e.data)) level = 'E';
    else if (/WARN/.test(e.data)) level = 'W';
    else if (/DEBUG/.test(e.data)) level = 'D';

    div.className = 'log-line ' + level;
    div.textContent = e.data;
    logBox.appendChild(div);

    if (logBox.children.length > 1000) logBox.removeChild(logBox.firstChild);
    if (autoScroll) logBox.scrollTop = logBox.scrollHeight;
    document.getElementById('log-count').textContent = logLines + t('lines_unit');
  };
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
  navigator.clipboard.writeText(text);
  showToast(t('logs_copied'));
}

initTheme();
initLang();
fetchStatus();
loadProfiles();
initLogStream();
setInterval(() => {
  fetchStatus();
  if (currentTab === 'conntrack') loadConntrack();
}, 2000);
