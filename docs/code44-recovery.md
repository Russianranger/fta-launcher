# Code 44 — finish retained protected Wine verification

**Report received:** runtime verification failed with access denied. Use
[code 45](code45-recovery.md), which repairs the reproduced empty bind-placeholder
leak before full verification. The instructions below describe the older update.

[FTA-Launcher-0.15.2-guarded-finish.apk](https://chatgpt.com/library?file_id=libfile_fded3867fea481919aa8d98b7ffc3865&account_id=personal)

**44 / 0.15.2-guarded-finish**, **60,541,967 bytes**, SHA256:
`4b077c577f00249413ea933ecda09ca70ccef184dc2d730fd7998d8ed43b9bcb`.
The persistent certificate is unchanged:
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
All **25 native payloads** and **148 runtime entries** match code 43 byte-for-byte.
All **245 packaging checks**, APK v2 signing and 16 KiB alignment passed.
[Packaging proof](evidence/android-2026-10-28-private-guarded-finish-apk.json).
No native payload was recompiled for this private delivery.

## What the report established

The exact code-43 Thor report contains **56 passing Windows checks and six zero
stages**, unchanged protected fixture files, completed ownership cleanup and no
matching process exit. The overall operation still ended `failed / io-failed`;
full final verification was not qualified. There is no proven root cause or
evidence of an attempt-matched low-memory kill.
[Bounded assessment](evidence/android-2026-10-28-thor-code43-incomplete.json).

This update completes that retained attempt through a fresh runtime rehash and
strict saved-file/transcript validation. It runs no Wine or native probe, creates
no new prefix and does not access the import. It preserves the original failed
report and exports `clientGuardedWineFinish` separately. Missing or changed
evidence is refused. New failures include an allowlisted phase/category and
numeric errno when available. [Recovery contract](client-guarded-finish.md).

## Device test

1. Install **over the existing app**; keep all app data.
2. Keep the launcher visible and tap **Finish protected Wine verification** once.
   Allow several minutes; Stop remains available.
3. Export and return the diagnostic JSON after completion, failure or interruption,
   before retrying. If the action is unavailable, export the report.

[FTA-Launcher-0.15.2-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_1f3ab7fbb6e08191b4481fab1a7eaf4f&account_id=personal).
Do not repeat the protected Wine run, runtime preparation, standalone Verify,
client import, storage-isolation or server tests. Replacing the saved attempt
would discard the evidence needed by this recovery. Original-client execution,
rendering and login remain unqualified. Code 44's returned device report failed
with access denied; use the code-45 instructions linked above.

## Measured build checks

Application commit **10896f7f58c71735cad7e6e6d84f379e23c656c3** passed
[foundation CI 40784628222](https://github.com/Russianranger/fta-launcher/actions/runs/40784628222):
**214 tooling**, **106 qualification** with no skips, **559 Android tests passed
/ one existing fixture skip**, zero failures/errors in 53 suites, lint/assembly,
native ownership, cache/JNI and full runtime installation/rehash with a 96 MiB
heap. [Measured CI record](evidence/ci-2026-10-28-code44-foundation-pass.json).
The assembled foundation APK SHA256 was
`52b256a472510b215908fccf6dcc461a946b1d7303347a5038783d6c279f8aee`.

[Guarded Wine CI 40784628266](https://github.com/Russianranger/fta-launcher/actions/runs/40784628266)
passed 56 Windows checks, six stages, full pre/post runtime rehash and unchanged
protected source on native ARM64 Linux. All 50 native guard checks and seven
invalid-root refusals passed. [Measured proof](evidence/host-2026-10-28-guarded-finish-pass.json).
These host results do not establish physical Android completion.

## Corresponding sources

- [FTA-client-bridge-code44-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_900f6ba3148c8191bb95ab227e9aa2fc&account_id=personal):
  **2,614,854 bytes**, SHA256
  `efbdfd07acdaa420f1ced0996e5df281e9b68020ffd42d9aa6b08c974230e8ce`.
  Exact full application snapshot, fixtures, original PRoot/talloc source,
  patches, build recipes, evidence and notices. All 220 tracked Android/native/
  manifest files in the snapshot matched the qualified application commit.
- Unchanged retained-server source: [part one](https://chatgpt.com/library?file_id=libfile_b0f7a3eb7a78819182906014bb1b7e55&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_9eb569eb8cd08191a4d2e3e95195b48b&account_id=personal).
  Concatenate in that order to recover
  `FTA-Launcher-0.15.1-world-corresponding-source.zip`, **540,242,674 bytes**,
  SHA256 `a52c6485dbd019b2c50e079d6822f24a49ac4a73d0fce0ad62bd0f5a9e6615fc`.
  The strict current-source verifier passed; no server-source refresh was needed.
  Part hashes and original source attribution remain in [code 43](code43-recovery.md).

Sources are build/recovery inputs, not installation inputs. Wine/FEX keeps its
original source links and notices. Use the private signed APK above; the public
foundation download is not the retained-world build. Future private updates
require code >=45 and the original signing capsule.
