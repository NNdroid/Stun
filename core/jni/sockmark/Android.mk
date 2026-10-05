# Copyright (C) 2026 Stun contributors
#
# sockmark — root 侧的 SO_MARK 代理。
#
# 为什么需要它：App 进程（myssh 的 Go 代码所在的进程）**没有 CAP_NET_ADMIN**，
# 而 setsockopt(SO_MARK) 明确要求该 capability（Linux 5.17 起也接受 CAP_NET_RAW）。
# 非 root 进程调用会直接 EPERM，SSH 隧道根本建不起来。
#
# 但 tproxy 侧本来就是 root 在跑（tproxy.sh / hev-socks5-tproxy / watchdog.sh），
# root 有这个 capability。所以由 root 进程代设：App 把「fd + mark」写过来，
# 这里经 /proc/<app_pid>/fd/<n> 取得 App 的 socket 句柄后设 mark。
#
# 本模块由 core/jni/Android.mk 的 `include $(call all-subdir-makefiles)` 自动纳入，
# 无需改动顶层构建文件。

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := sockmark

LOCAL_SRC_FILES := main.c

# 静态链接：helper 由 tproxy.sh 用 nohup 直接执行，不能依赖任何 .so 加载顺序。
LOCAL_STATIC_LIBRARIES :=

LOCAL_CFLAGS := -O2 -Wall -Wextra -D_GNU_SOURCE

include $(BUILD_EXECUTABLE)
