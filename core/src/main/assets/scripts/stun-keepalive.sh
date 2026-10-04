#!/system/bin/sh
# Stun 保活脚本 —— 由 Stun 的「Magisk service.d 保活」开关写入 /data/adb/service.d/。
#
# 作用：开机把 Stun 拉起来（Web 控制台随之恢复监听），并在进程被杀之后自动重启，
#       这样随时都能从浏览器打开控制台去开启隧道，不用走到电视盒子跟前。
#
# ⚠️ 请不要手改这个文件，也不用手动 cp —— 下次在设置里点开关会被覆盖回去。
#    卸载同样走设置页（开关关掉即删除本文件）。
#
# 兼容性：Magisk / KernelSU / APatch 都实现了 /data/adb/service.d，且都在
#         late_start service 阶段串行执行这里的每个可执行脚本。

# ── 由 App 注入的占位符（KeepAliveManager.installServiceD 替换后写入）──
PKG="__PKG__"
COMP="__COMP__"
INTERVAL=__INTERVAL__   # 巡检间隔（秒）
BOOT_DELAY=__BOOT_DELAY__ # 开机首次拉起前的等待（秒），等 User 0 / 包管理就绪

LOG=/data/local/tmp/stun-keepalive.log
PIDFILE=/data/local/tmp/stun-keepalive.pid

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >>"$LOG" 2>/dev/null
}

alive() {
  if command -v pidof >/dev/null 2>&1; then
    [ -n "$(pidof "$PKG" 2>/dev/null)" ]
  else
    # 极端情况下没有 pidof，退回 ps 全量匹配包名
    ps -A 2>/dev/null | grep -qw "$PKG"
  fi
}

# ── 看门狗循环 ──
# 由下面的入口用 `nohup ... --watchdog &` 拉起，**不能在 service.d 阶段直接跑**：
# service.d 是串行执行的，脚本不退出会把后面所有模块的启动脚本一起卡住。
if [ "$1" = "--watchdog" ]; then
  echo $$ >"$PIDFILE" 2>/dev/null
  log "watchdog started (pid $$) interval=${INTERVAL}s"
  # 开机初期系统还没完全就绪，am start 可能被挡，先睡一会儿再第一次巡检
  sleep "$BOOT_DELAY"
  while [ "$(cat "$PIDFILE" 2>/dev/null)" = "$$" ]; do
    if alive; then
      :
    else
      log "process not running -> am start $COMP"
      am start -n "$COMP" >>"$LOG" 2>&1
    fi
    sleep "$INTERVAL"
  done
  # 只有自己还持有 pidfile 时才清理（被新实例接管的话别删人家的）
  [ "$(cat "$PIDFILE" 2>/dev/null)" = "$$" ] && rm -f "$PIDFILE"
  log "watchdog exiting (pid $$)"
  exit 0
fi

# ── 入口：Magisk 在开机时执行的就是这一段 ──
# 先清掉上一轮的看门狗（pidfile 可能因 /data/local/tmp 被清而残留成死 pid）
if [ -f "$PIDFILE" ]; then
  OLD=$(cat "$PIDFILE" 2>/dev/null)
  [ -n "$OLD" ] && kill "$OLD" 2>/dev/null
  rm -f "$PIDFILE"
fi

nohup sh "$0" --watchdog >/dev/null 2>&1 &

# service.d 必须立刻返回，后面的启动动作交给看门狗
log "boot: watchdog dispatched"
exit 0
