# Preparing the retained native world gate

Current evidence: code 33 recovered the same code-32 log successfully. Sustained
health, 651.9 seconds screen-off and world exit zero remain historical evidence;
user Stop and retained postchecks remain open. The next gate uses the qualified
[code-34 auction COUNT correction](auction-count.md). No repeated recovery
export or standalone database/auth diagnostic is needed.

Physical code 29 passed the original world challenge and two combined healthy
samples. Its stdout limit then ended the test, and world cleanup exited 139 after
its Halting message. Code 31 adds original logger thresholds and a bounded,
process-only fault witness while retaining the original world ELF. See
[shutdown evidence and design](world-shutdown-diagnostic.md) and the current
handoff for the next private update. Native runtime remains unqualified.

## Live original GT loading

`tools/check_dbc_backup.py --live-native` restores the exact, hash-pinned Thor
backup into a new private temporary directory and verifies every backup file.
It requires exact MariaDB 12.3.2 and the pinned host Connector/C 3.4.9. It checks
the unchanged strict three-schema metadata before and after the test. No seed is
replayed and the supplied backup is never started or modified in place.

The new harness compiles unchanged original `DBCFileLoader`, `DBCStore`,
`DBCDatabaseLoader`, `ResultSet` and `Field` implementations, with original
assertions, strict field types and AddressSanitizer. Logging warnings/errors
abort rather than hiding conversion failures. The original `StringEqualI` leaf
is compiled from the pinned private source, without copying it into public Git.
GNU C++20 is needed for the original variadic assertion macros.

The only transport adapter provides a synchronous real C API connection and
the original first-row ownership contract. It accepts only the ten original
`SELECT * ... ORDER BY ID DESC` queries. A separate generated role has exactly
SELECT grants on the ten retained tables and ten disposable empty fixture tables;
grant inspection verifies that restriction. The real native query result feeds
the original loaders, with no captured-row substitute on that path.

All **8,922** loaded float values match independent CLI snapshots bit-for-bit.
All ten empty-table variants leave the original failed DBC load unresolved.
The GT snapshots and account/character counts remain unchanged; strict metadata
matches before and after; SQL shutdown exits zero; the original backup hash is
unchanged. LeakSanitizer task inspection is unavailable on this host, so only
AddressSanitizer is claimed. See
[live evidence](evidence/host-2026-10-24-live-dbc.json).

Reproduce with private inputs, one native compiler job:

```sh
python3 tools/check_dbc_backup.py \
  --backup /private/FTA-realm-backup-1792769735081.zip \
  --source-zip /private/From_The_Ashes_Source.zip \
  --report /private/FTA-database-diagnostic-1792849033352.json \
  --server /private/mariadb-12.3.2/bin/mariadbd \
  --client /private/mariadb-12.3.2/bin/mariadb \
  --snapshots /private/gt-snapshots --output /private/gt-evidence.json --live-native
```

This is host integration, with synthetic failing DBC bodies and an adapter in
place of the full original connection pool. The installed ARM64 worldserver,
Android Unix socket, actual game files and all 52 module startup hooks still need
their real gate. **`databaseFallbackValidated=false` remains mandatory.**

## Receive-only world listener probe

`LocalWorldProbe` accepts the pinned original 44-byte unencrypted
`SMSG_AUTH_CHALLENGE`: two-byte network-order length 42, little-endian opcode
`0x01ec`, fixed little-endian word 1, four seed bytes and 32 further seed bytes.
Seeds are opaque and never exported. The probe sends **zero application bytes**,
creates no account and submits no login request. It is restricted to loopback
and has a two-second deadline spanning connection and every read fragment.

The unit tests exercise all fragment sizes, EOF/zero reads at every boundary,
fixed-header corruption, lengths and invalid ports. The private source check
compiles the unchanged original `ServerPktHeader`, audits the original challenge
construction and tests the production probe against a fragmented host socket.
Truncated and deliberately slow streams fail. See
[source/socket evidence](evidence/host-2026-10-24-world-challenge.json).

```sh
python3 tools/check_world_probe_source.py --source-zip /private/From_The_Ashes_Source.zip \
  --kotlin-classpath /private/classes:/private/kotlin-stdlib.jar \
  --output /private/world-probe.json
```

The guarded native world action invokes this helper. A challenge is a listener observation,
not proof of completed module initialization, world health or gameplay.

## Retained session protocol; clean Stop and postconditions pending

1. Bind a full asset rehash/preflight to the exact candidate generation and
   retained realm under their ownership leases; require the verified cold backup.
   Keep original SQL/config staging, module inventory, schema locks and receipts.
2. Reopen the retained database; validate strict metadata and GT coverage before
   starting original auth then world. Never bootstrap/replay the seed. Use the
   verified candidate as `DataDir`, existing private source/update paths, safe
   generated credentials, schema-only grants and loopback listeners. The retained
   session config disables schema auto-setup and selects a private loopback port;
   original updater policy and module defaults remain intact.
3. Supervise the original owned ARM64 world process, record attempt-bound native
   loader/startup evidence and use `LocalWorldProbe` alongside SQL/auth health.
   Do not infer native GT success from the inert file receipt or this host test.
4. On explicit Stop, stop world first through its original SIGTERM handler, then
   auth, then SQL database shutdown. Require actual clean exits, closed listeners,
   retained data and strict postconditions. Failure cleanup must preserve ownership.
5. Measure at least **120,000 ms** of healthy screen-off runtime during this gate;
   the earlier 112,127 ms local-auth interval does not pass that requirement.

World startup may legitimately change world runtime state; diagnose measured
changes against original source rather than relaxing metadata checks. Controller,
client import, WarcraftXL, account provisioning and playable login are later work.

## Read-only recovery after code 27

Code 28 can export the existing failed attempt without restarting a native process.
`worldServiceDiagnostic.retainedWorldLogs` binds the journal's single terminated
world invocation to its visit directory and only reads that visit's three fixed
log roles. `gameDataOperation` also exposes the completed rehash and its original
process identity; an app update's later exit is not an asset-validation failure.

The log reader uses no-follow handles, rejects linked visit directories, limits
each file to 8 MiB and each parsed line to 4 KiB, checks that the file stayed stable,
and retains at most 64 matching events per log. An unavailable file stays unavailable.
A matching line exports only its ordinal, fixed severity and candidate source IDs.
Neither dynamic fields nor the 16 literal bytes are copied into the report.
Hashes, counts and identifiers do not set any native qualification flag.

`docs/evidence/world-log-dictionary.json` maps each reported `wNNNN` ID to the
original source file and line. Shared 16-byte prefixes deliberately return multiple
candidate IDs. The last matching log identifies an observed message, not necessarily
the executing hook at the crash; concurrent threads and unlogged work remain possible.
Reproduce its exact source provenance using:

```sh
python3 tools/world_log_dictionary.py --source-zip /private/From_The_Ashes_Source.zip
```

That recovery completed on code 28. The exact assertion reproduction now supports
the separate provider addition and the next code-29 native runtime test. The original 52 modules, DBC rules,
SQL, native payloads and retained realm are unchanged by this recovery increment.
