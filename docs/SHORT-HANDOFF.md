# Development handoff

Started 2026-10-21 from the supplied From the Ashes Android Integration
Assessment. Repository: `Russianranger/fta-launcher`, integration branch `main`.
The owner authorizes creating and pushing files here. Reference repositories
are read-only unless separately authorized.

## Preserve these constraints

- Target Android 13 ARM64/Bionic on AYN Thor; preserve core C++20 and all 52
  module directories. Do not substitute Pocket Realm's CMaNGOS/1.12 core.
- Database compatibility on Android is unproven. The source documents MySQL 8; the proposed
  Android provider is MariaDB 12.3.2. A host import is not Android qualification.
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
store and disposable native database diagnostic. Imports remain inert. The
diagnostic service uses only APK-managed native executables, creates a new
private database, checks it and shuts it down; it never opens imported seeds or
player saves. `tools/fta_source.py` stages the reviewed source;
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
replace qualification of the compiled original updater. MariaDB 10.11 fails the
original MySQL 8 collation; do not silently substitute that older engine.

The reviewed MariaDB C API port now builds and executes host `authserver --help`.
Original MySQL paths are preserved. Explicit TLS policies on the MariaDB path
are rejected before authentication until equivalent required-TLS behavior is
qualified; empty SSL settings/private local transport remain supported. No
permissive fallback is allowed. Native Android server compilation is separate.

Direct measurement corrected one assessment count: **563 seed tables total**
(27 auth, 165 characters, 371 world), plus one world trigger, and 236 runtime
SQL files. Do not reintroduce the handoff's 372-world-table expectation.

## Next concrete work

1. Qualify the packaged database diagnostic on Android: exact bootstrap, socket
   health, private credentials, collation/transaction checks and clean shutdown.
   Use its exported report; do not infer success from packaged ELF hashes.
2. Run the matching private seed on that exact engine in a disposable Android
   environment. Record engine version, schemas, trigger/routines, receipt
   hashes/counts and exact compatibility failures without publishing records.
3. Continue the Android Clang/CMake toolchain and cross-built Boost/OpenSSL/
   MariaDB dependencies. Configure authserver first with one or two compile
   jobs. Record actual compiler blockers and make reviewable bounded patches
   to a separate port layer/patch series, keeping original archive provenance.
4. Qualify worldserver with every required module. Only then package controlled
   native services, account provisioning and restart-safe database installation.

## Resource and evidence discipline

Use one owner per implementation area, bounded logs and concise handoffs.
Do not paste full source, database dumps or compiler output into agent chats.
The native driver caps build parallelism; use one heavy native build at a time.
Android Gradle caps workers and heap. Never equate synthetic tests, a host
build, a setup APK or a CI artifact with a successful Thor runtime test.

The initial shell lacked build tools; host tools/dependencies are now staged
privately and the exact host database gate has run. Do not repeat that old
missing-tools status as a current blocker. Native ARM64 server compilation and
physical-device diagnostics still require their own measured evidence. Inspect
the exact commit's GitHub CI run before claiming Android compilation success.
