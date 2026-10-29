# Code 47 — fresh working-session output folders

[FTA-Launcher-0.16.1-view-budget.apk](https://chatgpt.com/library?file_id=libfile_7a9e67631c7481918459b318f9691fa2&account_id=personal)

[FTA-Launcher-0.16.1-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_07f720f4b5b88191b74dd20da166aec0&account_id=personal)

The supplied code-46 report stopped after **130,618 ms** at
`client-preflight / view-copy-limit`. Full runtime verification passed. The
client full hash, working-view publication, Wine and Wow.exe had not started.
There was no memory-pressure pause or matching Android exit. Earlier passed
runtime/storage/import/server results remain intact.
[Measured report assessment](evidence/android-2026-10-28-thor-code46-view-copy-limit.json).

The old planner counted all imported Cache, Logs, Errors and Screenshots files
against the 64 MiB mutable-copy budget. Code 46 did not export per-folder costs,
so the exact contributing folder and bytes cannot be determined from this report.
Code 47 creates independent empty session directories for those four root
folders. All historical files remain untouched in the import; every source
entry is still included in full verification, inventory and metadata audits.
Only the working session omits the historical outputs. Path casing is preserved.

WTF and other configuration files still receive independent copies with the
same **64 MiB cap**. Shared assets stay protected guest links, with no writable
hard links or full client recopy. The 128 MiB working-view growth limit and
runtime reserve remain unchanged. Complete fixed-group byte/count totals are
recorded before a copy-budget rejection, without private filenames or settings.

Regression tests cover oversized sparse historical output files, fresh writable
session directories, preserved original bytes and settings, unchanged source
verification/audit coverage, source mutation, unsafe roots, continued settings
budget rejection, and the exact retained code-43 fixture plan digest.
[Startup contract](client-original-startup.md).

## One device test

1. Install code 47 over the existing launcher, keeping all app data.
2. Keep 5 GiB free internal storage, enable airplane mode with Wi-Fi off, and
   keep the launcher visible and the device powered.
3. Tap **Test original client startup** once. The required full client check
   has not run in code 46's failed attempt; allow roughly an hour plus runtime
   and prefix checks. Stop remains available.
4. Export and return the diagnostic JSON on completion/failure/Stop before
   retrying. If the action is unavailable, export the report instead.

No game window appears in this headless diagnostic. Success qualifies original
startup/host observation and cleanup; rendering and login remain later gates.
Do not repeat Finish, import, standalone Verify, runtime preparation, storage or
server tests. Do not delete caches from the imported baseline.

## Measured build and packaging

Application commit **272fb07dc779e66cb163be42941f9dcaa096fad7** passed
[foundation CI 40810151183](https://github.com/Russianranger/fta-launcher/actions/runs/40810151183):
**214 tooling**, **122 qualification** with no skips, **581 Android passed /
one existing skip** in 57 suites, lint/assembly, native ownership, cache/JNI,
and full runtime installation/rehash under a 96 MiB heap.
[Measured CI record](evidence/ci-2026-10-28-code47-foundation-pass.json).
[Same-commit ARM64 CI 40810151216](https://github.com/Russianranger/fta-launcher/actions/runs/40810151216)
passed the five-stage synthetic startup observer, all 56 guarded Windows checks,
50 native guard checks/seven root refusals, normal/Stop/killed-tracer cleanup,
and complete runtime/source integrity checks. [Host evidence](evidence/host-2026-10-28-code47-guarded-observer-pass.json).

The signed APK is **60,677,313 bytes**, SHA256
`45ecddaad7abe3ae6aafc95da0890ac63744cf2c554fec166ef8c6a481e484f1`.
All **247 packaging checks**, v2 signature and 16 KiB alignment passed. All
**25 native payloads / 148 runtime entries** match code 46 byte-for-byte.
The persistent certificate remains
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
[Packaging proof](evidence/android-2026-10-28-private-view-budget-apk.json).
Assembled APK SHA256 `b7f95a5e2d04f9cd84050b94dabe3e3f0481a8e06641ac824f53e439db50033a`.
No physical code-47 result is claimed by these host/build checks.


## Corresponding sources

- [FTA-client-bridge-code47-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_03ea329a13148191a194f6fc93ae24b5&account_id=personal):
  **2,689,192 bytes**, SHA256
  `3c9a9dab30339c9531351f65ce6d9ebec9efaaa134ffcce82965884b4c4813f8`.
  Exact application snapshot, original PRoot/talloc sources, patches, observer,
  fixtures, recipes and notices. All **236** tracked Android/native/manifest
  files matched the qualified commit.
- Unchanged retained-server companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in that order to recover
  `FTA-Launcher-0.16.0-world-corresponding-source.zip`, **540,243,819 bytes**,
  SHA256 `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Its strict source verifier still passes unchanged. All original native source,
  52 modules, C++20 and v15 build evidence are preserved; no refresh/rebuild was
  needed. Part hashes remain in [code 46](code46-recovery.md).

Sources are build/recovery inputs, not installation inputs. Use the signed
private APK above, not the public foundation download. Future private updates
require code >=48 and the original signing capsule.
