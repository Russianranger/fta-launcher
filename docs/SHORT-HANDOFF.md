# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. Owner authorizes qualified
implementation, commits and pushes. AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). Historical detail:
[DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Installed private build and passed physical gates

Latest installed APK: **37 / 0.13.2-client-memory**, 59,073,805 bytes, SHA256
`105bf65924ebabedb7dde71c2e3f880e463180a45ccf1fd4f7a6fe14b061cf0b`.
[Verified APK/source recovery](code37-recovery.md). Application commit
`39516bb1b0a499d84279ef9eefea0c5c9e9abcf4`, all three jobs in CI 40216989093 passed.
All 21 native payloads and 148 runtime entries remain the passed native code-34 set.
New **38 / 0.14.0-client-probe** is delivered for the next gate; physical results
are pending. [APK, SHA256, source companions and test procedure](code38-recovery.md).

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
uses the existing 24 MiB/s and memory-pressure governor, Stop, a 90-minute limit,
visible-activity binding and redacted attempt-matched exit reporting.

Delivered application commit: **e22f9b563b5f532da6cb5245f382c31039a8f8aa**.
All foundation jobs **40424860004** passed: 205 tooling, 73 qualification,
515 Android tests passed / one existing skip, lint/assembly, cache/JNI, packaged
checks and full 96 MiB-heap runtime installation. ARM64 workflow **40424860026**
passed the ten-stage synthetic program through the exact Android bind layout,
full runtime pre/post rehash, and normal/Stop/forced-tracer-death ownership gates.
[Exact host evidence](evidence/host-2026-10-27-client-runtime-path-pass.json).
Both Stop and SIGKILL terminate all three tracees/two detached session leaders.
These remain Linux results, not Thor. The earlier empty-prefix UID mismatch is
fixed only in the disposable CI setup; original Wine's checks remain unchanged.

The Android driver uses the existing native handle bridge and patched PRoot's
mandatory PTRACE_O_EXITKILL. Both PRoot outputs are ARM64/16 KiB, rebuilt with NDK
r28c and one job. The driver requires ownership gates before Wine and full post-
rehash before success. Memory pressure stops the tree. Bytecode writes are disabled;
prefix, temporary files and caches are separate binds. No managed client or realm
path is mounted. At most three private attempts are retained, with 2 GiB free
required before each. Original client/host/graphics and login remain unqualified.

**Private APK 38:** 60,139,725 bytes, SHA256
`32f4480d11e8af20402d2db7f679cbe1f36b9601c07338bd280cf3610e7907e2`.
Original certificate, v2 and 16 KiB alignment verified; 235 packaged-input checks
passed. All 21 donor payloads/148 runtime entries are unchanged; two separately
verified PRoot payloads bring the native total to 23. [APK proof](evidence/android-2026-10-27-private-client-probe-apk.json).
[All delivered artifacts and sources](code38-recovery.md) are durably recoverable.
The refreshed v15 companion preserves every original archive/notice and build
proof; the separate exact bridge companion contains new source/patches/notices.
`tools/refresh_world_session_source.py` has two targeted regressions (207 tooling
tests now pass locally); it changes no retained native build input.

## Next concrete gate and recovery rules

**Await the code-38 physical report.** Install the private update in place, allow
6 GiB free, keep the launcher visible, Prepare client runtime once, Test client
runtime once, export the report. On failure export immediately before another
attempt. Do not repeat client import/full rehash, server-data import,
database/auth or the completed world/screen-off gate. Do not uninstall or clear
storage. The new gate does not execute original game files or open the realm.

Original WarcraftXL/client execution requires a later separate writable view
under the verified client lease: no writable hard links, original mutations or
unbounded 60 GB copy. Deferred Attriboost Settings field 8 vs 9 remains gameplay work.

Private updates must retain the signing capsule
`FTA-Launcher-Development-Signing.zip`, `libfile_7f9c88258f0c8191a09ab071e7447d30`.
Certificate `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Never regenerate keys, expose credentials or substitute the public CI APK.
Future updates must use code >=39 after this delivered code 38.

Native cache/source recovery: [code32 baseline](code32-recovery.md), followed by
reviewed 0019 seller-count patch/v15 relinks from [code34](code34-recovery.md).
Restore pinned NDK/timestamps and inspect actual source/build inventory before
Ninja; a cache directory name is not evidence of its version. Native builds use
one job by default/max two after memory checks; never concurrent heavy builds.
