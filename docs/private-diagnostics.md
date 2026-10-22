# Private native diagnostic builds

Public CI snapshots package the database provider. A source-matching authserver
can additionally be staged for a private diagnostic APK without putting user
archives, seed SQL or compiled game-server files into Git. The recipient already
has the original source distribution; reviewed port patches and build recipes
remain in this repository.

Use `tools/stage_auth_runtime.py` to extend a notice-complete provider stage with
an actual ARM64 Release authserver, its measured dependency closure and the
hash-only auth-seed manifest. Staging must reject host binaries, missing target
libraries or mismatched source/build identities. See `docs/auth-runtime.md` for
the exact interface. The APK must be verified against that stage after building.

The auth test is optional and appears only when the APK supplies the corresponding
native role and metadata. It verifies the exact private database ZIP, imports
only its 27 auth table scripts into a fresh disposable schema, stages its one
auth runtime update and runs the compiled original authserver's `--dry-run`.
The listener is disabled. This does not qualify a running realm/world, player
login, or world/character migrations.

Follow-up reports include a bounded native invocation journal: role, diagnostic
stage, completion/timeout/cancellation outcome, exit code, duration and output
sizes. They also distinguish missing completion markers from process failures,
record observed database readiness and SQL shutdown, and summarize known auth
errors with fixed phrases. Arguments, environment, SQL, rows, credentials and
private paths remain omitted. A fresh attempt identity binds the status, auth
receipt and journal; older session logs are not exported as the current test.
Journal write failures cannot prevent native cleanup.

Realm eligibility evidence is read-only and exposes only three booleans: whether
realm rows exist, whether any remain eligible after the core's offline flag, and
whether every eligible candidate's three address fields are numeric IPv4.
No realm names, addresses, identifiers, rows or counts are exported. Ordinary
missing-property warnings that explicitly use defaults are distinguished from
fatal configuration failures.

## Signing and updates

Private test APKs use a persistent development signing key. Its private capsule
is named `FTA-Launcher-Development-Signing.zip` in the user's private files.
Recover and reuse that capsule for subsequent builds; do not generate a new key
when continuing this project. Never commit, print or publish its contents.

The Gradle build accepts `-PftaSigningStore=/private/fta-development.p12` and
`-PftaSigningAlias=ftadevelopment`; passwords are supplied only through
`FTA_SIGNING_STORE_PASSWORD` and `FTA_SIGNING_KEY_PASSWORD`. Optional
`-PftaVersionCode` / `-PftaVersionName` set the exact diagnostic version. Without
an explicit signing store, CI retains ordinary disposable debug signing.

The older setup-only debug APK used a different certificate. Android requires a
one-time uninstall of that earlier setup app before installing a persistent-key
diagnostic. Later persistent-key builds can update it while retaining imports.
No setup/diagnostic release contains player saves.

The first persistent-key build is version code 3, `0.3.0-auth-diagnostic`.
Its signing certificate SHA256 is
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Use a larger version code for subsequent private updates. The checked APK's
identity and qualification limits are in
[`evidence/android-2026-10-22-private-auth-apk.json`](evidence/android-2026-10-22-private-auth-apk.json).

The corrected socket diagnostic is version code 4,
`0.3.1-auth-socket-diagnostic`, signed by the same certificate. Its measured build
and payload evidence is in
[`evidence/android-2026-10-22-private-auth-socket-apk.json`](evidence/android-2026-10-22-private-auth-socket-apk.json).
Update it over version 3 to retain imports. Future private updates must increase
the version code beyond 4.

Resolver checkpoint: version code 5, `0.3.2-auth-resolver-diagnostic`, uses the same certificate
and the Android resolver correction. Its evidence is in
[`evidence/android-2026-10-22-private-auth-resolver-apk.json`](evidence/android-2026-10-22-private-auth-resolver-apk.json).
Future private updates must use a version code greater than 5.

Full-seed checkpoint: version code 6, `0.4.0-full-seed-diagnostic`, uses
the same certificate and retains the passing resolver-corrected authserver.
Install it over version 5 to keep the verified database ZIP. Select
`Test full original database seed`, allow the fresh disposable import to finish,
and export its JSON report. It requires at least 3 GiB free; the attempt has a
30-minute deadline and a notification Stop action. A source ZIP is not required.
The full-seed mode checks the original catalog and receipt fingerprints without
replaying runtime SQL or starting auth/world listeners. See
[`database-gate.md`](database-gate.md) and the
[exact build evidence](evidence/android-2026-10-22-private-full-seed-apk.json).
Future private builds must use a version code greater than 6 and the same key.

Charset checkpoint: version code 7, `0.4.1-charset-diagnostic`, is signed with the same key.
It corrects charset-data routing after version 6 failed on the first ASCII
column, following 158 successful table imports. The existing XML files and all
provider native bytes are preserved. The early database check now creates an
ASCII/ascii_bin column and verifies its exact bytes before starting the seed.
Install over version 6 and rerun `Test full original database seed`; keep the
imported ZIP. See the [device failure evidence](evidence/android-2026-10-22-thor-full-seed-attempt.json)
and [exact corrected APK evidence](evidence/android-2026-10-22-private-charset-apk.json).

Version 7 has now [passed the complete full-seed test on physical Thor](evidence/android-2026-10-22-thor-full-seed-pass.json):
563 tables and one trigger, all three exact schema/receipt baselines, complete
577-record native journal, and observed SQL shutdown with database exit 0.
The report qualifies disposable seed compatibility; runtime SQL was not applied.
The next substantial diagnostic will exercise the actual compiled worldserver's
three-database updater through the original missing-map startup boundary.
Use the same key and a version code greater than 7 for future private builds.


## Compiled three-database updater checkpoint

Latest private version code **8**, `0.5.0-compiled-updater-diagnostic`, adds the
actual completed ARM64 worldserver. All 52 modules, C++20, static scripts/modules
and default Lua 5.2 remain present. The clean APK passed all 136 Android unit
tests (including the real private Database.zip regression), lint, assembly,
signature/alignment and 221 exact staged runtime/notice inputs. See the
[exact APK evidence](evidence/android-2026-10-22-private-world-apk.json) and
[stage evidence](evidence/android-2026-10-22-private-world-stage.json).

Install as an update over version 7 with the same persistent certificate. Keep
Database.zip imported and at least 3 GiB free, then select
**Test compiled three-database updater** and export its JSON report. A source ZIP
is not required on the device. The attempt has a 40-minute overall deadline and
a 10-minute world-process deadline. It repeats the full-seed baseline, stages
original runtime SQL and all 49 unchanged module configurations, and runs the
normal compiled updater to its deliberate missing-map boundary. Exact receipts,
prepared-statement completion and shutdown are required; any unrelated error
fails the gate. No auth/world listener is requested.

Deliver the APK with its matching private corresponding-source companion. This
checkpoint qualifies the build and packaged bytes only. Android compiled-updater
execution, persistent services and gameplay require their own measured evidence.
Future private updates must reuse this key and use a version code greater than 8.
