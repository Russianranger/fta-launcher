# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. The owner authorizes autonomous
development and pushes. Target: AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). History: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Active fix: code 47 working-view budget

Code 46 failed at `client-preflight / view-copy-limit` after **130,618 ms**.
The full runtime precheck passed; client full verification, view creation and
Wine had not started. No pressure pause or matching Android exit. Earlier passes
remain intact. [Assessment](evidence/android-2026-10-28-thor-code46-view-copy-limit.json).
The report does not identify which mutable subtree exceeded the 64 MiB cap.

Code 47 / `0.16.1-view-budget` makes Cache/Logs/Errors/Screenshots fresh independent
session folders, preserving their original imported contents and every source
verification/audit entry. Settings still copy under the 64 MiB limit. Complete
fixed-group costs are recorded before a copy-budget refusal. Sparse oversized
output fixtures, audit mutations, settings limits and the retained code-43 plan
identity are covered by regressions. CI/private packaging are pending.
Do not retry code 46 or reimport; finish code 47 and deliver an in-place update.

## Current delivery: code 46 original-client startup

**46 / 0.16.0-client-startup** is built and persistently signed. Downloads and
one device test: [code-46 recovery](code46-recovery.md). APK **60,673,217 bytes**,
SHA256 `5b93c145bf067ee64034ecc81e0db8593fe6a5d2a8dfdedb245c40b0a18d1c66`.
All **247 packaging checks** passed; all **25 native payloads / 148 runtime
entries** remain byte-identical to code 45. No native server rebuild, reseed or
import is needed. [Packaging proof](evidence/android-2026-10-29-private-client-startup-apk.json).

Application **1424e31274a35e880bebc170b585f04f31f75b56**, foundation
**40805115590**, passed **214 tooling**, **122 qualification** (no skips),
**577 Android tests / one existing skip**, lint/assembly, ownership, cache/JNI
and full runtime installation/rehash under a 96 MiB heap.
[CI record](evidence/ci-2026-10-29-code46-foundation-pass.json).
The ARM64 synthetic observer/guard gate **40803796559** passed on **85239b7**;
its qualification sources are unchanged in the final app. Five startup stages,
x86-created x64 host, read-only header/parent/image observation and host exit
passed, alongside all 56 guarded Windows / 50 native guard checks and owned
tracer cleanup. [Host proof](evidence/host-2026-10-29-original-startup-observer-pass.json).

The new operation holds the client lease from fresh full verification through
isolated view creation, all owned process exits and a metadata audit. Large
assets are protected guest links; mutable copies are capped at 64 MiB. Only
view settings change. Runtime pre/post hashes remain full; source postcheck is
metadata/inventory, not a second full client hash. Strict typed completion
requires every stage, original-host event, verification and cleanup flag.
[Contract](client-original-startup.md).

**Next physical gate:** update in place, 5 GiB free, airplane mode with Wi-Fi off,
launcher visible, tap **Test original client startup** once. Allow roughly an
hour for client verification plus runtime/prefix checks. Export the JSON after
completion/failure/Stop before retrying; if unavailable, export instead. The
new `clientOriginalStartup` report is separate. The display is headless; there
is no game window, realm launch or login. Original behavior on Thor is still
unqualified. Assess this report before advancing to rendering/input/login.
Do not repeat Finish or passed import/runtime/storage/server gates.

## Passed physical recovery (code 45)

**45 / 0.15.3-bind-cleanup** is delivered with the original persistent signer.
[APK, sources and test instructions](code45-recovery.md). APK: **60,546,063 bytes**,
SHA256 `f1b84656765cc4ba2c59e5e786e5bfc1f41e9e733f8a87cc52113ff10c5355b8`.
[Packaging proof](evidence/android-2026-10-28-private-bind-cleanup-apk.json): all
**25** native payloads and **148** runtime entries byte-identical to code 44;
**245** checks, v2 signing and 16 KiB alignment passed. No native payload was
rebuilt for private delivery. The realm, imports and installed runtime remain.

Application **d09350364a38ceaed4d992ee67b9b3352994579f** passed foundation
**40792082906**, attempt 2: **214** tooling, **106** qualification (no skips),
**566 Android passed / one existing skip** in 54 suites, lint/assembly, ownership,
cache/JNI and full runtime installation/rehash with a 96 MiB heap.
[CI record](evidence/ci-2026-10-28-code45-foundation-pass.json).
Guarded Wine **40791282750** on **55ac0fb** reproduced two legacy mode-zero
placeholders after tracer death, then passed fixed normal/Stop/kill ownership,
56 Windows checks / six stages, full rehash and unchanged source on ARM64 Linux,
plus 50 native guard checks / seven root refusals. Mixed runtime **40791282689**
also passed. The final application commit changes only the Android directory-only
deletion API and its regression; the qualified harness/probe environment is unchanged.
[Host proof](evidence/host-2026-10-28-bind-placeholder-recovery-pass.json).

**Code 45's physical guarded-Wine recovery passed — no repeat.**
[Assessment](evidence/android-2026-10-28-thor-code45-guarded-finish-pass.json):
attempt `d077a12c-e9ce-478a-85d7-1dd190d3a735`, **155,074 ms (2m35s)**,
two known empty mode-zero bind placeholders removed, retained files/transcript
validated unchanged, and full runtime rehash of **14,092 entries / 2,637,799,241
file bytes** completed. Result committed, worker inactive, no matching exit.
One pressure pause recovered after **28,000 ms**; I/O pacing totaled **80,248 ms**.
Reported peak launcher RSS **214,480 KiB**, last available memory **7,954,456,576 bytes**.
These observations do not establish total guest RSS or actual cache eviction.

All **56 Windows checks / six zero stages** bind to the original code-43 attempt
`a47e4d81-110f-413a-81ae-111d3e963afb`; the saved source report digest still matches
the prior finish attempt. Wine was not rerun. The original code-43 report remains
`failed / io-failed` after 368,944 ms; its missing verification is completed by the
separate code-45 receipt. Preserve both records and the earlier
[incomplete assessment](evidence/android-2026-10-28-thor-code43-incomplete.json).

**Code 44 failed at `runtime-pre-verify / access-denied`**, after 122,548 ms.
All file bytes were read, but the last file-progress count is not proof that
directory verification completed. No matching exit, pressure pause or Wine rerun.
[Assessment](evidence/android-2026-10-28-thor-code44-access-denied.json).
The pinned PRoot creates mode-zero bind placeholders; the forced tracer kill
bypasses their exit destructors. CI now reproduces `/immutable` and `/view`
leftovers and tests the complete guarded ownership sequence. Code 45 now reports
removal of both matching placeholders on Thor and successful full rehash.
Future probes prevent this pollution. Device files were not independently read.

**No further code-45 test is needed.** Keep the installed app and all retained
data. The strict finish assessor binds exact APK, source attempt/report history,
retained fixture checks, 56 integer-zero checks, complete runtime counts, cleanup,
terminal state and exit evidence. Twelve regressions reject incomplete or
contradictory completion. [Contract](client-guarded-finish.md).
Do not rerun Finish, protected Wine or any passed import/runtime/storage/server gate.

The working-view core is implemented for disposable fixtures: protected guest
links, independent writable copies, 64 MiB copy limit, atomic publication,
source metadata consistency and growth polling. Original-client integration is now implemented in code 46; its physical
startup result is pending. Wine's DeleteFile reports success while the native guard prevents
deletion; actual file survival and full source inventory are independently checked.
No guard restriction was relaxed. [Design and limits](client-guarded-wine.md).

**Physical storage gate 42 passed — no repeat.** [Assessment](evidence/android-2026-10-27-thor-code42-storage-pass.json):
attempt `280ff792-9887-4b12-9e3b-d43f15a12084`, **704 ms**, all **50** checks zero,
four invalid-root launches refused, 65,545 fixture bytes, protected tree unchanged,
owned handles reaped, worker inactive and no matching exit. It did not run Wine
or access the import. [Code-42 recovery](code42-recovery.md).

## Physical client-runtime gate passed — no repeat needed

[Exact reported pass](evidence/android-2026-10-27-thor-code41-runtime-pass.json),
attempt `d23ae710-c265-4c2a-946f-b89dcf7b902f`, matches the delivered APK and pinned
runtime. All ten stages returned zero: CPU topology, display, prefix, registry,
x86/x64 entry, two/sixteen-channel IPC, owner exit and Wine Stop. Full pre/post
rehash covered all 14,092 entries / 2,637,799,241 file bytes. Normal, Stop and
forced tracer-death cleanup passed, `probeCleanupComplete=1`, worker inactive,
no attempt-matched exit. An older low-memory exit predates this attempt.

Elapsed **442,250 ms (7m22s)**; prefix **101,911 ms**; I/O pacing **160,353 ms**.
Two I/O pressure pauses recovered, totaling **79,000 ms**. Last available memory
9,667,428,352 bytes; launcher peak RSS 203,888 KiB (not total guest RSS).
Native advisory count was zero, so this run did not exercise the new advisory
allowance. Last trim level was critical (15), expired by completion. Do not
infer that code 40's missing trigger was advisory, or claim cache eviction.
`rpcss-unavailable` was observed during initialization; all subsequent gates
passed. It is not a demonstrated original-client compatibility result.

**Preserve the code-41 result and all retained data; code 42's storage test also passed.**
Do not repeat those passed import, standalone runtime or server tests. The
code-43 guarded attempt is now qualified through code 45's separately recorded
retained completion. Original WarcraftXL execution,
rendering and login remain unqualified.

## Original-client working view boundary

[Acceptance criteria](client-working-view.md). Preserve the full import/catalog,
original host/proxy and Windows-PID launch contract. Plain PRoot bindings and
symlinks alone provide no read-only protection. Never use writable hard links,
modify imports or silently copy 60 GB. The separately pinned guard passed its
native Linux and physical Android fixture. Guarded PE32/PE64 access now passes
native ARM64 Linux, and the bounded working-view builder is implemented.

**The code-45 finish report has passed.** Preserve the original failed
report, its successful completion and earlier passes. Code 46 prepares the original-client
view under `ClientStore.withVerifiedClient` and retains its lease until every
owned reader exits. Actual original-client view,
WarcraftXL startup, rendering and login remain unqualified. This is cooperative
file-integrity protection, not hostile-code confinement.

The runtime assessor binds exact APK/version, archive/catalog/counts, all ten zero stages,
ownership/cleanup, inactive terminal worker, full pre/post rehash and exit history.
Only typed allowlisted observations are saved. Report authenticity and device files
have not been independently read. [Assessment details](client-runtime-report-qualification.md).
The new storage assessor binds the exact APK/payload inventory, all 50 integer-zero
checks, four root refusals, terminal cleanup and exit history. Nine regressions
reject incomplete/contradictory receipts and keep its qualification limited to
the disposable fixture. No APK or native code changed to record this pass.

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

The verified Wine/FEX runtime remains installed: 14,092 entries /
2,637,799,241 file bytes. [Installer](client-runtime-installation.md): authenticated
extraction, <=1 MiB sync/readback, delayed links, exclusive lease, atomic
publication and full rehash. Client I/O remains 48 MiB/s with existing buffers
and memory/PSI/trim backpressure.

Original host/DLL/proxy identities are pinned in [warcraftxl-host.md](warcraftxl-host.md).
Preserve the original Windows-PID launch contract. Original execution needs an
isolated writable client view under the verified client lease: no writable hard
links, mutation of imports or unbounded 60 GB copy. Deferred Attriboost Settings
field 8 vs 9 is later gameplay work. Full 1.1 GB source is not needed for this gate.

Private updates retain `FTA-Launcher-Development-Signing.zip`,
`libfile_7f9c88258f0c8191a09ab071e7447d30`. Certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Never regenerate keys, expose credentials or substitute the public foundation
APK. Future updates use code >=47. [Code-46 recovery](code46-recovery.md) identifies
the exact bridge/application source and refreshed reproduction companion for
the unchanged v15 native server. Both passed the strict packaging verifier. The code changes and measured evidence are pushed.

Native recovery: [code32 baseline](code32-recovery.md), then the reviewed 0019
seller-count patch/v15 relinks from [code34](code34-recovery.md). Restore pinned
NDK/timestamps and inspect actual sources/build inventory before Ninja. Native
builds use one job by default/max two after memory checks; never concurrent
heavyweight builds. Do not rebuild unchanged native payloads for Java/Kotlin fixes.
