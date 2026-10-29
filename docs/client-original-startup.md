# Original-client startup diagnostic

Code 46 combines a fresh full managed-client verification, isolated working view,
original Windows startup observation and owned cleanup in one service operation.
It requires the completed code-45 guarded-Wine receipt and preserves all earlier
reports. No seed import, realm start, login, asset request or rendering claim is
part of this gate.

The exclusive runtime lease covers full pre/post runtime hashes. Inside it,
`ClientStore.withVerifiedClient` retains the client lease through full verification,
view construction, every owned reader's exit and the source metadata audit.
An early read-only preflight rejects a missing original binary, excessive mutable
copies or insufficient space before the roughly 60 GB verification. Existing
48 MiB/s pacing, cache release and memory backpressure remain in use.

The pinned catalog and four original Wow/proxy/DLL/host hashes must match.
Large shared files are guest links into `/immutable`, protected by the previously
qualified native guard. Mutable files are independent copies, capped at 256 MiB from code 48;
there are no writable hard links or full client recopy. From code 47, root
Cache/Logs/Errors/Screenshots start as independent empty session directories;
their historical contents remain in the imported baseline, full verification
and source audit, but are excluded from view copies and shared links. All WTF
and other configuration copies share that bounded budget. Code 47 measured
112,062,202 copied bytes, mostly WTF settings, exceeding its old 64 MiB cap.
Before native entry code 48 freezes the actual prepared view size (at most
257 MiB, including up to 1 MiB for launch settings) and adds a separate 128 MiB
session-output allowance. Five-second polling and the terminal check enforce
this limit without following shared links; this is not a filesystem quota. Fixed-group sizes and
counts are saved before a budget refusal, without filenames or settings. Only the view's
`WTF/Config.wtf` receives `specular=0` and `projectedTextures=1`; unrelated bytes
and the original root proxy remain intact. Source paths and metadata are audited
after cleanup. This is explicitly **not a second full client hash** or protection
against hostile native code. Post-session runtime verification remains a full hash.

From code 50, a first owned guarded tracer creates the fresh Wine prefix with
empty client bindings, then fully exits before client verification. A second
owned tracer reuses that completed prefix and starts a headless 1280x720 display.
The x64 observer starts original `Wow.exe -windowed`. Its unchanged
x86 DLL owns the x64 host launch using the original Windows process ID and Utils
directory. The observer never starts the original host independently. It reads
only the first 64 mapping bytes, checks magic/protocol/channel bounds, and checks
the host image and Windows parent process. It observes both alive for 15 seconds,
requests client window closure, uses its owned client handle for forced closure
if required, and checks host exit after client loss. Wine and the display are
then stopped; native ownership cleanup precedes either lease's release.

The `clientOriginalStartup` export records fixed numeric event names, stage
statuses, verification/cleanup flags and bounded progress. It does not export
private configurations, Windows process IDs or raw guest output. Success requires
all five stages, the full event contract, owned cleanup, the client verification,
source metadata consistency and the post-session runtime hash. Rendering and
login remain explicitly unqualified even when this startup gate passes.

The ARM64 Linux CI gate uses **synthetic** x86 client/x64 host executables to
exercise the observer's real process/mapping APIs and cleanup. It supplies no
original game files and cannot qualify original-client behavior on Android.
Android unit tests cover settings byte preservation, lease lifetime, early
rejection, source mutation and strict transcript acceptance. The physical test
is one tap on **Test original client startup**, followed by diagnostic export.
Keep airplane mode enabled and the launcher visible; no game window is presented
in this diagnostic. Do not repeat previously passed import/runtime/storage gates.

## Native memory confirmation from code 49

Code 48 completed client/view preparation but stopped during prefix startup on
`TRIM_MEMORY_RUNNING_CRITICAL` with substantial available memory. Android
[documents that callback](https://developer.android.com/reference/android/content/ComponentCallbacks2.html)
as a critical resource warning; high global available bytes alone do not prove
it was false. Code 48 did not sample PSI during native execution. The report
shows controlled cleanup, not an attempt-matched Android kill.

Code 49 samples ActivityManager memory and bounded `/proc/pressure/memory`
once per second throughout native execution, sharing the I/O sampler. Kernel
[PSI documentation](https://www.kernel.org/doc/html/v6.6/accounting/psi.html)
describes some/full as task stall fractions, not a free-memory reading or a
proof of per-app safety. The thresholds below are launcher policy, not Android
or kernel-prescribed limits.

- System lowMemory, available memory below the unchanged floor, PSI some >=10%
  or full >=1%, `onLowMemory`, and callbacks >=80 stop immediately.
- A running-critical callback (15..19) gets at most ten seconds from the first
  callback in its observed continuous episode only while available bytes are
  >=max(existing floor, total/3), measured PSI some <2% and full <0.2%.
  Missing/invalid PSI or reduced headroom refuses this allowance.
- A later milder callback cannot erase a critical/severe callback. Repeated
  critical callbacks cannot move the episode deadline. An isolated expired
  signal can recover; sustained critical signals stop. Existing advisory-only
  grace remains limited to 30 seconds.

No Java-monitor sleep pretends to pause native allocations. Stop, periodic
sampling, process ownership and source checks remain active. Numeric grace
counts/max duration and live PSI are exported. This is bounded confirmation,
not a guarantee against LMK or unmeasured cgroup pressure. Original-startup,
rendering and login qualification still require their actual device evidence.

## Prefix preparation before client verification from code 50

The code-49 report measured 1.07 GB of generated prefix files before its controlled
memory stop. PSI full 0.36% exceeded the 0.2% ceiling for critical grace. Cache
pressure from rapid prefix growth is a hypothesis; no threshold is relaxed.

Within the exclusive runtime lease, full runtime prehash now precedes Wine
preparation, **before** the long client hash and copied settings. This invocation
binds empty disposable source/view folders, never the imported client. Success
requires CPU topology, display, prefix and Wine Stop stages, a bounded prefix
receipt and registry/binary checks. Its owned tracer must be reaped before
returning. Failure skips the client hash, original process launch and view copy.
The attempt directory is retired only after all acquired native handles exit.

A Python worker opens only descriptor-relative entries below the disposable
prefix, with NOFOLLOW, NONBLOCK and regular-file/single-link checks. It skips
symlinks, hard links and special files; never reads payload contents or rewrites
files. Changed files up to 128 MiB receive fdatasync followed by DONTNEED advice.
Traversal is bounded by 10,000 entries and depth 16; unchanged inode/size/time
records skip repeat requests. Errors are counted. The worker is stopped before
the preparation pass marker; an unjoined worker cannot qualify setup. The native
owner remains responsible for terminating all descendants on cancellation.
Accepted calls **do not establish actual cache eviction**. The Linux
[cache-advice contract](https://www.man7.org/linux/man-pages/man2/posix_fadvise.2.html)
recommends syncing dirty data before requesting DONTNEED; the syscall remains
advisory. [Python documents](https://docs.python.org/3.11/library/os.html)
directory-descriptor traversal and descriptor-relative opens.

The second guarded invocation requires the prepared prefix/receipt and exact
staged observer bytes. It does not rerun wineboot. Client lease, full client
verification, immutable guard, settings preservation, source audit and full
runtime posthash remain mandatory. Already allocated prefix bytes are deducted
from remaining space reservations, with the same 3 GiB prefix/1 GiB free-space
limits. Memory policy, 256 MiB settings ceiling and 128 MiB growth allowance are
unchanged. Exported prefix stages, exit/cleanup flags and four cache counters
contain no file names or raw guest output. Success requires both invocations.

The ARM64 fixture gate performs real separate prefix/startup invocations, checks
prefix cache advice was exercised, and fully rehashes the runtime between and
after them. Synthetic client/host ownership checks still do not qualify original
WarcraftXL, rendering or login. One combined device test remains necessary.

## Bounded fault observations from code 51

Code 50 completed prefix preparation and full source verification but its original
client process exited early with **0xC0000005**. Microsoft defines this as an
[access violation](https://learn.microsoft.com/en-us/shows/inside/c0000005).
No faulting instruction/module or access parameters were saved, so that code
alone cannot identify a specific game, proxy, WarcraftXL, Wine or FEX defect.

Code 51 enables `trace+seh`, `trace+loaddll` and PID headers for the original
startup command only. Prefix preparation and memory policy remain unchanged.
The existing observer privately identifies its owned Windows client PID and early
exit/elapsed time; the streaming parser correlates only that process. Neither PID
nor raw Wine output reaches Android reports. No debugger is attached and no game
code, DLL, import or settings are rewritten.

The parser follows Wine 10.13's [exception trace](https://github.com/wine-mirror/wine/blob/wine-10.13/dlls/ntdll/exception.c),
[module trace](https://github.com/wine-mirror/wine/blob/wine-10.13/dlls/ntdll/loader.c)
and [PID header](https://github.com/wine-mirror/wine/blob/wine-10.13/dlls/ntdll/unix/debug.c).
It reads only bounded PE headers from verified fixed images, then matches exact
allowlisted load paths and ranges. Role IDs: 0 unknown, 1 Wow.exe, 2 WarcraftXL.dll,
3 root d3d9.dll, 4 x86 ntdll, 5 x86 kernelbase, 6 x86 kernel32, 7 x86 wined3d.
Unknown paths, unknown replacements, unloaded ranges, range overlaps and missing
headers cannot acquire a named role. Module/RVA capture occurs at exception time.

Limits are 4 KiB per line, 32 process states before private PID correlation, 128
known module ranges per process, 64 fixed event records and 256 KiB retained raw
prefix. Streaming event/marker capture continues past that raw prefix cap.
Oversized lines and state overflow are explicit, and incomplete traces cannot
claim an available location candidate. The strict Android validator requires
all fixed fields, integer ranges, access-kind/range/exit consistency and no extras.
The report exports numeric exception/access addresses, flags, module base/size/RVA,
fixed loaded-client-module bits, counts and elapsed exit time. No stack memory,
register dumps, file names outside the fixed role list or configurations escape.

The **last observed** access violation can have been handled before a later exit.
Matching exception and exit codes therefore do not prove fatal causality; the
export always sets `fatalExceptionLocationProven=false`. Trace observations never
promote original-startup/rendering/login qualification. The synthetic ARM64 test
adds a third invocation that deliberately writes to a PAGE_NOACCESS page in its
PE32 Wow.exe fixture; it must report the correct write/module/RVA and remain a
failed startup, with Wine/native cleanup and source/runtime integrity preserved.
Only that explicitly selected synthetic fixture retains a bounded raw trace in
its CI artifact; the production `--original` mode never saves raw Wine output.

The next device gate remains one combined original-startup attempt and export.
It collects the missing crash location; code 51 is not a demonstrated crash fix.
