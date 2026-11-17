/*
 * Minimal root-only TUN creator for Android.
 * Passes a TUN descriptor over an abstract Unix socket (SCM_RIGHTS).
 * Does not read or handle VPN keys.
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/if_tun.h>
#include <net/if.h>
#include <stddef.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static int valid_iface(const char *s) {
    size_t n = strlen(s);
    if (!n || n >= IFNAMSIZ) return 0;
    for (size_t i = 0; i < n; ++i) {
        if (!((s[i] >= 'a' && s[i] <= 'z') ||
              (s[i] >= 'A' && s[i] <= 'Z') ||
              (s[i] >= '0' && s[i] <= '9') || s[i] == '_' || s[i] == '-')) return 0;
    }
    return 1;
}

int main(int argc, char **argv) {
    if (argc != 3 || !valid_iface(argv[1]) || strlen(argv[2]) > 90) {
        fprintf(stderr, "Usage: root-tun-fd awg0 abstract-socket\n");
        return 2;
    }
    int tun = open("/dev/tun", O_RDWR | O_CLOEXEC);
    if (tun < 0) tun = open("/dev/net/tun", O_RDWR | O_CLOEXEC);
    if (tun < 0) { perror("open tun"); return 1; }

    struct ifreq ifr = {0};
    strncpy(ifr.ifr_name, argv[1], IFNAMSIZ - 1);
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    if (ioctl(tun, TUNSETIFF, &ifr) < 0) {
        perror("TUNSETIFF"); close(tun); return 1;
    }

    int sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (sock < 0) { perror("socket"); close(tun); return 1; }
    struct sockaddr_un addr = {0};
    addr.sun_family = AF_UNIX;
    memcpy(addr.sun_path + 1, argv[2], strlen(argv[2]));
    socklen_t addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(argv[2]));
    if (connect(sock, (struct sockaddr *)&addr, addrlen) < 0) {
        perror("connect"); close(sock); close(tun); return 1;
    }

    char marker = 1;
    struct iovec iov = {.iov_base = &marker, .iov_len = 1};
    char cbuf[CMSG_SPACE(sizeof(int))] = {0};
    struct msghdr msg = {0};
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof(cbuf);
    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &tun, sizeof(int));
    if (sendmsg(sock, &msg, 0) != 1) {
        perror("sendmsg"); close(sock); close(tun); return 1;
    }
    close(sock);
    close(tun);
    return 0;
}
