# Disposable client storage-isolation gate

Code **42 / 0.15.0-storage-isolation** adds **Test client storage isolation**.
This is the next device gate before a working view of the original client is
exposed. It does not open the imported client or Wine runtime, copy an MPQ,
reinitialize a prefix, or repeat the passed code-41 runtime test.

The test prepares 65,545 bytes in a new private attempt, plus tiny logs. The
protected tree contains two files and an empty directory; a writable sibling
contains a symlink back to it. The new, separately pinned `libproot_ro.so` runs
the native ARM64 `libfta_storage_probe.so` through the existing owned supervisor.
The old `libproot.so` and its loader stay byte-identical. All retained server
payloads, source modules and realm data remain unchanged.

The fixture checks 50 named operations: reads, seeks, private/shared mappings,
directory-relative access, direct/alias/proc-descriptor writes, truncation,
permissions, ownership, timestamps, extended attributes, links, rename, unlink,
directory mutations and a forked child. Writable create/write/truncate/chmod/
rename/unlink controls must also pass. Exactly one complete ordered transcript,
exit zero, reaped owned handles, four refused invalid-root launches, and an
independent protected-tree snapshot comparison are required. The native Linux
qualification also compares nanosecond mtime/ctime and rejects seven root cases.
Android snapshots compare content, directory inventory, device/inode, mode,
links, UID/GID, size and second-resolution mtime/ctime from `Os.lstat`.

The guard audits the import root before launching: canonical non-root directory,
no symlinks/special files or multiply-linked regular files, bounded depth/count.
It observes native-64 syscalls without PRoot seccomp acceleration, denies writes
through translated paths and file descriptors, resolves proc descriptor aliases,
protects ancestors, and prevents fake-root from masking a denial or temporarily
chmodding protected files. Unsupported Linux ABIs, unknown syscalls and alternate
asynchronous/handle/mount mutation entry points are refused. Windows PE32/PE64
compatibility under this new guard is **not yet measured**.

This is file-integrity protection for a cooperative owned session, not a security
sandbox for malicious code. Future original-client sessions still require an
exclusive verified-client lease, no inherited writable descriptors, bounded
working/prefix storage and post-session integrity checks. Working-view creation,
original WarcraftXL startup, rendering and login remain unimplemented/unqualified.

## Thor procedure

1. Install the private signed code-42 APK over code 41. Keep all app data.
2. Open the launcher and tap **Test client storage isolation** once. Keep it
   visible. This test normally takes seconds; its overall limit is two minutes.
   **Stop client runtime operation** cancels it.
3. Expected: **Storage isolation passed: 50 checks.** Export the diagnostic
   report and return it, including if the test fails, stops or is interrupted.
   Do not repeat import, full rehash, Wine/runtime or server tests.

The separate `clientStorageIsolation` section reports only allowlisted results,
payload identities, root refusals and attempt-matched process exits. It preserves
the existing `clientRuntime` report. No file paths or raw native logs are exported.
At most sixteen tiny attempts are retained; uncertain attempts are never deleted.

## Reproduction

`tools/check_client_readonly.py` builds one native host job from the pinned public
source archive and runs the same C fixture. `Client import write guard` runs this
on native x86-64 and ARM64 Linux. The first run exposed a proc-FD alias bypass;
commit `d5ad08a2eb642bde722a332678f61a22929bb5e7` / run `40613303413` fixes it and
passed both architectures. Local execution is restricted by the workspace's
ptrace policy; the local host compile alone is not execution evidence.

`tools/build_client_bridge.py --readonly` builds the separate Android tracer and
fixture with pinned NDK r28c/API26 and 16 KiB ELF segments. The standard invocation
continues to produce the original bridge. `tools/stage_client_readonly.py` pins
both outputs, notices and source hashes and extends the exact bridge source
companion. The private packager separately verifies these additions and refuses
silent replacement of any donor native payload. Android execution awaits the
Thor report; do not infer it from Linux CI or APK assembly.
