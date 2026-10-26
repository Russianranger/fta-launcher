/* Synthetic Windows x86 parent / x64 child, never the game or WarcraftXL host.
 * Qualifies CreateProcess, shared headers/events and parent-loss cleanup only.
 * Request words are fixture-specific and do NOT implement WarcraftXL requests.
 */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define STRIDE 786496u
#define MAGIC 0x4c585857u
#define DEADLINE 30000u
#define ROUNDS 32u
#define CHECK 0xa57e193bu

static int name_for(char *out, size_t size, const char *role, DWORD pid, DWORD channel) {
    int n = snprintf(out, size, "Local\\FTAProbe_%s_%lu_%lu", role, (unsigned long)pid, (unsigned long)channel);
    return n > 0 && (size_t)n < size;
}

static HANDLE named_event(const char *role, DWORD pid, DWORD channel, BOOL create) {
    char name[128];
    if (!name_for(name, sizeof(name), role, pid, channel)) return NULL;
    if (!create) return OpenEventA(EVENT_MODIFY_STATE | SYNCHRONIZE, FALSE, name);
    HANDLE result = CreateEventA(NULL, FALSE, FALSE, name);
    if (result && GetLastError() == ERROR_ALREADY_EXISTS) { CloseHandle(result); return NULL; }
    return result;
}

static HANDLE named_mapping(DWORD pid, DWORD channels, BOOL create) {
    char name[128];
    if (!name_for(name, sizeof(name), "Shm", pid, 0)) return NULL;
    if (!create) return OpenFileMappingA(FILE_MAP_ALL_ACCESS, FALSE, name);
    HANDLE result = CreateFileMappingA(INVALID_HANDLE_VALUE, NULL, PAGE_READWRITE, 0, STRIDE * channels, name);
    if (result && GetLastError() == ERROR_ALREADY_EXISTS) { CloseHandle(result); return NULL; }
    return result;
}

static volatile DWORD *header(void *view, DWORD channel) {
    return (volatile DWORD *)((BYTE *)view + STRIDE * channel);
}

static int start(const char *leaf, const char *arguments, DWORD flags, PROCESS_INFORMATION *process) {
    char application[MAX_PATH], command[MAX_PATH * 2 + 160];
    DWORD length = GetModuleFileNameA(NULL, application, sizeof(application));
    if (!length || length >= sizeof(application)) return 0;
    char *slash = strrchr(application, '\\');
    if (!slash || (size_t)(slash - application + 1) + strlen(leaf) >= sizeof(application)) return 0;
    strcpy(slash + 1, leaf);
    int n = snprintf(command, sizeof(command), "\"%s\" %s", application, arguments);
    if (n < 0 || (size_t)n >= sizeof(command)) return 0;
    STARTUPINFOA info; memset(&info, 0, sizeof(info)); info.cb = sizeof(info);
    memset(process, 0, sizeof(*process));
    return CreateProcessA(application, command, NULL, NULL, FALSE, flags | CREATE_NO_WINDOW,
                          NULL, NULL, &info, process) != 0;
}

static void close_process(PROCESS_INFORMATION *process, int success) {
    if (process->hProcess) {
        if (!success && WaitForSingleObject(process->hProcess, 0) == WAIT_TIMEOUT) {
            TerminateProcess(process->hProcess, 91); WaitForSingleObject(process->hProcess, DEADLINE);
        }
        CloseHandle(process->hProcess);
    }
    if (process->hThread) CloseHandle(process->hThread);
}

static int exited(HANDLE process, DWORD code) {
    DWORD actual = 0;
    return WaitForSingleObject(process, DEADLINE) == WAIT_OBJECT_0 &&
           GetExitCodeProcess(process, &actual) && actual == code;
}

#ifndef _WIN64
static int parent(DWORD channels, int abandon) {
    PROCESS_INFORMATION child; memset(&child, 0, sizeof(child));
    HANDLE request[16] = {0}, response[16] = {0}, ready = NULL, mapping = NULL, go = NULL;
    void *view = NULL; int success = 0; DWORD pid = GetCurrentProcessId(); char args[128];
    if (sizeof(void *) != 4 || (channels != 2 && channels != 16)) return 10;
    ready = named_event("Ready", pid, 0, TRUE); if (!ready) goto done;
    snprintf(args, sizeof(args), "--client-pid %lu --channels %lu", (unsigned long)pid, (unsigned long)channels);
    if (!start("probe64.exe", args, 0, &child)) goto done;
    HANDLE startup[2] = {ready, child.hProcess};
    if (WaitForMultipleObjects(2, startup, FALSE, DEADLINE) != WAIT_OBJECT_0) goto done;
    mapping = named_mapping(pid, channels, FALSE); if (!mapping) goto done;
    view = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, STRIDE * channels); if (!view) goto done;
    for (DWORD c = 0; c < channels; ++c) {
        volatile DWORD *h = header(view, c);
        if (h[0] != MAGIC || h[1] != 4 || h[6] != channels || h[7] != child.dwProcessId) goto done;
        for (DWORD word = 2; word < 16; ++word)
            if (word != 6 && word != 7 && h[word] != 0) goto done;
        request[c] = named_event("Req", pid, c, FALSE);
        response[c] = named_event("Resp", pid, c, FALSE);
        if (!request[c] || !response[c]) goto done;
        for (DWORD round = 1; round <= ROUNDS; ++round) {
            h[2] = round; h[4] = round ^ CHECK; MemoryBarrier();
            HANDLE wait[2] = {response[c], child.hProcess};
            if (!SetEvent(request[c]) || WaitForMultipleObjects(2, wait, FALSE, DEADLINE) != WAIT_OBJECT_0) goto done;
            MemoryBarrier();
            if (h[3] != round || h[5] != (round ^ CHECK)) goto done;
        }
    }
    if (abandon) {
        go = named_event("OwnerExit", pid, 0, FALSE);
        if (!go || WaitForSingleObject(go, DEADLINE) != WAIT_OBJECT_0) goto done;
    } else {
        header(view, 0)[2] = 0xffffffffu; MemoryBarrier();
        if (!SetEvent(request[0]) || !exited(child.hProcess, 37)) goto done;
    }
    success = 1;
done:
    if (view) UnmapViewOfFile(view);
    if (mapping) CloseHandle(mapping);
    if (go) CloseHandle(go);
    if (ready) CloseHandle(ready);
    for (DWORD c = 0; c < 16; ++c) { if (request[c]) CloseHandle(request[c]); if (response[c]) CloseHandle(response[c]); }
    close_process(&child, success);
    if (success) printf("FTA_SYNTHETIC_PARENT_OK channels=%lu roundTrips=%lu ownerExit=%d\n",
                        (unsigned long)channels, (unsigned long)(channels * ROUNDS), abandon);
    return success ? 0 : 11;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
        if (sizeof(void *) != 4) return 13;
        puts("FTA_SYNTHETIC_X86_ENTRY_OK"); return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--abandon") == 0) return parent(2, 1);
    if (argc == 2 && strcmp(argv[1], "2") == 0) return parent(2, 0);
    if (argc == 2 && strcmp(argv[1], "16") == 0) return parent(16, 0);
    return 12;
}
#else
static int child(DWORD pid, DWORD channels) {
    HANDLE requests[17] = {0}, response[16] = {0}, ready = NULL, mapping = NULL;
    void *view = NULL; int result = 20;
    if (!pid || (channels != 2 && channels != 16) || sizeof(void *) != 8) return 21;
    requests[channels] = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (!requests[channels]) goto done;
    mapping = named_mapping(pid, channels, TRUE); if (!mapping) goto done;
    view = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, STRIDE * channels); if (!view) goto done;
    for (DWORD c = 0; c < channels; ++c) {
        volatile DWORD *h = header(view, c); memset((void *)h, 0, 64);
        h[0] = MAGIC; h[1] = 4; h[6] = channels; h[7] = GetCurrentProcessId();
        requests[c] = named_event("Req", pid, c, TRUE);
        response[c] = named_event("Resp", pid, c, TRUE);
        if (!requests[c] || !response[c]) goto done;
    }
    ready = named_event("Ready", pid, 0, FALSE);
    MemoryBarrier(); if (!ready || !SetEvent(ready)) goto done;
    for (;;) {
        DWORD wait = WaitForMultipleObjects(channels + 1, requests, FALSE, DEADLINE);
        if (wait == WAIT_OBJECT_0 + channels) { result = 38; break; }
        if (wait >= WAIT_OBJECT_0 + channels) goto done;
        DWORD c = wait - WAIT_OBJECT_0; volatile DWORD *h = header(view, c); MemoryBarrier();
        DWORD value = h[2];
        if (c == 0 && value == 0xffffffffu) { result = 37; break; }
        if (!value || value > ROUNDS || h[4] != (value ^ CHECK)) goto done;
        h[3] = value; h[5] = value ^ CHECK; MemoryBarrier();
        if (!SetEvent(response[c])) goto done;
    }
done:
    if (ready) CloseHandle(ready);
    if (view) UnmapViewOfFile(view);
    if (mapping) CloseHandle(mapping);
    for (DWORD c = 0; c < 17; ++c) if (requests[c]) CloseHandle(requests[c]);
    for (DWORD c = 0; c < 16; ++c) if (response[c]) CloseHandle(response[c]);
    return result;
}

static int observer(void) {
    PROCESS_INFORMATION parent; memset(&parent, 0, sizeof(parent));
    HANDLE go = NULL, mapping = NULL, host = NULL; void *view = NULL; int success = 0;
    if (!start("probe32.exe", "--abandon", CREATE_SUSPENDED, &parent)) goto done;
    go = named_event("OwnerExit", parent.dwProcessId, 0, TRUE); if (!go) goto done;
    if (ResumeThread(parent.hThread) == (DWORD)-1) goto done;
    ULONGLONG deadline = GetTickCount64() + DEADLINE;
    do {
        mapping = named_mapping(parent.dwProcessId, 2, FALSE);
        if (mapping) {
            view = MapViewOfFile(mapping, FILE_MAP_READ, 0, 0, 64); if (!view) goto done;
            DWORD host_pid = header(view, 0)[7];
            if (host_pid) host = OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION, FALSE, host_pid);
            if (host) break;
            UnmapViewOfFile(view); view = NULL; CloseHandle(mapping); mapping = NULL;
        }
        if (WaitForSingleObject(parent.hProcess, 10) != WAIT_TIMEOUT) goto done;
    } while (GetTickCount64() < deadline);
    if (!host || !SetEvent(go) || !exited(parent.hProcess, 0) || !exited(host, 38)) goto done;
    success = 1;
done:
    close_process(&parent, success);
    if (host) {
        if (!success) WaitForSingleObject(host, DEADLINE);
        CloseHandle(host);
    }
    if (view) UnmapViewOfFile(view);
    if (mapping) CloseHandle(mapping);
    if (go) CloseHandle(go);
    if (success) puts("FTA_SYNTHETIC_OWNER_EXIT_OK parentExit=0 childExit=38");
    return success ? 0 : 31;
}

static int word(const char *input, DWORD *out) {
    if (!*input || strlen(input) > 10) return 0;
    ULONGLONG value = 0;
    for (const char *c = input; *c; ++c) {
        if (*c < '0' || *c > '9') return 0;
        value = value * 10 + (unsigned)(*c - '0'); if (value > 0xffffffffu) return 0;
    }
    *out = (DWORD)value; return 1;
}

int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "--self-test") == 0) {
        if (sizeof(void *) != 8) return 33;
        puts("FTA_SYNTHETIC_X64_ENTRY_OK"); return 0;
    }
    if (argc == 2 && strcmp(argv[1], "--observe-owner-exit") == 0) return observer();
    DWORD pid = 0, channels = 0;
    if (argc == 5 && strcmp(argv[1], "--client-pid") == 0 && strcmp(argv[3], "--channels") == 0 &&
        word(argv[2], &pid) && word(argv[4], &channels)) return child(pid, channels);
    return 32;
}
#endif
