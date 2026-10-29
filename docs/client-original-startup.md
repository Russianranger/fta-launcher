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

One owned guarded tracer starts a fresh Wine prefix and a headless 1280x720
display. The x64 observer starts original `Wow.exe -windowed`. Its unchanged
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
