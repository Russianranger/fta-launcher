# Code 48 — preserve large settings with bounded session output

[FTA-Launcher-0.16.2-settings-budget.apk](https://chatgpt.com/library?file_id=libfile_d7ad771597fc8191be752c8d380fc547&account_id=personal)

[FTA-Launcher-0.16.2-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_95a65a0a2e0481919c7d1c3d803afd75&account_id=personal)

Code 47 stopped at `client-preflight / view-copy-limit` after **127,186 ms**.
Its full runtime prehash passed, but client verification, view publication and
Wine had not started. No memory-pressure pause or matching Android exit occurred.
The newly exported costs identify **112,058,085 WTF bytes across 4,491 files**
plus **4,117 bytes** of other configuration: **112,062,202 bytes (106.9 MiB)**
of independent copies, exceeding the old 64 MiB limit. Cache exclusion correctly
removed 1,113,628 bytes from the copy cost; that alone was insufficient.
[Exact report assessment](evidence/android-2026-10-29-thor-code47-settings-budget.json).

Code 48 preserves every setting in independent copies and raises the copy
ceiling to **256 MiB**. Before native startup, it measures the prepared view
and freezes a **128 MiB session-output allowance above that initial size**.
The initial view is itself bounded by 256 MiB plus up to 1 MiB for launch settings.
Five-second polling and a terminal check enforce the frozen total without
following shared links. This is a polling threshold, not a filesystem quota.
The report exports the initial bytes, allowance and enforced total.

Root Cache/Logs/Errors/Screenshots remain fresh independent session directories;
the original imported folders and all settings remain in full verification and
source audits. No writable hard links, silent full client recopy, native changes,
realm reseeding or imported-file cleanup occur. Prefix and free-space checks,
256 KiB copy buffers, pacing, memory backpressure and lease/reader ownership remain.

The synthetic regression reproduces the measured copy totals and 4,491 WTF files,
compares the streamed copy hash, verifies source independence, rejects inadequate
space before publication, accepts the exact session-output allowance and rejects
one byte over it. Separate boundary coverage rejects an oversized initial view
and confirms that shared links are not counted. Existing over-cap rejection,
source mutation, cancellation, unsafe paths and retained code-43 identity tests
remain in the passing suite. No private settings content was used in tests.

## One device test

1. Install code 48 over the existing launcher, retaining all app data.
2. Keep at least 5 GiB free internally, airplane mode with Wi-Fi off, and the
   launcher visible with the device powered.
3. Tap **Test original client startup** once. The fresh full client check has
   not run in code 47's failed attempt. Allow roughly an hour plus runtime and
   prefix checks. Stop remains available.
4. Export the diagnostic JSON after completion/failure/Stop before retrying.
   If the action is disabled, export instead.

No game window is expected in this headless startup/host/cleanup diagnostic.
Physical original startup, rendering and login remain unqualified. Do not
reimport, clear data, delete imported cache/settings, repeat Finish or rerun
passed import, standalone Verify, runtime, storage or server gates.

## Verified build and package

Application **161720f9cea06e6fcb52374c3634ea673ec75b5b** passed
[foundation 40884250737](https://github.com/Russianranger/fta-launcher/actions/runs/40884250737):
**214 tooling**, **122 qualification** with no skips, **583 Android tests passed /
one existing skip**, lint/assembly, native ownership, cache/JNI and full runtime
installation/rehash under 96 MiB heap. [CI evidence](evidence/ci-2026-10-29-code48-foundation-pass.json).
[Same-commit ARM64 40884250533](https://github.com/Russianranger/fta-launcher/actions/runs/40884250533)
passed the five-stage synthetic startup observer, 56 Windows checks, 50 native
guard checks/seven invalid roots, normal/Stop/killed-tracer cleanup and full
runtime/source integrity. [Host evidence](evidence/host-2026-10-29-code48-guarded-observer-pass.json).

Signed APK: **60,677,313 bytes**, SHA256
`d152797c5046abbecd5edcfb2931eddf67916551f07c50b39a5d4b182d74d227`.
All **247 package checks**, v2 signature and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 47 exactly.
Certificate: `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
[Packaging proof](evidence/android-2026-10-29-private-settings-budget-apk.json).
Qualified assembly SHA256:
`4572b4ef5b74d66f491df94d713d82e9394a88e1b060f2fb797f928ff53fe068`.

## Corresponding sources

- [FTA-client-bridge-code48-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_b0a4e0ada6b88191b769c1b94c3c2629&account_id=personal):
  **2,703,396 bytes**, SHA256
  `a87d42553ffde90583939ac238c163adf480c56dbdbf228ace8f7f072c49a6a5`.
  Exact application snapshot, original bridge sources, patches, fixtures,
  observer, recipes and notices. All **236** tracked Android/native/manifest
  files matched the qualified application commit.
- Unchanged server source companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in order to recover `FTA-Launcher-0.16.0-world-corresponding-source.zip`,
  **540,243,819 bytes**, SHA256
  `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Its strict source verifier passed unchanged; original sources, all 52 modules,
  C++20 and v15 build evidence remain intact. Part hashes: [code 46](code46-recovery.md).

Sources are build/recovery inputs, not phone installation inputs. Use the signed
private APK above. Future updates require code >=49 and the original signer.
