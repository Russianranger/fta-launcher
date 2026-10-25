# From the Ashes Android — current handoff

Only **Russianranger/fta-launcher/main**. Owner authorizes qualified commits and
pushes. AYN Thor, Android 13, ARM64. Read `AGENTS.md`, `game-data-import.md`,
`private-diagnostics.md`, `native-world-gate.md`. Earlier history:
[DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current increment — code 34 qualification

Recovered remote **f71016941646ac7ddeded2a2a06cba3d00c7be99**; its exact CI
[40152332918](https://github.com/Russianranger/fta-launcher/actions/runs/40152332918)
passed all three jobs. Latest delivered private APK remains code 33 until the
code-34 delivery proof is added. Code 33 and source: [recovery links](code33-recovery.md).

**Code 33 read-only recovery passed**, same code-32 attempt
`d280294e-62b4-4255-82f1-5ca5c14bb80c`, no new native run. Report SHA256
`3c23e419d95637c8f907745bf05fc29332bb667aa66ea74ffb1e3e77e3a1f618`.
[Exact measured evidence](evidence/android-2026-10-25-thor-code33-recovery.json)
is authoritative for the report hash. World log 8,535,490 bytes; first/last 4 MiB
samples omit 146,882 bytes. Tail: 67,108 warnings, 33,554 aggregate-hint matches,
then original Halting. Existing prefix collides between Field.cpp's double and
uint64 hints; no exact Thor caller is proven. No additional recovery export is
needed before the next qualified update.

## Implemented source-backed correction

[Diagnosis, host proof and next gate](auction-count.md). Original recurring
auction snapshot reads COUNT column 4 as uint32, causing two warning lines per
row (three under strict checks), then falls back to uint64. v15 changes only
that expression to an explicit uint32 conversion of Get<uint64>(). Original
query, narrowing/accumulation, Field, all SQL/configurations and all 52 modules
are preserved. v14 shutdown repair remains. Four original/four fixed Release
and ASan/UBSan fixtures pass; boundary counts match and fixed warnings are zero.
Native compilation uses one job and reuses completed objects. Only seller is
recompiled. Repeated relink hash **3374a7515ec901d2fe1b413c91f423bb149f90a9cc41963cdcb7897decd21671**,
62,665,968 bytes. [Build evidence](evidence/android-2026-10-25-world-auction-build.json).
Code 34 also distinguishes the two complete static aggregate hints by hashes;
all existing source IDs, privacy bounds and the 8 MiB live guard remain.

**Finish exact application CI, package/sign code 34, verify all inputs and save
APK plus corresponding source before requesting the combined Thor gate.**
Keep source-backed defects separate from inferred Thor causation. Native runtime,
GT fallback, world readiness and gameplay flags remain false until complete Stop
and postconditions pass. Client import/WarcraftXL/playable login are later work.

## Physical runtime evidence retained

Code 32: 64 receipt samples / 65 native probes, 672,534 ms session and 651,866 ms
healthy screen-off. Complete 22,817-file / 7,625,943,031-byte rehash; strict
metadata/GT prechecks, original 113 DBC stores, readiness/freeze/challenge passed.
Log limit ended the session. World/auth exited zero; SQL shutdown and metadata/GT
postchecks were not reached. User Stop remains unqualified. No seed replay,
uninstall, storage clearing or repeated standalone database/auth tests.

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
