# Development handoff

Started 2026-10-21 from the supplied From the Ashes Android Integration
Assessment. Repository: `Russianranger/fta-launcher`, integration branch `main`.
The owner authorizes creating and pushing files here. Reference repositories
are read-only unless separately authorized.

## Preserve these constraints

- Target Android 13 ARM64/Bionic on AYN Thor; preserve core C++20 and all 52
  module directories. Do not substitute Pocket Realm's CMaNGOS/1.12 core.
- Database compatibility is unproven. The source documents MySQL 8; the proposed
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

`android/` is an independently written setup UI and transactional private-input
store. It imports only the reviewed archive hashes; it does not import SQL or
execute native services. `tools/fta_source.py` stages the reviewed source;
`tools/build_native.py` drives bounded native builds. `tools/fta_database.py`
and `tools/run_database_gate.py` inventory and test the private database in an
isolated disposable instance. Inputs remain external; the public provenance
manifest contains hashes and module/license metadata only.

Direct measurement corrected one assessment count: **563 seed tables total**
(27 auth, 165 characters, 371 world), plus one world trigger, and 236 runtime
SQL files. Do not reintroduce the handoff's 372-world-table expectation.

## Next concrete work

1. Stage the hash-pinned Bionic ARM64 MariaDB dependency closure using Pocket
   Realm's provider lockfile as a reference. Resolve Linux tool paths explicitly
   instead of copying its Windows `.exe` assumptions.
2. Run the matching private seed on that exact engine in a disposable Android
   environment. Record engine version, schemas, trigger/routines, receipt
   hashes/counts and exact compatibility failures without publishing records.
3. Qualify the Android Clang/CMake toolchain and cross-built Boost/OpenSSL/
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

No MariaDB binary/client, Android SDK, Clang, CMake or Ninja was present in the
initial development shell. Actual import/ARM64 server compilation must be
reported as open until executed. GitHub CI provides the Android foundation
compiler environment; inspect the exact commit's run before claiming success.
