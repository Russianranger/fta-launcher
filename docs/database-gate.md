# Private database compatibility gate

Host MariaDB 12.3.2 now imports the unchanged seed and passes the runtime SQL
probe using the original source's changed-file policy. The compiled host
authserver also passes its original updater, local listener, and graceful stop
probe. Each test runs in its own disposable instance; Bionic/ARM64, worldserver,
Android lifecycle, and Thor compatibility remain separate gates. A passing archive inventory alone
does **not** establish SQL compatibility. No database seed, credentials, account
records, or client files belong in this public repository.

## Measured input

`Database.zip` SHA256:
`56a6d3187d6c5fe308e2a5772a19bb087cb55dc999e585db5409bc5469a107f7`.
The planner streams every member, validates ZIP CRCs, hashes SQL files, rejects
unsafe paths, duplicate/case-colliding names, links, encrypted files, and excessive
sizes. It retains metadata and never returns SQL contents.

| Schema | Table scripts | Trigger scripts |
| --- | ---: | ---: |
| acore_auth | 27 | 0 |
| acore_characters | 165 | 0 |
| acore_world | 371 | 1 |

The inspected archive has 563 table scripts, one trigger, 236 runtime SQL scripts,
and 642,267,861 expanded bytes. The handoff's 372 world-table count differs from
the archive: there are 371 `table-*.sql` members plus one `trigger-*.sql` member.
The implementation uses the measured archive inventory.

## Commands

Place the reviewed archive outside the checkout. Inventory-only operation needs
Python 3.10+ and never connects to a database:

```bash
python3 tools/run_database_gate.py /private/Database.zip \
  --report /private/fta-db-inventory.json --inventory-only
```

A full host compatibility probe additionally needs an installed MariaDB server,
initialization helper, and CLI. MariaDB 12.3.2 matches the intended Android provider
version; running another host version supplies evidence only for that version.
The report records the actual version and whether it matches 12.3.2:

```bash
python3 tools/run_database_gate.py /private/Database.zip \
  --report /private/fta-db-host-gate.json \
  --mariadbd /path/to/mariadbd \
  --install-db /path/to/mariadb-install-db \
  --mysql /path/to/mariadb
```

The runner has no host, remote socket, existing datadir, password, port, or
destructive restore input. It creates a new private temporary datadir,
initializes it, and starts its own server. The default `--transport unix` disables
networking and uses only a private Unix socket. For host environments that deny
Unix socket binding, `--transport loopback` binds only `127.0.0.1` at a port chosen
by the runner. That mode initializes root with Unix-socket authentication, then
creates a separate loopback-only account with a fresh 256-bit random password
before the server listens. Its bootstrap SQL and dedicated client defaults file
are mode 0600 inside the private directory; secrets never enter command-line
arguments or reports. Readiness checks the instance-specific PID-file variable.
User MySQL environment/defaults are excluded. Server-side file access is restricted
to the temporary directory and client local-file loading is disabled. This is
process/data isolation for the pinned, reviewed seed, not an operating system
sandbox for arbitrary hostile SQL.

All three target schemas are checked for tables/views, routines, triggers, and
events **before any target is created**. Any existing object, unknown count, or
connection error refuses the seed. Tables import first, triggers second. Each
script passes intact through the MariaDB CLI's batch/binary mode; `DELIMITER`,
stored procedures, and large INSERTs are never split or rewritten by Python.

Verification checks exact imported table/trigger names against the archive,
representative AzerothCore table queries and row counts, column-schema
fingerprints, and updater receipt counts. Runtime SQL then runs on that same
throwaway installation as a strict SQL compatibility probe: matching SHA1
receipts are preserved and skipped; absent updates execute with their original
contents and gain a receipt only after CLI success; mismatched/empty receipts
stop the probe. Release receipts and remaining routine counts are checked after
all runtime scripts complete. In default strict mode a mismatch stops the probe,
never deletes receipts, and never disables updates.

An explicit `--probe-updater-policy` mode tests the original source's changed-file
policy in the same disposable instance. `UpdateFetcher.cpp` reads the original SQL,
compares its SHA1 to the stored receipt, reapplies changed scripts, and calls
`UpdateEntry` only after the SQL succeeds. `Util.cpp` encodes these hashes as
uppercase hexadecimal. The policy probe follows those operations, including
matching-hash state changes, and records each receipt's before/after hash and
state. Failed SQL never triggers a receipt replacement. It does not normalize
SQL, rewrite collations, delete receipts, allow empty-hash rehashing, or skip
failed migrations. Add this flag to the full command above, optionally with
`--transport loopback`, to run it.

This Python probe is a documented subset of the source updater policy. It does
not execute the compiled original `UpdateFetcher`, implement rename/orphan
cleanup policies, or establish game-semantic correctness. Production updater
execution remains a native server/Thor acceptance gate.

The JSON report includes stage, fixed release filename on failure, provider
version, hashes, object/row counts, and success/failure. SQL errors are reduced to
numeric error code and script line, because raw client errors can echo sensitive
INSERT values. Private diagnostics and the entire disposable database are removed
at exit. Report paths that alias the input archive are rejected. No raw seed
contents are copied into reports.

Resource limits are deliberately conservative: one script at a time, 1 MiB
stream buffers, a 128 MiB InnoDB buffer pool, ten maximum connections, 256 MiB
maximum SQL packet/member size, 1 GiB archive expansion cap, and bounded startup,
query, import, and shutdown waits. The runner requires 3 GiB free temporary
storage. A partial failure destroys only the disposable instance; it never
resumes by applying the seed over live saves. This is not yet the Android
restart-tolerant installation protocol.

## Evidence and outstanding work

The October 22 development run used the exact official MariaDB 12.3.2 Linux
x86_64 binary distribution, whose archive SHA256 was checked against the official
published checksum:

- Distribution: <https://archive.mariadb.org/mariadb-12.3.2/bintar-linux-systemd-x86_64/>
- Binary archive SHA256: `4a18365541d7280bbe947762e341bab94bc49f3ed31e6b5caf4e21f26246e458`
- Actual SQL `VERSION()`: `12.3.2-MariaDB`.

The host's execution policy denies Unix socket binding. The authenticated
loopback transport was therefore used for these host probes. No system database
service was started; every SQL operation targeted a fresh disposable datadir.

| Probe | Measured result |
| --- | --- |
| Reviewed archive inventory | Pass: 563 table scripts, one trigger, 236 runtime scripts |
| Ubuntu MariaDB 10.11.14 seed import | Fails after 27 auth tables, error 1273 at character `table-ac_virtual_satchel.sql` line 19 (`utf8mb4_0900_ai_ci`) |
| Exact MariaDB 12.3.2 seed import | Pass: all 563 table scripts and the trigger, exact object names, representative queries/counts, schema fingerprints |
| Default strict receipt probe on 12.3.2 | Stops at an existing character migration whose SHA1 differs; no receipt replacement or bypass |
| Explicit source-policy subset on 12.3.2 | Pass: 211 matching scripts verified, six absent scripts applied, 19 changed scripts reapplied; 20 character and two world release receipts verified; zero remaining routines |

Before any seed guards execute, the archive's runtime SQL has 211 matching seed
receipts, six absent receipts, and 19 different hashes. The first mismatch is
`2026_09_28_00_npcbot_consumable_integrity_zz_custom.sql`: seed receipt
`0EC395A81DA890C2D26212E955CC601F05EF82E1`, archive raw SHA1
`8A68ABE5708D1E369C11D1A5C3552B57A3056AFB`. Newline transformations do not explain
that difference. The original updater intentionally reapplies such changed SQL,
with `Updates.Redundancy = 1` enabled in the supplied configs. The source policy
probe follows that behavior on the disposable installation; it does not alter
the input archive or its original receipt exports.

Source-policy provenance is the reviewed `From_The_Ashes_Source.zip`:

| Source file | SHA256 |
| --- | --- |
| `src/server/database/Updater/UpdateFetcher.cpp` | `9b153a1a637f11613dd6ea00a68d4b5b2e3d47c33d6b8ca7ab5fd5bf38a5259a` |
| `src/common/Utilities/Util.cpp` | `f8bcc8de14dec77f70daa8651a26185c7f2e8616758cdc965afbb253a88fdd75` |

Metadata-only reports and receipt transitions are preserved under
[`evidence`](evidence/). No account names, credential values, character records,
or SQL dumps are included. The supplied snapshot does contain seed accounts and
characters; this host probe does not provision production credentials or make
that snapshot safe to ship.

A separate compiled host authserver probe also passed against MariaDB 12.3.2.
The exact host binary SHA256 is
`d897a986532f6e7e152bf87a6d0ae11b56219993298604a636b3afa4cfdfea1c`.
It used only a disposable auth schema and private generated database credentials;
realm endpoints were normalized to loopback inside that test fixture.

- Original auth updater `--dry-run`: exit 0, database pool and updater markers
  observed, auth receipt fingerprint unchanged.
- Normal auth listener: local TCP readiness observed; SIGTERM produced the halt
  marker and exit 0.
- Explicit TLS policy: rejected with exit 1 before database authentication;
  measured new database connections were zero after excluding the admin query.

These observations exercise the compiled original **auth** updater. They do not
qualify the compiled world/character updater, demonstrate a game account login,
or qualify Android. The full 236-file SQL evidence above remains the documented
Python source-policy subset. Metadata-only auth results are in
[`database-2026-10-22-compiled-auth.json`](evidence/database-2026-10-22-compiled-auth.json).

The tests use synthetic SQL and cover archive safety, refusal of nonempty or
unknown target state, CLI error redaction, unsplit `DELIMITER` scripts, private
loopback credentials, successful source-policy ordering, and failure without
receipt replacement:

```bash
python3 -m unittest discover -s tests -p test_database.py -v
```

A host MariaDB 12.3.2 SQL pass and host authserver probe do not qualify the
Bionic/ARM64 provider, Android process lifecycle, compiled world/character updater,
restart-tolerant installer, world startup, account creation, or Thor hardware.
Those gates remain open.


## Optional isolated Android auth dry run

The optional next diagnostic imports only the 27 auth table scripts and stages
one auth runtime SQL file from the private archive. The public
[`auth-seed-lock.json`](../manifests/auth-seed-lock.json) contains filenames, sizes,
and SHA256 values only. It excludes characters/world seeds and SQL contents.
Regenerate or verify it against the private pinned archive with:

```bash
python3 tools/create_auth_seed_lock.py /private/Database.zip
python3 tools/create_auth_seed_lock.py /private/Database.zip --check
```

Its flat JSON contract uses `formatVersion`, `archiveSha256`, `archiveSize`,
`files` (`path`, `role`, `targetPath`, `size`, `sha256`), `directories`, and
`sourceDirectory`. `auth-table` entries import in filename order into a newly
created `acore_auth` schema. The `auth-update` entry is copied unchanged to its
`targetPath`. All four listed directories must exist, including the empty ones:

```text
Data/data/sql/archive/db_auth
Data/data/sql/custom/db_auth
Data/data/sql/updates/db_auth
Data/data/sql/updates/pending_db_auth
```

The minimal config starts with `[authserver]`. Use a private generated database
user/password, a new diagnostic datadir, a private config file, and these settings:

```ini
[authserver]
LoginDatabaseInfo = ".;<private-socket>;<generated-user>;<generated-password>;acore_auth"
LoginDatabase.WorkerThreads = 1
LoginDatabase.SynchThreads = 1
SourceDirectory = "<private-runtime>/Data"
MySQLExecutable = "<bundled-mariadb-cli>"
LogsDir = "<private-diagnostic>/Logs"
PidFile = "<private-diagnostic>/auth.pid"
Updates.EnableDatabases = 1
Updates.AutoSetup = 1
Updates.Redundancy = 1
Updates.ArchivedRedundancy = 0
Updates.AllowRehash = 1
Updates.CleanDeadRefMaxCount = 3
BindIP = "127.0.0.1"
RealmServerPort = -1
Appender.Console = 1,5,0
Appender.Auth = 2,5,0,Auth.log,w
Logger.root = 4,Console Auth
EnableTOTP = 0
TOTPMasterSecret =
```

Launch only `authserver -c <private-config> --dry-run`. The source returns from
its dry-run branch before reading the auth TCP port; the invalid fallback port
also refuses startup before a listener if that argument is accidentally omitted.
No game account login or running realm is exposed. Leave the seed accounts and
receipts unchanged in this disposable instance; production account provisioning
is a later gate. The reviewed seed's realm addresses are already literal loopback
addresses, so this test needs no address rewrite or external resolution.

The same minimal configuration, four-directory auth-only tree, unchanged 27-table
seed, and `--dry-run` passed on the compiled x86_64 host authserver: exit 0,
original updater/completion markers observed, and auth receipt fingerprint
unchanged. The host used its authenticated loopback database transport because
Unix sockets are denied there. Summary evidence:
[`database-2026-10-22-minimal-auth.json`](evidence/database-2026-10-22-minimal-auth.json).

The exact private version 5 APK subsequently passed the original auth updater
and dry run on physical AYN Thor, using the Android Unix-socket path and Bionic
authserver. All 27 tables imported, the auth process exited 0 with its completion
marker, and MariaDB shut down through SQL with exit 0. This establishes normal
completion of this disposable auth test. Explicit cancellation, background and
process-death behavior still need their own device evidence. See
[`android-2026-10-22-thor-resolver-pass.json`](evidence/android-2026-10-22-thor-resolver-pass.json).

## Disposable full-seed Android diagnostic

`Test full original database seed` uses the verified `Database.zip` and the
APK's pinned database provider. It does not require the source ZIP or an auth
binary. The test creates its own fresh datadir, requires all three target
schemas to be absent, and imports the original 563 table files before the one
world trigger. SQL passes unchanged through MariaDB's client, preserving dump
directives and delimiter syntax. No runtime updates are replayed in this mode.

The [full-seed lock](../manifests/full-seed-lock.json) contains only member paths,
sizes, digests and catalog/receipt expectations. Its 564 files expand to
626,293,162 bytes. At least 3 GiB of free private storage is required; import
packets are bounded to 256 MiB, individual commands to five minutes and the
whole attempt to 30 minutes. Cancellation is checked while hashing, extracting
and waiting for native commands. Expanded SQL is removed after the attempt.

For each schema the test verifies the exact base-table names, trigger names,
zero routines/events and the original `updates` receipt fingerprint. Receipt
canonicalization preserves the exact hash/state case and orders by binary name;
it excludes timestamps and execution speeds. Both the expected concatenation
length and its actual length must match before its SHA256 is accepted. The
baseline contains 12 auth, 50 character and 3,191 world receipts. No receipt is
rewritten to make validation pass.

Gradle copies the canonical lock into `assets/metadata/full-seed-lock.json`.
APK verification checks its exact bytes as well as every provider/notice file.
The shareable report includes aggregate import counts, per-schema validation
booleans and current-attempt native outcomes; it excludes SQL, rows, receipt
names/hashes, private paths and credentials. Qualification requires all 564
imports to exit 0, all catalog/receipt checks to pass, a complete journal and
observed SQL shutdown with database exit 0. A seed import does not establish
compiled world/character updater compatibility or a playable realm.

Reproduce the metadata from a fresh, exact host MariaDB 12.3.2 instance:

```bash
python3 tools/create_full_seed_lock.py /private/Database.zip \
  --measure-host-baseline --baseline-report /private/full-seed-baseline.json \
  --mariadbd /private/mariadb/bin/mariadbd \
  --install-db /private/mariadb/scripts/mariadb-install-db \
  --mysql /private/mariadb/bin/mariadb
python3 tools/create_full_seed_lock.py /private/Database.zip \
  --baseline-report /private/full-seed-baseline.json --check
```

Use `--transport loopback` where host Unix sockets are unavailable. This tool
starts and stops its own disposable instance, verifies the baseline twice and
never opens live saves. Its host result is distinct from the next Thor test.
The later compiled updater gate must follow the
[original startup ordering and failure protocol](world-updater-gate.md).

## Thor charset-directory correction

Private version 6 imported 158 unchanged tables, then its next client exited 1
in 83 ms with MariaDB error 1115 at line 19. The current-attempt journal is
complete, and failure cleanup requested SQL shutdown and observed database
exit 0. The deterministic lock order identifies table 159,
`mod_npcbot_paperdoll_consumable_receipt`, whose original CREATE starts at line
19. Its `ascii`/`ascii_bin` column is the seed's only explicit ASCII column.
The raw device charset name was not exported; that identification comes from
the unchanged file and verified import order. See the
[physical failure evidence](evidence/android-2026-10-22-thor-full-seed-attempt.json).

Both required XML files were already packaged. The provider's compiled share
layout differs from the staged `share/mysql` path. MariaDB 12.3.2
[`fix_paths`](https://github.com/MariaDB/server/blob/mariadb-12.3.2/sql/mysqld.cc)
resolves the charset directory separately from its message directory;
[`get_charsets_dir`](https://github.com/MariaDB/server/blob/mariadb-12.3.2/mysys/charset.c)
uses the explicit directory when supplied. Version 7 passes that verified
directory to bootstrap, the running server and every direct client invocation.
It checks ASCII/ascii_bin DDL, exact ASCII bytes, Unicode and rollback before
the full-seed import. Original SQL, collation declarations and receipts are
preserved.

The exact corrected production probe SQL and the original failing file pass on
the separate Linux MariaDB 12.3.2 distribution with the packaged charset path.
That host also succeeds with an empty charset directory because ASCII is built
in there. This is a build-configuration limit of the host comparison; it does
not reproduce or qualify the Android negative case. The
[host/source evidence](evidence/database-2026-10-22-charset-path-probe.json)
records that limit explicitly.

Private version 7 subsequently passed on physical Android 13 AYN Thor. Its exact
APK-bound report records all 563 unchanged table imports plus the world trigger,
all 15 schema inventory/routine/event/receipt checks, and SQL shutdown with
database exit 0. All 577 current-attempt native invocation records are present;
none failed, timed out, were cancelled or were truncated. The full-seed interval
was 168,931 ms. See the
[bounded device evidence](evidence/android-2026-10-22-thor-full-seed-pass.json).
This closes the disposable full-seed compatibility gate. Runtime SQL was not
applied; the compiled three-database updater and persistent lifecycle remain
separate gates.
