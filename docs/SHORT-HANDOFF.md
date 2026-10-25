# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Physical code 31 and current repair

Source-repair commit **8f3947a529cb7945d48c948566d9ab44a18c2b03**; exact CI
[40142945314](https://github.com/Russianranger/fta-launcher/actions/runs/40142945314)
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

**Code-32 repair candidate: `0.12.5-world-stop`.** Full ARM64/Bionic world build
and verification passed: all 52 modules / 224 module translation units, C++20,
static scripts/modules/Lua 5.2, six exact retained link libraries and 16 KiB ELF
alignment. Only `ScriptMgr.cpp` differs from the verified v13 source tree. SQL,
configurations and source defaults are byte-identical. Profile v14 tree:
`c85f87ebc192e78263fa14789223628c9df6e225bc0a632e67f3a1a720db11f5`.
World ELF: **62,665,968 bytes**, SHA256
`a7f7d3f2018703f931da68031972d1468a0c120d8f815608fcaf0e153322b20e`.
[Measured build proof](evidence/android-2026-10-25-world-session-build.json).
184 tooling tests pass. The verifier now reads CMake's generated compiler config
when Android's toolchain omits the compiler path from its cache. Native builds
used one job initially, then two after a conservative ~5 GiB memory check; one
build graph throughout. Full compiler output remains private.

The new `libfta_world_session.so` is additive and selected only for retained world
sessions. Exact old manifest/world/updater pins remain the realm/backup identity;
no migration or seed replay. Session receipts bind the new world hash/profile.
The separately pinned v2 fault witness recognizes both world image names and
preserves the original signal behavior. Existing donor payloads stay unchanged.
[Integration and next physical gate](world-session-repair.md).

Verification-tool checkpoint **fba8b0b1c6e55b3edbcbf21b3fde499b51870b7c** passed
all three jobs in [40144052450](https://github.com/Russianranger/fta-launcher/actions/runs/40144052450).
Code-32 Android CI and private APK packaging/delivery are pending. **Do not infer
a delivered code-32 APK from the native build.** Next independent action: push
and verify the exact application CI, create the matching private source companion,
package with the persistent key, verify every byte/signature/alignment, and save
the APK/source/cache before requesting one Thor Stop gate.

## Recovery inputs

Code-31 donor APK private ID `libfile_e74c3496085881919ae5ba93097d0b00` (hash above).
Original source companion v8 was recovered and verified: SHA256
`5e2532f8e78f1de0453e73f59c9d07fc4353cfdd393e355f3c22e0e443a334f1`,
private ID `libfile_1c77e9d472fc8191a91ab7fc4b23ff0a`. It contains the unchanged
original source ZIP and exact dependency sources. The new companion tool preserves
all 178 original members against the pinned original checksum inventory, then adds
the repair sources and actual build evidence. No binary or credential goes in Git.
Original world ELF SHA256:
`97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766`.

Persistent signing capsule was securely recovered:
`FTA-Launcher-Development-Signing.zip`, private ID
`libfile_7f9c88258f0c8191a09ab071e7447d30`. Never regenerate or print its contents.
Code-31 donor's persistent v2 certificate was independently verified:
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
