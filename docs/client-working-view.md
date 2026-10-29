# Isolated original-client working view

Passed physical prerequisites: managed client import and full rehash
on code 37, retained native world/session/Stop on code 34, and the ten-stage mixed
runtime/ownership/pre-post integrity gate on code 41. Preserve those results.
The bounded working view is integrated into code 46's
[original-client startup diagnostic](client-original-startup.md). Physical
original-client startup remains unqualified. Code 43 passed its 56 Windows
checks but failed final verification. Code 44 identified access denial;
[code 45's retained-evidence and bind-placeholder recovery passed on Thor](evidence/android-2026-10-28-thor-code45-guarded-finish-pass.json).
It removed both matching empty directories and fully verified the runtime, with
the saved Windows evidence unchanged and no Wine rerun. No repeat is needed.

The imported client contains 10,476 files / 60,243,212,332 bytes and remains an
inert integrity baseline. Its original configurations, binaries, MPQs and modern
assets must not be modified by Wine, the game or the asset host. A cooperative
Java lease excludes launcher operations but does not impose read-only access on
guest processes. A symlink or a PRoot binding still exposes its target; neither
provides copy-on-write isolation. PRoot's [read-only binding request](https://github.com/proot-me/proot/issues/123)
remains an open feature request. Do not assume `-b` enforces it.

Before exposing an original-client action:

1. Select and qualify actual storage isolation on the device filesystem. A
   filesystem clone may share storage safely when supported, but support must
   be measured; an unsupported clone must never silently fall back to a full
   60 GB copy. Shared imports require enforced write protection, not only naming
   conventions. Reject writes, truncation, rename, unlink and permission changes
   through aliases as well as direct paths, or use independent copies.
2. Budget every independently copied byte before writing, preserve a reserve,
   use the existing bounded I/O/memory governor and crash-safe publication, and
   retain the verified client lease until all child readers have ended. Bound
   mutable logs, caches and prefix growth; Stop must preserve the baseline and
   leave a recoverable working generation. No writable hard links.
3. Preserve all client files and path casing, including `Utils/WarcraftXLHost.exe`,
   root `d3d9.dll`, WarcraftXL configuration, modern assets and nested backend.
   Apply source-required launch settings only to the isolated configuration copy;
   never reset the owner's imported configuration or replace the root proxy.
4. Test source immutability under writes through files, directories, links and
   failure cleanup. Include low space, cancellation/process death and partial
   publication. A successful setup receipt must describe measured protection and
   byte/storage costs, not claim it from a prior synthetic Wine pass.
5. Then launch the original Wow.exe under one owned Wine prefix. The original DLL
   launches its x64 host with its own **Windows** client PID and Utils working
   directory. Do not start the host separately or substitute an Android PID.
   Qualify original startup/IPC/Stop before rendering, input, account/login and
   world-entry gates. The synthetic protocol is not an original asset request.

The [code-42 disposable storage-isolation gate](client-storage-isolation.md) now
implements a separate guarded tracer and native canary fixture. Native x86-64 and
ARM64 checks passed, followed by all 50 physical Thor checks in 704 ms. It does not
expose the import or qualify guarded Wine/working-view compatibility. The bounded
view and guarded PE32/PE64 access passed native ARM64 Linux. Code 45 completed
the Thor fixture's retained evidence and full runtime verification; the
original-client view and startup are now the next physical gate.
No repeats of the passed storage/runtime/import/server gates are requested.

## Integrated preparation/session boundary

Code 46 wires the bounded view builder to `ClientStore.withVerifiedClient`. Bind its preparation result to
the exact client catalog, generation and view plan while holding the lease.
Use one fresh baseline verification as part of the actual preparation/session;
do not request an extra standalone 60 GB Verify beforehand. The lease must
remain held across view publication and any guest readers, including Stop and
failure cleanup. A later session must reacquire and validate that ownership.

The integrated path is covered by small managed-client fixtures: immutable source bytes, independent mutable copies, correct
guest link targets/casing, copy/reserve bounds, competing store operations,
cancellation, crash recovery and publication failures. Code 46 connects the owned
guarded Wine launch and original DLL/host contract after fresh verification.
The ARM64 host gate exercises the observer with synthetic client/host programs,
while actual original execution remains a separate Thor gate. Code 45 needs no
repeat; code 46 offers the combined preparation/startup diagnostic.
