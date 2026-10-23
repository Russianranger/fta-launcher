# User-controlled retained database and auth sessions

Private **0.10.0-local-service**, code **17**, advances from the physically passed
code 16 auth diagnostic to an explicit foreground service. **Start local database
+ auth** opens the existing realm and keeps the original auth listener running
until **Stop**, with an automatic 30-minute session limit. World startup and
valid-account login remain unqualified; matching DBC/maps/vmaps/mmaps are absent.

## Physical result and remaining measurement

Code 17 passes user-controlled Stop and retained restart on AYN Thor. Both sessions
have all ten flags true; all 54 native records exit zero and both database opens
finish clean SQL shutdown after auth exits zero. The retained generation and all
24 metadata expectations pass before/after each session. Export SHA256:
`412d43a2f39a34f08074c8880971d2937db69174c071468a1020d0548c4d67b5`.
See [physical evidence](evidence/android-2026-10-23-thor-local-service-pass.json).
The export records user Stop but cannot identify which Stop control was used.

Healthy screen-off time is **112,127 ms**, below the unchanged **120,000 ms**
requirement. `localServiceQualified` and `localServiceRestartQualified` are true;
`screenOffLocalServiceQualified` is false. Code 18 displays the measured live
counter. Bundle one three-minute screen-off session with the next
[game-data import test](game-data-import.md); do not repeat the two-session restart,
updater, automatic auth, backup, realm preparation or recovery milestones.

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
foreground service scheduling or screen-off behavior. Physical code 17 now supplies
Stop/restart evidence and partial screen-off duration as detailed above; world and
gameplay readiness remain separate.
