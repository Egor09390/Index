/*
 * KernelSU Samsung v2.2 authorized root-command child launcher.
 * The kernel verifies this APK UID against the KernelSU allowlist.
 * No root is granted unless the user already authorized the app.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

#ifndef SYS_SECCOMP
#define SYS_SECCOMP 1
#endif

#define KSU_INSTALL_MAGIC1 0xDEADBEEFU
#define KSU_INSTALL_MAGIC2 0xCAFEBABEU
#define KSU_IOCTL_GRANT_ROOT _IOC(_IOC_NONE, 'K', 1, 0)

static volatile sig_atomic_t install_blocked;

static void on_sigsys(int signo, siginfo_t *info, void *context) {
    if (signo == SIGSYS && info && context && info->si_code == SYS_SECCOMP) {
        install_blocked = 1;
#if defined(__aarch64__)
        ((ucontext_t *)context)->uc_mcontext.regs[0] = (uint64_t)(-(int64_t)EPERM);
        return;
#endif
    }
    _exit(126);
}

static int grant_if_allowed(void) {
    if (geteuid() == 0) return 0;
    struct sigaction action = {0};
    action.sa_sigaction = on_sigsys;
    action.sa_flags = SA_SIGINFO;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGSYS, &action, NULL) != 0) {
        perror("KernelSU sigaction");
        return -1;
    }

    int driver = -1;
    errno = 0;
    (void)syscall(__NR_reboot, KSU_INSTALL_MAGIC1, KSU_INSTALL_MAGIC2, 0UL, &driver);
    if (driver < 0) {
        fprintf(stderr, "KernelSU driver fd unavailable: %s%s\n",
                strerror(errno), install_blocked ? " [Android seccomp blocked]" : "");
        return -1;
    }
    if (ioctl(driver, KSU_IOCTL_GRANT_ROOT, 0) != 0) {
        int saved = errno;
        close(driver);
        fprintf(stderr, "KernelSU GRANT_ROOT denied for this app UID: %s\n", strerror(saved));
        return -1;
    }
    close(driver);
    if (geteuid() != 0) {
        fprintf(stderr, "KernelSU granted no root UID\n");
        return -1;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 3 || strcmp(argv[1], "-c") != 0) {
        fprintf(stderr, "Usage: libamnezia_ksu_root.so -c command\n");
        return 2;
    }
    if (grant_if_allowed() != 0) return 1;
    execl("/system/bin/sh", "sh", "-c", argv[2], (char *)NULL);
    perror("KernelSU exec sh");
    return 1;
}
