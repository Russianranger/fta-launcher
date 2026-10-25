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

## Current work — code 37 client memory backpressure

Recovered actual remote **577f6c7d4dc3abf776f25721618ab9bdade1e443**; all three jobs
in [CI 40209149566](https://github.com/Russianranger/fta-launcher/actions/runs/40209149566)
passed. Code 35 inspection passed: 10,476 files / 60,243,212,332 bytes, correct x86
3.3.5.12340 client, 33 MPQs. Exact x64 host pairing remains open.

Physical code 36 was interrupted during copy at **4,865,320,527 bytes**, 12 complete
files, 97,164 ms, peak RSS 154,576 KiB. Owner reports low-memory termination. Report
SHA256 `c18a50203f6570cec310cd9dc4f811dc1ac64e385a57160e8531c0c7dcf69f85`.
[Measured interruption](evidence/android-2026-10-25-thor-code36-client-interruption.json).
No code-36 client exit/memory-pressure history exists; the old runtime exit in the
report does not match this client attempt. No completed client import/rehash.
Native world qualification remains true; do not repeat server/DB/auth/game-data tests.

`0.13.2-client-memory`, code 37, adds [I/O backpressure](client-import.md): 24 MiB/s
aggregate payload I/O (about 8 MiB/s new copy), 1 MiB sync/readback/cache windows,
pressure pause with hysteresis and a two-minute safe-stop limit, and attempt-bound
client exit/memory observations. Old 16 MiB checkpoints are unchanged and resumable.
Full integrity, source files, all native payloads and existing world receipts stay
unchanged. Application CI and private APK delivery are being qualified; no new APK
identity is claimed yet. Original donor/signing/source artifacts recovered and
measured against code-36/34 evidence; no native rebuild needed.

Next physical gate after qualified delivery: install in place, choose **Import /
resume client folder** with the SAME original tree, keep visible, allow prefix
verification and slower copy; reopen, **Verify imported client**, export JSON.
Export on safe stop/failure before resuming. Do not delete the partial copy, clear
storage or reimport server data. Memory-pressure survival and complete managed
import remain unqualified; WarcraftXL/Wine IPC and gameplay remain later gates.

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
