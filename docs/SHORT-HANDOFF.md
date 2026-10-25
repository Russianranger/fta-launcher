# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current checkpoint and next action

Recovered main **becada5e6fbf5a5789913628f12cd3fa324ebff8**; exact CI
[40148421622](https://github.com/Russianranger/fta-launcher/actions/runs/40148421622)
passed all three jobs. Latest delivered APK is private code **32**,
`0.12.5-world-stop`, SHA256
`abaa6cbc199817cecd016db8b542642b9561da86819a824b3887ddf3ca7e0fda`.
[APK/source/cache recovery](code32-recovery.md).

**New code-32 Thor report** `FTA-database-diagnostic-1792892076477.json`, SHA256
`a6bd04057da09649c78ce08c7ba87c0494fb2d0bbf51029789a5abcb12a915f1`:
exact APK/world matched; 64 receipt samples / 65 native health probes, **672,534 ms**
measured session and **651,866 ms healthy screen-off**. Full 22,817-file rehash,
strict metadata/GT prechecks, native DBC stores, ready/freeze/challenge passed.
The unchanged 8 MiB log cap ended the session during LOCAL_SERVICE_RUNNING.
World exited **zero**, with no fatal capture; auth zero, database zero without
observed SQL shutdown. This is improved failure cleanup, not a qualified user Stop.
Metadata/GT postchecks were not reached; false postchecks do not prove data loss.
The world-file export is unavailable; stdout 1,042,275 bytes, stderr 19 bytes.
[Physical proof](evidence/android-2026-10-25-thor-code32-runtime.json).

**Code-33 candidate: `0.12.6-log-evidence`.** Read-only export samples oversized
retained logs in two bounded 4 MiB regions, with explicit omitted bytes and
range-specific hashes. Adds capped source-message frequencies and 287 original
warning-site fingerprints, preserving all 160 earlier IDs. Native bytes, logger
policy, runtime 8 MiB cap, realm/backup/assets and qualification gates stay unchanged.
185 Python tests pass; Android CI, private packaging and delivery pending.
[Recovery design and procedure](world-log-recovery.md).

Next independent step: qualify Android on the exact application commit, package
with all 21 unchanged code-32 native files and persistent key, verify/save the APK
and matching source companion. Then **install in place and Export diagnostic report
only**. Do not start another world session: recover the existing failed attempt
before it is replaced. No import, verification run, seed replay, uninstall or storage
clear. All combined runtime/database-fallback/world-ready/gameplay flags stay false.

## Exact physical finding and repair

Code-31 report `FTA-database-diagnostic-1792883744162.json`, SHA256
`30c9f597530779f0c872c4c10ebc3de4d16269bdd1e78deedbeb290ee4d4b099`:
19 healthy samples, 186,234 ms session, **144,884 ms healthy screen-off**.
All 22,817 files / 7,625,943,031 bytes rehashed; strict metadata/GT prechecks,
113 native DBC stores, challenge and freeze guard passed. Failure markers false.
Stop printed Halting then world exited 139 after 221,129 ms. Auth exited zero;
database exited zero without observed SQL shutdown. Metadata/GT postchecks were
not reached; false postchecks do not prove data loss. Aggregate flags remain false.
[Physical evidence](evidence/android-2026-10-24-thor-code31-runtime.json).

Witness: SIGSEGV/code 2, unknown PC, exact old-world LR `0x220b8ac`, in
`ScriptMgr::OnDestructObject(Object*)`. Original Unload deletes scripts while
EnabledHooks retains freed pointers. The original-code fixture reproduces late
and reentrant use-after-free; detaching hooks before deletion passes Release and
ASan/UBSan. [Diagnosis and limits](script-unload.md). No physical repair claim yet.

Full ARM64/Bionic v14 build passed: all 52 modules / 224 module translation units,
C++20, static scripts/modules/Lua 5.2, six exact retained link libraries and
16 KiB ELF alignment. Only `ScriptMgr.cpp` differs from verified v13; SQL,
configurations and all other source files remain byte-identical. v14 tree:
`c85f87ebc192e78263fa14789223628c9df6e225bc0a632e67f3a1a720db11f5`.
World ELF **62,665,968 bytes**, SHA256
`a7f7d3f2018703f931da68031972d1468a0c120d8f815608fcaf0e153322b20e`.
[Build proof](evidence/android-2026-10-25-world-session-build.json).
One build graph used one job initially, then two after a conservative ~5 GiB memory
check. Native build complete; do not rebuild it merely to recover a lost session.

`libfta_world_session.so` is selected only for retained world sessions. Old
manifest/world/updater pins remain the realm/backup identity; no migration.
Receipts bind the actual world hash/profile. The separately pinned v2 witness
recognizes both executable names and preserves original fatal-signal behavior.
[Integration and physical procedure](world-session-repair.md).

## Private recovery artifacts

The APK, matching source companion and complete native cache are saved privately.
[Recovery links, part hashes and reconstruction](code32-recovery.md). The source
companion preserves all 178 original members and adds current reproduction
sources/build evidence; its original checksum inventory is independently pinned.
The private cache preserves the completed build, source v14 and target prefix.
Read its `RECOVERY.json`; restore pinned NDK at the recorded absolute prefix,
then use `RESTORE_TIMES.py` / `NANOSECOND_TIMES.json` before Ninja. Exact nanosecond
timestamps prevent accidental rebuilds. Archive streaming verification passed:
24,120 regular members, 32,573 timestamp records, exact repaired world hash.
The cache contains no signing key, live database or imported game assets. Keep it
private. Build one job by default, maximum two after memory checks; never overlap
heavy native builds. Keep compiler output in files.

Code-31 donor: `libfile_e74c3496085881919ae5ba93097d0b00`, APK SHA256
`b34acb24a20b9e2388a12c2e13136718673c183429e6e2a1da9af40ae20ab517`.
Original v8 source companion: `libfile_1c77e9d472fc8191a91ab7fc4b23ff0a`, SHA256
`5e2532f8e78f1de0453e73f59c9d07fc4353cfdd393e355f3c22e0e443a334f1`.
Signing capsule `FTA-Launcher-Development-Signing.zip`:
`libfile_7f9c88258f0c8191a09ab071e7447d30`. Recover securely; never regenerate or
print contents. Persistent certificate:
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.

## Preserved milestones

Code 26 passed import/preflight/publication; subsequent sessions fully rehashed
all files. Archive SHA256
`407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`;
catalog `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Host original DBC loaders and restored exact MariaDB loaded all 8,922 GT floats
bit-for-bit, with ten empty-table negatives and ASan. Android native fallback
qualification still requires clean shutdown and complete runtime postconditions.
Physical database/updater, realm reuse, backup/restore, process-death recovery,
local auth and Stop/restart gates already passed. Client import, WarcraftXL and
playable login are later milestones. Deferred Attriboost Settings field 8 vs 9
bug remains gameplay work. Private binaries/assets/SQL/credentials stay out of Git.
