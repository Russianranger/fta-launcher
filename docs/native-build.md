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

Cgroup availability subtracts actual memory usage and includes clean inactive
file cache plus one quarter of clean active file cache that the kernel can
reclaim. The other three quarters of active cache, dirty/writeback pages and slab
are not counted as available. Host `MemAvailable` also caps the result. This
prevents staged archive caches from being mistaken for anonymous compiler memory
while retaining a 512 MiB reserve plus 1,536 MiB per compiler job.

```sh
python3 tools/build_native.py --source /private/fta-source \
  --build-dir /private/fta-build-host-auth --platform host --stage auth --dry-run
python3 tools/build_native.py --source /private/fta-source \
  --build-dir /private/fta-build-host-auth --platform host --stage auth
python3 tools/build_native.py --source /private/fta-android-resolver-source \
  --port-profile android-arm64-resolver-v3 \
  --build-dir /private/fta-build-android --platform android --stage world \
  --toolchain /private/android-ndk-r28c/build/cmake/android.toolchain.cmake \
  --dependency-prefix /private/android-dependencies \
  --mysql-library /private/android-dependencies/lib/libmariadb.so \
  --mysql-include /private/android-dependencies/include/mariadb \
  --mysql-executable /private/provider/jniLibs/arm64-v8a/libfta_mariadb_client.so
```

`--stage world` configures both applications and builds authserver successfully
before attempting worldserver. Both stages retain Release, C++20, static scripts
and static modules. Existing cache entries that disable modules or script groups
are rejected. The driver has no module-exclusion or arbitrary CMake-override
option. An auth-only build does **not** qualify worldserver or its modules.
`--configure-only` and `--dry-run` report only their actual scopes. No command
installs binaries, imports SQL or starts a server.

## Reviewed MariaDB portability profile

The original MySQL API choices do not compile against actual MariaDB Connector/C
headers: `mysql_ssl_mode`, `MYSQL_OPT_SSL_MODE` and `mysql_stmt_bind_named_param`
are absent. MariaDB's `MYSQL_VERSION_ID=101114` identifies compatibility with a
server release, while the loaded Connector/C reports its own `30317` version.
The original runtime comparison rejects that valid header/library pairing.

`native-port/patches/0001-mariadb-connector-api.patch` keeps the original MySQL
branches and introduces provider-specific Connector/C API checks, exact loaded
connector/header version matching and ordinary positional prepared binding.
MariaDB's TLS enforcement option can fall back if a server lacks TLS, so this
profile refuses every explicit, nonempty SSL policy **before authentication**
until equivalent MySQL SSL-mode semantics are qualified. Private Unix sockets
use an empty SSL field. No weaker TLS fallback is introduced. This does not
establish SQL, updater, authentication or worldserver compatibility.

```sh
python3 tools/fta_port.py /private/fta-source /private/fta-mariadb-source \
  --profile mariadb-c-api-v1
python3 tools/fta_source.py inventory /private/fta-mariadb-source \
  --port-profile mariadb-c-api-v1
python3 tools/build_native.py --source /private/fta-mariadb-source \
  --port-profile mariadb-c-api-v1 --build-dir /private/fta-mariadb-auth \
  --platform host --stage auth
```

The profile pins every patch byte, all modified file preimages/results and the
entire resulting tree. Other edits, module omissions and patch-path substitutions
are rejected. All 52 module directories, updater SQL behavior, game logic and
original license files are retained.

Android cross compilation needs target-built Boost, OpenSSL and MySQL-compatible
headers/libraries; a host MySQL installation is insufficient. The explicit
toolchain must configure the NDK/Bionic target. The driver disables host
`mysql_config` probing in Android mode to avoid importing host link flags. The
reviewed profile adapts the original CMake MySQL-only probe for Connector/C.
Do not fake versions, disable updater
receipts or add permissive duplicate-symbol linker flags to bypass failures.

## Initial measured status

The supplied source has 5,551 files, 52 module directories and tree SHA256
`2528155574583a1a51edcb8e8a5fd7548a0d28ad15f8e93c1a99a520781efe14`.
The original source has Android-compatible architecture detection and routes
Android through UNIX/Clang platform settings; this static observation is not an
Android build. After staging dependencies in an isolated host prefix, the
unmodified source passed host auth-only configure with CMake 3.28.3, Ninja 1.11.1,
GCC 13.3, Boost 1.83 and OpenSSL 3.0.13. The standalone real Connector/C 3.3.17
probe confirms a thread-safe loaded library, exact connector/header version
matching and acceptance of an enforcement option; that acceptance does not
prove fail-closed TLS semantics. The compile-only original
API probe reproduces the missing APIs above. The corrected pinned profile then
passed the complete host authserver compile/link and `authserver --help`
execution. Its host ELF SHA256 is
`d897a986532f6e7e152bf87a6d0ae11b56219993298604a636b3afa4cfdfea1c`.
The actual binary then passed an isolated MariaDB 12.3.2 test: original auth
updater dry run and pool initialization, unchanged update receipts, loopback TCP
listener, graceful SIGTERM shutdown, and rejection of an explicit TLS policy
with zero database authentication connections. The provider-specific server
version branch admits only the qualified 12.3.2-MariaDB release, including its
standard optional protocol prefix; ten compiled cases reject older, future and
unknown engines. Unsupported-version cleanup cancels the async queue before
destroying a connection, matching the existing failed-open cleanup.

## Measured Android auth build

The `android-arm64-v1` profile adds two reviewed changes to the MariaDB profile:
opaque `cpu_set_t` inspection with `CPU_ISSET`, and an explicit signed 64-bit
conversion at the database logger's existing timestamp binding. The former
removes a glibc-only diagnostic cast; the latter resolves libc++'s different
`std::chrono::seconds::rep` typedef. Neither changes gameplay or updater SQL.
The complete pinned Android source tree is
`b621c5f063bc305240ab92b7171f3bfcb8213413df09d86437a249fa88eb2f02`.

```sh
python3 tools/fta_port.py /private/fta-source /private/fta-android-source \
  --profile android-arm64-v1
python3 tools/build_native.py --source /private/fta-android-source \
  --port-profile android-arm64-v1 --platform android --stage auth \
  --build-dir /private/fta-android-auth \
  --toolchain /private/android-ndk-r28c/build/cmake/android.toolchain.cmake \
  --dependency-prefix /private/android-dependencies \
  --mysql-library /private/android-dependencies/lib/mariadb/libmariadb.so \
  --mysql-include /private/android-dependencies/include/mariadb \
  --mysql-executable /private/provider/jniLibs/arm64-v8a/libfta_mariadb_client.so
```

The actual NDK r28c/Clang 19.0.1, ARM64/API-33, Release/C++20 authserver compile
and link passed with static Boost and static libc++. The 30,638,440-byte ELF has
SHA256 `acf186557515897a4c359ac2ceee889f52c1482f36f8b012db007a8625b5fd05`.
It is an AArch64 PIE using `/system/bin/linker64`; every load segment has at least
16 KiB alignment. Its dynamic dependencies are Connector/C, OpenSSL 3, Android
platform zlib, `libm`, `libdl` and `libc`. It has no shared C++ runtime dependency.
`native-port/qualification.json` records this measured scope and exact hashes.

This auth-only build preserves all 52 module directories and static build
settings, but does not compile their worldserver translation units. Android
execution, worldserver, all-module compilation and physical Thor qualification
remain open gates.

## Reproduce the Android dependency prefix

`native-port/android-inputs.json` pins the official NDK, Boost, OpenSSL and
Connector/C source archives and the measured output hashes. Boost and Connector/C
were cross compiled locally. OpenSSL headers were generated for the target from
the exact matching source; its shared libraries were reused unchanged from the
hash-verified provider. This distinction remains recorded in the input manifest.

The dependency driver needs Python 3.11+, a Linux x86_64 host, CMake, Ninja, GNU
make, Perl, GCC and G++. Supply the four pinned archives and a previously verified
base provider stage. It downloads nothing, uses a fresh external output directory
and runs one compiler job. `--plan` validates every archive and provider hash and
prints the exact command plan without creating that directory.

```sh
python3 native-port/build-dependencies.py \
  --ndk-archive /private/inputs/android-ndk-r28c-linux.zip \
  --boost-archive /private/inputs/boost_1_83_0.tar.gz \
  --openssl-archive /private/inputs/openssl-3.6.3.tar.gz \
  --connector-archive /private/inputs/mariadb-connector-c-64e9fe09.tar.gz \
  --provider-stage /private/provider --work-root /private/rebuilt-dependencies \
  --plan
```

Remove `--plan` to build. The resulting `android-deps` directory contains the
target prefix; `receipt.json` records input and output hashes, commands, tool
versions and private log paths. An explicit `--cmake` or `--ninja` path supports
isolated host tools. A supplied `LD_LIBRARY_PATH` is retained only for their host
runtime loader; target include/library discovery uses the explicit NDK/prefix.
Boost's unused optional bzip2, LZMA, Zstandard and ICU adapters are disabled;
platform zlib remains enabled.

The published auth artifact came from the recorded individual build steps. The
combined fresh-prefix driver has validation and mocked composition coverage;
its no-write plan passed against all four real pinned archives and the real
provider. Extraction of all four real archives also passed, including executable
modes, all 35 NDK symlink targets and running the freshly extracted Clang's
version command. A complete heavy rebuild through that new driver has not yet
been rerun. A
fresh build may differ in bytes because build paths and debug metadata differ.

## Unix-socket pool correction

The first Thor auth diagnostic imported all 27 auth tables and ran the ARM64
binary's version command, then failed during the broad auth dry-run phase. Its
exported log did not identify the native cause. Source inspection and an exact
function-body trace found a separate definite defect: the first Unix-socket pool
connection changed the shared host from `.` to `localhost`. The next connection
then attempted to parse the socket path as a numeric port and dereferenced an
empty optional. The earlier host database gate used TCP and did not cover this
path. This defect is consistent with the report; it is not a confirmed diagnosis
of that device attempt.

`android-arm64-socket-v2` retains the complete v1 profile and adds patch 0004.
It normalizes a local connection host while preserving the shared configuration,
uses the same explicit socket in the updater CLI, and rejects empty socket paths
and malformed or out-of-range numeric ports before connecting. Windows named
pipes, SQL, updater receipts and TLS policy remain unchanged. Both earlier
profiles and their exact source inventories remain reproducible.

```sh
python3 tools/fta_port.py /private/fta-source /private/fta-android-socket-source \
  --profile android-arm64-socket-v2
python3 native-port/check-socket-route.py --source /private/fta-android-socket-source
```

The checker compiles the exact reviewed `Open` and updater `ApplyFile` bodies,
with inert database/process endpoints. It passed nine socket opens and reconnects
across shared pool configuration, three valid TCP ports, twelve rejected invalid
ports, an empty-socket rejection and three updater argument routes. This is
parameter regression coverage, not an actual Unix-socket database integration:
the local execution environment refuses AF_UNIX socket creation.

The corrected ARM64 auth build passed compile/link with source tree
`263d23e3df271f2e8463894925f2bbd7c21efde2b6a562e64f17d2acee80e1da`.
Its 30,655,016-byte ELF has SHA256
`bfa532f7c91d59410c10104a2b7e5ce40d5763c59dad36f16c625bf152f3428c`;
the ARM64/API-33, C++20/static settings, 16 KiB segment alignment and dynamic
dependency list match the earlier build. The v2 Thor run now confirms its socket
pool opens and its original updater reports up to date; its final dry run fails
at realm lookup. The v2 profile reproduces this socket-corrected stage; the
resolver profile below is used for the current diagnostic.

## Bionic resolver correction

The socket-corrected Thor run opened the auth database pool and the original
updater reported it up to date, then auth exited because no realm was accepted.
The unchanged seed has an eligible realm and numeric IPv4 address fields.
The original resolver passes Boost's `all_matching` flag, which maps to
`AI_ALL=0x100` on Android. Android 13 Bionic's supported `AI_MASK=0x40f` excludes
that flag, and `getaddrinfo` rejects it before numeric parsing. This is a
deterministic platform incompatibility in the original helper.

`android-arm64-resolver-v3` adds only an Android zero-flags branch to that helper.
Other platforms keep the original flag, and the existing Boost resolver, realm
eligibility query, seed rows, updater, listener configuration and app permissions
remain unchanged. Bionic parses the numeric IPv4/empty-service case locally and
returns before its DNS proxy path; no INTERNET permission or custom numeric
parser is needed for this diagnostic.

Primary implementation evidence is the Android 13
[Bionic flag mask](https://android.googlesource.com/platform/bionic/+/refs/tags/android-13.0.0_r1/libc/include/netdb.h)
and [getaddrinfo implementation](https://android.googlesource.com/platform/bionic/+/refs/tags/android-13.0.0_r1/libc/dns/net/getaddrinfo.c).
The checked source hashes and regression scope are recorded in
`native-port/qualification.json`.

```sh
python3 tools/fta_port.py /private/fta-source /private/fta-android-resolver-source \
  --profile android-arm64-resolver-v3
python3 native-port/check-resolver.py --source /private/fta-android-resolver-source \
  --boost-include /private/android-dependencies/include
python3 native-port/check-socket-route.py --source /private/fta-android-resolver-source \
  --profile android-arm64-resolver-v3
```

The resolver regression compiles the exact production helper and IoContext with
real Boost 1.83. An interposed `getaddrinfo` models Android's allowed flags and
delegates only strict numeric IPv4 work to host libc with name lookup disabled.
Its 16 cases reproduce the old flag rejection, accept the three seed address
fields with Android flags zero, retain numeric service ports, and reject invalid
or nonnumeric inputs. It observed zero interposed network socket calls and
opened no listener. This is host policy emulation, not Android libc execution or
device qualification. Use v3 and its source path with the auth build command for
the current diagnostic; all earlier profiles and artifact hashes are retained.

The v3 ARM64 auth compile/link passed with source tree
`671245955b368e2f6996fc0dd6fd542efb13901c25cb50a3a3a95ece3e101ced`.
Its 30,655,008-byte ELF has SHA256
`7e901a2bbb4efa5c239c44242578bd587aff194d30ed6aed810d5576ceaf9ce4`.
Release/C++20/static settings, 16 KiB load alignment and dependency closure are
unchanged. The socket regression also passed against this exact profile.
The corrected artifact's Thor dry run remains unqualified until measured.

## Next worldserver gate

Worldserver compilation can proceed without client maps or DBC files. Its console
and the default Lua module need target GNU Readline and a terminal library;
NDK does not supply those dependencies, and the provider's libedit is not a
qualified substitute. These dependencies must be pinned and cross built before
the full static module build. Retain the supplied Lua default; the optional
LuaJIT build invokes host make without a qualified cross environment.

The full world build is a separate, lengthy single-job gate. Runtime world
readiness additionally needs matching user-supplied maps, vmaps, mmaps and DBCs;
an auth compile or a world `--help` run cannot establish it.

Build logs are private under the chosen build directory. Publish reviewed
summaries, tool versions and binary hashes only; do not upload unfiltered logs or
configuration files that could later contain credentials. A passing compiler
report still leaves database, game-data, graceful shutdown and Thor qualification
open.
