# Code 51 — bounded original-client fault diagnostics

[FTA-Launcher-0.17.1-client-fault.apk](https://chatgpt.com/library?file_id=libfile_3fc049e815ec81919b558945eb1cc23c&account_id=personal)

[FTA-Launcher-0.17.1-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_e4537a755040819186f5570414bbd648&account_id=personal)

This is a **diagnostic update**, not a demonstrated fix for the new client crash.

## What code 50 established

The exact returned code-50 report passed all four prefix-preparation stages,
including owned cleanup. Prefix setup completed in **100,368 ms**, with 790
system32 and 831 syswow64 entries. It recorded 1,754 accepted prefix-cache
requests and two errors; actual eviction was not measured.

Full verification of **10,476 files / 60,243,212,332 bytes** and the independent
**112,062,202-byte** settings copy passed. The observer created the original
client process, which exited early with **0xC0000005** before any WarcraftXL host
observation. This status denotes an [access violation](https://learn.microsoft.com/en-us/shows/inside/c0000005);
it does not establish which code or dependency caused it, or how far game/DLL
initialization ran. Total attempt time was **2,288,780 ms (38m09s)**.

There was no recorded pressure stop or attempt-matched Android exit. Wine Stop,
owned cleanup and source metadata consistency passed. Runtime posthash did not
run after failure. The report contains no faulting instruction/module or access
parameters. [Bounded assessment](evidence/android-2026-10-29-thor-code50-client-access-violation.json).
Original startup, rendering and login remain unqualified.

## What code 51 records

The original-startup command enables bounded Wine exception/module tracing.
Private process-ID markers from the existing observer correlate the stream to
its owned client. Only fixed module roles and numeric exception/access addresses,
flags, image base/size/relative offset, counts, exit status and elapsed time are
exported. PIDs, raw logs, private paths, registers, stack contents and settings
are excluded. No debugger attaches and no original binary is modified.

PE header reads, line length, process/module state and retained output are
bounded. Unknown paths, missing headers, unloaded/replaced images and ambiguous
ranges cannot supply a valid named range. Malformed or inconsistent fields are
rejected as a whole. Dropped lines/overflow are explicit and prevent a location
candidate from being marked available. Streaming terminal event capture survives
the raw-output cap. Memory thresholds, prefix-first setup/cache requests, full
verification, protected source, independent settings and ownership remain intact.

The report labels the **last observed exception** as a candidate. An exception
could have been handled before a later exit; matching codes alone do not prove
fatal causality. `fatalExceptionLocationProven` remains false. These observations
never promote startup/rendering/login success. [Design and primary references](client-original-startup.md#bounded-fault-observations-from-code-51).

## One combined device test

1. Install code 51 over the existing launcher, keeping all data.
2. Keep **5 GiB free internally**, airplane mode with Wi-Fi off, the launcher
   visible, and the device connected to power.
3. Tap **Test original client startup** once. Setup runs first; the full client
   check and bounded original-client attempt follow automatically. Allow
   **40–60 minutes** for a complete attempt. Stop remains available.
4. Export the diagnostic JSON after completion/failure/Stop and return it before
   retrying. If the action is disabled, export instead.

A repeated access violation can supply the missing fault location; do not run
repeated attempts. Each new original-client session still requires fresh source
verification under its lease. No separate Verify is requested. No game window
is expected, and this test does not start the realm or log in. Do not uninstall
first, clear data, reimport files, delete settings/cache, repeat Finish or rerun
passed standalone import/runtime/storage/server gates. Preserve the code-50
report and earlier successful receipts.

## Exact-commit qualification

Application **cb13b1753837609989eaf5ef9838f3054b1a1c7b** passed
[foundation 40927895734](https://github.com/Russianranger/fta-launcher/actions/runs/40927895734):
**214 tooling**, **135 qualification** (no skips), **597 Android tests passed /
one existing skip**, lint/assembly, native ownership, cache/JNI and full runtime
installation/rehash with a **96 MiB heap**. [CI evidence](evidence/ci-2026-10-29-code51-foundation-pass.json).

Same-commit [ARM64 run 40927895789](https://github.com/Russianranger/fta-launcher/actions/runs/40927895789)
passed normal synthetic startup and a third invocation that deliberately writes
to a PAGE_NOACCESS page in its PE32 fixture. The fault was correlated to the
synthetic Wow.exe image: **module 1, RVA 0x7f18, write access, exit 0xC0000005,
767 ms**, with no dropped trace lines or state overflow. The fixture stayed
**failed** with Wine/native cleanup and runtime/source integrity checks passing.
Two access-violation trace records were observed; no fatal-causality claim follows.
All 56 protected Windows checks, 50 native guard checks, seven root refusals and
normal/Stop/tracer-death ownership checks also passed.
[ARM64 evidence](evidence/host-2026-10-29-code51-client-fault-pass.json).
No original private client was executed in CI.

## Signed private package

APK **60,689,601 bytes**, SHA256
`501ed646a4006f69090ee7bbaebd51b896beab829c269a77c09b822adf16c572`.
All **247 package checks**, persistent **v2 signing** and **16 KiB alignment**
passed. All **25 native payloads / 148 runtime entries** match code 50 exactly.
Qualified assembly SHA256:
`08a3732f469d1495646d58c78ffcae4c4b1347bc58dc1191f364630cdd6d7193`.
Signer certificate:
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
[Packaging proof](evidence/android-2026-10-29-private-client-fault-apk.json).
No native payload was rebuilt for this private delivery. Use the signed private
APK above; the foundation APK is not the complete retained runtime update.

## Corresponding sources

- [FTA-client-bridge-code51-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_30b8926df9b48191a56c04e0199a82a7&account_id=personal):
  **2,762,032 bytes**, SHA256
  `c67f6c03022b21f0cf96d02fa3274b9c4c659a571e57a18e99220c1b45d631b5`.
  Exact application snapshot, original bridge sources, patches, fixtures,
  observer, recipes and notices. All **240** tracked Android/native/manifest
  files matched the qualified application commit.
- Unchanged server source companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in order to recover `FTA-Launcher-0.16.0-world-corresponding-source.zip`,
  **540,243,819 bytes**, SHA256
  `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Its strict source verifier passed unchanged. Original sources, all 52 modules,
  C++20 and v15 build evidence remain intact. Part hashes: [code 46](code46-recovery.md).

Sources are development/recovery inputs, not phone installation inputs.
Future updates require code **>=52** and the original signer.
