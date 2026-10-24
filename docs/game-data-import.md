# Extracted game-data import and source-derived preflight

Private **0.11.3-import-checkpoints**, code **21**, imports an inert ZIP containing extracted
`dbc`, `maps`, `vmaps` and `mmaps` folders, directly or under one enclosing folder.
It preserves the retained realm, verified backup, Database.zip and native inputs.
It does not extract a full client/MPQ archive, launch worldserver or create accounts.
Matching actual game data is not present in the development environment.

## Unfinished code 20 attempt and code 21 checkpoints

The latest report matches private code 20 but ends at `GAME_DATA_COPY`, showing
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
Android SAF, OS exit history and actual game bytes still require the device run.
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

Retry the **unchanged original Data.zip** with code 21. On failure export the
**diagnostic `.json` file** immediately. `failureStage`, `failureReason` and
`failureAsset` identify the boundary; layout failures now include
`rejectedAssetCount` and up to 32 `rejectedAssets`, collected across the validated
index. Format preflight similarly reports a total and up to 32 `problems`.
Unsafe names and raw exceptions are never exported. `db2CompanionFiles` counts
opaque DB2s, and `stockDbcFiles` counts only auxiliary `.dbc` backups. These are
subsets of the overall `count.dbc` folder count, not extra runtime requirements.
Old reports and receipts remain readable with new counts defaulting to zero.

## One combined physical test

1. Install the verified private code 21 APK as an in-place update. Its certificate
   and all 16 native payloads match code 20. Never uninstall, clear storage or use
   the differently signed public development/debug APK to replace it.
2. With a ZIP of matching extracted game data, select **Import extracted game-data
   ZIP**. Allow free space for both the ZIP and unpacked files, plus any retained
   previous asset candidate. Wait for **Game-data preflight passed**.
3. Close/reopen the launcher and select **Verify imported game data**. This
   rehashes every file and reruns the preflight. If import/verification fails,
   reopen if necessary and export the diagnostic JSON immediately before another attempt; do not retry unrelated database tests.
4. After success, select **Start local database + auth**, wait for running status,
   lock the screen for **three minutes**, then wake it. The live measured counter
   must reach **120 seconds**; if short, lock again within the same session.
   Use **Stop** and wait for clean completion. Export one diagnostic JSON.

The combined export should contain `gameDataDiagnostic.state=VERIFIED`,
`preflightPassed=true`, `activeCatalogMatchesReport=true`, and current service
qualification with `screenOffLocalServiceQualified=true`. This completes the
7,873 ms shortfall from code 17 without repeating its passed restart milestone.
If no extracted-data ZIP exists yet, identify whether the available input is an
original extracted server bundle or a full matching client; do not substitute
unverified generic assets. Keep game bytes private and out of repository issues.

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
