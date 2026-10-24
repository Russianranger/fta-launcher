# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current increment — recover the physical world-startup failure

Starting remote HEAD: **925fd632459f623862cf62a4b9b4d75d005b6cf4**;
its exact CI [40117691753](https://github.com/Russianranger/fta-launcher/actions/runs/40117691753)
passed all three jobs. Private code 27 was delivered with SHA256
`08eef256f0006176e8937e607113a169a6da083d9461ea1a3aa145b2e939a8b8`.

**Physical code 27 passed the full retained asset rehash but world startup failed.**
Report `FTA-database-diagnostic-1792865377817.json`, SHA256
`0b4a376c3185e133192bb7d73a77097443fbc66b00675ff0fc33f2c4a995800f`.
[Redacted evidence](evidence/android-2026-10-24-thor-code27-world-exit.json).
The exact 22,817 files / 7,625,943,031 bytes reverified, zero problems; retained
archive/catalog still match. All 24 metadata prechecks and ten GT coverage queries
passed. The actual world reached all 113 DBC stores, its ready message and the
60-second freeze guard, then exited **139 after 27,590 ms**. No world challenge,
healthy runtime sample or screen-off interval passed. The native auth process
exited zero and the temporary role was removed. Metadata/GT postchecks and a
qualified clean database shutdown were not recorded. A false postcheck means
unmeasured, not proof of damaged data. Do not replay a seed or restore the realm.

`databaseFallbackValidated=false`, world readiness and game login remain false.
The original ready message precedes module OnStartup hooks; the failure's exact
native instruction and cause are unknown. Do not attribute it to a particular
module or alter game data based on this report alone.

**Code 28 (`0.12.1-world-evidence`) is being qualified for read-only recovery.**
It preserves the original world failure phase across cleanup and reports successful
auth cleanup separately from failed world cleanup. World reports now include the
existing attempt-bound asset verification/process-exit evidence. Export additionally
reads only the same finished attempt's retained world/stdout/stderr logs, with
8 MiB file, 4 KiB line and 64-event limits. It exports source-message identifiers,
line/level counts and file hashes, never native log text or dynamic values.
Stale attempts, running journals, multiple world records, links, oversized or
changing files fail closed. Missing evidence never establishes readiness.

The source dictionary fingerprints only the first 16 literal ASCII bytes of 160
original messages in startup modules and selected core/database files. The original
archive identity and all selected source hashes are verified. Shared prefixes are
explicit candidate sets; a match is neither hook completion nor a proven cause.
[Source index](evidence/world-log-dictionary.json).
No native payload, module, source rule, configuration or SQL was changed.
The original source companion v8 and persistent signer remain required.

Local qualification: **165 tooling tests** and exact private-source dictionary
reproduction passed. Android tests/lint/assembly and private packaging are pending
exact-commit CI. Do not deliver an unqualified public CI APK as the private update.

## Next Thor procedure

After code 28's verified private APK is delivered, install it **in place**, reopen
and select **Export diagnostic report** immediately. **Do not start another world
test first**: the update can inspect code 27's existing failed attempt. Send the
new JSON. No reimport, rehash, database/auth test, uninstall or storage clearing.
After the failure is diagnosed, the next runtime gate still requires controlled
world startup/Stop and at least 120,000 ms healthy screen-off observation.

Expected archive: `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`.
Expected catalog: `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Persistent certificate: `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Recover `FTA-Launcher-Development-Signing.zip`; never regenerate or publish it.

## Independent evidence and preserved milestones

Host original DBC loaders/ResultSet/Field, live Connector/C 3.4.9 and isolated exact
MariaDB 12.3.2 restored Thor backup loaded all **8,922** GT floats bit-for-bit; ten
empty-table negatives fail; ASan passes; strict metadata, account/character counts,
GT data and backup stay unchanged. Connection-pool adapter and host transport are
not Android execution. [Evidence](evidence/host-2026-10-24-live-dbc.json).
Original world challenge framing and new lifecycle source assertions pass;
fragmented receive succeeds, truncated/slow streams fail, client sends zero bytes.
[Source evidence](evidence/host-2026-10-24-world-session-source.json).

Physical database/updater, persistent realm, backup/restore, process-death recovery,
local auth and user Stop/restart gates already passed. Code 17 screen-off evidence
was only **112,127 ms**, so 120,000 ms is deferred into this world gate unchanged.
Deferred gameplay bug: Attriboost Settings uses field 8 vs 9; leave it for gameplay.

Use one native compiler job by default, at most two after memory checks. No
concurrent heavy native builds. Keep logs/private binaries/assets/SQL/credentials
outside Git. Run Python tests, Android tests/lint/assembly, package/signature/
16 KiB checks and exact-commit CI. Keep private delivery evidence truthful.
