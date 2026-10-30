# Code 55 — live startup pressure

[FTA-Launcher-0.17.5-live-pressure.apk](https://chatgpt.com/library?file_id=libfile_b5f3eb3d52c4819181b1e9d46617caed&account_id=personal) ·
[FTA-Launcher-0.17.5-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_a810c0940c3881918af9634288bfff49&account_id=personal)

Code 54 stopped after **48 seconds during prefix preparation**, before Wow.exe or
WarcraftXLHost.exe started. The trigger was a full-stall avg10 reading of **2.07%**;
7.84 GiB was available, no low-memory flag was set, and prefix cleanup completed.
That report cannot distinguish ongoing stalls from a decaying average, and gives
no new evidence about the earlier post-host failure.
[Report assessment](evidence/android-2026-10-29-thor-code54-prefix-pressure.json).

Code 55 measures fresh stall-counter intervals and confirms moderate pressure
against a three-second deadline with ample memory. Fresh quiet intervals allow
recovery; severe stalls, low memory, critical callbacks and unavailable evidence
during confirmation still stop promptly. The monitor stays active. These are
tested policy changes, not proof that the physical event was harmless or that the
post-host failure is fixed. [Policy and regressions](client-live-pressure.md).
The **240 MiB/s** verification rate, x87 workaround, original binaries, runtime,
settings, receipts and retained realm remain intact.

## One Thor test

1. Install the signed APK over code 54, keeping app data and imports.
2. Keep **5 GiB free**, power connected, airplane mode on with Wi-Fi off, and
   launcher visible. Close other memory-heavy apps.
3. Tap **Test original client startup** once. Allow **8–10 minutes** plus pressure
   pauses. Stop remains available. A visible game window is not expected.
4. Export diagnostic JSON after completion, failure or Stop; return it before
   retrying. If the action is disabled, export instead.

Do not uninstall, clear data, reimport, delete settings/cache, repeat Finish,
run separate Verify or repeat passed storage/runtime/server gates.

## Qualified delivery

Application **23d829dd5a01b14d2a6336e15321cf5868a3d975** passed
[foundation 40994731715](https://github.com/Russianranger/fta-launcher/actions/runs/40994731715):
**613 Android tests passed / one existing skip**, **214 tooling**, **138 qualification
with no skips**, lint/assembly, ownership, cache/JNI and full runtime install/rehash
under **96 MiB heap**. All eleven new pressure regression tests passed.
[CI evidence](evidence/ci-2026-10-29-code55-foundation-pass.json).
The native and Windows observer sources are unchanged; retain code-54 ARM64
**40989654994**, which was not rerun. No physical code-55 execution, original
startup, rendering, login or gameplay success is claimed.

APK **60,697,793 bytes**, SHA256
`85aef564cbf41ab707b91e44dfd0b965102ca960e455e536e61355d557eaa8d7`.
All **247 package checks**, persistent **v2 signature** and **16 KiB alignment**
passed. All **25 native payloads**, **148 runtime manifest entries** and **205
runtime asset files** match code 54. No native payload was rebuilt for private
delivery. [Package proof](evidence/android-2026-10-29-private-live-pressure-apk.json).

## Corresponding sources

- [FTA-client-bridge-code55-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_d8fbe11b7348819195c070fc6c302baf&account_id=personal): **2,835,456 bytes**, SHA256
  `2f4b1b3d26bd16f842c972824d1cef0a52322edb3dc6779465337fc95d7baea9`.
  All **244** tracked Android/native/manifest source files match the tested commit.
- Unchanged world companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in order to `FTA-Launcher-0.16.0-world-corresponding-source.zip`,
  **540,243,819 bytes**, SHA256
  `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Strict source verification passed; all 52 modules and C++20 remain intact.
  Part hashes/reproduction details: [code 46](code46-recovery.md).

Sources are development/recovery inputs, not phone installation inputs.
Future updates require code **>=56** and the original persistent signer.
