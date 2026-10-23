# Cold realm backup and isolated restore

Physical code 13 passed persistent realm reuse on the Thor. Its later invocation
reopened the active generation twice without importing seed SQL, passed all
storage flags and all 24 metadata expectations on both reopens, and shut down
cleanly. The 14 native records are complete. See
[the physical result](evidence/android-2026-10-23-thor-persistent-realm-pass.json).

The current private update is code **14**, **0.8.0-realm-backup**. It creates an
exportable cold backup and checks recovery into an isolated copy. The active
realm is never replaced. Original SQL, strict expectations, source identities
and native runtime payloads remain unchanged.

APK SHA256: `e268967eb5cc95d2a17161daf20a22f5719767b170db6cff95457a9fdb5880e8`.
The private build passed 198 Android tests, lint/assembly, 142 tooling tests,
221 packaged input checks and signature/alignment verification.

## One substantial physical test

1. Update in place with the private code 14 APK. Retain app storage, the verified
   input ZIPs and native dependencies; do not use the public debug APK.
2. Select **Create / verify realm backup**. Allow space for two additional copies
   of the database plus 256 MiB. The action checks the original database, stops
   it cleanly, makes a backup, restores an isolated copy and verifies two clean
   reopens of that copy. No seed or updater SQL is replayed.
3. After success, use **Export verified realm backup** and keep the ZIP in a
   private location. Then use **Export diagnostic report** and share only that
   JSON. If the action fails, export the diagnostic JSON immediately.

Expect `REALM_BACKUP_PASSED`, `realmBackupQualified=true`, all eight nested
checks true, `realmBackupDiagnostic.exportedCopyVerified=true` after ZIP export,
and three complete native journals (`original`, `restore1`,
`restore2`), each with clean database shutdown. Physical code 14 remains
unverified until its report is inspected. No repeat of the earlier updater,
lifecycle, service recovery or initial persistent installation is needed.

The backup contains database/account data and private database credentials;
it is not a diagnostic attachment. It is an unencrypted ZIP, so keep it private.
This release verifies restoration into a separate copy inside this installation.
It does not expose a replace-active or external-backup import action. Portability
to another app storage path/provider and an explicit restore/activation UI remain
future work; retain the existing installation and its original inputs.

## Data and ownership rules

The active store's OS lease spans original verification, backup, restored
verification and final original-data comparison. Its existing owner/child
identity rules must establish that no previous writer remains. Original
verification uses the measured retained-database path and SQL shutdown.

Only the stopped `database/` tree, `client.cnf`, `identity.properties` and
`prepared.properties` enter the ZIP. Provider executables, imported seed SQL,
logs, visit journals and arbitrary app files are excluded. The manifest records
the existing realm token, five exact source/provider identities, all directories,
file sizes and SHA-256 hashes. Each file is hashed again while writing.

The archive is written under a unique attempt, synced and atomically published.
Cancellation cannot publish a partial archive. Prior backups are not overwritten
or automatically pruned. Export availability is published only after the complete
restore rehearsal succeeds; a failed attempt cannot replace the last verified
backup selection. The export path reads the saved document back and checks its
length and SHA-256 before recording `exportedCopyVerified`; use a destination
that supports reading the newly saved document.

Restore requires an empty separate store, validates archive identity and the
complete entry list, then checks every extracted file's size and hash. It refuses
existing destinations, traversal, extra/duplicate entries, missing metadata,
links in source storage and malformed/oversized declarations. Limits are 50,000
entries, 32 GiB uncompressed data and an 8 MiB manifest. Extraction creates only
regular files/directories; ZIP entries cannot request native execution.

The restored copy receives provider data from the signed APK. Its retained
credentials and control identity must work without bootstrap, seed import or
updater replay. Two database launches each require the unchanged 24 schema and
receipt checks, followed by SQL shutdown and exit zero. The restore is never
activated. The original file inventory and active-pointer digest must still
match the snapshot taken after its initial clean stop.

Only the successfully verified, stopped rehearsal copy is removed. A failed or
interrupted rehearsal is retained for diagnosis, with uncertain writer ownership
still blocking access. Repeated failed attempts may consume space and need later
targeted cleanup; there is no automatic deletion of active data or old backups.

The JSON export binds flags, three native journals and archive identity to the
current attempt. It independently checks the archive digest and active pointer.
It omits SQL, rows, credentials, paths and the per-file backup inventory. Generic
disposable diagnostic booleans do not supersede the passing physical milestones.

## Host qualification and remaining work

`tools/check_realm_backup.py` uses the production Kotlin archive, restore and
storage protocols with the production JNI owner and real host MariaDB 12.3.2.
Synthetic InnoDB rows include binary data. It verifies the archive/restore,
both restored reopens, unchanged original files/pointer and an additional
original reopen under a second JVM. This is host evidence; it does not execute
the Android directory-sync adapter, SAF export, original seed or ARM64 world.

Unit tests cover corruption, path escape, oversize declarations, cancellation,
source changes, existing destinations, source mismatch and either restored-reopen
failure. The private build passes all 198 Android tests and lint/assembly; 142 tooling
tests also pass. Exact-commit CI is checked before delivery. See
[host evidence](evidence/database-2026-10-23-host-realm-backup.json) and
[private APK identity](evidence/android-2026-10-23-private-realm-backup-apk.json).

After this physical gate, proceed to retained database start/stop and local
auth/world orchestration, with explicit backup import/activation safeguards.
Matching DBC/maps/vmaps/mmaps are still needed for world startup. No listener,
world-entry or gameplay readiness is claimed by a backup pass.
