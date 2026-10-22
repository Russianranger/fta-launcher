# Private ARM64 authentication diagnostic packaging

`tools/stage_auth_runtime.py` extends a completed, notice-bearing Android
database provider stage with an **actually compiled** ARM64 authserver. It does
not compile a substitute executable, import a database, open an auth listener,
or claim a playable world. This bundle is for the user's private diagnostic APK;
the custom server ELF and original source ZIP are not public release assets.

## Inputs and command

```sh
python3 tools/stage_auth_runtime.py \
  --provider-stage /private/fta-provider-stage-release \
  --authserver /private/android-build/src/server/apps/authserver/authserver \
  --target-libs /private/android-dependencies/lib \
  --build-report /private/android-auth-report.json \
  --dependency-provenance /private/android-dependencies/target-dependency-provenance.json \
  --auth-seed-lock manifests/auth-seed-lock.json \
  --output /private/fta-auth-runtime
```

The output must be new and outside the input trees. A failed operation removes
only its temporary output. Existing provider data, runtime generations and
source inputs are not replaced.

The build report must explicitly record a completed Android/ARM64 auth build,
Release, C++20, static modules/scripts, auth-only application profile, all 52
preserved source modules, a source digest matching a reviewed port profile, and
the exact authserver artifact SHA256 and size. Host/configure-only reports,
artifact drift, unreviewed source changes and missing evidence are blockers.
Compilation evidence is distinct from execution on Android or Thor.

New shared libraries require matching source/build provenance. The dependency
file has `formatVersion: 1` and `libraries` entries with `logicalName`, built
`sha256`, `license`, and `source` containing `project`, `version`, HTTPS `url`
and source-archive `sha256`. The actual Android Connector/C may use SONAME
`libmariadb.so`; the stager reads the ELF instead of assuming a desktop SONAME.
Only dependencies reached from the actual executable are added.

## Preservation and executable boundary

The base provider's ELF and bootstrap identities must match the repository's
provider lock. Every declared native/data/legal file is hash-checked; undeclared
payloads and filesystem links in the provider stage are refused. The original
files and existing manifest entries are preserved byte-for-byte.

The authserver is packaged as `libfta_authserver.so`. Added private dependencies
receive unique hashed Android `.so` names and retain their logical loader names
in the runtime manifest. Each is inspected for ELF64 AArch64, position
independence and Android Bionic linkage. The complete recursive `DT_NEEDED`
closure must resolve to existing hash-pinned provider libraries, newly verified
target libraries, or the explicitly allowed Android platform libraries. A target
library cannot escape through a symlink, inject a host ELF or silently replace
a provider dependency with different bytes. No executable is stripped, patched
or rewritten during staging.

Only after those checks pass does the manifest gain the `authserver` capability
and `auth-server` role. Worldserver is absent. The exact public auth seed lock is
included as `assets/runtime/data/metadata/auth-seed-lock.json`; this file contains
identities and safe import constraints, never SQL rows or account credentials.
Actual seed scripts continue to come from the user's private reviewed archive.

`AUTH_BUILD_PROVENANCE.json` records the source/build/artifact identities,
dependency provenance, preserved provider provenance, and explicit
`private-user-apk-only` distribution scope. It keeps Android execution and public
distribution qualification false. Android runs only APK-installed immutable
native files under `nativeLibraryDir`; these staging directories themselves are
never an executable runtime installation.

## Validation

Eleven packaging tests cover actual host-ELF rejection, missing dependencies,
library collisions, source/build/artifact drift, duplicate JSON evidence,
escaping library links, preserved provider bytes and existing-output refusal.
Synthetic fixtures exercise packaging safety only. The real first auth stage
also passed using the successful `android-arm64-v1` build: source tree digest
`b621c5f063bc305240ab92b7171f3bfcb8213413df09d86437a249fa88eb2f02`,
authserver SHA256
`acf186557515897a4c359ac2ceee889f52c1482f36f8b012db007a8625b5fd05`,
and a source-built Connector/C 3.4.9 library. Both ELF files use ARM64/Bionic;
authserver is 30,638,440 bytes and has 16 KiB load alignment. Android's public
`libz.so` dependency is allowed separately from the provider's private
`libz.so.1`; it is never redirected to a different library by aliasing.
Provider native/data/legal payload preservation passed for that actual stage.

The intended device diagnostic boots a fresh socket-only disposable database,
imports the pinned auth-only seed, runs authserver's original `--dry-run`, and
shuts down its processes without opening a realm listener or touching live saves.
Successful packaging is not a successful device diagnostic; the exact APK and
exported device evidence remain required for that claim.

The follow-up `android-arm64-socket-v2` stage also passed with authserver SHA256
`bfa532f7c91d59410c10104a2b7e5ce40d5763c59dad36f16c625bf152f3428c`.
It preserves the same dependencies, provider data and notices, while correcting
pool socket routing. Private APK version 4 passed all 83 Android tests, lint,
assembly, signature/alignment and 114 exact packaged-file checks; its corrected
auth execution remains unqualified on Thor. See
[`native-build.md`](native-build.md) for the regression and
[`evidence/android-2026-10-22-private-auth-socket-apk.json`](evidence/android-2026-10-22-private-auth-socket-apk.json)
for the exact APK identity.
