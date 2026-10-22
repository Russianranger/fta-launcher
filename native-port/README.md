<!-- SPDX-License-Identifier: GPL-2.0-or-later -->

# Reviewed native portability profiles

Port changes are maintained as a small patch series against the immutable
2026-10-20 source input. `profiles.json` pins every patch byte, each changed
file's preimage/result hash, and the complete resulting source inventory. The
original archive and verified source are never edited. The build driver accepts
only the pristine distribution or an explicitly selected pinned profile.

`tools/fta_port.py ORIGINAL NEW_COPY --profile NAME` checks the pristine source,
copies it into a separate temporary directory, checks patch paths and hashes,
applies without three-way/fuzzy merging, verifies the complete result, then
publishes the new source directory. A failure never replaces an existing source.

`probes/original-api.cpp` is a compile-only reproduction of the original core's
MySQL API choices. `probes/mariadb-api.cpp` measures actual MariaDB header/loaded
connector versions and TLS enforcement option support without connecting to a
database. Neither probe establishes schema or server compatibility.

Server patches retain the licenses and notices of the original source they
modify. No game logic, module content, SQL receipts or updates may be dropped to
make a build pass. Compiler, database and physical-device qualification are
tracked separately.

`android-arm64-socket-v2` extends the preserved Android v1 profile with immutable
shared Unix-socket routing. Each pool connection normalizes its local connect
host, while the configured host remains unchanged; the updater CLI selects the
same explicit socket. Invalid numeric ports fail before connection. Run
`check-socket-route.py --source REVIEWED_SOURCE` to compile and exercise the exact
reviewed connection/updater bodies with inert C API/process endpoints. This
checks parameters and reconnects without connecting to a database or running SQL.

`android-arm64-resolver-v3` adds Android-supported resolver flags. Bionic rejects
the original `AI_ALL` flag before parsing even numeric IPv4 addresses. Only the
Android branch uses zero flags; other platforms retain the original behavior.
`check-resolver.py --source REVIEWED_SOURCE --boost-include BOOST_INCLUDE` tests
the exact helper with real Boost 1.83 and an interposed Bionic flag policy. This
is a host regression, separate from device execution.

`android-arm64-world-v8` preserves the earlier world compiler fixes and adds
explicit Android linkage to the pinned target `ncursesw` library. The original
raw `-lncurses` flag cannot resolve against the NDK; the non-Android linker flags
are retained. All 52 modules, C++20, static scripts and default static Lua 5.2 are
preserved. The profile records source portability, while completed compilation
and device execution still require their separate evidence.

`android-arm64-world-v9` resolves Android's chrono/ByteBuffer overload ambiguity
with an explicit `int64` packet timestamp conversion. It preserves the original
eight serialized bytes, including values above 32 bits. The exact production
ByteBuffer regression compares host payloads and checks the original and fixed
NDK compile outcomes without executing Android code.

`android-arm64-world-v10` adds the missing direct `CreatureScript.h` dependency
for the original Dark Portal Siege custom script with precompiled headers off.
The full target translation unit compiles after this include-only change.

`android-arm64-world-v11` adds direct owning headers to 17 original script
files after actual NOPCH compiler errors and an undefined private-template
warning. All script bodies and original line endings are preserved. A bounded
v10 diagnostic pass returned nonzero; only a subsequent completed normal world
build can qualify compilation.

`LICENSE` preserves the original core's GPL version 2 license text; its source
headers allow version 2 or later. The patch series and this explanation are
GPL-2.0-or-later. The independently written probes and Python tools retain their
MIT license. The upstream CMake files' copying exception remains intact in the
staged source.
