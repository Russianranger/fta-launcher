# Android ARM64 database provider staging

`tools/stage_android_provider.py` stages the exact MariaDB **12.3.2 Bionic
ARM64** runtime recorded in `manifests/android-provider-lock.json`. This is
independent FTA packaging tooling; no Pocket Realm implementation is copied.
The reference lock's binary identities and package metadata are retained.

The output contains two executable ELFs and nine private libraries. The server
and client are named `libfta_mariadbd.so` and `libfta_mariadb_client.so` for
Android's APK native-library extraction. Library files use unique hashed `.so`
basenames; the manifest retains their original dynamic-loader lookup names.

## Reproduce the stage

Use Python 3.10+ and GNU `readelf` (or pass `--readelf` with an LLVM equivalent).
Keep downloaded distributions and generated output outside Git:

```sh
python3 tools/stage_android_provider.py \
  --fetch-apk /private/cache/Pocket-Realm-0.100.2-alpha-arm64-v8a.apk \
  --output /private/fta-provider-stage
```

The fallback is the original upstream release asset, pinned by whole-container
SHA256 `5e04073375c7305919530e08fc8925870c8084eb83103840007913d13abb00e8`.
An already downloaded copy may be supplied with `--from-apk`; `--offline`
forbids network fetching. Only the locked database provider is extracted from
the APK. Client, Wine and unrelated runtime payloads remain untouched.

The primary package route is:

```sh
python3 tools/stage_android_provider.py --packages-dir /private/termux-cache \
  --output /private/fta-provider-stage
```

The exact packages are SHA256 checked. `--mirror-base` can point at another
Termux main-repository mirror, but cannot change the required bytes. Cached
hash mismatches fail instead of silently replacing the cache or upgrading.
The original canonical 12.3.2 package URLs returned HTTP 404 during this work;
the verified fallback APK supplied byte-identical pinned binaries and system
SQL. A newer package version is never accepted implicitly.

## Output and Android integration

| Output | Purpose |
| --- | --- |
| `jniLibs/arm64-v8a/*.so` | Immutable APK-managed executable and library files |
| `assets/fta-runtime-manifest.json` | Native/data hashes, sizes, roles, dependencies and ABI |
| `assets/runtime/data/provider/bootstrap.sql` | Original combined MariaDB system bootstrap SQL |
| `assets/runtime/data/provider/share/mysql/` | Original system/help/charset/message files |
| `assets/runtime/legal/` | Received upstream notice, FTA subset attribution, full terms and source evidence |
| `BUILD_PROVENANCE.json` | Input identity, bootstrap component hashes and staging scope |

Manifest `files[].path` begins with `native/` or `data/`. Data is stored under
the APK's `assets/runtime/` prefix. The application validates every pinned file
before use. It creates private lookup-name symlinks that target APK-extracted
native libraries and supplies that lookup directory through `LD_LIBRARY_PATH`.
Two pinned Termux libraries omit `DT_SONAME`; their locked lookup names remain
valid dependencies. No ELF is rewritten or stripped to change those names.

Keep `extractNativeLibs=true`, legacy JNI extraction and native debug symbols
in Gradle so Android installs executable files under `nativeLibraryDir` and
the recorded SHA256 values remain intact. A target-SDK-35 app must not execute
ELFs copied into writable `filesDir`. Runtime ZIP imports are inert data.

Bootstrap uses the immutable server executable's `--bootstrap` mode with the
verified SQL on stdin. It does not run a shell or `mariadb-install-db` script.
The combined bootstrap preserves the six original package SQL files and sets
`@auth_root_socket=NULL`, matching the original database initialization protocol.
Application credentials and FTA schemas are separate later operations.

Relocation also requires an explicit `--character-sets-dir` for the server
(including bootstrap) and each direct MariaDB client. Use the verified private
`data/provider/share/mysql/charsets` directory. The provider's compiled layout
uses `share/mariadb`; overriding `--basedir` or `--lc-messages-dir` does not bind
the separately resolved character-set directory to the staged `share/mysql`.
Require the pinned `Index.xml` and `ascii.xml` before launching native tools.
The database probe creates an ASCII/ascii_bin column and verifies an exact byte
round trip alongside Unicode and rollback before importing any private seed.

Physical version 6 reached its first explicit ASCII column after 158 table
imports and returned error 1115. The exact APK already contained the required
XML. Version 7 corrects the directory binding without changing those files or
the native ELFs. The [bounded device evidence](evidence/android-2026-10-22-thor-full-seed-attempt.json)
and [host/source probe](evidence/database-2026-10-22-charset-path-probe.json)
separate the measured failure from the corrected device result, which still
requires a Thor retest.

## Validation and evidence

The stage checks complete input identity, traversal/duplicate/link/size limits,
ELF64 AArch64, position independence, the Android interpreter, original native
hashes, library lookup names and the entire `DT_NEEDED` dependency closure.
Only Android platform libraries can remain outside the nine private libraries.
Generated output is published only after validation and an existing output
directory is never replaced. SQL placeholders or bootstrap hash mismatches
stop the stage. Staging does not execute SQL or start a database.

The real pinned APK stage passed: all eleven ELF hashes and bootstrap hash
match the reference; both executable interpreters are `/system/bin/linker64`;
every ELF load segment has at least 16 KiB alignment; nine private dependencies
resolve. Eighteen safety tests cover malicious package/APK paths, duplicate
names, links, truncation, hash drift, incomplete dependency closure, wrong
architecture/interpreter, failure preservation and required notice identity.

Every stage requires the complete hash-pinned legal set under
`licenses/android-provider/` before processing distributions or publishing an
output. The received Pocket Realm notice is preserved unchanged, including its
upstream three-year corresponding-source offer. The FTA subset document
identifies the components actually included and records a recipe-based
correction to its libandroid-support license label. Missing or altered notices
fail closed; `--notices-path` permits an external byte-identical copy of the
received upstream document. The pinned copy also exists in the source APK.

`BUILD_PROVENANCE.json` records `noticeProvided=true` and the full legal-file
hashes only after they are copied to the staged APK assets. Source evidence
distinguishes a received upstream written offer, retrieved historical recipes
and individual source-archive hash checks from a complete independently
verified corresponding-source release or a reproduced binary build. Preserving
the received offer does not convert it into a claim of independent source
verification by this project.

This proves a reproducible provider stage, **not Android execution or FTA seed
compatibility**. The manifest records those gates as unqualified. Public
redistribution qualification remains separately explicit rather than inferred
from having notice files. No generated runtime binary, database seed or user
record is committed here.
