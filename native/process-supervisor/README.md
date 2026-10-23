# Owned native processes

The Android service launches only integrity-checked executables from the APK's
`nativeLibraryDir`. This helper supplies process ownership; it does not select
runtime binaries or grant authority to an imported ZIP.

The parent converts all JNI arguments before `fork`. The child resets the signal
mask/termination dispositions, sets `PR_SET_PDEATHSIG`, creates a process group,
installs fixed stdin/stdout/stderr and closes inherited descriptors before
`execve`. No shell is involved. A failed exec is reported before a handle is
published. Foreground server modes are required: the direct child must not
daemonize or intentionally leave its owned process group.

Opaque handles refer only to children created by this parent. `waitid(WNOWAIT)`
keeps an exited leader PID reserved while ending any remaining group members;
only then is the leader reaped. This prevents targeting a recycled process group.
Stop first requests SIGTERM, then uses bounded SIGKILL fallback. Parent-death
SIGKILL protects even a child that ignores TERM if the owning runtime
thread/process dies. The current database test is disposable. Persistent
database crash recovery remains a separate runtime responsibility.

`process_id(handle)` exposes only a live, directly owned child for observation.
It uses `waitid(WNOWAIT)` without reaping, signalling or changing the registry;
an exited leader or unknown handle is refused. The recovery controller combines
this PID with the kernel start time and verifies initial parentage. Reparenting
alone is never termination, and inaccessible/malformed procfs records fail the
test. No public API accepts a caller-selected PID to stop.

Two reader threads drain stdout/stderr so a child cannot block on a full pipe.
Each private mode-0600 log retains at most 8 MiB while excess output is discarded.
The cap applies only to captured logs, not database files. Final pipe draining is
also bounded. Finished status is cached; finished log readers are joined before
returning completion. Unknown caller-selected handles are rejected.

The host regression executable tests successful/nonzero exits, repeat waits and
stops, TERM, inherited signal masks, parent death, leader/grandchild cleanup,
bounded logs, failed exec, invalid handles and symlinked IO paths. Run:

```sh
g++ -std=c++17 -Wall -Wextra -Werror -pthread \
  native/process-supervisor/process_owner.cpp \
  native/process-supervisor/process_owner_test.cpp -o /tmp/fta-owner-test
/tmp/fta-owner-test
```

These Linux host tests do not substitute for the JNI/Android runtime gate.
