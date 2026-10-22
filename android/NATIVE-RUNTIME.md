# Native runtime integration contract

The Android 13 target keeps `targetSdk=35`. Executable code is supplied by the signed APK and extracted with `extractNativeLibs=true` / legacy JNI packaging. Every provider ELF is retained byte-for-byte (`keepDebugSymbols`); staging paths are supplied with `-PftaProviderDir=/absolute/stage`. No imported or generated ELF is executed from writable storage.

The in-repository C++ JNI library `libfta_process.so` owns native processes. Kotlin uses `com.russianranger.fta.runtime.NativeProcessBridge` with opaque handles and fixed argument arrays, never a shell. The helper sets a parent-death signal, owns process groups, bounds logs, and provides deadline-based waits and TERM/KILL shutdown. Server, client, and library paths must resolve to the checked APK native library directory.

## Manifest and staged layout

The stage has `assets/fta-runtime-manifest.json`, `assets/runtime/data/...`, and `jniLibs/arm64-v8a/*.so`. Manifest format version 1 contains:

- `abi`: `arm64-v8a`.
- `providerVersion`: exact engine version, initially `12.3.2`.
- `providerId`: optional provenance identifier.
- `capabilities`: `database` initially; `authserver` and `worldserver` require their matching executable roles and dependencies.
- `files`: exact `path`, `sha256`, `size`, and `role` records. Native paths are flat `native/lib*.so`; data paths are beneath `data/`. Supported roles are `database-server`, `database-client`, `auth-server`, `world-server`, `library`, and `data`.
- Native records may additionally pin `needed`, `logicalName`, `sonames`, `interpreter`, and `minimumLoadAlignment`. Shared library logical names preserve the original SONAMEs. Private SONAME link directories are scoped by both manifest and current APK install path so an unchanged-provider app update cannot retain stale library paths. Symlinks point only to integrity-checked immutable APK files; `LD_LIBRARY_PATH` contains that link directory and the APK native library directory.
- Optional `bootstrap` is a `path` / `sha256` / `size` reference to an exactly matching declared data file. Current path: `data/provider/bootstrap.sql`; original server data is under `data/provider/share/mysql/`.
- Optional `qualification` booleans are informational provenance. They never establish readiness or bypass runtime checks.

Current executable basenames are `libfta_mariadbd.so` and `libfta_mariadb_client.so`. The provider has no compiled AzerothCore authserver or worldserver yet.

The APK manifest is executable authority. A user-selected runtime ZIP uses the same manifest and payload paths but remains an inert validation generation. Its hashes, ELF headers, paths and ZIP mode metadata are checked. Importing it cannot replace the APK provider or enable server start.

## Database diagnostic

The foreground service is nonexported and runs in a separate `:runtime` process. The application skips UI import initialization in that process, preventing it from cleaning an ongoing source import. A user explicitly starts a bounded disposable database test. The service:

1. Rechecks the packaged provider hashes, ARM64 ELF metadata and dependency closure.
2. Copies and verifies declared provider data into a fresh private diagnostic generation, with at least 512 MiB free storage.
3. Feeds the pinned bootstrap SQL directly to `mariadbd --bootstrap`; no installer shell is run. It records the matching `mariadb_upgrade_info` marker only after successful bootstrap.
4. Starts the daemon with `--skip-networking` and `--skip-name-resolve`, an app-private Unix socket, an empty private plugin directory, and bounded memory settings. The socket uses a short path outside the generation directory to fit `sockaddr_un` limits.
5. Waits for an actual `SELECT 1` client response. Immediately replaces the initial local root password with a generated random secret kept in a private temporary options file.
6. Checks the exact server version, populated system schema, actual MySQL 8 collation DDL with Unicode insertion/readback, and an InnoDB transaction rollback in a disposable test schema.
7. Runs SQL `SHUTDOWN`, confirms clean exit, and removes temporary credentials. Cancellation/failure falls back to the JNI helper's bounded process-group shutdown.

The test uses no imported database seed and opens no player database. It retains at most three recent diagnostic generations with private logs; credentials are removed. A six-minute wake lock is bounded and released on completion. Native stdout/stderr are capped by the helper. No network permission is requested, and the test never enables a TCP listener.

A passed database diagnostic proves only the measured bootstrap, local engine checks, and shutdown on the device where it ran. It does **not** prove the populated seed imports, SQL updates/routines/triggers apply, AzerothCore compiles, world assets match, or a game world starts. Those remain separate gates. Process start, background lifecycle, stop, crash recovery, and performance still require exact-build Android device evidence.

## Lifecycle policy for future server drivers

`RuntimeCoordinator` starts database → authserver → worldserver, requires measured driver readiness, rolls back newly started components on failure, and stops in reverse order. World restart preserves database and authserver. A failed stop keeps ownership of any live component and preserves its dependencies. This policy is unit-tested with synthetic drivers; no placeholder driver claims to run missing game binaries.

## Shareable diagnostic report

The screen exports JSON through Android's Create Document picker. Report creation runs on a worker and records exact APK SHA-256, native supervisor SHA-256, full provider manifest/hash, app version, Android API/ABI/device model, last fixed stage/result, and bounded native failure summaries. It does not export a database or private log verbatim. Summaries allow only numeric MariaDB error/line references, safe ELF loader library/symbol identifiers, and fixed failure categories; raw SQL, query rows, paths, selected URIs, generated passwords, and unrecognized output are omitted. The report keeps seed/authserver/worldserver qualification explicitly false.
