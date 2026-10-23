# Runtime recovery diagnostic

Physical code 10 passed the original compiled updater with all 24 checks. Code
11 passed all ten database lifecycle observations, including screen-off health.
Those milestones remain established. Code **12**, **0.6.1-runtime-recovery**,
adds one combined test for Android Stop delivery and separate service-process
death, independent child termination, and recovery of the same disposable DB.

## One physical test

Install the supplied private code 12 as an ordinary update. APK SHA256:
`5fa04d6e7837595d58526a3a7e773df503c0dd5d80eaab639fc8e097e6ebf8c5`.
The signer remains
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Keep the existing verified Database.zip and dependencies. Do not uninstall,
clear data or use the public debug APK. No source ZIP or reimport is needed.

Select **Test runtime recovery**, keep the app open and let it finish. It
automatically dispatches Stop, terminates only the separate test runtime, and
restarts its tiny disposable database. Do not press Stop yourself or lock the
screen for this run. The controller has a nine-minute deadline and a bounded
wake lock; allow 512 MiB free private storage. Then choose **Export diagnostic
report**, save the JSON with Android's file picker and attach it, even on failure.

A passing report has `RUNTIME_RECOVERY_PASSED` and all three booleans true:
`runtimeRecoveryQualified`, `serviceProcessDeathQualified`, `stopActionQualified`.
Physical code 12 remains unverified until such a report is inspected.

## Independent observations

The nonexported controller runs in the UI process and owns the current attempt
identity and overall status. The nonexported database service stays in `:runtime`.
Its private fixed-schema phase checkpoints bind the attempt, disposable root,
owner identity and two native children. PIDs and kernel start times stay private.

1. **Cancel:** create a fresh tiny database, commit a synthetic canary and
   attempt token, and observe a named lock proving a second native client's
   uncommitted transaction is active. Dispatch the same immutable Stop
   PendingIntent used by the worker notification and ordinary diagnostic UI.
   Require service destruction to complete, both child identities to end, and
   the native journal to contain exactly the expected two cancellations.
2. **Crash:** prepare a different fresh database with the same synthetic probes.
   A current-attempt-only command asks the runtime service to SIGKILL itself;
   it cannot signal caller-selected PIDs or the controller. The controller must
   observe owner termination and both native children ending before continuing.
   Parent PID changes are not death. Permission/parse failures block advancement.
3. **Resume:** a new runtime owner reopens that same crash datadir after checking
   the prior owner/children have ended, the root is private and not a symlink,
   packaged data still matches the manifest, and the retained client options
   have exactly the expected private-socket shape. The per-attempt token and
   committed canary must survive; the interrupted transaction and named lock
   must be absent. Final SQL shutdown must exit 0.

The controller never kills native processes itself. The native owner still
provides group cleanup and parent-death signals; its only new JNI API exposes
the live PID behind an owned handle for observation. The private APK rebuilds
this helper. All 15 other native payloads, original seed, metadata locks,
compiled updater expectations, C++20 core and 52 modules remain unchanged.

## Reading the JSON

`runtimeRecoveryDiagnostic` includes seven independently recorded flags and
three phase summaries under `phases.cancel`, `phases.crash`, `phases.resume`.
Each phase retains its own bounded, current-attempt native invocation journal.
Crash and resume use separate journals and log directories in the same datadir.
Actual log lengths are checked after termination, so truncation by a dead
writer cannot be hidden by its unfinished journal.

The killed worker necessarily leaves two historical `running` records, one
database and one active client. They are accepted only in the crash phase and
only when independent termination flags, new-owner recovery and all other
phase evidence pass. They do not mean those processes are still alive. The
controller cannot reap another process's children on Android; therefore
`nativeExitSignalsObserved=false` is explicit. Owner death and native termination
are observed; exact child exit signals are not claimed on Android.

Missing records, dropped evidence, bounded-log truncation, timeouts, an active
old identity, wrong attempt/root, missing cleanup or a failed final SQL shutdown
prevent qualification. Exports omit SQL, canary rows, credentials, private paths,
PIDs and start times. This diagnostic does not import the seed; its false seed
or compiled-updater flags do not revoke the earlier independent physical passes.

## Host checks and remaining boundary

`tools/check_runtime_recovery.py` runs the actual compiled production Kotlin
protocol, shared canary SQL and production JNI owner against exact host MariaDB
12.3.2. It creates two fresh temporary datadirs and generated loopback credentials.
The host fixture translates this environment's nested PID namespace using
direct parentage plus `NSpid`; Android instead requires a consistent self/procfs
view and does not use that translation. An owning JVM is actually SIGKILLed;
the host subreaper requires both orphaned native children to terminate by
SIGKILL, and no adopted child may remain after same-database recovery.

Build production Android classes first, then pass `--server`, `--initializer`,
`--client`, `--jni-header`, `--kotlin-classes`, `--kotlin-stdlib` and `--output`.
This host fixture substitutes command delivery, so it does not qualify Android
PendingIntent delivery, foreground-service behavior or device process visibility.
The disk-evidence tests also reject missing or truncated crash/resume logs;
protocol tests reject surviving children, inaccessible observations, unfinished
service destruction, wrong roots and reused owner identities.

Measured results: [host recovery](evidence/database-2026-10-23-host-runtime-recovery.json),
[private APK](evidence/android-2026-10-23-private-runtime-recovery-apk.json), and
[physical code 11 lifecycle](evidence/android-2026-10-23-thor-lifecycle-pass.json).
After a physical recovery pass, the next implementation can promote the measured
ownership/recovery rules into fresh-only persistent realm storage with restart
and data-retention safeguards. Do not replay the populated seed over existing
saves. Auth/world orchestration, local account provisioning, matching client
DBC/maps/vmaps/mmaps, world entry and gameplay remain distinct later gates.
