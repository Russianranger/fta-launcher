# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current increment — retained native world gate

Started from verified remote **eb8715711fc9a074bcc31acba0c5308b6861b954**;
Foundation CI [40111993680](https://github.com/Russianranger/fta-launcher/actions/runs/40111993680)
passed all three jobs. This increment adds the code **27**, `0.12.0-native-world`
guarded session. Its exact delivery commit, CI and private artifact identity will
be appended after qualification. Public CI APKs contain only the database provider;
they cannot update the persistent-key private diagnostic.

**Physical code 26 import/publication passed.** Report
`FTA-database-diagnostic-1792860233678.json`, SHA256
`73483405b8e70faf14c1e5910eff8c2bcc1852fb8698d1b04d4802830bd424cb`:
22,817 files / 7,625,943,031 bytes; zero problems/rejections; active catalog matches;
104 high-bit mmap files handled; expected DBC routes retained. Exact code-26 APK
`2a276e781698c3a9501f15d25693fd57085e7965151ad13ff5f991e3f037ecba`.
[Redacted physical evidence](evidence/android-2026-10-24-thor-code26-import-pass.json).
The report records **import**, not a separate full reverify. No repeat import.

Code 27 performs the missing full rehash/preflight automatically, holding the asset
lease until all children stop. It requires the exact physically published archive,
catalog, counts, retained realm and verified backup. It reopens the realm (no seed
replay), checks all 24 original metadata expectations and all ten GT ID ranges,
stages unchanged original SQL and 49 module configs in a fresh visit, then starts
owned auth and world processes. The original world ELF, all 52 modules, NPCBots,
C++20, source schemas, source companion v8 and 17 native payloads remain unchanged.
Generated world config uses schema-only grants, a private socket, loopback random
ports, `Updates.AutoSetup=0`, original updater policy and original 60-second freeze
guard. No console, RA, SOAP or cluster listener is enabled.

Qualification requires all 113 original DBC stores, original ready/freeze markers,
receive-only world challenge, SQL/auth/world health, **120,000 ms** screen off,
world halt marker after module shutdown, zero world/auth exits, closed listeners,
SQL SHUTDOWN, strict metadata/GT postchecks and retained identities. Stop order is
world → auth → SQL. Merely compiling or seeing a ready marker never qualifies.
`databaseFallbackValidated` remains false in inert asset receipts; only a completed
attempt-bound native session can establish it. **No native world pass is claimed.**
Client import, WarcraftXL and playable login remain later work.

## Next Thor procedure

After the private code-27 artifact is signed, verified and delivered: install it
**in place**, open **Start native world test**, keep the launcher visible during
rehash/startup. Once world/auth/database health passes, lock the screen for at least
three minutes; reopen, select **Stop local service / test**, then **Export diagnostic
report**. If any stage fails, export immediately before retrying. Do not uninstall,
clear storage, reimport Data.zip, restore over the realm, or repeat passed database,
auth, backup or lifecycle tests.

Expected Data.zip: `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`.
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
