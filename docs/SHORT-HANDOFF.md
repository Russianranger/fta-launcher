# Development handoff

Started 2026-10-21 from the supplied From the Ashes Android Integration
Assessment. Repository: `Russianranger/fta-launcher`, integration branch `main`.
The owner authorizes creating and pushing files here. Reference repositories
are read-only unless separately authorized.

## Current checkpoint

Latest private update: **0.11.3-import-checkpoints**, version code **21**.
APK SHA256: `0e0cddb18986f18b95076aa8ddf7a71a71d3cd35811377a9e9e907e1e43e91fb`.

**Physical code 20 did not finish; its cause is not yet known.** The new JSON
matches the verified private APK and records `GAME_DATA_COPY / COPYING` at
**1,677 MiB**, with no terminal error, archive hash or active asset candidate.
Code 20 leaves the UI at copying during ZIP indexing, and its asset report stays
COPYING until success or a handled failure. Thus the last recorded stage does
not establish where the process actually stopped. No new unsupported asset or
schema mismatch is proven. See [physical evidence](evidence/android-2026-10-23-unfinished-import.json).

An unchanged-code-20 regression confirms that its progress omits indexing.
A **1,888,997,926-byte / 40,141-file** synthetic archive imports and fully
reverifies on both code 20 and code 21 with a **64 MiB JVM heap**. This does not
reproduce or explain the Android failure, and no actual game archive is present.

Code 21 adds atomic operation checkpoints readable independently of the asset
lease: copy, copy fsync, ZIP indexing (including entry counts), extraction,
preflight, publication and verification. It records copied bytes, completed
archive SHA256, free storage, heap measurements and bounded application frame
names on failure. Android exit history includes only fixed reason codes,
timestamps/status and sampled memory; an exit is tied to an attempt only when
its recorded owner PID, attempt token and time window match. Unmatched older exits
are explicitly uncorrelated. No raw trace, exception message, process ID, source
URI or game/database data is exported. Live, interrupted and unknown worker
observations remain distinct; none can qualify game data.

The private build passes **290 Android tests** (26 classes), lint/assembly,
**148 tooling tests**, all **221** runtime/notice inputs, signing and alignment.
Its signer and all **16 native payloads** match code 20. **26 separate JVMs**
exercise hard exits at every import boundary, preserved prior/new candidates,
orphan recovery and the large-archive import/reverify. Original C++ loader and
five additional source/store JVM checks also pass. See [checkpoint host evidence](evidence/host-2026-10-23-import-checkpoints.json),
[source checks](evidence/host-2026-10-23-import-checkpoint-source.json) and
[APK proof](evidence/android-2026-10-23-private-import-checkpoint-apk.json).

**Next focused physical step:** update to private code 21 in place and import the
same unchanged `Data.zip`. Let the displayed phases complete; after success,
reopen and **Verify imported game data**. If both pass, complete the remaining
screen-off measurement with one local database/auth session: lock for three
minutes, confirm the counter reaches 120 seconds, Stop and export one JSON. If
anything stops or fails, reopen if necessary and export immediately before
starting another test. The export now carries `gameDataOperation` independently
of `gameDataDiagnostic`. Do not uninstall, clear storage, repackage Data.zip,
reimport Database.zip or repeat passed database milestones. Physical code 21,
Android exit-history behavior and actual asset/native world loading remain
unverified. All original SQL, source policy and strict validation stay unchanged.

Physical code 19 previously identified `dbc/lightskybox.db2` as a ZIP-index
rejection. Code 20 preserves these unused companions without interpreting a DB2
schema or substituting them for the 113 required DBCs. Its 274 Android tests and
host source checks passed. The code 20 report does not establish whether the
actual archive reached or passed that corrected boundary.
See [code 19 failure](evidence/android-2026-10-23-db2-import-failure.json) and
[code 20 APK proof](evidence/android-2026-10-23-private-db2-companion-apk.json).

Code 19 previously fixed the screenshot-observed `dbc/stock_dbc` folder rejection
and introduced failure-stage reporting. Its 265 Android tests and original-loader
stock-backup checks passed, but physical execution exposed the separate DB2
companion rejection described above. See [prior APK proof](evidence/android-2026-10-23-private-data-import-fix-apk.json).

Previous private update: **0.11.0-game-data**, version code **18**.
APK SHA256: `66db6ffd94d6a3e7070e46bba08822d48abdd8c42f136008c7e2a55b054d8434`.

**Physical code 17 passes user-controlled service Stop and retained restart.**
Both sessions have all ten checks true, clean auth termination before SQL database
shutdown, all 24 strict metadata checks before/after and unchanged active realm.
All 54 native records exit zero, with no timeout, cancellation, validation error or
dropped record. The longest measured healthy screen-off interval is **112,127 ms**,
short of the unchanged **120,000 ms** requirement by 7,873 ms. Thus
`localServiceQualified=true`, `localServiceRestartQualified=true`, but
`screenOffLocalServiceQualified=false`. Do not repeat the passed two-session
restart milestone or lower the screen-off threshold. The report does not distinguish
notification Stop from the app's Stop control.
Report SHA256: `412d43a2f39a34f08074c8880971d2937db69174c071468a1020d0548c4d67b5`.
See [physical service evidence](evidence/android-2026-10-23-thor-local-service-pass.json).

Code 18 adds an **inert game-data importer** for an extracted `dbc/maps/vmaps/mmaps`
ZIP/ZIP64, full per-file hashes, atomic candidate publication, interrupted-import
recovery and later full re-verification. It uses the exact original source's
**113 active DBC loads**, seven version sentinels, eight startup map coordinates,
map version 9, `VMAP_4.8`, mmap version 20 and Detour version 7. Two commented-out
DBC entries were caught by compiling the independent original-loader check and
are excluded. This is a file/header preflight, not full native semantic loading,
world startup, complete map coverage or game login. No game assets are available
here; actual matching assets remain the next external dependency.

Code 18's private build passed **255 Android tests** (25 classes), lint/assembly,
**148 tooling tests**, all **221** runtime/notice inputs, signing and alignment.
Its signer and all **16 native payloads** match code 17. An independent C++20
harness compiles the unchanged original DBC loader and checks 113 synthetic files,
all seven sentinels, byte-field offsets and original header sizes. Five separate
JVMs run the production importer, crash during replacement, reverify the retained
candidate, recover orphan staging and reverify the next candidate. Synthetic
headers never qualify full terrain/navigation semantics or actual game data.
See [host checks](evidence/host-2026-10-23-game-data-preflight.json),
[private APK](evidence/android-2026-10-23-private-game-data-apk.json) and
[import details](game-data-import.md).

**Combined physical gate with code 21 checkpoints:**
update in place with private code 21; import the ZIP, close/reopen the launcher
and select **Verify imported game data**. If either fails, export immediately.
After success, start one local database/auth session, lock for **three minutes**,
wake and confirm the live counter reached 120 seconds, then Stop cleanly and
export one JSON. The combined export includes historical game-data verification
and current service evidence. No second restart is needed. Keep the retained
realm, verified backup, Database.zip and native dependencies. Do not import a
full client/MPQ ZIP or repeat updater/backup/recovery tests. World startup remains
disabled until real asset evidence is inspected. Physical code 18/19 import failed and code 20 did not finish; code 21 is unverified.

Previous private update: **0.10.0-local-service**, version code **17**.
APK SHA256: `864615af00847d0e648f3583fce353389b8be6db44093cbe1b671227d1685d94`.
Passed 230 Android tests, 142 tooling tests and real host retained service sessions.
[Host service evidence](evidence/database-2026-10-23-host-local-service.json).

**Physical code 16 passed retained local auth:** all 15 required checks, two
original auth listener sessions, all 39 native exits zero and all 24 metadata
checks before/after clean shutdown. Report SHA256:
`fbc4832bd11a1e420fab500018aee75ddbc42df5ed73bd14b1a1b4faa4062a7c`.
[Physical auth evidence](evidence/android-2026-10-23-thor-local-auth-pass.json).
Do not repeat the automatic local-auth diagnostic.

Previous private update: **0.9.1-auth-role**, version code **16**.
APK SHA256: `8d472f42651e1c2ce4de4023db4efcfd89f8eede2d5d67a25cf12796dbabc39c`.

**Physical code 15 isolated a launcher setup defect.** The exact private APK
reopened the retained realm, passed all 24 metadata checks and the guarded offline
transition, then failed before any auth process started. All 13 recorded native
invocations exited zero; the temporary database role was removed and
`activeGenerationVerified=true`. SQL shutdown was not observed, so exit zero is
not promoted to a passed shutdown gate. Report SHA256:
`941e1bc3a90f6a45933ff07508f878a6efa61aed49d89ba432accc8b15af0665`.
See `docs/evidence/android-2026-10-23-thor-local-auth-attempt.json`.

The source and regression identify the precise cause: generated
`fta_local_<UUID hex>` roles always contain the UUID-v4 version digit **4**, but
`AuthDiagnosticConfig` accepted only letters and underscores. Code 16 admits
safe ASCII digits after the first character, retaining the 32-character bound
and rejection of configuration/SQL delimiters. It validates config before realm
state changes or role creation. The production UUID name generator is shared
with regression tests and both real-auth host fixtures; earlier fixtures used
an alphabetic fixed name and did not cover this boundary. No native, original
SQL, schema expectation, updater receipt, realm build or flag policy changes.

The private build passed **217 Android tests** (23 classes, no failures or skips),
lint/assembly, **142 tooling tests**, all **221** runtime/notice input checks,
v2 signing and 16 KiB alignment. Its certificate and all **16 native payloads**
match version 15. The regression failed with the old validator and passes with
the correction. Both original-auth host fixtures pass with production-generated
UUID roles: four clean auth stops and 40 SQL health checks in total. The restored
Thor backup passes all 24 strict metadata checks before and after auth, preserves
account/character counts and shuts down through SQL with exit zero.
See [private APK evidence](evidence/android-2026-10-23-private-auth-role-apk.json),
[seed host evidence](evidence/database-2026-10-23-host-local-auth-role.json) and
[restored-backup host evidence](evidence/database-2026-10-23-host-thor-backup-auth-role.json).
Host TCP adapters remain distinct from Android socket/ARM64 execution.

**Physical code 14 passed backup/restore.** All eight flags and all three database
opens passed, with 21 successful native records and clean SQL shutdowns. The
uploaded ZIP exactly matches the reported 181,937,064 bytes and SHA256
`0b5fb6ef6912add622f6371d5a1cfcbde3bd63155d03b95ecec6e6d0fa36df8f`;
every one of its 1,357 file hashes verified. The JSON predates ZIP export, so its
`exportedCopyVerified` flag is false; the uploaded copy independently verifies,
but the Android SAF readback receipt itself remains unobserved. Do not repeat
the backup/restore milestone. Report SHA256:
`c90bd8fae69ee2e7fb7880cf70ef3996c2519278c08f2e910b643c7931ad3eab`.
See `docs/evidence/android-2026-10-23-thor-realm-backup-pass.json`.

**Measured next-start blocker:** original world startup sets VERSION_MISMATCH
before loading maps and clears it only on successful initialization. The qualified
missing-map stop leaves that bit; original auth adds OFFLINE, and flag 3 is
excluded from its realm list. The restored Thor backup reproduced that refusal
while all 24 metadata checks passed. Code 15 guards realm 1, gamebuild 12340 and
flags 0..3, then clears only the stale startup bit while explicitly retaining
OFFLINE. No schema, receipt, account or build expectation is relaxed. See
`docs/evidence/database-2026-10-23-auth-startup-flag-boundary.json`.

Previous private update: **0.8.0-realm-backup**, version code **14**.
APK SHA256: `e268967eb5cc95d2a17161daf20a22f5719767b170db6cff95457a9fdb5880e8`.
It passed 198 Android tests, 142 tooling tests, lint/assembly, all 221 packaged
inputs, signature/alignment and real MariaDB backup/restore across two JVMs.
See `docs/evidence/android-2026-10-23-private-realm-backup-apk.json`.

Previous private update: **0.7.0-persistent-realm**, version code **13**.
APK SHA256: `898c64163e0c62e35cca358ec6032e3220126fa07b1a27772c2b4b1a003543ee`.
The signer and **all 16 native payloads** match the physically passing code 12.
The private build passed **181 Android tests** (21 classes), lint/assembly,
**142 tooling tests**, all 221 packaged runtime/notice inputs, v2 signing and
16 KiB alignment. Native/core/provider code is unchanged. Real host MariaDB
passed the production generation/activation protocol, canary SQL and JNI owner:
one fresh generation, four reopens across two owning JVM sessions, five clean
SQL shutdowns, unchanged retained data and no reimport in the second session.
See `docs/evidence/database-2026-10-23-host-persistent-realm.json` and
`docs/evidence/android-2026-10-23-private-persistent-realm-apk.json`.

**Physical version 12 passed runtime recovery.** Its exact APK SHA256 is
`5fa04d6e7837595d58526a3a7e773df503c0dd5d80eaab639fc8e097e6ebf8c5`.
All seven observations and all three qualification flags are true: Stop action,
service-process death and same-database recovery. The 24 native records are
complete (9 cancel, 9 crash, 6 resume), with no drops, timeout, truncation or
validation failure. The crash writer's two historical running records are
resolved by independent owner/child termination and recovery under a new owner.
The final database received SQL shutdown and exited 0. Exact child exit signals
remain unobserved on Android. Report SHA256:
`b9b043d2f2763b003c0bdfc1f8d314263a37ab4ab9f64a68258d8099dc0e6833`.
See `docs/evidence/android-2026-10-23-thor-runtime-recovery-pass.json`.
Do not repeat the passed runtime-recovery diagnostic.

**Physical version 11 passed all ten lifecycle observations**, including
screen-off SQL health. The exact code 11 APK SHA256 is
`ad6a38bcfa7a22deba4f648cf779c85ca5376ad918553009effd61ddf400d517`.
Its 85 current-attempt native records are complete with no dropped/truncated
records, timeout or validation error. The forced database exit 137 and two
interrupted clients are expected test actions. Both clean shutdowns exit 0.
`databaseLifecycleQualified` and `screenOffLifecycleQualified` are true;
`serviceProcessDeathQualified` remains false. Report SHA256:
`76eba7e19406fb462d124f5f963712cd2da3676f0c50eb9418dcda8693453d50`.
See `docs/evidence/android-2026-10-23-thor-lifecycle-pass.json`.
Do not reinterpret the connection failure during the deliberate database kill
as a TLS or schema defect. Do not repeat the passed lifecycle/updater tests.

**Physical version 13 passed persistent realm reuse.** The exact code 13 APK
above produced `REALM_STORAGE_PASSED`, `persistentRealmStorageQualified=true`,
all seven storage checks, and `seedImportNeeded=false`. Its active generation and
source identity were verified; both reopen journals contain seven successful
native records, all 24 strict metadata checks and clean SQL shutdown. No timeout,
cancellation, drop or validation failure occurred. Report SHA256:
`c692974fa983b7ce1f829d02627f8156e5022083884abb0be41ea07f2d226a65`.
See `docs/evidence/android-2026-10-23-thor-persistent-realm-pass.json`.
Generic disposable flags in this separate scope do not revoke prior passes.
Do not repeat the initial persistent preparation milestone.

Cold backup/restore and retained local auth passed physically in codes 14 and 16.
User-controlled service Stop/restart passed in code 17; see `docs/local-service-sessions.md`.
The next gate is matching game-data import and the remaining screen-off measurement in code 18.
World startup still requires matching client data and service orchestration.

Passing physical updater diagnostic: **0.5.2-metadata-timezone**, version code **10**.
APK SHA256: `a6e562ec8cb1f8e13267a8825f787ae10fdc94d9034945c69e590243f1d188fd`.
The production implementation passed 143 Android tests, lint and assembly, plus
142 tooling tests. Signature/alignment and all 221 packaged runtime/notice inputs
passed; certificate and all 16 native payloads match version 9. Native source
companion remains unchanged. Physical version 10 now passes the compiled updater gate.
See `docs/evidence/android-2026-10-23-private-timezone-apk.json`.

Previous private diagnostic: **0.5.1-column-diagnostic**, version code **9**.
Its clean build passed **143 Android tests** (18 classes, no failures/errors/skips),
lint and assembly. Final signature/alignment and all **221** provider/notice
inputs passed. APK SHA256:
`1b478f1c1d99ca4928b47429068e6ad7adc6710540d9af4ecfbd0d3bbd6e09aa`.
See `docs/evidence/android-2026-10-22-private-column-apk.json`.

Version 9 kept **all 16 native payloads byte-identical to version 8**, including the original
worldserver, provider, authserver and supervisor. The final APK restores the
verified code 8 supervisor after clean assembly (the rebuilt Debug helper has
different path-dependent bytes), then aligns and signs with the same persistent
certificate. No native rebuild is claimed for this delivery. The v13 source was
reproduced from the original archive; C++20, all 52 modules, static scripts/modules
and default Lua 5.2 retain their version 8 compilation evidence. World ELF SHA256:
`97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766`.
The native corresponding-source companion is unchanged and remains applicable.
Latest launcher source is the public repository; native source inputs stay private.

**Physical version 8 reached the original compiled updater boundary.** All three
updaters completed, all statements prepared, 236 runtime SQL files were staged,
the expected missing-map stop was observed, and no native SQL failure occurred.
The fresh 563-table/one-trigger baseline passed all 15 checks. Final checks were
**23/24**: only `acore_world.columnInventoryMatches` was false. All 585 native
records are present without dropped entries, truncation, timeout or cancellation;
MariaDB received SQL shutdown and exited 0. Report SHA256:
`6c246c9566e1bca3db84f1e61409a85624b5327ebddca3bdbcd8a1571566feb1`.
See `docs/evidence/android-2026-10-22-thor-compiled-updater-attempt.json`.
At version 8, compiled-updater qualification was **false**; version 10 passes below.

Version 9 preserves both locks, original SQL, all 24 strict checks and the native
runtime. It adds bounded read-only world-column counts/hashes before and after
startup, per-table indices into the signed table list, and component hashes for
four source-known floating defaults. Reports omit SQL, defaults and row data.
At that checkpoint the cause was unknown; the subsequent physical version 9
report and host time-zone reproduction below resolve that uncertainty.

The actual new production Kotlin query/parser passed on a fresh exact host
MariaDB 12.3.2 seed and source-policy model: complete 371/372-table snapshots,
all canonical postconditions unchanged, utf8/utf8mb4 outputs identical, and
142,271-byte pretty diagnostic export. This is host evidence, not Android
execution. See `docs/world-column-diagnostic.md` and its hash-only fixtures.

**Physical version 9 localized the remaining mismatch to `game_event`**, signed
post-table index 103. Its before/after table hash is unchanged; every other table
and all four floating-default probes match the host. The original compiled
updaters/prepared statements/missing-map boundary and clean SQL shutdown repeat
successfully. Version 9 strict final validation was 23/24, so that attempt remains unqualified. See `docs/evidence/android-2026-10-23-thor-column-attempt.json`.

The exact MariaDB 12.3.2 host reproduces the Thor table hash at session UTC−06:00
and the original reference at UTC+07:00. Only the rendered defaults of
`game_event.start_time` and `game_event.end_time` differ. This is a metadata-session
time-zone discrepancy already present before the updater, not evidence of a
migration failure. The dump itself sets UTC for its original import. Version 10
pins only the read-only metadata sessions to the existing reference's +07:00,
preserving both locks, all 24 strict checks, source SQL and all native bytes.
Server/global time zones, import/updater sessions and stored timestamps are not
changed. See `docs/world-column-diagnostic.md` for the measured reproduction.

**Physical version 10 passed all 24 final checks.** The new report is bound to
APK `a6e562ec8cb1f8e13267a8825f787ae10fdc94d9034945c69e590243f1d188fd`.
Both complete snapshots exactly equal the original host fixture: all 371 before
and 372 after table entries, aggregates and four component probes. The 15-check
fresh seed baseline, all three original compiled updaters, every statement and
the expected missing-map boundary passed. All 587 native records are present;
there was no timeout, cancellation, validation failure or truncation. MariaDB
received SQL shutdown and exited 0. `compiledUpdaterQualified` is **true**.
Report SHA256: `a27638df5a2b779747e9f3714a42dd03a58e198de867edb21f89a63b5a518ca0`.
See `docs/evidence/android-2026-10-23-thor-compiled-updater-pass.json`.
Do not repeat the updater test just to establish this already-passed milestone.
The missing-map stop still does not establish world readiness or gameplay.

The bounded **Test database lifecycle** mode now has passing physical evidence;
see `docs/database-lifecycle-diagnostic.md`. The new recovery mode remains a
small synthetic disposable test and never opens imported seed SQL or realm
saves. Private updates retain the key and strictly increase the code. Public CI
APKs use another certificate and cannot replace a private update.

Physical version 6 imported **158 tables**, then failed on the first ASCII column
with MariaDB error **1115** at line 19. The native client exited 1 in 83 ms;
the database still received SQL shutdown and exited 0. Verified import order
identifies table 159, `mod_npcbot_paperdoll_consumable_receipt`. This is the only
seed table with an explicit ASCII/ascii_bin column. See
`docs/evidence/android-2026-10-22-thor-full-seed-attempt.json`.

The pinned APK already includes Index.xml and ascii.xml. MariaDB derives its
charset path independently from its message directory; compiled share/mariadb
and staged share/mysql differ. Version 7 binds `--character-sets-dir` explicitly
for bootstrap/server and every direct client invocation, then checks a real
ASCII/ascii_bin column and exact ASCII/Unicode round trips before seed import.
No SQL or provider ELF was changed. The exact new foundation SQL and unchanged
failing file pass on host 12.3.2. That host also succeeds without XML because its
ASCII support is compiled in; it does not reproduce Android's negative case.
Physical version 7 subsequently **passed the complete full-seed gate**. All 563
tables plus the trigger imported unchanged; import and validation took 168,931 ms.
Every inventory,
routine, event and original receipt check matched across all three schemas.
All 577 current-attempt native records are present with no failure, timeout,
cancellation or truncation. SQL shutdown was observed and MariaDB exited 0.
Report SHA256: `2949a94aa120f89154c77a0fdab1527c65774ce15fa844dee3af3a219e455804`.
See `docs/evidence/android-2026-10-22-thor-full-seed-pass.json`. Runtime SQL was
not applied; compiled world/character updater and gameplay remain separate.

Physical Thor version 5 **passed the original auth updater and dry run** after
all 27 unchanged auth imports. The auth process exited 0 in 107 ms, its
completion marker was observed, and MariaDB received SQL shutdown and exited 0.
All 41 invocation outcomes are present with no timeout, cancellation or validation
failure. This qualifies the disposable auth updater gate, not a running listener,
full seed, worldserver, game login or abnormal lifecycle handling. See
`docs/evidence/android-2026-10-22-thor-resolver-pass.json`.

## Preserve these constraints

- Target Android 13 ARM64/Bionic on AYN Thor; preserve core C++20 and all 52
  module directories. Do not substitute Pocket Realm's CMaNGOS/1.12 core.
- Full-seed compatibility on Android passed in private version 7. The source
  documents MySQL 8; the Android provider is MariaDB 12.3.2. Preserve the exact
  passing SQL and provider. A host probe does not qualify the remaining compiled
  updater, persistent service or gameplay gates.
- Seed is populated and destructive: fresh empty installation only. Never
  distribute the seed or automatically apply it to an existing user database.
- Runtime SQL must remain beside `Data/data/sql`; preserve updater checks,
  hashes and release receipts. Do not bypass a failure by disabling updates.
- No supplied DBC/maps/vmaps/mmaps or WarcraftXL client. World-ready requires
  matching data and actual server evidence. The setup APK cannot launch games.
- Client later: exact build 12340; preserve WarcraftXL adjunct files and
  developer compatibility rule `SET specular "0"`. Validate 32-bit WoW with
  x64 asset host and their IPC on Thor before declaring a usable client.
- Lifecycle later: database → authserver → worldserver, reverse graceful stop,
  controlled world restart, private generated credentials and local binding.
- Do not copy GPL/AGPL reference implementations into MIT files without
  resolving notices and compatible distribution licensing.

## Implemented foundation

`android/` is an independently written setup UI, transactional private-input
store and disposable native database diagnostic. Importing an archive does not
execute it. The diagnostic service uses only APK-managed native executables,
creates a new private database, checks it and shuts it down. Its default mode
uses no imported seed; the optional private auth mode verifies the exact private
database ZIP and stages only 27 auth table files plus one auth updater into a
fresh disposable schema. Neither mode opens player saves or installs a realm.
`tools/fta_source.py` stages the reviewed source;
`tools/build_native.py` drives bounded native builds. `tools/fta_database.py`
and `tools/run_database_gate.py` inventory and test the private database in an
isolated disposable instance. Inputs remain external; the public provenance
manifest contains hashes and module/license metadata only.

`tools/stage_android_provider.py` reproduces the exact MariaDB 12.3.2 Bionic
provider from pinned packages or a byte-verified upstream APK. The latter was
needed because the historical package URL returned 404. Eleven native files and
the bootstrap match the original provider lock. See `docs/android-provider.md`
for notices, source references and build instructions. No provider ELF is in Git.

Measured host gate: MariaDB 12.3.2 imports the entire unchanged seed and traverses
all 236 runtime SQL files with source-confirmed changed-script behavior: 211
existing scripts match, six are new and 19 are reapplied after hash changes.
Default strict mode still stops on a changed receipt; the explicit policy probe
records the change and updates its receipt only after SQL success. This does not
qualify the compiled world/character updater. MariaDB 10.11 fails the
original MySQL 8 collation; do not silently substitute that older engine.

The reviewed MariaDB C API port builds and executes the host authserver. Its
compiled original auth updater, local TCP listener, graceful stop and explicit
TLS-policy refusal probes passed against exact MariaDB 12.3.2. The minimal
unchanged 27-table seed and original `--dry-run` also passed on that host. This
does not qualify the compiled world/character updater or Android. Original MySQL
paths are preserved. Explicit TLS policies on the MariaDB path are rejected
before authentication until equivalent required-TLS behavior is qualified;
empty SSL settings/private local transport remain supported. No permissive
fallback is allowed.

Actual Android API 33 ARM64/Bionic authserver compilation now passed with NDK
r28c, C++20, Release, static scripts/modules and the reviewed `android-arm64-v1`
profile. All 52 module directories remain intact; auth-only compilation does
not compile or qualify their world code. Authserver SHA256:
`acf186557515897a4c359ac2ceee889f52c1482f36f8b012db007a8625b5fd05`.
The real private stage passed ELF/dependency checks with source-built Connector/C
3.4.9 and preserved all base database-provider native/data/legal payloads.
Source, seed and server binaries remain private. See `docs/auth-runtime.md` and
`docs/private-diagnostics.md` for exact evidence, staging and persistent signing.
Private APK `0.3.0-auth-diagnostic` (version code 3) passed all 61 Android unit
tests, lint, assembly, signature verification and exact byte checks for 114
packaged runtime/notice files. Its SHA256 is
`aff0b0d4b751690a8feae5aab4f272ea5133b52ffa0fa6286bf45ea90cbf8908`;
see `docs/evidence/android-2026-10-22-private-auth-apk.json`. It uses the persistent
private development key; reuse that key and increase the version code for later
user builds.

The exact version 3 APK has now executed on the physical AYN Thor. Its exported
report reached `AUTH_UPDATER` after database bootstrap, socket health, generated
credentials, collation/Unicode and rollback checks, all 27 auth imports and the
authserver version command. The auth dry-run diagnostic failed; the old report
omits exit codes and useful auth error categories, so its exact cause is unknown.
Clean shutdown, full seed and world readiness remain unqualified. See
`docs/evidence/android-2026-10-22-thor-auth-attempt.json` for the bounded evidence.

Source investigation independently found a Unix-socket pool bug: the first
connection mutates shared host `.` to `localhost`, causing the second connection
to parse the socket pathname as a numeric port. Preserve the original socket
transport across every connection and reconnect; do not use TCP fallback or
weaken updater checks. The reviewed `android-arm64-socket-v2` profile fixes this
and preserves the old profile for version 3 provenance. Exact function-body
tests passed repeated opens/reconnects, port validation and updater client
routing. The corrected ARM64 authserver rebuilt successfully with SHA256
`bfa532f7c91d59410c10104a2b7e5ce40d5763c59dad36f16c625bf152f3428c`.
Version 4 now supplies physical-device evidence for its socket connection pool.
It also records native invocation outcomes and safe auth error categories.

The corrected private APK is `0.3.1-auth-socket-diagnostic`, version code 4,
SHA256 `75e127898c972cf9d2d7e54400cb06c59b37c24ba9dd9ad5e93b3f1a3fc33eb5`.
Its clean Gradle build passed all 83 Android tests, lint and assembly. Signature,
alignment and exact checks for 114 runtime/notice files passed. The certificate
matches version 3, permitting an ordinary update with imports retained. Use a
version code greater than 4 and the same private key for later builds. See
`docs/evidence/android-2026-10-22-private-auth-socket-apk.json`.

The exact version 4 Thor report confirms database readiness and SQL shutdown
with exit 0. All 27 auth tables imported, the connection pool opened, and the
original updater reported the auth database up to date. Authserver then exited 1
with no valid realms; the dry run remains unqualified. See
`docs/evidence/android-2026-10-22-thor-socket-attempt.json`.

Source inspection found a second portability defect: Boost `all_matching`
passes `AI_ALL`, which Android 13 Bionic rejects before resolving numeric
addresses. The private seed's realm remains eligible and its addresses are
numeric IPv4. An Android-only zero-flags resolver branch preserves the existing
resolver, seed, realm filter and network restrictions. Do not add a synthetic
realm, rewrite its address or grant network permission to bypass this failure.
The exact Thor resolver error was not exported; the source defect is consistent
with the reported realm lookup failure.

Direct measurement corrected one assessment count: **563 seed tables total**
(27 auth, 165 characters, 371 world), plus one world trigger, and 236 runtime
SQL files. Do not reintroduce the handoff's 372-world-table expectation.

## Next concrete work

1. Preserve the passing physical version 5 auth evidence. Still qualify explicit
   cancellation, background and process-death cleanup on Thor; the normal passing
   dry run does not establish those behaviors.
2. Preserve the passing physical version 7 full-seed evidence and exact APK.
   Later updater diagnostics must first repeat its fresh import and original
   three-schema/receipt baseline checks before applying runtime SQL. Keep the
   early ASCII probe, bounded current-attempt evidence and observed SQL shutdown.
3. Preserve the complete v13 native build, actual private stage and version 8 APK
   evidence. Compiler-led patches preserve C++20/all 52 modules, signed 64-bit
   timestamp values and atomic shared-settings semantics. Link-time debug
   stripping keeps the unchanged final ELF within Android runtime size bounds;
   symbols remain available. See `docs/native-build.md` and `docs/world-runtime.md`.
4. Preserve physical versions 8/9 and the exact host time-zone reproduction.
   Version 10 supplies the all-24-check Thor result and exact full snapshot match.
   The compiled updater gate is qualified; expected metadata remains unchanged.
   If a later change requires rerunning this mode, it first repeats the
   passing full-seed gate, then runs original **normal** world startup through
   the missing-map boundary. **Do not use world `--dry-run` for this gate**:
   failed SQL can write receipts before a final error-counter check that missing
   DBCs prevent reaching. Follow `docs/world-updater-gate.md`.
5. With that gate passed, qualify abnormal lifecycle/cancellation/process-death
   cleanup and matching DBC/maps/vmaps/mmaps before persistent services or realm
   gameplay. No game assets were supplied. Later work includes controlled
   database → auth → world startup, account provisioning and restart-safe storage.

The diagnostic uses ordinary process priority, the original World Inspector
queue-directory environment override with a fresh empty private queue, and
bounded SQL-safe error exports. All 49 original module configurations are kept.
The actual Database.zip regression ran among all 136 clean Gradle JVM tests.
All 24 production Kotlin SQL/parser checks and 783 extracted-source host
statement preparations passed previously; final SQL bytes remain identical.
The latter only prepared/deallocated queries, without executing their bodies or
Bionic connection pools. The final ELF contains all 763 registration variants
(753 distinct SQL strings) and the original branch query. Final bindings reuse
those host measurements without claiming a SQL rerun. Only the physical report
can qualify this compiled updater on Android.

Static startup review also found an original gameplay-cache issue to investigate
before realm qualification: `modules/Attriboost/src/Attriboost.cpp:284` reads
`Settings` from index 8 of `SELECT *`, which the supplied table DDL identifies as
`achievement_granted`; its later per-player loader uses index 9. Both are unsigned
integer columns, so this is not a query/type blocker for the updater gate. The
original implementation remains unchanged; module data correctness and gameplay
need their own validation.

## Resource and evidence discipline

Use one owner per implementation area, bounded logs and concise handoffs.
Do not paste full source, database dumps or compiler output into agent chats.
The native driver caps build parallelism; use one heavy native build at a time.
Android Gradle caps workers and heap. Never equate synthetic tests, a host
build, a setup APK or a CI artifact with a successful Thor runtime test.

The initial shell lacked build tools; host tools/dependencies are now staged
privately and the exact host database gate has run. Do not repeat that old
missing-tools status as a current blocker. ARM64 auth compilation, private staging and the physical auth dry run passed;
the compiled updater now has passing physical evidence; abnormal lifecycle
behavior still requires its own physical evidence. The complete v13 world cross-build and private APK checks passed. Inspect the exact commit's GitHub CI results before
reporting setup APK checks as passed; native compilation has its own build report.

Workspace recovery: previous scratch bytes and world objects were pruned by
maintenance. The exact Android toolchain was restored and the private runtime
recovered byte-for-byte from the saved version 6 APK; the signing capsule was
reused. Do not claim the old world object cache is still present. The original
source archive was recovered and profile v7 reproduced exactly before
resuming native work. Target dependencies were reproduced from pinned inputs. During the final native
check, a stale scripts archive was detected and refused. A sole-owner targeted
normal rebuild repaired it; three subsequent complete checks matched source,
report, artifacts and seven affected archive members. Keep native/Gradle builds
under one filesystem owner with all other workspace activity paused to avoid
recurrence; the underlying replacement cause was not conclusively established.
Saved artifact identities and existing source profiles remain authoritative.

Current recovery: workspace maintenance removed previous toolchains, source trees,
build caches and private runtime files. Version 8 APK, corresponding source, seed
and signing capsule were recovered from saved artifacts. All 221 runtime/notice
inputs were verified against the exact APK and all native delivery bytes remain
identical. Do not claim old static archives or build reports are present. Exact
Android tooling and host MariaDB were restored with published checksum/version
checks; the new Java/Kotlin code and host metadata probe ran this session.
