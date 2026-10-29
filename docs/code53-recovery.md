# Code 53 — x87 startup compatibility

[FTA-Launcher-0.17.3-x87-startup.apk](https://chatgpt.com/library?file_id=libfile_c46978d8946481919832f6951b071fb6&account_id=personal) ·
[FTA-Launcher-0.17.3-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_bce53892ee4c8191b9d9b19e62bd00a8&account_id=personal)

The exact supplied Wow.exe matched the retained report hash. Its repeated fault
candidate lies in an x87 zero-fill loop that should stop after 48 iterations.
On the exact pinned ARM64 Wine/FEX runtime, a synthetic reproduction changed
integer flags from **149 to 84** and wrote into a no-access guard page. The
compatibility profile preserved **149**, completed **48 iterations / 576 bytes**
and kept the canary intact. Enabling strict NaN handling reproduced the failure.
[Analysis and source evidence](wow-startup-x87.md).

Code 53 applies **FEX_X87REDUCEDPRECISION=1** and
**FEX_X87STRICTREDUCEDPRECISION=0** only to original-client startup and its children.
This is a 64-bit x87 precision workaround; full rendering accuracy remains a later
gate. Original client bytes, section permissions, installed runtime, settings,
realm and source protection remain unchanged. The **240 MiB/s** verification
rate is retained; code 52 measured **7m26s versus 39m15s**, a 5.28x ratio between
two reported attempts. Original-client success still requires the Thor report.

## One combined device test

1. Install code 53 over the existing launcher, preserving app data and imports.
2. Keep **5 GiB free internally**, airplane mode with Wi-Fi off, power connected
   and the launcher visible.
3. Tap **Test original client startup** once. Allow roughly **7–10 minutes** based
   on the last report; setup, full verification and the startup check are automatic.
4. Export diagnostic JSON after completion, failure or Stop and return it before
   retrying. If the action is disabled, export instead.

No visible game window is expected in this diagnostic. Do not uninstall, clear
data, reimport, delete settings/cache, repeat Finish, tap separate Verify or repeat
passed standalone storage/runtime/server gates. Preserve all successful receipts.

## Qualified delivery

Application **384198efe1a5357ff0d1d5967c4fc2067f408527** passed
[foundation 40946926084](https://github.com/Russianranger/fta-launcher/actions/runs/40946926084):
**600 Android tests passed / one existing skip**, **214 tooling**, **135
qualification with no skips**, lint/assembly, ownership, cache/JNI and full runtime
installation/rehash with **96 MiB heap**. [CI proof](evidence/ci-2026-10-29-code53-foundation-pass.json).

Same-commit [ARM64 40946925988](https://github.com/Russianranger/fta-launcher/actions/runs/40946925988)
passed the exact production x87 profile and required failure/pass/failure matrix,
normal startup observer, intentional write-fault reporting, **56 Windows / 50
native guard checks**, seven root refusals and normal/Stop/kill ownership. Full
runtime/source integrity passed. [ARM64 proof](evidence/host-2026-10-29-code53-x87-startup-pass.json).
These are synthetic host results, not original-game, rendering or login qualification.

APK **60,689,601 bytes**, SHA256
`97e36579bfc35cf7e91c34460cf70247bf3fb17cf056e39af3ea0899edb4cce4`.
All **247 package checks**, persistent **v2 signature** and **16 KiB alignment**
passed. All **25 native payloads / 148 runtime manifest entries** match code 52;
all 205 runtime asset files are byte-identical. No native payload was rebuilt
for private delivery. [Package proof](evidence/android-2026-10-29-private-x87-startup-apk.json).

## Corresponding sources

- [FTA-client-bridge-code53-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_c3b5dec99784819183e412aec7989693&account_id=personal): **2,799,259 bytes**, SHA256
  `3b032602da68b412f86794b5b415ea94b948398e9efebf8f59488f061b198cd8`.
  All **242** tracked Android/native/manifest files match the tested commit.
- Unchanged world companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in order to `FTA-Launcher-0.16.0-world-corresponding-source.zip`,
  **540,243,819 bytes**, SHA256
  `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Strict source verification passed; all 52 modules and C++20 remain intact.
  Part hashes and reproduction details: [code 46](code46-recovery.md).

Sources are development/recovery inputs, not phone installation inputs.
Future updates require code **>=54** and the original persistent signer.
