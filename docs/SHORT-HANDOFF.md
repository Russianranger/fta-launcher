# From the Ashes Android — current handoff

Only **Russianranger/fta-launcher/main**. Owner authorizes qualified commits and
pushes. AYN Thor, Android 13, ARM64. Read `AGENTS.md`, `game-data-import.md`,
`private-diagnostics.md`, `native-world-gate.md`. Earlier history:
[DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Complete native world gate passed — physical code 34

Recovered remote/delivery commit **92b483507c20051b604ccbeda33f334283ec6929**;
all three jobs in [CI 40155924517](https://github.com/Russianranger/fta-launcher/actions/runs/40155924517)
passed. Application commit **0885153f71061751bb7e189ed8d8e52479badfcb**, all three
jobs [CI 40155455259](https://github.com/Russianranger/fta-launcher/actions/runs/40155455259).
Private `0.12.7-auction-count`, code 34, APK SHA256
`45ade0fd1cb78c52527f46bfe0c0ac338c40d8948debb838d1bb0db85a6431a8`.
[APK/source recovery](code34-recovery.md).

New [physical proof](evidence/android-2026-10-25-thor-code34-world-pass.json):
report SHA256 `382cf547ae370fc2f012c40528e84ad865e488953f784cc090db41ea16fd485d`,
attempt `9e7570a8-23db-4a23-ab82-c44cbf360a02`. All 23 checks true; all five failure
markers false. Native runtime, original database fallback, world readiness and
screen-off gates are **qualified for this exact build**. 30 healthy receipt/native
samples, 307,424 ms session, **259,192 ms healthy screen-off**. User Stop completed;
owned world/auth/database all exited zero, SQL shutdown observed, listeners closed,
temporary role removed, retained metadata/GT postchecks passed. No seed imported.
World log 1,626,489 bytes, no ERROR/FATAL, two exact COUNT hints, no log-limit hit.
Game login remains false. Do not repeat passed server or standalone DB/auth tests.

## Current work — code 37 client integrity passed; runtime preparation

Runtime-preparation implementation **d5382d5312e7ba5150adfffa397de57aa6045e8b**;
all three jobs in [CI 40254380310](https://github.com/Russianranger/fta-launcher/actions/runs/40254380310)
passed: **473 Android tests / one existing skip**, 41 suites, no errors/failures,
lint/assembly/cache-helper checks, **186 tooling + 24 report tests**. Recovered
remote before this increment was `30e79a7fb734b66d05dc9d15a64a16827e1160f6`.
[Exact qualification](evidence/android-2026-10-25-client-runtime-preparation-ci.json).
Latest installed private APK remains **37 / 0.13.2-client-memory**,
59,073,805 bytes, SHA256
`105bf65924ebabedb7dde71c2e3f880e463180a45ccf1fd4f7a6fe14b061cf0b`.
[Verified APK/source recovery](code37-recovery.md). Application commit
**39516bb1b0a499d84279ef9eefea0c5c9e9abcf4**. All 21 native payloads, 148 runtime
entries and native source companion remain those of the passed native build.

[Import proof](evidence/android-2026-10-25-thor-code37-client-import-pass.json):
report `04f6e4b88c6c8dcf8763e4fa606b37eccb1858efa79ea28109e2abbae040c3e9`,
attempt `cb1d364a-4c5e-4ce0-9a4a-0e7c25fe3e3a`, all **10,476 files /
60,243,212,332 bytes**, copied/read back/published in 9,604,969 ms (2h40m05s),
4,898,874,959 bytes resumed. No matching exit; zero pressure pauses.

New [retained proof](evidence/android-2026-10-25-thor-code37-client-retained-pass.json):
report **98,093 bytes**, SHA256
`8d8ee246c69891ac57d3c03f5bc75e5daeca77fdadf3b98e681aae23f5dfd09c`,
attempt `a8fdcc27-89b7-4fbf-9cc2-a9dd3f8a2c90`, **3,309,796 ms (55m10s)**.
Complete `operation=verify`, full counts/bytes, retainedRehashValidated=true.
[Automated assessment](client-report-qualification.md) matched the import baseline;
**clientIntegrityGateQualified=true**. Same catalog:
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
No matching exit, peak reported RSS 186,548 KiB, zero pressure pauses. The older
unmatched low-memory exit remains historical. Cache eviction and forced-pressure
backoff are not measured. Native-world attempt/qualification remains intact.

[Runtime preparation](client-runtime-preparation.md) now adds a fresh-rehash store
lease for future readers and the original Wine DLL-override transformation.
16 new Android regressions; 52 focused JVM tests passed under 64 MiB heap.
An unchanged-original C++20 probe matches Kotlin across **1,038 cases**. No native
payload, imported file, config, account or installed APK is changed. These helpers
are source-only and not yet called by an executable runtime path. The next private
APK must increase the version code; do not relabel a rebuild as the old artifact.

**Next input:** original WarcraftXL runtime bundle/source matching the inspected
client, including x64 host. The recovered Launcher.zip establishes proxy/config
behavior, but not exact host path/launch/IPC. Two x64 hashes are recorded; do not
infer identity from version 1.8.34.0. No matching bundle was found in recovered
inputs/Library searches. The full 60 GB assets and private configs are unnecessary
for this inspection. Next agent: match supplied component hashes, establish host
pairing, then prepare pinned Wine/translator and a separate writable runtime view
with owned process cleanup. WarcraftXL compatibility and gameplay remain false.

**No physical retest is requested.** Keep installed code 37. Do not repeat import,
retained client rehash, server, DB/auth, screen-off or game-data gates. Preserve
all source files, managed client, realm, native dependencies and backups.

## Recovery and safety

[Code-32 cache/source recovery](code32-recovery.md) remains the v14 build baseline.
Restore its pinned NDK and timestamps before Ninja. Apply reviewed 0019 to the
source and rebuild one seller object plus relinks for v15; do not rebuild the
whole graph unnecessarily. The local cached path may still be named world-v14;
verify its actual v15 source inventory and build report, never infer profile from
its directory name. One job by default/max two after memory check; no concurrent
heavy native builds. Preserve all native link dependencies and old updater pins.

Persistent signing capsule: `FTA-Launcher-Development-Signing.zip`,
`libfile_7f9c88258f0c8191a09ab071e7447d30`. Never regenerate or expose credentials.
Certificate `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Private source, compiled binaries, database archives/configurations and assets
stay outside public Git. Public CI APKs are foundation previews only.

## Preserved milestones

Code 26 passed import/preflight/publication; later sessions fully rehashed all
files. Archive `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`;
catalog `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Host original DBC loaders and restored exact MariaDB loaded all 8,922 GT floats
bit-for-bit, with empty-table negatives and ASan. Physical database/updater, realm
reuse, backup/restore, process-death recovery, local auth and Stop/restart already
passed. Client import and retained rehash passed; Wine/WarcraftXL execution and playable
login remain pending.
Deferred Attriboost Settings field 8 vs 9 bug remains gameplay work. No private
binaries, assets, SQL, credentials or player data may enter Git.
