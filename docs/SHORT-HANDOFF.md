# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. Owner authorizes qualified
implementation, commits and pushes. AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). Historical detail:
[DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Installed private build and passed physical gates

Latest installed APK: **37 / 0.13.2-client-memory**, 59,073,805 bytes, SHA256
`105bf65924ebabedb7dde71c2e3f880e463180a45ccf1fd4f7a6fe14b061cf0b`.
[Verified APK/source recovery](code37-recovery.md). Application commit
`39516bb1b0a499d84279ef9eefea0c5c9e9abcf4`, all three jobs in CI 40216989093 passed.
All 21 native payloads and 148 runtime entries remain the passed native code-34 set.
No new private APK is built for the preparation work below.

**Client integrity fully passed on 37.**
[Import](evidence/android-2026-10-25-thor-code37-client-import-pass.json):
10,476 files / 60,243,212,332 bytes, 2h40m05s, 4,898,874,959 bytes resumed.
[Retained full rehash](evidence/android-2026-10-25-thor-code37-client-retained-pass.json):
same complete counts and catalog, 55m10s, peak reported RSS 186,548 KiB.
Neither attempt has a matching Android exit. Catalog
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
Old unmatched low-memory exits remain historical. Zero pressure pauses means
forced-pressure backoff and actual cache eviction remain unmeasured.

**Complete native world gate passed on 34.**
[Physical proof](evidence/android-2026-10-25-thor-code34-world-pass.json), attempt
`9e7570a8-23db-4a23-ab82-c44cbf360a02`: all 23 checks true; original native database
fallback, 113 DBC/8,922 GT rows, world readiness, 307,424 ms healthy session,
259,192 ms healthy screen-off, controlled world/auth/database Stop with exit zero,
SQL shutdown, closed listeners and retained metadata postchecks. No seed imported.
All 52 source modules, C++20 and NPCBots preserved. Game login remains unqualified.
[Private code-34 recovery](code34-recovery.md) contains exact APK/source identities.

All 22,817 server assets / 7,625,943,031 bytes passed import and full rehash.
Original Data.zip SHA
`407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`;
catalog `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Database/updater, realm reuse, backup/restore, process-death recovery and local
auth/Stop/restart already passed. **Do not repeat these physical gates.**

## Current client-runtime preparation

[Host/DLL/proxy contract](warcraftxl-host.md) pins the supplied original binaries
against Thor's imported observations. The original x64 host is 1,071,104 bytes,
SHA `6349752018efba461a2ac4bf19a98d1293aa7c21f98cb90f50615f33a7701955`.
Original host instructions generated 1,140 header comparisons (855 accepted /
285 zero-PID negatives), all matching the original x86 DLL validator and Kotlin.
This is isolated fragment/static evidence, not original-host execution or graphics.
The DLL must launch Utils/WarcraftXLHost.exe with its **Windows** PID in the same
Wine prefix/session. Never substitute Android PID or prelaunch a separate host.
The original host's exit watcher is conditional; independent owned cleanup is needed.
The full 1.1 GB client source is not currently required.

[Verified-client lease and original Wine override transformation](client-runtime-preparation.md)
are source helpers; imported files/configs remain unchanged. Runtime candidate
[lock/audit](client-mixed-runtime.md) pins the public Wine/FEX archive, all
14,092 entries / 2,637,799,241 file bytes, 12 critical component identities and
three recovered source archives. All original runtime binaries remain unchanged.

**Synthetic native ARM64 Linux Wine gate passed:** implementation
`eddd5be4de75b427660e3489e6bc4d85f3497780`,
[CI 40396385255](https://github.com/Russianranger/fta-launcher/actions/runs/40396385255),
[exact proof](evidence/host-2026-10-26-mixed-wine-runtime-pass.json).
x86/x64 entry exit zero; x86 parent creates x64 child; 64/512 IPC round trips at
2/16 channels; explicit child Stop exit 37; parent loss child exit 38.
The initial FEX assertion was missing sysfs/CPU registry in the disposable chroot;
real read-only sysfs fixes it without invented CPU values or binary patches.
No original client/host binary enters CI. Android runtime, graphics, original
WarcraftXL transport and gameplay remain unqualified.

[Android PRoot bridge build](client-android-bridge.md) uses pinned PRoot/talloc,
original compatibility patches, NDK r28c/API26 and one compiler job. Both load
alignments are 16 KiB. Android PIE tracer depends only on libc/libdl; its static
ARM64 ET_EXEC loader has checked entry, segment bounds and non-executable stack.
[Exact build proof](evidence/host-2026-10-26-android-client-bridge-build.json).
Tracer SHA `46dcafabc77b386a7127f6cb7c71bdac03c372ff6bb4db0e1e9493ee29332adb`;
loader SHA `614591192bb34c98cc6cef528fb74ecfc1c97d0fabf4e352df1f16873c71d447`.
The old public 4 KiB loader is not reused. Outputs stay outside Git and are not
staged in an APK. `tools/build_client_bridge.py` rebuilds from the pinned archives
in a new private directory; rebuilt identities must be measured afresh.

**Tracer-death ownership passed on native ARM64 Linux:** implementation
`969660f0d9c028537d1be53e735bedf1ed50e655`,
[CI 40400465375](https://github.com/Russianranger/fta-launcher/actions/runs/40400465375),
[exact evidence](evidence/host-2026-10-26-proot-ownership-pass.json).
Mandatory PTRACE_O_EXITKILL closes the original tracer-SIGKILL gap: baseline
survivors=3, patched survivors=0, including two detached session leaders; normal
exit remains zero. Exact entry/mapping identities are checked before retaining
cleanup pidfds. The native ELF64 regression uses the separate native loader;
it does not qualify ARM32 Linux or Android execution. The same CI reran the
complete Windows x86/x64 process/IPC gate successfully.

All three [foundation jobs 40400465293](https://github.com/Russianranger/fta-launcher/actions/runs/40400465293)
passed for **969660f0d9c028537d1be53e735bedf1ed50e655**: 200 tooling tests,
62 qualification tests, 486 Android tests / one existing skip, lint/assembly,
cache-helper/JNI and packaged-input checks. This checkpoint adds only evidence
and documentation after that implementation. Inspect remote main on recovery;
never infer the installed private APK from public CI output.

## Next concrete gate and recovery rules

Complete the exact distribution source/notice companion, then implement the
owned Android diagnostic service, bounded runtime installation and disposable
Wine prefix. Qualify synthetic mixed processes on Thor before original host/
graphics claims. Use the existing native process bridge and the new PRoot
ownership requirement for parent death/Stop; Linux evidence does not qualify
Android cleanup. Later create a separate writable client view under the verified
lease: no writable hard links, mutations of imported originals or unbounded
60 GB copy. Preserve all realm/accounts/backups and never replay a fresh seed.
Deferred Attriboost Settings field 8 vs 9 remains gameplay work.

Keep installed 37; **no physical retest requested now**. A new private APK must
use version code >=38 and the original signing capsule
`FTA-Launcher-Development-Signing.zip`, `libfile_7f9c88258f0c8191a09ab071e7447d30`.
Certificate `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Never regenerate keys, expose credentials or label public foundation CI APKs as
private diagnostics. No private binaries/assets/configs/SQL/player data enter Git.

Native cache/source recovery: [code32 baseline](code32-recovery.md), followed by
reviewed 0019 seller-count patch/v15 relinks from [code34](code34-recovery.md).
Restore pinned NDK/timestamps and inspect actual source/build inventory before
Ninja; a cache directory name is not evidence of its version. Native builds use
one job by default/max two after memory checks; never concurrent heavy builds.
