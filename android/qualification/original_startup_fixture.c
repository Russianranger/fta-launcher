/* Host qualification only. Not shipped as the original client or asset host. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char **argv) {
#ifdef _WIN64
    if (argc != 3 || strcmp(argv[1], "--client-pid")) return 2;
    DWORD pid = (DWORD)strtoul(argv[2], NULL, 10);
    HANDLE parent = OpenProcess(SYNCHRONIZE, FALSE, pid); if (!parent) return 3;
    char name[96]; snprintf(name, sizeof(name), "Local\\WarcraftXLShm_%lu", (unsigned long)pid);
    HANDLE mapping = CreateFileMappingA(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE, 0, 64, name);
    if (!mapping) { CloseHandle(parent); return 4; }
    DWORD *words = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, 64);
    if (!words) { CloseHandle(mapping); CloseHandle(parent); return 5; }
    memset(words, 0, 64); words[0] = 0x4c585857u; words[1] = 4; words[6] = 2; words[7] = GetCurrentProcessId();
    MemoryBarrier(); DWORD status = WaitForSingleObject(parent, 30000);
    UnmapViewOfFile(words); CloseHandle(mapping); CloseHandle(parent);
    return status == WAIT_OBJECT_0 ? 0 : 6;
#else
    if (argc == 3 && !strcmp(argv[2], "--fta-fixture-crash")) {
        volatile DWORD *page = VirtualAlloc(NULL, 4096, MEM_RESERVE | MEM_COMMIT, PAGE_NOACCESS);
        if (!page) return 12;
        *page = 42; // Actual synthetic write fault in this PE32 image, never private game code.
        return 13;
    }
    char args[160]; snprintf(args, sizeof(args), "\"Z:\\view\\Utils\\WarcraftXLHost.exe\" --client-pid %lu", (unsigned long)GetCurrentProcessId());
    STARTUPINFOA info; memset(&info, 0, sizeof(info)); info.cb = sizeof(info);
    PROCESS_INFORMATION host; memset(&host, 0, sizeof(host));
    if (!CreateProcessA("Z:\\view\\Utils\\WarcraftXLHost.exe", args, NULL, NULL, FALSE, 0, NULL, "Z:\\view\\Utils", &info, &host)) return 10;
    CloseHandle(host.hThread); CloseHandle(host.hProcess);
    Sleep(30000); return 11;
#endif
}
