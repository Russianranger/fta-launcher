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

The screen exports JSON through Android's Create Document picker. Report creation runs on a worker and records exact APK SHA-256, native supervisor SHA-256, full provider manifest/hash, app version, Android API/ABI/device model, last fixed stage/result, and bounded native failure summaries. It does not export a database or private log verbatim. Summaries allow only numeric MariaDB error/line references, safe ELF loader library/symbol identifiers, and fixed failure categories; raw SQL, query rows, paths, selected URIs, generated passwords, and unrecognized output are omitted. The report keeps full-seed/authserver/worldserver qualification explicitly false and separately reports bounded auth-updater evidence when present.

## Optional original auth updater dry run

The default APK remains a database-only diagnostic. When a separately qualified stage includes `authserver` capability, an integrity-pinned `auth-server` executable, and `data/metadata/auth-seed-lock.json`, the screen can expose a separate auth updater test. It additionally requires the user's exact verified private `Database.zip`; no seed SQL is bundled in the app.

The runtime process locates that verified input without constructing the UI import store or cleaning any UI staging. The full archive is hashed again. The metadata pins exactly 27 `acore_auth` table files and one auth updater, including all per-file sizes/hashes and the four required `Data/data/sql` auth directories. Only these files are copied into a fresh diagnostic stage. The native CLI receives one whole SQL file at a time; there is no statement splitting or SQL rewriting. An absent `acore_auth` schema is required in the newly created disposable database before import. Character/world data and user saves are never opened.

A generated database-only account gets privileges on that disposable auth schema. The generated `[authserver]` config preserves updater settings `1/1/1/0/1/3`, one worker and synchronous connection, private Unix-socket connection information, exact `SourceDirectory`, and the packaged native client path. Config and updater temporary credential files remain private and are removed on completion. The original executable runs `--version`, then `--config <private> --dry-run`; exit zero and the original completion marker are required. `RealmServerPort=-1` deliberately fails closed if the dry-run flag were omitted; original source returns before port validation in dry-run mode. No auth TCP listener or network permission is introduced, and seed account/realm records are not rewritten.

The diagnostic receipt records only planned/imported table counts, pinned archive and auth executable hashes, updater count, and the dry-run result. The database then follows the existing clean shutdown path. A passed original updater dry run qualifies that bounded path only; game login, a running auth listener, full seed compatibility and worldserver readiness remain separate gates. Android execution remains unqualified until measured on the exact device/build.

## Private update signing and versions

Default CI debug signing is unchanged. A private test build can use a persistent externally stored keystore with `-PftaSigningStore=/private/path/store.jks`, optional `-PftaSigningAlias=ftadevelopment`, and the environment variables `FTA_SIGNING_STORE_PASSWORD` and `FTA_SIGNING_KEY_PASSWORD`. Both debug and release use that signing config only when the store property is explicitly supplied. Keys and passwords do not belong in Git or provider metadata. Matching signatures allow future test APKs to update without deleting private imports.

`-PftaVersionCode=<positive integer>` and `-PftaVersionName=<version>` can identify private diagnostic milestones. Without overrides, version code 2 and the database diagnostic version remain unchanged. The screen derives its displayed version from the installed package.
