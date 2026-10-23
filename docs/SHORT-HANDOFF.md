# Development handoff

Started 2026-10-21 from the supplied From the Ashes Android Integration
Assessment. Repository: `Russianranger/fta-launcher`, integration branch `main`.
The owner authorizes creating and pushing files here. Reference repositories
are read-only unless separately authorized.

## Current checkpoint

Latest private diagnostic: **0.5.1-column-diagnostic**, version code **9**.
Its clean build passed **143 Android tests** (18 classes, no failures/errors/skips),
lint and assembly. Final signature/alignment and all **221** provider/notice
inputs passed. APK SHA256:
`1b478f1c1d99ca4928b47429068e6ad7adc6710540d9af4ecfbd0d3bbd6e09aa`.
See `docs/evidence/android-2026-10-22-private-column-apk.json`.

All **16 native payloads are byte-identical to version 8**, including the original
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
Overall compiled-updater qualification remains **false**.

Version 9 preserves both locks, original SQL, all 24 strict checks and the native
runtime. It adds bounded read-only world-column counts/hashes before and after
startup, per-table indices into the signed table list, and component hashes for
four source-known floating defaults. Reports omit SQL, defaults and row data.
No cause is asserted: the old baseline never fingerprinted seed columns, so the
mismatch may predate the updater. Source review found no unmodeled pre-map world
column ALTER. Floating-default formatting is only a hypothesis.

The actual new production Kotlin query/parser passed on a fresh exact host
MariaDB 12.3.2 seed and source-policy model: complete 371/372-table snapshots,
all canonical postconditions unchanged, utf8/utf8mb4 outputs identical, and
142,271-byte pretty diagnostic export. This is host evidence, not Android
execution. See `docs/world-column-diagnostic.md` and its hash-only fixtures.

**Next substantial physical test:** install version 9 over version 8, keep
Database.zip imported, have at least 3 GiB free, select **Test compiled
three-database updater**, then export the JSON report even if the same mismatch
appears. The new `worldColumnDiagnostic` distinguishes before/after shape and
localizes differing tables/components. A source ZIP is not required on device.
The 40-minute overall and 10-minute world-process deadlines remain unchanged.
Future private versions must use the same signing key and code **greater than 9**.
Public provider-only CI APKs use another certificate and cannot replace this
private update. Gameplay, persistent services and world readiness remain separate.

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
4. Preserve the physical version 8 partial updater evidence and obtain the
   version 9 column diagnostics before changing any expected metadata or
   treating the compiled updater as qualified. The mode first repeats the
   passing full-seed gate, then runs original **normal** world startup through
   the missing-map boundary. **Do not use world `--dry-run` for this gate**:
   failed SQL can write receipts before a final error-counter check that missing
   DBCs prevent reaching. Follow `docs/world-updater-gate.md`.
5. After that gate passes, qualify abnormal lifecycle/cancellation/process-death
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
the compiled-updater and lifecycle diagnostics require their own physical
evidence. The complete v13 world cross-build and private APK checks passed. Inspect the exact commit's GitHub CI results before
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
