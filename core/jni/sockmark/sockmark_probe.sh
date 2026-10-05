#!/system/bin/sh
# ============================================================================
# sockmark 真机探针 —— B 方案（root 侧 SO_MARK）的落地前置验证
# ============================================================================
#
# 用途：在把 B 方案接进主链之前，先证明这台设备上"root 进程能否给 App 进程的
# socket 设 SO_MARK"这件事成立。四个环节任何一环不通，B 方案都跑不起来，
# 而症状都表现为"SSH 连不上"，很难定位。
#
# 用法（需要 root shell 或 adb shell su）：
#     sh sockmark_probe.sh [app_pid|包名] [mark]
#
# app_pid 可省略：省略时自动用包名反查（默认 app.fjj.stun）。真机验证时通常
# 就是忘了 pid —— 让脚本自己去 /proc 扫，比手动 `adb shell pidof` 顺手。
#
# 它做四件事，每件都独立报告：
#   1. 内核是否支持 pidfd（pidfd_open / pidfd_getfd，Linux 5.6+）
#   2. root 能否读 /proc/<pid>/fd/<n>（部分 ROM 限制非 root 进程，但 root 应可）
#   3. sockmark 二进制能否启动、协议是否正常（PING / MARK / 未知命令）
#   4. 对着一个**真实的 socket fd** 设 mark，并用 SO_MARK 回读验证真的写进去了
#
# 预期：全部 PASS。任一 FAIL 就不要接 B 方案，退回按 uid 放行的做法。
#
# ⚠️ 本脚本只做验证，不修改任何系统状态（不改 iptables、不建隧道）。
# ============================================================================

MARK=${2:-0x5354}
PKG=app.fjj.stun
PROBE_DIR=/data/local/tmp
SOCKMARK_BIN=$PROBE_DIR/sockmark

PASS=0
FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
bad()  { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
info() { echo "  [INFO] $1"; }

# ── pid 解析：数字直接用；否则当包名反查 ──────────────────────────────────────
# 找不到时在 /proc 下逐个读 cmdline 兜底（pidof 在部分 ROM 上不可用）。
resolve_pid() {
    case "$1" in
        ''|*[!0-9]*) ;;
        *) echo "$1"; return 0 ;;
    esac
    local pid
    pid=$(pidof "$1" 2>/dev/null | awk '{print $1}')
    [ -n "$pid" ] && { echo "$pid"; return 0; }
    for d in /proc/[0-9]*; do
        if [ "$(cat "$d/cmdline" 2>/dev/null | tr '\0' ' ')" = "$1 " ] ||
           grep -qs "$1" "$d/cmdline" 2>/dev/null; then
            echo "${d#/proc/}"; return 0
        fi
    done
    return 1
}

APP_PID=$(resolve_pid "${1:-$PKG}")

echo "=============================================="
echo " sockmark probe  app_pid=$APP_PID mark=$MARK"
echo "=============================================="

# ---------------------------------------------------------------- 前置检查
if [ -z "$APP_PID" ]; then
    echo "  [FAIL] 找不到 Stun 进程。请先启动 App，或显式传 pid："
    echo "        adb shell pidof $PKG"
    echo "用法: sh sockmark_probe.sh [app_pid|包名] [mark]"
    exit 2
fi
if [ ! -d "/proc/$APP_PID" ]; then
    echo "  [FAIL] 进程 $APP_PID 不存在（先启动 Stun 再跑本脚本）"
    exit 2
fi
if [ "$(id -u)" != "0" ]; then
    echo "  [FAIL] 需要 root（当前 uid=$(id -u)）"
    exit 2
fi
info "uid=0，进程存活"

# ------------------------------------------------- 1. 内核 pidfd 支持探测
echo ""
echo "[1/4] 内核 pidfd 支持"
KERNEL=$(uname -r)
info "kernel=$KERNEL"
# pidfd_open=434, pidfd_getfd=438（arm64）。x86_32 上号码不同，
# 但只要内核 >= 5.6 就有；这里用 5.6 的版本号做粗判，真实能力由第 4 步实测。
MAJOR=$(echo "$KERNEL" | cut -d. -f1)
MINOR=$(echo "$KERNEL" | cut -d. -f2)
if [ "$MAJOR" -gt 5 ] || { [ "$MAJOR" -eq 5 ] && [ "$MINOR" -ge 6 ]; }; then
    ok "内核版本 >= 5.6，理论上支持 pidfd_open/getfd"
else
    bad "内核 $KERNEL < 5.6，pidfd_getfd 不可用 —— B 方案无法实现"
fi

# --------------------------------------- 2. root 能否读 /proc/<pid>/fd/<n>
echo ""
echo "[2/4] root 读取目标进程 fd 目录"
if ls "/proc/$APP_PID/fd" >/dev/null 2>&1; then
    FD_COUNT=$(ls "/proc/$APP_PID/fd" 2>/dev/null | wc -l)
    ok "可读 /proc/$APP_PID/fd（$FD_COUNT 个 fd）"
else
    bad "无法读 /proc/$APP_PID/fd —— 该 ROM 限制了 fd 目录访问，B 方案不可行"
    echo ""
    echo ">>> 结论：不可行，请改用按目标地址 bypass 的方案。"
    exit 1
fi

# ---------------------------------------------------- 3. sockmark 二进制协议
echo ""
echo "[3/4] sockmark 二进制与协议"
if [ ! -x "$SOCKMARK_BIN" ]; then
    # 先自己找：App 部署 sockmark 的目标是 cacheDir（AppBootstrap 那条链），
    # 所以直接从 /proc/<pid>/fd 之外按已知路径捞。手写 cacheDir 路径那一步
    # 是整个验证流程里最容易出错、也最没必要手写的一步。
    for cand in \
        "/data/data/$PKG/cache/sockmark" \
        "/data/user/0/$PKG/cache/sockmark"
    do
        if [ -f "$cand" ]; then
            cp -f "$cand" "$SOCKMARK_BIN" 2>/dev/null && chmod +x "$SOCKMARK_BIN" 2>/dev/null
            [ -x "$SOCKMARK_BIN" ] && { info "已从 $cand 自动取到二进制"; break; }
        fi
    done
fi
if [ ! -x "$SOCKMARK_BIN" ]; then
    bad "找不到可执行的 $SOCKMARK_BIN"
    echo "        App 会把它部署到 cacheDir。若上面没捞到（多用户/工作资料场景路径不同），"
    echo "        手动执行："
    echo "        adb shell \"su -c 'cp <cacheDir>/sockmark $SOCKMARK_BIN && chmod +x $SOCKMARK_BIN'\""
    exit 1
fi

# 用 FIFO 造一条"像样的"请求-响应通道。
FIFO=$PROBE_DIR/sockmark_probe_fifo
rm -f "$FIFO"
mkfifo "$FIFO"

# helper 读 FIFO 写 stdout；这里把 stdout 收进临时文件逐条读回。
OUT=$PROBE_DIR/sockmark_probe_out
rm -f "$OUT"

"$SOCKMARK_BIN" "$APP_PID" < "$FIFO" > "$OUT" 2>/dev/null &
HELPER_PID=$!
sleep 1

if kill -0 "$HELPER_PID" 2>/dev/null; then
    ok "helper 启动成功（pid=$HELPER_PID）"
else
    bad "helper 启动即退出"
    rm -f "$FIFO" "$OUT"
    exit 1
fi

# FIFO 需持续有写端，否则 helper 的 fgets 读到 EOF 会退出。用 exec 保持写端打开。
exec 9>"$FIFO"

# PING
echo "PING" >&9
sleep 1
if grep -q "PONG" "$OUT" 2>/dev/null; then
    ok "PING -> $(grep PONG "$OUT" | head -1)"
else
    bad "PING 无响应（helper 协议异常）"
fi

# 未知命令：应回 ERR 而不是静默
echo "BOGUSCMD" >&9
sleep 1
if grep -q "ERR" "$OUT" 2>/dev/null; then
    ok "未知命令正确回 ERR"
else
    bad "未知命令未回 ERR —— 协议可能不匹配，App 侧会误判"
fi

# ------------------------------------------------ 4. 对真实 socket 设 mark
echo ""
echo "[4/4] 对真实 socket fd 设 SO_MARK 并回读验证"

# 找一个属于目标进程的 socket fd。
# 优先挑一个 socket 类型（/proc/<pid>/fd 的 readlink 形如 socket:[...]）。
SOCK_FD=""
for f in /proc/$APP_PID/fd/*; do
    tgt=$(readlink "$f" 2>/dev/null)
    case "$tgt" in
        socket:*) SOCK_FD=$(basename "$f"); SOCK_TGT=$tgt; break ;;
    esac
done

if [ -z "$SOCK_FD" ]; then
    bad "目标进程当前没有 socket fd（先让它建立连接再跑，或直接看结论：跳过实测）"
    info "协议层已验证通过；这一项需要 App 正在连接时才测得到"
else
    info "选中 fd=$SOCK_FD ($SOCK_TGT)"
    MARK_DEC=$((MARK))

    echo "MARK $SOCK_FD $MARK_DEC" >&9
    sleep 1
    if tail -1 "$OUT" | grep -q "^OK"; then
        ok "MARK 请求被接受"
    else
        bad "MARK 被拒：$(tail -1 "$OUT")"
        info "EPERM 通常意味着内核 <5.17 且 helper 没有 CAP_NET_ADMIN"
    fi

    # 决定性验证：GET 回读 mark 值。
    # getsockopt(SO_MARK) 不需要特权，所以这一步能独立证明 mark 真的落在那个 socket 上 ——
    # 比"helper 说 OK"有说服力得多（后者可能只是 helper 自己没报错）。
    echo "GET $SOCK_FD" >&9
    sleep 1
    GOT_LINE=$(tail -1 "$OUT")
    GOT_MARK=$(echo "$GOT_LINE" | grep "^MARK " | awk '{print $2}')
    if [ -n "$GOT_MARK" ]; then
        if [ "$GOT_MARK" = "$MARK_DEC" ]; then
            ok "回读确认 mark=$GOT_MARK（与写入值一致）"
        elif [ "$GOT_MARK" = "0" ]; then
            bad "回读到 mark=0 —— helper 说 OK 但值没写进去（多半是设在了别的 netns 的同名 fd 上）"
        else
            bad "回读到 mark=$GOT_MARK，与期望的 $MARK_DEC 不符"
        fi
    else
        bad "GET 无有效响应：$GOT_LINE"
    fi
fi

# ------------------------------------------------------------------ 清理
exec 9>&-
kill "$HELPER_PID" 2>/dev/null
rm -f "$FIFO" "$OUT"

echo ""
echo "=============================================="
echo " 结果: $PASS PASS / $FAIL FAIL"
echo "=============================================="
if [ "$FAIL" -eq 0 ]; then
    echo ">>> 全部通过，B 方案在这台设备上可行。"
    exit 0
else
    echo ">>> 有失败项。B 方案在这台设备上**不可行或不可靠**，"
    echo ">>> 请改用按目标地址（节点 IP）bypass 的方案。"
    exit 1
fi
