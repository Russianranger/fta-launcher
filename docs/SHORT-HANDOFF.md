# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. The owner authorizes autonomous
development and pushes. Target: AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). History: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current delivery and next test

**40 / 0.14.2-client-prefix** is delivered, with the original persistent signer.
[APK, exact sources and test procedure](code40-recovery.md). APK: 60,152,013 bytes,
SHA256 `434b067ff2fa12781fe186814a1baf894f2a818a51b755fa07c48f210a096dae`.
[Packaging proof](evidence/android-2026-10-27-private-client-prefix-apk.json).
All 23 native payloads and 148 retained runtime entries are byte-identical to 39;
235 packaged checks, v2 signature and 16 KiB alignment passed.

Application commit **fdf7eb5fedaaa0beaac0a239e1b3b6949bef47c9** passed foundation
**40587205325**, all three jobs, and ARM64 **40587205346**. Results: 207 tooling,
82 qualification (no skips), 522 Android tests passed / one existing skip,
lint/assembly, cache/JNI and complete runtime installation/rehash with a 96 MiB
heap. All ten live ARM64 guest stages, socket controls, owned-process cleanup
and full runtime pre/post rehash passed. [Exact host proof](evidence/host-2026-10-27-client-prefix-pass.json).
These are Linux results; code 40 has not yet run on Thor.

**Await one code-40 report.** Install in place; retain all storage, keep the
launcher visible and allow at least 2 GiB free. Run **Test client runtime** once,
then **Export diagnostic report** on either outcome. Initialization can take up
to ten minutes; subsequent checks/rehash are automatic. Export immediately on
failure before retrying. Do not uninstall, clear storage, prepare/download again,
reimport the client/Data.zip or repeat completed database/auth/world tests.
The synthetic test does not open the game. Existing failed attempts remain
intact under the three-attempt limit.

## Latest physical failure and repair limits

**39 / 0.14.1-client-sockets** is the last measured installed version.
[Physical result](evidence/android-2026-10-27-thor-code39-prefix-timeout.json):
socket errno 0, display ready, normal/Stop/forced tracer-death ownership and
full 2,637,799,241-byte runtime pre-rehash passed. Prefix initialization timed out
at the fixed 180-second deadline; total elapsed 305,961 ms, deliberate pacing
80,171 ms. Available memory 11,095,248,896 bytes, zero pressure pauses and no
attempt-matched Android exit. Do not call this a low-memory kill.

[Code 40](client-prefix-initialization.md) uses Wine's original first-instance
`-i` mode for the always-fresh disposable prefix, a 600-second prefix allowance
and 1,200-second guest ceiling, bounded 15-second progress, narrowly scoped
Wine diagnostics and a corrected command monitor that cannot wait indefinitely
on a finished command's noisy inherited pipe. Stop and memory safeguards remain.
The prior report did not distinguish slow initialization from a persistent Wine
hang; Android success is not established by the new deadline or Linux pass.

On the next report, match exact APK identity and inspect inactive completed
worker, socket/display, all ten stages, owned cleanup, full pre/post rehash and
attempt-matched exits. Prefix counts/marker presence are diagnostics, never proof
of successful initialization. If failed, use the new metadata/categories for a
targeted repair. A pass advances to the isolated writable client view and original
WarcraftXL launch. Original client, graphics and game login remain unqualified.

## Passed physical gates — preserve, do not repeat

**Client integrity passed on 37.** [Import](evidence/android-2026-10-25-thor-code37-client-import-pass.json)
and [retained full rehash](evidence/android-2026-10-25-thor-code37-client-retained-pass.json):
10,476 files / 60,243,212,332 bytes, catalog
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
Import 2h40m05s with 4,898,874,959 resumed bytes; rehash 55m10s, peak reported
RSS 186,548 KiB. No matching exit. [Code-37 recovery](code37-recovery.md).
Actual cache eviction and forced-pressure backoff remain unmeasured.

**Complete native world gate passed on 34.**
[Physical proof](evidence/android-2026-10-25-thor-code34-world-pass.json), attempt
`9e7570a8-23db-4a23-ab82-c44cbf360a02`: all 23 checks true, original database
fallback, 113 DBC/8,922 GT rows, world readiness, 307,424 ms healthy session and
259,192 ms healthy screen-off, controlled world/auth/database Stop with exit zero,
SQL shutdown, closed listeners and retained metadata postchecks. No seed imported.
All 52 source modules, C++20 and NPCBots preserved. [Code-34 recovery](code34-recovery.md).

All 22,817 server assets / 7,625,943,031 bytes passed import and full rehash.
Original Data.zip SHA `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`;
catalog `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Database/updater, realm reuse, backup/restore, process-death recovery and local
auth/Stop/restart already passed. Never reseed the retained realm.

## Recovery and following development

The pinned public Wine/FEX runtime has 14,092 entries / 2,637,799,241 file bytes.
Its verified installation is retained. [Installer](client-runtime-installation.md):
authenticated extraction ranges, <=1 MiB sync/readback windows, delayed links,
exclusive lease, atomic publication and complete rehash. Aggregate client I/O
remains 48 MiB/s with unchanged buffers and memory/PSI/trim backpressure.

Original host/DLL/proxy identities and the isolated header contract are pinned
in [warcraftxl-host.md](warcraftxl-host.md). Preserve the original Windows-PID
launch contract. Original execution needs a separate writable view held under
the verified client lease: no writable hard links, original mutations or
unbounded 60 GB copy. Deferred Attriboost Settings field 8 vs 9 is later gameplay
work. The full 1.1 GB source is not needed for the synthetic gate.

Private updates must retain the signing capsule
`FTA-Launcher-Development-Signing.zip`, `libfile_7f9c88258f0c8191a09ab071e7447d30`.
Certificate `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Never regenerate keys, expose credentials or substitute the public CI APK.
Future updates must use code >=41. [Code-40 recovery](code40-recovery.md) identifies
the new exact bridge source and unchanged v15 server source companion; the
strict packaging verifier accepts both.

Native cache/source recovery: [code32 baseline](code32-recovery.md), then the
reviewed 0019 seller-count patch/v15 relinks from [code34](code34-recovery.md).
Restore pinned NDK/timestamps and inspect actual source/build inventory before
Ninja; cache directory names do not prove version. Native builds use one job
by default/max two after memory checks; never concurrent heavyweight builds.
