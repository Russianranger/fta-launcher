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
static void process_state(const char *role, HANDLE process, DWORD pid, ULONGLONG began, DWORD phase) {
    char key[64]; DWORD state = process ? WaitForSingleObject(process, 0) : WAIT_FAILED;
    DWORD wait_error = state == WAIT_FAILED && process ? GetLastError() : 0;
    snprintf(key, sizeof(key), "%s-state", role);
    event(key, !process ? 0 : state == WAIT_TIMEOUT ? 1 : state == WAIT_OBJECT_0 ? 2 : 3);
    if (!process) return;
    if (state == WAIT_OBJECT_0) {
        DWORD status;
        if (GetExitCodeProcess(process, &status)) {
            snprintf(key, sizeof(key), "%s-exit-code", role); event(key, status);
            // Only spontaneous exits may be correlated with an exception.
            if (phase <= 2) {
                if (!strcmp(role, "client")) event("client-early-exit", status);
                printf("FTA_%s_EXIT %lu %lu %llu\n", !strcmp(role, "client") ? "CLIENT" : "HOST",
                    (unsigned long)pid, (unsigned long)status, (unsigned long long)(GetTickCount64() - began)); fflush(stdout);
            }
        } else { DWORD error = GetLastError(); snprintf(key, sizeof(key), "%s-exit-read-error", role); event(key, error); }
    } else if (state != WAIT_TIMEOUT) {
        snprintf(key, sizeof(key), "%s-wait-error", role); event(key, wait_error);
    }
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
    int fixture_crash = argc == 2 && !strcmp(argv[1], "--fixture-crash");
    const char *late = argc == 2 && !strcmp(argv[1], "--fixture-client-exit") ? " --fta-fixture-client-exit" :
        argc == 2 && !strcmp(argv[1], "--fixture-host-exit") ? " --fta-fixture-host-exit" :
        argc == 2 && !strcmp(argv[1], "--fixture-host-crash") ? " --fta-fixture-host-crash" : "";
    int fixture = argc == 2 && (!strcmp(argv[1], "--fixture") || fixture_crash || late[0]);
    if (argc != 2 || (!fixture && strcmp(argv[1], "--original"))) return 2;
    STARTUPINFOA info; memset(&info, 0, sizeof(info)); info.cb = sizeof(info);
    PROCESS_INFORMATION client; memset(&client, 0, sizeof(client));
    HANDLE mapping = NULL, host = NULL; void *view = NULL; int passed = 0; DWORD host_pid = 0, phase = 1;
    char command[160]; snprintf(command, sizeof(command), "\"Z:\\view\\Wow.exe\" -windowed%s", fixture_crash ? " --fta-fixture-crash" : late);
    ULONGLONG began = GetTickCount64();
    if (!CreateProcessA("Z:\\view\\Wow.exe", command, NULL, NULL, FALSE, 0, NULL, "Z:\\view", &info, &client)) {
        event("client-create-error", GetLastError()); return 10;
    }
    // Correlation markers are consumed privately by the bounded guest parser.
    // Android exports neither the PID nor raw guest output.
    printf("FTA_CLIENT_PROCESS %lu\n", (unsigned long)client.dwProcessId); fflush(stdout);
    event("client-started", 1);
    char name[96]; snprintf(name, sizeof(name), "Local\\WarcraftXLShm_%lu", (unsigned long)client.dwProcessId);
    ULONGLONG deadline = GetTickCount64() + (fixture ? 10000 : 90000);
    do {
        if (WaitForSingleObject(client.hProcess, 0) != WAIT_TIMEOUT) {
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
                    host_pid = header[7];
                    printf("FTA_HOST_PROCESS %lu\n", (unsigned long)host_pid); fflush(stdout);
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
    phase = 2;
    deadline = GetTickCount64() + (late[0] ? 5000 : fixture ? 1000 : 15000);
    do {
        if (WaitForSingleObject(client.hProcess, 0) != WAIT_TIMEOUT || WaitForSingleObject(host, 0) != WAIT_TIMEOUT) goto done;
        Sleep(100);
    } while (GetTickCount64() < deadline);
    event("startup-observed", 1);
    phase = 3;
    if (!stop_client(&client)) goto done;
    event("client-stopped", 1);
    phase = 4;
    if (WaitForSingleObject(host, 15000) != WAIT_OBJECT_0) goto done;
    event("host-stopped", 1); passed = 1;
done:
    if (!passed) {
        event("observer-failure-phase", phase);
        event("observation-elapsed-ms", (DWORD)(GetTickCount64() - began));
        process_state("client", client.hProcess, client.dwProcessId, began, phase);
        process_state("host", host, host_pid, began, phase);
        stop_client(&client);
    }
    if (view) UnmapViewOfFile(view);
    if (mapping) CloseHandle(mapping);
    if (host) CloseHandle(host);
    if (client.hThread) CloseHandle(client.hThread);
    if (client.hProcess) CloseHandle(client.hProcess);
    puts(passed ? "FTA_ORIGINAL_STARTUP_PASS" : "FTA_ORIGINAL_STARTUP_FAILED");
    return passed ? 0 : 11;
}
