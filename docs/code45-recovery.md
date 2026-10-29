# Code 45 — recover empty bind placeholders and finish verification

[FTA-Launcher-0.15.3-bind-cleanup.apk](https://chatgpt.com/library?file_id=libfile_fe621fe5d0dc81918a32abdb805d209d&account_id=personal)

**45 / 0.15.3-bind-cleanup**, **60,546,063 bytes**, SHA256:
`f1b84656765cc4ba2c59e5e786e5bfc1f41e9e733f8a87cc52113ff10c5355b8`.
The persistent certificate is unchanged:
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
All **25 native payloads** and **148 runtime entries** match code 44 byte-for-byte.
All **245 packaging checks**, APK v2 signing and 16 KiB alignment passed.
[Packaging proof](evidence/android-2026-10-28-private-bind-cleanup-apk.json).
No native payload was recompiled for this private delivery.

## What failed and what changed

The exact code-44 report failed at `runtime-pre-verify / access-denied` after
122,548 ms. It retained the original code-43 attempt's 56 zero Windows checks
and six zero stages, with no Wine rerun or matching process exit. Reading all
file bytes did not establish successful directory verification.
[Bounded assessment](evidence/android-2026-10-28-thor-code44-access-denied.json).

The pinned PRoot creates permission-zero placeholders for absent bind targets.
The deliberate forced tracer-death ownership test bypasses their exit cleanup.
Native ARM64 CI now reproduces the two leftovers, `immutable` and `view`, using
the real guarded bindings; the previous Wine-only host test omitted that sequence.
The fixed normal, Stop, killed-tracer and Wine sequence leaves no extra runtime
entries. This reproduces a mechanism matching the report; the exact physical
directory identities await recovery. [Measured host proof](evidence/host-2026-10-28-bind-placeholder-recovery-pass.json).

Code 45 sets `PROOT_DONT_POLLUTE_ROOTFS=1` for future probes. For the retained
attempt, Finish first checks saved fixture files and transcript under the runtime
lease. It removes only those two known names if absent from the catalog and
verified as app-owned, permission-zero directories with link count two.
Directory-only removal atomically refuses files, symlinks and nonempty directories;
there is no chmod or recursive deletion. Full runtime verification then runs
under the same lease, preserving every existing integrity check.

The original failed code-43 report remains unchanged. A new
`clientGuardedWineFinish` report records the cleanup count/checks and final
integrity result. Missing or changed retained evidence is refused. This action
starts no Wine/native probe and creates no new prefix. [Recovery contract](client-guarded-finish.md).

## One device test

1. Install **over the existing app** and keep app data.
2. Keep the launcher visible and tap **Finish protected Wine verification** once.
   Allow several minutes; Stop remains available.
3. Export and return the diagnostic JSON after completion, failure or interruption,
   before retrying. If the action is unavailable, export the report instead.

[FTA-Launcher-0.15.3-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_50db41fc3ae08191b2573d69cd62f18b&account_id=personal).
Preserve the saved attempt; no protected Wine rerun, runtime preparation, separate
Verify, client import, storage-isolation or server test is needed. Code 45's Thor
recovery remains pending. Original WarcraftXL execution, rendering and login
remain unqualified.

## Measured build checks

Application commit **d09350364a38ceaed4d992ee67b9b3352994579f** passed
[foundation CI 40792082906](https://github.com/Russianranger/fta-launcher/actions/runs/40792082906),
attempt 2: **214 tooling**, **106 qualification** with no skips, **566 Android
tests passed / one existing fixture skip**, zero failures/errors in 54 suites,
lint/assembly, native ownership, cache/JNI and full runtime installation/rehash
with a 96 MiB heap. [Measured CI record](evidence/ci-2026-10-28-code45-foundation-pass.json).
The assembled foundation APK SHA256 was
`2ab2ba40df419b5cd6318dac9c3f010488dd9db30523101cf13ee36498ed6c9d`.

[Guarded Wine CI 40791282750](https://github.com/Russianranger/fta-launcher/actions/runs/40791282750)
passed on **55ac0fb75271106b5ef559254abd904f6a035a79**: reproduced legacy
mode-zero placeholders, corrected ownership lifecycles, 56 Windows checks,
six stages, full runtime pre/post rehash and unchanged protected source, plus
50 native guard checks and seven invalid-root refusals.
[Mixed runtime CI 40791282689](https://github.com/Russianranger/fta-launcher/actions/runs/40791282689)
passed all ten stages on the same commit. The subsequent application commit
corrects the Android directory-only deletion API and adds its regression; the
qualified native probe environment and host harness are unchanged. These host
results do not establish physical Android completion.

## Corresponding sources

- [FTA-client-bridge-code45-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_22d200a98c408191b890cc014506efeb&account_id=personal):
  **2,630,129 bytes**, SHA256
  `844bb4890e979a4a88a80d7e3e4c305c35bbc31ab026a78c117ba4d638c1c057`.
  Full application snapshot, fixtures, original PRoot/talloc source, patches,
  build recipes, evidence and notices. All 222 tracked Android/native/manifest
  files matched the qualified application commit.
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
require code >=46 and the original signing capsule.
