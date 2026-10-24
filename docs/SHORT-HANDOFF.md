# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current increment — original world crypto provider

Starting remote HEAD **56c11b20f877ae9503f58ad3e0cf5a97aec6a0cf**; exact CI
[40121053801](https://github.com/Russianranger/fta-launcher/actions/runs/40121053801)
passed all three jobs. Code 28 (`0.12.1-world-evidence`) APK SHA256
`304f07ae9f72118a4208fe0bbb489f3310a866b1af1c02733705c8c8cb29beaf`.

**Physical code 28 recovered the code-27 failure successfully, without another
native launch.** Report `FTA-database-diagnostic-1792868442649.json`, SHA256
`d624410009929d75109c633e552e7ac6246a91805784f816c1a55fa246a731c0`.
Same attempt `473bfcdf-b4ca-4bcc-b894-b7b69a0215c3`, world exit 139 after 27,590 ms.
The full 22,817-file / 7,625,943,031-byte retained rehash passed. All 24 metadata
prechecks and ten GT coverage queries passed; original world reached 113 DBC stores,
ready and freeze guard. No challenge, healthy sample, screen-off interval or clean
world/database postcondition passed. False postchecks mean unmeasured, not corruption.
[Recovery evidence](evidence/android-2026-10-24-thor-code28-recovery.json).

**Exact cause identified:** stderr is 326 bytes / 9 lines, SHA256
`c874def29443f077496d51e01c69be82a8a677f7fa7528880dad71ee4ed8f997`.
Unchanged original ARC4/provider setup/assertion/fmt code reproduces that exact
hash and SIGSEGV with a missing legacy provider. It passes the known RC4 vector
with the provider. Assertion: `src/common/Cryptography/ARC4.cpp:27`, constructor
`ARC4`, `result == 1`. The original world socket constructs two ARC4 instances
before its initial challenge. The last Auctionator log is not the cause.
[Diagnosis, source proofs and build instructions](world-crypto-provider.md).

Code **29**, `0.12.2-world-crypto`, is implemented; exact-commit Android CI and
private APK packaging are pending. The new 162,336-byte ARM64 legacy provider is
built from unchanged pinned OpenSSL 3.6.3 with NDK r28c/API33, one job, 16 KiB
alignment, and the exact retained libcrypto. Two fresh builds produced SHA256
`432951dda01dada3ca6cb2bbe522c35b43a17b37a8f796c0257ba3fd8a9a1cec`.
Existing source companion v8 supplies that exact Apache-2.0 source and notices;
`tools/build_openssl_legacy.py` supplies the additional reproducible build recipe.
No worldserver, libcrypto, source module, SQL, configuration or game asset changed.

Runtime verifies the separate module and retained crypto identity before opening
the realm. It creates an install-scoped `legacy.so` symlink to the immutable APK
library, then scopes `OPENSSL_MODULES` and `OPENSSL_CONF=/dev/null` to the actual
world session. The original provider load and cipher selection remain unchanged.
All 17 donor native payloads and all 148 runtime-manifest entries stay byte identical;
realm/backup provider identities remain valid. Expected package: 18 native files,
222 provider/notice checks. Only the original persistent signing key is allowed.

Qualification so far: **171 tooling tests**, exact original host assertion/vector
reproduction and two matching Android module builds. Seven new Android regression
tests cover install updates, changed/missing/linked/wrong-architecture inputs,
malicious pre-existing links, exact dependency pinning and process-only environment.
Android tests/lint/assembly will be qualified by exact implementation CI.
`databaseFallbackValidated=false`, world readiness and game login remain false.

## Next physical gate

After the private code-29 APK is qualified and saved, install it **in place** over
code 28. Keep all existing realm/database/backup/assets. Select **Start native
world test** once and keep the launcher visible until healthy. Turn the screen
off for **three minutes**, reopen, select **Stop**, then **Export diagnostic
report**. On failure export immediately before another attempt. No manual import,
manual rehash or already-passed database/auth test is needed. The action's automatic
full rehash is required to bind its new asset lease. Never uninstall or clear storage.

Expected archive: `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`.
Expected catalog: `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Persistent certificate: `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Recover `FTA-Launcher-Development-Signing.zip`; never regenerate or publish it.

## Preserved milestones and limits

Host original DBC loaders/ResultSet/Field, Connector/C 3.4.9 and isolated exact
MariaDB 12.3.2 restored Thor backup loaded all **8,922** GT floats bit-for-bit; ten
empty-table negatives and ASan passed. Strict metadata/data/backup stayed unchanged.
[Live evidence](evidence/host-2026-10-24-live-dbc.json). Host adapters are not Android
execution. The original 44-byte challenge probe sends zero application bytes.

Physical database/updater, persistent realm, backup/restore, process-death recovery,
local auth and Stop/restart gates already passed. The earlier 112,127 ms screen-off
interval remains short of 120,000 ms; qualify it during the native world gate.
All 52 source modules, C++20, NPCBots, owned processes and graceful Stop remain.
Client import, WarcraftXL and playable login are later milestones. Deferred
Attriboost Settings field 8 vs 9 bug remains gameplay work.

Native builds: one job by default, at most two after memory checks; no concurrent
heavy builds. Keep logs/private binaries/assets/SQL/credentials outside Git. Run
Python, Android tests/lint/assembly, package/signature/16 KiB checks and exact CI.
