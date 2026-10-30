# Code 54 — post-host startup exit diagnostics

[FTA-Launcher-0.17.4-startup-exits.apk](https://chatgpt.com/library?file_id=libfile_93710e837db881918343330e464a079c&account_id=personal) ·
[FTA-Launcher-0.17.4-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_cc1af84d83648191977c77d323369fdb&account_id=personal)

The physical code-53 report reached the original owned WarcraftXL host with
**eight channels**. The previous AV was not recorded. It failed during the next
15-second observation period, where the old observer omitted the process state
and exit code. The report does not identify which process exited or rule out a
wait error. [Assessment](evidence/android-2026-10-29-thor-code53-post-host-failure.json).
Elapsed **8m38s** includes **67 seconds / two pressure pauses**; faster verification
is retained. Full client verification, prefix preparation and owned cleanup passed.

Code 54 records both process states and available exits before cleanup, adds a
separately correlated x64 host fault trace, and exports fixed Wine error-channel
categories. [Protocol and privacy bounds](client-original-startup.md#post-host-exit-observations-from-code-54).
Clean early exits still fail the gate. This diagnoses the later failure; it is
not a demonstrated repair for that failure. The x87 compatibility profile,
240 MiB/s pacing, original binaries, runtime, settings and realm remain unchanged.
Rendering and gameplay remain unqualified, including reduced-x87 precision effects.

## One combined Thor test

1. Install code 54 over the existing launcher, keeping app data and imports.
2. Keep **5 GiB free internally**, airplane mode with Wi-Fi off, power connected
   and the launcher visible.
3. Tap **Test original client startup** once. Allow roughly **8–10 minutes** based
   on the last report, longer if pressure pauses occur. Stop remains available.
4. Export diagnostic JSON after completion, failure or Stop and return it before
   retrying. If the action is disabled, export instead.

No visible game window is expected. Do not uninstall, clear data, reimport,
delete settings/cache, repeat Finish, tap separate Verify or rerun passed
standalone storage/runtime/server gates. Preserve all successful receipts.

## Qualified delivery

Application **e93aa5c30689ff05da38c05ef929b2ff0ba4abc5** passed
[foundation 40989654997](https://github.com/Russianranger/fta-launcher/actions/runs/40989654997):
**602 Android tests passed / one existing skip**, **214 tooling**, **138
qualification with no skips**, lint/assembly, ownership, cache/JNI and full
runtime installation/rehash with **96 MiB heap**.
[CI proof](evidence/ci-2026-10-29-code54-foundation-pass.json).

Same-commit [ARM64 40989654994](https://github.com/Russianranger/fta-launcher/actions/runs/40989654994)
passed normal startup, the original PE32 write-fault observer, and three new
post-host cases: clean client exit **0**, host exit **73**, and host write AV
**0xC0000005**, module base **0x140000000**, RVA **0x7c38**, zero trace drops/overflow.
All three stayed failed with owned cleanup complete. The x87 regression, **56
Windows / 50 native guard checks**, seven root refusals, normal/Stop/kill ownership
and full runtime/source integrity passed.
[ARM64 proof](evidence/host-2026-10-29-code54-startup-exits-pass.json).
These are synthetic host results, not original-game or physical code-54 success.

APK **60,693,697 bytes**, SHA256
`8f94601c8190451a6606ba586196fcad9348fb8dd47f3669248f91598edb11af`.
All **247 package checks**, persistent **v2 signature** and **16 KiB alignment**
passed. All **25 native payloads / 148 runtime manifest entries** match code 53;
all **205 runtime asset files** are byte-identical. No native payload was rebuilt
for private delivery. [Package proof](evidence/android-2026-10-29-private-startup-exits-apk.json).

## Corresponding sources

- [FTA-client-bridge-code54-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_a5d8bce45130819197ca569bd1fbde8a&account_id=personal): **2,816,800 bytes**, SHA256
  `465465886241d2abea7d62b744245972d92de4df40aae2b93d01a5acf4987ab7`.
  All **242** tracked Android/native/manifest files match the tested commit.
- Unchanged world companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in order to `FTA-Launcher-0.16.0-world-corresponding-source.zip`,
  **540,243,819 bytes**, SHA256
  `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Strict source verification passed; all 52 modules and C++20 remain intact.
  Part hashes/reproduction details: [code 46](code46-recovery.md).

Sources are development/recovery inputs, not phone installation inputs.
Future updates require code **>=55** and the original persistent signer.
