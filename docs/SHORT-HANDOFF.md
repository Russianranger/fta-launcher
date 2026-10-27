# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. Owner authorizes qualified
implementation, commits and pushes. AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). Historical detail:
[DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Recovery checkpoint — 2026-10-27

Recovered at 14:06 UTC (09:06 CDT). GitHub reports zero running and zero queued
workflow runs. Code-39 application CI **40444054488** and ARM64 runtime CI
**40444054525** completed successfully. The documentation-only head `b989c06`
also passed all three foundation jobs in **40445176642**, attempt 2; no test
needs resuming. Exact job logs confirm 207 tooling and 77 qualification tests,
521 Android tests with 520 passed / one existing skip, and both ARM64 synthetic
success markers.

Recovered the original [code-39 APK and bridge source](code39-recovery.md), checked
their complete SHA256/size identities and ZIP integrity, and independently
matched all 23 packaged native payload hashes to the measured APK proof. This
recovery changes documentation only; it does not rebuild, resign or increment
the APK. The previous signing/alignment evidence remains applicable to these
byte-identical APK bytes. See [recovery evidence](evidence/recovery-2026-10-27-code39.json).

The owner has renewed permission to continue development and push to `main`.
The next physical gate remains the single code-39 runtime probe below. On receipt,
check exact APK identity, inactive completed worker, socket/display stages, all
ten synthetic stages, owned-process cleanup, full pre/post runtime rehash and
attempt-matched exits. A failure should drive the next targeted repair; a pass
advances to the isolated writable client view and original WarcraftXL launch.
Do not infer original client execution or game login from the synthetic pass.

## Installed private build and passed physical gates

Last passed client-import APK: **37 / 0.13.2-client-memory**, 59,073,805 bytes, SHA256
`105bf65924ebabedb7dde71c2e3f880e463180a45ccf1fd4f7a6fe14b061cf0b`.
[Verified APK/source recovery](code37-recovery.md). Application commit
`39516bb1b0a499d84279ef9eefea0c5c9e9abcf4`, all three jobs in CI 40216989093 passed.
All 21 native payloads and 148 runtime entries remain the passed native code-34 set.
**38 / 0.14.0-client-probe** first failed at
display startup with status 124, without an attempt-matched Android exit.
[Measured failure and code-39 repair](client-socket-repair.md). **39 / 0.14.1-client-sockets** is installed. Its repeat passed sockets/display and ownership,
then timed out at the 180-second Wine prefix stage. [Measured failure and code-40 candidate](client-prefix-initialization.md).
[Verified APK, source companions and procedure](code39-recovery.md). [APK, SHA256, source companions and test procedure](code38-recovery.md).

**Client integrity fully passed on 37.**
[Import](evidence/android-2026-10-25-thor-code37-client-import-pass.json):
10,476 files / 60,243,212,332 bytes, 2h40m05s, 4,898,874,959 bytes resumed.
[Retained full rehash](evidence/android-2026-10-25-thor-code37-client-retained-pass.json):
same complete counts and catalog, 55m10s, peak reported RSS 186,548 KiB.
Neither attempt has a matching Android exit. Catalog
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
Old unmatched low-memory exits remain historical. Zero pressure pauses means
forced-pressure backoff and actual cache eviction remain unmeasured.

**Complete native world gate passed on 34.**
[Physical proof](evidence/android-2026-10-25-thor-code34-world-pass.json), attempt
`9e7570a8-23db-4a23-ab82-c44cbf360a02`: all 23 checks true; original native database
fallback, 113 DBC/8,922 GT rows, world readiness, 307,424 ms healthy session,
259,192 ms healthy screen-off, controlled world/auth/database Stop with exit zero,
SQL shutdown, closed listeners and retained metadata postchecks. No seed imported.
All 52 source modules, C++20 and NPCBots preserved. Game login remains unqualified.
[Private code-34 recovery](code34-recovery.md) contains exact APK/source identities.

All 22,817 server assets / 7,625,943,031 bytes passed import and full rehash.
Original Data.zip SHA
`407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`;
catalog `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Database/updater, realm reuse, backup/restore, process-death recovery and local
auth/Stop/restart already passed. **Do not repeat these physical gates.**

## Current client-runtime development

Original host/DLL/proxy identities and the isolated original header contract are
pinned in [warcraftxl-host.md](warcraftxl-host.md). The full 1.1 GB client source
is not needed for this synthetic gate. Preserve the original Windows-PID launch
contract; original client/host execution, graphics and gameplay remain unqualified.

The unchanged public Wine/FEX archive is pinned in
`android/qualification/client-runtime-lock.json`: 14,092 entries / 2,637,799,241
file bytes. [Installation](client-runtime-installation.md) now has authenticated
extraction ranges, <=1 MiB sync/readback windows, delayed links, OS lease, atomic
publication and complete retained rehash. Full-candidate host installation and
both rehashes pass with a 96 MiB JVM heap. The separate Android foreground worker
uses the 48 MiB/s and unchanged memory-pressure governor, Stop, a 90-minute limit,
visible-activity binding and redacted attempt-matched exit reporting.

Latest delivered application commit: **51358069f8d958c5e05b343cd6bf1d22be4f13f9**.
Foundation **40444054488** passed all three jobs: 207 tooling, 77 qualification
(no skips), 520 Android tests passed / one existing skip, lint/assembly,
cache/JNI, packaged checks and full runtime install/rehash under a 96 MiB heap.
ARM64 **40444054525** passed the old-path failure control (118-byte socket,
EINVAL 22), short-path success, all ten synthetic stages, process ownership and
complete runtime pre/post rehash. [Exact proof](evidence/host-2026-10-26-client-socket-repair-pass.json).
These are Linux results. The corrected display and ownership now pass on Thor;
the complete mixed-runtime gate remains blocked at prefix initialization.

Code 38's physical probe reached CPU topology 0 and display 124 after 196,339 ms,
including 130,011 ms deliberate pacing, about 9 GB available and no runtime exit.
Its old long directory also makes PRoot's original socket fallback too long.
Code 39 uses the short `files/cr/<UUID hex>/tmp` path, checks both pathname byte
budgets, preserves/counts legacy attempts, and exports a real socket preflight,
display exit/timeout categories and completed ownership/pre-rehash evidence.
Aggregate client I/O is now 48 MiB/s; buffers, dirty windows and memory/PSI/trim
backpressure remain unchanged. Faster throughput/pressure behavior is not yet
physically qualified. [Repair and evidence limits](client-socket-repair.md).

**Private APK 39:** 60,147,917 bytes, SHA256
`63b42422a083f3a0e866c6d9df7be1e0048a111b21637ea3dd63a81022d8b0f5`.
Original certificate, v2/16 KiB alignment and 235 packaged checks passed.
All 23 donor native payloads and 148 runtime entries are unchanged. The new
bridge source companion matches this exact application commit; the unchanged
v15 native source companion from code 38 remains valid. [All recovery links](code39-recovery.md).
[APK proof](evidence/android-2026-10-26-private-client-sockets-apk.json).

## Next concrete gate and recovery rules

**Qualify and deliver code 40 / 0.14.2-client-prefix.** [The implementation](client-prefix-initialization.md)
uses fresh-prefix initialization with a 600-second cold-start budget, bounded
progress and late diagnostic categories, and fixes waiting on a completed
command's inherited output pipe. The full synthetic success criteria, prepared
runtime, I/O/memory controls and all original native bytes remain unchanged.
Exact-commit CI and private packaging are pending. After qualified delivery,
repeat only **Test client runtime**, keeping the launcher visible, then export
immediately on failure before retrying. No Prepare/download, client or Data.zip
reimport, or completed database/auth/world/screen-off tests. Do not uninstall or
clear storage. A longer deadline is a candidate mitigation; slow initialization
versus a persistent Wine hang was not distinguishable in the code-39 report.

Original WarcraftXL/client execution requires a later separate writable view
under the verified client lease: no writable hard links, original mutations or
unbounded 60 GB copy. Deferred Attriboost Settings field 8 vs 9 remains gameplay work.

Private updates must retain the signing capsule
`FTA-Launcher-Development-Signing.zip`, `libfile_7f9c88258f0c8191a09ab071e7447d30`.
Certificate `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Never regenerate keys, expose credentials or substitute the public CI APK.
Future updates must use code >=40 after this delivered code 39.

Native cache/source recovery: [code32 baseline](code32-recovery.md), followed by
reviewed 0019 seller-count patch/v15 relinks from [code34](code34-recovery.md).
Restore pinned NDK/timestamps and inspect actual source/build inventory before
Ninja; a cache directory name is not evidence of its version. Native builds use
one job by default/max two after memory checks; never concurrent heavy builds.
