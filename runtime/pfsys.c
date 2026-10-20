// libpfsys.so — preloaded into glibc programs that trip Android's app seccomp filter.
// Android kills disallowed syscalls with SIGSYS (SECCOMP_RET_TRAP). We catch it and make the
// call return -ENOSYS instead, so glibc/Python take their normal fallback paths.
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>

static volatile int logged = 0;

static void put_num(char *b, int *n, long v) {
    char t[24]; int k = 0;
    if (v < 0) { b[(*n)++] = '-'; v = -v; }
    do { t[k++] = '0' + (v % 10); v /= 10; } while (v && k < 23);
    while (k) b[(*n)++] = t[--k];
}

static void on_sigsys(int sig, siginfo_t *si, void *ctx) {
    (void)sig;
    ucontext_t *uc = (ucontext_t *)ctx;
    uc->uc_mcontext.regs[0] = (unsigned long)(-ENOSYS);
    if (logged < 8) {
        logged++;
        char b[96]; int n = 0;
        const char *m = "[pinforge] blocked syscall ";
        for (const char *p = m; *p; p++) b[n++] = *p;
        put_num(b, &n, si->si_syscall);
        const char *e = " -> ENOSYS\n";
        for (const char *p = e; *p; p++) b[n++] = *p;
        (void)!write(2, b, n);
    }
}

__attribute__((constructor)) static void pf_init(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = on_sigsys;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    sigaction(SIGSYS, &sa, 0);
}
