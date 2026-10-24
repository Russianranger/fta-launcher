# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Physical code 31 and current repair

Recovered remote HEAD **5d4339f1bd91e6f5c290bcf90352d7bfb4ba3067**; exact CI
[40133212573](https://github.com/Russianranger/fta-launcher/actions/runs/40133212573)
passed all three jobs. Latest private APK is code **31**, `0.12.4-world-signals`,
SHA256 `b34acb24a20b9e2388a12c2e13136718673c183429e6e2a1da9af40ae20ab517`.
Application commit `52e53173e660955009d8a5c6bc3288bb13acc770`;
[APK proof](evidence/android-2026-10-24-private-world-signals-apk.json).
Private file ID `libfile_e74c3496085881919ae5ba93097d0b00`.
Code 30 is withdrawn; do not install it. Public CI APKs are not private updates.

**Code 31 passed sustained native health and the screen-off duration observation.**
Report `FTA-database-diagnostic-1792883744162.json`, SHA256
`30c9f597530779f0c872c4c10ebc3de4d16269bdd1e78deedbeb290ee4d4b099`.
19 healthy samples, 186,234 ms measured session, **144,884 ms healthy screen-off**.
All 22,817 files / 7,625,943,031 bytes rehashed; strict metadata/GT prechecks,
113 native DBC stores, challenge and freeze guard passed. All failure markers,
including the unchanged 8 MiB log cap, are false. This resolves log pressure and
establishes the previously missing duration observation for this executable.

**The aggregate gate still failed during Stop.** World printed Halting then
exited 139 after 221,129 ms. Auth exited zero; database exited zero without observed
SQL shutdown. Metadata/GT postchecks were not reached; false postchecks do not
prove data loss. `sessionCompleted`, `qualified`, `databaseFallbackValidated`,
`worldReady` and composite `screenOffQualified` remain false.
[Physical evidence](evidence/android-2026-10-24-thor-code31-runtime.json).

The new witness worked: SIGSEGV/code 2, unknown PC, world LR `0x220b8ac`.
Exact original ELF symbolization identifies the virtual dispatch in
`ScriptMgr::OnDestructObject(Object*)`. Original Unload deletes scripts but leaves
EnabledHooks pointing at them. The original-code host fixture reproduces late
and reentrant heap-use-after-free; the narrow fix passes Release and ASan/UBSan.
[Diagnosis, limits and reproduction](script-unload.md).

**Source repair implemented: `android-arm64-world-v14`.** It retains all v13
patches and adds `0018`, clearing every inner enabled-hook list before deleting
any script. Indexed outer slots, deletion order, active callbacks, all 52 modules,
C++20, NPCBots, SQL and source defaults remain. Tree:
`c85f87ebc192e78263fa14789223628c9df6e225bc0a632e67f3a1a720db11f5`.
177 tooling tests and private original-code host probes pass. Full native rebuild,
private update packaging, Android CI and physical repair validation remain pending.
The Boost dependency driver also corrects B2's failing `--version` probe to `-v`.

## Next independent actions

Complete the memory-checked Android dependency/world rebuild, using the pinned
NDK r28c, all 52 static modules/scripts and C++20. Do not ship a partial build.
Preserve all retained realm/backup identities and original provider payloads when
integrating the new world executable. Qualify exact build/link/source/ELF evidence,
Android tests/lint/assembly, private signer and 16 KiB alignment before the next
substantial Thor test. No new APK has been delivered for the shutdown repair.
Do not reimport assets, replay seed/updater SQL, uninstall or clear storage.

Workspace maintenance removed earlier scratch builds. Source companion v8 was
recovered and verified: SHA256
`5e2532f8e78f1de0453e73f59c9d07fc4353cfdd393e355f3c22e0e443a334f1`,
private ID `libfile_1c77e9d472fc8191a91ab7fc4b23ff0a`.
It includes the unchanged original source ZIP and exact dependency sources.
Original world ELF SHA256:
`97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766`.
Recover `FTA-Launcher-Development-Signing.zip` for delivery; never regenerate it.
Persistent certificate:
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.

## Preserved evidence and boundaries

Archive `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`;
catalog `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
The persistent 148-entry runtime manifest SHA256 is
`dda46e9555ad3b58acdbb0125fccdc89850de78a56159ee2bfcbfbde8f529c8b`.
Host original DBC loaders and restored exact MariaDB loaded all 8,922 GT floats
bit-for-bit, with ten empty-table negatives and ASan. Host transport adapters
remain distinct from Android execution. Native GT qualification remains gated by
complete runtime postconditions.

Physical database/updater, persistent realm, backup/restore, process-death recovery,
local auth and Stop/restart gates already passed. Client import, WarcraftXL and
playable login are later milestones. Deferred Attriboost Settings field 8 vs 9
bug remains gameplay work. One compiler job by default, at most two after memory
checks; no concurrent heavy builds. Logs/binaries/assets/SQL/credentials stay
outside Git. Run prescribed Python/Android/package checks and exact-commit CI.
