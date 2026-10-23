# Extracted game-data import and source-derived preflight

Private **0.11.0-game-data**, code **18**, imports an inert ZIP containing extracted
`dbc`, `maps`, `vmaps` and `mmaps` folders, directly or under one enclosing folder.
It preserves the retained realm, verified backup, Database.zip and native inputs.
It does not extract a full client/MPQ archive, launch worldserver or create accounts.
Matching actual game data is not present in the development environment.

## One combined physical test

1. Install the verified private code 18 APK as an in-place update. Its certificate
   and all 16 native payloads match code 17. Never uninstall, clear storage or use
   the differently signed public development/debug APK to replace it.
2. With a ZIP of matching extracted game data, select **Import extracted game-data
   ZIP**. Allow free space for both the ZIP and unpacked files, plus any retained
   previous asset candidate. Wait for **Game-data preflight passed**.
3. Close/reopen the launcher and select **Verify imported game data**. This
   rehashes every file and reruns the preflight. If import/verification fails,
   export the diagnostic JSON immediately; do not retry unrelated database tests.
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

255 Android unit tests and 148 tooling tests pass. New adversarial cases cover
ZIP64, bad CRCs, path/mode/encryption attacks, incompatible headers, source version
markers, unsafe size arithmetic, cancellation, directory-sync failures on both
sides of publication, concurrent stores, changed files and preserved candidates.

`tools/check_game_data_source.py --source-zip ... --kotlin-classpath ... --output ...`
compiles the unchanged original DBC loader with C++20, original assertions and an
abort-on-failure logging sink. It loads all 113 synthetic DBCs, checks seven IDs,
byte-field offsets, invalid magic and original format/header sizes. Five separate
JVMs run production import, abrupt replacement death, retained re-verification,
orphan recovery/new import and final re-verification. No real assets or Android
SAF/foreground operation are qualified by that host test.

See [host evidence](evidence/host-2026-10-23-game-data-preflight.json),
[private APK identity](evidence/android-2026-10-23-private-game-data-apk.json) and
[physical code 17 evidence](evidence/android-2026-10-23-thor-local-service-pass.json).
