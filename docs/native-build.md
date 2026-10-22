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
python3 tools/build_native.py --source /private/fta-android-world-source \
  --port-profile android-arm64-world-v11 \
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

Android builds set the original CMake `CONF_DIR` option to relative `configs`.
The supervised runtime's working directory supplies `configs/modules/` with the
original module configuration files; the compiler no longer embeds a private
build-machine installation directory for module configuration lookup. Host
builds retain their original default configuration directory.

A completed world report additionally records CMake file API evidence. It
requires the actual world link to include the static modules, scripts and Lua
archives, C++20 compile groups, an exact compiled list of all 52 modules and 49
original configuration names, and positive compiled source counts for each
module. Archive paths, sizes and hashes and the resolved Android ncurses library
are recorded separately. Missing artifacts or a different module/configuration
list block the report. These checks support build provenance, not execution or
gameplay qualification.

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
The exact version 5 private APK subsequently passed the original auth updater
dry run on AYN Thor/Android 13: native exit 0, completion marker observed and
MariaDB SQL shutdown with exit 0. The three read-only realm eligibility checks
also passed. See `docs/evidence/android-2026-10-22-thor-resolver-pass.json`. This
qualifies that disposable auth-updater path, not an auth listener, full seed,
worldserver or gameplay.

## Measured world dependency and compile gate

Readline 8.3 with official patches 001–003 now cross builds for ARM64/API33.
The final 418,840-byte shared library has SHA256
`321f805999b0c1c5dc2b759b423eedd8cc235ea3916729beebfb717329d4ba15`,
SONAME `libreadline.so.8`, four 16 KiB-aligned load segments and no embedded
RPATH/RUNPATH. Its dependencies are the unchanged pinned provider ncurses and
Android support libraries plus Bionic libc/libdl. The actual `--no-undefined`
link and independent symbol/ELF review passed. Nothing replaced the provider's
ncurses ELF or substituted libedit.

`native-port/world-inputs.json` pins the Readline and ncurses sources and all
three official patches. `native-port/build-world-dependencies.py` generates
matching target ncurses headers and builds only the new shared Readline. It
requires explicit local inputs, a fresh output directory, NDK r28c and one
compiler job. `--plan` validates identities without writing. The combined driver
has eight tests and has now completed its entire fresh-output build with pinned
inputs. It regenerated matching ncurses headers, applied the three Readline
patches, and reproduced the exact Readline ELF hash above while preserving every
provider library byte. The broader auth dependency driver still does not claim
a complete fresh rebuild; this restoration reused the verified NDK/provider and
configured matching target headers.

```sh
python3 native-port/build-world-dependencies.py \
  --ndk-root /private/android-ndk-r28c \
  --provider-stage /private/provider \
  --readline-archive /private/readline-8.3.tar.gz \
  --ncurses-archive /private/ncurses-5f58399b.tar.gz \
  --readline-patch-001 /private/readline83-001 \
  --readline-patch-002 /private/readline83-002 \
  --readline-patch-003 /private/readline83-003 \
  --work-root /private/new-world-dependencies --plan
```

The resulting prefix contains Readline headers/library plus symlinks to the
verified provider dependencies. Add those target inputs to the existing Boost,
OpenSSL and Connector/C prefix before the world build; retain their hashes.
The library intentionally retains the original default inputrc behavior. A later
private runtime must explicitly supply its input configuration and terminal-data
environment; no fixed Termux installation path is introduced. Readline is
GPL-3.0-or-later: any combined world distribution must retain its notices and
corresponding source, and elect compatible terms for GPL-2.0-or-later core code.
No world binary is distributed at this checkpoint.

The actual full world configuration now passes with all 52 module directories,
C++20, Release, static scripts/modules and default static Lua 5.2.4. Its pinned
Lua archive is fetched by the original module CMake, not substituted by this
driver. The world build's auth prerequisite compiled and linked successfully.
Actual world compilation then exposed four bounded build defects:

1. The Lua module removed `-Werror` as a substring, corrupting NDK's
   `-Werror=format-security` into an invalid compiler argument. Patch 0006 removes
   only the standalone token and preserves targeted security flags. The
   `android-arm64-world-v4` profile records that exact source stage. The exact
   production CMake regression passed 18 C/C++ flag cases, reproduced the old
   corruption, and cross compiled a literal-format probe while correctly
   rejecting nonliteral `printf` under retained format-security enforcement.
   No probe binary was executed.
2. The first module translation unit included `Object.h`, whose inline grid
   helper uses `LOG_DEBUG` without declaring that macro. With precompiled headers
   disabled, Clang rejects it. Patch 0007 directly includes `Log.h`, retaining the
   original helper behavior. Profile `android-arm64-world-v5` adds only this
   header dependency and preserves every earlier profile. The actual Attriboost
   translation unit then compiled, followed by all 32 Lua C sources and the
   static Lua library link.
3. gSOAP forced its Android `strerror_r` call through the XSI `int` branch even
   when Bionic exposes its GNU `char*` interface. Patch 0008 selects the existing
   GNU return behavior only for Android, `__USE_GNU` and API 23 or later. The
   exact production function passed 16 expected compile outcomes across C11 and
   C++20, GNU/XSI modes and APIs 21/33; the original API33 GNU failures were
   reproduced. The final full gSOAP source then compiled and its static library
   linked successfully. No probe or server binary was executed.
4. `botspell.h` declared a `std::size_t` constant without including `<cstddef>`.
   Patch 0009 adds that standard header directly; no constant or gameplay type
   changes. The actual `botspell.cpp` translation unit then compiled for
   ARM64/API33 with C++20, producing a 908,968-byte object with SHA256
   `404fbd9e240077bbde09a0fc58b4f735d9ff061cfd0e7c9f2de942ca8e0f29c8`.
   The complete source profile was reverified after compilation.

The earlier `android-arm64-world-v7` source tree is
`5095554b60889f9e5a5e55951139576cfca06efd3cc9ad1f6a5c841af25d5265`.
Every world profile reproduced exactly from the pristine source. These patches
and successful configuration are progress toward compilation, not a completed
world build or module qualification. The earlier single-job build was intentionally paused after these actual compile
checks with more than 1,200 world build edges remaining. Those transient objects
were subsequently pruned; the active build restaged verified source and target
dependencies. No game assets are required for compilation. The build driver now
explicitly pins Lua 5.2 static and rejects caches that swap it for LuaJIT, another
Lua version or dynamic linkage.

The `android-arm64-world-v8` linker profile tree is
`b92be92d888912deb5c66e588e4ed95444745fd117ffd2de98a5b0e6cec8ad6f`.
Patch 0010 fixes a confirmed Android linker dependency: the original world
command adds raw `-lncurses`, which the NDK cannot resolve. A target probe
reproduced that failure, while calling both Readline and ncurses functions and
linking the pinned libraries by full path passed with `--no-undefined`. The
Android CMake branch now resolves target `ncursesw` explicitly; other platforms
retain their original linker flags. The complete v8 profile reproduced from the
pristine source and its auth prerequisite linked. Full world compilation is
running, with all NPC bot translation units past the compiler. This remains an
incomplete world build until the complete target links and its ELF is inspected.

The `android-arm64-world-v9` packet portability tree is
`a1ba6d040406bd252628f39e63e4027a2c722fcbf68c2425f98c632e6e90272d`.
Actual `Unit.cpp` compilation then found an ambiguous packet insertion:
Android's `std::chrono::milliseconds::rep` is `long long`, while this target's
`int64` overload is `long`. Patch 0011 casts that single timestamp explicitly to
`int64`, preserving the original supported platforms' signed 64-bit value and
eight serialized bytes. The packet reservation and other fields are unchanged.
`check-packet-timestamp.py` uses the production ByteBuffer header, append body
and exact timestamp statement. Eight boundary values, including values above
32 bits and both signed extremes, produce identical original and fixed host
bytes. The original Android ambiguity is reproduced and the fixed expression
cross compiles with signed 64-bit type assertions. No Android probe is executed.
The full v9 source profile reproduced from pristine source before the complete
world build resumed.

The `android-arm64-world-v10` tree is
`640867ba0e45ff4150f889053f2b7a987c8f815cd51846b713ea1ab1ad96303b`.
Actual Custom script compilation next found that `dark_portal_siege.cpp` uses
the `CreatureScript` base and `RegisterCreatureAI` macro without their declaring
header. Patch 0012 directly includes `CreatureScript.h`; no script body changes.
The complete source profile reproduced from pristine source, and the full
ARM64/API33 translation unit then compiled successfully. The complete world
target continues; this header fix alone does not establish a linked worldserver.

The current `android-arm64-world-v11` tree is
`882a152345a18d6d4ce480457cbe3aacbb4c20d3e2a119c5ce59c9f0812b3679`.
A bounded two-job Ninja diagnostic pass on unchanged v10 reached 285 of 827
remaining build edges before its configured 16 failed-edge limit; it returned
1 and the full source inventory still matched v10. The compiler errors identify
missing direct owning headers in 16 script translation units. An actual target
object also confirms lockpick's undefined private-template `Visit` dependency.
Patch 0013 adds 22 owning-header includes across those 17 files, preserving
original line endings and every script body. `CellImpl.h` supplies the actual
`Cell::VisitObjects` template bodies for four diagnosed grid-search callers.
The complete profile reproduces from pristine source. The normal world build
must recompile these units and finish linking before compilation is qualified.

Runtime world readiness additionally needs matching user-supplied maps, vmaps,
mmaps and DBCs. See `docs/world-updater-gate.md` for the original startup ordering
and the future compiled database-updater gate. An auth compile or a world
`--help` run cannot establish world readiness.

Build logs are private under the chosen build directory. Publish reviewed
summaries, tool versions and binary hashes only; do not upload unfiltered logs or
configuration files that could later contain credentials. A passing compiler
report still leaves database, game-data, graceful shutdown and Thor qualification
open.
