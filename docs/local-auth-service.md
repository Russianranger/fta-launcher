# Retained realm and original local auth service

Private version **16**, **0.9.1-auth-role**, advances from the physically passing
version 14 backup/restore milestone. It opens the retained database, verifies all
24 unchanged metadata expectations, starts the original authserver twice on an
ephemeral IPv4 loopback port, checks its build-12340 protocol response and SQL
health, stops auth cleanly, rechecks all 24 expectations, then uses SQL SHUTDOWN.
It never imports the populated seed, creates a game account or starts worldserver.

## Evidence that determined the change

Physical code 15 failed before launching authserver. Its retained database and
24 metadata checks passed; the guarded stale-flag transition succeeded and the
temporary role was removed. The active generation still verifies. All 13 native
records exited zero, but the report does not observe SQL shutdown. See the
[bounded physical report](evidence/android-2026-10-23-thor-local-auth-attempt.json).

The launcher generated a UUID-based role containing digits, then rejected it
with the older letters/underscores-only config validator. Every UUID v4 supplies
a `4` in the retained suffix, making failure deterministic. Code 16 permits
`[a-z_][a-z0-9_]{0,31}` and validates the entire config before any realm flag or
role write. Delimiters, Unicode, whitespace and oversize names still fail.
The shared production name generator now feeds both unit regressions and the
original-auth host fixtures. The previous host fixture's fixed alphabetic name
explains why it missed this launcher error.

The version 14 report passed all eight backup checks and all three database
opens (original, restore 1, restore 2), with 21 successful native records. The
uploaded backup exactly matches the reported size and SHA-256; all 1,357 file
hashes match its manifest. The JSON predates the later backup export, so its
`exportedCopyVerified` flag is false. The uploaded ZIP independently verifies
the copy; the Android SAF readback receipt itself remains unobserved. Do not
repeat the passing backup/restore milestone for that reporting order.

An additional host run restored that exact Thor backup through the production
Kotlin implementation. All 24 metadata expectations passed, but the original
authserver refused to start because no realm remained eligible after setting
its offline flag. This reproduced a real retained-data boundary before asking
for another physical test. It was not a schema or receipt mismatch.

The original `apps/worldserver/Main.cpp` sets VERSION_MISMATCH (bit 1) before
loading maps and clears it only after successful world initialization. The
already-qualified missing-map exit leaves that startup flag behind. Original
`apps/authserver/Main.cpp` adds OFFLINE (bit 2), and its realm query excludes the
resulting value 3. See the [source-bound observation](evidence/database-2026-10-23-auth-startup-flag-boundary.json).

`LocalRealmAuthState` handles this lifecycle boundary for the already configured
realm 1 only. Before any update it requires exactly that row, gamebuild 12340,
and flags in 0..3. It clears the stale startup bit while explicitly setting
OFFLINE. It verifies the resulting offline state before starting auth. Missing
rows, another client build, unexpected flags or a failed postcondition block
startup. No blanket flag reset, build rewrite, account change or online claim
is made. Original native behavior, source SQL and strict expectations remain
unchanged. Normal original auth startup also retains its offline/ban handling.

## One substantial physical test

1. Install the verified private code 16 APK in place. Keep the existing realm,
   backup, Database.zip and native dependencies. Do not uninstall, clear app
   storage or substitute a public debug APK.
2. Select **Test local auth service**. Allow about two minutes. The test checks
   both listener starts and stops automatically; it leaves the realm offline.
3. Use **Export diagnostic report** after completion and send only that JSON.
   If it fails or is stopped, export the report immediately.

Expect `LOCAL_AUTH_PASSED`, `localAuthQualified=true`, all required nested checks
true, two auth processes exiting zero, six strict metadata queries, two retained
identity checks and a clean database shutdown. The supplied code 15 run already
cleared the stale flag; `staleStartupFlagCleared=false` is valid for this retry
because the realm is already in the verified offline state.

No updater, lifecycle, recovery, first persistent preparation or backup test
needs repeating. This is the next physical listener test, not a playable realm.

## Ownership, transport and privacy

The existing persistent-store lease spans the entire operation. A verified cold
backup bound to the active pointer and exact source/provider identity is required
before opening the database or changing realm state. Its archive is rehashed.
The database remains Unix-socket-only on Android. Auth gets a random database
role with privileges on `acore_auth` only; clean completion removes it. Interrupted
attempts may leave that private role, but never reuse its unknown credentials or
replace an existing role. Temporary configurations are private and deleted.

Android's INTERNET permission is now needed for the loopback listener/probe.
The generated configuration hardcodes `127.0.0.1`; socket activation is disabled.
The listener uses a newly selected unprivileged port and must be absent before
each start and after each stop. There is no LAN listener or caller-supplied bind
address. The database and auth children retain the existing JNI ownership and
parent-death protections. Auth stops before database shutdown; cancellation or
uncertain cleanup cannot qualify an attempt.

The readiness request is a source-derived build-12340 login challenge for a
random name proven absent by SQL. It requires the original unknown-account
response. No password, SRP proof, account creation or realm-list request is sent.
Each launch includes 20 seconds of repeated protocol and SQL health checks.
The report contains booleans and bounded native outcomes, without usernames,
credentials, private paths, SQL, rows or raw native logs.

APK SHA256: `8d472f42651e1c2ce4de4023db4efcfd89f8eede2d5d67a25cf12796dbabc39c`.

The private build passed **217 Android tests** (23 classes, no failures or skips),
lint/assembly, **142 tooling tests**, all **221** runtime/notice input checks,
v2 signing and 16 KiB alignment. Its certificate and all **16 native payloads**
match version 15. The regression failed with the old validator and passes with
the correction. Both original-auth host fixtures pass with production-generated
UUID roles: four clean auth stops and 40 SQL health checks in total. The restored
Thor backup passes all 24 strict metadata checks before and after auth, preserves
account/character counts and shuts down through SQL with exit zero.
See [private APK evidence](evidence/android-2026-10-23-private-auth-role-apk.json),
[seed host evidence](evidence/database-2026-10-23-host-local-auth-role.json) and
[restored-backup host evidence](evidence/database-2026-10-23-host-thor-backup-auth-role.json).
Host TCP adapters remain distinct from Android socket/ARM64 execution.

## Qualification and limits

Android unit tests cover the wire packet, fragmented/truncated/wrong responses,
loopback configuration, both lifecycle failure paths, cancellation, evidence
binding, guarded offline transitions and rejection before writes. Full lint and
private APK assembly are required, followed by exact-commit CI.

`tools/check_local_auth.py` exercises production Kotlin wire/lifecycle code and
JNI against the original host authserver, exact MariaDB 12.3.2 and unchanged
27-table auth seed. `tools/check_local_auth_backup.py` adds production restoration
of the exact private Thor backup and all 24 production metadata checks before
and after auth. The latter never opens or modifies the uploaded ZIP itself.
Host-only TCP adapters use generated private roles in disposable databases;
they do not execute Android socket credentials, service behavior or ARM64 code.

The next work after a passing physical report is retained service start/stop and
matching client-data import for database → auth → world orchestration. Matching
DBC/maps/vmaps/mmaps are still required. Valid-account authentication, world
entry, gameplay and external backup activation remain unqualified.
