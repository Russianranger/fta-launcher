# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. The owner authorizes autonomous
development and pushes. Target: AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). History: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current delivery and next test

**42 / 0.15.0-storage-isolation** is delivered with the original persistent signer.
[APK, sources and test procedure](code42-recovery.md). APK: 60,291,597 bytes,
SHA256 `e86336eafd6a5e49733e88033024db8e9c8c6b0d5fdaec4207eba30e3d8b1bd5`.
[Packaging proof](evidence/android-2026-10-28-private-storage-isolation-apk.json):
all 23 donor native payloads / 148 runtime entries unchanged; two separate native
additions, 25 payloads total, 239 checks, v2 signing and 16 KiB alignment passed.

Application **29008511b1860c07a01b58b0b799e6b06d600165** passed foundation
**40614566601**: 212 tooling, 93 qualification, 540 Android passed / one existing
skip, lint/assembly, ownership/cache/JNI and runtime rehash with a 96 MiB heap.
Guard workflow **40614566581** passed all 50 fixture checks on x86-64 and ARM64,
unchanged trees and seven invalid-root refusals each. [CI proof](evidence/host-2026-10-28-storage-isolation-pass.json).

**Physical storage gate passed — no repeat needed.** The owner-supplied Thor report
matches the exact APK and three pinned payloads. [Assessment](evidence/android-2026-10-27-thor-code42-storage-pass.json):
attempt `280ff792-9887-4b12-9e3b-d43f15a12084`, **704 ms**, all **50** checks zero,
all four invalid-root launches refused, 65,545 fixture bytes, unchanged protected
tree, owned handles reaped, worker inactive. No attempt-matched exit; all three
historical exits predate the attempt. It did not access the import or run Wine.
The old code-41 runtime report and code-37 client verification are still present.
Keep code 42 and all data. No new device test is requested until the next concrete
development gate is ready; do not repeat storage/import/runtime/server tests.

## Physical client-runtime gate passed — no repeat needed

[Exact reported pass](evidence/android-2026-10-27-thor-code41-runtime-pass.json),
attempt `d23ae710-c265-4c2a-946f-b89dcf7b902f`, matches the delivered APK and pinned
runtime. All ten stages returned zero: CPU topology, display, prefix, registry,
x86/x64 entry, two/sixteen-channel IPC, owner exit and Wine Stop. Full pre/post
rehash covered all 14,092 entries / 2,637,799,241 file bytes. Normal, Stop and
forced tracer-death cleanup passed, `probeCleanupComplete=1`, worker inactive,
no attempt-matched exit. An older low-memory exit predates this attempt.

Elapsed **442,250 ms (7m22s)**; prefix **101,911 ms**; I/O pacing **160,353 ms**.
Two I/O pressure pauses recovered, totaling **79,000 ms**. Last available memory
9,667,428,352 bytes; launcher peak RSS 203,888 KiB (not total guest RSS).
Native advisory count was zero, so this run did not exercise the new advisory
allowance. Last trim level was critical (15), expired by completion. Do not
infer that code 40's missing trigger was advisory, or claim cache eviction.
`rpcss-unavailable` was observed during initialization; all subsequent gates
passed. It is not a demonstrated original-client compatibility result.

**Preserve the code-41 result and all retained data; code 42's storage test also passed.**
Do not repeat import, client/runtime verification, synthetic probes or passed
server tests. Original WarcraftXL execution, rendering and login remain unqualified.

## Active development: isolated original-client working view

[Next gate and acceptance criteria](client-working-view.md). Preserve the full
import, its catalog, original host/proxy and Windows-PID launch contract. Ordinary
PRoot bindings are not read-only protection. Do not launch Wine against the import,
use writable hard links, infer safe sharing from symlinks, or silently copy 60 GB.
Implement and qualify the storage isolation before exposing original-client startup.
The separately pinned guard passed its native Linux and physical Android fixture.
[Storage-isolation design](client-storage-isolation.md); APK/source packaging and
exact-commit CI are complete. Next qualify guarded Wine PE32/PE64 file access
and implement a bounded working
view under the verified-client lease. The old bridge is unchanged; this is
cooperative integrity protection, not hostile-code confinement. Original working
view, WarcraftXL startup, rendering and login remain unqualified.

The runtime assessor binds exact APK/version, archive/catalog/counts, all ten zero stages,
ownership/cleanup, inactive terminal worker, full pre/post rehash and exit history.
Only typed allowlisted observations are saved. Report authenticity and device files
have not been independently read. [Assessment details](client-runtime-report-qualification.md).
The new storage assessor binds the exact APK/payload inventory, all 50 integer-zero
checks, four root refusals, terminal cleanup and exit history. Nine regressions
reject incomplete/contradictory receipts and keep its qualification limited to
the disposable fixture. No APK or native code changed to record this pass.

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
APK. Future updates use code >=43. [Code-42 recovery](code42-recovery.md) identifies
the exact bridge source and refreshed v15 server source; both passed the strict
packaging verifier. The code changes and measured evidence are pushed.

Native recovery: [code32 baseline](code32-recovery.md), then the reviewed 0019
seller-count patch/v15 relinks from [code34](code34-recovery.md). Restore pinned
NDK/timestamps and inspect actual sources/build inventory before Ninja. Native
builds use one job by default/max two after memory checks; never concurrent
heavyweight builds. Do not rebuild unchanged native payloads for Java/Kotlin fixes.
