# Client runtime preparation after the code-37 integrity pass

The complete managed import and subsequent retained full rehash passed on Thor:
10,476 files / 60,243,212,332 bytes, catalog
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
[Import proof](evidence/android-2026-10-25-thor-code37-client-import-pass.json) and
[retained proof](evidence/android-2026-10-25-thor-code37-client-retained-pass.json).
Keep the installed private code 37. No repeat import, verification, native world,
database/auth or screen-off test is needed. The source changes below prepare a
future runtime update; they do not change the installed artifact or expose a new
action. Any later private APK must have a higher version code and original signer.

## Fresh integrity and ownership

`ClientStore.withVerifiedClient` acquires the existing exclusive store lease,
rehashes all files with the same memory governor/cache hooks, validates membership
and PE metadata through the required validator, and checks an expected catalog.
Only then can its callback use the exact directory that was checked. Import,
replacement, verification and nested acquisition cannot enter while that callback
holds the lease. Exceptions and cancellation release ownership; no files or active
pointer are changed by the helper. A prior success receipt alone never opens it.

Eight regressions cover exact directory/catalog binding, replacement exclusion,
same-size corruption after a prior verify, stale catalogs, callback cleanup,
memory-pressure interruption, validator failure, cancellation and missing inputs.
These are synthetic JVM tests, not a client runtime qualification.

This is a cooperative filesystem lease, not a sandbox. All eventual child readers
must finish inside the callback, including failure cleanup. Callers must not retain
the path afterward. A writable runtime view still needs implementation: launching
Wine directly against the immutable imported generation would mutate its settings,
logs and cache and invalidate its catalog. No hard-linked writable files, silent
config resets or second unbounded full-client copy should be introduced.

## Original Wine proxy behavior

The exact private `Launcher.zip` SHA256 is
`b1ab843535307a5a384b30ed29ef80733abf5e45b7967250408a80f79a6380f0`.
Its `Launcher/src/main_linux.cpp` supplies `NativeD3d9Overrides`: keep unrelated
module overrides, remove exact case-insensitive d3d9/d3d9.dll names from grouped
clauses, and append `d3d9=n,b`. This prefers the client-root WarcraftXL proxy while
retaining the original fallback policy. It does not replace that proxy with the
nested DXVK backend. Opaque clauses and wildcard module names retain source behavior.

`ClientWineOverrides.nativeProxy` implements this bounded string transformation.
It rejects embedded NUL and inputs over 16,384 characters, accepts no command or
path, writes no environment/configuration, and starts no process. Eight unit tests
exercise grouping, conflicts, case, whitespace, empty/opaque clauses, idempotence
and bounds. A separate host probe compiled the **unchanged original functions**
and compared **1,038 synthetic cases byte-for-byte** against production Kotlin.
[Measured source comparison](evidence/host-2026-10-25-client-wine-overrides.json).

The same original Linux/Windows source enforces `specular=0` and
`projectedTextures=1` immediately before launch. It retains root d3d9.dll and
WarcraftXL.dll, uses the client folder as working directory, and selects an
embedded DXVK backend independently. These observations guide later runtime-view
implementation; this increment does not alter imported WTF/Config.wtf,
WarcraftXL.cfg, custom MPQs or Modern Assets/host/Back Sheath Flip settings.

Reproduce the source comparison with JDK 17, Kotlin 2.0.21 and one C++20 compiler:
compile `ClientWineOverrides.kt` plus
`android/qualification/ClientWineOverridesHost.kt` into a private classes directory,
then supply that directory and Kotlin stdlib as the classpath:

```sh
python3 android/qualification/check_client_wine_source.py \
  --launcher-zip /private/Launcher.zip \
  --kotlin-classpath /private/classes:/private/kotlin-stdlib-2.0.21.jar \
  --work-dir /private/new-probe-directory --output /private/new-evidence.json
```

The archive identity is mandatory. Generated original-source harness, executable
and logs remain outside Git. The normal Android suite runs all 16 new regressions;
the private original-source comparison cannot run in public CI without its input.
Implementation `d5382d5312e7ba5150adfffa397de57aa6045e8b` passed all three jobs in
[CI 40254380310](https://github.com/Russianranger/fta-launcher/actions/runs/40254380310):
473 Android tests passed / one existing skip, lint/assembly, cache-helper checks,
186 tooling and 24 report tests. [Exact qualification](evidence/android-2026-10-25-client-runtime-preparation-ci.json).

## Remaining input and runtime gates

The report contains two anonymous x64 executables:

| Bytes | SHA256 | Observed version |
| --- | --- | --- |
| 1,071,104 | `6349752018efba461a2ac4bf19a98d1293aa7c21f98cb90f50615f33a7701955` | Absent |
| 801,792 | `773a73824dbdb3b909b4d9120080483be316c6451d71938fd11010a68a56c497` | 1.8.34.0 |

Neither architecture, size, version nor presence identifies the matched asset
host or proves its IPC contract. The recovered launcher source does not name or
spawn that host directly. The matching WarcraftXL runtime/source package is not
available in the recovered inputs; Library searches did not locate it. Public
WarcraftXL development sources cannot establish this customized binary's identity.

Obtain the **original WarcraftXL runtime bundle/source matching this client**,
including its proxy, WarcraftXL.dll, x64 host and adjacent runtime dependencies.
The 60 GB MPQ/game-data set, realm database, accounts and private settings are not
needed for this source/binary inspection. Match the already recorded component
hashes before selecting a host path or adapting its behavior.

Then pin the ARM64 Wine/translator/display stack and notices, implement a writable
client view without changing the preserved import, supervise the complete process
tree, and qualify x86 client to x64 host startup/IPC/Stop on Thor. Renderer,
controller, account/login, world entry and sustained thermal/runtime gates follow.
No Wine/client execution, asset-host pairing, WarcraftXL compatibility or gameplay
qualification follows from these preparation helpers.
