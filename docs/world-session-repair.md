# Additive world-session shutdown repair

Physical code 31 established sustained world/auth/database health and 144,884 ms
of healthy screen-off operation. Its Stop failed with world exit 139. The source
diagnosis and original-code regression are in [script-unload.md](script-unload.md).
The composite runtime gate remains unqualified until clean Stop, database SQL
shutdown and all metadata/GT postchecks pass on Thor.

## Retained identities

The repaired executable is a separate `libfta_world_session.so`, selected only
for the retained world session. The old world executable remains the compiled
updater. All donor native files, 148 runtime manifest entries, provider libraries,
SQL, module configurations, realm identity and backup identity remain unchanged.
No identity migration, seed replay or metadata exception is needed.

`WorldSessionExecutable` requires the exact baseline manifest, original world
metadata and updater lock, then hashes the separate immutable APK executable.
A missing, linked or changed repair fails before database startup. Database,
client, auth and updater invocations keep their original executable selection.
Session receipts record the tested world hash and source profile; an earlier or
different executable's report cannot qualify this version.

`tools/verify_world_session.py` independently checks the complete v13/v14 source
trees, a single changed source file, actual CMake file-API/link/static archives,
all 52 compiled modules, C++20, static Lua 5.2 and the unchanged ELF dependency
closure. All other source files, including SQL and configurations, must match.
The separate source companion includes every original source archive and notice,
current reproduction sources and measured build evidence.

The v2 fault witness recognizes both world executable names. It preserves the
original signal protocol and chaining behavior. The old witness remains in the
APK unchanged; only the separately verified v2 helper is preloaded for a session.

## Next physical gate

Private code **32**, `0.12.5-world-stop`, passed full ARM64 build, exact application
CI, 225 packaged-input checks, persistent v2 signing and 16 KiB alignment.
[Measured APK proof](evidence/android-2026-10-25-private-world-stop-apk.json) and
[current handoff](SHORT-HANDOFF.md) record the artifact and source companion.
A public CI foundation APK does not include the repaired private world.

1. Install the verified private code-32 update in place. Preserve app storage.
2. Select **Start native world test**. Keep the launcher visible during its normal
   full asset rehash and startup.
3. After world/auth/database health checks begin, lock the screen for at least
   three minutes, reopen, and select Stop once.
4. Export the diagnostic report immediately, whether Stop succeeds or fails.

No asset reimport, fresh database/auth diagnostic, seed replay, uninstall or
storage clearing. Code 31's screen-off observation remains valid historical
evidence; the new executable needs its own complete combined runtime gate.
Clean shutdown, postconditions, native GT fallback and composite readiness must
be measured. Client import, WarcraftXL and playable login remain later work.
