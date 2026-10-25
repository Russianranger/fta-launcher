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

## Current work — physical code 37 client import passed

Recovered actual remote **9f1c95042ef734175a06712d31538667b6ac526c**; all three jobs
in [CI 40217676223](https://github.com/Russianranger/fta-launcher/actions/runs/40217676223)
passed (457 Android tests / one existing skip, 186 tooling tests, lint/assembly).
Latest private APK remains **code 37 / 0.13.2-client-memory**, 59,073,805 bytes,
SHA256 `105bf65924ebabedb7dde71c2e3f880e463180a45ccf1fd4f7a6fe14b061cf0b`.
[Verified APK/source recovery](code37-recovery.md). All 21 native payloads, 148
runtime entries and the native source companion remain unchanged from the passed
native build. Application commit **39516bb1b0a499d84279ef9eefea0c5c9e9abcf4**.

New [physical import proof](evidence/android-2026-10-25-thor-code37-client-import-pass.json):
report SHA256 `04f6e4b88c6c8dcf8763e4fa606b37eccb1858efa79ea28109e2abbae040c3e9`,
98,172 bytes; attempt `cb1d364a-4c5e-4ce0-9a4a-0e7c25fe3e3a`. All **10,476 files /
60,243,212,332 bytes** copied, independently read back and published; **4,898,874,959
bytes** resumed. **9,604,969 ms** (2h40m05s), peak RSS **186,548 KiB**, zero pressure
pauses, no attempt-matched exit. One older unmatched client low-memory exit remains
historical evidence. Source/destination advice both 67,521 accepted requests;
actual eviction is not measured. The native-world qualification/attempt remains
intact. Code-36 interruption history is retained; do not repeat the completed copy.

Published catalog:
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
`operation=import`, `copyReadbackValidated=true`, **retainedRehashValidated=false**.
This run qualifies the large-copy path and its survival, not forced-pressure
backoff, cache eviction, host pairing or client execution.

Independent continuation adds [automated report assessment](client-report-qualification.md)
with 24 regression cases and a CI step. It binds reports to the exact private APK,
keeps import/retained/runtime gates separate, rejects contradictory or incomplete
receipts, and binds a subsequent rehash to this import's catalog/counts/chronology.
No application or native runtime change; no new private APK is needed.

**Next physical action:** reopen the installed code 37, select **Verify imported
client**, keep visible until complete, then **Export diagnostic report**. On failure
export before another attempt. Do not select Import again, uninstall, clear storage,
or repeat server/DB/auth/screen-off/game-data tests. Next agent: assess that report
against the private APK proof and this import baseline with the documented command.
After retained integrity passes, resolve exact x64 asset-host identity/pairing and
qualify Wine/translator IPC before execution. WarcraftXL and gameplay remain open.

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
passed. Client import now passed; retained client rehash, WarcraftXL and playable
login remain pending.
Deferred Attriboost Settings field 8 vs 9 bug remains gameplay work. No private
binaries, assets, SQL, credentials or player data may enter Git.
