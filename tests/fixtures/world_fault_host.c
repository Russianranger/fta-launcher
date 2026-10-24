#define _GNU_SOURCE
#include <signal.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

static _Alignas(16) char original_stack[98304];
static int prior_mode;
static void finish_prior(int number) {
    stack_t stack;
    sigset_t mask;
    if (sigaltstack(0, &stack) || stack.ss_sp != original_stack ||
        sigprocmask(SIG_SETMASK, 0, &mask) || !sigismember(&mask, SIGUSR1)) _exit(125);
    static const char message[] = "FTA_PRIOR_HANDLER_PRESERVED\n";
    if (write(2, message, sizeof(message) - 1) != sizeof(message) - 1) _exit(125);
    struct sigaction action = {.sa_handler = SIG_DFL};
    sigemptyset(&action.sa_mask);
    if (sigaction(number, &action, 0) || syscall(SYS_tgkill, getpid(), syscall(SYS_gettid), number)) _exit(125);
}
static void prior_info(int number, siginfo_t* info, void* context) {
    struct sigaction action;
    if (!info || !context || info->si_signo != number || sigaction(number, 0, &action)) _exit(125);
    if (number == SIGSEGV && info->si_code != SEGV_MAPERR) _exit(125);
    if (prior_mode == 4 ? action.sa_handler != SIG_DFL : !(action.sa_flags & SA_SIGINFO)) _exit(125);
    finish_prior(number);
}
static void prior_plain(int number) { finish_prior(number); }
static void preinit(int argc, char** argv, char** environment) {
    (void)environment;
    prior_mode = argc > 2 ? atoi(argv[2]) : 0;
    if (!prior_mode) return;
    stack_t stack = {.ss_sp = original_stack, .ss_size = sizeof(original_stack)};
    if (sigaltstack(&stack, 0)) _exit(125);
    struct sigaction action = {0};
    sigemptyset(&action.sa_mask); sigaddset(&action.sa_mask, SIGUSR1);
    action.sa_flags = SA_ONSTACK | SA_RESTART;
    if (prior_mode == 3) action.sa_handler = SIG_IGN;
    else if (prior_mode == 2) action.sa_handler = prior_plain;
    else { action.sa_sigaction = prior_info; action.sa_flags |= SA_SIGINFO; }
    if (prior_mode == 4) action.sa_flags |= SA_RESETHAND;
    if (sigaction(SIGSEGV, &action, 0) || sigaction(SIGBUS, &action, 0)) _exit(125);
}
// Like Android linker debuggerd setup, this precedes shared-library constructors.
__attribute__((section(".preinit_array"), used))
static void (*const before_preload)(int, char**, char**) = preinit;
__attribute__((noinline)) void fault_here(volatile int* address) { *address = 1; }
int main(int argc, char** argv) {
    struct rlimit limit = {0, 0};
    if (setrlimit(RLIMIT_CORE, &limit)) return 2;
    int mode = argc > 1 ? atoi(argv[1]) : 0;
    if (mode == 11) fault_here((volatile int*)0);
    else if (mode) raise(mode);
    return 0;
}
