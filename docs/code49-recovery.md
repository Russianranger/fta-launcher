# Code 49 — bounded native memory-pressure confirmation

[FTA-Launcher-0.16.3-pressure-confirmation.apk](https://chatgpt.com/library?file_id=libfile_0c65cfd96c208191a23533393582a05e&account_id=personal)

[FTA-Launcher-0.16.3-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_6aabe3c396d081918041b10ed2a3443e&account_id=personal)

## What code 48 established

The returned report completed fresh verification of **10,476 files /
60,243,212,332 bytes**, copied **112,062,202 settings bytes**, and prepared the
isolated working view. The settings-budget fix worked. CPU topology and display
stages passed; Wine prefix initialization began. After **2,257,840 ms (37m38s)**,
the launcher stopped on a critical level-15 trim notification aged 585 ms.
It reported **8,570,064,896 available bytes**, systemLowMemory=false, and no
matching Android exit. Owned cleanup and the source metadata audit passed.
[Exact assessment](evidence/android-2026-10-29-thor-code48-prefix-memory-stop.json).

The last prefix progress was 15,022 ms, with 72 system32 entries and no registry
bytes yet. Wow.exe had not started. Native samples omitted PSI, so neither a
spurious callback nor absence of actual system/cgroup pressure is established.
The runtime posthash did not complete; original startup remains unqualified.

## What changed

Code 49 shares the bounded pressure-stall sampler between I/O and native
monitoring. It samples once per second without sleeping the native monitor.
System lowMemory, availability below the existing floor, PSI some >=10% or
full >=1%, severe callbacks >=80 and onLowMemory still stop immediately.

A running-critical callback (15..19) alone can receive at most **ten seconds**
from the original continuous episode start only while availability is
>=max(existing floor, total/3), measured PSI some <2% and full <0.2%.
Missing/invalid PSI or reduced headroom refuses the allowance. Repeated callbacks
cannot extend the deadline; a milder callback cannot erase critical/severe state.
An isolated expired notification can recover. Sustained critical notifications
stop. Existing advisory-only grace remains capped at 30 seconds.

The report now retains live native PSI and numeric critical-grace observations.
Seven new regressions cover the reported memory snapshot with/without new PSI,
strict thresholds, immediate pressure stops, repeated callbacks, severity
preservation, snapshot identity and separate recovered episodes.
[Policy, primary references and limitations](client-original-startup.md#native-memory-confirmation-from-code-49).
These are launcher policy thresholds, not proof against Android LMK or unmeasured
cgroup pressure. Stop, ownership cleanup, source protection and full verification
remain active. No Android memory controls or root settings were changed.

## One combined device test

1. Install code 49 over the existing launcher, keeping all data.
2. Keep at least 5 GiB free internally, airplane mode with Wi-Fi off, and the
   launcher visible with the device powered.
3. Tap **Test original client startup** once. Allow about 40–60 minutes plus
   prefix checks. Each new session freshly verifies its source under its lease;
   no separate Verify or import action is requested. Stop remains available.
4. Export the diagnostic JSON after completion/failure/Stop before retrying.
   If the action is disabled, export instead.

No game window is expected in this headless original-startup/host/cleanup test.
Actual original startup, rendering and login remain pending. Do not reimport,
clear data, delete imported settings/cache, repeat Finish or rerun passed
standalone runtime, storage, import or server gates.

## Verified build and package

Application **41055bcde585997333ab56202e88850d45d4f7e3** passed
[foundation 40894673034](https://github.com/Russianranger/fta-launcher/actions/runs/40894673034):
**214 tooling**, **122 qualification** with no skips, **590 Android tests passed /
one existing skip**, lint/assembly, native ownership, cache/JNI and full runtime
installation/rehash under 96 MiB heap. [CI evidence](evidence/ci-2026-10-29-code49-foundation-pass.json).
The native guard, startup observer, owned session code and host harness match
the qualified code-48 versions byte-for-byte. Their [existing ARM64 evidence](evidence/host-2026-10-29-code48-guarded-observer-pass.json)
remains applicable to those unchanged components. No new guarded host run or
physical code-49 execution is claimed.

Signed APK: **60,677,313 bytes**, SHA256
`1f10af52686d4a796e0d4434bd717f242c1673f11ecf0544da773eb3de4482d5`.
All **247 package checks**, persistent v2 signature and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 48 exactly.
Certificate: `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
[Packaging proof](evidence/android-2026-10-29-private-pressure-confirmation-apk.json).
Qualified assembly SHA256:
`ebabc3c667b5411e5e73010255cb8540854b2f79cbdb6b3a308007830491b2ca`.

## Corresponding sources

- [FTA-client-bridge-code49-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_792ac8f1041081919760b50fc7304ad7&account_id=personal):
  **2,718,845 bytes**, SHA256
  `18bd25dbc524643e1c0d28f73c901dfa03c8a3098dba1fab2dc4ec06d58d6e3f`.
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
private APK above. Future updates require code >=50 and the original signer.
