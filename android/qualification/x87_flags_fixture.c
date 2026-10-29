/* Synthetic x87 store/condition-code regression. Contains no game code/data. */
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
static unsigned char *guard_page;
static LONG CALLBACK fault(EXCEPTION_POINTERS *p) {
    if (p->ExceptionRecord->ExceptionCode != EXCEPTION_ACCESS_VIOLATION) return EXCEPTION_CONTINUE_SEARCH;
    uintptr_t address = p->ExceptionRecord->ExceptionInformation[1];
    printf("FTA_X87_FAULT %lu %u\n", (unsigned long)p->ExceptionRecord->ExceptionInformation[0],
        (unsigned)(address >= (uintptr_t)guard_page && address < (uintptr_t)guard_page + 4096));
    fflush(stdout); ExitProcess(21); return EXCEPTION_CONTINUE_SEARCH;
}
#else
#include <sys/mman.h>
#endif

int main(void) {
    const unsigned page = 4096, count = 48, bytes = count * 12;
    unsigned char *memory;
#ifdef _WIN32
    DWORD old;
    memory = VirtualAlloc(NULL, page * 2, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (!memory || !VirtualProtect(memory + page, page, PAGE_NOACCESS, &old)) return 2;
    guard_page = memory + page;
    if (!AddVectoredExceptionHandler(1, fault)) return 3;
#else
    memory = mmap(NULL, page * 2, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_32BIT, -1, 0);
    if (memory == MAP_FAILED || mprotect(memory + page, page, PROT_NONE)) return 2;
#endif
    memset(memory, 0xa5, page);
    unsigned char *values = memory + page - bytes;
    uint32_t cursor = (uint32_t)(uintptr_t)(values + 8), remaining = count - 1;
    uintptr_t before, after;
    float scratch;
    /* FST must leave integer flags unchanged, including the sign flag. */
    __asm__ volatile("fldz\n\tmov $0, %%ecx\n\tsub $1, %%ecx\n\t"
#ifdef __x86_64__
        "pushfq\n\tpopq %0\n\t"
#else
        "pushfl\n\tpopl %0\n\t"
#endif
        "fsts %2\n\t"
#ifdef __x86_64__
        "pushfq\n\tpopq %1\n\t"
#else
        "pushfl\n\tpopl %1\n\t"
#endif
        "fstp %%st(0)" : "=&r"(before), "=&r"(after), "=m"(scratch) : : "ecx", "cc", "st", "memory");
    before &= 0x8d5; after &= 0x8d5;
    printf("FTA_X87_FLAGS %lu %lu\n", (unsigned long)before, (unsigned long)after); fflush(stdout);
    /* Three scalar stores per iteration. The guard catches a wrong JNS before
     * it can damage anything outside this synthetic allocation. */
    __asm__ volatile("fldz\n\t1: fsts -8(%%eax)\n\tadd $12, %%eax\n\tsub $1, %%ecx\n\t"
        "fsts -16(%%eax)\n\tfsts -12(%%eax)\n\tjns 1b\n\tfstp %%st(0)"
        : "+a"(cursor), "+c"(remaining) : : "cc", "st", "memory");
    if (remaining != UINT32_MAX || cursor != (uint32_t)(uintptr_t)(values + bytes + 8)) return 4;
    for (unsigned i = 0; i < page - bytes; i++) if (memory[i] != 0xa5) return 5;
    for (unsigned i = 0; i < bytes; i++) if (values[i]) return 6;
    printf("FTA_X87_LOOP %u %u\n", count, bytes);
    if (before != after || scratch != 0.0f) return 20;
    puts("FTA_X87_PASS"); return 0;
}
