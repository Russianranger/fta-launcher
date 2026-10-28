# Next gate: isolated original-client working view

Passed physical prerequisites: managed client import and full rehash
on code 37, retained native world/session/Stop on code 34, and the ten-stage mixed
runtime/ownership/pre-post integrity gate on code 41. Preserve those results.
The bounded working-view core is implemented for disposable fixtures; integration
with the original client remains unimplemented. Code 43 passed its 56 Windows
checks but failed final verification. Complete [code 44's retained-evidence
recovery](client-guarded-finish.md) before advancing the physical gate.

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
view and guarded PE32/PE64 access passed native ARM64 Linux. Final verification
of the Thor attempt remains pending; the original-client view is the next
implementation after that gate completes.
No repeats of the passed storage/runtime/import/server gates are requested.
