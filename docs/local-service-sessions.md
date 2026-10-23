# User-controlled retained database and auth sessions

Private **0.10.0-local-service**, code **17**, advances from the physically passed
code 16 auth diagnostic to an explicit foreground service. **Start local database
+ auth** opens the existing realm and keeps the original auth listener running
until **Stop**, with an automatic 30-minute session limit. World startup and
valid-account login remain unqualified; matching DBC/maps/vmaps/mmaps are absent.

## One physical handoff

1. Install the verified private code 17 APK in place, preserving the realm,
   verified backup, imported Database.zip and native dependencies. Public debug
   APKs have another certificate and cannot update this installation.
2. Select **Start local database + auth**. Wait for **Local database and auth are
   running**, then lock the screen for **2 minutes 20 seconds**.
3. Wake the screen and use **Stop** in the From the Ashes notification. If
   notifications are disabled, use **Stop local service / test** in the app.
   Wait for **Local service stopped cleanly**.
4. Start it again, wait for running status, allow 20 seconds, then stop again.
   Export **one diagnostic report** after clean completion. If either session
   fails, export immediately instead of repeating the test.

Expected final fields: `lastDiagnostic.state=LOCAL_SERVICE_STOPPED`,
`localServiceQualified=true`, `localServiceRestartQualified=true` and
`screenOffLocalServiceQualified=true`. Both sessions should have `stopReason=user`,
one auth exit zero, one SQL database shutdown and all ten required checks true.
Each session independently rechecks all 24 original metadata expectations before
and after auth. The JSON retains the current and immediately preceding session;
a failed or interrupted session breaks the restart chain.

No updater, automatic local-auth, backup, initial realm preparation or disposable
recovery milestone needs repeating. Export JSON only; the backup contains private
database data and credentials.

## Implementation and evidence boundaries

The existing persistent-store OS lease spans each entire session. Startup checks
the active generation, source/provider identity and complete backup archive hash
before opening the database. It verifies staged provider inputs, retained canary
and exact schema/update receipts. No bootstrap, seed replay or world launch runs.

The measured original auth configuration, generated least-scope database role,
guarded offline realm transition and build-12340 unknown-account challenge are
shared with code 16. Auth binds only to IPv4 loopback on a selected unprivileged
port. The database remains Unix-socket-only on Android. No game account is made.
The world remains explicitly offline. Original compiled updater policy is intact.

Stop uses a distinct request flag so cleanup is not mistaken for cancellation.
The worker finishes its bounded health operation, stops/reaps auth with exit zero,
checks listener closure, removes the temporary role, repeats strict metadata and
canary checks, then issues SQL SHUTDOWN and requires database exit zero. Unexpected
owner destruction still uses existing JNI child ownership/parent-death cleanup;
it is a failed session, never an automatic restart or clean-stop qualification.
Stop during setup can finish before auth starts; that reports `before_start` and
does not qualify an auth session.

Live SQL and protocol health are sampled every ten seconds. Screen-off time is
credited only between successful probes with the screen observed noninteractive
throughout the interval; gaps over 15 seconds receive no credit. At least 120
seconds of measured screen-off health in a clean session is required. This is
observational sampled evidence, not a claim to observe every hardware transition.
The partial wake lock covers the bounded session and cleanup; the service is
nonexported, not sticky and never starts at boot. The 30-minute limit caps log and
journal growth (at most 181 regular health samples), below the existing 1,024
record limit. Native timeout, cancellation, log truncation or dropped records
prevent qualification.

Receipts export only validated booleans, bounded timings/counts, pinned hashes and
native outcomes. They omit credentials, usernames, SQL, rows, paths and raw logs.
Current status/attempt, original identity, active generation, both metadata rounds,
auth termination, SQL shutdown and exact health-record count must agree. A prior
receipt cannot qualify the current session or another generation.

## Checks completed before handoff

The private APK passes 230 Android tests, lint and assembly, 142 tooling tests,
221 runtime/notice input checks, v2 signing and 16 KiB alignment. Its certificate
and all 16 native payloads match code 16. Original native sources, source SQL and
strict metadata expectations are unchanged. Tests cover early Stop, normal Stop,
automatic session limit, failed health, cancellation, nonzero auth exit, listener
closure failure, screen transitions, long gaps and the full simulated session.

`tools/check_local_auth_backup.py --service` restores the exact private Thor
backup through production Kotlin, then runs two real MariaDB/auth sessions with
the production `LocalServiceProtocol` and JNI owner. A separate thread requests
Stop in each session. Each auth listener must close; temporary generated roles
must be removed, all 24 strict metadata checks pass before/after each session,
account/character counts stay unchanged, and SQL shutdown exits zero before the
same database reopens. The original backup ZIP remains byte-identical.

See [physical code 16](evidence/android-2026-10-23-thor-local-auth-pass.json),
[host sessions](evidence/database-2026-10-23-host-local-service.json) and
[private APK](evidence/android-2026-10-23-private-local-service-apk.json).
The unchanged automatic two-pass auth path also passes the updated host fixture:
two additional clean auth stops and 20 SQL health queries, preserving all original
27 auth tables and account counts. See [regression evidence](evidence/database-2026-10-23-host-auth-service-regression.json).
Host TCP adapters do not execute Android socket credentials, notification Stop,
foreground service scheduling or screen-off behavior. Those are the remaining
physical code 17 gate; gameplay/world readiness remain separate.
