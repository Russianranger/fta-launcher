/* SPDX-License-Identifier: GPL-3.0-or-later
 * FTA's fixed import-tree write guard for the pinned native-64 PRoot bridge.
 * This is file-integrity protection for a cooperative owned session, not a
 * security sandbox for hostile code. The launcher retains the client lease.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include "extension/extension.h"
#include "syscall/sysnum.h"
#include "tracee/mem.h"

static char protected_root[PATH_MAX];

static bool beneath(const char *path, const char *root)
{
    size_t size = strlen(root);
    return strncmp(path, root, size) == 0 && (path[size] == '\0' || path[size] == '/');
}

static bool touches(const char *path)
{
    return path[0] == '/' && (beneath(path, protected_root) ||
        strcmp(path, "/") == 0 || beneath(protected_root, path));
}

/* Imported generations contain no links. Reject pre-existing hard-link aliases
 * before any guest entry; subsequent link/linkat attempts are checked below. */
static int audit_tree(const char *path, unsigned depth, unsigned *entries)
{
    struct stat info;
    if (++*entries > 100001 || depth > 33 || lstat(path, &info) != 0) return -1;
    if (S_ISREG(info.st_mode)) return info.st_nlink == 1 ? 0 : -1;
    if (!S_ISDIR(info.st_mode)) return -1;
    DIR *directory = opendir(path);
    if (directory == NULL) return -1;
    int status = 0;
    while (true) {
        errno = 0;
        struct dirent *entry = readdir(directory);
        if (entry == NULL) { if (errno != 0) status = -1; break; }
        if (!strcmp(entry->d_name, ".") || !strcmp(entry->d_name, "..")) continue;
        char child[PATH_MAX];
        int length = snprintf(child, sizeof(child), "%s/%s", path, entry->d_name);
        if (length < 0 || (size_t)length >= sizeof(child) || audit_tree(child, depth + 1, entries) != 0) {
            status = -1; break;
        }
    }
    if (closedir(directory) != 0) status = -1;
    return status;
}

int fta_readonly_initialize(const char *path)
{
    char canonical[PATH_MAX];
    unsigned entries = 0;
    struct stat info;
    if (protected_root[0] || path == NULL || strlen(path) >= sizeof(canonical) ||
        realpath(path, canonical) == NULL || strcmp(path, canonical) != 0 ||
        strlen(canonical) < 2 || lstat(canonical, &info) || !S_ISDIR(info.st_mode) ||
        audit_tree(canonical, 0, &entries) != 0) return -1;
    /* Every syscall must reach the guard, including fd-only and newer syscalls. */
    if (setenv("PROOT_NO_SECCOMP", "1", 1) != 0) return -1;
    strcpy(protected_root, canonical);
    return 0;
}

int fta_readonly_ready(void) { return protected_root[0] != '\0'; }

static int denied(Tracee *tracee, int error)
{
    tracee->fta_readonly_errno = error;
    return -error;
}

static int check_fd(Tracee *tracee, Reg argument)
{
    int fd = (int)peek_reg(tracee, ORIGINAL, argument);
    char link[80], path[PATH_MAX];
    if (fd < 0) return 0;
    snprintf(link, sizeof(link), "/proc/%d/fd/%d", tracee->pid, fd);
    ssize_t size = readlink(link, path, sizeof(path) - 1);
    if (size < 0) return errno == ENOENT ? 0 : denied(tracee, EACCES);
    if ((size_t)size >= sizeof(path) - 1) return denied(tracee, EACCES);
    path[size] = '\0';
    return touches(path) ? denied(tracee, EROFS) : 0;
}

static bool writes_path(const Tracee *tracee)
{
    word_t flags;
    switch (get_sysnum(tracee, ORIGINAL)) {
    case PR_open:
        flags = peek_reg(tracee, ORIGINAL, SYSARG_2);
        return (flags & (O_ACCMODE | O_CREAT | O_TRUNC)) != 0 || (flags & O_TMPFILE) == O_TMPFILE;
    case PR_openat: case PR_openat2:
        flags = peek_reg(tracee, CURRENT, SYSARG_3);
        return (flags & (O_ACCMODE | O_CREAT | O_TRUNC)) != 0 || (flags & O_TMPFILE) == O_TMPFILE;
    case PR_creat: case PR_truncate: case PR_truncate64:
    case PR_chmod: case PR_chown: case PR_chown32: case PR_lchown: case PR_lchown32:
    case PR_fchmodat: case PR_fchownat:
    case PR_utime: case PR_utimes: case PR_utimensat: case PR_futimesat:
    case PR_setxattr: case PR_lsetxattr: case PR_removexattr: case PR_lremovexattr:
    case PR_link: case PR_linkat: case PR_rename: case PR_renameat: case PR_renameat2:
    case PR_unlink: case PR_unlinkat: case PR_rmdir:
    case PR_mkdir: case PR_mkdirat: case PR_mknod: case PR_mknodat:
    case PR_symlink: case PR_symlinkat: case PR_bind:
        return true;
    default:
        return false;
    }
}

int fta_readonly_event(Tracee *tracee, ExtensionEvent event, intptr_t data1, intptr_t data2 UNUSED)
{
    if (!fta_readonly_ready()) return 0;
    switch (event) {
    case SYSCALL_ENTER_START:
        tracee->fta_readonly_errno = 0;
        /* Both Windows architectures use Wine/FEX's native-64 Unix side. Do
         * not silently admit another Linux syscall ABI without qualification. */
        if (get_abi(tracee) != ABI_DEFAULT) return denied(tracee, ENOSYS);
        switch (get_sysnum(tracee, ORIGINAL)) {
        case PR_void: /* Includes unknown newer operations, e.g. io_uring. */
        case PR_io_setup: case PR_io_submit: case PR_open_by_handle_at: case PR_name_to_handle_at:
        case PR_ptrace: case PR_process_vm_writev:
        case PR_mount: case PR_umount: case PR_umount2: case PR_pivot_root:
        case PR_chroot: case PR_setns: case PR_unshare:
        case PR_acct: case PR_swapon: case PR_swapoff:
            return denied(tracee, ENOSYS);
        case PR_write: case PR_writev: case PR_pwrite64: case PR_pwritev: case PR_pwritev2:
        case PR_ftruncate: case PR_ftruncate64: case PR_fallocate:
        case PR_fchmod: case PR_fchown: case PR_fchown32:
        case PR_fsetxattr: case PR_fremovexattr: case PR_ioctl:
        case PR_sendfile: case PR_sendfile64: case PR_vmsplice:
            return check_fd(tracee, SYSARG_1);
        case PR_splice: case PR_copy_file_range:
            return check_fd(tracee, SYSARG_3);
        case PR_tee:
            return check_fd(tracee, SYSARG_2);
        case PR_mmap: case PR_mmap2:
            if ((peek_reg(tracee, ORIGINAL, SYSARG_3) & PROT_WRITE) &&
                (peek_reg(tracee, ORIGINAL, SYSARG_4) & MAP_SHARED)) return check_fd(tracee, SYSARG_5);
            return 0;
        case PR_utimensat: case PR_futimesat: case PR_fchownat: case PR_linkat: {
            word_t address = peek_reg(tracee, ORIGINAL, SYSARG_2);
            char first = '\0';
            if (address != 0 && read_data(tracee, &first, address, 1) < 0) return denied(tracee, EFAULT);
            return address == 0 || first == '\0' ? check_fd(tracee, SYSARG_1) : 0;
        }
        default:
            return 0;
        }
    case HOST_PATH:
        /* PRoot's fake-root extension temporarily chmods traversed nodes.
         * Skip that and other extension side effects on protected nodes. */
        return touches((const char *)data1) ? 1 : 0;
    case TRANSLATED_PATH:
        if (!touches((const char *)data1)) return 0;
        return writes_path(tracee) ? denied(tracee, EROFS) : 1;
    case SYSCALL_ENTER_END:
        return tracee->fta_readonly_errno ? -tracee->fta_readonly_errno : 0;
    case SYSCALL_EXIT_START:
        if (!tracee->fta_readonly_errno) return 0;
        poke_reg(tracee, SYSARG_RESULT, (word_t)-tracee->fta_readonly_errno);
        return 1; /* No fake-root extension may turn denial into success. */
    case SYSCALL_EXIT_END:
        return tracee->fta_readonly_errno ? -tracee->fta_readonly_errno : 0;
    default:
        return 0;
    }
}
