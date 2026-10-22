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
