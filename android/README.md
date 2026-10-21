# Android foundation

This independently written MIT-licensed app prepares the two exact 2026-10-21 handoff inputs. It does not contain a database, server, client, player records, or credentials. Archive verification is preparation evidence, **not** proof of MariaDB compatibility, successful compilation, or a running world.

Build with JDK 17 and Android SDK 35:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The Gradle 8.9 distribution is SHA-256 pinned. AGP 8.7.3 and Kotlin 2.0.21 are fixed versions. Gradle has two workers and a 1.5 GiB maximum heap. The standard `gradle-wrapper.jar` is from the official Gradle `v8.9.0` tag; it is Apache-2.0 licensed (see `gradle/wrapper/NOTICE`).

The storage picker leaves the selected source unchanged. A single background worker streams 256 KiB chunks into app-private storage, enforces the exact input size and SHA-256, syncs the file, writes a receipt, and atomically publishes a verified generation. A failed replacement preserves the previous generation. On process restart, active archives are streamed through SHA-256 again on the background worker before the checklist accepts them. Incomplete copies and unpublished generations are removed; select the same ZIP to retry from the beginning. Imports do not continue through process death and are not byte-resumable. Rotation preserves the in-process import. The screen stays awake while the importing screen is visible; no foreground service or hidden ongoing background operation is claimed.

Android backup is disabled because the seed contains account and character records. No network/storage permissions are requested. Small setup logs contain fixed status messages only. Server controls remain disabled until later development supplies and qualifies the native database provider, AzerothCore binaries, and matching game data. The client importer and native process supervisor are future milestones.

Unit tests exercise a real temporary filesystem: hash/size failures, input I/O failures, retained previous generations, interrupted-copy recovery, pointer traversal, missing archives, and bounded reads. Actual Android 13 storage picker, rotation, lifecycle, layout, and controller navigation still need device validation.
