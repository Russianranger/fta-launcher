# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. The owner authorizes autonomous
development and pushes. Target: AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). History: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current delivery and next test

**41 / 0.14.3-client-memory** is delivered, with the original persistent signer.
[APK, sources and test procedure](code41-recovery.md). APK: 60,156,109 bytes,
SHA256 `3e26e4bdeac5bd856b5ad95dd6d957e98d3734e1dcc5c6d4186056c53b104f26`.
[Packaging proof](evidence/android-2026-10-27-private-client-memory-apk.json).
All 23 native payloads and 148 retained runtime entries match code 40 exactly;
235 packaged checks, v2 signature and 16 KiB alignment passed.

Application commit **85a3462dc461577c39849680ea01e5133f9a8c32** passed foundation
**40603687890**, all three jobs. Results: 207 tooling, 82 qualification (no skips),
534 Android tests passed / one existing fixture-dependent skip, lint/assembly,
cache/JNI and full runtime installation/rehash with a 96 MiB heap.
[Exact host/CI proof](evidence/host-2026-10-27-client-memory-pass.json).
The guest scripts, bridge and runtime are unchanged from the ten-stage ARM64
pass on commit fdf7eb5 / run 40587205346; that job was not rerun for this Android
monitoring change. These are host results; code 41 has not yet run on Thor.

**Await one code-41 report.** Install in place, retain all storage, keep the
launcher visible and allow at least 2 GiB free. Select **Test client runtime**
once, then **Export diagnostic report** on either outcome. Initialization can
use up to ten minutes; subsequent checks and full rehash run automatically.
Export immediately on failure before retrying. Do not uninstall, clear storage,
prepare/download again, reimport client/Data.zip or repeat passed server tests.
The synthetic test does not open the game.

## Latest physical failure and repair limits

**40 / 0.14.2-client-prefix** stopped with `memory-pressure`, not a prefix timeout.
[Physical result](evidence/android-2026-10-27-thor-code40-memory-guard.json):
Windows initialization progressed through 75.247 seconds, 789 system32 / 286
syswow64 entries and 1,828,429 registry bytes. Full runtime pre-rehash,
sockets/display and all ownership gates passed. Total elapsed 257,170 ms;
I/O pacing 80,173 ms, one recovered I/O pressure pause of 42,000 ms.
The last successful memory sample was 8,848,064,512 available bytes, lowMemory
false. The aborting sample and trim level were not persisted. There was no
attempt-matched Android exit. Do not infer a low-memory kill or a proven false
alarm from the stale sample.

[Code 41](client-memory-recovery.md) gives advisory-only native trim signals a
30-second continuous ceiling while measured memory remains healthy. Measured
low memory, available-memory floor and critical callbacks still stop immediately.
The trigger sample/category/trim data are saved before teardown. I/O backpressure,
Stop and all guest deadlines remain. A bounded fourth retry slot preserves the
three unproven legacy attempts; new work becomes reusable only after validated
ownership and completed handle cleanup. Uncertain attempts remain intact and
produce `probe-attempt-limit` if the cap is reached. No unproven directory is deleted.

On the next report inspect exact APK, inactive completed worker, all ten stages,
ownership, pre/post full rehash and attempt-matched exits. If pressure recurs,
inspect `memoryPressureSource`, `lastTrimLevel`, `lastTrimAgeMs`, threshold/floor,
available memory, lowMemory/critical flags and advisory duration. A handled native
termination should have `probeCleanupComplete: 1`. Prefix progress alone cannot
qualify initialization. A pass advances to the isolated writable client view and
original WarcraftXL launch; original execution, graphics and login remain unqualified.

## Passed physical gates — preserve, do not repeat

**Client integrity passed on 37.** [Import](evidence/android-2026-10-25-thor-code37-client-import-pass.json)
and [retained full rehash](evidence/android-2026-10-25-thor-code37-client-retained-pass.json):
10,476 files / 60,243,212,332 bytes, catalog
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
Import 2h40m05s with 4,898,874,959 resumed bytes; rehash 55m10s, peak reported
RSS 186,548 KiB. No matching exit. [Code-37 recovery](code37-recovery.md).
Actual cache eviction and forced-pressure backoff remain unmeasured.

**Complete native world gate passed on 34.**
[Physical proof](evidence/android-2026-10-25-thor-code34-world-pass.json), attempt
`9e7570a8-23db-4a23-ab82-c44cbf360a02`: all 23 checks true, original database
fallback, 113 DBC/8,922 GT rows, world readiness, 307,424 ms healthy session and
259,192 ms healthy screen-off, controlled world/auth/database Stop with exit zero,
SQL shutdown, closed listeners and retained metadata postchecks. No seed imported.
All 52 source modules, C++20 and NPCBots preserved. [Code-34 recovery](code34-recovery.md).

All 22,817 server assets / 7,625,943,031 bytes passed import and full rehash.
Original Data.zip SHA `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`;
catalog `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Database/updater, realm reuse, backup/restore, process-death recovery and local
auth/Stop/restart already passed. Never reseed the retained realm.

## Recovery and following development

The verified Wine/FEX runtime remains installed: 14,092 entries /
2,637,799,241 file bytes. [Installer](client-runtime-installation.md): authenticated
extraction, <=1 MiB sync/readback, delayed links, exclusive lease, atomic
publication and full rehash. Client I/O remains 48 MiB/s with existing buffers
and memory/PSI/trim backpressure.

Original host/DLL/proxy identities are pinned in [warcraftxl-host.md](warcraftxl-host.md).
Preserve the original Windows-PID launch contract. Original execution needs an
isolated writable client view under the verified client lease: no writable hard
links, mutation of imports or unbounded 60 GB copy. Deferred Attriboost Settings
field 8 vs 9 is later gameplay work. Full 1.1 GB source is not needed for this gate.

Private updates retain `FTA-Launcher-Development-Signing.zip`,
`libfile_7f9c88258f0c8191a09ab071e7447d30`. Certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Never regenerate keys, expose credentials or substitute the public foundation
APK. Future updates use code >=42. [Code-41 recovery](code41-recovery.md) identifies
the new exact bridge source and unchanged v15 server source; both passed the
strict packaging verifier. The code changes and measured evidence are pushed.

Native recovery: [code32 baseline](code32-recovery.md), then the reviewed 0019
seller-count patch/v15 relinks from [code34](code34-recovery.md). Restore pinned
NDK/timestamps and inspect actual sources/build inventory before Ninja. Native
builds use one job by default/max two after memory checks; never concurrent
heavyweight builds. Do not rebuild unchanged native payloads for Java/Kotlin fixes.
