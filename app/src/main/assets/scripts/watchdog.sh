#!/system/bin/sh

# 接收第一个参数：主应用的进程 ID (PID)
APP_PID=$1

# 接收第二个参数：应用包名。
# 如果外部调用没有传递 $2，则自动使用默认值 "app.fjj.stun"
PACKAGE_NAME=${2:-"app.fjj.stun"}

while true; do
    # 检查 PID 是否还在
    if ! kill -0 "$APP_PID" 2>/dev/null; then
        # 如果 PID 消失，执行清理
        /data/user/0/"$PACKAGE_NAME"/cache/tproxy.sh stop
        exit 0
    fi
    sleep 2
done