# Code 46 — isolated original-client startup

[FTA-Launcher-0.16.0-client-startup.apk](https://chatgpt.com/library?file_id=libfile_2798c6e5b80c8191a49ec7aa20a7991f&account_id=personal)

[FTA-Launcher-0.16.0-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_3414e0a072508191b194073fbf631acb&account_id=personal)

Code 46 / `0.16.0-client-startup` adds **Test original client startup**.
The operation freshly verifies the retained client under its exclusive lease,
creates an isolated working view, launches original Wow.exe, observes its
DLL-owned WarcraftXL host, then stops the owned session. It preserves previous
reports, the original import and the populated realm. [Contract](client-original-startup.md).

This diagnostic uses a headless display. It does not present a game window,
start the realm or log in. Original-client behavior on Thor remains unqualified
until the device report is assessed, regardless of the synthetic host CI result.

## One device test

1. Install the private code-46 update over the existing launcher, keeping app data.
2. Have 5 GiB free internal storage, enable airplane mode with Wi-Fi off, and
   keep the launcher visible and the device powered.
3. Tap **Test original client startup** once. It includes the required fresh
   verification; allow roughly an hour for the 60 GB client plus several minutes
   for runtime/prefix checks. Stop remains available.
4. Export and return the diagnostic JSON after completion, failure or Stop,
   before retrying. If the action is unavailable, export the report instead.

The report adds `clientOriginalStartup`; a pass requires all five zero stages,
valid host ownership/header events, client and host exit, native cleanup, full
client verification, unchanged source metadata and full runtime pre/post hashes.
The source postcheck is metadata/inventory, explicitly not a second full client
hash. Large shared assets use protected guest links; mutable copies are capped at
64 MiB. Only the working copy receives the two required launch settings.

Do not repeat Finish, import, standalone Verify, runtime preparation, storage or
server tests. The passed code-45 recovery and prior gates remain preserved.

## Build qualification

The [ARM64 observer/storage gate](https://github.com/Russianranger/fta-launcher/actions/runs/40803796559)
passed on `85239b7b226d41900a088f368ce1d0abdc201892`. The observer's synthetic
x86 client/x64 host passed startup, two-channel header/Windows parent identity,
controlled client termination, host exit and full runtime/source integrity.
The existing 56 guarded Windows checks/six stages and 50 native guard checks
also passed, with seven invalid-root refusals and tracer cleanup tests.
[Measured proof](evidence/host-2026-10-29-original-startup-observer-pass.json).
The qualification sources are unchanged by the later Android reporting and
fixture fixes. The first Android test run stopped on five incomplete synthetic
fixtures rejected by the existing import validator; the missing required roles
were added without relaxing the production validator.

Application commit **1424e31274a35e880bebc170b585f04f31f75b56** passed
[foundation CI 40805115590](https://github.com/Russianranger/fta-launcher/actions/runs/40805115590):
**214 tooling**, **122 qualification** with no skips, **577 Android tests passed /
one existing skip** in 57 suites, lint/assembly, ownership, cache/JNI and full
runtime installation/rehash with a 96 MiB heap.
[Measured CI record](evidence/ci-2026-10-29-code46-foundation-pass.json).
Assembled APK SHA256 `b908eec49d6995da4de6b24a02c401bf1c61feb0e96b721cb57fa238cca66618`.

The private APK is **60,673,217 bytes**, SHA256
`5b93c145bf067ee64034ecc81e0db8593fe6a5d2a8dfdedb245c40b0a18d1c66`.
All **247 packaging checks**, v2 signing and 16 KiB alignment passed. All
**25 ARM64 native payloads / 148 runtime entries** match code 45 byte-for-byte.
The persistent signing certificate remains
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
[Packaging proof](evidence/android-2026-10-29-private-client-startup-apk.json).
No original-client device execution is claimed by these build/host checks.


## Corresponding sources

- [FTA-client-bridge-code46-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_e9e9f89cdd748191b06c9641e4ed07fb&account_id=personal):
  **2,673,617 bytes**, SHA256
  `90aa85d2f206bb80ccf92a939c0837086d7373b460119a2a3404ead26ee5ebc7`.
  Includes the exact application snapshot, original PRoot/talloc sources,
  patches, Windows observer/fixtures, recipes and notices. All **236** tracked
  Android/native/manifest files matched the qualified application commit.
- Retained native-server source companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal) and
  [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal). Concatenate in that order to recover
  `FTA-Launcher-0.16.0-world-corresponding-source.zip`, **540,243,819 bytes**,
  SHA256 `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  Distribution tools/tests were refreshed; every original archive, notice,
  native source and v15 build proof remains unchanged. All 52 modules and C++20
  remain preserved. The strict current-source verifier passed.

| Source part | Bytes | SHA256 |
| --- | ---: | --- |
| 1 | 419,430,400 | `f1833850313150c7deb903126f20d8f380793173000964a801db9461124598ec` |
| 2 | 120,813,419 | `af4acd6a698562e7acac19780695cc1cc952d3b7831391397c774405cf5a9a3d` |

Sources are recovery/build inputs, not installation inputs. Wine/FEX retains its
original source attribution and notices. Use the private signed APK above, not
the public foundation APK. Future private updates require code >=47 and the
original signing capsule.
