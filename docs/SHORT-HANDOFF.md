# Development handoff

Started 2026-10-21 from the supplied From the Ashes Android Integration
Assessment. Repository: `Russianranger/fta-launcher`, integration branch `main`.
The owner authorizes creating and pushing files here. Reference repositories
are read-only unless separately authorized.

## Current checkpoint

Latest private Thor diagnostic: **0.3.2-auth-resolver-diagnostic**, version code
**5**, with profile `android-arm64-resolver-v3`. Its clean build passed 91 Android
tests, lint, signing/alignment and all 114 packaged-file identity checks.
APK SHA256: `468d9896a614ae8c3a1bf0b9ac33805d47455187d8d9f0fcf48976d2a0d1d913`.
See `docs/evidence/android-2026-10-22-private-auth-resolver-apk.json`.
Use this private APK for auth tests; public CI releases remain database-only.
Reuse the persistent private signing key and increase future version codes
beyond 5. Imports survive an ordinary update from versions 3 or 4.

Thor version 4 proved database readiness, 27 auth imports, pool opening and SQL
shutdown with exit 0. Its original updater reported up to date, but the dry run
failed realm lookup. Version 5 fixes Android resolver flags and adds read-only
realm eligibility booleans; its device dry-run result remains unqualified.

## Preserve these constraints

- Target Android 13 ARM64/Bionic on AYN Thor; preserve core C++20 and all 52
  module directories. Do not substitute Pocket Realm's CMaNGOS/1.12 core.
- Full-seed compatibility on Android remains unproven. The source documents
  MySQL 8; the Android provider is MariaDB 12.3.2. Thor has passed its bootstrap,
  socket, credential, collation/transaction and shutdown checks plus 27 auth
  imports. A host import does not qualify the remaining Android gates.
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

1. Retest the original auth updater using the corrected private version 5 APK on
   Thor. Export the current-attempt report to confirm native exit, dry-run
   completion and database clean shutdown, or identify the next exact failure.
   The imported database ZIP can remain in place; a source ZIP is not needed.
   Do not infer execution from compiled or packaged ELF hashes. Check
   stop/background/crash ownership too.
2. Run the matching full private seed on that exact engine in a disposable Android
   environment. Record engine version, schemas, trigger/routines, receipt
   hashes/counts and exact compatibility failures without publishing records.
3. Start the separate world build with the verified Android toolchain and target
   dependencies, including Readline for worldserver/bundled Lua. Preserve C++20,
   all 52 required modules and static scripts/modules; auth must still build
   first. Use one heavy build at a time and record actual compiler blockers as
   bounded reviewed patches. A read-only module audit found no further confirmed
   Android compile blocker; it is not compiler evidence.
4. Qualify compiled world/character updaters and worldserver with every required
   module, the disposable seed and matching DBC/maps/vmaps/mmaps. Those data
   assets were not supplied. Only then package controlled
   native services, account provisioning and restart-safe database installation.

## Resource and evidence discipline

Use one owner per implementation area, bounded logs and concise handoffs.
Do not paste full source, database dumps or compiler output into agent chats.
The native driver caps build parallelism; use one heavy native build at a time.
Android Gradle caps workers and heap. Never equate synthetic tests, a host
build, a setup APK or a CI artifact with a successful Thor runtime test.

The initial shell lacked build tools; host tools/dependencies are now staged
privately and the exact host database gate has run. Do not repeat that old
missing-tools status as a current blocker. ARM64 auth compilation and private
staging passed; world compilation and physical-device diagnostics still require
their own measured evidence. Inspect the exact commit's GitHub CI results before
reporting setup APK checks as passed; native compilation has its own build report.
