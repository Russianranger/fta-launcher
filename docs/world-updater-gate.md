# Compiled world updater gate before game data

This is a source audit and proposed execution protocol, not a passed native or
device test. A successful full-seed import does not qualify the compiled world
or character updater. The supplied worldserver can execute those original
updaters before it requires maps or DBC files, but its complete dry run still
requires matching game data.

The audit used the exact supplied source distribution. The files listed below
are byte-identical between the original extracted source and the verified source
staging area. Locations are relative to `AzerothCore_Linux`; line numbers describe
that original source, before any future port changes.

## Original execution order

| Source location | Behavior |
| --- | --- |
| `src/server/apps/worldserver/Main.cpp:264–281` | Load module configs, register script and module loaders, initialize scripts, then call `StartDB()`. |
| `src/server/apps/worldserver/Main.cpp:430–483` | Add Login, Character and World pools to `DatabaseLoader`; execute its loader; set the realm identifier; perform startup database writes; load world version; call the post-database script hook. |
| `src/server/database/Database/DatabaseLoader.cpp:173–190` | Open pools, populate empty schemas, update databases and prepare statements, in that order. Each failed stage stops the loader. |
| `src/server/apps/worldserver/Main.cpp:285–310` | Mark the realm offline, load realm information, initialize metrics/secrets, then enter `World::SetInitialWorldSettings()`. |
| `src/server/game/World/World.cpp:323–348` | Load configuration/security/highest GUID state. Normal startup checks starting-area map and vmap files and terminates on their absence. The dry-run flag skips this particular check. |
| `src/server/game/World/World.cpp:384–387` | Load DBC stores unconditionally, including during a dry run. |
| `src/server/game/DataStores/DBCStores.cpp:630–655` | Missing, incompatible or outdated required DBC files terminate the process. |
| `src/server/game/World/World.cpp:1067–1078` | Only after world initialization reaches its end does a dry run check the accumulated SQL failure count and choose its success or failure exit. |
| `src/server/apps/worldserver/Main.cpp:323–359` | Remote access, SOAP and world listener startup follow world initialization. They are not reached through the original missing-map or missing-DBC exits. |

Consequently, a missing-data termination can occur **after the compiled original
three-database updater has executed**. It is never evidence that world startup
or the complete world dry run passed.

## Why a dry run is unsuitable for this narrower gate

`DBUpdater<T>::ApplyFile()` in
`src/server/database/Updater/DBUpdater.cpp:530–553` records an external SQL-client
failure. Normal mode then throws an `UpdateException`; dry-run mode continues.
`UpdateFetcher::ApplyUpdateFile()` in
`src/server/database/Updater/UpdateFetcher.cpp:374–385` writes an update receipt
after `Apply()` returns. A failed SQL file can therefore acquire a receipt during
a dry run. The accumulated failure count is inspected only at the late world
dry-run boundary, which missing DBC files prevent this environment from reaching.

Updater summaries and matching post-run receipt hashes alone would therefore be
insufficient to qualify a world updater run using `--dry-run` without DBC files.
Do not interpret its expected nonzero missing-data exit as successful dry-run
completion, or infer SQL success from newly written receipts.

## Candidate bounded execution protocol

Once a real worldserver and its target dependency closure are available, use
the original **normal startup**, omitting `--dry-run`, for a separate disposable
updater test:

1. Verify the exact compiled world ELF, reviewed source profile, C++20 build and
   all 52 required module directories and enabled static module/script targets.
   Verify the exact MariaDB provider, native SQL client and every dependency.
   This requires actual target execution; a host test is a different gate.
2. Create a fresh private datadir and generated credentials. Require all three
   diagnostic schemas to be absent before importing the unchanged pinned seed.
   Validate the seed inventory and original receipt baseline first. Never point
   this protocol at an existing installation, account database or player saves.
3. Stage the complete unchanged runtime SQL tree, preserving its original
   `data/sql` layout under the configured `SourceDirectory`. The database ZIP
   supplies 236 runtime SQL files. Inventory every source-selected include and
   module SQL directory; do not silently substitute an auth-only directory.
   The currently supplied source contains no module `.sql` files and the
   database ZIP contains no `modules/` tree; preserve that measured absence.
4. Set `MySQLExecutable` to the APK-managed verified SQL client and all database
   connection strings to generated private socket credentials. Keep the original
   updater mask `7`, redundancy and hash policy. Do not bypass changed scripts,
   updater errors, prepared statements or module registration. Inventory the
   actual include-directory selection and every missing-directory warning.
5. Point `DataDir` at a newly created, verified empty private directory. Leave
   the original game-data checks intact. Keep remote access, SOAP and clustering
   disabled, use a local bind configuration, retain the diagnostic APK's network
   restrictions, and supervise the entire process group with a bounded deadline
   and cancellation. The deliberately absent data is an observed stopping
   boundary, not a substitute asset set.
6. Run `worldserver --config <private-config>` under the native supervisor. Require
   complete nontruncated local diagnostic output, evidence that all three
   updater stages and statement preparation completed, and the **specific original
   missing-starting-map termination after `StartDB()`**. A signal, timeout,
   unrelated exit, earlier SQL error or incomplete output fails this protocol.
   Treat every SQL-client failure as a failure even if later output appears
   healthy. Keep raw logs private and export only fixed classifications.
7. After the world process and all its SQL-client children have exited, validate
   the exact resulting receipt names/hashes/states, known schema changes,
   release receipts and remaining routine counts against the approved updater
   policy. Do not use receipt counts alone. Record intentional startup changes
   separately: original normal startup clears online state, writes world-version
   information and updates realm flags in this disposable copy.
8. Request MariaDB SQL shutdown and observe exit 0. Record native outcomes,
   cancellation/timeout state, process-group cleanup and shutdown independently.

Even if every condition passes, the resulting claim is limited to compiled
original Login/Character/World updater execution against that exact disposable
seed and engine, ending at the expected original missing-game-data boundary.
World initialization, a complete dry run, module gameplay, listening services,
game login and physical-device lifecycle qualification remain separate.

No implementation of this protocol is claimed by this document. The next APK's
full-seed diagnostic imports and validates the seed only; it does not launch
worldserver or replay the runtime SQL as a stand-in for the compiled updater.

## Lua and data requirements

`modules/mod-ale/CMakeLists.txt:1–20` selects `lua52` by default and static Lua
linking. Its bundled CMake recipe pins Lua 5.2.4 with SHA256
`b9e2e4aad6789b3b63a056d442f7b39f0ecfca3ae0f1fc0ae4e9614401b69f4b`.
On Unix it defines `LUA_USE_LINUX` and links `dl`, `m` and Readline
(`modules/mod-ale/src/lualib/lua/CMakeLists.txt:40–55,77–82,111–115`).
Do not replace the default with LuaJIT or disable the module to avoid target
dependencies. Successful compilation does not establish that Lua scripts run:
ALE's world initialization callback is later than these missing-data boundaries.

The original normal map check uses real map/vmap existence tests; the dry run
still loads real DBC stores and checks 3.3.5a records. No matching DBC, maps,
vmaps or mmaps were supplied. A complete world startup and its gameplay gates
therefore require the correct extracted game data; this narrower updater protocol
does not remove that requirement.

## Audited source identities

| Source file | SHA256 |
| --- | --- |
| `src/server/apps/worldserver/Main.cpp` | `782a1100dcbc84c24b924f6394ee3cb7ec33712881089274aaa3f22974edd1a0` |
| `src/server/game/World/World.cpp` | `2774cc222da87dc909264b91c6868378674b1fc26525fb1f45a61564f2419788` |
| `src/server/game/DataStores/DBCStores.cpp` | `6627af84f765ccb5b847211b1493dbc6d84513fa9518497d1e04df3dfc7fe0f6` |
| `src/server/database/Database/DatabaseLoader.cpp` | `0f275c42496e1f74cdc7ba2a76ae83b2017d1a55ad67d35e9ad56da1c2137486` |
| `src/server/database/Updater/DBUpdater.cpp` | `a091e4dc6b83457eef7f63ef7cf973ccc63ab1344154dc69010704e2e5f79019` |
| `src/server/database/Updater/UpdateFetcher.cpp` | `9b153a1a637f11613dd6ea00a68d4b5b2e3d47c33d6b8ca7ab5fd5bf38a5259a` |
| `modules/mod-ale/CMakeLists.txt` | `3077bd31d5b803f147635aa3c512fe5d9f1902ca63271885d7c8411bf7adfd81` |
| `modules/mod-ale/src/lualib/lua/CMakeLists.txt` | `7bd0b1acbd763d1d3759a493bd4244edb3cef87174a0d0f3137d959aee54769c` |

These are original-source audit hashes. A future port can change a source hash;
its reviewed profile and build evidence must describe that change separately.
