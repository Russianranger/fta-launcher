# Disposable database lifecycle diagnostic

Physical version 10 completed the original compiled updater gate: 24/24 checks,
complete world snapshots matching the unchanged host reference, prepared
statements, expected missing-map boundary and clean database shutdown. Preserve
that milestone. This next diagnostic does not reimport the seed or rerun world.

## Physical result

Version 11 has **passed** on the AYN Thor: `databaseLifecycleQualified=true`,
`screenOffLifecycleQualified=true`, all ten flags true and 85 complete native
records with no timeout, truncation, dropped entry or validation error. Both
clean stops exited 0; the deliberate forced stop exited 137. Its two client
interruptions were expected. See [physical evidence](evidence/android-2026-10-23-thor-lifecycle-pass.json).
Do not repeat this milestone. The next single test is [runtime recovery](runtime-recovery-diagnostic.md),
which independently checks the app's Stop action and Android service-process death.

## Archived version 11 physical procedure

APK identity: SHA256
`ad6a38bcfa7a22deba4f648cf779c85ca5376ad918553009effd61ddf400d517`,
41,988,587 bytes; signing certificate SHA256
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
All 16 native files match the passing version 10 byte for byte.

Install the supplied private **0.6.0-database-lifecycle**, version code **11**, as
an update over the installed private version. Keep Database.zip and all native
inputs. Do not uninstall, clear data or substitute a public debug APK. Allow
512 MiB free private storage for this small disposable test.

Choose **Test database lifecycle**. When the screen-off stage appears, lock the
Thor for two minutes. Reopen it and choose **Export diagnostic report**. Attach
that JSON even if the test fails. It runs under a foreground service and bounded
wake lock; its total deadline remains five minutes. No source ZIP or seed import
is needed. The notification's Stop action can still cancel the test, but do not
press it during this combined run: transaction cancellation is exercised
automatically against an observed active connection.

Expected: `databaseLifecycleQualified=true` and
`screenOffLifecycleQualified=true`, with `DATABASE_LIFECYCLE_PASSED` as the state.
If the core checks pass without a sufficiently long observed screen-off period,
only the screen-off flag stays false. Service/JVM process death is explicitly
unqualified and cannot be inferred from the forced database stop.

## What the test proves

The unchanged provider boots a fresh, socket-only database with generated
credentials. The existing ASCII/Unicode, collation and rollback foundation runs.
The shared production Kotlin probe then:

1. Commits a synthetic InnoDB canary, requests SQL shutdown, reaps exit 0, and
   restarts the same disposable datadir with the same credentials. It verifies
   the canary survived.
2. Starts another native client with an uncommitted synthetic insert and a
   300-second sleep. A named database lock proves that transaction is active.
   The production native owner cancels/reaps the client; a separate connection
   observes lock release and verifies that only the committed canary remains.
3. Checks the canary every two seconds during a 90-second window. Screen-off
   health is recorded only after at least ten consecutive observed seconds of
   a noninteractive display and successful SQL probes. Host tests cannot set
   that Android observation.
4. Opens another transaction and confirms it is active. Zero-grace native stop
   must actually reap MariaDB with SIGKILL (137). Any other result refuses crash
   qualification. The owned client is also reaped, then the same disposable
   database restarts. The canary must survive and the interrupted transaction
   must be rolled back. Final SQL shutdown must exit 0.

Only confirmed termination allows a restart and stale socket removal. Every
attempt has a new identity, private directory, bounded native journal and atomic
fixed-schema lifecycle receipt. Export matches receipt/status/journal to the
current attempt. Qualification requires all mandatory observations, three ready
database processes, two clean shutdowns, one observed forced stop, the two
expected client interruptions, and no unexpected failed, timed-out, truncated
or dropped native evidence. Reports contain fixed flags, not SQL or canary rows.

The seed/updater receipts are retained on disk and remain bound to their original
attempt. A lifecycle export does not relabel that earlier attempt as the current
one; the independently verified physical updater evidence remains in the repo.

## Host validation and limits

`tools/check_database_lifecycle.py` runs the actual compiled production Kotlin
probe and production JNI/C++ owner against exact host MariaDB 12.3.2. It creates
its own temporary datadir and loopback-only generated credentials; no existing
database endpoint or datadir is accepted. It then SIGKILLs a separate owning JVM
while a real database transaction is active, observes/reaps MariaDB's parent-death
SIGKILL, restarts the same disposable storage, verifies durability/rollback and
requires no adopted child to remain. Host loopback transport is recorded honestly;
Android still uses only its private Unix socket and has no Internet permission.

Build Android production classes first, then supply `--server`, `--initializer`,
`--client`, `--jni-header`, `--kotlin-classes`, `--kotlin-stdlib` and `--output`.
The JNI header must match the JNI ABI (the staged NDK header works on this host).
The tool requires Linux child-subreaper support, g++, JDK 17 and MariaDB 12.3.2.
It does not require or load the original private seed.

`DatabaseLifecycleProbeTest` covers lost durable data, ineffective cancellation,
user cancellation during the observation window, refusal of an unobserved force
kill, absent screen-off evidence, malformed receipts and missing/failed native
outcomes. Native ownership regressions additionally exercise TERM-to-KILL
escalation, concurrent stop/wait, unrelated-child isolation, process-group
cleanup, abrupt owner SIGKILL and 80 commands crossing the bounded registry.

None of this qualifies Android service-process death, the notification/UI Stop
route, persistent realm saves, world readiness, client login or gameplay. Matching
DBC/maps/vmaps/mmaps are still absent. Those remain explicit subsequent gates;
original SQL, strict schema expectations, source provenance and native server
payloads are unchanged.

Measured host results are in [the lifecycle evidence](evidence/database-2026-10-23-host-lifecycle.json); build/signing results are in [the private APK evidence](evidence/android-2026-10-23-private-lifecycle-apk.json).
