/* SPDX-License-Identifier: MIT
 * Disposable native-64 syscall fixture; never receives an imported client path.
 * The controller independently checks the complete protected tree afterwards.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <sys/xattr.h>
#include <unistd.h>
#include <utime.h>

static void check(const char *name, int ok) {
    printf("FTA_RO %s %d\n", name, ok ? 0 : 1); fflush(stdout);
    if (!ok) { fprintf(stderr, "fixture errno=%d\n", errno); exit(1); }
}
#define DENIED(name, call) do { errno=0; long r=(long)(call); check(name, r == -1 && errno == EROFS); } while (0)
static void path(char *out, size_t size, const char *root, const char *tail) {
    int n=snprintf(out,size,"%s/%s",root,tail);
    if (n < 0 || (size_t)n >= size) exit(2);
}

int main(int argc, char **argv) {
    if (argc != 2 || argv[1][0] != '/' || strlen(argv[1]) > 2048) return 2;
    char immutable[4096], file[4096], directory[4096], newfile[4096], writable[4096], alias[4096], out[4096], parent[4096];
    path(immutable,sizeof(immutable),argv[1],"immutable");
    path(file,sizeof(file),immutable,"canary.bin");
    path(directory,sizeof(directory),immutable,"empty");
    path(newfile,sizeof(newfile),immutable,"new");
    path(writable,sizeof(writable),argv[1],"writable");
    path(alias,sizeof(alias),writable,"alias/canary.bin");
    path(out,sizeof(out),writable,"output");
    path(parent,sizeof(parent),argv[1],"moved");
    int fd=open(file,O_RDONLY); check("read-open",fd >= 0);
    unsigned char bytes[4096];
    check("read-content",read(fd,bytes,sizeof(bytes)) == (ssize_t)sizeof(bytes) && bytes[0] == 0 && bytes[4095] == 4095%251);
    check("seek",lseek(fd,60000,SEEK_SET) == 60000 && read(fd,bytes,1) == 1 && bytes[0] == 60000%251);
    void *mapped=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_PRIVATE,fd,0);
    check("private-map",mapped != MAP_FAILED && ((unsigned char *)mapped)[65000] == 65000%251);
    ((char *)mapped)[0]=42; munmap(mapped,65536);
    mapped=mmap(NULL,65536,PROT_READ,MAP_SHARED,fd,0);
    check("shared-read-map",mapped != MAP_FAILED && ((unsigned char *)mapped)[0] == 0);
    munmap(mapped,65536);
    int dir=open(immutable,O_RDONLY|O_DIRECTORY); check("directory-read",dir >= 0);
    int second=openat(dir,"canary.bin",O_RDONLY); check("relative-read",second >= 0); close(second);
    second=open(alias,O_RDONLY); check("alias-read",second >= 0); close(second);
    char read_only[4096]; path(read_only,sizeof(read_only),immutable,"read-only.txt");
    second=open(read_only,O_RDONLY); check("mode-protected-read",second >= 0 && read(second,bytes,9) == 9); close(second);

    DENIED("open-write",open(file,O_WRONLY));
    DENIED("open-read-write",open(file,O_RDWR));
    DENIED("open-truncate",open(file,O_RDONLY|O_TRUNC));
    DENIED("open-create",open(newfile,O_WRONLY|O_CREAT,0600));
    DENIED("relative-write",openat(dir,"canary.bin",O_WRONLY));
    DENIED("alias-write",open(alias,O_WRONLY));
    char procfd[80]; snprintf(procfd,sizeof(procfd),"/proc/self/fd/%d",fd);
    DENIED("proc-fd-write",open(procfd,O_WRONLY));
    DENIED("truncate",truncate(file,0));
    DENIED("fd-truncate",ftruncate(fd,0));
    DENIED("fd-write",write(fd,"x",1));
    DENIED("fd-pwrite",pwrite(fd,"x",1,0));
    DENIED("chmod",chmod(file,0777));
    DENIED("fd-chmod",fchmod(fd,0777));
    DENIED("relative-chmod",fchmodat(dir,"canary.bin",0777,0));
    DENIED("chown",chown(file,getuid(),getgid()));
    DENIED("fd-chown",fchown(fd,getuid(),getgid()));
    DENIED("relative-chown",fchownat(dir,"canary.bin",getuid(),getgid(),0));
    DENIED("timestamps",utimensat(AT_FDCWD,file,NULL,0));
    DENIED("fd-timestamps",futimens(fd,NULL));
    DENIED("xattr",setxattr(file,"user.fta-test","x",1,0));
    DENIED("fd-xattr",fsetxattr(fd,"user.fta-test","x",1,0));
    DENIED("unlink",unlink(file));
    DENIED("relative-unlink",unlinkat(dir,"canary.bin",0));
    DENIED("rename-out",rename(file,out));
    DENIED("hard-link-out",link(file,out));
    DENIED("relative-link-out",linkat(dir,"canary.bin",AT_FDCWD,out,0));
    DENIED("mkdir",mkdir(newfile,0700));
    DENIED("rmdir",rmdir(directory));
    DENIED("symlink-in",symlink(out,newfile));
    DENIED("directory-rename",rename(immutable,parent));
    DENIED("directory-chmod",chmod(immutable,0777));
    DENIED("ancestor-chmod",chmod(argv[1],0777));
    errno=0; mapped=mmap(NULL,65536,PROT_READ|PROT_WRITE,MAP_SHARED,fd,0);
    check("shared-write-map",mapped == MAP_FAILED && errno == EROFS);

    pid_t child=fork();
    if (child == 0) { errno=0; int attempt=open(file,O_WRONLY); _exit(attempt == -1 && errno == EROFS ? 0 : 1); }
    check("child-start",child >= 0);
    int status=0; check("child-write",waitpid(child,&status,0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    close(dir); close(fd);

    int output=open(out,O_RDWR|O_CREAT|O_EXCL,0600); check("writable-create",output >= 0);
    check("writable-write",write(output,"allowed",7) == 7 && fsync(output) == 0);
    check("writable-truncate",ftruncate(output,4) == 0);
    check("writable-chmod",fchmod(output,0640) == 0); close(output);
    path(parent,sizeof(parent),writable,"renamed");
    check("writable-rename",rename(out,parent) == 0);
    check("writable-unlink",unlink(parent) == 0);
    puts("FTA_RO_COMPLETE 1");
    return 0;
}
