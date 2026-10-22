# Compiled world updater gate before game data

This is a source audit, metadata contract and execution protocol, not a passed
native or device updater test. A successful full-seed import does not qualify
the compiled world or character updater. The supplied worldserver can execute those original
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

The metadata contract and host expected-result model below are implemented.
Compiled server build evidence and target execution remain separate gates. A
full-seed-only report does not launch worldserver or qualify its updater.

## Exact metadata contract and host expected results

`manifests/world-updater-lock.json` binds the unchanged database archive, all 236
runtime SQL files, the reviewed native source profile and tree, all 52 module
names, and the measured absence of module SQL. Each runtime file retains its
original `Data/data/sql/...` target path, byte length, SHA256 and original
updater SHA1. The twelve included archive/custom/released/pending directories
must exist in the original database ZIP. The generator rejects additional files
in that runtime tree; it does not invent empty SQL directories to hide warnings.
The private APK stager verifies this source identity and SQL absence before
packaging the canonical lock. The device materializes the signed inventory; it
does not revalidate a source ZIP on the device.

`tools/create_world_updater_lock.py` can measure expected metadata using a new,
bounded host MariaDB 12.3.2 instance. This is a Python model of the audited
`UpdateFetcher`, **not compiled worldserver execution**. It preserves the
original receipt snapshot, exact hash case, released-before-pending ordering,
changed-file reapplication, optional empty-hash rehashing, rename handling and
orphan cleanup threshold. It rejects ambiguous duplicate-hash renames rather
than guessing C++ unordered-map order. A failed SQL client aborts immediately
without replacing that file's receipt. Original SQL bytes are never rewritten.

The original policy remains `Updates.EnableDatabases=7`, `Redundancy=1`,
`AllowRehash=1`, `ArchivedRedundancy=0`, `CleanDeadRefMaxCount=3`, normal mode.
The measured fresh-seed result verifies 211 unchanged receipts, applies 6 new
files and reapplies 19 changed files. This fixture requires no rehash, rename,
state change or orphan deletion. The orphan counts exceed the original cleanup
threshold in each schema, so the original historical receipts remain present.
The model was repeated with the device's generated schema-only updater account:
global `USAGE` and `ALL PRIVILEGES` on only the three target schemas, without
grant option or global `SUPER`. Its runtime SQL client used the original
`utf8` default charset. All required original SQL succeeded and the canonical
postconditions match the earlier unrestricted model exactly; no additional
grants were needed. The bounded public record is
`docs/evidence/database-2026-10-22-world-updater-model.json`.

The exact production Kotlin `WorldUpdaterEvidence.schemaSql` output was also
executed on a fresh host instance after that model, then parsed by the compiled
production Kotlin `parseValidation` method. All 24 schema-validation fields
passed; the three read-only queries preserved the postcondition digests and
MariaDB exited 0 after SQL shutdown. The public record contains only source,
class, query and metadata-output hashes plus fixed flags:
`docs/evidence/database-2026-10-22-world-updater-production-sql.json`. This checks
the host SQL/parser integration and does not execute the compiled native updater
or establish Android execution.

The canonical source binding subsequently advanced from world-v8 to world-v9
for an explicit signed 64-bit packet serialization cast in `Unit.cpp`. The
guarded fixture confirms that all audited updater/startup/ALE source hashes
remain unchanged. Actual production Kotlin re-emitted all three queries from
the v9 lock and each query is byte-identical to the host-tested v8 query. The
public proof preserves the historical v8 host lock identity and records this
metadata-only binding advance separately; it does not claim another SQL run.

The original prepared-statement registrations were checked separately against
host MariaDB 12.3.2 after a fresh unchanged seed and updater model. A small C++20
recorder compiled the exact original statement/connection enums and complete
`DoPrepareStatements` bodies with recording-only stubs. The C++ compiler retained
adjacent string-literal semantics; SQL was not extracted or rewritten with a
regular expression. First-connect and reconnect registrations matched.

| Pool | Selected registered statements | ASYNC preparation | SYNCH preparation |
| --- | ---: | ---: | ---: |
| Login | 106 | 57 | 50 |
| Character | 554 | 444 | 128 |
| World | 102 | 46 | 57 |

The 762 selected registrations expand to 782 connection instances because 20
use `CONNECTION_BOTH`. Character preparation contains one real schema-dependent
branch at index 511: its original metadata query observed the transmog table,
selecting the join variant. The recorder also covered null/zero-row results,
and the alternate empty-result statement prepared separately. Every one of
those 783 sequential host operations used only `PREPARE` and `DEALLOCATE`, with
the C API's `utf8mb4` session charset and schema-only grants. No prepared query
body was executed. A server-side byte-length/SHA256 round trip confirmed each
unchanged input, all clients exited 0 with empty stderr, postconditions stayed
unchanged and SQL shutdown exited 0.

World's original enum additionally declares unused index 65,
`WORLD_SEL_GAMEOBJECT_TARGET`; the full original source/module inventory contains
only that declaration. No registration or SQL was invented for it. All 763
selected/alternate SQL byte strings plus NUL terminators were also found in the
three actual AArch64 target object files, tying literal contents to the native
compile without claiming execution. Source/function/query hashes, indices,
connection flags and outcomes are recorded in
`docs/evidence/database-2026-10-22-host-prepared-statements.json`. This is
extracted-source host preparation evidence; the real Bionic pools, their
simultaneous prepared state and the device updater boundary remain separate.

| Schema | Tables after updater | Canonical update receipts | Release receipts | Routines / events |
| --- | ---: | ---: | ---: | ---: |
| `acore_auth` | 27 | 12 | 0 | 0 / 0 |
| `acore_characters` | 166 | 53 | 20 | 0 / 0 |
| `acore_world` | 372 | 3194 | 2 | 0 / 0 |

Counts are accompanied by exact table/trigger identities and bounded canonical
SHA256 summaries. Update receipt summaries preserve exact names, hash case and
state in binary name order, excluding timestamp and duration. Release receipts
include release ID, original `Sql/` name, exact hash, `APPLIED` status and
`once_only=1`, excluding timestamps. Column summaries include every table/column
identity, ordinal, type, nullability, default, collation and extra property;
hexadecimal string encoding distinguishes NULL and delimiters. Every aggregate
compares full summed byte length with concatenated byte length before accepting
its digest. The canonical expressions and strict parsers are shared in the
generator, with synthetic failure tests in `tests/test_world_updater_lock.py`.

The lock also records exact source updater start/completion lines for Auth,
Character and World. The unmodified `DatabaseLoader` has no preparation-success
log of its own: `Loading World Information...` is emitted only after `Load()`
has successfully updated and prepared all three pools. That later marker,
ordered updater phases, complete private logs without failed SQL, the exact
original missing-starting-map message and normal exit 1 must all agree. Receipt
summaries alone cannot satisfy this boundary. The original ALE startup hook
also issues its `instance.data` TEXT conversion before the map boundary; the
supplied seed already has that shape. Its source identity is included in the
audit inventory. Original online-state, version and realm writes remain confined
to the disposable copy.

To regenerate the lock, provide the reviewed private database ZIP, exact staged
source profile and private metadata-only host measurement. `--check` compares
the entire canonical output without writing. A new native port profile requires
regenerating the source binding; the host SQL result fixture may be reused only
when the audited updater policy and input SQL remain unchanged. The lock always
states that the expected-result model does not qualify compiled world execution,
world readiness, a listener or gameplay.

## Reviewed module work before the map boundary

Original initial configuration callbacks run before the missing-map exit. They
can read and change rows in the disposable copy independently of the 236 runtime
SQL files. These callbacks remain enabled according to the packaged original
module configurations. Their reviewed behavior is broader than the updater,
prepared-statement and catalog checks:

| Module and original source | Initial behavior |
| --- | --- |
| BossLoot, `mod-talisman-of-binding-shard/src/mod_talisman_of_binding_shard.cpp:358–510,704–739` | Ensures the already inventoried world once-drop table exists; inserts missing configured keys, updates rule metadata, optionally resets eligible startup state, reads dropped state and can copy legacy state with guarded updates. The legacy path contains no drop, alter or truncate operation. |
| MountRequirements, `mod-mount-requirements/src/MountRequirementsScript.cpp:14–19` and `MountRequirements.cpp:23–79,160–201,259–285` | Applies configured or original mount requirements using world `item_template` and `trainer_spell` updates. `CommitTransaction` queues this work on the original database pool; disabling its feature selects restoration updates. |
| Attriboost, `Attriboost/src/Attriboost.cpp:253–291,764–772` | Reads character attributes into its in-memory map even on the first configuration load. Reload additionally saves and clears the old map. An empty/failed initial read logs an error. |
| StatBooster, `StatBooster/src/StatBoost.cpp:202–240` and `StatBoostCfgMgr.cpp:44–94,134–184` | When enabled, reads the world enchant-template and enchant-score tables into memory. Some failure/empty paths report at INFO severity, so catalog and SQL-error checks alone do not establish a successful module data load. |
| ALE, `mod-ale/src/LuaEngine/LuaEngine.cpp:62–85,113–135,617–665` | Issues the original character `instance.data` TEXT conversion, discovers configured Lua paths and initializes its engine. The supplied seed already has that column shape. Configured Lua script-file execution and gameplay callbacks remain beyond this map boundary. |

Locations in this table are relative to `AzerothCore_Linux/modules`. Complete
logs must still contain no failed SQL. The gate compares receipts and catalog
metadata; it does not assert every module row value, async transaction effect,
loaded in-memory record or gameplay outcome. The host updater/prepare probes
did not execute these native module callbacks. No player-data contents or module
row counts are exported. All initialization reads, DML and DDL in the native
diagnostic are restricted to the freshly seeded disposable installation.

The final source audit also pins the original
`mod-world-inspector-runtime/src/WorldInspectorRuntime.cpp` queue helper, binding
the source-supported private runtime-directory configuration. The guarded host
SQL fixture retains its original nine updater/startup/ALE source identities;
adding this native configuration pin does not relabel the earlier host model as
module execution or imply another SQL run.

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
