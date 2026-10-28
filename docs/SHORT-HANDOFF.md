# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. The owner authorizes autonomous
development and pushes. Target: AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). History: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current delivery and next test

**43 / 0.15.1-guarded-wine** is delivered with the original persistent signer.
[APK, sources and test instructions](code43-recovery.md). APK: **60,525,583 bytes**,
SHA256 `45965d3cc61321e8f293f7a2cde4cf542e50803b38d2fb1c7655b2d24965a3d3`.
[Packaging proof](evidence/android-2026-10-27-private-guarded-wine-apk.json): all
**25** native payloads and **148** runtime entries byte-identical to code 42;
**245** packaged checks, v2 signing and 16 KiB alignment passed. No native payload
was rebuilt for this private delivery. All retained realm/import/runtime data remain.

Application **cdb80d916028151e0211e88a3f942442ebedef0a** passed foundation
**40623960896**: **214** tooling, **105** qualification (no skips), **551 Android
passed / one existing fixture skip** in 51 suites, lint/assembly, ownership,
cache/JNI and full runtime install/rehash with a 96 MiB heap. Guarded Wine CI
**40623960909** passed all **56** Windows checks / six stages on native ARM64
Linux, full runtime pre/post rehash and unchanged protected source inventory.
All 50 native guard checks and seven invalid-root refusals also passed.
[Measured proof](evidence/host-2026-10-27-guarded-wine-pass.json).

**Next physical test: Test protected Wine access, once.** Install over the existing
app, allow **3 GiB free**, keep the launcher visible and export the diagnostic
report after completion/failure/interruption. Return JSON before retrying any
failure. This test uses only disposable files and a fresh Wine prefix; it does
not open the imported game. Its new `clientGuardedWine` report preserves the old
code-41 runtime and code-42 storage reports. Automatic runtime pre/post rehash
belongs to the new gate; do not run separate Verify/Prepare actions.

The working-view core is implemented and exercised by the synthetic gate:
protected guest links, independent writable copies, 64 MiB copy limit,
atomic publication, source metadata consistency, cancellation cleanup and
128 MiB view / 2 GiB prefix growth polling. Android app-directory aliases are
resolved before the strict canonical-root audit. The original import is not yet
wired into a launch action. [Design and limits](client-guarded-wine.md).

Wine reports DeleteFile success although the native guard prevents deletion.
Both architectures' return/error are recorded; `delete-preserved` verifies the
file remains readable with its original size/content, and the entire protected
tree is independently compared after tracees exit. Do not describe this as a
Windows API denial or original-game compatibility. No guard restriction was
relaxed; the exact code-42 native guard is retained.

**Physical storage gate 42 passed — no repeat.** [Assessment](evidence/android-2026-10-27-thor-code42-storage-pass.json):
attempt `280ff792-9887-4b12-9e3b-d43f15a12084`, **704 ms**, all **50** checks zero,
four invalid-root launches refused, 65,545 fixture bytes, protected tree unchanged,
owned handles reaped, worker inactive and no matching exit. It did not run Wine
or access the import. [Code-42 recovery](code42-recovery.md).

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
Do not repeat those passed import, standalone runtime or server tests. The
new code-43 guarded action is a separate gate. Original WarcraftXL execution, rendering and login remain unqualified.

## Active development: isolated original-client working view

[Acceptance criteria](client-working-view.md). Preserve the full import/catalog,
original host/proxy and Windows-PID launch contract. Plain PRoot bindings and
symlinks alone provide no read-only protection. Never use writable hard links,
modify imports or silently copy 60 GB. The separately pinned guard passed its
native Linux and physical Android fixture. Guarded PE32/PE64 access now passes
native ARM64 Linux, and the bounded working-view builder is implemented.

**Await the code-43 Thor report**, assess its exact APK/payload identity, six
ordered zero stages, 56 checks, source/copy observations, ownership/cleanup,
runtime pre/post rehash and matching exit history. Preserve earlier passes.
Then advance the original-client view under `ClientStore.withVerifiedClient`;
retain its lease until every owned reader exits. Actual original-client view,
WarcraftXL startup, rendering and login remain unqualified. This is cooperative
file-integrity protection, not hostile-code confinement.

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
APK. Future updates use code >=44. [Code-43 recovery](code43-recovery.md) identifies
the exact bridge/application source and refreshed v15 server source; both passed the strict
packaging verifier. The code changes and measured evidence are pushed.

Native recovery: [code32 baseline](code32-recovery.md), then the reviewed 0019
seller-count patch/v15 relinks from [code34](code34-recovery.md). Restore pinned
NDK/timestamps and inspect actual sources/build inventory before Ninja. Native
builds use one job by default/max two after memory checks; never concurrent
heavyweight builds. Do not rebuild unchanged native payloads for Java/Kotlin fixes.
