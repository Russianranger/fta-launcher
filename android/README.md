# Android server diagnostic foundation

This independently written app prepares the exact handoff inputs and implements isolated native database diagnostics when a matching provider is staged into the APK. It contains no player seed, client assets, or reusable login credentials. Game-server start remains unavailable until AzerothCore and its data are qualified.

Build with JDK 17, Android SDK 35, NDK 28.2.13676358, and CMake 3.22.1:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

An approved private provider stage can be included with `-PftaProviderDir=/absolute/stage`. Without that argument the app still supports input and runtime-bundle validation, while native database testing stays disabled. See [NATIVE-RUNTIME.md](NATIVE-RUNTIME.md) for the exact contract and limits.

The Gradle 8.9 distribution is SHA-256 pinned; AGP 8.7.3 and Kotlin 2.0.21 are fixed. Gradle has two workers and a 1.5 GiB maximum heap. The official Gradle wrapper retains Apache-2.0 notices. Native provider dependencies retain their own licenses; packaging bytes is separate from qualifying redistribution.

The storage picker leaves original files unchanged. Source and seed imports stream 256 KiB chunks into private generations, enforce exact sizes and SHA-256s, and publish atomically. Startup rechecks active hashes on a worker before the checklist accepts them. Failed replacements retain the previous copy. Incomplete copies are cleaned at restart; select the file to retry from the beginning. Imports are restart-safe, not byte-resumable.

Runtime ZIP validation additionally checks strict manifests, ARM64 ELF metadata, ZIP paths and Unix modes, exact payload membership, per-file/total size limits, and all hashes. Imported executable bytes remain inert. Only immutable APK native libraries can run.

The explicit native database test runs in a separate nonexported foreground service, bootstraps a fresh socket-only MariaDB instance, tests local schema/transaction/collation behavior, then shuts down. It never opens the imported seed or player saves. Temporary credentials stay private. Game-server and WarcraftXL client integration remain future gates.

Android backup is disabled. The app requests foreground-service, optional notification, and bounded wake-lock permissions; it requests no network or broad storage permission. Fixed setup messages and raw native diagnostic output remain private. The diagnostic report export includes exact APK/provider identities and allowlisted error summaries while omitting SQL, credentials, rows and private paths. Native output is bounded, and process ownership survives UI changes through the service/JNI supervisor.

Pure JVM tests cover pinned-input failure/recovery, adversarial runtime bundles, and dependency lifecycle failure ownership. Android compile/lint checks and exact-device bootstrap, background, stop, crash recovery, layout, and controller validation remain necessary evidence; a generated APK alone does not qualify a running game world.
