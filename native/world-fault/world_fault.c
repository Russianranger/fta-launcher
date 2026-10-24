// SPDX-License-Identifier: MIT
// Bounded diagnostic only: no game state, memory contents or absolute addresses.
#define _GNU_SOURCE
#include <errno.h>
#include <link.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

_Static_assert(ATOMIC_INT_LOCK_FREE == 2, "Signal witness needs lock-free atomics");
struct range { uintptr_t start, end, base; unsigned image; };
static struct range ranges[256];
static unsigned range_count;
static int range_overflow;
static atomic_int captured;
static _Alignas(16) unsigned char alternate_stack[65536];

static unsigned image_id(const char* path) {
    const char* name = strrchr(path, '/');
    name = name ? name + 1 : path;
    if (!*name || !strcmp(name, "libfta_worldserver.so")) return 1;
    if (!strcmp(name, "libfta_db_libcrypto_so_3_4122f5c950.so") || !strcmp(name, "libcrypto.so.3")) return 2;
    if (!strcmp(name, "libfta_world_fault.so")) return 3;
    static const char* aliases[][2] = {
        {"libfta_authdep_libmariadb_so_a7306c9150.so", "libmariadb.so"},
        {"libfta_db_libssl_so_3_9bf626fadf.so", "libssl.so.3"},
        {"libfta_worlddep_libreadline_so_8_321f805999.so", "libreadline.so.8"},
        {"libfta_db_libz_so_1_c47748ed4b.so", "libz.so.1"},
        {"libfta_db_libc_shared_so_e09c2f45cf.so", "libc++_shared.so"},
        {"libfta_db_libandroid_support_so_739cf82951.so", "libandroid-support.so"},
        {"libfta_db_libcrypt_so_bf078ced08.so", "libcrypt.so"},
        {"libfta_db_libedit_so_3776bfd469.so", "libedit.so"},
        {"libfta_db_libncursesw_so_6_795f855f5a.so", "libncursesw.so.6"},
        {"libfta_db_libpcre2_8_so_a6bbeb410e.so", "libpcre2-8.so"}
    };
    for (unsigned i = 0; i < sizeof(aliases) / sizeof(aliases[0]); ++i)
        if (!strcmp(name, aliases[i][0]) || !strcmp(name, aliases[i][1])) return i + 4;
    if (!strcmp(name, "libc.so") || !strcmp(name, "libc.so.6")) return 100;
    if (!strcmp(name, "libdl.so")) return 101;
    if (!strcmp(name, "libm.so")) return 102;
    if (!strcmp(name, "linker64")) return 103;
    return 0;
}

static int collect(struct dl_phdr_info* info, size_t size, void* unused) {
    (void)size; (void)unused;
    unsigned image = image_id(info->dlpi_name);
    if (!image) return 0;
    for (unsigned i = 0; i < info->dlpi_phnum; ++i) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type != PT_LOAD || !(ph->p_flags & PF_X)) continue;
        if (range_count == 256) { range_overflow = 1; return 1; }
        uintptr_t start = info->dlpi_addr + ph->p_vaddr;
        if (start < info->dlpi_addr || ph->p_memsz > UINTPTR_MAX - start) { range_overflow = 1; return 1; }
        ranges[range_count++] = (struct range){start, start + ph->p_memsz, info->dlpi_addr, image};
    }
    return 0;
}

static char* literal(char* out, const char* text) { while (*text) *out++ = *text++; return out; }
static char* decimal(char* out, int value) {
    char digits[12]; unsigned used = 0;
    int64_t number = value;
    if (number < 0) { *out++ = '-'; number = -number; }
    do { digits[used++] = (char)('0' + number % 10); number /= 10; } while (number);
    while (used) *out++ = digits[--used];
    return out;
}
static char* location(char* out, uintptr_t address) {
    unsigned image = 0; uint64_t offset = 0;
    for (unsigned i = 0; i < range_count; ++i) {
        if (address >= ranges[i].start && address < ranges[i].end) {
            image = ranges[i].image; offset = address - ranges[i].base; break;
        }
    }
    out = decimal(out, (int)image); *out++ = ':';
    for (int shift = 60; shift >= 0; shift -= 4) *out++ = "0123456789abcdef"[(offset >> shift) & 15];
    return out;
}

static void fault(int number, siginfo_t* info, void* context) {
    if (!atomic_exchange_explicit(&captured, 1, memory_order_relaxed)) {
        const ucontext_t* uc = context;
        uintptr_t pc = 0, lr = 0;
#if defined(__aarch64__)
        pc = uc->uc_mcontext.pc; lr = uc->uc_mcontext.regs[30];
#elif defined(__x86_64__)
        pc = (uintptr_t)uc->uc_mcontext.gregs[REG_RIP];
#else
#error Unsupported fault witness architecture
#endif
        char line[160]; char* out = literal(line, "FTA_FAULT_V1 signal=");
        out = decimal(out, number); out = literal(out, " code="); out = decimal(out, info->si_code);
        out = literal(out, " pc="); out = location(out, pc);
        out = literal(out, " lr="); out = location(out, lr); *out++ = '\n';
        // One sub-PIPE_BUF write. No allocator, loader, unwinder, mutex or stdio in the handler.
        ssize_t result;
        do { result = write(STDERR_FILENO, line, (size_t)(out - line)); } while (result < 0 && errno == EINTR);
    }
    // SA_RESETHAND restores the default action. Delivery occurs after this blocked handler returns.
    if (syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), number) != 0) _exit(128 + number);
}

__attribute__((constructor)) static void install(void) {
    dl_iterate_phdr(collect, 0);
    if (!range_count || range_overflow) _exit(126);
    stack_t stack = {.ss_sp = alternate_stack, .ss_size = sizeof(alternate_stack)};
    if (sigaltstack(&stack, 0)) _exit(126);
    const int signals[] = {SIGSEGV, SIGBUS};
    for (unsigned i = 0; i < 2; ++i) {
        struct sigaction before, action;
        if (sigaction(signals[i], 0, &before) || before.sa_handler != SIG_DFL) _exit(126);
        memset(&action, 0, sizeof(action));
        action.sa_sigaction = fault;
        action.sa_flags = SA_SIGINFO | SA_ONSTACK | SA_RESETHAND;
        sigemptyset(&action.sa_mask);
        if (sigaction(signals[i], &action, 0)) _exit(126);
    }
    static const char ready[] = "FTA_FAULT_READY_V1\n";
    if (write(STDERR_FILENO, ready, sizeof(ready) - 1) != (ssize_t)(sizeof(ready) - 1)) _exit(126);
}
