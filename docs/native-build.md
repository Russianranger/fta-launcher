# Native source and build gate

The 2026-10-20 From the Ashes distribution is an external, pinned input. No server
source, database seed, or client content is committed to this repository. The
launcher tooling is independently written; the server's GPL-2.0-or-later headers,
module licenses and vendored dependency licenses remain applicable to any future
server distribution. `manifests/reference-inputs.json` records all three archive
SHA256 values, the upstream base commit, every one of the 52 module directories,
license-file hashes and a digest covering every source path and file byte.

## Prepare the exact source

Keep inputs and builds outside the checkout. Supply the original source ZIP:

```sh
python3 tools/fta_source.py stage /private/From_The_Ashes_Source.zip /private/fta-source
python3 tools/fta_source.py inventory /private/fta-source
```

The stage command refuses a different SHA256, existing destination, traversal,
absolute/Windows paths, symlinks, duplicate paths, file/directory collisions,
encrypted files, more than 20,000 entries, entries above 128 MiB or total declared
contents above 1 GiB. It streams files through a 1 MiB buffer, checks ZIP CRCs,
preserves file bytes and ordinary permission bits, and stages through a sibling
temporary directory. It never modifies the input archive. Interrupted extraction
does not produce a completed destination; stale `.fta-source-*` temporary folders
may be removed after confirming no stage process is running.

The distribution also includes one top-level `README.txt`; that explicitly
allowed auxiliary file has its own manifest hash and remains in the original
archive. The destination is the `AzerothCore_Linux` source tree, which is copied
without extra generated files.

## Authserver before worldserver

The build driver verifies the complete source digest before and after building.
It defaults to one compiler job, permits at most two, considers cgroup/host memory
and rejects insufficient memory. Ninja compilation and linking have separate
pools, with one link at a time. Precompiled headers are disabled to limit memory.
Use isolated build directories for host and Android evidence.

```sh
python3 tools/build_native.py --source /private/fta-source \
  --build-dir /private/fta-build-host-auth --platform host --stage auth --dry-run
python3 tools/build_native.py --source /private/fta-source \
  --build-dir /private/fta-build-host-auth --platform host --stage auth
python3 tools/build_native.py --source /private/fta-source \
  --build-dir /private/fta-build-android --platform android --stage world \
  --toolchain /private/android-toolchain.cmake \
  --dependency-prefix /private/android-dependencies \
  --mysql-library /private/android-dependencies/lib/libmariadb.so \
  --mysql-include /private/android-dependencies/include/mariadb
```

`--stage world` configures both applications and builds authserver successfully
before attempting worldserver. Both stages retain Release, C++20, static scripts
and static modules. Existing cache entries that disable modules or script groups
are rejected. The driver has no module-exclusion or arbitrary CMake-override
option. An auth-only build does **not** qualify worldserver or its modules.
`--configure-only` and `--dry-run` report only their actual scopes. No command
installs binaries, imports SQL or starts a server.

Android cross compilation needs target-built Boost, OpenSSL and MySQL-compatible
headers/libraries; a host MySQL installation is insufficient. The explicit
toolchain must configure the NDK/Bionic target. The driver disables host
`mysql_config` probing in Android mode to avoid importing host link flags. The
original CMake source's `FindMySQL.cmake` expects MySQL 8.x and other places may
still need reviewed MariaDB adaptations. Do not fake versions, disable updater
receipts or add permissive duplicate-symbol linker flags to bypass failures.

## Initial measured status

The supplied source has 5,551 files, 52 module directories and tree SHA256
`2528155574583a1a51edcb8e8a5fd7548a0d28ad15f8e93c1a99a520781efe14`.
The original source has Android-compatible architecture detection and routes
Android through UNIX/Clang platform settings; this static observation is not a
successful configure or build. The initial workspace provides `g++` but lacks
`cmake`, `ninja`, Clang and an Android NDK/dependency sysroot. A real driver attempt
also stopped safely at its memory preflight: less than 2 GiB remained in the
8 GiB cgroup at that moment. Host and Android server
compilation, module qualification and physical Thor execution remain pending.

Build logs are private under the chosen build directory. Publish reviewed
summaries, tool versions and binary hashes only; do not upload unfiltered logs or
configuration files that could later contain credentials. A passing compiler
report still leaves database, game-data, graceful shutdown and Thor qualification
open.
