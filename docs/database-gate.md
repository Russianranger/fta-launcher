# Private database compatibility gate

The first development gate is implemented as an archive planner and an isolated
host MariaDB test runner. A passing archive inventory does **not** establish SQL,
Android, server-startup, or Thor compatibility. No database seed, credentials,
account records, or client files belong in this public repository.

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

The runner has no host, remote socket, existing datadir, password, or destructive
restore option. It creates a new private temporary datadir, initializes it,
starts its own server with networking disabled, and uses only its private Unix
socket. User MySQL environment/defaults are excluded. Server-side file access is
restricted to the temporary directory and client local-file loading is disabled.
This is process/data isolation for the pinned, reviewed seed, not an operating
system sandbox for arbitrary hostile SQL.

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
all runtime scripts complete. A mismatch is a blocker to investigate, never a
reason to delete receipts or disable updates.

This probe does not replace AzerothCore's production updater and does not assert
that every updater policy, source-directory configuration, or game-semantic
migration behavior matches. Production updater execution remains a native
server/Thor acceptance gate.

The JSON report includes stage, fixed release filename on failure, provider
version, hashes, object/row counts, and success/failure. SQL errors are reduced to
numeric error code and script line, because raw client errors can echo sensitive
INSERT values. Private diagnostics and the entire disposable database are removed
at exit. Report paths that alias the input archive are rejected. No raw seed
contents are copied into reports.

Resource limits are deliberately conservative: one script at a time, 1 MiB
stream buffers, a 128 MiB InnoDB buffer pool, eight maximum connections, 256 MiB
maximum SQL packet/member size, 1 GiB archive expansion cap, and bounded startup,
query, import, and shutdown waits. The runner requires 3 GiB free temporary
storage. A partial failure destroys only the disposable instance; it never
resumes by applying the seed over live saves. This is not yet the Android
restart-tolerant installation protocol.

## Evidence and outstanding work

The real archive inventory passed locally and all database safety tests use
synthetic SQL. No MariaDB binaries are present in the initial development host,
so seed import, collation support, trigger creation, runtime migration execution,
and SQL/schema compatibility remain **unproven**. The runner fails closed when
its provider is unavailable. A matching Bionic/ARM64 MariaDB 12.3.2 run and physical
Thor validation remain required even after a host probe succeeds.

Run the safety tests without the private archive:

```bash
python3 -m unittest discover -s tests -p test_database.py -v
```
