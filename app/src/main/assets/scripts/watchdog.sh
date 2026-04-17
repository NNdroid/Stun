#!/system/bin/sh

# 1. 接收主应用的进程 ID (PID)
APP_PID=$1
# 2. 接收 App 的缓存目录完整路径
CACHE_DIR=$2
# 3. 接收 App 的包名 (可选，强烈建议传入，用于精准防 PID 复用)
PACKAGE_NAME=$3

SCRIPT_TPROXY_NAME="tproxy.sh"
BIN_HEV_SOCKS5_TPROXY_NAME="hev-socks5-tproxy"

# --- 前置安全校验 ---
if [ -z "$APP_PID" ] || [ -z "$CACHE_DIR" ]; then
    echo "[Watchdog] Error: Missing required arguments. PID='$APP_PID', CACHE_DIR='$CACHE_DIR'"
    exit 1
fi

echo "[Watchdog] Started. PID: $APP_PID, Dir: $CACHE_DIR, Pkg: ${PACKAGE_NAME:-<Not Provided>}"

# --- 核心清理逻辑 (抽取为函数) ---
cleanup_and_exit() {
    local reason=$1
    echo "[Watchdog] Triggering cleanup. Reason: $reason"

    # 1. 停止代理脚本
    if [ -f "$CACHE_DIR/$SCRIPT_TPROXY_NAME" ]; then
        sh "$CACHE_DIR/$SCRIPT_TPROXY_NAME" stop
    else
        echo "[Watchdog] Warning: $SCRIPT_TPROXY_NAME not found!"
    fi

    # 2. 杀死二进制文件 (忽略报错以保持日志干净)
    killall -9 "$BIN_HEV_SOCKS5_TPROXY_NAME" 2>/dev/null || true

    echo "[Watchdog] Cleanup finished. Exited cleanly."
    exit 0
}

# --- 守护轮询 ---
while true; do
    # 检查 1：PID 对应的进程是否彻底消失
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        cleanup_and_exit "Process $APP_PID is completely dead."
    fi

    # 检查 2：防 PID 复用 (终极校验)
    if [ -n "$PACKAGE_NAME" ]; then
        # 读取进程的启动命令行参数，去掉 NUL 字符
        CURRENT_CMD=$(cat "/proc/$APP_PID/cmdline" 2>/dev/null | tr -d '\0')

        # 检查读取到的命令行中是否包含我们的包名
        case "$CURRENT_CMD" in
            *"$PACKAGE_NAME"*)
                # 匹配成功，还是我们自己的 App，啥也不干
                ;;
            *)
                # 如果不匹配，说明系统把这个 PID 分配给了别的 App！
                cleanup_and_exit "PID $APP_PID recycled by a different process ($CURRENT_CMD)."
                ;;
        esac
    fi

    # 休眠 2 秒后进入下一轮检测
    sleep 2
done