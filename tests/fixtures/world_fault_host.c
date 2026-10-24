#include <signal.h>
#include <stdlib.h>
#include <sys/resource.h>
__attribute__((noinline)) void fault_here(volatile int* address) { *address = 1; }
int main(int argc, char** argv) {
    struct rlimit limit = {0, 0};
    if (setrlimit(RLIMIT_CORE, &limit)) return 2;
    int mode = argc > 1 ? atoi(argv[1]) : 0;
    if (mode == 11) fault_here((volatile int*)0);
    else if (mode) raise(mode);
    return 0;
}
