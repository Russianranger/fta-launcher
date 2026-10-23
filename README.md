# From the Ashes for Android

Independent Android launcher and native server port for **From the Ashes**, the
customized AzerothCore **WoW 3.3.5a / build 12340** distribution. Initial target:
AYN Thor, Android 13, Snapdragon 8 Gen 2, Adreno 740, ARM64.

Development includes a setup application, a packaged database diagnostic and
reproducible input/build tooling. **A playable realm and game client remain
unqualified.** The default database diagnostic creates a fresh disposable
instance. A separately staged private auth diagnostic can import only the exact
27-table auth seed and run the original authserver's updater dry run. The full-seed
test imports all 563 tables and one trigger into its own disposable database,
then verifies the original catalog and receipt fingerprints. These tests do not
install a persistent realm or open player saves.

## Current implementation

- Android setup app: private storage imports through Android's file picker,
  streaming SHA256 checks, visible progress and persistent verified input state.
- Native database diagnostic: APK-managed ARM64/Bionic MariaDB, private bootstrap,
  socket health, generated credentials, collation/transaction probes and graceful
  shutdown in a separate foreground service. Native process ownership uses JNI
  parent-death signals, bounded logs and process-group shutdown.
- Pinned source distribution: safe external staging, deterministic tree digest,
  all 52 module names and license-file digests.
- Database compatibility tooling: verified seed inventory, fresh-only planning
  and an isolated disposable MariaDB import runner. SQL goes through MariaDB's
  own client, preserving `DELIMITER` and stored-routine syntax.
- Measured host compatibility: the exact MariaDB 12.3.2 engine imports all 563
  seed tables and one world trigger unchanged, then traverses all 236 runtime SQL files
  under the source-confirmed updater policy. This is host evidence, not Android
  or compiled world/character-updater qualification. A separate compiled host
  authserver passes its original updater, local listener, graceful stop and
  explicit TLS-policy refusal probes.
- Memory-bounded native build driver: C++20, Release, static scripts/modules,
  authserver before worldserver, explicit host versus Android qualification.
- Actual ARM64/Bionic authserver compile and private runtime staging passed.
  The reviewed Android port preserves all 52 module directories; auth-only
  compilation does not qualify worldserver or its modules. The private stage
  preserves the database provider and adds the measured Connector/C dependency.
  The exact private version 5 APK passed on physical Thor: all 27 unchanged auth
  imports, original auth updater and dry-run completion with native exit 0, then
  observed SQL shutdown and database exit 0. The resolver correction preserves
  the seed and network restrictions. A listener and world remain unqualified.
- CI: Python safety tests, Android unit tests/lint/debug compilation and an APK
  checksum. Debug artifacts are setup previews, not playable releases.

The public repository contains **no database seed, accounts, client files,
game data or compiled game server**. Provider binaries are reproducible build
inputs with pinned identities and retained notices. Pocket Realm and Ashen Order remain
unchanged. Their existing WoW 1.12/CMaNGOS binaries cannot serve this build.

## Build the setup app

Use JDK 17, SDK 35, NDK 28.2.13676358, CMake 3.22.1 and the pinned Gradle wrapper:

```bash
cd android
./gradlew --no-daemon --max-workers=2 testDebugUnitTest lintDebug assembleDebug
```

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`.
Successful `main` builds publish a direct APK download and SHA256 under
[Releases](https://github.com/Russianranger/fta-launcher/releases). Actions artifacts
are also retained for 14 days.

See [`docs/android-provider.md`](docs/android-provider.md) to reproduce the exact
provider stage, then build with `-PftaProviderDir=/absolute/fta-provider-stage`.
Without the provider, the setup app builds but its database test stays unavailable.
Imported runtime bundles are checked inert copies and cannot replace executable
code from the installed APK.

The optional auth stage is for a private diagnostic APK, not a public release.
See [`docs/auth-runtime.md`](docs/auth-runtime.md) for the measured build/stage
identities and [`docs/private-diagnostics.md`](docs/private-diagnostics.md) for
signing and validation. Latest private version **0.6.1-runtime-recovery**
(code **12**) passed all **164 Android tests**, lint, signing/alignment and 221 exact
runtime/notice checks. The provider, authserver, worldserver and dependencies
(all 15 other native payloads) match code 11 byte for byte. Its process supervisor
adds a read-only owned-child identity API. The same persistent certificate permits
an in-place update with imports retained. See [runtime recovery instructions](docs/runtime-recovery-diagnostic.md)
and [exact APK evidence](docs/evidence/android-2026-10-23-private-runtime-recovery-apk.json).

Physical code **11 passed database lifecycle and screen-off health**, with all
ten observations and 85 complete native records. The [physical evidence](docs/evidence/android-2026-10-23-thor-lifecycle-pass.json)
qualifies cancellation, clean restart, forced database-stop recovery and screen-off
SQL operation. Code 12 adds one automatic test for the actual Stop action and
separate Android service-process death; its physical execution remains unverified.

Physical version **10 passed all 24 compiled-updater checks**, both complete
world-column snapshots matched the original host reference, and MariaDB shut
down cleanly. Version 9 localized the earlier single mismatch to the timestamp
defaults of `game_event`; version 10 pinned metadata readers to the original
reference's +07:00 session zone. Original SQL, strict expected hashes, global
time and native bytes remain unchanged. See the
[resolved diagnosis](docs/world-column-diagnostic.md) and
[passing physical evidence](docs/evidence/android-2026-10-23-thor-compiled-updater-pass.json).
No repeat updater run is needed for the unchanged implementation.
The production recovery protocol and JNI owner passed real host MariaDB process
termination and same-database recovery. This does not qualify Android services.
The existing provider/server source companion remains applicable; the updated
supervisor source is in this repository. No source ZIP is needed on the device.
The original auth updater/dry-run gate already
[passed on physical Thor in version 5](docs/evidence/android-2026-10-22-thor-resolver-pass.json);
version 6 reached 158 seed tables, then failed on its first ASCII column.
Version 7 explicitly binds the packaged charset directory and checks ASCII before
seed import. It [passed on physical Thor](docs/evidence/android-2026-10-22-thor-full-seed-pass.json):
all 563 tables and one trigger imported, all three schema/receipt baselines matched,
and MariaDB shut down cleanly with exit 0. Runtime SQL was not applied.
Public CI snapshots use a different
debug certificate and package only the database provider; use the separately
provided private APK to update the installed private diagnostic.

The app currently accepts the exact reviewed `From_The_Ashes_Source.zip` and
`Database.zip`. Selecting a file stores a private verified preparation input;
it does not extract or execute the contents, import SQL, or start a service.
Failed or interrupted replacement must preserve the last verified generation.

## Work with private inputs

Get the original ZIPs from your own files. Archive hashes and source inventory
are in [`manifests/reference-inputs.json`](manifests/reference-inputs.json).
Put private inputs outside the repository, or under the ignored `inputs/`
directory. Do not upload them as CI artifacts or releases.

See [`docs/database-gate.md`](docs/database-gate.md) for database inspection
and disposable compatibility tests, and [`docs/native-build.md`](docs/native-build.md)
for source staging and the native build driver. Run tooling tests with:

```bash
python3 -m unittest discover -s tests -v
python3 tools/check_repository.py
```

The original handoff counted 372 world tables. Direct ZIP inspection found
**371 world tables plus one trigger**, alongside 27 auth and 165 character
tables. Validation uses the measured archive inventory.

## Remaining gates

| Gate | Status |
| --- | --- |
| Independent repository, private-input boundary and archive identity | Implemented |
| Android setup/import foundation | Implemented; CI compilation required for each commit |
| Exact MariaDB 12.3.2 host seed and runtime-SQL probe | Passed: 563 tables, one trigger, 236 runtime scripts |
| Compiled host authserver and original auth updater | Passed; world/character updater remains separate |
| Android ARM64/Bionic authserver compile and private runtime stage | Passed; private APK build/payload checks passed; auth version command ran on Thor |
| Packaged Android database bootstrap/health/shutdown | Passed on Thor, including observed SQL shutdown and exit 0 |
| Optional private auth-only updater diagnostic | Passed on physical Thor: original updater/dry-run exit 0, 27 auth imports and clean SQL shutdown |
| Full seed import on pinned Android MariaDB provider | Passed on physical Thor: 563 tables, one trigger, exact three-schema/receipt baselines and clean SQL shutdown |
| Android ARM64 worldserver and all 52 required modules | Build and private staging passed; world readiness remains open |
| Original compiled three-database updater | Passed on physical Thor: 24/24 strict checks and exact before/after host snapshots |
| Disposable database cancellation/restart/crash recovery and screen-off health | Passed on physical Thor code 11: all ten observations, 85 complete records |
| Android Stop action and runtime service-process death/recovery | Production protocol/SQL/JNI host test passed; private code 12 physical test pending |
| Supervised persistent server APK and local account provisioning | Open |
| WarcraftXL full-client import, 3.3.5 extraction and Wine/x64 host integration | Open |
| Physical Thor startup, world entry, shutdown and thermal qualification | Open |

The next device gate is the [runtime recovery diagnostic](docs/runtime-recovery-diagnostic.md).
The ARM64 world build and original compiled updater have passing evidence;
preserve their C++20, all 52 modules, static Lua 5.2, source hashes and strict
[updater protocol](docs/world-updater-gate.md). Android service-process death and
persistent services remain separate. World startup also requires matching
DBC/maps/vmaps/mmaps, which were not supplied.

Preserve the original SQL/update
receipts; never restore the populated fresh-install seed over live saves.
The Windows launcher is a behavior reference, not an Android dependency.

Read [`docs/SHORT-HANDOFF.md`](docs/SHORT-HANDOFF.md) before continuing.
Licensing and reference provenance are recorded in [`NOTICE.md`](NOTICE.md).
