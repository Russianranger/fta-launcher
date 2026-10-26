# Private native diagnostic builds

## Current private delivery — code 37 client memory backpressure

Physical code 34 passed the complete native world gate, including original GT
fallback, all 113 DBC stores, 259.2 seconds healthy screen-off, user Stop, clean
exits and retained metadata/GT postchecks.
[World proof](evidence/android-2026-10-25-thor-code34-world-pass.json).
Physical code 35 passed the complete 60.24 GB client folder inspection and exact
x86 build observation. [Inspection proof](evidence/android-2026-10-25-thor-code35-client-inspection.json).

[Code 37](code37-recovery.md) adds [memory backpressure](client-import.md) after the
code-36 client copy was interrupted at 4.87 GB. It retains code-36 checkpoints and
the same 21 native payloads / 148 runtime entries. Follow the [current handoff](SHORT-HANDOFF.md)
for exact CI, artifact identity and availability. The full 10,476-file import,
readback/publication and subsequent retained rehash have now passed. Keep the
installed code 37; no new physical test is requested. [Runtime preparation](client-runtime-preparation.md)
is source-only and is not delivered in the installed private APK. Do not repeat the
passed inspection, server, screen-off, database/auth or game-data import tests.
WarcraftXL execution, exact x64 host pairing and playable login remain open.

Historical milestones below are evidence, not instructions to repeat passed work.
Code 30 was withdrawn before physical testing; do not install it.

For recovery packaging, `python3 tools/package_private_update.py --help` documents
the inputs. It uses the exact passing CI assembly and verified unchanged donor
native/runtime/notice bytes, requires unchanged application/native/manifest source
and helper source identities, and signs only with the persistent private capsule.
Passwords stay in child-process environment variables. It rejects an existing
output path, stale metadata, a version downgrade, wrong certificate or altered
application payload. For the additive code-32 repair, supply `--legacy-provider`,
`--fault-witness` (v2), `--world-session` and `--world-source-companion` explicitly.
The new corresponding-source companion preserves every original source/notices
member and adds the reviewed repair sources and full build evidence. The helper
source is public MIT code; `assets/metadata/launcher-LICENSE.txt` must match the
repository license. The repaired world is selected only for retained sessions;
original updater and realm/backup identities remain unchanged. See
[the integration and next gate](world-session-repair.md). Package qualification
does not set native database fallback, world readiness or gameplay flags.


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
That checkpoint was followed by the actual compiled worldserver's
three-database updater test, which passed on physical version 10 below.
Use the same key and a version code greater than 7 for future private builds.


## Compiled three-database updater checkpoint

Compiled-updater checkpoint version code **8**, `0.5.0-compiled-updater-diagnostic`, adds the
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


## Column metadata checkpoint

Previous private code **9**, `0.5.1-column-diagnostic`, preserves all 16 native
payloads, the signed runtime manifest, both locks and original SQL from version 8.
It adds read-only before/after world-column observations so a failed strict check
can be localized without exposing query results. See
[diagnostic scope and host evidence](world-column-diagnostic.md) and
[exact APK evidence](evidence/android-2026-10-22-private-column-apk.json).
Install over version 8, retain Database.zip, keep 3 GiB free and run the same
**Test compiled three-database updater** action. Export the report even if the
same failure remains. Future private versions must exceed code 9 and reuse the
persistent certificate. The native source companion from version 8 is unchanged.

## Metadata time-zone correction

Private code **10**, `0.5.2-metadata-timezone`, fixes the proven metadata-session
time-zone mismatch in the two `game_event` timestamp defaults. It retains the
exact version 9 certificate, all 16 native payloads, both metadata locks and
original SQL. Only metadata readers use the original host reference's explicit
+07:00 session setting; import/updater sessions and server time remain untouched.
See [cause and reproducible regression](world-column-diagnostic.md) and
[APK evidence](evidence/android-2026-10-23-private-timezone-apk.json).
Physical version 10 now [passes all 24 checks and both complete host snapshots](evidence/android-2026-10-23-thor-compiled-updater-pass.json), with 587 complete native records and clean SQL shutdown. Preserve this milestone; do not rerun it for the unchanged code.

## Database lifecycle checkpoint

Private code **11**, `0.6.0-database-lifecycle`, retained the certificate and all
16 native payloads. It now [passes physical lifecycle and screen-off checks](evidence/android-2026-10-23-thor-lifecycle-pass.json):
all ten observations, 85 complete records and both clean shutdowns, with only the
intentional forced stop and client interruptions. No repeat run is needed.

## Runtime service recovery checkpoint

Private code **12**, `0.6.1-runtime-recovery`, keeps the same certificate
and all 15 provider/server/dependency native payloads. It rebuilds only the
process supervisor to expose a live owned-child PID for read-only observation.
Do **not** restore the older supervisor during packaging: it lacks this JNI API.
Its source and build identity accompany [the exact APK evidence](evidence/android-2026-10-23-private-runtime-recovery-apk.json).
All 164 Android tests, lint/assembly, 142 tooling tests, native ownership checks,
ASan/UBSan, 221 packaged inputs and signing/alignment passed. Real host MariaDB
passed the shared recovery protocol, committed-data/rollback SQL and JNI owner.

Physical code 12 now [passes all runtime recovery gates](evidence/android-2026-10-23-thor-runtime-recovery-pass.json).
Its Stop action, actual service-process death, independent native-child termination,
same-database recovery and final SQL shutdown are established. No repeat is needed.

## Persistent realm storage checkpoint

Private code **13**, `0.7.0-persistent-realm`, retains the certificate and
all 16 native payloads from code 12. Packaging verifies the supervisor's source
identity before retaining those already qualified helper bytes. Do not restore
an older pre-code-12 helper. The [APK evidence](evidence/android-2026-10-23-private-persistent-realm-apk.json)
records 181 Android tests, lint/assembly, 142 tooling tests, 221 packaged inputs
and signing/alignment. The shared storage protocol/SQL/JNI also passed real host
MariaDB across two owning JVMs without reimport or loss of retained data.

Physical code 13 [passed retained database reuse](evidence/android-2026-10-23-thor-persistent-realm-pass.json).
The backup/restore gate also passed in code 14; the next gate is [local auth](local-auth-service.md).
An ordinary same-key update preserves imports and retained generations. Future
private builds must exceed code 15. Public debug releases cannot replace the
installed private diagnostic; never uninstall or clear data to force an update.

## Cold backup and restore checkpoint

Previous private code **14**, `0.8.0-realm-backup`, retains the certificate and all
16 native payloads from code 13. It passes 198 Android tests, lint/assembly,
142 tooling tests, 221 packaged inputs and signature/alignment checks. Its
production archive/restore protocol and JNI owner pass exact host MariaDB 12.3.2,
including two restored reopens, seven clean SQL shutdowns, unchanged original
files and an original reopen under a second JVM.

The active realm is never replaced; no seed/updater SQL is replayed. The exported
ZIP contains private database/account data and credentials. Share only the
redacted diagnostic JSON. Follow [the one combined backup check](realm-backup.md).
Physical code 14 passed backup/restore; its uploaded ZIP independently matches
the report and every manifest hash. Its earlier JSON lacks the later Android
SAF readback receipt. Subsequent private builds must increase the
version code and keep this signing certificate; public debug APKs cannot update it.

## Local auth checkpoint

Previous private code **15**, `0.9.0-local-auth`, retains the certificate and all
16 native payloads from code 14. APK SHA256: `95749ca8c01008c816618a9942774025d11468c317325b9c34f1acc6ffdf669c`.
It passes 213 Android tests, lint/assembly, 142 tooling tests, 221 packaged-input
checks, signature/alignment and original-auth host qualification. The additional
restored-Thor-backup test passes all 24 strict metadata checks before/after auth.

Android INTERNET permission supports only the generated loopback listener and
probe in this feature. Database access stays on the private Unix socket. The
realm remains offline after the guarded source-derived startup-flag transition.
Physical code 15 failed during launcher config validation before auth launch;
see the [device evidence](evidence/android-2026-10-23-thor-local-auth-attempt.json).
Do not use public debug releases, uninstall the private app or clear its data.

## Generated auth role correction

Latest private code **16**, `0.9.1-auth-role`, corrects the measured mismatch
between generated hexadecimal database role names and the configuration
validator. It validates configuration before database writes and retains the
32-character and delimiter restrictions. Native payloads and original SQL are
unchanged. APK SHA256: `8d472f42651e1c2ce4de4023db4efcfd89f8eede2d5d67a25cf12796dbabc39c`.

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

Install in place and follow [one local auth test](local-auth-service.md). Physical
code 16 is unverified. Keep the current realm, backup and imported dependencies;
no seed/updater/lifecycle/backup milestone needs repeating.


## Original world crypto provider — code 29

Code 28 recovered code 27's same failed attempt. Its stderr hash exactly matches
the original ARC4 constructor assertion when OpenSSL's legacy provider is missing.
Private code **29**, `0.12.2-world-crypto`, adds that separately pinned module from
the exact OpenSSL 3.6.3 source already supplied in companion v8. The original
17 native payloads, runtime manifest, realm/backup identities and signer remain
unchanged. No assertion, source module or asset validation is bypassed.
See [source reproduction and module qualification](world-crypto-provider.md) and
[measured signed APK evidence](evidence/android-2026-10-24-private-world-crypto-apk.json).

Install the qualified private APK in place. Select **Start native world test**
once, keep the launcher visible until healthy, turn the screen off for three
minutes, reopen, select **Stop**, then export the report. On failure export
immediately before retrying. No reimport or separate database/auth test is needed.
The action's automatic full rehash binds the new asset lease. Native world readiness,
GT fallback and gameplay remain unqualified until their measured gates pass.
