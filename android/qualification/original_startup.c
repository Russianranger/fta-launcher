/* Read-only WarcraftXL startup observation. The original x86 DLL alone starts
 * its x64 host. No requests, account credentials or game data are submitted. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>
#include <stdio.h>
#include <string.h>

static void event(const char *name, DWORD value) {
    printf("FTA_CLIENT_EVENT %s %lu\n", name, (unsigned long)value); fflush(stdout);
}
static int owned_host(DWORD pid, DWORD parent, HANDLE process) {
    char image[1024]; DWORD size = sizeof(image);
    if (!QueryFullProcessImageNameA(process, 0, image, &size)) return 0;
    if (_stricmp(image, "Z:\\view\\Utils\\WarcraftXLHost.exe") &&
        _stricmp(image, "Z:\\immutable\\Utils\\WarcraftXLHost.exe")) return 0;
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) return 0;
    PROCESSENTRY32 entry; memset(&entry, 0, sizeof(entry)); entry.dwSize = sizeof(entry);
    int found = 0;
    if (Process32First(snapshot, &entry)) do {
        if (entry.th32ProcessID == pid) { found = entry.th32ParentProcessID == parent; break; }
    } while (Process32Next(snapshot, &entry));
    CloseHandle(snapshot); return found;
}
static BOOL CALLBACK close_window(HWND window, LPARAM value) {
    DWORD pid = 0; GetWindowThreadProcessId(window, &pid);
    if (pid == (DWORD)value) PostMessageA(window, WM_CLOSE, 0, 0);
    return TRUE;
}
static int stop_client(PROCESS_INFORMATION *client) {
    if (WaitForSingleObject(client->hProcess, 0) == WAIT_TIMEOUT) {
        EnumWindows(close_window, (LPARAM)client->dwProcessId);
        if (WaitForSingleObject(client->hProcess, 5000) == WAIT_TIMEOUT) {
            if (!TerminateProcess(client->hProcess, 91)) return 0;
            event("client-forced-stop", 1);
        }
    }
    return WaitForSingleObject(client->hProcess, 10000) == WAIT_OBJECT_0;
}
int main(int argc, char **argv) {
    int fixture = argc == 2 && !strcmp(argv[1], "--fixture");
    if (argc != 2 || (!fixture && strcmp(argv[1], "--original"))) return 2;
    STARTUPINFOA info; memset(&info, 0, sizeof(info)); info.cb = sizeof(info);
    PROCESS_INFORMATION client; memset(&client, 0, sizeof(client));
    HANDLE mapping = NULL, host = NULL; void *view = NULL; int passed = 0;
    char command[] = "\"Z:\\view\\Wow.exe\" -windowed";
    if (!CreateProcessA("Z:\\view\\Wow.exe", command, NULL, NULL, FALSE, 0, NULL, "Z:\\view", &info, &client)) {
        event("client-create-error", GetLastError()); return 10;
    }
    event("client-started", 1);
    char name[96]; snprintf(name, sizeof(name), "Local\\WarcraftXLShm_%lu", (unsigned long)client.dwProcessId);
    ULONGLONG deadline = GetTickCount64() + (fixture ? 10000 : 90000);
    do {
        if (WaitForSingleObject(client.hProcess, 0) != WAIT_TIMEOUT) {
            DWORD status = 0; if (GetExitCodeProcess(client.hProcess, &status)) event("client-early-exit", status);
            goto done;
        }
        mapping = OpenFileMappingA(FILE_MAP_READ, FALSE, name);
        if (mapping) {
            view = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 64);
            if (!view) goto done;
            DWORD header[16]; memcpy(header, view, sizeof(header));
            if (header[0] == 0x4c585857u && header[1] == 4 && header[6] >= 2 && header[6] <= 16 && header[7]) {
                host = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, header[7]);
                if (host && owned_host(header[7], client.dwProcessId, host) && WaitForSingleObject(host, 0) == WAIT_TIMEOUT) {
                    event("host-header-valid", 1); event("host-owned", 1); event("host-channels", header[6]);
                    break;
                }
                if (host) { CloseHandle(host); host = NULL; }
            }
            UnmapViewOfFile(view); view = NULL; CloseHandle(mapping); mapping = NULL;
        }
        Sleep(100);
    } while (GetTickCount64() < deadline);
    if (!host) { event("host-not-observed", 1); goto done; }
    // Observe both original processes alive; no synthetic asset requests.
    deadline = GetTickCount64() + (fixture ? 1000 : 15000);
    do {
        if (WaitForSingleObject(client.hProcess, 0) != WAIT_TIMEOUT || WaitForSingleObject(host, 0) != WAIT_TIMEOUT) goto done;
        Sleep(100);
    } while (GetTickCount64() < deadline);
    event("startup-observed", 1);
    if (!stop_client(&client)) goto done;
    event("client-stopped", 1);
    if (WaitForSingleObject(host, 15000) != WAIT_OBJECT_0) goto done;
    event("host-stopped", 1); passed = 1;
done:
    if (!passed) stop_client(&client);
    if (view) UnmapViewOfFile(view);
    if (mapping) CloseHandle(mapping);
    if (host) CloseHandle(host);
    if (client.hThread) CloseHandle(client.hThread);
    if (client.hProcess) CloseHandle(client.hProcess);
    puts(passed ? "FTA_ORIGINAL_STARTUP_PASS" : "FTA_ORIGINAL_STARTUP_FAILED");
    return passed ? 0 : 11;
}
