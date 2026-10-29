# Code 52 — faster file verification

[FTA-Launcher-0.17.2-fast-verify.apk](https://chatgpt.com/library?file_id=libfile_84d7a29f4f608191b85188ed46c92bb3&account_id=personal) ·
[FTA-Launcher-0.17.2-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_9b5f0dcc30ac8191a3837b01b94d8ce9&account_id=personal)

The shared client/runtime I/O ceiling is now **240 MiB/s**, five times the previous
48 MiB/s. Fractional timing carries across buffers, and time spent reading and
hashing counts toward the deadline. This removes the old per-buffer rounding
and extra sleep after useful work. Full hashes, existing leases, 256 KiB buffers,
1 MiB cache/write/readback windows, Stop and memory-pressure thresholds remain.
No extra worker or native/runtime change was introduced.
[Scheduler design](client-original-startup.md#verification-pacing-from-code-52).

## Returned report

Code 51 completed the attempt in **39m15s**, with **28m20s of requested pacing**
and zero pressure pauses. Prefix setup and complete verification of 10,476 client
files / 60,243,212,332 bytes passed; independent settings and owned cleanup passed.
The trace captured a candidate write access violation in **Wow.exe**, RVA
**0x5c7898**, targeting **0xdfe000**. The original process exited **0xC0000005**
after **4296 ms**, with no trace drops or overflow. This identifies a candidate
location, not proven fatal causality or a specific dependency fault.
[Assessment](evidence/android-2026-10-29-thor-code51-fault-and-pacing.json).

Code 52 addresses verification speed. The original-client crash remains
unresolved; startup, rendering and login remain unqualified. Fivefold configured
I/O rate is not a measured fivefold total-attempt speedup: storage, hashing and
scheduler performance still determine elapsed time. The returned device result is recorded below.

## Returned physical code-52 result

The returned [report assessment](evidence/android-2026-10-29-thor-code52-speed-and-repeat-fault.json)
records **7m26s**, down from code 51's **39m15s**: a **5.28x** elapsed ratio across
these two attempts, with the same accounted I/O/client/settings volumes and no
pressure pauses. Requested pacing fell from 1,699,713 to 137,522 ms. Keep the
faster governor. This is not a controlled storage benchmark.

Prefix setup and full client verification passed. The original process repeated
the identical fault candidate and exited after 4424 ms. No new APK or repeated
device test is requested yet. The next required input is the original client-root
**Wow.exe** (7,702,016 bytes), matching SHA256
`c4ea8503112878fa47d06580de9d936b184f9bb6b452f6931dfe793fe2f0a47e`,
for read-only analysis of the observed instruction and target image section.
Existing DLL/host uploads do not contain that executable. Startup/rendering/login
remain unqualified; do not infer a specific defect or patch from an address alone.

## Original combined test (completed; do not repeat yet)

1. Install code 52 over the existing launcher, preserving all app data/imports.
2. Keep **5 GiB free internally**, airplane mode with Wi-Fi off, power connected,
   and the launcher visible.
3. Tap **Test original client startup** once. Setup, verification and the bounded
   startup attempt follow automatically; Stop remains available. Note elapsed time.
4. Export diagnostic JSON after completion/failure/Stop and return it before a
   retry. If the action is disabled, export instead.

The report should show `ioLimitBytesPerSecond=251658240`; compare `elapsedMs`,
`ioBytes`, `throttledMs` and `pressureWaitMs` with code 51. A shorter failed startup
still measures verification performance. No visible game window is expected.
Do not uninstall, clear data, reimport, delete settings/cache, repeat Finish,
tap separate Verify or rerun passed standalone storage/runtime/server gates.

## Qualified package

Application **10912350bdc639c2204aab4063606aaeb8612037** passed
[foundation 40938186319](https://github.com/Russianranger/fta-launcher/actions/runs/40938186319),
attempt 2. The first attempt failed while downloading the NDK, before application
compilation; the same-commit retry passed. **600 Android tests passed / one
existing skip**, **214 tooling**, **135 qualification with no skips**, lint,
assembly, native ownership, cache/JNI and full runtime installation/rehash with a
**96 MiB heap** passed. [CI evidence](evidence/ci-2026-10-29-code52-foundation-pass.json).
Fake-clock regressions require 240 MiB mixed I/O in exactly 1000 ms, tiny-request
fractional pacing, elapsed-work credit, bounded idle behavior and oversleep handling.
The unchanged observer/guard retains [code-51 ARM64 evidence](evidence/host-2026-10-29-code51-client-fault-pass.json);
no new original-client or physical execution is claimed.

APK **60,689,601 bytes**, SHA256
`0b6498ce144c2fa8ba2308d9fe2af5523c01fa2b918ecc1678efc94981b72851`.
All **247 package checks**, persistent **v2 signature** and **16 KiB alignment**
passed. All **25 native payloads / 148 runtime entries** match code 51 byte-for-byte.
No native payload was rebuilt for private delivery. The original signer is
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
[Package evidence](evidence/android-2026-10-29-private-fast-verify-apk.json).

## Corresponding sources

- [FTA-client-bridge-code52-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_630c9231e27c81918118979bd61f0682&account_id=personal):
  **2,778,406 bytes**, SHA256
  `6e86855877aedfa85383e5c6a191f05353c61254b93a5dad1d53f59a8e4f4891`.
  All **240** tracked Android/native/manifest files match the tested commit;
  original bridge sources, patches, fixtures, observer, recipes and notices remain.
- Unchanged server companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in order to `FTA-Launcher-0.16.0-world-corresponding-source.zip`,
  **540,243,819 bytes**, SHA256
  `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Strict source verification passed; all 52 modules and C++20 remain intact.
  Part hashes: [code 46](code46-recovery.md).

Sources are development/recovery inputs, not phone installation inputs.
Future updates require code **>=53** and the original signer.
