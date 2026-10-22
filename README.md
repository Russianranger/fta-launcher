# From the Ashes for Android

Independent Android launcher and native server port for **From the Ashes**, the
customized AzerothCore **WoW 3.3.5a / build 12340** distribution. Initial target:
AYN Thor, Android 13, Snapdragon 8 Gen 2, Adreno 740, ARM64.

Development includes a setup application, a packaged database diagnostic and
reproducible input/build tooling. **The app does not yet start an AzerothCore
server or game client.** The database diagnostic creates a fresh disposable
instance; it does not install the populated FTA seed or open player saves.

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
  seed tables and the trigger unchanged, then traverses all 236 runtime SQL files
  under the source-confirmed updater policy. This is host evidence, not Android
  or compiled-updater qualification.
- Memory-bounded native build driver: C++20, Release, static scripts/modules,
  authserver before worldserver, explicit host versus Android qualification.
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
| Exact MariaDB 12.3.2 host seed and runtime-SQL probe | Passed; compiled original updater still separate |
| Native authserver with reviewed MariaDB API port | Host build passed; Android build remains open |
| Packaged Android database bootstrap/health/shutdown | Diagnostic implemented; physical-device evidence required |
| Actual seed import on pinned Android MariaDB provider | Open |
| Android ARM64 authserver/worldserver and all modules | Open |
| Supervised persistent server APK and local account provisioning | Open |
| WarcraftXL full-client import, 3.3.5 extraction and Wine/x64 host integration | Open |
| Physical Thor startup, world entry, shutdown and thermal qualification | Open |

Native server porting and the Android database compatibility gate are underway.
Preserve the original SQL/update
receipts; never restore the populated fresh-install seed over live saves.
The Windows launcher is a behavior reference, not an Android dependency.

Read [`docs/SHORT-HANDOFF.md`](docs/SHORT-HANDOFF.md) before continuing.
Licensing and reference provenance are recorded in [`NOTICE.md`](NOTICE.md).
