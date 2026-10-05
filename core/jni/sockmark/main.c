/*
 * sockmark — root 侧的 SO_MARK 代理。
 *
 * 背景
 * ----
 * App 进程（myssh 的 Go 代码所在进程）没有 CAP_NET_ADMIN，而
 * setsockopt(SO_MARK) 明确要求该 capability（Linux 5.17 起也接受 CAP_NET_RAW）。
 * 非 root 进程调用直接 EPERM，SSH 隧道根本建不起来。
 *
 * tproxy 侧本来就是 root 在跑，root 有这个 capability，所以由 root 代设。
 * App 通过 stdin 写 "fd mark" 请求，helper 设完回一行 ACK。
 *
 * 为什么不能用 open("/proc/<pid>/fd/<n>")
 * --------------------------------------
 * /proc/<pid>/fd/<n> 对 socket 而言只是一个指向 "socket:[inode]" 的符号链接，
 * 直接 open() 它在 Linux 上返回 ENXIO —— 拿不到可用的 fd。
 * 正确做法是 pidfd_open() + pidfd_getfd()（Linux 5.6+）向目标进程"借"一个 fd 副本。
 * 两者都需要对目标进程有 ptrace 权限，root 满足。
 *
 * 关键性质：SO_MARK 挂在 socket 结构体上，不是挂在 fd 上。所以对"借来的 fd 副本"
 * 设 mark，与对原 fd 设 mark 是同一件事 —— 这正是我们能隔进程操作的原因。
 *
 * netns
 * -----
 * marksocket 必须在**目标 socket 所属的 netns** 里设置，否则作用在错误的 netns 上。
 * tproxy 模式不使用 VpnService（它是 iptables 方案），App 与 root 同处默认 netns；
 * 但 VPN 模式下 App 会被 VpnService 放进独立 netns。所以这里启动时尝试切进
 * App 的 netns，失败则继续（多数情况不需要切）。
 *
 * 协议（行式，stdin → stdout）
 * --------------------------
 *   请求:  "MARK <fd> <mark>"   → 响应: "OK" | "ERR <errno> <strerror>"
 *   请求:  "PING"               → 响应: "PONG <app_pid>"
 *   EOF / 任意未知命令         → 退出进程
 */

/* Application.mk 的 APP_CFLAGS 已带 -D_GNU_SOURCE，这里用 ifndef 避免重复定义告警。 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

/* NDK 的 <sys/syscall.h> 未必暴露这两个号（旧 API level），缺了自行定义。 */
#ifndef __NR_pidfd_open
#define __NR_pidfd_open 434
#endif
#ifndef __NR_pidfd_getfd
#define __NR_pidfd_getfd 438
#endif

static pid_t g_app_pid = 0;

static int sys_pidfd_open(pid_t pid, unsigned int flags) {
    return (int)syscall(__NR_pidfd_open, pid, flags);
}

static int sys_pidfd_getfd(int pidfd, int targetfd) {
    return (int)syscall(__NR_pidfd_getfd, pidfd, targetfd, 0);
}

/*
 * 切进 App 的 netns。
 *
 * 只做一次：之后所有请求都在同一 netns 里处理。失败不致命 ——
 * tproxy 模式（无 VpnService）下 App 就在默认 netns，本来就不需要切。
 */
static void enter_app_netns(void) {
    char path[64];
    int fd;

    snprintf(path, sizeof(path), "/proc/%d/ns/net", (int)g_app_pid);
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        fprintf(stderr, "[sockmark] open %s failed: %s (continuing)\n", path, strerror(errno));
        return;
    }
    if (setns(fd, CLONE_NEWNET) != 0) {
        /* 非致命：默认 netns 场景下这一步本就不需要。 */
        fprintf(stderr, "[sockmark] setns failed: %s (continuing)\n", strerror(errno));
    } else {
        fprintf(stderr, "[sockmark] entered app netns\n");
    }
    close(fd);
}

/*
 * 给 App 进程里的某个 socket fd 打 mark。
 *
 * 返回 0 成功；失败返回 errno（供 ACK 回传，App 侧据此决定是否放弃该连接）。
 */
static int mark_socket(int target_fd, int mark) {
    int pidfd;
    int borrowed;
    int rc;

    pidfd = sys_pidfd_open(g_app_pid, 0);
    if (pidfd < 0) {
        return errno; /* 常见：EPERM（无 ptrace 权限）或 ESRCH（App 已死） */
    }

    borrowed = sys_pidfd_getfd(pidfd, target_fd);
    /* 借到的 fd 用完必须立刻关掉：否则每来一个请求就漏一个 fd。 */
    if (borrowed < 0) {
        rc = errno;
        close(pidfd);
        return rc;
    }

    rc = setsockopt(borrowed, SOL_SOCKET, SO_MARK, &mark, sizeof(mark));
    if (rc != 0) {
        rc = errno;
    } else {
        rc = 0;
    }

    close(borrowed);
    close(pidfd);
    return rc;
}

static void handle_line(char *line) {
    char verb[16];
    int fd = -1;
    int mark = 0;
    int n;

    n = sscanf(line, "%15s", verb);
    if (n < 1) {
        return; /* 空行 */
    }

    if (strcmp(verb, "PING") == 0) {
        printf("PONG %d\n", (int)g_app_pid);
        fflush(stdout);
        return;
    }

    if (strcmp(verb, "GET") == 0) {
        /*
         * 回读某个 socket 当前的 SO_MARK。
         *
         * 这条命令是给真机探针用的：getsockopt(SO_MARK) **不需要**特权，
         * 所以「helper 说 OK」可以被独立验证 —— 直接读回 mark 值，
         * 证明它真的落在那个 socket 上，而不是 helper 自说自话。
         * 响应: "MARK <value>" | "ERR <errno> <strerror>"
         */
        if (sscanf(line, "%15s %d", verb, &fd) != 2) {
            printf("ERR %d %s\n", EINVAL, "malformed GET request");
            fflush(stdout);
            return;
        }
        {
            int pidfd = sys_pidfd_open(g_app_pid, 0);
            if (pidfd < 0) {
                int e = errno;
                printf("ERR %d %s\n", e, strerror(e));
                fflush(stdout);
                return;
            }
            int borrowed = sys_pidfd_getfd(pidfd, fd);
            if (borrowed < 0) {
                int e = errno;
                close(pidfd);
                printf("ERR %d %s\n", e, strerror(e));
                fflush(stdout);
                return;
            }
            int got = 0;
            socklen_t len = sizeof(got);
            if (getsockopt(borrowed, SOL_SOCKET, SO_MARK, &got, &len) != 0) {
                int e = errno;
                close(borrowed);
                close(pidfd);
                printf("ERR %d %s\n", e, strerror(e));
                fflush(stdout);
                return;
            }
            close(borrowed);
            close(pidfd);
            printf("MARK %d\n", got);
            fflush(stdout);
        }
        return;
    }

    if (strcmp(verb, "MARK") == 0) {
        if (sscanf(line, "%15s %d %d", verb, &fd, &mark) != 3) {
            printf("ERR %d %s\n", EINVAL, "malformed MARK request");
            fflush(stdout);
            return;
        }
        if (mark == 0) {
            /* mark=0 等于"不要设"，不是"清零"。App 侧 mark 关闭时会发 0。 */
            printf("OK\n");
            fflush(stdout);
            return;
        }
        {
            int rc = mark_socket(fd, mark);
            if (rc == 0) {
                printf("OK\n");
            } else {
                printf("ERR %d %s\n", rc, strerror(rc));
            }
            fflush(stdout);
        }
        return;
    }

    /* 未知命令：让 App 侧立刻发现协议不匹配，而不是各自静默。 */
    printf("ERR %d %s\n", EINVAL, "unknown command");
    fflush(stdout);
}

int main(int argc, char **argv) {
    char line[256];

    if (argc < 2) {
        fprintf(stderr, "usage: sockmark <app_pid>\n");
        return 2;
    }

    g_app_pid = (pid_t)atoi(argv[1]);
    if (g_app_pid <= 0) {
        fprintf(stderr, "[sockmark] invalid app pid: %s\n", argv[1]);
        return 2;
    }

    /*
     * fd 传递通道本身走 stdin/stdout，但标准流是 App 通过 pipe 传进来的 ——
     * 这些 fd 属于 App 进程，在切 netns 之前先记下 raw 副本，
     * 切完仍然能用（fd 本身与 netns 无关）。
     */
    enter_app_netns();

    fprintf(stderr, "[sockmark] ready, app_pid=%d\n", (int)g_app_pid);

    while (fgets(line, sizeof(line), stdin) != NULL) {
        /* 去掉行尾换行，sscanf 更好处理。 */
        size_t len = strlen(line);
        while (len > 0 && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
            line[--len] = '\0';
        }
        handle_line(line);
    }

    fprintf(stderr, "[sockmark] stdin closed, exiting\n");
    return 0;
}
