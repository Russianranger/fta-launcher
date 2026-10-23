# Persistent realm storage

Physical Thor gates already passed: original full seed (code 7), all 24 compiled
updater checks (code 10), lifecycle/screen-off health (code 11), and actual service
death plus Stop/recovery (code 12). Code **13**, **0.7.0-persistent-realm**, uses
those implementations to prepare the first retained realm database.

APK SHA256: `898c64163e0c62e35cca358ec6032e3220126fa07b1a27772c2b4b1a003543ee`.
The private signer and all 16 native payloads match code 12. There is no provider,
authserver, worldserver, module, native dependency or supervisor change.

## Physical result

Code 13 has passed the later reuse invocation on the AYN Thor: all storage checks,
14 complete successful native records, two clean database stops and no seed
reimport. See [the evidence](evidence/android-2026-10-23-thor-persistent-realm-pass.json).
Do not repeat this milestone; backup/restore also passed in code 14. Continue to [local auth](local-auth-service.md).

## Archived code 13 procedure

1. Install the private code 13 APK as an update. Keep the existing verified
   Database.zip and dependencies. Do not uninstall, clear app storage or use a
   public debug APK. Allow 3 GiB free for first preparation.
2. Select **Prepare / verify persistent realm**. Allow preparation and both
   automatic restarts to finish. First preparation can take several minutes;
   it has a bounded total duration of about 50 minutes. Stop remains available.
3. After success, close and reopen the app, then select the same action again.
   This invocation must reuse the existing realm and should be much quicker.
   Choose **Export diagnostic report**, save the JSON and attach it. On a
   failure, export immediately instead of proceeding to the next step.

The final report should show `REALM_STORAGE_PASSED`,
`persistentRealmStorageQualified=true`, and
`realmStorageDiagnostic.checks.seedImportNeeded=false`. Both reopen phase
journals must be complete and qualified, with final SQL shutdown and exit 0.
No manual force stop, screen-off test or disposable updater repeat is required.

The inspected physical code 13 report meets those criteria. This creates
retained database storage; it does not yet start an auth/world listener or enable
gameplay. Matching DBC/maps/vmaps/mmaps and service orchestration remain necessary.

## Installation and reuse rules

Persistent generations live under `persistent-realm/generations/r-<UUID>`,
separate from disposable diagnostic directories. The diagnostic pruning path
does not enumerate or delete this storage. No reset/delete action is supplied.

A new generation must contain only its launcher identity receipt before seed
import can begin. The existing production pipeline bootstraps MariaDB, imports
the exact original 563 tables and trigger, runs the original compiled updaters
through their expected missing-map boundary, and requires all 15 baseline and
24 final checks. This is required first installation into empty retained storage;
it does not repeat an import into an already active database.

The launcher then creates its own `fta_launcher.realm_identity` control table,
containing a random generation token. This is separate from the three original
`acore_*` schemas. Their SQL, expectations, receipts and user/account rows are
not rewritten. The temporary updater SQL account is dropped before activation.
The private root-client options and database files are retained across shutdown.

After a recorded clean shutdown, two separate database launches must reopen the
same datadir with the same generated credentials. Each checks the generation
token, all 24 unchanged schema/receipt expectations, and clean SQL shutdown.
Only then is a synced active pointer atomically published. Original import and
updater code is unreachable once a valid active generation exists. Ordinary
verification reads metadata/control identity and shuts down; it does not run
the updater, reimport SQL or write user rows.

The source binding includes the seed hash, provider manifest, world executable,
updater lock and source-tree hash. A later incompatible source/provider change
blocks reuse pending an explicit migration design; it never silently resets or
recreates the active realm.

## Interruption and ownership

An OS file lock serializes access across processes. Atomic metadata replacement
syncs file contents and containing directories. Android uses a directory file
descriptor with close-on-exec and `fsync`; this feature requires API 27 or newer,
including the target Thor API 33. Ordinary diagnostic compatibility is unchanged.

Every bootstrap/server launch first records launch intent, then records the
owner and child kernel identities. A new owner may clear a stale writer only
after independently observing both identities ended. Reparenting, inaccessible
procfs, malformed state or an interrupted launch without a child identity cannot
count as cleanup. Those cases fail closed with storage retained. Normal owned
shutdown also requires observed child termination before clearing the writer.

Failed preparation leaves an unactivated candidate intact. A retry creates a
different empty candidate; it never imports into that partially populated
directory. A prepared candidate that failed a reopen resumes verification with
no reimport. Once an active pointer exists, malformed metadata, a missing root,
source mismatch or symlink is an error, not evidence that no realm exists.
Incomplete candidates are deliberately retained; repeated failed preparations
may require later targeted cleanup rather than automatic deletion.

If interruption occurs after the atomic pointer replacement, the next session
recognizes that active generation and verifies it. It does not replay the seed.
Code 14 adds verified backup/export and isolated restore; external restore activation
and migration remain unavailable. Retain app storage and the original
inputs while those subsequent safeguards are developed.

## Evidence and host qualification

The report binds current status, flags and per-launch native journals to one
attempt. Reopen journals use separate visit directories and preserve the initial
preparation evidence. Successful export independently rechecks the active pointer,
source binding, complete native journals, all three metadata-query records per
reopen and clean shutdown. SQL, rows, secrets, paths and process identities stay
private. Generic disposable-seed/updater booleans remain false for this separate
scope; they do not revoke earlier milestones. The nested storage checks identify
whether the current invocation needed a fresh seed import.

`tools/check_persistent_realm.py` runs the production storage protocol, shared
canary SQL and JNI owner against exact host MariaDB 12.3.2. It creates a fresh
temporary system-database template and synthetic retained row, then uses two
independent JVM sessions. One generation, four reopens and five clean SQL stops
must preserve that row and the active pointer; the second session refuses any
attempt to invoke preparation. It accepts no existing datadir, seed or external
endpoint. Supply the same explicit toolchain arguments as the recovery host tool.

The host fixture does not execute the Android directory-sync adapter, original
seed or compiled ARM64 world updater. Those prior components have separate
physical evidence; their composition into retained storage still requires the
code 13 test, which has now passed. Unit tests additionally cover partial preparation, reopen failures,
corrupt/missing active data, mismatched source receipts, symlinks, competing
leases, living/ambiguous writers, interrupted activation and missing native
schema/shutdown evidence.

See [host persistence evidence](evidence/database-2026-10-23-host-persistent-realm.json),
[private APK identity](evidence/android-2026-10-23-private-persistent-realm-apk.json),
and [physical code 12 recovery](evidence/android-2026-10-23-thor-runtime-recovery-pass.json).
After this physical pass, the next implementation should add retained database
start/stop with safe backup/restore, then local auth/world service orchestration.
World-ready and gameplay claims still require matching client data and actual
listener/world-entry evidence. Preserve C++20, all 52 modules and source provenance.
