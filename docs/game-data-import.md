# Extracted game-data import and source-derived preflight

**Current status:** physical code 26 passed import/preflight/publication, and
physical code 29 passed the full retained rehash of all 22,817 unchanged files.
Do not repeat import or standalone verification for the next world test. Its
automatic rehash binds a new asset lease. Follow the [current handoff](SHORT-HANDOFF.md);
the version-specific procedures below document earlier checkpoints.

Private **0.11.8-dbc-loading**, code **26**, imports an inert ZIP containing extracted
`dbc`, `maps`, `vmaps` and `mmaps` folders, directly or under one enclosing folder.
It preserves the retained realm, verified backup, Database.zip and native inputs.
It does not extract a full client/MPQ archive, launch worldserver or create accounts.
Matching actual game data is not present in the development environment.

## Source-proven DBC handling in code 26

Physical code 25 passes all **104 high-bit mmap headers** and reports exactly
twelve remaining DBC failures. Its SHA256 and the unchanged Data.zip identity
match. The complete extraction runs without an attempt-matched Android kill.
See [device evidence](evidence/android-2026-10-24-thor-dbc-layouts.json).

| Measured file layout | Original source behavior | Code 26 handling |
| --- | --- | --- |
| CharHairTextures: 8 fields, 32-byte records, zero string block | Filename absent from active server/common/module references; raw loader accepts zero string bytes | Preserve the exact unused file, checking full length and hash |
| PowerDisplay: 6 fields, 15-byte records | `nixxxx` consumes only the first eight bytes and ignores the trailing fields | Accept this additional packed width for this root filename only |
| Ten GT files: 1 field, 4-byte records | `df` file loading fails, then `LoadDBC` invokes the named `LoadFromDB` route | Preserve exact bytes and declare the original database dependency |

The ten GT routes use exact measured row counts: 100 for BarberShopCostBase and
NPCManaCostScaler; 11 for both CritBase files; 3,200 for CombatRatings; 1,100 for
each remaining reported GT file. A separately restored, hash-verified Thor backup
has `ID, Data` columns and every zero-based ID for all **8,922 rows**, with unchanged
three-schema metadata before/after read-only queries and a clean SQL shutdown.
Unmodified original DBC storage/database loaders consume those exact captured
query results, and empty results still fail. The host adapter replaces only the
database transport for this probe; no native server payload is changed.

Original DBC expectations remain in the unchanged policy manifest. These narrow
routes do not admit missing files, other root names/locales, unexpected GT counts,
other PowerDisplay widths, malformed lengths, corrupt ZIP payloads or changed
later hashes. No asset gets an inserted ID, widened record, new string byte or
SQL-generated replacement. All supplied bytes and provenance remain intact.

`dbcFileCompatibility` reports every accepted special case, its SHA256 and row
count. GT entries also identify the fixed source SQL table. The report explicitly
sets `databaseFallbackRequired=true` and **`databaseFallbackValidated=false`**.
Full reverify checks hashes and reconstructs these observations; it does not
access the retained database. Native loading must still qualify the real database
fallback and complete terrain/navigation semantics before world readiness.
Format-1/2 reports remain readable without inferred compatibility observations.

Update in place to private code 26, import the unchanged Data.zip once, reopen,
**Verify imported game data**, then **Export diagnostic report**. Export immediately
on failure. Keep all dependencies and retained saves. Passed updater/auth milestones
need no repeat. See [original loader proof](evidence/host-2026-10-24-dbc-loaders.json),
[store/recovery proof](evidence/host-2026-10-24-dbc-store.json), and
[APK identity](evidence/android-2026-10-24-private-dbc-loading-apk.json).

## Completed physical extraction and code 25 header diagnosis

Physical code 24 extracted **22,817 files / 7,625,943,031 bytes** from the unchanged
Data.zip and reached preflight. No process kill matches this attempt. Validation
rejected 116 assets; only 32 reasons were exported. The first twelve are DBCs
(CharHairTextures, ten GT tables and PowerDisplay), followed by twenty mmap files.
The report does not contain their numeric headers or the remaining 84 reasons.
See [physical evidence](evidence/android-2026-10-23-thor-asset-preflight-rejection.json).

Pinned `src/tools/mmaps_generator/MapBuilder.cpp` computes `maxPolys = 1 << 31`.
The original `DT_POLYREF64` build uses 64-bit references and accepts the resulting
`0x80000000` capacity word in its 28-byte parameter header. Code 25 corrects the
launcher's signed-positive-only check for this exact representation. Other
negative values, zero, invalid origins/dimensions/tile count and wrong lengths
still fail. The original Detour generation-expression/reload probe runs with
C++20 and UBSan, and its generated file passes production import and later full
rehash without rewriting bytes. This does not yet establish Thor's exact header
values or qualify navigation tile semantics.

Every new preflight report contains `problemCountsByReason` for all failures,
not just the first 32. Failed-file details include its already-measured SHA256,
`observedHeader` and (where required by source) `sourceExpected`. DBC header
fields are `fileBytes`, `magic`, `rows`, `fields`, `recordBytes`, `stringBytes`.
Mmap values are the seven raw unsigned little-endian 32-bit words, with names
ending in `Bits`; float bit patterns stay integers even for invalid NaNs. The
separate `mmapHighBitCapacityFiles` counts observed `maxPolysBits=2147483648`,
including files that fail another condition. Truncated headers contain only
complete words. There are no record/string dumps. The stored report is bounded
to 64 KiB, retains at most 32 asset details and validates numeric ranges/hash
syntax/count consistency before exporting anything. Old format-1 reports are
readable with `headerDiagnosticsAvailable=false`.

All **DBC policy bytes and required names remain unchanged**. Once the actual
report arrives, compare measured `fields`/`recordBytes` to `sourceExpected` and
check `20 + rows * recordBytes + stringBytes == fileBytes`. Keep file hashes as
evidence. A mismatch is a diagnosis, not permission to rewrite the file or policy.
Original `LoadDBC` calls `LoadFromDB` for named SQL tables, even when file loading
fails; relying on that requires independent original-loader/database-coverage
qualification, not guessed schemas. An unused empty DBC likewise needs its actual
header and source-use evidence before a narrowly justified policy change.

The completed code 25 procedure was to update in place and import Data.zip once
with the launcher visible. **Export diagnostic report** immediately afterward.
If import succeeds, reopen and **Verify imported game data** before exporting.
Preserve Database.zip, native dependencies, realm and backup. Do not repeat the
passed database/auth milestones. No world-startup claim follows from preflight.
See [host proof](evidence/host-2026-10-23-asset-headers.json) and
[private APK proof](evidence/android-2026-10-23-private-asset-headers-apk.json).

## Repeated physical low-memory exit and code 24 cache handling

Private code 23 was killed during copy at **2,258,370,560 bytes**. Android's
attempt-matched reason is `low_memory`, 241 ms after a checkpoint showing
2.9 MB Java heap, 10.9 GB available system memory, importance 100 and an active
visible-screen binding. No asset rejection or completed archive hash exists.
The root cause remains unproven; a small Java heap and a free-memory sample do
not measure file-cache/cgroup charges or the exact memory state at termination.
See [physical evidence](evidence/android-2026-10-23-thor-import-cache-kill.json).

Code 24 requests `POSIX_FADV_DONTNEED` after 16 MiB source ranges are consumed,
after copied/extracted output is synced, every 128 indexed entries and periodically for the private extraction ZIP, after
each preflight file, and during later full-file hashing. The provider's existing stream is
preserved; only a duplicate seekable descriptor is borrowed for source advice.
Pipe or non-file providers remain supported through the existing stream path.
Cache advice is optional to integrity: rejection cannot skip checks, change bytes,
replace missing runtime assets, publish early or assert world readiness.

The dedicated `libfta_game_io.so` adds one native file; all 16 prior payloads,
original SQL, source-derived policy and signer remain unchanged. The helper uses
only read-only, NOFOLLOW regular-file handles and positive bounded ranges. It
never changes file position or closes a borrowed descriptor. No kernel settings,
root privileges, global cache clearing or process-killer changes are used.

`gameDataOperation.fileCacheAdvice` records requests, accepted/rejected counts,
cumulative requested bytes, source-descriptor availability and the 16 MiB
interval. `actualEvictionMeasured=false` is intentional: syscall success is not
proof of eviction on Thor. Repeated ZIP ranges make the byte counter larger than
the archive; it is not a cache-size reading. Old checkpoints remain readable;
malformed/incomplete counters reject the whole checkpoint export.

[Android's file-cache guidance](https://developer.android.com/topic/performance/memory/guide/page-advice)
distinguishes clean-page eviction from write synchronization; its
[lmkd description](https://source.android.com/docs/core/perf/lmkd) includes memory
pressure and thrashing, beyond a simple free-memory threshold. This supports the
mitigation's mechanism but does not establish the Thor-specific cause.

319 Android tests, original C++ loading, five source/store JVMs, a 3.23 GB import
and full reverify with 64 MiB heap, boundary deaths, and the exact latest copy-byte
interruption pass with advice enabled. Native host measurements show 64 MiB of
clean cache becoming nonresident, with bytes/position unchanged. See the current
[handoff](SHORT-HANDOFF.md) for exact evidence and private APK identity. Android
cache eviction and general survival remain unqualified. The later code 24 run
above completes extraction and exposes actual header rejections; world loading
remains unqualified.

## Physical editor-archive rejection and code 23 correction

The verified private code 22 report fully copies the unchanged 2,869,892,425-byte
archive and walks all 22,822 central-directory entries. The worker records
activity binding, importance 100 and no low-memory flag; no exit matches this
attempt. The prior copy interruption did not recur in this run. Index acceptance
fails on exactly one path: **`dbc/WDBX - DBC EDITOR.zip`**. Extraction and asset
format preflight have not yet run. See
[device evidence](evidence/android-2026-10-23-thor-editor-companion-rejection.json).

The pinned original DBC loader names `.dbc` files explicitly. Code 23 retains
only the exact reported editor path as an opaque companion, without executing
it or opening its contents as another ZIP. Outer ZIP size/CRC, path/mode checks,
non-executable permissions, catalog SHA256 and full later rehash all apply.
Other ZIP filenames/locations remain rejected. The editor cannot replace any
required DBC, map, vmap or mmap file. `editorCompanionFiles` (0..1) is a subset
of the `dbc` folder count, never an additional runtime requirement.

A regression fails on unchanged code 22 and passes after this correction.
Adversarial synthetic nested names are preserved inside the unopened ZIP; no
nested files are extracted. Additional tests cover missing runtime assets,
unrelated ZIP rejection, CRC failure, hash mutation, old report compatibility
and invalid attempts to promote the editor into the source policy. The unchanged
original C++ loader and five production-store JVMs pass with all companion kinds.
See [host checks](evidence/host-2026-10-23-editor-companion.json). Original SQL,
strict database expectations and source-derived format policy remain unchanged.

## Physical low-memory exit and code 22 mitigation

Private code 21 records an **attempt-matched Android low-memory kill** 302 ms
after the last copy checkpoint at 2,281,177,088 bytes. The checkpoint has about
4 MiB Java heap used and 233 GiB storage free. It does not record a Java OOM,
asset rejection, completed ZIP hash or published candidate. The source of
system memory pressure remains unknown. Do not correlate the older unmatched
exit entries to earlier attempts merely by timestamp proximity.
See [device evidence](evidence/android-2026-10-23-thor-import-low-memory.json).

Code 22 binds the visible activity to the already-started import/verify worker
with [`BIND_IMPORTANT` and `BIND_ADJUST_WITH_ACTIVITY`](https://developer.android.com/reference/android/content/Context#BIND_IMPORTANT).
This communicates the active screen's importance to Android. The binding is
released when hidden, terminal, disconnected or unavailable; it never uses
`BIND_AUTO_CREATE`, starts a worker or restarts an interrupted import. The
existing foreground service and Stop lifecycle remain. No other diagnostic or
local database/auth session receives this binding.

Archive and extracted-file output synchronize every 16 MiB and at completion.
The 256 KiB streaming buffer and exact bytes/hashes remain unchanged. This
bounds this writer's pending unsynchronized output, not the entire kernel cache;
file-cache pressure is a mitigation hypothesis, not the measured cause of death.
Sync errors/cancellation fail the attempt and preserve the previous candidate.
The source archive, SQL, asset rules and strict metadata expectations are unchanged.

`systemMemoryObservation` adds available/total memory, Android's low-memory
threshold/flag, the worker's importance and observed activity binding to each
operation checkpoint. Failed sampling is explicitly unavailable, never filled
with zeros or reused from a previous sample. See Android's
[MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo)
and [getMyMemoryState](https://developer.android.com/reference/android/app/ActivityManager#getMyMemoryState(android.app.ActivityManager.RunningAppProcessInfo)).
These are last-checkpoint observations, not guarantees of protection or exact
measurements at termination. Existing bounded exit history remains independent.

The host runs a 3,231,655,994-byte, 40,146-file synthetic archive under a 64 MiB
heap through production import and full reverify, beyond the reported Thor copy
point. It also checks six pre-publication hard exits, post-publication exit,
retained candidates and orphan cleanup. Six additional JVMs hard-exit at the
reported 2,281,177,088-byte copy point and check the retained candidate, exact
checkpoint and recovery through the next explicit import. See
[copy-point evidence](evidence/host-2026-10-23-import-memory-copy-exit.json). Unit regressions cover the exact 16 MiB
writeback bound across more than 3 GiB, byte slices, failed sync, cancellation,
visible/hidden/terminal binding lifetimes and memory record validation.
See [host checks](evidence/host-2026-10-23-import-memory.json). The later code 22 report observes Android binding and a complete SAF copy.
Actual asset extraction, preflight and native loading still require the focused run.

## Earlier code 20 attempt and code 21 checkpoints

The earlier report matches private code 20 but ends at `GAME_DATA_COPY`, showing
1,677 MiB copied. It contains no terminal failure or completed archive hash.
Because code 20 omitted index progress, copying is only the last recorded phase;
it cannot establish the actual point or cause of interruption. The report does
not prove another unsupported file or DBC/schema mismatch.
See [physical evidence](evidence/android-2026-10-23-unfinished-import.json).

Code 21 reports copy fsync, ZIP entry indexing and publication separately. A
small atomic operation record survives process death and remains readable while
the asset-store lease is held. `gameDataOperation` includes:

- Attempt-bound stage, timestamps, copied bytes and completed archive hash.
- Entry progress during indexing and byte progress during copy/extraction/verify.
- Observed free storage and Java heap usage/limit, without assuming a failure cause.
- `RUNNING`, `INTERRUPTED` or `UNKNOWN` for an unfinished operation, according to
  whether its owning worker is observed active, inactive or cannot be checked.
- Fixed failure categories and at most four sanitized application class/method/
  line references. Raw exception messages, paths and stack traces are omitted.
- Up to three recent exits of this app's runtime process. `attemptMatched=true`
  requires the recorded owner PID (kept private), exact attempt token and time
  window. Unmatched historical exits do not establish this attempt's cause.

Android's [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)
and [process state summary API](https://developer.android.com/reference/android/app/ActivityManager#setProcessStateSummary(byte[]))
provide the bounded exit observations on API 30+. History may be unavailable or
empty; memory figures are the OS's last sample, not exact memory at death. No
trace streams, descriptions, process IDs or user data are exported. These fields
are observational and never grant readiness. A completed operation record also
does not replace the strict asset receipt, hashes or final qualification checks.

A production host regression reproduces the missing index phase on code 20.
Both versions import and fully reverify a 1,888,997,926-byte synthetic ZIP with
40,141 files under a 64 MiB Java heap, so size alone has not reproduced the Thor
failure. Code 21's 26-process harness hard-exits during copy, fsync, index,
extraction, preflight, publication and immediately after publication. It checks
checkpoint survival, prior/new candidate retention and subsequent full verify.
Android SAF and actual game bytes still require the device run. Code 21's later report physically exercises the attempt-matched exit-history path.
See [host evidence](evidence/host-2026-10-23-import-checkpoints.json).

## Import correction from the physical diagnostic

The new JSON proves that verified private code 19 rejected
`dbc/lightskybox.db2` at `archive_index / unsupported_asset_path`, before any
extraction. The reported archive SHA256 is
`407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`.
The actual archive bytes remain unavailable here. A synthetic regression using
that exact path fails on unchanged code 19 and passes with code 20.
See [physical failure evidence](evidence/android-2026-10-23-db2-import-failure.json).

The exact pinned source explicitly loads 113 `.dbc` filenames and recognized
locale variants. Inspection of its server/common/module C++ sources finds no DB2 loader
or `.db2` runtime reference. Code 20 therefore preserves direct `.db2` files in
`dbc`, recognized locale folders and `dbc/stock_dbc` as opaque companions. They
are non-executable, size/CRC checked and included in full SHA256 verification.
Their format is neither guessed nor qualified; their presence never satisfies
a missing DBC or bypasses a required file's layout/version sentinel.

The code 19 stock-backup fix remains: direct `.dbc` backups under `stock_dbc`
retain generic WDBC bounds checks and cannot replace root DBCs. All source policy
bytes and database expectations stay unchanged. Arbitrary nested folders,
SQL/executable filenames, unsafe paths, duplicate names and invalid ZIP modes
still fail before extraction. No files are silently discarded or rewritten.

Retry the **unchanged original Data.zip** with code 24. On failure export the
**diagnostic `.json` file** immediately. `failureStage`, `failureReason` and
`failureAsset` identify the boundary; layout failures now include
`rejectedAssetCount` and up to 32 `rejectedAssets`, collected across the validated
index. Format preflight similarly reports a total and up to 32 `problems`.
Unsafe names and raw exceptions are never exported. `db2CompanionFiles` counts
opaque DB2s, and `stockDbcFiles` counts only auxiliary `.dbc` backups. These are
subsets of the overall `count.dbc` folder count, not extra runtime requirements.
Old reports and receipts remain readable with new counts defaulting to zero.

## One focused physical test

1. Install private code 24 as an in-place update. Its signer and all 16 existing native
   payloads match code 23; one small cache helper is added. Keep the retained realm, Database.zip and dependencies.
   Never uninstall, clear storage or substitute the public debug APK.
2. Keep the launcher visible and select **Import extracted game-data ZIP** with
   the same unchanged `Data.zip`. Wait for **Game-data preflight passed**.
3. Close/reopen the launcher, select **Verify imported game data**, and keep it
   visible until completion. Then **Export diagnostic report** and send the JSON.
   If either operation fails or stops, reopen if necessary and export immediately
   before another attempt or diagnostic.

Expected asset evidence is `gameDataDiagnostic.state=VERIFIED`,
`preflightPassed=true`, `activeCatalogMatchesReport=true` and a matching
`gameDataOperation` completion. Memory/priority observations are diagnostic;
no particular sample grants asset or world readiness. Preserve this game-data
scope for export. The remaining 120-second screen-off service measurement can
join a later native gate; do not repeat database/updater/backup/restart tests.
The importer never silently edits or drops game files. Keep actual assets private.

## What the preflight proves

`tools/create_game_data_policy.py` checks the exact original source archive hash,
then derives `manifests/game-data-policy.properties` from 13 source files. The
manifest retains every input hash and source archive identity. C++ comments are
excluded: two commented-out loads are not active requirements. The actual count
is **113 DBCs**, independently compiled against the original loader.

| Input | Required check |
| --- | --- |
| Required DBCs | WDBC magic, exact source field count and record byte width, bounded record/string lengths |
| Version markers | Original seven IDs in AreaTable, CharTitles, GemProperties, Item, ItemExtendedCost, Map and Spell |
| Startup terrain | Eight original `ExistMapAndVMap` coordinates mapped with float32 grid arithmetic |
| Map files | `MAPS`, version 9, bounded header sections; original loader does not require a fixed buildMagic |
| Vmaps | `VMAP_4.8`; startup trees and required tiled files with original Y-then-X filenames |
| Mmaps | At least one parameter file and tile, 28-byte finite parameters, version 20/Detour 7 headers and bounded payload size |

This preflight does **not** prove complete map/navigation coverage, DBC string
references, terrain bodies, collision trees, cross-file model references or native
semantic correctness. Synthetic minimal bodies intentionally demonstrate that
passing headers alone are insufficient. `worldReady`, `gameLoginQualified` and
`nativeSemanticValidationPassed` remain false. The smallest next step after real
asset evidence is to investigate any exact mismatch, or prepare one guarded
native world-start qualification using the retained realm and verified backup.
Original SQL, compiled updater policy, strict metadata and provenance stay intact.

## Storage, interruption and reporting

Before extraction the importer checks ZIP/ZIP64 directory bounds, entry/local
names, duplicate case paths, modes, encryption flags, size caps and overlaps.
Only inert asset filenames are accepted; traversal, symlinks, special files,
executables and SQL are rejected. The caps are 100,000 entries, 512 MiB per file
and 32 GiB total. Android's foreground service has Stop and a one-hour deadline.

The app spools and hashes the ZIP, extracts under a fresh private generation,
checks CRC/length and SHA256 for each file, preflights it, fsyncs receipts and
directories, then atomically publishes the candidate pointer. An OS lease prevents
concurrent mutations. Failed/interrupted candidates cannot replace the previous
pointer; a failure after publication never deletes the new active generation.
The latest and immediately previous asset generations are retained. Stale partial
imports are removed only under the lease at the next import. No database is opened.

Verification compares the exact stored inventory and every hash, rejects symlinks
or unexpected files, and repeats format checks. Stored reports are historical
evidence of that completed check, not a background guarantee of current integrity.
The JSON includes the policy/archive/catalog fingerprints, counts and at most 32
asset-relative format failures, with a total failure count. It omits game bytes,
source URI, credentials, database rows and raw native logs. Other diagnostic
scopes also include this bounded report, allowing the combined service export.

## Developer checks

290 Android unit tests and 148 tooling tests pass. New adversarial cases cover
ZIP64, bad CRCs, path/mode/encryption attacks, incompatible headers, source version
markers, unsafe size arithmetic, cancellation, directory-sync failures on both
sides of publication, concurrent stores, changed files and preserved candidates.

`tools/check_game_data_source.py --source-zip ... --kotlin-classpath ... --with-stock-backup --with-db2-companions --output ...`
compiles the unchanged original DBC loader with C++20, original assertions and an
abort-on-failure logging sink. It loads all 113 synthetic DBCs, checks seven IDs,
byte-field offsets, invalid magic and original format/header sizes. Five separate
JVMs run production import, abrupt replacement death, retained re-verification,
orphan recovery/new import and final re-verification with stock DBC backups and
opaque DB2 companions. It confirms that their exact bytes survive and root DBCs
remain intact. No real assets or Android
SAF/foreground operation are qualified by that host test.

See [host source evidence](evidence/host-2026-10-23-import-checkpoint-source.json),
[private APK identity](evidence/android-2026-10-23-private-import-checkpoint-apk.json) and
[physical code 17 evidence](evidence/android-2026-10-23-thor-local-service-pass.json).

## Physical code 26 and the retained world gate

The code-26 report passes import, preflight and publication of all 22,817 files
with zero problems. It records no standalone reverify. Physical codes 27 and 29
passed the full retained rehash under the lease held through their native sessions. No
repeat import is needed. See [current handoff](SHORT-HANDOFF.md) and
[physical evidence](evidence/android-2026-10-24-thor-code26-import-pass.json).
Native fallback, world runtime and gameplay remain unqualified until their gates.
