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

## Current work — code 36 managed client import

Recovered remote **f0df995c35de5c4a8d380346d23af58cfa47cda4** and exact
[CI 40161313683](https://github.com/Russianranger/fta-launcher/actions/runs/40161313683):
all three jobs passed. Physical **code 35 inspection passed** in 10,161 ms.
Report SHA256 `5307e28695edd7dffeb2723dd1241433cbaf41c2071cd6a8cd51c9dffd91f932`.
[Measured inspection](evidence/android-2026-10-25-thor-code35-client-inspection.json):
10,476 files, 1,175 directories, 60,243,212,332 bytes, 33 MPQs, zero unknown sizes;
matching x86 Wow.exe 3.3.5.12340, x86 proxy/WarcraftXL/DXVK and two x64 executable
candidates. Exact host identity and pairing remain open. The native world receipt
still qualifies. No repeat inspection/server/imported-game-data test is needed.

`0.13.1-client-import`, code 36, implements [resumable managed import](client-import.md):
read-only source, 256 KiB buffers, verified 16 MiB checkpoints, space reserve,
separate foreground worker, explicit Stop, atomic generation publication and full
retained rehash. Source configurations/MPQs stay unchanged. New candidate failures
retain the active generation. All native/server inputs remain unchanged.
Application commit **7f2c59df3579044f70777f7d2f5382f088257e46**; all three jobs in
[CI 40208480192](https://github.com/Russianranger/fta-launcher/actions/runs/40208480192)
passed. 186 tooling tests; Android 437 passed/one existing private-fixture skip,
including 32 new import regressions; lint, assembly and cache-helper checks pass.
Private APK **59,061,517 bytes**, SHA256
`8feb0cff4bf08ae740be0a038c991e80ce3d9c04eb749a0af60968366baeca8a`.
[Verified APK, source and test procedure](code36-recovery.md). Persistent signer,
v2 signing, 16 KiB alignment and all 225 packaged checks pass. All 21 native
payloads and 148 runtime entries remain byte-identical to code 35 / passed code 34.
No native rebuild; saved donor/signing/source artifacts were securely recovered.

Next physical gate after delivery: import the same complete client folder once,
keep visible, reopen and Verify imported client, then Export diagnostic report.
Export before retrying on failure. Roughly 61.32 GB free internal storage is needed
for the initial copy; no duplicate full-client staging copy is created. Actual
managed SAF import/retained rehash, WarcraftXL host pairing, Wine/translator IPC,
client execution and playable login remain unqualified. Keep both originals and
retained server data. No source-backed rule is loosened to make import pass.

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
passed. Client import, WarcraftXL and playable login are later milestones.
Deferred Attriboost Settings field 8 vs 9 bug remains gameplay work. No private
binaries, assets, SQL, credentials or player data may enter Git.
