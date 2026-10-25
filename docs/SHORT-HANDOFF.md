# From the Ashes Android — current handoff

Only **Russianranger/fta-launcher/main**. Owner authorizes qualified commits and
pushes. AYN Thor, Android 13, ARM64. Read `AGENTS.md`, `game-data-import.md`,
`private-diagnostics.md`, `native-world-gate.md`. Earlier history:
[DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current private delivery — code 34

Application commit **0885153f71061751bb7e189ed8d8e52479badfcb**, exact
[CI 40155455259](https://github.com/Russianranger/fta-launcher/actions/runs/40155455259)
passed all three jobs. **0.12.7-auction-count**, code **34**, 59,000,019 bytes;
APK SHA256 `45ade0fd1cb78c52527f46bfe0c0ac338c40d8948debb838d1bb0db85a6431a8`.
[Verified APK and corresponding source](code34-recovery.md).
186 tooling tests; Android 383 passed/one existing private-fixture skip, no errors
or failures; lint/assembly, 225 packaged inputs, persistent v2 signing/16 KiB
alignment passed. Twenty of 21 native payloads and all 148 runtime entries are
byte-identical to code 33; only the separate world-session executable changes.

**Next: install code 34 in place, Start native world test once, wait for healthy
samples, screen off three minutes, reopen, Stop once, Export diagnostic report.**
Export immediately on failure before retrying. No reimport, seed replay, uninstall,
storage clear or repeated standalone database/auth tests. The complete gate must
prove clean world/auth exits, SQL shutdown and strict metadata/GT postchecks.

## Measured diagnosis and implemented correction

Code 33 recovered the same code-32 attempt without a new session:
`d280294e-62b4-4255-82f1-5ca5c14bb80c`. Report SHA256
`3c23e419d95637c8f907745bf05fc29332bb667aa66ea74ffb1e3e77e3a1f618`.
[Recovery proof](evidence/android-2026-10-25-thor-code33-recovery.json).
World log 8,535,490 bytes; sampled first/last 4 MiB, omitted 146,882. Tail has
67,108 warnings/33,554 shared aggregate-hint matches and final Halting. The old
prefix cannot distinguish double/uint64 hints; no exact Thor caller is proven.

[Source-backed correction](auction-count.md): recurring auction snapshot reads
COUNT column 4 as uint32, emits two warnings per row, then retries uint64. v15
changes only that expression to an explicit uint32 conversion of Get<uint64>().
Query, narrowing/accumulation, original Field, SQL/configurations and all 52 modules
are preserved; v14 shutdown repair remains. Four original/four fixed Release and
ASan/UBSan fixtures pass with identical boundary counts and zero fixed warnings.
Code 34 also distinguishes the two complete static hint hashes, preserving all
existing IDs, privacy bounds, warning levels and the live 8 MiB guard.

The single-job ARM64 build reuses the completed graph. Only seller recompiles;
repeated relink SHA256 `3374a7515ec901d2fe1b413c91f423bb149f90a9cc41963cdcb7897decd21671`,
62,665,968 bytes. [Build proof](evidence/android-2026-10-25-world-auction-build.json).
This demonstrated caller bug is consistent with the warnings; Thor causation
and successful runtime remain unqualified until the new report. Combined native
runtime, GT fallback, world readiness and gameplay flags remain false.

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
