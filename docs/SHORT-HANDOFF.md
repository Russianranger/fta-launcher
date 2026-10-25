# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current delivery and next action

Application commit **da1a92f1ec6904f389adf7489f3b828ccfb06388**; exact CI
[40147468365](https://github.com/Russianranger/fta-launcher/actions/runs/40147468365)
passed all three jobs. Private code **32**, `0.12.5-world-stop`:
**58,987,731 bytes**, APK SHA256
`abaa6cbc199817cecd016db8b542642b9561da86819a824b3887ddf3ca7e0fda`.
[Measured APK proof](evidence/android-2026-10-25-private-world-stop-apk.json).
[Verified private APK](https://chatgpt.com/library?file_id=libfile_2ac409c7f814819181b82c6ec4020e02&account_id=personal).
184 tooling tests pass; Android: 379 total, **378 passed, one skipped**, no failures
or errors. The existing `WorldUpdaterStoreTest` needs private `Database.zip`, absent
in public CI; do not repeat earlier physical database tests. Lint/assembly,
225 packaged-input checks, persistent v2 signature and 16 KiB alignment pass.
All 19 donor native files and 148 runtime entries are unchanged; the additive
world and v2 witness bring the native file count to 21. Code 30 remains withdrawn.
Public CI APKs are foundation previews, not private updates.

**Next substantial gate: install private code 32 in place, select Start native
world test, keep visible through retained full rehash/startup, then leave the
screen off for at least three minutes after healthy world/auth/database checks.
Reopen, select Stop once, and export the diagnostic report immediately.** Export
immediately on failure. No uninstall, storage clear, asset reimport, seed replay
or repeat database/auth tests. The new world needs its own complete combined gate.
Native database fallback, world readiness and gameplay remain **unqualified**.

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
