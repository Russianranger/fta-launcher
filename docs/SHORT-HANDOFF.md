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

## Current client-runtime development

Original host/DLL/proxy identities and the isolated original header contract are
pinned in [warcraftxl-host.md](warcraftxl-host.md). The full 1.1 GB client source
is not needed for this synthetic gate. Preserve the original Windows-PID launch
contract; original client/host execution, graphics and gameplay remain unqualified.

The unchanged public Wine/FEX archive is pinned in
`android/qualification/client-runtime-lock.json`: 14,092 entries / 2,637,799,241
file bytes. [Installation](client-runtime-installation.md) now has authenticated
extraction ranges, <=1 MiB sync/readback windows, delayed links, OS lease, atomic
publication and complete retained rehash. Full-candidate host installation and
both rehashes pass with a 96 MiB JVM heap. The separate Android foreground worker
uses the existing 24 MiB/s and memory-pressure governor, Stop, a 90-minute limit,
visible-activity binding and redacted attempt-matched exit reporting.

Latest completed qualified checkpoint: **fcab94bfcead3ef62daa42c65a5467b979939e4c**.
All foundation jobs **40422957436** passed: 200 tooling, 71 qualification,
514 Android tests passed / one existing skip, lint/assembly, cache/JNI, packaged
checks and full 96 MiB-heap runtime installation. ARM64 workflow **40422957424**
also passed the original Wine tests, the new guest program's ten stages, and its
normal/Stop/forced-tracer-death ownership fixture. The empty CI prefix's UID
needed to match the chroot owner (measured runner UID 1001); original Wine's UID
check and runtime bytes remain unchanged. These are Linux results, not Thor.

[Android bridge](client-android-bridge.md): pinned PRoot/talloc, NDK r28c, one job,
16 KiB tracer and loader, mandatory PTRACE_O_EXITKILL. The original-vs-fixed Linux
negative control proved three survivors versus zero, including two detached
session leaders. Exact build proof remains in evidence. This turn's rebuild
measured the same tracer/loader hashes; no world/server payload was rebuilt.

Current increment wires the owned Android driver and private disposable prefix.
It requires normal/Stop/SIGKILL ownership gates before synthetic x86/x64 entry,
2/16-channel IPC, parent loss and Wine stop. A full runtime post-rehash follows
success. Native memory pressure stops the process tree instead of pausing it.
Python bytecode writes are disabled; temporary files, prefix and font cache are
separate binds. No managed client or retained realm path is mounted.
Interrupted attempts are retained, with a three-attempt/2 GiB free-space gate.

`tools/stage_client_probe.py` builds only synthetic fixtures, checks both exact
bridge ELF identities, stages complete notices, and creates/verifies the matching
public corresponding-source companion. Foundation CI builds these sequentially
before Gradle and publishes the companion with its APK. Private packaging requires
that exact companion, keeps all 21 donor payloads unchanged, and separately checks
the two bridge additions. Code **38 / 0.14.0-client-probe** is reserved for delivery;
no private code-38 APK has been issued yet. Verify this increment's exact CI before
packaging. Latest local changes: 205 tooling and 73 qualification tests pass;
18 installer, six download and five ownership-protocol JVM regressions pass.
ARM64 CI also runs the exact Android bind layout through PRoot with complete
pre/post runtime-tree verification. Preparation source/bridge and this layout
gate must both pass for the exact delivered commit.

## Next concrete gate and recovery rules

After exact-commit CI passes, package the private code-38 update from its CI APK,
code-37 donor, unchanged v15 source companion and new bridge source companion.
Verify signature, payload inventories, metadata, sources, 16 KiB alignment and
new measured APK SHA256; save a directly accessible private artifact. The next
Thor test is in-place update, Prepare client runtime, Test client runtime, export
report immediately on success/failure. Do not repeat client import/full rehash,
server data import, database/auth or the completed world/screen-off gate.

Original WarcraftXL/client execution requires a later separate writable view
under the verified client lease: no writable hard links, original mutations or
unbounded 60 GB copy. Deferred Attriboost Settings field 8 vs 9 remains gameplay work.

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
