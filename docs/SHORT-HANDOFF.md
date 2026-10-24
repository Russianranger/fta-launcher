# From the Ashes Android — current handoff

Repository `Russianranger/fta-launcher`, branch **main** only. The owner authorizes
qualified commits/pushes here. Pocket Realm and Ashen Order are read-only references.
Target: AYN Thor, Android 13, Snapdragon 8 Gen 2, Adreno 740, ARM64/Bionic.
Read `AGENTS.md`, this file, `game-data-import.md`, `private-diagnostics.md` and
`native-world-gate.md`. Earlier evidence remains in [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Recovery checkpoint — October 24

Recovery began from actual remote `1c9e51db48fd6a8eed3e083b10956f74a3333651`.
Its Foundation CI [40101255095](https://github.com/Russianranger/fta-launcher/actions/runs/40101255095)
passed. This increment adds live host DBC qualification and a receive-only world
challenge probe. Exact-commit CI is pending publication; verify it before delivery.

**Latest private APK remains code 26, `0.11.8-dbc-loading`.** Recovered unchanged:
measured SHA256, valid v2 signature, persistent certificate, version, 16 KiB
alignment, all 17 native payload hashes, original runtime manifest and its 148
entries, and metadata/policy hashes. It was not rebuilt.
[Recovery evidence](evidence/android-2026-10-24-code26-recovery.json).

- APK SHA256: `2a276e781698c3a9501f15d25693fd57085e7965151ad13ff5f991e3f037ecba`.
- Certificate SHA256: `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
- Saved APK: `FTA-Launcher-0.11.8-dbc-loading.apk`, file ID
  `libfile_b6bd9a26ae20819189c158e8355c45c5`.
- Signing capsule: `FTA-Launcher-Development-Signing.zip`, file ID
  `libfile_7f9c88258f0c8191a09ab071e7447d30`. Located; not opened/needed in recovery.

The new world probe is **not in that existing APK** or wired to a launcher action.
Do not deliver the public CI APK as a private update. Future private builds must
reuse the capsule and increase version code beyond 26.

## Immediate substantial Thor gate

Code 26 remains **physically unqualified**. Install it in place, keep the launcher
visible, import the unchanged `Data.zip` once. After success, reopen and select
**Verify imported game data**, then **Export diagnostic report**. If either fails,
export immediately before another attempt. No uninstall, storage clearing,
repackaging or repeated passed database/auth tests. Preserve the realm, backup,
Database.zip, native dependencies and imported files.

Expected Data.zip SHA256:
`407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`.
World startup stays disabled until actual import/publication/full-rehash evidence
is inspected. Bundle the deferred **120-second** screen-off observation with the
later native runtime gate.

## Code 26 asset checkpoint

Physical code 25 extracted **22,817 files / 7,625,943,031 bytes**, passed all 104
mmap headers and identified exactly twelve DBC differences. No Android kill
matches that attempt. Code 26 preserves original requirements/bytes while handling
only source-proven unused zero-string CharHairTextures, packed 15-byte PowerDisplay
and ten GT files using the original SQL fallback with **8,922** required rows.

Original code 26 qualification: 334 Android tests, 151 tooling tests, lint/assembly,
221 packaged inputs, signature/alignment and original-loader tests. All 17 native
payloads match code 25. Its inert importer always keeps
**`databaseFallbackValidated=false`**. File preflight does not qualify native
terrain/navigation semantics, actual GT loading, world startup or gameplay.

## Newly completed independent work

`tools/check_dbc_backup.py --live-native` tests real Connector/C 3.4.9 against an
isolated hash-verified Thor backup on exact MariaDB 12.3.2. Unchanged original
DBC loaders, `ResultSet` and `Field` consume native query results. All 8,922 floats
match independent CLI snapshots bit-for-bit; ten empty-table controls remain
failed loads. Original assertions, strict field types and AddressSanitizer pass.
LeakSanitizer is disabled for host task-inspection limitations. The connection
adapter remains host-only, not the complete original connection pool.

SELECT-only grants are checked. Original GT rows, strict three-schema metadata,
account/character counts and backup bytes are unchanged; SQL shutdown exits zero.
No seed replay, schema relaxation or server patch.
[Live evidence](evidence/host-2026-10-24-live-dbc.json).

`LocalWorldProbe` recognizes the original 44-byte world challenge on loopback
without sending application bytes. Wrong framing, truncation and slow fragments
fail under one two-second deadline. Five unit tests and a real host socket fixture
pass; the pinned original `ServerPktHeader` compiles unchanged.
[Source/socket evidence](evidence/host-2026-10-24-world-challenge.json).
No original worldserver or Android process was started by these checks.

155 tooling tests pass locally. Local Gradle could not resolve its Android plugin
through this runtime's Java network path. Full Android tests/lint/assembly require
exact-commit CI verification. The five new pure Kotlin tests passed locally.

## Preserved physical milestones

| Code | Passed gate |
| --- | --- |
| 7 | Complete unchanged seed: 563 tables and one trigger |
| 10 | Three compiled updaters, statements, all 24 checks, clean SQL stop |
| 11–12 | Database lifecycle, owned-process recovery and retained reopen |
| 13–14 | Persistent realm and cold backup/restore |
| 16 | Retained original auth listener and clean shutdown |
| 17 | User Stop/restart; screen-off 112,127 ms still below 120,000 |

Do not repeat passed gates for unchanged payloads. World ELF stays
`97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766`;
all 52 modules, C++20, NPCBots and profile `android-arm64-world-v13` remain intact.
The version 8 native corresponding-source companion still applies.

## Next implementation after physical report

1. Verify code 26 report identity, extraction, preflight, publication and retained
   full rehash. Diagnose failures from exact measured evidence.
2. Integrate retained database → auth → original world startup under process
   ownership, with verified assets/backup, strict metadata, original updates and
   modules, generated private credentials and loopback binding. See
   [native-world-gate.md](native-world-gate.md) for tested pieces and remaining contracts.
3. Qualify the **actual Android** GT path and complete startup, world/auth/SQL
   health, reverse graceful Stop, retained data and 120 seconds of screen-off
   health. Host evidence must never set device qualification flags.
4. Client import, WarcraftXL build 12340 (`SET specular "0"`), account provisioning,
   playable login, controller and thermal tests remain later work.

Deferred original gameplay issue: `Attriboost.cpp:284` reads Settings at index 8
of `SELECT *`; source DDL/later per-player loading indicate index 9. Do not silently
change gameplay logic during infrastructure qualification.

## Safety and recovery

Never replay the populated seed over saves. Preserve SQL, locks, receipts, asset
schemas and provenance. No assets, database rows/archives, private configs, signing
credentials or compiled private server binaries enter Git. Run
`tools/check_repository.py` after staging; keep logs private and bounded.

Use one native compiler job, at most two after memory checks, and no concurrent
heavy native builds. Do not assume an old full-world object cache. This recovery
obtained the code 26 APK, original source companion and backup, restored exact
host MariaDB from its published checksum, and rebuilt only small host fixtures.
Native delivery bytes and signing key are unchanged. Scratch tools are disposable;
saved artifact identities and this Git handoff remain authoritative.
