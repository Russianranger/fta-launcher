/* SPDX-License-Identifier: MIT. Synthetic Windows file access; no game assets. */
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <stdio.h>
#include <string.h>

#ifdef _WIN64
#define ARCH "x64"
#define OTHER "Z:\\view\\probe32.exe"
#else
#define ARCH "x86"
#define OTHER "Z:\\view\\probe64.exe"
#endif
static unsigned checks;
static const char *shared="Z:\\view\\Data\\canary.bin";
static const char *direct="Z:\\immutable\\Data\\canary.bin";
static int denied(DWORD error) { return error == ERROR_ACCESS_DENIED || error == ERROR_WRITE_PROTECT; }
static void check(const char *name, int ok) {
    DWORD error=GetLastError();
    printf("FTA_GUARDED_WIN " ARCH " %s %d\n",name,ok ? 0 : 1); fflush(stdout);
    if (!ok) { printf("FTA_GUARDED_ERROR " ARCH " %s %lu\n",name,error); fflush(stdout); ExitProcess(1); }
    checks++;
}
static HANDLE open_file(const char *path, DWORD access, DWORD mode) {
    return CreateFileA(path,access,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,NULL,mode,FILE_ATTRIBUTE_NORMAL,NULL);
}
static int cannot_open(const char *path, DWORD access, DWORD mode) {
    SetLastError(0); HANDLE file=open_file(path,access,mode); DWORD error=GetLastError();
    if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
    return file == INVALID_HANDLE_VALUE && denied(error);
}
int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1],"--child")) {
        HANDLE file=open_file(shared,GENERIC_READ,OPEN_EXISTING); unsigned char byte=255; DWORD count=0;
        int ok=file != INVALID_HANDLE_VALUE && ReadFile(file,&byte,1,&count,NULL) && count==1 && byte==0;
        if (file != INVALID_HANDLE_VALUE) CloseHandle(file);
        return ok && cannot_open(shared,GENERIC_WRITE,OPEN_EXISTING) ? 0 : 2;
    }
    if (argc != 1) return 2;
    HANDLE file=open_file(shared,GENERIC_READ,OPEN_EXISTING); check("shared-read-open",file != INVALID_HANDLE_VALUE);
    unsigned char bytes[4096]; DWORD count=0;
    check("read-content",ReadFile(file,bytes,sizeof(bytes),&count,NULL) && count==sizeof(bytes) && bytes[0]==0 && bytes[4095]==4095%251);
    LARGE_INTEGER position; position.QuadPart=60000;
    check("seek",SetFilePointerEx(file,position,NULL,FILE_BEGIN) && ReadFile(file,bytes,1,&count,NULL) && count==1 && bytes[0]==60000%251);
    HANDLE mapping=CreateFileMappingA(file,NULL,PAGE_READONLY,0,0,NULL); check("read-mapping",mapping != NULL);
    unsigned char *view=MapViewOfFile(mapping,FILE_MAP_READ,0,0,65536);
    check("mapped-content",view != NULL && view[65000]==65000%251); UnmapViewOfFile(view); CloseHandle(mapping);
    mapping=CreateFileMappingA(file,NULL,PAGE_WRITECOPY,0,0,NULL); check("private-mapping",mapping != NULL);
    view=MapViewOfFile(mapping,FILE_MAP_COPY,0,0,65536); check("private-view",view != NULL);
    view[0]=99; UnmapViewOfFile(view); CloseHandle(mapping); CloseHandle(file);
    file=open_file(direct,GENERIC_READ,OPEN_EXISTING); check("direct-read",file != INVALID_HANDLE_VALUE);
    check("private-write-isolated",ReadFile(file,bytes,1,&count,NULL) && count==1 && bytes[0]==0); CloseHandle(file);
    WIN32_FIND_DATAA found; HANDLE listing=FindFirstFileA("Z:\\view\\Data\\*",&found);
    check("directory-list",listing != INVALID_HANDLE_VALUE); FindClose(listing);
    check("shared-write-denied",cannot_open(shared,GENERIC_WRITE,OPEN_EXISTING));
    check("direct-write-denied",cannot_open(direct,GENERIC_WRITE,OPEN_EXISTING));
    check("truncate-denied",cannot_open(shared,GENERIC_WRITE,TRUNCATE_EXISTING));
    check("overwrite-denied",cannot_open(shared,GENERIC_WRITE,CREATE_ALWAYS));
    check("protected-create-denied",cannot_open("Z:\\immutable\\Data\\new.bin",GENERIC_WRITE,CREATE_NEW));
    SetLastError(0); int ok=DeleteFileA(direct); check("delete-denied",!ok && denied(GetLastError()));
    SetLastError(0); ok=MoveFileA(direct,"Z:\\view\\moved.bin"); check("rename-denied",!ok && denied(GetLastError()));
    SetLastError(0); ok=SetFileAttributesA(direct,FILE_ATTRIBUTE_READONLY); check("attributes-denied",!ok && denied(GetLastError()));
    SetLastError(0); ok=CreateHardLinkA("Z:\\view\\hardlink.bin",direct,NULL); check("hardlink-denied",!ok && denied(GetLastError()));
    SetLastError(0); ok=CreateDirectoryA("Z:\\immutable\\new-directory",NULL); check("mkdir-denied",!ok && denied(GetLastError()));
    file=open_file("Z:\\view\\WTF\\Config.wtf",GENERIC_READ|GENERIC_WRITE,OPEN_EXISTING);
    check("config-open",file != INVALID_HANDLE_VALUE);
    check("config-write",WriteFile(file,"private",7,&count,NULL) && count==7 && FlushFileBuffers(file)); CloseHandle(file);
    file=open_file("Z:\\view\\Logs\\writable.bin",GENERIC_WRITE,CREATE_ALWAYS); check("log-create",file != INVALID_HANDLE_VALUE);
    check("log-write",WriteFile(file,"allowed",7,&count,NULL) && count==7 && FlushFileBuffers(file)); CloseHandle(file);
    check("log-rename",MoveFileA("Z:\\view\\Logs\\writable.bin","Z:\\view\\Logs\\renamed.bin"));
    check("log-delete",DeleteFileA("Z:\\view\\Logs\\renamed.bin"));
    STARTUPINFOA startup; PROCESS_INFORMATION child; ZeroMemory(&startup,sizeof(startup)); ZeroMemory(&child,sizeof(child)); startup.cb=sizeof(startup);
    char command[]="\"" OTHER "\" --child";
    check("mixed-child-start",CreateProcessA(OTHER,command,NULL,NULL,FALSE,0,NULL,"Z:\\view",&startup,&child));
    DWORD waited=WaitForSingleObject(child.hProcess,15000); DWORD code=255;
    if (waited != WAIT_OBJECT_0) { TerminateProcess(child.hProcess,3); WaitForSingleObject(child.hProcess,5000); }
    check("mixed-child-read-protection",waited==WAIT_OBJECT_0 && GetExitCodeProcess(child.hProcess,&code) && code==0);
    CloseHandle(child.hThread); CloseHandle(child.hProcess);
    printf("FTA_GUARDED_WIN_PASS " ARCH " checks=%u\n",checks); fflush(stdout);
    return 0;
}
