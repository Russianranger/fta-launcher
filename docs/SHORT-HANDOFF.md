# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current private delivery and immediate action

Application commit **9155fd76009b2ff613f8d2975a7784d1d7ccc477**; exact CI
[40151936482](https://github.com/Russianranger/fta-launcher/actions/runs/40151936482)
passed all three jobs. Private code **33**, `0.12.6-log-evidence`, **59,000,019 bytes**;
APK SHA256 `c1b6f5434f1a43852ba7948f69232a7da51e53e56848eb872cdfda0f43fc1553`.
[Delivery and matching source](code33-recovery.md);
[APK proof](evidence/android-2026-10-25-private-log-evidence-apk.json).
185 tooling tests pass; Android **382 passed, one existing private-fixture test
skipped**, no failures/errors. Lint/assembly, 225 packaged-input checks, persistent
v2 signature and 16 KiB alignment pass. All **21** native files and all 148 runtime
manifest entries are byte-identical to code 32. No native rebuild occurred.
Public CI APKs are foundation previews, not private native updates.

**Next action: install private code 33 in place, reopen and Export diagnostic
report only. Do not start another world session or import/verify assets first.**
Recover attempt `d280294e-62b4-4255-82f1-5ca5c14bb80c` before a new test replaces it.
No seed replay, uninstall, storage clear or repeat database/auth checks.
[Recovery design and procedure](world-log-recovery.md).

## New measured physical result

Code-32 report `FTA-database-diagnostic-1792892076477.json`, SHA256
`a6bd04057da09649c78ce08c7ba87c0494fb2d0bbf51029789a5abcb12a915f1`:
exact APK/world matched; 64 receipt samples / 65 native health probes, **672,534 ms**
measured session and **651,866 ms healthy screen-off**. Full 22,817-file /
7,625,943,031-byte rehash, strict metadata/GT prechecks, original 113 DBC stores,
ready/freeze/challenge passed. The unchanged 8 MiB log cap ended the session during
LOCAL_SERVICE_RUNNING. World exited **zero**, with no fatal capture; auth zero,
database zero without observed SQL shutdown. Metadata/GT postchecks were not
reached; their false values do not prove data loss. User Stop remains unqualified.
World-file export unavailable; stdout 1,042,275 bytes, stderr 19 bytes.
[Physical proof](evidence/android-2026-10-25-thor-code32-runtime.json).

Code 33 recovers oversized logs using only first/last 4 MiB, explicit omitted
bytes and range hashes. It adds bounded frequencies for 447 original message
fingerprints plus 22 source-category IDs; all 160 old message IDs remain stable.
Variable-first warnings can identify candidate categories without exporting text.
Native bytes, logger policy, live 8 MiB cap and qualification gates are unchanged.
After the retained report arrives, resolve measured IDs against the exact source
index, then implement a source-backed correction. Do not suppress warnings or
raise limits speculatively. Combined runtime, database fallback, world readiness
and gameplay flags remain false until the full clean-stop/postcondition gate.

## Retained native repair and recovery

Code 31 identified SIGSEGV at `ScriptMgr::OnDestructObject(Object*)` from freed
EnabledHooks after Unload. The narrow v14 fix detaches hooks before deleting
scripts; original-code Release and ASan/UBSan fixtures pass. Code 32 now observes
world exit zero during failure cleanup; explicit Stop/postconditions still open.
[Diagnosis](script-unload.md), [integration](world-session-repair.md).

The completed ARM64 world preserves all 52 modules / 224 module translation units,
C++20, static scripts/Lua 5.2 and six exact retained link libraries. Only
`ScriptMgr.cpp` differs from v13; SQL/configurations and other source files match.
World SHA256 `a7f7d3f2018703f931da68031972d1468a0c120d8f815608fcaf0e153322b20e`,
62,665,968 bytes. [Build proof](evidence/android-2026-10-25-world-session-build.json).
The additive executable is only selected for retained world sessions. Original
world/updater/manifest pins remain the realm and backup identity; no migration.

[Code-32 native cache/source recovery](code32-recovery.md) remains valid. Do not
rebuild completed objects to recover a session. Read cache `RECOVERY.json`, restore
the pinned NDK at its recorded absolute prefix, then restore nanosecond timestamps
before Ninja. One job by default, maximum two after memory checks; no concurrent
heavy native builds. Logs and private compiled files stay outside Git.
Signing capsule `FTA-Launcher-Development-Signing.zip`:
`libfile_7f9c88258f0c8191a09ab071e7447d30`; never regenerate or print its contents.
Persistent certificate `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.

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
