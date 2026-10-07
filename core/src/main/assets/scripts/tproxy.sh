#!/bin/sh

readonly SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
# Version (use YY.MM.DD format)
readonly SCRIPT_VERSION="v26.10.07"

export TZ=Asia/Shanghai

# Configuration (modify as needed)

# Proxy core configuration
# Proxy running user and group
readonly DEFAULT_CORE_USER_GROUP="root:net_admin"
# Proxy traffic mark
readonly DEFAULT_ROUTING_MARK=""
readonly DEFAULT_FORCE_MARK_BYPASS=0
# Proxy ports (transparent proxy listening ports)
readonly DEFAULT_PROXY_TCP_PORT="10812"
readonly DEFAULT_PROXY_UDP_PORT="10812"

# Proxy mode: 0=auto (check TPROXY support), 1=force TPROXY, 2=force REDIRECT
readonly DEFAULT_PROXY_MODE=1

# Performance mode (0=normal, 1=performance optimized)
# When enabled, may enable some features (e.g. conntrack) for better speed
readonly DEFAULT_PERFORMANCE_MODE=1

# DNS configuration
# DNS hijack method (0: disabled, 1: tproxy, 2: redirect)
readonly DEFAULT_DNS_HIJACK_ENABLE=1
# DNS listening port
readonly DEFAULT_DNS_PORT="10553"

# Interface definitions
# Mobile data interface
readonly DEFAULT_MOBILE_INTERFACE="rmnet_data+"
# WiFi interface
readonly DEFAULT_WIFI_INTERFACE="wlan0"
# Hotspot interface
readonly DEFAULT_HOTSPOT_INTERFACE="wlan2"
# USB tethering interface
readonly DEFAULT_USB_INTERFACE="rndis+"

# Other interfaces that require bypassing or proxying. Multiple interfaces can be separated by spaces
readonly DEFAULT_OTHER_BYPASS_INTERFACES=""
readonly DEFAULT_OTHER_PROXY_INTERFACES=""

# Proxy switches
readonly DEFAULT_PROXY_MOBILE=1
readonly DEFAULT_PROXY_WIFI=1
readonly DEFAULT_PROXY_HOTSPOT=0
readonly DEFAULT_PROXY_USB=0
readonly DEFAULT_PROXY_TCP=1
readonly DEFAULT_PROXY_UDP=1

# IPv6 proxy control:
#  0 = disable proxy (but IPv6 stack remains active)
#  1 = enable proxy (normal IPv6 proxy)
# -1 = force disable IPv6 stack entirely (disable_ipv6=1 on all interfaces)
readonly DEFAULT_PROXY_IPV6=1

# The use of 100.0.0.0/8 instead of 100.64.0.0/10 is purely due to a mistake by China Telecom's service provider, and you can change it back
# RFC1918 (10/8, 172.16/12, 192.168/16) is deliberately NOT listed: this app must be able to proxy
# LAN traffic, and anything listed here is ACCEPTed before it reaches the proxy, so a GeoIP rule can
# never route it. Only genuinely unproxyable destinations stay (loopback, link-local, multicast,
# documentation ranges, broadcast).
readonly DEFAULT_BYPASS_IPv4_LIST="0.0.0.0/8 100.0.0.0/8 127.0.0.0/8 169.254.0.0/16 192.0.0.0/24 192.0.2.0/24 192.88.99.0/24 198.51.100.0/24 203.0.113.0/24 224.0.0.0/4 240.0.0.0/4 255.255.255.255/32"
readonly DEFAULT_BYPASS_IPv6_LIST="::/128 ::1/128 ::ffff:0:0/96 100::/64 64:ff9b::/96 2001::/32 2001:10::/28 2001:20::/28 2001:db8::/32 2002::/16 fe80::/10 ff00::/8"
readonly DEFAULT_PROXY_IPv4_LIST=""
readonly DEFAULT_PROXY_IPv6_LIST=""

# Destination-based bypass (tri-tuple: ip / ip:port / ip:lo-hi).
# The SSH tunnel server is auto-injected by the App (SSH_SERVER_ENTRY) as the
# loop-avoidance PRIMARY path, so the tunnel socket escapes TPROXY/REDIRECT
# without needing SO_MARK / pidfd_getfd (Linux 5.6+). uid bypass (-m owner) stays
# as the fallback. A bare IP (no port) means ICMP; ip:port is TCP+UDP; ip:lo-hi
# is a TCP+UDP port range.
readonly DEFAULT_BYPASS_DST_LIST=""
readonly DEFAULT_SSH_SERVER_ENTRY=""

# Hotspot subnet when WiFi and hotspot share the same interface (common on older devices)
# Only used when HOTSPOT_INTERFACE == WIFI_INTERFACE
readonly DEFAULT_HOTSPOT_SUBNET_IPV4="192.168.43.0/24"
readonly DEFAULT_HOTSPOT_SUBNET_IPV6="fe80::/10"

# Mark values
readonly DEFAULT_MARK_VALUE=20
readonly DEFAULT_MARK_VALUE6=25

# Routing table ID
readonly DEFAULT_TABLE_ID=2025

# Per-app proxy (use space to separate package names, supports user:package format)
readonly DEFAULT_APP_PROXY_ENABLE=1
readonly DEFAULT_PROXY_APPS_LIST=""
# Example: "com.example.app com.other"
readonly DEFAULT_BYPASS_APPS_LIST="app.fjj.stun"
# Example: "com.android.shell"
readonly DEFAULT_APP_PROXY_MODE="blacklist"
# "blacklist" or "whitelist"

# CN IP bypass configuration
readonly DEFAULT_BYPASS_CN_IP=0
# CN IP list file name
readonly DEFAULT_CN_IP_FILE="cn.zone"
readonly DEFAULT_CN_IPV6_FILE="cn_ipv6.zone"
# CN IP source URLs
readonly DEFAULT_CN_IP_URL="https://raw.githubusercontent.com/Hackl0us/GeoIP2-CN/release/CN-ip-cidr.txt"
readonly DEFAULT_CN_IPV6_URL="https://ispip.clang.cn/all_cn_ipv6.txt"

# MAC address blacklist/whitelist configuration (hotspot mode)
readonly DEFAULT_MAC_FILTER_ENABLE=0
# MAC address blacklist/whitelist (use space to separate MAC addresses)
readonly DEFAULT_PROXY_MACS_LIST=""
# Example: "AA:BB:CC:DD:EE:FF 11:22:33:44:55:66"
readonly DEFAULT_BYPASS_MACS_LIST=""
# Example: "FF:EE:DD:CC:BB:AA"
readonly DEFAULT_MAC_PROXY_MODE="blacklist"
# "blacklist" or "whitelist"

# Local-address bypass configuration
#
# 目的地址是本机自己持有的地址（127.0.0.1、Wi-Fi 局域网 IP、热点 IP、link-local ...）
# 的流量必须直连，不能进隧道。不做这一步的话，局域网内按设备 IP 拨号的所有 TCP/UDP
# 连接都会被 TPROXY 推进 SSH 隧道 —— 这就是 WebUI / DB Web 开着时隧道连接数被刷高的来源。
# 回环接口本身由 `PROXY_INTERFACE -i lo -j RETURN` 兜住了，但走局域网 IP 的流量不是
# lo 接口流量，所以必须单独判「目的地址属于本机」。
#
# 两种载体，**每个地址族独立决定**主载体（见 local_addr_carrier）：
#   addrtype -> `-m addrtype --dst-type LOCAL`，内核自己跟踪本机地址表，
#               地址怎么变都不用管，也不需要后台进程；
#   ipset    -> `localaddr` / `localaddr6`，由后台进程跟着本机地址增删；
#   none     -> 这个地址族的代理链没建起来（或两种载体都不具备），谈不上旁路。
# v4 与 v6 分开判是刻意的：`NETFILTER_XT_MATCH_ADDRTYPE` 是 v4 的符号，它存在
# 不代表 v6 那半能用。
#
# 主载体是 addrtype 时不建集合、不拉后台进程（常见路径，省一个常驻进程）；但规则
# 插入失败仍要能退回 ipset —— addrtype 探测通过不代表规则一定插得进规则链。
# 回退时现场建表（setup_local_addr_sets 带 force 参数），见 setup_proxy_chain。
#
# 后台进程的两种触发方式（见 local_addr_watch_loop）：
#   ip monitor 可用 -> 事件驱动，地址一变立刻同步，另挂一个慢速清扫兜底；
#   ip monitor 不可用 -> 轮询 `ip addr show`，间隔见下面。
# 两种方式跑的是同一个 sync_local_ipset，对账语义不可能漂移。
# Android 上的 toybox `ip` 不一定带 netlink monitor，所以先探测、探测不到再轮询，
# 不能假设它一定有 —— 这个探测是「试着真跑一次看进程活不活」，不解析 ip help
# （各版本帮助格式都不一样）。
#
# 默认开：关掉能省一条规则，代价是上面那类回灌，得不偿失。
readonly DEFAULT_BYPASS_LOCAL_ADDRS=1
# 轮询间隔（秒）。只有 ip monitor 不可用、退化到轮询时才真的轮询。
readonly DEFAULT_LOCAL_ADDR_POLL_INTERVAL=2
# 1 = 先试 `ip monitor address`（事件驱动），拿不到数据再退轮询；0 = 直接轮询。
# 留这个开关是因为个别版本的 toybox monitor 行为不确定，留一条不改代码的退路。
readonly DEFAULT_LOCAL_ADDR_USE_MONITOR=1
# 事件模式下的慢速清扫间隔（秒）。事件流万一静默失效（socket 还活着但不再吐事件），
# 集合会停在上一次快照上；这个清扫把过期窗口限定在一个周期。复用轮询循环实现，
# 集合被销毁时它自己退出，就算 watcher 被 SIGKILL 收掉也能自愈。
readonly DEFAULT_LOCAL_ADDR_SWEEP_INTERVAL=300

# block quic
readonly DEFAULT_BLOCK_QUIC=0

# Whether to include timestamp in logs (0=disable, 1=enable)
# Disabling this can improve performance by avoiding a process fork for each log entry.
readonly DEFAULT_LOG_TIMESTAMP=0

# Dry-run mode (disabled by default)
readonly DEFAULT_DRY_RUN=0

log() {
    local level="$1"
    local message="$2"
    local color_code

    case "$level" in
        Debug) color_code="\033[0;36m" ;;
        Info) color_code="\033[1;32m" ;;
        Warn) color_code="\033[1;33m" ;;
        Error) color_code="\033[1;31m" ;;
        *)
            level="Unknown"
            color_code="\033[0m"
            ;;
    esac

    local should_print=0

    if [ "$DRY_RUN" -eq 1 ]; then
        if [ "$VERBOSE" -eq 1 ]; then
            should_print=1
        elif [ "$level" = "Debug" ] && case "$message" in "[EXEC] "*) true ;; *) false ;; esac then
            should_print=1
        fi
    else
        if [ "$level" = "Info" ] || [ "$level" = "Warn" ] || [ "$level" = "Error" ]; then
            should_print=1
        elif [ "$VERBOSE" -eq 1 ] && [ "$level" = "Debug" ]; then
            should_print=1
        fi
    fi

    [ "$should_print" -eq 0 ] && return 0

    local timestamp=""
    if [ "$LOG_TIMESTAMP" -eq 1 ]; then
        timestamp="$(date +"%Y-%m-%d %H:%M:%S") "
    fi

    # 按级别分流：只有 Warn/Error 属于「出事了」，走 stderr；Debug/Info 是正常
    # 流程，走 stdout。两个流都必须这样才说得通 —— App 侧（RootShell）按 fd 定级，
    # 全都写 stderr 会把每条 [Info] 都打成 ERROR，真报错反而被淹没。
    local out_fd=2
    if [ "$level" = "Debug" ] || [ "$level" = "Info" ]; then
        out_fd=1
    fi

    if [ -t "$out_fd" ]; then
        printf "%b\n" "${color_code}${timestamp}[${level}]: ${message}\033[0m" >&"$out_fd"
    else
        printf "%s\n" "${timestamp}[${level}]: ${message}" >&"$out_fd"
    fi
}

load_config() {
    if [ -z "$CONFIG_DIR" ]; then
        CONFIG_DIR="$SCRIPT_DIR"
        log Warn "CONFIG_DIR not specified, fallback to script directory: $CONFIG_DIR"
    fi

    # NOTE: the config file name is also hardcoded in TransparentProxyConfigBuilder /
    # MyTransparentProxyService (FILE_TPROXY_RULES). Keep the two in sync — renaming one
    # side only makes the script silently fall back to its built-in defaults, and `start`
    # still exits 0, so the failure is invisible (wrong ports, no DNS hijack).
    # It must NOT be called tproxy.conf: that name used to be shared with the VPN mode's
    # hev-socks5-tunnel YAML, and this `source` would then parse a YAML file as shell.
    if [ -f "$CONFIG_DIR/tproxy_rules.conf" ]; then
        log Info "Sourcing configuration file: $CONFIG_DIR/tproxy_rules.conf"
        . "$CONFIG_DIR/tproxy_rules.conf"
    else
        log Info "No tproxy_rules.conf found in $CONFIG_DIR, using script defaults + environment variables"
    fi

    log Info "Loading configuration from environment or defaults..."

    DRY_RUN="${DRY_RUN:-$DEFAULT_DRY_RUN}"
    CORE_USER_GROUP="${CORE_USER_GROUP:-$DEFAULT_CORE_USER_GROUP}"
    ROUTING_MARK="${ROUTING_MARK:-$DEFAULT_ROUTING_MARK}"
    FORCE_MARK_BYPASS="${FORCE_MARK_BYPASS:-$DEFAULT_FORCE_MARK_BYPASS}"
    PROXY_TCP_PORT="${PROXY_TCP_PORT:-$DEFAULT_PROXY_TCP_PORT}"
    PROXY_UDP_PORT="${PROXY_UDP_PORT:-$DEFAULT_PROXY_UDP_PORT}"
    PROXY_MODE="${PROXY_MODE:-$DEFAULT_PROXY_MODE}"
    PERFORMANCE_MODE="${PERFORMANCE_MODE:-$DEFAULT_PERFORMANCE_MODE}"
    DNS_HIJACK_ENABLE="${DNS_HIJACK_ENABLE:-$DEFAULT_DNS_HIJACK_ENABLE}"
    DNS_PORT="${DNS_PORT:-$DEFAULT_DNS_PORT}"
    MOBILE_INTERFACE="${MOBILE_INTERFACE:-$DEFAULT_MOBILE_INTERFACE}"
    WIFI_INTERFACE="${WIFI_INTERFACE:-$DEFAULT_WIFI_INTERFACE}"
    HOTSPOT_INTERFACE="${HOTSPOT_INTERFACE:-$DEFAULT_HOTSPOT_INTERFACE}"
    USB_INTERFACE="${USB_INTERFACE:-$DEFAULT_USB_INTERFACE}"
    OTHER_BYPASS_INTERFACES="${OTHER_BYPASS_INTERFACES:-$DEFAULT_OTHER_BYPASS_INTERFACES}"
    OTHER_PROXY_INTERFACES="${OTHER_PROXY_INTERFACES:-$DEFAULT_OTHER_PROXY_INTERFACES}"
    PROXY_MOBILE="${PROXY_MOBILE:-$DEFAULT_PROXY_MOBILE}"
    PROXY_WIFI="${PROXY_WIFI:-$DEFAULT_PROXY_WIFI}"
    PROXY_HOTSPOT="${PROXY_HOTSPOT:-$DEFAULT_PROXY_HOTSPOT}"
    PROXY_USB="${PROXY_USB:-$DEFAULT_PROXY_USB}"
    PROXY_TCP="${PROXY_TCP:-$DEFAULT_PROXY_TCP}"
    PROXY_UDP="${PROXY_UDP:-$DEFAULT_PROXY_UDP}"
    PROXY_IPV6="${PROXY_IPV6:-$DEFAULT_PROXY_IPV6}"
    MARK_VALUE="${MARK_VALUE:-$DEFAULT_MARK_VALUE}"
    MARK_VALUE6="${MARK_VALUE6:-$DEFAULT_MARK_VALUE6}"
    TABLE_ID="${TABLE_ID:-$DEFAULT_TABLE_ID}"
    PROXY_IPv4_LIST="${PROXY_IPv4_LIST:-$DEFAULT_PROXY_IPv4_LIST}"
    PROXY_IPv6_LIST="${PROXY_IPv6_LIST:-$DEFAULT_PROXY_IPv6_LIST}"
    BYPASS_IPv4_LIST="${BYPASS_IPv4_LIST:-$DEFAULT_BYPASS_IPv4_LIST}"
    BYPASS_IPv6_LIST="${BYPASS_IPv6_LIST:-$DEFAULT_BYPASS_IPv6_LIST}"
    BYPASS_DST_LIST="${BYPASS_DST_LIST:-$DEFAULT_BYPASS_DST_LIST}"
    SSH_SERVER_ENTRY="${SSH_SERVER_ENTRY:-$DEFAULT_SSH_SERVER_ENTRY}"
    HOTSPOT_SUBNET_IPV4="${HOTSPOT_SUBNET_IPV4:-$DEFAULT_HOTSPOT_SUBNET_IPV4}"
    HOTSPOT_SUBNET_IPV6="${HOTSPOT_SUBNET_IPV6:-$DEFAULT_HOTSPOT_SUBNET_IPV6}"
    APP_PROXY_ENABLE="${APP_PROXY_ENABLE:-$DEFAULT_APP_PROXY_ENABLE}"
    # ⚠️ APPS_LIST 的空串**故意**落回内置默认（bash 的 `:-` 对空串也回退）：
    # DEFAULT_BYPASS_APPS_LIST 里是 App 自己 —— 这是 uid 粒度防回环的**最后防线**：
    # SO_MARK 会随内核/ROM 失效（"mark 死亡"），BYPASS_DST 对域名型服务端也可能失配，
    # 三道防线里只有 uid 旁路从不失效。2026-10 曾把这里改成「仅未设置才回退」以让
    # App 自身流量进隧道，结果在 mark 已死、BYPASS_DST 失配的设备上隧道 socket 被
    # 抓回自身形成回环，服务端完全连不上 —— 已回滚。
    # 代价是 App 自身流量直连；出口 IP 探测为此**显式走本地 SOCKS5**（见
    # ExitIpProbe.contextAwareFetch），不依赖本表的旁路状态。
    PROXY_APPS_LIST="${PROXY_APPS_LIST:-$DEFAULT_PROXY_APPS_LIST}"
    BYPASS_APPS_LIST="${BYPASS_APPS_LIST:-$DEFAULT_BYPASS_APPS_LIST}"
    APP_PROXY_MODE="${APP_PROXY_MODE:-$DEFAULT_APP_PROXY_MODE}"
    BYPASS_CN_IP="${BYPASS_CN_IP:-$DEFAULT_BYPASS_CN_IP}"
    BYPASS_LOCAL_ADDRS="${BYPASS_LOCAL_ADDRS:-$DEFAULT_BYPASS_LOCAL_ADDRS}"
    LOCAL_ADDR_POLL_INTERVAL="${LOCAL_ADDR_POLL_INTERVAL:-$DEFAULT_LOCAL_ADDR_POLL_INTERVAL}"
    LOCAL_ADDR_USE_MONITOR="${LOCAL_ADDR_USE_MONITOR:-$DEFAULT_LOCAL_ADDR_USE_MONITOR}"
    LOCAL_ADDR_SWEEP_INTERVAL="${LOCAL_ADDR_SWEEP_INTERVAL:-$DEFAULT_LOCAL_ADDR_SWEEP_INTERVAL}"
    CN_IP_FILE="${CN_IP_FILE:-$DEFAULT_CN_IP_FILE}"
    CN_IPV6_FILE="${CN_IPV6_FILE:-$DEFAULT_CN_IPV6_FILE}"
    CN_IP_URL="${CN_IP_URL:-$DEFAULT_CN_IP_URL}"
    CN_IPV6_URL="${CN_IPV6_URL:-$DEFAULT_CN_IPV6_URL}"
    MAC_FILTER_ENABLE="${MAC_FILTER_ENABLE:-$DEFAULT_MAC_FILTER_ENABLE}"
    PROXY_MACS_LIST="${PROXY_MACS_LIST:-$DEFAULT_PROXY_MACS_LIST}"
    BYPASS_MACS_LIST="${BYPASS_MACS_LIST:-$DEFAULT_BYPASS_MACS_LIST}"
    MAC_PROXY_MODE="${MAC_PROXY_MODE:-$DEFAULT_MAC_PROXY_MODE}"
    BLOCK_QUIC="${BLOCK_QUIC:-$DEFAULT_BLOCK_QUIC}"
    LOG_TIMESTAMP="${LOG_TIMESTAMP:-$DEFAULT_LOG_TIMESTAMP}"
    SKIP_CHECK_FEATURE="${SKIP_CHECK_FEATURE:-0}"

    if [ "$VERBOSE" -eq 1 ]; then
        for _var in DRY_RUN CORE_USER_GROUP ROUTING_MARK FORCE_MARK_BYPASS \
                    PROXY_TCP_PORT PROXY_UDP_PORT PROXY_MODE PERFORMANCE_MODE \
                    DNS_HIJACK_ENABLE DNS_PORT \
                    MOBILE_INTERFACE WIFI_INTERFACE HOTSPOT_INTERFACE USB_INTERFACE \
                    OTHER_BYPASS_INTERFACES OTHER_PROXY_INTERFACES \
                    PROXY_MOBILE PROXY_WIFI PROXY_HOTSPOT PROXY_USB \
                    PROXY_TCP PROXY_UDP PROXY_IPV6 \
                    MARK_VALUE MARK_VALUE6 TABLE_ID \
                    PROXY_IPv4_LIST PROXY_IPv6_LIST BYPASS_IPv4_LIST BYPASS_IPv6_LIST \
                    HOTSPOT_SUBNET_IPV4 HOTSPOT_SUBNET_IPV6 \
                    APP_PROXY_ENABLE PROXY_APPS_LIST BYPASS_APPS_LIST APP_PROXY_MODE \
                    BYPASS_CN_IP BYPASS_LOCAL_ADDRS LOCAL_ADDR_POLL_INTERVAL \
                    LOCAL_ADDR_USE_MONITOR LOCAL_ADDR_SWEEP_INTERVAL \
                    CN_IP_FILE CN_IPV6_FILE CN_IP_URL CN_IPV6_URL \
                    MAC_FILTER_ENABLE PROXY_MACS_LIST BYPASS_MACS_LIST MAC_PROXY_MODE \
                    BLOCK_QUIC LOG_TIMESTAMP SKIP_CHECK_FEATURE; do
            eval "log Debug \"$_var: \$$_var\""
        done
    fi

    # 本机地址旁路的开关与间隔下面全用算术比较（[ "$X" -eq 1 ]），写进非数字会让每次
    # 比较变成 `[: illegal number` —— 旁路就**静默**失效了，日志里什么都看不到。
    # 这里校验一次，不合法就回到默认值并响亮告警。
    local _bad_local_addr=""
    for _var in BYPASS_LOCAL_ADDRS LOCAL_ADDR_USE_MONITOR \
                LOCAL_ADDR_POLL_INTERVAL LOCAL_ADDR_SWEEP_INTERVAL; do
        if ! is_positive_integer "${!_var}"; then
            _bad_local_addr="$_bad_local_addr ${_var}=${!_var}"
        fi
    done
    if [ -n "$_bad_local_addr" ]; then
        log Warn "Invalid local address bypass config (must be a positive integer):$_bad_local_addr — using defaults"
        BYPASS_LOCAL_ADDRS="$DEFAULT_BYPASS_LOCAL_ADDRS"
        LOCAL_ADDR_USE_MONITOR="$DEFAULT_LOCAL_ADDR_USE_MONITOR"
        LOCAL_ADDR_POLL_INTERVAL="$DEFAULT_LOCAL_ADDR_POLL_INTERVAL"
        LOCAL_ADDR_SWEEP_INTERVAL="$DEFAULT_LOCAL_ADDR_SWEEP_INTERVAL"
    fi

    log Info "Configuration loading completed"
}

save_runtime_config() {
    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "Skip saving runtime config"
        return 0
    fi

    local runtime_file="$CONFIG_DIR/runtime_tproxy.conf"
    log Info "Saving runtime config to $runtime_file"

    {
        echo "# Runtime config slice for stop/cleanup only (generated at $(date))"
        echo "CONFIG_DIR=$CONFIG_DIR"
        echo "CORE_USER_GROUP=$CORE_USER_GROUP"
        echo "PROXY_TCP=$PROXY_TCP"
        echo "PROXY_UDP=$PROXY_UDP"
        echo "PROXY_IPV6=$PROXY_IPV6"
        echo "PROXY_MODE=$PROXY_MODE"
        echo "OTHER_PROXY_INTERFACES=$OTHER_PROXY_INTERFACES"
        echo "BYPASS_CN_IP=$BYPASS_CN_IP"
        echo "BYPASS_LOCAL_ADDRS=$BYPASS_LOCAL_ADDRS"
        echo "BLOCK_QUIC=$BLOCK_QUIC"
        echo "DNS_HIJACK_ENABLE=$DNS_HIJACK_ENABLE"
        echo "TABLE_ID=$TABLE_ID"
        echo "MARK_VALUE=$MARK_VALUE"
        echo "MARK_VALUE6=$MARK_VALUE6"
        echo "USE_TPROXY=$USE_TPROXY"
    } > "$runtime_file" || {
        log Warn "Failed to save runtime config to $runtime_file"
    }
}

load_runtime_config() {
    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "Skip loading runtime config"
        return 0
    fi

    local runtime_file="$CONFIG_DIR/runtime_tproxy.conf"
    if [ -f "$runtime_file" ]; then
        log Info "Loading runtime config from $runtime_file for cleanup"
        . "$runtime_file" || {
            log Warn "Failed to load runtime config from $runtime_file, using current config"
            return 1
        }
    else
        log Warn "No runtime config found at $runtime_file, using current config for cleanup"
        return 1
    fi
}

init_tmpdir() {
    for d in /tmp /data/local/tmp "$CONFIG_DIR/tmp"; do
        if [ -d "$d" ] && [ -w "$d" ]; then
            export TMPDIR="$d"
            log Debug "Using TMPDIR: $TMPDIR"
            return 0
        fi
    done

    if mkdir -p "$CONFIG_DIR/tmp" 2> /dev/null && [ -w "$CONFIG_DIR/tmp" ]; then
        export TMPDIR="$CONFIG_DIR/tmp"
        log Debug "Created fallback TMPDIR: $TMPDIR"
        return 0
    else
        log Error "Failed to find or create writable TMPDIR"
        exit 1
    fi
}

init_kernel_config_cache() {
    [ "$DRY_RUN" -eq 1 ] && return 0
    [ "$SKIP_CHECK_FEATURE" = "1" ] && return 0

    if [ -f /proc/config.gz ]; then
        if zcat /proc/config.gz > "$TMPDIR/kernel_config.cache" 2> /dev/null; then
            log Debug "Kernel config cached to $TMPDIR/kernel_config.cache"
        else
            log Warn "Failed to cache /proc/config.gz"
            rm -f "$TMPDIR/kernel_config.cache" 2> /dev/null
        fi
    fi
}

# Helper: validate a value is a positive integer (zero forks)
is_positive_integer() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    return 0
}

validate_config() {
    log Debug "Validating configuration..."

    if ! is_positive_integer "$PROXY_TCP_PORT" || [ "$PROXY_TCP_PORT" -lt 1 ] || [ "$PROXY_TCP_PORT" -gt 65535 ]; then
        log Error "Invalid PROXY_TCP_PORT: $PROXY_TCP_PORT"
        return 1
    fi

    if ! is_positive_integer "$PROXY_UDP_PORT" || [ "$PROXY_UDP_PORT" -lt 1 ] || [ "$PROXY_UDP_PORT" -gt 65535 ]; then
        log Error "Invalid PROXY_UDP_PORT: $PROXY_UDP_PORT"
        return 1
    fi

    case "$PROXY_MODE" in
        0|1|2) ;;
        *) log Error "Invalid PROXY_MODE: $PROXY_MODE (must be 0=auto, 1=force TPROXY, 2=force REDIRECT)"; return 1 ;;
    esac

    case "$DNS_HIJACK_ENABLE" in
        0|1|2) ;;
        *) log Error "Invalid DNS_HIJACK_ENABLE: $DNS_HIJACK_ENABLE (must be 0=disabled, 1=tproxy, 2=redirect)"; return 1 ;;
    esac

    if ! is_positive_integer "$DNS_PORT" || [ "$DNS_PORT" -lt 1 ] || [ "$DNS_PORT" -gt 65535 ]; then
        log Error "Invalid DNS_PORT: $DNS_PORT"
        return 1
    fi

    if [ -n "$SSH_SERVER_ENTRY" ]; then
        case "$SSH_SERVER_ENTRY" in
            *:*)
                local _ssh_port="${SSH_SERVER_ENTRY##*:}"
                if ! is_positive_integer "$_ssh_port" || [ "$_ssh_port" -lt 1 ] || [ "$_ssh_port" -gt 65535 ]; then
                    log Error "Invalid SSH_SERVER_ENTRY port: $SSH_SERVER_ENTRY"
                    return 1
                fi
                ;;
        esac
    fi

    if ! is_positive_integer "$MARK_VALUE" || [ "$MARK_VALUE" -lt 1 ] || [ "$MARK_VALUE" -gt 2147483647 ]; then
        log Error "Invalid MARK_VALUE: $MARK_VALUE"
        return 1
    fi

    if ! is_positive_integer "$MARK_VALUE6" || [ "$MARK_VALUE6" -lt 1 ] || [ "$MARK_VALUE6" -gt 2147483647 ]; then
        log Error "Invalid MARK_VALUE6: $MARK_VALUE6"
        return 1
    fi

    if ! is_positive_integer "$TABLE_ID" || [ "$TABLE_ID" -lt 1 ] || [ "$TABLE_ID" -gt 65535 ]; then
        log Error "Invalid TABLE_ID: $TABLE_ID"
        return 1
    fi

    case "$CORE_USER_GROUP" in
        *:*)
            CORE_USER="${CORE_USER_GROUP%%:*}"
            CORE_GROUP="${CORE_USER_GROUP#*:}"
            log Debug "Parsed user:group as '$CORE_USER:$CORE_GROUP'"
            ;;
    esac

    if [ -z "$CORE_USER" ] || [ -z "$CORE_GROUP" ]; then
        log Warn "Empty user or group detected, Using default user:group 'root:net_admin'"
        CORE_USER="root"
        CORE_GROUP="net_admin"
    fi

    case "$APP_PROXY_MODE" in
        blacklist | whitelist) ;;
        *)
            log Error "Invalid APP_PROXY_MODE: $APP_PROXY_MODE"
            return 1
            ;;
    esac

    case "$MAC_PROXY_MODE" in
        blacklist | whitelist) ;;
        *)
            log Error "Invalid MAC_PROXY_MODE: $MAC_PROXY_MODE"
            return 1
            ;;
    esac

    log Debug "Configuration validation passed"
    return 0
}

check_root() {
    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "Skip root check"
        return 0
    fi
    if [ "$(id -u 2> /dev/null || echo 1)" != "0" ]; then
        log Error "Must run with root privileges"
        exit 1
    fi
}

check_dependencies() {
    export PATH="$PATH:/data/data/com.termux/files/usr/bin"

    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "Skip dependency check"
        return 0
    fi

    local missing=""
    local required_commands="ip iptables"
    local cmd

    for cmd in $required_commands; do
        if ! command -v "$cmd" > /dev/null 2>&1; then
            missing="$missing $cmd"
        fi
    done

    if [ -n "$missing" ]; then
        log Error "Missing required commands: $missing"
        log Error "Please check PATH: $PATH"
        exit 1
    fi
}

setup_busybox() {
    if command -v busybox > /dev/null 2>&1; then
        log Debug "BusyBox already available in PATH: $(command -v busybox)"
        return 0
    fi

    log Debug "BusyBox not found in PATH, starting detection..."

    local bb_paths="
        /data/adb/ksu/bin/busybox
        /data/adb/ap/bin/busybox
        /data/adb/magisk/busybox
    "

    local found_bb=""
    for bb in $bb_paths; do
        if [ -f "$bb" ] && [ -x "$bb" ]; then
            found_bb="$bb"
            break
        fi
    done

    if [ -n "$found_bb" ]; then
        local bb_dir=$(dirname "$found_bb")
        export PATH="$PATH:$bb_dir"
        log Info "BusyBox detected and added to PATH: $found_bb"
    else
        log Warn "No BusyBox found in common root paths"
    fi
}

check_kernel_feature() {
    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "Skip kernel feature check for $1"
        return 0
    fi

    if [ "$SKIP_CHECK_FEATURE" = "1" ]; then
        log Warn "Kernel feature check skipped"
        return 0
    fi

    local feature="$1"
    local config_name="CONFIG_${feature}"

    # Check compile-time config (/proc/config.gz)
    if [ -f "$TMPDIR/kernel_config.cache" ]; then
        if grep -qE "^${config_name}=[ym]$" "$TMPDIR/kernel_config.cache" 2> /dev/null; then
            log Debug "Kernel feature $feature is enabled (config)"
            return 0
        fi
    fi

    # check runtime loaded modules (/sys/module/)
    local module_name=""
    case "$feature" in
        IP_SET)                       module_name="ip_set" ;;
        NETFILTER_XT_SET)             module_name="xt_set" ;;
        NETFILTER_XT_MATCH_ADDRTYPE)  module_name="xt_addrtype" ;;
        NETFILTER_XT_TARGET_TPROXY)   module_name="xt_TPROXY" ;;
    esac
    if [ -n "$module_name" ] && [ -d "/sys/module/$module_name" ]; then
        log Debug "Kernel feature $feature is enabled (loaded module)"
        return 0
    fi

    log Warn "Kernel feature $feature is disabled or not found"
    return 1
}

init_feature_flags() {
    log Info "Detecting kernel features..."
    check_kernel_feature "NETFILTER_XT_TARGET_TPROXY" && HAS_TPROXY=1
    check_kernel_feature "NETFILTER_XT_MATCH_CONNTRACK" && HAS_CONNTRACK=1
    check_kernel_feature "NETFILTER_XT_MATCH_OWNER" && HAS_OWNER=1
    check_kernel_feature "NETFILTER_XT_MATCH_MARK" && HAS_MARK_MT=1
    check_kernel_feature "NETFILTER_XT_TARGET_MARK" && HAS_MARK_TG=1
    check_kernel_feature "NETFILTER_XT_MATCH_SOCKET" && HAS_SOCKET=1
    check_kernel_feature "NETFILTER_XT_MATCH_ADDRTYPE" && HAS_ADDRTYPE=1
    check_kernel_feature "NETFILTER_XT_MATCH_MAC" && HAS_MAC=1
    check_kernel_feature "IP_SET" && HAS_IPSET=1
    check_kernel_feature "NETFILTER_XT_SET" && HAS_XT_SET=1
    check_kernel_feature "IP6_NF_NAT" && HAS_NAT6=1
    check_kernel_feature "IP6_NF_TARGET_REDIRECT" && HAS_REDIRECT6=1
    # 单独记一下 ip6tables 在不在：v4 的 addrtype 符号存在**不代表** v6 那半能用，
    # v6 链起不来时本机地址旁路对该族就是 none，不能拿 v4 的能力推断去充数。
    command -v ip6tables > /dev/null 2>&1 && HAS_IP6TABLES=1
}

check_tproxy_support() {
    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "TPROXY support check skipped"
        return 0
    fi

    if [ "$HAS_TPROXY" -eq 1 ]; then
        log Info "Kernel TPROXY support confirmed"
        return 0
    else
        log Warn "Kernel TPROXY support not available"
        return 1
    fi
}

# Unified command wrapper functions
run_ipt_command() {
    local cmd="$1"
    shift

    log Debug "[EXEC] $cmd -w 100 $*"

    [ "$DRY_RUN" -eq 1 ] && return 0

    command "$cmd" -w 100 "$@"
}

iptables() {
    run_ipt_command iptables "$@"
}

ip6tables() {
    run_ipt_command ip6tables "$@"
}

ip_rule() {
    log Debug "[EXEC] ip rule $*"
    [ "$DRY_RUN" -eq 1 ] && return 0
    command ip rule "$@"
}

ip6_rule() {
    log Debug "[EXEC] ip -6 rule $*"
    [ "$DRY_RUN" -eq 1 ] && return 0
    command ip -6 rule "$@"
}

ip_route() {
    log Debug "[EXEC] ip route $*"
    [ "$DRY_RUN" -eq 1 ] && return 0
    command ip route "$@"
}

ip6_route() {
    log Debug "[EXEC] ip -6 route $*"
    [ "$DRY_RUN" -eq 1 ] && return 0
    command ip -6 route "$@"
}

find_packages_uid() {
    [ $# -eq 0 ] && return 0

    awk -v tokens="$*" '
    BEGIN {
        n = split(tokens, t_arr, " ")
        for (i = 1; i <= n; i++) {
            t = t_arr[i]
            if (t ~ /:/) {
                split(t, parts, ":")
                pfx = parts[1]; pkg = parts[2]
            } else {
                pfx = 0; pkg = t
            }
            # Record that we want this package and store its prefix(es)
            wanted[pkg] = 1
            # Multiple prefixes might exist for the same package
            pfxs[pkg] = (pkg in pfxs) ? pfxs[pkg] " " pfx : pfx
        }
    }
    ($1 in wanted) {
        base_uid = ""
        if ($2 ~ /^[0-9]+$/) base_uid = $2
        else if ($(NF-1) ~ /^[0-9]+$/) base_uid = $(NF-1)
        
        if (base_uid != "") {
            m = split(pfxs[$1], p_arr, " ")
            for (j = 1; j <= m; j++) {
                # Store result keyed by package and prefix to preserve order later
                res[$1, p_arr[j]] = (p_arr[j] * 100000 + base_uid)
            }
        }
    }
    END {
        final_out = ""
        for (i = 1; i <= n; i++) {
            t = t_arr[i]
            if (t ~ /:/) {
                split(t, parts, ":")
                pfx = parts[1]; pkg = parts[2]
            } else {
                pfx = 0; pkg = t
            }
            
            if ((pkg, pfx) in res) {
                final_out = (final_out == "") ? res[pkg, pfx] : final_out " " res[pkg, pfx]
            }
        }
        print final_out
    }
    ' /data/system/packages.list
}

safe_chain_create() {
    local family="$1"
    local table="$2"
    local chain="$3"
    local cmd="iptables"

    [ "$family" = "6" ] && cmd="ip6tables"

    $cmd -t "$table" -N "$chain" 2>/dev/null || true
    $cmd -t "$table" -F "$chain"
}

download_file() {
    local url="$1"
    local output="$2"

    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "[EXEC] download $url -> $output (skipped, dry-run)"
        return 0
    fi

    if command -v curl > /dev/null 2>&1; then
        log Debug "[EXEC] curl -fsSL --connect-timeout 10 --retry 3 $url -o $output"
        curl -fsSL --connect-timeout 10 --retry 3 "$url" -o "$output"
    else
        log Debug "[EXEC] busybox wget -q -T 10 -t 3 -O $output $url"
        busybox wget -q -T 10 -t 3 -O "$output" "$url"
    fi
}

download_cn_ip_list() {
    if [ "$BYPASS_CN_IP" -eq 0 ]; then
        log Debug "CN IP bypass is disabled, download skipped"
        return 0
    fi

    log Info "Checking/Downloading China mainland IP list to $CONFIG_DIR/$CN_IP_FILE"

    # Re-download if file doesn't exist or is older than 7 days
    if [ ! -f "$CONFIG_DIR/$CN_IP_FILE" ] || [ "$(find "$CONFIG_DIR/$CN_IP_FILE" -mtime +7 2> /dev/null)" ]; then
        log Info "Fetching latest China IP list from $CN_IP_URL"

        if ! download_file "$CN_IP_URL" "$CONFIG_DIR/$CN_IP_FILE.tmp"; then
            log Error "Failed to download China IP list"
            log Debug "[EXEC] rm -f $CONFIG_DIR/$CN_IP_FILE.tmp"
            rm -f "$CONFIG_DIR/$CN_IP_FILE.tmp"
            return 1
        fi

        log Debug "[EXEC] mv $CONFIG_DIR/$CN_IP_FILE.tmp $CONFIG_DIR/$CN_IP_FILE"
        if [ "$DRY_RUN" -eq 0 ]; then
            mv "$CONFIG_DIR/$CN_IP_FILE.tmp" "$CONFIG_DIR/$CN_IP_FILE"
        fi
        log Info "China IP list saved to $CONFIG_DIR/$CN_IP_FILE"
    else
        log Debug "Using existing China IP list: $CONFIG_DIR/$CN_IP_FILE"
    fi

    if [ "$PROXY_IPV6" -eq 1 ]; then
        log Info "Checking/Downloading China mainland IPv6 list to $CONFIG_DIR/$CN_IPV6_FILE"

        if [ ! -f "$CONFIG_DIR/$CN_IPV6_FILE" ] || [ "$(find "$CONFIG_DIR/$CN_IPV6_FILE" -mtime +7 2> /dev/null)" ]; then
            log Info "Fetching latest China IPv6 list from $CN_IPV6_URL"

            if ! download_file "$CN_IPV6_URL" "$CONFIG_DIR/$CN_IPV6_FILE.tmp"; then
                log Error "Failed to download China IPv6 list"
                log Debug "[EXEC] rm -f $CONFIG_DIR/$CN_IPV6_FILE.tmp"
                rm -f "$CONFIG_DIR/$CN_IPV6_FILE.tmp"
                return 1
            fi

            log Debug "[EXEC] mv $CONFIG_DIR/$CN_IPV6_FILE.tmp $CONFIG_DIR/$CN_IPV6_FILE"
            if [ "$DRY_RUN" -eq 0 ]; then
                mv "$CONFIG_DIR/$CN_IPV6_FILE.tmp" "$CONFIG_DIR/$CN_IPV6_FILE"
            fi
            log Info "China IPv6 list saved to $CONFIG_DIR/$CN_IPV6_FILE"
        else
            log Debug "Using existing China IPv6 list: $CONFIG_DIR/$CN_IPV6_FILE"
        fi
    fi
}

setup_cn_ipset() {
    if [ "$BYPASS_CN_IP" -eq 0 ]; then
        log Debug "CN IP bypass is disabled, ipset setup skipped"
        return 0
    fi

    if ! command -v ipset > /dev/null 2>&1; then
        log Error "ipset command not found. Cannot bypass CN IPs"
        return 1
    fi

    log Info "Setting up ipset for China mainland IPs"

    log Debug "[EXEC] ipset destroy cnip"
    log Debug "[EXEC] ipset destroy cnip6"
    if [ "$DRY_RUN" -eq 0 ]; then
        ipset destroy cnip 2> /dev/null || true
        ipset destroy cnip6 2> /dev/null || true
    fi

    local ipv4_count
    local ipv6_count

    if [ -f "$CONFIG_DIR/$CN_IP_FILE" ]; then
        log Debug "Loading IPv4 CIDR from $CONFIG_DIR/$CN_IP_FILE"

        ipv4_count=$(wc -l < "$CONFIG_DIR/$CN_IP_FILE" 2> /dev/null || echo "0")

        log Debug "[EXEC] ipset create cnip hash:net family inet hashsize 8192 maxelem 65536"
        log Debug "[EXEC] Generating temporary ipset restore file with $ipv4_count entries"

        if [ "$DRY_RUN" -eq 0 ]; then
            temp_file=$(mktemp) || {
                log Error "Failed to create temporary file for ipset restore"
                return 1
            }
            {
                echo "create cnip hash:net family inet hashsize 8192 maxelem 65536"
                awk '!/^[[:space:]]*#/ && NF > 0 {printf "add cnip %s\n", $0}' "$CONFIG_DIR/$CN_IP_FILE"
            } > "$temp_file" || {
                log Error "Failed to write to temporary file: $temp_file"
                rm -f "$temp_file"
                return 1
            }
        else
            log Debug "[EXEC] Would create temporary file and add $ipv4_count entries to cnip"
        fi

        log Debug "[EXEC] ipset restore -f \"$temp_file\""

        if [ "$DRY_RUN" -eq 0 ]; then
            if ipset restore -f "$temp_file" 2> /dev/null; then
                log Info "Successfully loaded $ipv4_count IPv4 CIDR entries into ipset 'cnip'"
            else
                log Error "Failed to create ipset 'cnip' or load IPv4 CIDR entries"
                rm -f "$temp_file" 2> /dev/null
                return 1
            fi
            log Debug "[EXEC] rm -f $temp_file"
            rm -f "$temp_file"
        else
            log Debug "[EXEC] Would load $ipv4_count IPv4 CIDR entries via ipset restore"
        fi

    else
        log Error "CN IP file not found: $CONFIG_DIR/$CN_IP_FILE"
        return 1
    fi
    log Info "ipset 'cnip' loaded with China mainland IPs"

    if [ "$PROXY_IPV6" -eq 1 ]; then
        if [ -f "$CONFIG_DIR/$CN_IPV6_FILE" ]; then
            log Debug "Loading IPv6 CIDR from $CONFIG_DIR/$CN_IPV6_FILE"

            ipv6_count=$(wc -l < "$CONFIG_DIR/$CN_IPV6_FILE" 2> /dev/null || echo "0")

            log Debug "[EXEC] ipset create cnip6 hash:net family inet6 hashsize 8192 maxelem 65536"
            log Debug "[EXEC] Generating temporary ipset restore file with $ipv6_count entries"

            if [ "$DRY_RUN" -eq 0 ]; then
                temp_file6=$(mktemp) || {
                    log Error "Failed to create temporary file for ipset restore"
                    return 1
                }
                {
                    echo "create cnip6 hash:net family inet6 hashsize 8192 maxelem 65536"
                    awk '!/^[[:space:]]*#/ && NF > 0 {printf "add cnip6 %s\n", $0}' "$CONFIG_DIR/$CN_IPV6_FILE"
                } > "$temp_file6" || {
                    log Error "Failed to write to temporary file: $temp_file6"
                    rm -f "$temp_file6"
                    return 1
                }
            else
                log Debug "[EXEC] Would create temporary file and add $ipv6_count entries to cnip6"
            fi

            log Debug "[EXEC] ipset restore -f \"$temp_file6\""

            if [ "$DRY_RUN" -eq 0 ]; then
                if ipset restore -f "$temp_file6" 2> /dev/null; then
                    log Info "Successfully loaded $ipv6_count IPv6 CIDR entries into ipset 'cnip6'"
                else
                    log Error "Failed to create ipset 'cnip6' or load IPv6 CIDR entries"
                    rm -f "$temp_file6" 2> /dev/null
                    return 1
                fi
                log Debug "[EXEC] rm -f $temp_file6"
                rm -f "$temp_file6"
            else
                log Debug "[EXEC] Would load $ipv6_count IPv6 CIDR entries via ipset restore"
            fi

        else
            log Error "CN IPv6 file not found: $CONFIG_DIR/$CN_IPV6_FILE"
            return 1
        fi

        log Info "ipset 'cnip6' loaded with China mainland IPv6 IPs"
    fi
}

# Helper: add sub-chain jump rules with optional performance mode conntrack optimization
# Uses dynamic scoping for $cmd and $table from the calling function
_add_chain_jumps() {
    local parent="$1" perf="$2"
    shift 2
    local target
    for target in "$@"; do
        if [ "$perf" -eq 1 ]; then
            $cmd -t "$table" -A "$parent" -p tcp --syn -j "$target"
            $cmd -t "$table" -A "$parent" -p udp -m conntrack --ctstate NEW,RELATED -j "$target"
        else
            $cmd -t "$table" -A "$parent" -j "$target"
        fi
    done
}

# 用 addrtype 匹配「目的地址属于本机」：内核自己维护本机地址表，不依赖任何后台进程。
# 两条必须一起成（见 setup_proxy_chain 的说明），成对返回 0；任一失败返回 1。
local_addr_rule_addrtype() {
    local cmd="$1"
    local table="$2"
    local chain="$3"
    if $cmd -t "$table" -A "$chain" -m addrtype --dst-type LOCAL -p udp ! --dport 53 -j ACCEPT && \
       $cmd -t "$table" -A "$chain" -m addrtype --dst-type LOCAL ! -p udp -j ACCEPT; then
        log Info "Added local address type bypass ($chain)"
        return 0
    fi
    return 1
}

# 用 ipset 匹配同一个语义。集合由 setup_local_addr_sets 建表、后台进程增删；
# 引用不存在的集合会让规则整体插不进去，所以先确认集合在。
local_addr_rule_ipset() {
    local cmd="$1"
    local table="$2"
    local chain="$3"
    local set_name="$4"
    if ! ipset list "$set_name" > /dev/null 2>&1; then
        return 1
    fi
    if $cmd -t "$table" -A "$chain" -m set --match-set "$set_name" dst -p udp ! --dport 53 -j ACCEPT && \
       $cmd -t "$table" -A "$chain" -m set --match-set "$set_name" dst ! -p udp -j ACCEPT; then
        log Info "Added ipset-based local address bypass ($set_name)"
        return 0
    fi
    return 1
}

setup_proxy_chain() {
    local family="$1"
    local mode="$2" # tproxy or redirect
    local suffix=""
    local mark="$MARK_VALUE"
    local cmd="iptables"

    if [ "$family" = "6" ]; then
        suffix="6"
        mark="$MARK_VALUE6"
        cmd="ip6tables"
    fi

    # Set mode name for logging
    local mode_name="$mode"
    if [ "$mode" = "tproxy" ]; then
        mode_name="TPROXY"
    else
        mode_name="REDIRECT"
    fi

    log Info "Setting up $mode_name chains for IPv${family}"

    # Define chains based on family
    local chains=""
    chains="PROXY_PREROUTING$suffix PROXY_OUTPUT$suffix DIVERT$suffix PROXY_IP$suffix BYPASS_IP$suffix BYPASS_DST$suffix BYPASS_INTERFACE$suffix PROXY_INTERFACE$suffix DNS_HIJACK_PRE$suffix DNS_HIJACK_OUT$suffix APP_CHAIN$suffix MAC_CHAIN$suffix"

    local table="mangle"
    if [ "$mode" = "redirect" ]; then
        table="nat"
    fi

    # Create chains
    for c in $chains; do
        safe_chain_create "$family" "$table" "$c"
    done

    if [ "$PERFORMANCE_MODE" -eq 1 ] && [ "$HAS_MARK_TG" -eq 1 ] && [ "$HAS_SOCKET" -eq 1 ]; then
        $cmd -t "$table" -A DIVERT$suffix -j MARK --set-mark "$mark"
        $cmd -t "$table" -A DIVERT$suffix -j ACCEPT

        $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p tcp -m socket --transparent -j DIVERT$suffix
    fi

    if [ "$HAS_CONNTRACK" -eq 1 ]; then
        $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -m conntrack --ctdir REPLY -j ACCEPT
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m conntrack --ctdir REPLY -j ACCEPT
        log Info "Added reply connection direction bypass"
    fi

    local bypass_success=0
    if [ "$FORCE_MARK_BYPASS" -eq 1 ] && [ "$HAS_MARK_MT" -eq 1 ] && [ -n "$ROUTING_MARK" ]; then
        $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -m mark --mark "$ROUTING_MARK" -j ACCEPT
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m mark --mark "$ROUTING_MARK" -j ACCEPT
        log Info "Added bypass for marked traffic with core mark $ROUTING_MARK (forced)"
        bypass_success=1
    elif [ "$FORCE_MARK_BYPASS" -eq 1 ]; then
        # App 侧已探测「SO_MARK 能设上」才生成 FORCE_MARK_BYPASS=1，但**内核是否带
        # NETFILTER_XT_MATCH_MATCH 只能在这里判**（App 探测不到 xt match 模块）。
        # 内核缺该 match 时 mark 规则加不上，隧道 socket 又已被 App 移出
        # BYPASS_APPS_LIST ⇒ 必然死循环。这条必须响亮地报出来，否则症状只会是
        # 「SSH 连不上」，根因完全无从推断。
        log Error "FORCE_MARK_BYPASS=1 but kernel lacks NETFILTER_XT_MATCH_MARK — cannot add mark bypass, tunnel will loop"
    elif [ "$HAS_OWNER" -eq 1 ]; then
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -j ACCEPT
        log Info "Added bypass for core user $CORE_USER:$CORE_GROUP"
        bypass_success=1
    elif [ "$HAS_MARK_MT" -eq 1 ] && [ -n "$ROUTING_MARK" ]; then
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m mark --mark "$ROUTING_MARK" -j ACCEPT
        log Info "Added bypass for marked traffic with core mark $ROUTING_MARK"
        bypass_success=1
    fi
    if [ "$bypass_success" -eq 0 ]; then
        log Error "Core traffic bypass not configured, may cause traffic loop"
    fi

    # Pre-check performance mode with conntrack
    local _perf_ct=0
    if [ "$PERFORMANCE_MODE" -eq 1 ] && [ "$HAS_CONNTRACK" -eq 1 ]; then
        _perf_ct=1
    fi

    # --- Destination-based bypass (tri-tuple) ---------------------------------
    # Matched destinations ACCEPT and escape TPROXY/REDIRECT *before* the proxy
    # final-state rules. This is the loop-avoidance PRIMARY path: the App injects the
    # SSH tunnel server (ip:port) here, so the tunnel socket does NOT need SO_MARK /
    # pidfd_getfd (Linux 5.6+), which is unavailable on many kernels. uid bypass
    # (-m owner --uid-owner CORE_USER) stays as the fallback.
    # ICMP (bare-IP entries) is added explicitly because _add_chain_jumps' perf
    # wrapping only covers TCP/UDP.
    local _icmp_proto="icmp"
    [ "$family" = "6" ] && _icmp_proto="ipv6-icmp"
    if [ "$_perf_ct" -eq 1 ]; then
        $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p tcp --syn -j "BYPASS_DST$suffix"
        $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p udp -m conntrack --ctstate NEW,RELATED -j "BYPASS_DST$suffix"
        $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p "$_icmp_proto" -j "BYPASS_DST$suffix"
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -p tcp --syn -j "BYPASS_DST$suffix"
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -p udp -m conntrack --ctstate NEW,RELATED -j "BYPASS_DST$suffix"
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -p "$_icmp_proto" -j "BYPASS_DST$suffix"
    else
        $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -j "BYPASS_DST$suffix"
        $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -j "BYPASS_DST$suffix"
    fi
    setup_dst_bypass "$family" "$mode"
    # -------------------------------------------------------------------------

    _add_chain_jumps "PROXY_PREROUTING$suffix" "$_perf_ct" \
        "PROXY_IP$suffix" "BYPASS_IP$suffix" "PROXY_INTERFACE$suffix" "MAC_CHAIN$suffix" "DNS_HIJACK_PRE$suffix"

    _add_chain_jumps "PROXY_OUTPUT$suffix" "$_perf_ct" \
        "PROXY_IP$suffix" "BYPASS_IP$suffix" "BYPASS_INTERFACE$suffix" "APP_CHAIN$suffix" "DNS_HIJACK_OUT$suffix"

    local subnet4
    local subnet6
    if [ "$family" = "6" ]; then
        if [ -n "$PROXY_IPv6_LIST" ]; then
            for subnet6 in $PROXY_IPv6_LIST; do
                $cmd -t "$table" -A "PROXY_IP$suffix" -d "$subnet6" -j RETURN
            done
            log Info "Added proxy rules for PROXY IPv6 ranges"
        fi
    else
        if [ -n "$PROXY_IPv4_LIST" ]; then
            for subnet4 in $PROXY_IPv4_LIST; do
                $cmd -t "$table" -A "PROXY_IP$suffix" -d "$subnet4" -j RETURN
            done
            log Info "Added proxy rules for PROXY IPv4 ranges"
        fi
    fi

    # 本机地址旁路：优先 addrtype（内核自己跟踪本机地址表），插不进去退回 ipset
    # `localaddr`。两条匹配缺一不可，且两个载体的语义必须一致 —— UDP 放行但排除
    # DNS 端口（DNS 劫挂靠在这里），其余协议放行。
    #
    # 为什么还要回退：local_addr_carrier 只按「模块能不能用」预判，规则真正插入这一
    # 刻仍可能失败（模块在这台机器上装不起来、规则链已被别的进程占住）。不回退就是
    # 静默漏旁路 —— 本机流量被推进隧道，用户只会看到连接变慢，日志里什么都没有。
    # 回退要现场建集合（setup_local_addr_sets 带 force 参数），因为初始那轮判定的是
    # addrtype、没建表；集合必须灌过首份地址再插规则，引用空集合等于没规则。
    if [ "$BYPASS_LOCAL_ADDRS" -eq 1 ]; then
        local _local_set="localaddr$suffix"
        local _local_done=0
        local _local_primary="$(local_addr_carrier "$family")"

        if [ "$_local_primary" = "addrtype" ]; then
            local_addr_rule_addrtype "$cmd" "$table" "BYPASS_IP$suffix" && _local_done=1
            if [ "$_local_done" -eq 0 ]; then
                log Warn "addrtype rule failed for IPv${family}, falling back to ipset ($_local_set)"
                if setup_local_addr_sets "$family"; then
                    local_addr_rule_ipset "$cmd" "$table" "BYPASS_IP$suffix" "$_local_set" && _local_done=1
                fi
            fi
        elif [ "$_local_primary" = "ipset" ]; then
            local_addr_rule_ipset "$cmd" "$table" "BYPASS_IP$suffix" "$_local_set" && _local_done=1
        fi

        if [ "$_local_done" -eq 0 ]; then
            log Warn "Local address bypass unavailable for IPv${family} — local->local traffic will still be proxied"
        fi
    fi

    if [ "$family" = "6" ]; then
        for subnet6 in $BYPASS_IPv6_LIST; do
            $cmd -t "$table" -A "BYPASS_IP$suffix" -d "$subnet6" -p udp ! --dport 53 -j ACCEPT
            $cmd -t "$table" -A "BYPASS_IP$suffix" -d "$subnet6" ! -p udp -j ACCEPT
        done
        log Info "Added bypass rules for BYPASS IPv6 ranges"
    else
        for subnet4 in $BYPASS_IPv4_LIST; do
            $cmd -t "$table" -A "BYPASS_IP$suffix" -d "$subnet4" -p udp ! --dport 53 -j ACCEPT
            $cmd -t "$table" -A "BYPASS_IP$suffix" -d "$subnet4" ! -p udp -j ACCEPT
        done
        log Info "Added bypass rules for BYPASS IPv4 ranges"
    fi

    if [ "$BYPASS_CN_IP" -eq 1 ]; then
        local ipset_name="cnip"
        if [ "$family" = "6" ]; then
            ipset_name="cnip6"
        fi
        if command -v ipset > /dev/null 2>&1 && ipset list "$ipset_name" > /dev/null 2>&1; then
            $cmd -t "$table" -A "BYPASS_IP$suffix" -m set --match-set "$ipset_name" dst -p udp ! --dport 53 -j ACCEPT
            $cmd -t "$table" -A "BYPASS_IP$suffix" -m set --match-set "$ipset_name" dst ! -p udp -j ACCEPT
            log Info "Added ipset-based CN IP bypass rule"
        else
            log Warn "ipset '$ipset_name' not available, skipping CN IP bypass"
        fi
    fi

    log Info "Configuring interface proxy rules"
    $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i lo -j RETURN
    if [ "$PROXY_MOBILE" -eq 1 ]; then
        $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$MOBILE_INTERFACE" -j RETURN
        log Info "Mobile interface $MOBILE_INTERFACE will be proxied"
    else
        $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$MOBILE_INTERFACE" -j ACCEPT
        $cmd -t "$table" -A "BYPASS_INTERFACE$suffix" -o "$MOBILE_INTERFACE" -j ACCEPT
        log Info "Mobile interface $MOBILE_INTERFACE will bypass proxy"
    fi

    local subnet
    if [ "$family" = "6" ]; then
        subnet="$HOTSPOT_SUBNET_IPV6"
    else
        subnet="$HOTSPOT_SUBNET_IPV4"
    fi

    if [ "$HOTSPOT_INTERFACE" = "$WIFI_INTERFACE" ]; then
        if [ "$PROXY_HOTSPOT" -eq 1 ]; then
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$HOTSPOT_INTERFACE" -s "$subnet" -j RETURN
            log Info "Hotspot interface $HOTSPOT_INTERFACE will be proxied"
        else
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$HOTSPOT_INTERFACE" -s "$subnet" -j ACCEPT
            log Info "Hotspot interface $HOTSPOT_INTERFACE will bypass proxy"
        fi

        if [ "$PROXY_WIFI" -eq 1 ]; then
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$WIFI_INTERFACE" ! -s "$subnet" -j RETURN
            log Info "WiFi interface $WIFI_INTERFACE will be proxied"
        else
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$WIFI_INTERFACE" ! -s "$subnet" -j ACCEPT
            $cmd -t "$table" -A "BYPASS_INTERFACE$suffix" -o "$WIFI_INTERFACE" -j ACCEPT
            log Info "WiFi interface $WIFI_INTERFACE will bypass proxy"
        fi
    else
        if [ "$PROXY_WIFI" -eq 1 ]; then
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$WIFI_INTERFACE" -j RETURN
            log Info "WiFi interface $WIFI_INTERFACE will be proxied"
        else
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$WIFI_INTERFACE" -j ACCEPT
            $cmd -t "$table" -A "BYPASS_INTERFACE$suffix" -o "$WIFI_INTERFACE" -j ACCEPT
            log Info "WiFi interface $WIFI_INTERFACE will bypass proxy"
        fi

        if [ "$PROXY_HOTSPOT" -eq 1 ]; then
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$HOTSPOT_INTERFACE" -j RETURN
            log Info "Hotspot interface $HOTSPOT_INTERFACE will be proxied"
        else
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$HOTSPOT_INTERFACE" -j ACCEPT
            $cmd -t "$table" -A "BYPASS_INTERFACE$suffix" -o "$HOTSPOT_INTERFACE" -j ACCEPT
            log Info "Hotspot interface $HOTSPOT_INTERFACE will bypass proxy"
        fi
    fi

    if [ "$PROXY_USB" -eq 1 ]; then
        $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$USB_INTERFACE" -j RETURN
        log Info "USB interface $USB_INTERFACE will be proxied"
    else
        $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$USB_INTERFACE" -j ACCEPT
        $cmd -t "$table" -A "BYPASS_INTERFACE$suffix" -o "$USB_INTERFACE" -j ACCEPT
        log Info "USB interface $USB_INTERFACE will bypass proxy"
    fi

    local interface
    if [ -n "$OTHER_PROXY_INTERFACES" ]; then
        for interface in $OTHER_PROXY_INTERFACES; do
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$interface" -j RETURN
        done
        log Info "Other interface $OTHER_PROXY_INTERFACES will be proxied"
    fi

    if [ -n "$OTHER_BYPASS_INTERFACES" ]; then
        for interface in $OTHER_BYPASS_INTERFACES; do
            $cmd -t "$table" -A "PROXY_INTERFACE$suffix" -i "$interface" -j ACCEPT
            $cmd -t "$table" -A "BYPASS_INTERFACE$suffix" -o "$interface" -j ACCEPT
        done
        log Info "Other interface $OTHER_PROXY_INTERFACES will bypass proxy"
    fi

    log Info "Interface proxy rules configuration completed"

    local mac
    if [ "$MAC_FILTER_ENABLE" -eq 1 ] && [ "$PROXY_HOTSPOT" -eq 1 ] && [ -n "$HOTSPOT_INTERFACE" ]; then
        if [ "$HAS_MAC" -eq 1 ]; then
            log Info "Setting up MAC address filter rules for interface $HOTSPOT_INTERFACE"
            case "$MAC_PROXY_MODE" in
                blacklist)
                    if [ -n "$BYPASS_MACS_LIST" ]; then
                        for mac in $BYPASS_MACS_LIST; do
                            if [ -n "$mac" ]; then
                                $cmd -t "$table" -A "MAC_CHAIN$suffix" -m mac --mac-source "$mac" -i "$HOTSPOT_INTERFACE" -j ACCEPT
                                log Info "Added MAC bypass rule for $mac"
                            fi
                        done
                    else
                        log Warn "MAC blacklist mode enabled but no bypass MACs configured"
                    fi
                    $cmd -t "$table" -A "MAC_CHAIN$suffix" -i "$HOTSPOT_INTERFACE" -j RETURN
                    ;;
                whitelist)
                    if [ -n "$PROXY_MACS_LIST" ]; then
                        for mac in $PROXY_MACS_LIST; do
                            if [ -n "$mac" ]; then
                                $cmd -t "$table" -A "MAC_CHAIN$suffix" -m mac --mac-source "$mac" -i "$HOTSPOT_INTERFACE" -j RETURN
                                log Info "Added MAC proxy rule for $mac"
                            fi
                        done
                    else
                        log Warn "MAC whitelist mode enabled but no proxy MACs configured"
                    fi
                    $cmd -t "$table" -A "MAC_CHAIN$suffix" -i "$HOTSPOT_INTERFACE" -j ACCEPT
                    ;;
            esac
        else
            log Warn "MAC filtering requires NETFILTER_XT_MATCH_MAC kernel feature which is not available"
        fi
    fi

    local uids
    local uid
    if [ "$APP_PROXY_ENABLE" -eq 1 ]; then
        if [ "$HAS_OWNER" -eq 1 ]; then
            log Info "Setting up application filter rules in $APP_PROXY_MODE mode"
            case "$APP_PROXY_MODE" in
                blacklist)
                    if [ -n "$BYPASS_APPS_LIST" ]; then
                        uids=$(find_packages_uid $BYPASS_APPS_LIST)
                        if [ $? -eq 0 ] && [ -n "$uids" ]; then
                            for uid in $uids; do
                                if [ -n "$uid" ]; then
                                    $cmd -t "$table" -A "APP_CHAIN$suffix" -m owner --uid-owner "$uid" -j ACCEPT
                                    log Info "Added bypass for UID $uid"
                                fi
                            done
                        fi
                    else
                        log Warn "App blacklist mode enabled but no bypass apps configured"
                    fi
                    $cmd -t "$table" -A "APP_CHAIN$suffix" -j RETURN
                    ;;
                whitelist)
                    # ⚠️ bypass 优先于 proxy 名单。App 侧在 SO_MARK 不可用时会把
                    # **自己**写进 BYPASS_APPS_LIST：此时隧道 socket 只能按 uid 放行，
                    # 而同一 uid 下无法区分 App 的其他 socket，所以整个 App 必须直连。
                    # 这条必须排在 PROXY_APPS_LIST 的 `-j RETURN` **之前**：
                    #  - 放前面 ⇒ 自己命中 ACCEPT（终止遍历=直连），名单内应用照旧 RETURN；
                    #  - 放后面 ⇒ 自己先命中 RETURN，继续往下走，最终被 PROXY_OUTPUT 链尾
                    #    的 REDIRECT 抓回本地 socks5 ⇒ **隧道死循环**。
                    # 加之前本分支完全不读 BYPASS_APPS_LIST，导致 whitelist 模式下
                    # uid 放行无任何承载者。
                    if [ -n "$BYPASS_APPS_LIST" ]; then
                        uids=$(find_packages_uid $BYPASS_APPS_LIST)
                        if [ $? -eq 0 ] && [ -n "$uids" ]; then
                            for uid in $uids; do
                                if [ -n "$uid" ]; then
                                    $cmd -t "$table" -A "APP_CHAIN$suffix" -m owner --uid-owner "$uid" -j ACCEPT
                                    log Info "Added bypass for UID $uid (takes precedence in whitelist mode)"
                                fi
                            done
                        fi
                    fi
                    if [ -n "$PROXY_APPS_LIST" ]; then
                        uids=$(find_packages_uid $PROXY_APPS_LIST)
                        if [ $? -eq 0 ] && [ -n "$uids" ]; then
                            for uid in $uids; do
                                if [ -n "$uid" ]; then
                                    $cmd -t "$table" -A "APP_CHAIN$suffix" -m owner --uid-owner "$uid" -j RETURN
                                    log Info "Added proxy for UID $uid"
                                fi
                            done
                        fi
                    else
                        log Warn "App whitelist mode enabled but no proxy apps configured"
                    fi
                    $cmd -t "$table" -A "APP_CHAIN$suffix" -j ACCEPT
                    ;;
            esac
        else
            log Warn "Application filtering requires NETFILTER_XT_MATCH_OWNER kernel feature which is not available"
        fi
    fi

    if [ "$DNS_HIJACK_ENABLE" -ne 0 ]; then
        if [ "$mode" = "redirect" ]; then
            setup_dns_hijack "$family" "redirect"
        else
            if [ "$DNS_HIJACK_ENABLE" -eq 2 ]; then
                setup_dns_hijack "$family" "redirect2"
            else
                setup_dns_hijack "$family" "tproxy"
            fi
        fi
    fi

    if [ "$_perf_ct" -eq 1 ]; then
        if [ "$mode" = "tproxy" ]; then
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -m conntrack --ctstate NEW,RELATED -j CONNMARK --set-mark "$mark"
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p tcp -m connmark --mark "$mark" -j TPROXY --on-port "$PROXY_TCP_PORT" --tproxy-mark "$mark"
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p udp -m connmark --mark "$mark" -j TPROXY --on-port "$PROXY_UDP_PORT" --tproxy-mark "$mark"

            $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m conntrack --ctstate NEW,RELATED -j CONNMARK --set-mark "$mark"
            $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m connmark --mark "$mark" -j MARK --set-mark "$mark"
            log Info "TPROXY mode rules added"
        else
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -m conntrack --ctstate NEW,RELATED -j CONNMARK --set-mark "$mark"
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -m connmark --mark "$mark" -j REDIRECT --to-ports "$PROXY_TCP_PORT"

            $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m conntrack --ctstate NEW,RELATED -j CONNMARK --set-mark "$mark"
            $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -m connmark --mark "$mark" -j REDIRECT --to-ports "$PROXY_TCP_PORT"
            log Info "REDIRECT mode rules added"
        fi
    else
        if [ "$mode" = "tproxy" ]; then
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p tcp -j TPROXY --on-port "$PROXY_TCP_PORT" --tproxy-mark "$mark"
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -p udp -j TPROXY --on-port "$PROXY_UDP_PORT" --tproxy-mark "$mark"
            $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -j MARK --set-mark "$mark"
            log Info "TPROXY mode rules added"
        else
            $cmd -t "$table" -A "PROXY_PREROUTING$suffix" -j REDIRECT --to-ports "$PROXY_TCP_PORT"
            $cmd -t "$table" -A "PROXY_OUTPUT$suffix" -j REDIRECT --to-ports "$PROXY_TCP_PORT"
            log Info "REDIRECT mode rules added"
        fi
    fi

    # Add rules to main chains
    if [ "$PROXY_UDP" -eq 1 ] || [ "$mode" = "redirect" ]; then
        $cmd -t "$table" -I PREROUTING -p udp -j "PROXY_PREROUTING$suffix"
        $cmd -t "$table" -I OUTPUT -p udp -j "PROXY_OUTPUT$suffix"
        log Info "Added UDP rules to PREROUTING and OUTPUT chains"
    fi
    if [ "$PROXY_TCP" -eq 1 ]; then
        $cmd -t "$table" -I PREROUTING -p tcp -j "PROXY_PREROUTING$suffix"
        $cmd -t "$table" -I OUTPUT -p tcp -j "PROXY_OUTPUT$suffix"
        log Info "Added TCP rules to PREROUTING and OUTPUT chains"
    fi

    log Info "$mode_name chains for IPv${family} setup completed"
}

# Parse BYPASS_DST_LIST (space-separated tri-tuple entries) plus the auto-injected
# SSH tunnel server into the BYPASS_DST chain.
#
# Entry formats (IPv4 examples; IPv6 uses [v6]:port or bare [v6]):
#   ip            -> entire destination IP, ICMP only (bare IP = ICMP, per App spec)
#   ip:port       -> destination IP + TCP port, and IP + UDP port
#   ip:lo-hi      -> destination IP + TCP port range lo:hi, and UDP range
# The SSH server (SSH_SERVER_ENTRY, e.g. "203.0.113.5:22" or "203.0.113.5" for ICMP)
# is auto-injected FIRST so the tunnel socket escapes TPROXY without SO_MARK /
# pidfd_getfd. uid bypass (-m owner) remains the fallback if SO_MARK is unavailable.
setup_dst_bypass() {
    local family="$1"
    local mode="$2"
    local suffix=""
    local cmd="iptables"
    local proto_icmp="icmp"
    if [ "$family" = "6" ]; then
        suffix="6"
        cmd="ip6tables"
        proto_icmp="ipv6-icmp"
    fi
    local table="mangle"
    [ "$mode" = "redirect" ] && table="nat"

    # Build the effective entry list: SSH server first (loop avoidance primary),
    # then user-provided entries.
    local entries=""
    if [ -n "$SSH_SERVER_ENTRY" ]; then
        entries="$SSH_SERVER_ENTRY"
    fi
    if [ -n "$BYPASS_DST_LIST" ]; then
        entries="$entries $BYPASS_DST_LIST"
    fi
    entries="$(echo "$entries" | tr -s ' ' | sed 's/^ //;s/ $//')"
    [ -z "$entries" ] && { log Info "No destination bypass entries (family $family)"; return 0; }

    local entry ip port_spec lo hi is_v6
    for entry in $entries; do
        is_v6=0
        case "$entry" in
            \[*\]:*)
                # [v6]:port
                ip="${entry#\[}"; ip="${ip%%\]*}"
                port_spec="${entry##*\]:}"
                is_v6=1
                ;;
            \[*\])
                # bare IPv6 (ICMP) — unsupported as ICMP here, skip
                log Warn "Bare IPv6 destination bypass (ICMP) not supported yet: $entry"
                continue
                ;;
            *:*)
                # ipv4:port OR ipv4:lo-hi (range). Distinguish by a '-' in the port part.
                ip="${entry%:*}"
                port_spec="${entry##*:}"
                is_v6=0
                ;;
            *)
                # bare IP -> ICMP
                ip="$entry"
                port_spec=""
                case "$ip" in *:*) is_v6=1;; *) is_v6=0;; esac
                ;;
        esac

        # Skip entries that don't belong to this family (an IPv4 address injected
        # into ip6tables would be rejected, and vice versa).
        if [ "$family" = "6" ] && [ "$is_v6" -ne 1 ]; then
            continue
        fi
        if [ "$family" != "6" ] && [ "$is_v6" -eq 1 ]; then
            continue
        fi

        if [ -z "$port_spec" ]; then
            # ICMP bypass for this destination
            $cmd -t "$table" -A "BYPASS_DST$suffix" -d "$ip" -p "$proto_icmp" -j ACCEPT
            log Info "Added ICMP destination bypass for $ip"
        elif echo "$port_spec" | grep -q -- '-'; then
            # range lo-hi
            lo="${port_spec%-*}"
            hi="${port_spec#*-}"
            $cmd -t "$table" -A "BYPASS_DST$suffix" -d "$ip" -p tcp --dport "$lo:$hi" -j ACCEPT
            $cmd -t "$table" -A "BYPASS_DST$suffix" -d "$ip" -p udp --dport "$lo:$hi" -j ACCEPT
            log Info "Added TCP+UDP destination bypass range $ip:$lo-$hi"
        else
            # single port
            $cmd -t "$table" -A "BYPASS_DST$suffix" -d "$ip" -p tcp --dport "$port_spec" -j ACCEPT
            $cmd -t "$table" -A "BYPASS_DST$suffix" -d "$ip" -p udp --dport "$port_spec" -j ACCEPT
            log Info "Added TCP+UDP destination bypass $ip:$port_spec"
        fi
    done
    log Info "Destination bypass chain configured (family $family)"
}

setup_dns_hijack() {
    local family="$1"
    local mode="$2"
    local suffix=""
    local mark="$MARK_VALUE"
    local cmd="iptables"

    if [ "$family" = "6" ]; then
        suffix="6"
        mark="$MARK_VALUE6"
        cmd="ip6tables"
    fi

    case "$mode" in
        tproxy)
            # Handle DNS from interfaces in PREROUTING chain (DNS_HIJACK_PRE)
            $cmd -t mangle -A "DNS_HIJACK_PRE$suffix" -j RETURN
            # Handle local DNS hijacking in OUTPUT chain (DNS_HIJACK_OUT)
            $cmd -t mangle -A "DNS_HIJACK_OUT$suffix" -j RETURN

            log Info "DNS hijack enabled using TPROXY mode"
            ;;
        redirect)
            # Handle DNS using REDIRECT method
            $cmd -t nat -A "PROXY_PREROUTING$suffix" -p tcp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
            $cmd -t nat -A "PROXY_PREROUTING$suffix" -p udp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
            $cmd -t nat -A "PROXY_OUTPUT$suffix" -p tcp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
            $cmd -t nat -A "PROXY_OUTPUT$suffix" -p udp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
            log Info "DNS hijack enabled using REDIRECT mode to port $DNS_PORT"
            ;;
        redirect2)
            # Handle DNS using REDIRECT method
            if [ "$family" = "6" ] && {
                [ "$HAS_NAT6" -eq 0 ] || [ "$HAS_REDIRECT6" -eq 0 ]
            }; then
                log Warn "IPv6: Kernel does not support IPv6 NAT or REDIRECT, IPv6 DNS hijack skipped"
                return 0
            fi
            safe_chain_create "$family" "nat" "NAT_DNS_HIJACK$suffix"
            $cmd -t nat -A "NAT_DNS_HIJACK$suffix" -p tcp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"
            $cmd -t nat -A "NAT_DNS_HIJACK$suffix" -p udp --dport 53 -j REDIRECT --to-ports "$DNS_PORT"

            [ "$PROXY_MOBILE" -eq 1 ] && $cmd -t nat -A PREROUTING -i "$MOBILE_INTERFACE" -j "NAT_DNS_HIJACK$suffix"
            [ "$PROXY_WIFI" -eq 1 ] && $cmd -t nat -A PREROUTING -i "$WIFI_INTERFACE" -j "NAT_DNS_HIJACK$suffix"
            [ "$PROXY_USB" -eq 1 ] && $cmd -t nat -A PREROUTING -i "$USB_INTERFACE" -j "NAT_DNS_HIJACK$suffix"
            local interface
            if [ -n "$OTHER_PROXY_INTERFACES" ]; then
                for interface in $OTHER_PROXY_INTERFACES; do
                    $cmd -t nat -A PREROUTING -i "$interface" -j "NAT_DNS_HIJACK$suffix"
                done
            fi

            $cmd -t nat -A OUTPUT -p udp --dport 53 -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -j ACCEPT
            $cmd -t nat -A OUTPUT -p tcp --dport 53 -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -j ACCEPT
            $cmd -t nat -A OUTPUT -j "NAT_DNS_HIJACK$suffix"

            log Info "DNS hijack enabled using REDIRECT mode to port $DNS_PORT"
            ;;
    esac
}

setup_tproxy_chain4() {
    setup_proxy_chain 4 "tproxy"
}

setup_redirect_chain4() {
    log Warn "REDIRECT mode only supports TCP"
    setup_proxy_chain 4 "redirect"
}

setup_tproxy_chain6() {
    setup_proxy_chain 6 "tproxy"
}

setup_redirect_chain6() {
    if [ "$HAS_NAT6" -eq 0 ] || [ "$HAS_REDIRECT6" -eq 0 ]; then
        log Warn "IPv6: Kernel does not support IPv6 NAT or REDIRECT, IPv6 proxy setup skipped"
        return 0
    fi
    log Warn "REDIRECT mode only supports TCP"
    setup_proxy_chain 6 "redirect"
}

setup_routing4() {
    log Info "Setting up routing rules for IPv4"

    ip_rule add fwmark "$MARK_VALUE" table "$TABLE_ID" pref "$TABLE_ID" || {
        log Error "Failed to add IPv4 routing rule"
        return 1
    }
    ip_route add local 0.0.0.0/0 dev lo table "$TABLE_ID" || {
        log Error "Failed to add IPv4 route"
        return 1
    }

    log Debug "[EXEC] echo 1 > /proc/sys/net/ipv4/ip_forward"
    [ "$DRY_RUN" -eq 0 ] && echo 1 > /proc/sys/net/ipv4/ip_forward

    log Info "IPv4 routing setup completed"
}

setup_routing6() {
    log Info "Setting up routing rules for IPv6"

    ip6_rule add fwmark "$MARK_VALUE6" table "$TABLE_ID" pref "$TABLE_ID" || {
        log Error "Failed to add IPv6 routing rule"
        return 1
    }
    ip6_route add local ::/0 dev lo table "$TABLE_ID" || {
        log Error "Failed to add IPv6 route"
        return 1
    }

    log Debug "[EXEC] echo 1 > /proc/sys/net/ipv6/conf/all/forwarding"
    [ "$DRY_RUN" -eq 0 ] && echo 1 > /proc/sys/net/ipv6/conf/all/forwarding

    log Info "IPv6 routing setup completed"
}

cleanup_chain() {
    local family="$1"
    local mode="$2"
    local suffix=""
    local cmd="iptables"

    if [ "$family" = "6" ]; then
        suffix="6"
        cmd="ip6tables"
    fi

    local mode_name="$mode"
    if [ "$mode" = "tproxy" ]; then
        mode_name="TPROXY"
    else
        mode_name="REDIRECT"
    fi

    log Info "Cleaning up $mode_name chains for IPv${family}"

    local table="mangle"
    if [ "$mode" = "redirect" ]; then
        table="nat"
    fi

    # Remove from main chains (symmetric with setup)
    if [ "$PROXY_TCP" -eq 1 ]; then
        $cmd -t "$table" -D PREROUTING -p tcp -j "PROXY_PREROUTING$suffix" 2>/dev/null || true
        $cmd -t "$table" -D OUTPUT -p tcp -j "PROXY_OUTPUT$suffix" 2>/dev/null || true
    fi
    if [ "$PROXY_UDP" -eq 1 ] || [ "$mode" = "redirect" ]; then
        $cmd -t "$table" -D PREROUTING -p udp -j "PROXY_PREROUTING$suffix" 2>/dev/null || true
        $cmd -t "$table" -D OUTPUT -p udp -j "PROXY_OUTPUT$suffix" 2>/dev/null || true
    fi

    # Define chains based on family
    local chains="PROXY_PREROUTING$suffix PROXY_OUTPUT$suffix DIVERT$suffix PROXY_IP$suffix BYPASS_IP$suffix BYPASS_DST$suffix BYPASS_INTERFACE$suffix PROXY_INTERFACE$suffix DNS_HIJACK_PRE$suffix DNS_HIJACK_OUT$suffix APP_CHAIN$suffix MAC_CHAIN$suffix"

    # Clean up chains
    for c in $chains; do
        $cmd -t "$table" -F "$c" 2>/dev/null || true
        $cmd -t "$table" -X "$c" 2>/dev/null || true
    done

    # Remove DNS rules if applicable
    if [ "$mode" = "tproxy" ] && [ "$DNS_HIJACK_ENABLE" -eq 2 ]; then
        $cmd -t nat -D PREROUTING -i "$MOBILE_INTERFACE" -j "NAT_DNS_HIJACK$suffix" 2>/dev/null || true
        $cmd -t nat -D PREROUTING -i "$WIFI_INTERFACE" -j "NAT_DNS_HIJACK$suffix" 2>/dev/null || true
        $cmd -t nat -D PREROUTING -i "$USB_INTERFACE" -j "NAT_DNS_HIJACK$suffix" 2>/dev/null || true
        local interface
        if [ -n "$OTHER_PROXY_INTERFACES" ]; then
            for interface in $OTHER_PROXY_INTERFACES; do
                $cmd -t nat -D PREROUTING -i "$interface" -j "NAT_DNS_HIJACK$suffix" 2>/dev/null || true
            done
        fi
        $cmd -t nat -D OUTPUT -p udp --dport 53 -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -j ACCEPT 2>/dev/null || true
        $cmd -t nat -D OUTPUT -p tcp --dport 53 -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -j ACCEPT 2>/dev/null || true
        $cmd -t nat -D OUTPUT -j "NAT_DNS_HIJACK$suffix" 2>/dev/null || true
        $cmd -t nat -F "NAT_DNS_HIJACK$suffix" 2>/dev/null || true
        $cmd -t nat -X "NAT_DNS_HIJACK$suffix" 2>/dev/null || true
    fi

    log Info "$mode_name chains for IPv${family} cleanup completed"
}

cleanup_tproxy_chain4() {
    cleanup_chain 4 "tproxy"
}

cleanup_tproxy_chain6() {
    cleanup_chain 6 "tproxy"
}

cleanup_redirect_chain4() {
    cleanup_chain 4 "redirect"
}

cleanup_redirect_chain6() {
    if [ "$HAS_NAT6" -eq 0 ] || [ "$HAS_REDIRECT6" -eq 0 ]; then
        log Warn "IPv6: Kernel does not support IPv6 NAT or REDIRECT, IPv6 cleanup skipped"
        return 0
    fi
    cleanup_chain 6 "redirect"
}

cleanup_routing4() {
    log Info "Cleaning up IPv4 routing rules"

    ip_rule del fwmark "$MARK_VALUE" table "$TABLE_ID" pref "$TABLE_ID"
    ip_route del local 0.0.0.0/0 dev lo table "$TABLE_ID"

    log Debug "[EXEC] echo 0 > /proc/sys/net/ipv4/ip_forward"
    [ "$DRY_RUN" -eq 0 ] && echo 0 > /proc/sys/net/ipv4/ip_forward

    log Info "IPv4 routing cleanup completed"
}

cleanup_routing6() {
    log Info "Cleaning up IPv6 routing rules"

    ip6_rule del fwmark "$MARK_VALUE6" table "$TABLE_ID" pref "$TABLE_ID"
    ip6_route del local ::/0 dev lo table "$TABLE_ID"

    log Debug "[EXEC] echo 0 > /proc/sys/net/ipv6/conf/all/forwarding"
    [ "$DRY_RUN" -eq 0 ] && echo 0 > /proc/sys/net/ipv6/conf/all/forwarding

    log Info "IPv6 routing cleanup completed"
}

cleanup_ipset() {
    if [ "$BYPASS_CN_IP" -eq 0 ]; then
        log Debug "CN IP bypass is disabled, ipset cleanup skipped"
        return 0
    fi

    log Debug "[EXEC] ipset destroy cnip"
    log Debug "[EXEC] ipset destroy cnip6"
    if [ "$DRY_RUN" -eq 0 ]; then
        ipset destroy cnip 2>/dev/null || true
        ipset destroy cnip6 2>/dev/null || true
        log Info "ipset 'cnip' and 'cnip6' destroyed"
    fi
}

# --- Local-address bypass -------------------------------------------------
#
# 目的地址是本机地址的流量走直连。两条规则挂在 BYPASS_IP（见 setup_proxy_chain），
# 载体二选一：xt_addrtype 在就不用管（内核自己跟踪地址表），不在就靠下面这套 ipset
# 加后台进程。
#
# 后台进程是**独立进程**而不是子 shell：`tproxy.sh start` 是一次性 CLI，跑完就退出，
# 进程必须自己活下来（nohup + &）。它以 `--local-addr-watcher` 重新执行本脚本，
# 因此能重新 load_config 拿到最新的 PROXY_IPV6，也不需要在 sh -c 里塞函数定义。

# 收集本机地址，每行一个。$1 = 1 取 IPv6，否则取 IPv4。
list_local_addrs() {
    local want6="${1-0}"
    ip addr show 2> /dev/null | awk -v want6="$want6" '
        $1 == "inet" || $1 == "inet6" {
            is6 = ($1 == "inet6" ? 1 : 0)
            if (is6 != want6) next
            split($2, a, "/"); print a[1]
        }'
}

# 让 `localaddr[$suffix]` 的成员集合与本机当前地址完全一致：该删的删、该加的加。
# $1 = 地址族后缀（"" 为 IPv4，"6" 为 IPv6）。
# 返回值：0 = 已同步；1 = 集合不存在（调用方应当退出）；2 = 集合还在但写不进去。
# 必须把 2 传出去 —— 之前只 Warn 然后返回 0，调用方分不清「已同步」和「集合一直是
# 空的」，watcher 就会永远报成功、永远空转。
sync_local_ipset() {
    local suffix="${1-}"
    local set_name="localaddr$suffix"
    local want6=0
    [ "$suffix" = "6" ] && want6=1

    if ! ipset list "$set_name" > /dev/null 2>&1; then
        log Debug "ipset '$set_name' is gone, local address sync stops"
        return 1
    fi

    # 必须折成单行：下面的 `case " $cur " in *" $addr "*` 做成员判定，字符串里只要还
    # 留着换行，多元素列表就永远匹配不上 —— 每个 tick 都会把全部成员删一遍再加回来。
    # 本机地址通常不止一个（127.0.0.1 + 局域网 IP），这个坑一定会踩到。
    local cur prev addr _err
    cur="$(list_local_addrs "$want6" | sort -u | tr '\n' ' ')"
    prev="$(ipset save "$set_name" 2> /dev/null | awk -v s="$set_name" \
        '$1 == "add" && $2 == s {print $3}' | sort -u | tr '\n' ' ')"

    for addr in $prev; do
        case " $cur " in
            *" $addr "*) ;;
            *)
                log Debug "[EXEC] ipset del $set_name $addr"
                if [ "$DRY_RUN" -eq 0 ]; then
                    # 删不到只可能是并发的清扫同时动了它（事件循环和慢速清扫会一起同步）。
                    # 不算失败：下一个周期还会再对账一次。
                    ipset del "$set_name" "$addr" 2> /dev/null || \
                        log Debug "ipset del $set_name $addr: not a member (concurrent sync), skipped"
                fi
                log Info "Removed local address bypass entry $addr"
                ;;
        esac
    done

    for addr in $cur; do
        case " $prev " in
            *" $addr "*) ;;
            *)
                log Debug "[EXEC] ipset add $set_name $addr"
                if [ "$DRY_RUN" -eq 0 ]; then
                    _err="$(ipset add "$set_name" "$addr" 2>&1)" || case "$_err" in
                        *"already exists"*)
                            # 并发的同步已经加过了，幂等，当成功。
                            ;;
                        *)
                            # add 失败有两种原因，别混：集合在开头那次 list 之后被销毁了
                            # （stop_proxy 正在收尾），那是 rc=1、该退场；集合还在只是写不
                            # 进去，才是 rc=2、该重试。混成 2 会让调用方记一条误导性 Error
                            # 然后空转到下个周期才退。
                            if ! ipset list "$set_name" > /dev/null 2>&1; then
                                log Debug "ipset '$set_name' disappeared mid-sync, local address sync stops"
                                return 1
                            fi
                            log Error "Failed to add local address $addr to $set_name: $_err"
                            return 2
                            ;;
                    esac
                fi
                log Info "Added local address bypass entry $addr"
                ;;
        esac
    done

    return 0
}

# 只按 LOCAL_ADDR_FAMILIES 决定同步哪个族，不照搬 PROXY_IPV6：后者表示「v6 代理链
# 要不要建」，前者表示「localaddr6 这个集合在不在」。v6 走 addrtype 时集合压根没
# 建，同步它每周期都会刷一条失败。
sync_local_ipset_family() {
    local family="$1"
    local suffix=""
    local rc
    case "$family" in
        6) suffix="6" ;;
        4) suffix="" ;;
        *) return 0 ;;
    esac
    case "$LOCAL_ADDR_FAMILIES" in
        *"$family"*) ;;
        *) return 0 ;;
    esac
    sync_local_ipset "$suffix"
    rc=$?
    return "$rc"
}

# 一次对账两个族。集合没了返回 1（调用方应当退出），写不进去返回 2 —— 这两个含义
# 不同，不能塌成一个「失败」。
sync_local_ipset_both() {
    local rc
    sync_local_ipset_family 4
    rc=$?
    if [ "$rc" -ne 0 ]; then
        return "$rc"
    fi
    sync_local_ipset_family 6
    rc=$?
    if [ "$rc" -ne 0 ]; then
        return "$rc"
    fi
    return 0
}

# 后台进程主循环。集合被销毁（代理已停）就自行退出，免得有人忘记 stop 时留一个
# 一直刷日志的孤儿 —— 这一点在两种模式下都必须成立。
local_addr_watch_loop() {
    local interval="$LOCAL_ADDR_POLL_INTERVAL"
    local rc
    is_positive_integer "$interval" || interval="$DEFAULT_LOCAL_ADDR_POLL_INTERVAL"

    local pf
    pf="$(local_addr_pidfile)"
    [ "$DRY_RUN" -eq 0 ] && echo $$ > "$pf"

    # 先对账一次：事件流只报「之后」的变化，启动那一刻的当前状态得自己同步。
    # （setup_local_addr_sets 已经灌过一份，这里幂等，不算重复开销。）
    # rc=1 是集合没了 -> 退出；rc=2 是写不进去 -> 记日志继续，下个周期再试。
    sync_local_ipset_both
    rc=$?
    if [ "$rc" -eq 1 ]; then
        exit 0
    fi
    if [ "$rc" -eq 2 ]; then
        log Warn "local address set exists but is not writable (rc=2), retrying in the main loop"
    fi

    if [ "$LOCAL_ADDR_USE_MONITOR" -eq 1 ]; then
        # 事件模式没跑起来就退轮询。注意这里的分支是「没拿到数据」，不是「集合没了」：
        # 集合被销毁时 monitor 循环内部已经 exit 0，不会走到这里。
        if ! local_addr_monitor_loop; then
            log Info "ip monitor unavailable, local address watcher falls back to polling (${interval}s)"
        fi
    else
        log Debug "LOCAL_ADDR_USE_MONITOR=0, using polling (${interval}s)"
    fi

    local_addr_poll_loop "$interval"
}

# 轮询模式：定时取一次本机地址快照，跟 ipset 成员做差集。
# $2 = 日志标签。事件模式里的慢速清扫也复用本函数，标签不同免得日志分不清是谁在跑。
local_addr_poll_loop() {
    local interval="$1"
    local label="${2:-Local address watcher}"
    local rc
    log Info "$label running (poll ${interval}s)"
    while :; do
        # rc=1 = 集合没了（代理已停）-> 退出；rc=2 = 集合在但写不进去 -> 记日志，
        # 下个周期再试。不能把 2 当成功吞掉，否则 watcher 会一直报「正常」实则空转。
        sync_local_ipset_both
        rc=$?
        if [ "$rc" -eq 1 ]; then
            exit 0
        fi
        if [ "$rc" -eq 2 ]; then
            log Warn "$label: local address set is not writable (rc=2), retrying next cycle"
        fi
        # sleep 必须放到后台再 wait：POSIX 规定 shell 在等前台命令完成时收到的
        # trap 要等那条命令结束才执行，前台 sleep 会让 stop 的 SIGTERM 最长推迟
        # 一个周期。后台 + wait 让信号立刻打断。
        sleep "$interval" &
        wait $! 2> /dev/null
    done
}

# 事件模式：`ip monitor address` 挂在 netlink 上，任何地址变化立刻往管道吐数据，
# read 被唤醒后跑的就是同一个 sync_local_ipset —— 两种模式对账语义不可能漂移。
#
# 探测和退化是同一个动作，不做单独探测：monitor 不支持或中途挂了，read 立刻拿到
# EOF、while 不执行，本函数返回 1，调用方退轮询。代价只是一次失败 fork，不用
# 解析 `ip help`（各版本格式都不一样），也不需要 toybox 不一定有的 timeout。
#
# 必须用 FIFO 而不是管道（`ip monitor | while read`）：管道右半边的 while 跑在
# **子 shell** 里，里面的 `exit 0` 只杀子 shell、杀不掉 watcher 本身 —— 集合一旦被
# 销毁就会变成「重启 monitor、同步失败、又重启 monitor」的无限空转。FIFO 让 read
# 在 watcher 自己的 shell 里阻塞，exit 才能真正退出进程。
#
# 事件模式下另挂一个慢速清扫（见下）：monitor 进程没了表现为 EOF，本函数返回、上面
# 退轮询；但 socket 还活着却不吐事件的话永远不 EOF，那时靠清扫把过期窗口兜住。
# 清扫复用轮询循环，集合被销毁时它自己 exit，所以就算 watcher 被 SIGKILL 收掉、
# trap 没跑，清扫也会在下一次对账时退掉，不留孤儿。

# 收掉 monitor 与清扫子进程、清掉 FIFO、退出进程。所有出口共用，少收一个就等于把
# 挂着 netlink（或还在轮询）的进程留成孤儿。
# $1 = monitor pid；$2 = FIFO 路径；$3 = 慢速清扫 pid。
#
# 这里必须用 kill 而不是 wait：monitor 的 FIFO 写端还开着，wait 会阻塞到它自己退出，
# 而它挂着 netlink 不会自己退 —— `exit 0` 永远执行不到，watcher 就卡死在这里了。
local_addr_monitor_stop() {
    kill "$1" 2> /dev/null
    kill "$3" 2> /dev/null
    rm -f "$2"
    exit 0
}

local_addr_monitor_loop() {
    local fifo="$TMPDIR/localaddr_monitor.$$.fifo"
    local mp= sp=
    local _line rc
    log Info "Local address watcher running (event mode: ip monitor address)"
    if ! mkfifo "$fifo" 2> /dev/null; then
        rm -f "$fifo"
        log Warn "mkfifo failed, local address watcher falls back to polling"
        return 1
    fi

    # 先起清扫再起 monitor：万一 monitor 起不来，清扫自己会在下一次对账时退掉。
    local_addr_poll_loop "$LOCAL_ADDR_SWEEP_INTERVAL" "Local address sweep" &
    sp=$!

    # 重定向让子进程先打开写端（会阻塞到读端打开），我们紧接着打开读端，两边同时
    # 解开 —— 所以不存在「write 端没人读」的 SIGPIPE 窗口。
    ip monitor address > "$fifo" 2> /dev/null &
    mp=$!

    # stop 走 SIGTERM，此刻 read 正阻塞在 FIFO 上，循环体里的清理一条都执行不到；
    # 不兜这个口，monitor 与清扫会各留一个孤儿。这个 trap 不拆：退化到轮询后
    # $mp/$sp 已是死 pid、$fifo 已删，再触发也只做无害空操作然后正常退出。
    trap 'local_addr_monitor_stop "${mp-}" "${fifo-}" "${sp-}"' INT TERM

    while IFS= read -r _line < "$fifo"; do
        # rc=1 = 集合被销毁（代理已停）-> 全退；rc=2 = 写不进去 -> 记日志，
        # 等下一次事件或清扫再对账，不退。
        sync_local_ipset_both
        rc=$?
        case "$rc" in
            1) local_addr_monitor_stop "$mp" "$fifo" "$sp" ;;
            2) log Warn "local address set is not writable (rc=2), waiting for next event" ;;
        esac
    done

    # 走到这里说明 read 拿到了 EOF，即 monitor 已经自己退出了，wait 能立刻收尸。
    rm -f "$fifo"
    wait "$mp" 2> /dev/null
    kill "$sp" 2> /dev/null
    return 1
}

local_addr_pidfile() {
    [ -z "$CONFIG_DIR" ] && CONFIG_DIR="/tmp"
    echo "$CONFIG_DIR/local_addr_watcher.pid"
}

# 拉起后台进程。由 setup_local_addr_sets 在建链之前调用（start_proxy 顺序保证），
# 让 BYPASS_IP 里的 `-m set` 规则一加上去集合就已经在维护了。
start_local_addr_watcher() {
    [ "$BYPASS_LOCAL_ADDRS" -eq 1 ] || return 0
    # 不按 HAS_ADDRTYPE 提前返回：那是全局判断，v4 走 addrtype 时 v6 可能仍需要
    # ipset（见 local_addr_carrier）。要不要集合已由 setup_local_addr_sets 逐族决定，
    # 它只在确有集合要维护时才调本函数。
    [ "$HAS_IPSET" -eq 1 ] && [ "$HAS_XT_SET" -eq 1 ] || return 0

    local pf old_pid
    pf="$(local_addr_pidfile)"
    if [ -f "$pf" ]; then
        # 上一次运行没清干净的进程会跟这个一起同步（无害但会刷日志），且活得比这次久。
        old_pid="$(cat "$pf" 2> /dev/null)"
        is_positive_integer "$old_pid" && [ "$DRY_RUN" -eq 0 ] && kill "$old_pid" 2> /dev/null
        rm -f "$pf"
    fi

    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "[EXEC] Would launch local address watcher"
        return 0
    fi
    if ! command -v nohup > /dev/null 2>&1; then
        log Warn "nohup not found, local address watcher will not start"
        return 1
    fi

    # LOCAL_ADDR_FAMILIES 是这一轮推导出来的状态，不在配置文件里；子进程重新执行本
    # 脚本时会把自己的默认值当事实，必须显式带过去，否则它会去同步没建的集合。
    log Debug "[EXEC] nohup sh $0 --local-addr-watcher -d $CONFIG_DIR (families=$LOCAL_ADDR_FAMILIES)"
    LOCAL_ADDR_FAMILIES="$LOCAL_ADDR_FAMILIES" nohup sh "$0" --local-addr-watcher -d "$CONFIG_DIR" > /dev/null 2>&1 &
    log Info "Local address watcher launched"
    return 0
}

# 某个地址族用哪种载体：addrtype / ipset / none。
#
# v6 必须单独判：HAS_ADDRTYPE 来自 NETFILTER_XT_MATCH_ADDRTYPE，那是 v4 侧的符号，
# 它存在不代表 ip6tables 那半能用。ip6tables 不存在时 v6 代理链压根没建起来，拿 v4
# 的能力去充数只会让 v6 的旁路静默缺失 —— 这正是本机地址回灌会出问题的地方。
local_addr_carrier() {
    local family="$1"
    if [ "$HAS_ADDRTYPE" -ne 1 ]; then
        if [ "$HAS_IPSET" -eq 1 ] && [ "$HAS_XT_SET" -eq 1 ]; then
            echo "ipset"
        else
            echo "none"
        fi
        return 0
    fi
    if [ "$family" = "6" ] && [ "$HAS_IP6TABLES" -ne 1 ]; then
        echo "none"
        return 0
    fi
    echo "addrtype"
}

# 建表 + 灌首份地址 + 拉后台进程。
#
# $1 可选，形如 "4" / "6" / "46"：强制这些地址族走 ipset 载体。addrtype 规则插入
# 失败的回退路径用它（见 setup_proxy_chain）—— 探测通过不代表规则一定插得进规则链，
# 集合建好了才有真东西可匹配。不传就按 local_addr_carrier 的自然判定：addrtype 优先，
# 不建集合、不拉后台进程（这是常见路径，省一个后台进程）。
#
# 返回值：0 = 集合维护已就绪（或按能力判定无需集合）；1 = 集合该建却没建起来，
# 或者后台进程没起来。这个返回值必须被调用方看见 —— 后台进程没起来意味着集合冻结
# 在首份快照上，地址一变旁路就漏了，用户得知道。
setup_local_addr_sets() {
    local force_ipset="${1-}"
    [ "$BYPASS_LOCAL_ADDRS" -eq 1 ] || return 0

    local force_v4=0 force_v6=0
    case "$force_ipset" in *4*) force_v4=1 ;; esac
    case "$force_ipset" in *6*) force_v6=1 ;; esac

    local need_v4=0 need_v6=0 _carrier _any_none=0

    if [ "$HAS_IPSET" -ne 1 ] || [ "$HAS_XT_SET" -ne 1 ] || \
       ! command -v ipset > /dev/null 2>&1; then
        # 没有 ipset 就只能靠 addrtype；它也不在，这个族就没有任何旁路手段。
        _carrier="$(local_addr_carrier 4)"
        if [ "$_carrier" = "none" ]; then
            _any_none=1
            log Warn "Local address bypass unavailable for IPv4 (no addrtype module and no ipset support) — local->local IPv4 traffic will still be proxied"
        fi
        if [ "$PROXY_IPV6" -eq 1 ]; then
            _carrier="$(local_addr_carrier 6)"
            if [ "$_carrier" = "none" ]; then
                _any_none=1
                log Warn "Local address bypass unavailable for IPv6 (no addrtype on this stack and no ipset) — local->local IPv6 traffic will still be proxied"
            fi
        fi
        if [ -n "$force_ipset" ]; then
            log Warn "Local address bypass cannot fall back to ipset (ipset unavailable)"
            return 1
        fi
        [ "$_any_none" -eq 0 ] && return 0
        return 1
    fi

    _carrier="$(local_addr_carrier 4)"
    if [ "$_carrier" = "ipset" ] || [ "$force_v4" -eq 1 ]; then
        need_v4=1
    elif [ "$_carrier" = "none" ] && [ "$force_v4" -eq 0 ]; then
        log Warn "Local address bypass unavailable for IPv4 (no addrtype module and no ipset support) — local->local IPv4 traffic will still be proxied"
    fi
    if [ "$PROXY_IPV6" -eq 1 ]; then
        _carrier="$(local_addr_carrier 6)"
        if [ "$_carrier" = "ipset" ] || [ "$force_v6" -eq 1 ]; then
            need_v6=1
        elif [ "$_carrier" = "none" ] && [ "$force_v6" -eq 0 ]; then
            log Warn "Local address bypass unavailable for IPv6 (no addrtype on this stack and no ipset) — local->local IPv6 traffic will still be proxied"
        fi
    fi

    [ "$need_v4" -eq 0 ] && [ "$need_v6" -eq 0 ] && return 0

    if [ "$need_v4" -eq 1 ]; then
        log Debug "[EXEC] ipset destroy localaddr"
        log Debug "[EXEC] ipset create localaddr hash:ip family inet hashsize 64 maxelem 128"
        if [ "$DRY_RUN" -eq 0 ]; then
            ipset destroy localaddr 2> /dev/null || true
            ipset create localaddr hash:ip family inet hashsize 64 maxelem 128 || {
                log Error "Failed to create ipset 'localaddr'"
                return 1
            }
        fi
    fi
    if [ "$need_v6" -eq 1 ]; then
        log Debug "[EXEC] ipset destroy localaddr6"
        log Debug "[EXEC] ipset create localaddr6 hash:ip family inet6 hashsize 64 maxelem 128"
        if [ "$DRY_RUN" -eq 0 ]; then
            ipset destroy localaddr6 2> /dev/null || true
            ipset create localaddr6 hash:ip family inet6 hashsize 64 maxelem 128 || {
                log Warn "Failed to create ipset 'localaddr6'"
                need_v6=0
            }
        fi
    fi

    LOCAL_ADDR_FAMILIES=""
    [ "$need_v4" -eq 1 ] && LOCAL_ADDR_FAMILIES="${LOCAL_ADDR_FAMILIES}4"
    [ "$need_v6" -eq 1 ] && LOCAL_ADDR_FAMILIES="${LOCAL_ADDR_FAMILIES}6"
    log Info "Local address ipset families: ${LOCAL_ADDR_FAMILIES:-none}"

    if ! sync_local_ipset_both; then
        return 1
    fi

    if ! start_local_addr_watcher; then
        log Warn "Local address sets created but the watcher did not start — sets are frozen at the initial snapshot, address changes will NOT be followed"
        return 1
    fi
    log Info "Local address bypass sets ready"
    return 0
}

stop_local_addr_watcher() {
    local pf old_pid
    pf="$(local_addr_pidfile)"
    [ -f "$pf" ] || return 0
    old_pid="$(cat "$pf" 2> /dev/null)"
    if is_positive_integer "$old_pid"; then
        log Debug "[EXEC] kill $old_pid"
        [ "$DRY_RUN" -eq 0 ] && kill "$old_pid" 2> /dev/null
    fi
    rm -f "$pf"
}

cleanup_local_addr() {
    # 不按 BYPASS_LOCAL_ADDRS 提前返回：集合和后台进程是**上一次**启动留下的，
    # 这一轮的配置值不能决定要不要清理它们。典型漏网场景是 runtime_tproxy.conf
    # 丢失（半路崩掉、CONFIG_DIR 不一致），此时回落到当前配置，用户恰好把开关关
    # 了 —— 跳过清理就会把一个一直刷日志的孤儿 watcher 和两个 ipset 留在那儿。
    # 反过来销毁不存在的集合是无害空操作，所以无条件清理总是对的。
    if [ "$BYPASS_LOCAL_ADDRS" -ne 1 ]; then
        log Debug "Local address bypass is off in the current config; still cleaning up leftovers from a previous run"
    fi
    stop_local_addr_watcher
    log Debug "[EXEC] ipset destroy localaddr"
    log Debug "[EXEC] ipset destroy localaddr6"
    if [ "$DRY_RUN" -eq 0 ]; then
        ipset destroy localaddr 2> /dev/null || true
        ipset destroy localaddr6 2> /dev/null || true
        # 事件模式的 FIFO 由后台进程自己清；但它被 SIGTERM 收掉时清理不会跑。
        # 按 pid 命名所以互不冲突，这里在 stop 时顺手扫掉，免得 $TMPDIR 里慢慢堆。
        rm -f "$TMPDIR"/localaddr_monitor.*.fifo 2> /dev/null
    fi
    log Info "Local address sets destroyed"
}

detect_proxy_mode() {
    USE_TPROXY=0
    case "$PROXY_MODE" in
        0)
            if check_tproxy_support; then
                USE_TPROXY=1
                log Info "Kernel supports TPROXY, using TPROXY mode (auto)"
            else
                log Warn "Kernel does not support TPROXY, falling back to REDIRECT mode (auto)"
            fi
            ;;
        1)
            if check_tproxy_support; then
                USE_TPROXY=1
                log Info "Using TPROXY mode (forced by configuration)"
            else
                log Error "TPROXY mode forced but kernel does not support TPROXY"
                exit 1
            fi
            ;;
        2)
            log Info "Using REDIRECT mode (forced by configuration)"
            ;;
    esac
}

start_proxy() {
    log Info "Starting proxy setup..."
    if [ "$BYPASS_CN_IP" -eq 1 ]; then
        if [ "$HAS_IPSET" -eq 0 ] || [ "$HAS_XT_SET" -eq 0 ]; then
            log Error "Kernel does not support ipset (CONFIG_IP_SET, CONFIG_NETFILTER_XT_SET). Cannot bypass CN IPs"
            BYPASS_CN_IP=0
        else
            download_cn_ip_list || log Warn "Failed to download CN IP list, continuing without it"
            if ! setup_cn_ipset; then
                log Error "Failed to setup ipset, CN bypass disabled"
                BYPASS_CN_IP=0
            fi
        fi
    fi

    # 本机地址旁路必须先于建链：BYPASS_IP 里的 `-m set --match-set localaddr` 规则
    # 在集合不存在时加不上，而集合在这里才建。失败只告警 —— addrtype 那对规则可能已经够了。
    setup_local_addr_sets || log Warn "Local address bypass not active (falling back to whatever the kernel offers)"

    if [ "$USE_TPROXY" -eq 1 ]; then
        setup_tproxy_chain4
        setup_routing4
        if [ "$PROXY_IPV6" -eq 1 ]; then
            setup_tproxy_chain6
            setup_routing6
        fi
    else
        setup_redirect_chain4
        if [ "$PROXY_IPV6" -eq 1 ]; then
            setup_redirect_chain6
        fi
    fi
    log Info "Proxy setup completed"
    block_loopback_traffic enable
    [ "$BLOCK_QUIC" -eq 1 ] && block_quic enable
    if [ "$PROXY_IPV6" -eq -1 ]; then
        manage_ipv6 disable || log Warn "Failed to disable IPv6 stack"
    fi
    save_runtime_config
}

stop_proxy() {
    log Info "Stopping proxy..."
    if load_runtime_config; then
        log Info "Using runtime config for cleanup"
    else
        log Warn "Using current config for cleanup (runtime config unavailable)"
    fi
    if [ "$USE_TPROXY" -eq 1 ]; then
        log Info "Cleaning up TPROXY chains"
        cleanup_tproxy_chain4
        cleanup_routing4
        if [ "$PROXY_IPV6" -eq 1 ]; then
            cleanup_tproxy_chain6
            cleanup_routing6
        fi
    else
        log Info "Cleaning up REDIRECT chains"
        cleanup_redirect_chain4
        if [ "$PROXY_IPV6" -eq 1 ]; then
            cleanup_redirect_chain6
        fi
    fi
    cleanup_ipset
    cleanup_local_addr
    log Info "Proxy stopped"
    block_loopback_traffic disable
    block_quic disable
    if [ "$PROXY_IPV6" -eq -1 ]; then
        manage_ipv6 restore || log Warn "Failed to restore IPv6 settings"
    fi
    [ "$DRY_RUN" -eq 1 ] || rm -f "$CONFIG_DIR/runtime_tproxy.conf" 2> /dev/null
}

# This rule blocks local access to tproxy-port to prevent traffic loopback.
block_loopback_traffic() {
    case "$1" in
        enable)
            ip6tables -t filter -A OUTPUT -d ::1 -p tcp -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -m tcp --dport "$PROXY_TCP_PORT" -j REJECT
            iptables -t filter -A OUTPUT -d 127.0.0.1 -p tcp -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -m tcp --dport "$PROXY_TCP_PORT" -j REJECT
            ;;
        disable)
            ip6tables -t filter -D OUTPUT -d ::1 -p tcp -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -m tcp --dport "$PROXY_TCP_PORT" -j REJECT 2>/dev/null || true
            iptables -t filter -D OUTPUT -d 127.0.0.1 -p tcp -m owner --uid-owner "$CORE_USER" --gid-owner "$CORE_GROUP" -m tcp --dport "$PROXY_TCP_PORT" -j REJECT 2>/dev/null || true
            ;;
    esac
}

block_quic() {
    case "$1" in
        enable)
            iptables -N BLOCK_QUIC 2>/dev/null || true
            iptables -F BLOCK_QUIC
            if [ "$BYPASS_CN_IP" -eq 1 ]; then
                iptables -A BLOCK_QUIC -p udp --dport 443 -m set ! --match-set cnip dst -j REJECT
            else
                iptables -A BLOCK_QUIC -p udp --dport 443 -j REJECT
            fi
            iptables -I INPUT -j BLOCK_QUIC
            iptables -I FORWARD -j BLOCK_QUIC
            iptables -I OUTPUT -j BLOCK_QUIC

            if [ "$PROXY_IPV6" -eq 1 ]; then
                ip6tables -N BLOCK_QUIC6 2>/dev/null || true
                ip6tables -F BLOCK_QUIC6
                if [ "$BYPASS_CN_IP" -eq 1 ]; then
                    ip6tables -A BLOCK_QUIC6 -p udp --dport 443 -m set ! --match-set cnip6 dst -j REJECT
                else
                    ip6tables -A BLOCK_QUIC6 -p udp --dport 443 -j REJECT
                fi
                ip6tables -I INPUT -j BLOCK_QUIC6
                ip6tables -I FORWARD -j BLOCK_QUIC6
                ip6tables -I OUTPUT -j BLOCK_QUIC6
            fi
            log Info "QUIC traffic blocked"
            ;;
        disable)
            local chain
            for chain in INPUT FORWARD OUTPUT; do
                iptables -D "$chain" -j BLOCK_QUIC 2>/dev/null || true
                ip6tables -D "$chain" -j BLOCK_QUIC6 2>/dev/null || true
            done
            iptables -F BLOCK_QUIC 2>/dev/null || true
            iptables -X BLOCK_QUIC 2>/dev/null || true
            ip6tables -F BLOCK_QUIC6 2>/dev/null || true
            ip6tables -X BLOCK_QUIC6 2>/dev/null || true
            log Info "QUIC traffic blocking disabled"
            ;;
    esac
}

manage_ipv6() {
    local action="$1"
    local ipv6_backup_file="$CONFIG_DIR/ipv6_backup.conf"

    case "$action" in
        backup | disable | restore) ;;
        *)
            log Error "Invalid action for manage_ipv6: $action (must be backup, disable, or restore)"
            return 1
            ;;
    esac

    if [ "$DRY_RUN" -eq 1 ]; then
        log Debug "Would $action IPv6 settings"
        return 0
    fi

    if [ "$action" = "backup" ] || [ "$action" = "disable" ]; then
        log Info "Backing up current IPv6 settings to $ipv6_backup_file"

        {
            echo "# IPv6 settings backup (generated at $(date))"
            echo "accept_ra=$(cat /proc/sys/net/ipv6/conf/all/accept_ra 2> /dev/null || echo unknown)"
            echo "autoconf=$(cat /proc/sys/net/ipv6/conf/all/autoconf 2> /dev/null || echo unknown)"
            echo "forwarding=$(cat /proc/sys/net/ipv6/conf/all/forwarding 2> /dev/null || echo unknown)"

            for iface in /proc/sys/net/ipv6/conf/*; do
                if [ -f "$iface/disable_ipv6" ]; then
                    iface_name=$(basename "$iface")
                    current=$(cat "$iface/disable_ipv6" 2> /dev/null || echo unknown)
                    echo "$iface_name=$current"
                fi
            done
        } > "$ipv6_backup_file" || {
            log Warn "Failed to backup IPv6 settings"
            return 1
        }

        log Debug "IPv6 backup completed"
    fi

    if [ "$action" = "disable" ]; then
        log Info "Force disabling IPv6 stack (disable_ipv6=1)"

        echo 0 > /proc/sys/net/ipv6/conf/all/accept_ra 2> /dev/null || true
        echo 0 > /proc/sys/net/ipv6/conf/all/autoconf 2> /dev/null || true
        echo 0 > /proc/sys/net/ipv6/conf/all/forwarding 2> /dev/null || true

        for iface in /proc/sys/net/ipv6/conf/*; do
            if [ -f "$iface/disable_ipv6" ]; then
                echo 1 > "$iface/disable_ipv6" 2> /dev/null || true
            fi
        done

        log Info "IPv6 stack fully disabled"
    fi

    if [ "$action" = "restore" ]; then
        if [ ! -f "$ipv6_backup_file" ]; then
            log Warn "No IPv6 backup file found: $ipv6_backup_file, skip restore"
            return 0
        fi

        log Info "Restoring IPv6 settings from $ipv6_backup_file"

        while IFS='=' read -r key value; do
            # Skip comments and empty lines
            case "$key" in
                \#* | "") continue ;;
            esac

            case "$key" in
                accept_ra)
                    echo "$value" > /proc/sys/net/ipv6/conf/all/accept_ra 2> /dev/null || true
                    ;;
                autoconf)
                    echo "$value" > /proc/sys/net/ipv6/conf/all/autoconf 2> /dev/null || true
                    ;;
                forwarding)
                    echo "$value" > /proc/sys/net/ipv6/conf/all/forwarding 2> /dev/null || true
                    ;;
                *)
                    if [ -f "/proc/sys/net/ipv6/conf/$key/disable_ipv6" ]; then
                        echo "$value" > "/proc/sys/net/ipv6/conf/$key/disable_ipv6" 2> /dev/null || true
                    fi
                    ;;
            esac
        done < "$ipv6_backup_file"

        rm -f "$ipv6_backup_file" 2> /dev/null
        log Info "IPv6 settings restored"
    fi

    return 0
}

is_func() {
    command -v "$1" > /dev/null 2>&1
}

call_func() {
    local func="$1"
    shift
    if is_func "$func"; then
        log Info "Calling user hook: $func"
        "$func" "$@"
    else
        log Debug "No user hook defined: $func"
    fi
}

show_usage() {
    local script_name
    script_name=$(basename "$0")

    cat << EOF
Usage: $script_name {start|stop|restart} [options]

This script sets up / cleans up transparent proxy (TPROXY or REDIRECT) rules
for TCP/UDP traffic redirection, DNS hijacking, per-app proxy, CN IP bypass, etc.

Commands:
  start     Apply proxy rules, routing tables, ipset, sysctl changes
  stop      Remove all added rules, routes, ipset sets, restore sysctl
  restart   Equivalent to stop → short delay → start

Options:
  -v, --version              Show version number and exit

  -d DIR, --dir DIR
      Specify the base configuration directory.
      Default: the directory where this script is located.
      
      Files that may be read from or written to in this directory:
      • tproxy_rules.conf    (optional) user configuration overrides
      • runtime_tproxy.conf  (generated/used during runtime for cleanup)
      • cn.zone              (China IPv4 CIDR list, auto-downloaded if missing/old)
      • cn_ipv6.zone         (China IPv6 CIDR list, auto-downloaded if IPv6 enabled)
      • tmp/                 (temporary subdirectory for mktemp files, downloads, etc.)

      Requirements:
      - The directory must exist and be writable by the script (root usually).
      - If using custom location (e.g. /data/adb/modules/xxx), ensure it has
        read/write/execute permissions for root, and is persistent across reboots
        if you want downloaded lists and runtime config to survive.

  --dry-run
      Simulate all operations without actually modifying:
      • iptables / ip6tables rules
      • ip rules / routes
      • ipset sets
      • sysctl settings (/proc/sys/...)
      • file system writes (downloads, temp files, runtime config)
      Ideal for previewing what changes would be made.

  --verbose
      Increase logging detail:
      • With --dry-run: shows ALL log levels (Info, Warn, Error, Debug, [EXEC])
      • Without --dry-run: shows normal output + Debug-level messages
      • Without this flag: shows only Info, Warn, Error (quiet mode)

  -h, --help
      Show this help message and exit

Examples:
  $script_name start --dry-run
      # Preview changes without applying anything

  $script_name start --dry-run --verbose
      # Very detailed simulation (shows every command that would run)

  $script_name start -d /data/adb/myproxy
      # Use custom config directory

  $script_name restart --verbose
      # Restart with extra debug output

  $script_name stop -d /sdcard/myproxy
      # Stop using a specific config directory

Note:
  • Almost all operations require root privileges.
  • Some features (TPROXY, ipset, owner matching, etc.) depend on kernel support.
EOF
}

parse_args() {
    MAIN_CMD=""
    VERBOSE=0
    while [ $# -gt 0 ]; do
        case "$1" in
            start | stop | restart)
                if [ -n "$MAIN_CMD" ]; then
                    log Error "Multiple commands specified."
                    exit 1
                fi
                MAIN_CMD="$1"
                ;;
            --dry-run)
                DRY_RUN=1
                ;;
            --verbose)
                VERBOSE=1
                ;;
            -v | --version)
                echo "$SCRIPT_VERSION"
                exit 0
                ;;
            --local-addr-watcher)
                # 内部标记，不是用户参数：start_local_addr_watcher 用它重新执行本脚本，
                # 后台同步本机地址到 localaddr 集合。故意不出现在 help 里。
                LOCAL_ADDR_WATCHER=1
                ;;
            -d | --dir)
                shift
                if [ $# -eq 0 ] || [ -z "$1" ]; then
                    log Error "Option -d/--dir requires a directory argument"
                    show_usage
                    exit 1
                fi
                if [ ! -d "$1" ]; then
                    log Error "Directory does not exist or is not a directory: $1"
                    show_usage
                    exit 1
                fi
                CONFIG_DIR="$(cd "$1" 2> /dev/null && pwd -P)" || {
                    log Error "Failed to resolve absolute path for directory: $1"
                    exit 1
                }
                ;;
            -h | --help)
                show_usage
                exit 0
                ;;
            *)
                log Error "Invalid argument: $1"
                show_usage
                exit 1
                ;;
        esac
        shift
    done
    if [ -z "$MAIN_CMD" ] && [ "$LOCAL_ADDR_WATCHER" != "1" ]; then
        log Error "No command specified"
        show_usage
        exit 1
    fi
}

main() {
    local script_name
    script_name=$(basename "$0")
    log Debug "Starting ${script_name} ${SCRIPT_VERSION}"

    load_config

    if [ "$LOCAL_ADDR_WATCHER" = "1" ]; then
        # 后台同步进程：只需要配置和 ipset/ip，不参与建链，也不走 root / 依赖检查。
        init_tmpdir
        local_addr_watch_loop
        exit 0
    fi

    if [ "$DRY_RUN" -eq 1 ]; then
        if [ "$VERBOSE" -eq 1 ]; then
            log Info "Dry-run mode + verbose: showing ALL logs"
        else
            log Info "Dry-run mode: only showing commands that would be executed"
        fi
    elif [ "$VERBOSE" -eq 1 ]; then
        log Info "Verbose mode: showing debug information"
    fi

    if ! validate_config; then
        log Error "Configuration validation failed"
        exit 1
    fi

    check_root
    check_dependencies
    setup_busybox

    init_tmpdir
    init_kernel_config_cache
    init_feature_flags

    detect_proxy_mode

    case "$MAIN_CMD" in
        start)
            call_func pre_start_hook
            start_proxy
            ;;
        stop)
            stop_proxy
            call_func post_stop_hook
            ;;
        restart)
            log Info "Restarting proxy..."
            stop_proxy
            call_func post_stop_hook
            sleep 2
            call_func pre_start_hook
            start_proxy
            log Info "Proxy restarted"
            ;;
        *)
            log Error "Invalid command: $MAIN_CMD"
            show_usage
            exit 1
            ;;
    esac
}

# Pre-initialize variables for set -u safety
DRY_RUN=0
VERBOSE=0
CONFIG_DIR=""
MAIN_CMD=""
LOCAL_ADDR_WATCHER=0
# 哪些地址族真的建了 ipset（"4" / "6" 的子集）。watcher 据此决定同步哪几个集合，
# 而不是照搬 PROXY_IPV6 —— 后者表示「v6 代理链要不要建」，跟「localaddr6 在不在」
# 不是一回事（v6 走 addrtype 时集合没建，同步它只会每周期刷一条失败）。
# 用 ${VAR:-4} 而不是硬赋值：watcher 是重新执行本脚本的子进程，这个值必须靠启动
# 方用环境变量带进来（见 start_local_addr_watcher），硬赋值会把传进来的值盖掉。
LOCAL_ADDR_FAMILIES="${LOCAL_ADDR_FAMILIES:-4}"
USE_TPROXY=0
HAS_TPROXY=0
HAS_CONNTRACK=0
HAS_OWNER=0
HAS_MARK_MT=0
HAS_MARK_TG=0
HAS_SOCKET=0
HAS_ADDRTYPE=0
HAS_MAC=0
HAS_IPSET=0
HAS_XT_SET=0
HAS_NAT6=0
HAS_REDIRECT6=0
HAS_IP6TABLES=0

parse_args "$@"

main
