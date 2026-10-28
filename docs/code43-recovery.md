# Code 43 — protected Windows file access

[FTA-Launcher-0.15.1-guarded-wine.apk](https://chatgpt.com/library?file_id=libfile_d7b7efd9f6888191a8dc32da488a56a4&account_id=personal)

**43 / 0.15.1-guarded-wine**, **60,525,583 bytes**, SHA256:
`45965d3cc61321e8f293f7a2cde4cf542e50803b38d2fb1c7655b2d24965a3d3`.
Same persistent certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
All **25 native payloads** and **148 runtime entries** match code 42 byte-for-byte.
All **245 packaging checks**, v2 signing and 16 KiB alignment passed.
[Packaging proof](evidence/android-2026-10-27-private-guarded-wine-apk.json).
The retained world, all 52 source modules, realm, client import and Wine runtime
are preserved. No native payload was recompiled for this private update.

Application commit **cdb80d916028151e0211e88a3f942442ebedef0a** passed
[foundation CI 40623960896](https://github.com/Russianranger/fta-launcher/actions/runs/40623960896):
214 tooling tests, 105 qualification tests with no skips, **551 Android tests
passed / one existing fixture skip**, lint/assembly, process ownership, cache/JNI
and runtime installation/full rehash with a 96 MiB heap.
[Guarded Wine CI 40623960909](https://github.com/Russianranger/fta-launcher/actions/runs/40623960909)
passed **56 Windows checks** and all six stages on native ARM64 Linux, full
runtime pre/post rehash, independent configuration writes and an unchanged
protected source tree. All 50 native guard checks and seven root refusals passed.
[Measured CI proof](evidence/host-2026-10-27-guarded-wine-pass.json).
These results do not establish physical Android execution.

## Next device test

1. Install the APK **over the existing app**. Keep all app data; do not uninstall.
2. With **3 GiB free**, tap **Test protected Wine access** and keep the launcher
   visible. Allow roughly 10–20 minutes; Stop client runtime operation is available.
3. After success, failure or interruption, **export and return the diagnostic JSON**.
   Return the report before retrying a failure.

Expected: **Protected Wine access passed: 56 checks; protected files unchanged.**
[Detailed test instructions](https://chatgpt.com/library?file_id=libfile_af216c9c58388191aef80f7729ce0c85&account_id=personal).
Do not repeat passed import, retained-client verification, standalone Wine/runtime,
standalone storage-isolation or server tests. The new gate automatically performs
its own Wine runtime rehash and creates a fresh disposable prefix. It does not
open the imported game. Its separate `clientGuardedWine` receipt preserves the
previous runtime and storage receipts.

The new builder shares protected files through the guard and copies mutable
settings independently, with a 64 MiB copy limit and atomic publication.
It polls view and prefix growth at 128 MiB and 2 GiB; these are thresholds, not
hard quotas. It resolves Android app-directory aliases before strict root checks.
Wine's DeleteFile reports success while the guard prevents deletion, so the test
records the return/error and separately verifies actual file survival and the
entire protected source inventory. No guard restriction was relaxed.
[Implementation and limits](client-guarded-wine.md).

Original-client working-view qualification, WarcraftXL startup, rendering and
login remain future gates. A successful synthetic test does not qualify them.

## Corresponding sources

- [FTA-client-bridge-code43-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_bc57a63d5a1c81919371623789ef5efb&account_id=personal):
  **2,592,429 bytes**, SHA256
  `3f48560730d0c3d0ef49bab9b126eb4b2bfe759f8780ee521c1d2588da8a590d`.
  Includes the exact full application snapshot, working-view/Windows fixtures,
  original PRoot/talloc archives, guard and ownership patches, generated recipes,
  measured build evidence and complete notices.
- Retained-server source: [part one](https://chatgpt.com/library?file_id=libfile_b0f7a3eb7a78819182906014bb1b7e55&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_9eb569eb8cd08191a4d2e3e95195b48b&account_id=personal).
  Concatenate in that order to recover
  `FTA-Launcher-0.15.1-world-corresponding-source.zip`, **540,242,674 bytes**,
  SHA256 `a52c6485dbd019b2c50e079d6822f24a49ac4a73d0fce0ad62bd0f5a9e6615fc`.
  Part one: 419,430,400 bytes, SHA256
  `5ec550f4b1eb5727b89166bcfbf71574bcf3208d6413e1aa9f815ed622d3da47`.
  Part two: 120,812,274 bytes, SHA256
  `652d435c6ccf74547c4205e91b78b4cec62e37e56ac7eb7d57d475c20fbb393b`.
  Packaging helpers are refreshed; original native archives, notices, source and
  build evidence remain unchanged. Strict corresponding-source checks passed.

Sources are recovery/build inputs, not installation inputs. Wine/FEX keeps its
separate original source links and notices. Use the private signed APK above;
the public foundation APK is not the retained-world build. Future private updates
require code >=44 and the original signing capsule.
