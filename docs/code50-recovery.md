# Code 50 — prepare Wine before the long client verification

**Code 50 passed prefix preparation and reached the original client process.**
The returned report then recorded an early 0xC0000005 exit. Preserve this report
and earlier passes. [Assessment](evidence/android-2026-10-29-thor-code50-client-access-violation.json).
[Code 51](code51-recovery.md) adds bounded fault-location diagnostics; follow its
current recovery notes.

[FTA-Launcher-0.17.0-prefix-first.apk](https://chatgpt.com/library?file_id=libfile_4f3aa91a3fc88191b5ff1e0a1058762f&account_id=personal)

[FTA-Launcher-0.17.0-test-instructions.txt](https://chatgpt.com/library?file_id=libfile_05d06d4d8d98819182f93f1daf628212&account_id=personal)

## Code-49 report and response

The returned code-49 report completed all **10,476 files / 60,243,212,332 bytes**
of client verification and the independent **112,062,202-byte** settings copy.
It then stopped during Wine prefix setup after **2,186,993 ms (36m27s)**.
A critical level-15 trim was active; full PSI **0.36%** exceeded the **0.2%**
quiet threshold required for critical grace. Available memory was **9,026,596,864
bytes**, systemLowMemory=false, and no Android exit matched the attempt.
Owned cleanup and source metadata audit passed; original Wow.exe never started.
[Bounded report assessment](evidence/android-2026-10-29-thor-code49-prefix-pressure.json).

The prefix had generated **1,067,277,683 bytes**. This makes cache pressure a
plausible contributor, not an established cause. Code 50 leaves all memory
thresholds intact and makes two changes:

- After runtime prehash, create the Wine prefix using empty client bindings and
  fully reap that owned tracer **before** the long client hash. A setup failure
  now skips that expensive work. Only a complete prefix can reach client
  verification and the second original-startup invocation.
- A bounded worker syncs changed disposable prefix files and requests cache
  release through descriptor-relative NOFOLLOW handles. It skips symlinks,
  hard links and special files, never reads payload contents or changes them,
  and records synced/accepted/error/byte counts. Accepted advice does not prove
  eviction. The worker must stop before prefix preparation can pass.

The second invocation reuses the completed prefix and exact observer assets;
it does not rerun wineboot. Full client verification, immutable guard, independent
settings, source audit, owned cleanup and full runtime posthash remain required.
Already allocated prefix bytes are deducted from remaining space reservations;
3 GiB prefix, 1 GiB free-space, 256 MiB settings and 128 MiB session-growth
limits remain. [Design, references and limitations](client-original-startup.md#prefix-preparation-before-client-verification-from-code-50).

## One combined device test

1. Install code 50 over the existing launcher, keeping all data.
2. Keep **5 GiB free internally**, airplane mode with Wi-Fi off, the launcher
   visible, and the device connected to power.
3. Tap **Test original client startup** once. Setup runs first, after runtime
   verification. If it passes, the full client check proceeds automatically.
   Allow **40–60 minutes** for the complete attempt; Stop remains available.
4. Export the diagnostic JSON after completion/failure/Stop, before retrying.
   If the action is disabled, export instead.

No visible game window is expected in this headless diagnostic. Do not clear
data, uninstall first, reimport files, delete imported settings/cache, repeat
Finish, tap separate Verify or rerun passed standalone runtime/storage/server
checks. Imports, runtime and saved realm remain. Physical original startup,
rendering and login are still unqualified.

## Exact-commit qualification

Application **490a9cf7bfa7234b5ee98426fb0a9b9c79e7f0b6** passed
[foundation 40908106621](https://github.com/Russianranger/fta-launcher/actions/runs/40908106621):
**214 tooling**, **128 qualification** (no skips), **593 Android tests passed /
one existing skip**, lint/assembly, native ownership, cache/JNI and full runtime
installation/rehash with a **96 MiB heap**. [Measured CI evidence](evidence/ci-2026-10-29-code50-foundation-pass.json).

Same-commit [ARM64 run 40908106926](https://github.com/Russianranger/fta-launcher/actions/runs/40908106926)
passed both real prefix/startup invocations, synthetic client/host observation,
56 protected Windows checks, 50 native guard checks, seven invalid-root refusals,
normal/Stop/tracer-death ownership and full runtime/source integrity checks.
Prefix preparation took **42,909 ms** on that host, with **1,750 accepted cache
requests / zero errors**, covering **2,125,016,112 cumulative requested bytes**.
This is not a cache-size/eviction measurement or prediction of Thor timing.
[ARM64 evidence](evidence/host-2026-10-29-code50-prefix-first-pass.json).
No original private game files were executed in CI.

## Signed private package

APK **60,681,409 bytes**, SHA256
`682141dc4f1a59805b650513341a7ce96e85f620472542af18c4ee03e4115aaa`.
All **247 package checks**, persistent **v2 signature** and **16 KiB alignment**
passed. All **25 native payloads / 148 runtime entries** match code 49 exactly.
Qualified assembly SHA256:
`a147172bfc235d58bd0cde3b572e517bcf36d5a21a76a6e23e88df27d32a0df1`.
Persistent signer certificate:
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
[Packaging proof](evidence/android-2026-10-29-private-prefix-first-apk.json).
No native payload was rebuilt for this private delivery. Use this signed private
APK; the public foundation APK lacks the retained private runtime.

## Corresponding sources

- [FTA-client-bridge-code50-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_3b6c545487e88191ac86a47daa47a92d&account_id=personal):
  **2,738,033 bytes**, SHA256
  `9c8889912b8927ea6d1c2dd9cda41d83664b71f78feb0c1fa3eea0d26b1c9e8c`.
  Exact application snapshot, original bridge sources, patches, fixtures,
  observer, recipes and notices. All **237** tracked Android/native/manifest
  files matched the qualified application commit.
- Unchanged server source companion: [part one](https://chatgpt.com/library?file_id=libfile_37d026ca34588191a06bf02b9994fb2b&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_b46c5c5d50dc81919b94e81c25027572&account_id=personal).
  Concatenate in order to recover `FTA-Launcher-0.16.0-world-corresponding-source.zip`,
  **540,243,819 bytes**, SHA256
  `32c1e84fee72d6674b8928e4edb1b9064c1b81b7b14ec033737b09d7087a9de5`.
  The strict source verifier passed unchanged. Original sources, all 52 modules,
  C++20 and v15 build evidence remain intact. Part hashes: [code 46](code46-recovery.md).

Sources are development/recovery inputs, not phone installation inputs.
Future updates require code **>=51** and the original signer.
