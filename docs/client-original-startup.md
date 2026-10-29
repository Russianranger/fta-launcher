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
qualified native guard. Mutable files are independent copies, capped at 64 MiB;
there are no writable hard links or full client recopy. Only the view's
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
