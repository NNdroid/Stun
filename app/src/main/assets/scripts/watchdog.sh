#!/system/bin/sh

# 1. 接收第一个参数：主应用的进程 ID (PID)
APP_PID=$1
# 2. 接收第二个参数：App 的缓存目录完整路径 (例如: /data/user/0/app.fjj.stun/cache)
CACHE_DIR=$2

# --- 前置安全校验 ---
if [ -z "$APP_PID" ] || [ -z "$CACHE_DIR" ]; then
    echo "[Watchdog] Error: Missing required arguments. PID='$APP_PID', CACHE_DIR='$CACHE_DIR'"
    exit 1
fi

echo "[Watchdog] Started. Monitoring PID: $APP_PID, Working Dir: $CACHE_DIR"

while true; do
    # 检查进程是否存在
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        echo "[Watchdog] Target PID $APP_PID disappeared. Initiating cleanup..."

        # 使用 sh 显式调用，防止因为没有 +x 权限导致的 Permission Denied
        if [ -f "$CACHE_DIR/tproxy.sh" ]; then
            sh "$CACHE_DIR/tproxy.sh" stop
            echo "[Watchdog] Cleanup finished."
        else
            echo "[Watchdog] Warning: $CACHE_DIR/tproxy.sh not found!"
        fi

        # 清理完毕，守护进程功成身退
        exit 0
    fi

    # 进阶校验（可选但推荐）：防止 PID 复用导致的误判
    # 检查 /proc/$APP_PID/cmdline 是否存在且可读。如果 App 彻底死亡，该文件会消失。
    if [ ! -r "/proc/$APP_PID/cmdline" ]; then
        echo "[Watchdog] Target PID $APP_PID exists but process seems replaced. Initiating cleanup..."
        sh "$CACHE_DIR/tproxy.sh" stop
        exit 0
    fi

    # 休眠 2 秒后进入下一轮检测
    sleep 2
done