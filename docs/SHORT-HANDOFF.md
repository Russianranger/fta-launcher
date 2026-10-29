# From the Ashes Android — current handoff

Work only in **Russianranger/fta-launcher/main**. The owner authorizes autonomous
development and pushes. Target: AYN Thor, Android 13, ARM64. Read `AGENTS.md`,
[game data](game-data-import.md), [private diagnostics](private-diagnostics.md)
and [native gate](native-world-gate.md). History: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current delivery: code 52 faster verification

**52 / 0.17.2-fast-verify** is signed, saved and qualified.
[APK, sources and one device test](code52-recovery.md). APK **60,689,601 bytes**,
SHA256 `0b6498ce144c2fa8ba2308d9fe2af5523c01fa2b918ecc1678efc94981b72851`.
**247 package checks**, persistent v2 signing and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 51 exactly; the
unchanged code-46 server companion passed strict source verification.
[Package evidence](evidence/android-2026-10-29-private-fast-verify-apk.json).

Owner requested fivefold verification speed. Shared client/runtime I/O ceiling
is **240 MiB/s**, up from 48. Fractional milliseconds carry across buffers and
elapsed reading/hashing time counts toward the deadline. After a stall only the
current <=256 KiB operation may proceed; idle credit does not accumulate.
Full hashes, leases, 1 MiB cache/write/readback windows, Stop and all pressure
thresholds remain. No extra workers, native or original-client changes.
The returned physical attempt fell from **39m15s to 7m26s (5.28x)** with identical
accounted I/O and zero pressure pauses. Keep this implementation. This compares
two reported attempts; it is not a controlled storage benchmark.
[Code-52 assessment](evidence/android-2026-10-29-thor-code52-speed-and-repeat-fault.json).

Application **10912350bdc639c2204aab4063606aaeb8612037** passed foundation
**40938186319 attempt 2**: **600 Android passed / one existing skip**, **214
tooling**, **135 qualification with no skips**, lint/assembly, ownership, cache/JNI
and full runtime install/rehash under 96 MiB heap. Attempt 1 failed installing
the SDK/NDK before compiling; same-commit retry passed. [CI proof](evidence/ci-2026-10-29-code52-foundation-pass.json).
Unchanged guard/observer retains code-51 ARM64 run **40927895789**; no rerun claimed.

Code 51 returned **39m15s**, including **28m20s requested pacing / zero pressure
pauses**. Prefix setup (101277 ms), full client hash and settings copy passed.
The trace recorded a write AV in **Wow.exe**, candidate RVA **0x5c7898**, target
**0xdfe000**, exit **0xC0000005 after 4296 ms**; zero drops/overflow and all three
client image roles observed. Fatal causality is unproven; the crash is unresolved.
[Assessment](evidence/android-2026-10-29-thor-code51-fault-and-pacing.json).
Original startup/rendering/login remain unqualified. Preserve this candidate
for further investigation against the exact imported Wow.exe, not a guessed build.

Code 52 repeated the same fault candidate exactly (all fault fields except exit
elapsed); client exit occurred at **4424 ms**. Prefix setup, all client hashes,
settings, source metadata and owned cleanup passed. Runtime posthash did not
complete after failure. No matching Android exit or memory-pressure pause.

**Next:** obtain the exact original client-root **Wow.exe**, **7,702,016 bytes**,
SHA256 `c4ea8503112878fa47d06580de9d936b184f9bb6b452f6931dfe793fe2f0a47e`.
It is not among supplied files; existing WarcraftXL DLL/proxy/host are available.
Inspect its PE section permissions and instruction/control flow around candidate
RVA **0x5c7898**, and the write target RVA **0x9fe000**. Do not substitute a stock
build, infer fatal causality from equal exception/exit codes, or patch permissions
without evidence. No full client upload, repeated device test, reimport, clear
data or passed standalone gate is needed yet. Keep code 52 installed and preserve
all reports/settings/receipts. No new APK has been built from this assessment.
Future application code **>=53**.

## Superseded delivery: code 51 client fault tracing

**51 / 0.17.1-client-fault** is signed and saved. [APK, sources and one device test](code51-recovery.md).
This collects the missing crash location; **it is not a demonstrated crash fix**.
APK **60,689,601 bytes**, SHA256
`501ed646a4006f69090ee7bbaebd51b896beab829c269a77c09b822adf16c572`.
All **247 package checks**, persistent v2 signing and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 50 byte-for-byte.
No native delivery rebuild; the exact code-46 server companion remains valid.
[Packaging proof](evidence/android-2026-10-29-private-client-fault-apk.json).

Application **cb13b1753837609989eaf5ef9838f3054b1a1c7b** passed foundation
**40927895734**: **214 tooling**, **135 qualification** (no skips), **597 Android
passed / one existing skip**, lint/assembly, native ownership, cache/JNI and full
runtime installation/rehash with 96 MiB heap. [CI evidence](evidence/ci-2026-10-29-code51-foundation-pass.json).
Same-commit ARM64 **40927895789** passed normal startup plus an actual synthetic
PE32 write fault: module 1 (Wow.exe), RVA **0x7f18**, access kind 1, exit
**0xC0000005**, **767 ms**, two AV trace records, zero dropped lines/overflow.
The fault case stayed failed; cleanup/source/runtime checks passed. The 56 Windows,
50 native guard, seven root-refusal and normal/Stop/kill ownership checks passed.
[ARM64 evidence](evidence/host-2026-10-29-code51-client-fault-pass.json).
No original game execution or physical code-51 result is claimed.

Code 50 **passed prefix setup in 100,368 ms**, all 60.24 GB client verification
and independent settings preparation. Its original client process was created,
then exited early with **0xC0000005** before host observation. No recorded pressure
stop or matching Android exit. Wine Stop, owned cleanup and source audit passed;
runtime posthash did not complete. [Assessment](evidence/android-2026-10-29-thor-code50-client-access-violation.json).
This report has no faulting instruction/module. Do not assert a specific root
cause or claim original startup passed. Cache eviction remains unmeasured.

Code 51 adds bounded process-correlated Wine 10.13 SEH/module-load tracing only
to original startup. Fixed numeric role/range/access/exit facts are exported;
PIDs, raw logs, private paths/settings, register/stack contents are excluded.
No debugger attaches and no original code or memory policy changes. PE headers,
lines and process/module state are bounded; strict Android validation rejects
malformed/inconsistent fields. Unknown or incomplete traces cannot claim a
location candidate. The last observed exception can have been handled; matching
exit codes do not prove fatal causality (`fatalExceptionLocationProven=false`).
Normal event/terminal-marker capture continues beyond the raw trace prefix cap.
[Design and roles 0..7](client-original-startup.md#bounded-fault-observations-from-code-51).

**Next:** install in place, 5 GiB free internally, airplane mode with Wi-Fi off,
launcher visible and powered. Tap **Test original client startup** once and export
after completion/failure/Stop before retrying (or export if disabled). Allow
40–60 minutes; no game window. A repeated failure is useful for the new fault
location. No repeated attempts, reimports, clear data, settings/cache deletion,
Finish, separate Verify or passed standalone gates. All data/receipts remain.
Original startup/rendering/login remain unqualified. Future code **>=52**.

## Superseded delivery: code 50 prefix first

**50 / 0.17.0-prefix-first** is signed and saved. [APK, sources and one device test](code50-recovery.md).
APK **60,681,409 bytes**, SHA256
`682141dc4f1a59805b650513341a7ce96e85f620472542af18c4ee03e4115aaa`.
All **247 package checks**, persistent v2 signing and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 49 byte-for-byte.
The exact code-46 server companion remains valid; no native delivery rebuild.
[Packaging proof](evidence/android-2026-10-29-private-prefix-first-apk.json).

Application **490a9cf7bfa7234b5ee98426fb0a9b9c79e7f0b6** passed foundation
**40908106621**: **214 tooling**, **128 qualification** (no skips), **593 Android
passed / one existing skip**, lint/assembly, native ownership, cache/JNI and full
runtime installation/rehash with 96 MiB heap. [CI evidence](evidence/ci-2026-10-29-code50-foundation-pass.json).
Same-commit ARM64 **40908106926** passed the new two-invocation prefix/startup
fixture, all 56 Windows checks, 50 native guard checks and owned process cleanup.
Prefix took **42,909 ms**, with **1,750 accepted cache requests / zero errors**.
[Host evidence](evidence/host-2026-10-29-code50-prefix-first-pass.json).
These are synthetic host observations, not Thor/original-client qualification.

Code 49 fully verified all client files and copied settings, then stopped during
Wine setup after **36m27s**. Active critical trim + measured full PSI **0.36%**
exceeded the **0.2%** ceiling for critical grace. No matching Android kill;
owned cleanup/source audit passed. [Assessment](evidence/android-2026-10-29-thor-code49-prefix-pressure.json).
It had generated 1.07 GB of prefix files; cache pressure is plausible, not proven.

Code 50 prepares Wine with empty client bindings before the long client hash,
then fully reaps that owned tracer. Only successful setup reaches full client
verification and the second original-startup invocation. Bounded descriptor-
relative NOFOLLOW cache maintenance syncs/advises disposable prefix files,
exports fixed counters and joins before setup can pass. Accepted advice is not
measured eviction. Code-49 memory limits, source protection, settings, leases,
cleanup and full verification remain. Already allocated prefix bytes are not
reserved twice. [Design](client-original-startup.md).

**Next:** install in place, keep 5 GiB free internally, airplane mode with Wi-Fi
off, launcher visible and powered. Tap **Test original client startup** once.
Prefix setup now runs after runtime prehash, before the expensive client hash.
If setup passes, allow roughly 40–60 minutes for the complete attempt. Export
after completion/failure/Stop before retrying, or export if disabled. No game
window is expected; physical original startup/rendering/login remain unqualified.
Do not reimport, clear data, delete imported settings/cache, repeat Finish, tap
separate Verify or repeat passed standalone gates. Preserve all data/receipts.
Future updates require code **>=51** and the persistent signer.

## Superseded delivery: code 49 native pressure confirmation

**49 / 0.16.3-pressure-confirmation** is signed and packaged.
[APK, sources and one test](code49-recovery.md). APK **60,677,313 bytes**, SHA256
`1f10af52686d4a796e0d4434bd717f242c1673f11ecf0544da773eb3de4482d5`.
All **247 package checks**, persistent v2 signing and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 48 byte-for-byte.
[Packaging proof](evidence/android-2026-10-29-private-pressure-confirmation-apk.json).
No native rebuild; the exact server companion from code 46 remains valid.

Application **41055bcde585997333ab56202e88850d45d4f7e3** passed foundation
**40894673034**: **214 tooling**, **122 qualification** (no skips), **590 Android
passed / one existing skip**, lint/assembly, native ownership, cache/JNI and
full runtime installation/rehash with 96 MiB heap. [CI evidence](evidence/ci-2026-10-29-code49-foundation-pass.json).
The guarded native/observer/ownership code and host harness match code 48's
qualified sources exactly. Its [ARM64 evidence](evidence/host-2026-10-29-code48-guarded-observer-pass.json)
is retained for those unchanged components; no new guarded host run is claimed.

Code 48 physically completed full verification of all 10,476 client files /
60,243,212,332 bytes, copied 112,062,202 settings bytes and prepared the isolated
view. Its settings-budget fix worked. After **2,257,840 ms (37m38s)** it failed
during prefix initialization on a level-15 critical trim aged 585 ms, with
8,570,064,896 available bytes and systemLowMemory=false. No matching Android
kill. Owned cleanup and source metadata audit passed; original Wow.exe never
started and runtime posthash remains incomplete.
[Report assessment](evidence/android-2026-10-29-thor-code48-prefix-memory-stop.json).
Native PSI was not recorded, so a spurious warning or absence of actual
system/cgroup pressure cannot be inferred.

Code 49 uses the same bounded PSI sampler for I/O and native monitoring.
Measured pressure still stops immediately. A running-critical callback alone
receives at most ten seconds from its original episode start only with available
memory >=max(existing floor, total/3), PSI some <2% and full <0.2%; missing PSI
refuses grace. Repeated callbacks cannot extend the window. Severe callbacks and
onLowMemory remain immediate and cannot be downgraded by later milder callbacks.
The monitor never sleeps its native guest; cancellation and ownership cleanup
remain active. Seven new regressions cover all policy boundaries and recovery.
[Exact policy and limits](client-original-startup.md#native-memory-confirmation-from-code-49).

**Next:** install in place, keep 5 GiB free internally, airplane mode with Wi-Fi
off, launcher visible and powered. Tap **Test original client startup** once;
allow 40–60 minutes plus prefix checks. A new session requires a fresh source
verification under its lease; no separate Verify is requested. Export after
completion/failure/Stop before retrying, or export if disabled. No game window
is expected. Physical original startup, rendering and login remain unqualified.
Do not reimport, clear data, delete imported cache/settings, repeat Finish or
rerun passed standalone storage/runtime/server gates. Future code >=50.

## Superseded delivery: code 48 settings budget

**48 / 0.16.2-settings-budget** is signed and packaged.
[APK, sources and one test](code48-recovery.md). APK **60,677,313 bytes**, SHA256
`d152797c5046abbecd5edcfb2931eddf67916551f07c50b39a5d4b182d74d227`.
All **247 package checks**, persistent v2 signing and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 47 byte-for-byte.
[Packaging proof](evidence/android-2026-10-29-private-settings-budget-apk.json).
No native rebuild; the exact server companion from code 46 remains valid.

Application **161720f9cea06e6fcb52374c3634ea673ec75b5b** passed foundation
**40884250737**: **214 tooling**, **122 qualification** (no skips), **583 Android
passed / one existing skip**, lint/assembly, native ownership, cache/JNI and full
runtime installation/rehash under 96 MiB heap. [CI evidence](evidence/ci-2026-10-29-code48-foundation-pass.json).
Same-commit ARM64 **40884250533** passed synthetic startup observation, guarded
Wine, native storage and owned tracer cleanup. [Host evidence](evidence/host-2026-10-29-code48-guarded-observer-pass.json).

Code 47 physically failed before Wine at `client-preflight / view-copy-limit`
after **127,186 ms**. The new aggregate costs identify **112,058,085 bytes /
4,491 files in WTF**, plus **4,117 configuration bytes**, above its 64 MiB cap.
Cache exclusion worked (1,113,628 bytes); it was insufficient to fit settings.
Runtime prehash passed, worker inactive, no pressure pause or matching exit.
[Report assessment](evidence/android-2026-10-29-thor-code47-settings-budget.json).

Code 48 preserves every setting in independent copies, raises their ceiling to
256 MiB, and freezes a separate 128 MiB output allowance above the prepared view
before native entry. The initial view is bounded by the copy cap plus at most
1 MiB for launch settings. Five-second polling and a terminal check enforce the
total; it is not a filesystem quota. Prefix, free space, 256 KiB I/O buffers,
source audits and ownership remain unchanged. Synthetic regressions reproduce
112,062,202 copied bytes / 4,491 WTF files, verify copy/source independence,
reserve refusal and exact session-output boundaries. No private file content
was used in the fixture.

**Next:** install in place, keep 5 GiB free, airplane mode with Wi-Fi off,
launcher visible and powered. Tap **Test original client startup** once. Allow
roughly an hour for fresh full client verification plus runtime/prefix checks.
Export after completion/failure/Stop before retrying; if disabled, export instead.
No visible game window is expected. Physical original startup/rendering/login
remain unqualified. Do not reimport, clear data, delete imported cache/settings,
repeat Finish or rerun passed storage/runtime/server gates. Future code >=49.

## Superseded delivery: code 47 working-view budget

**47 / 0.16.1-view-budget** is signed and packaged. [APK, sources and one test](code47-recovery.md).
APK **60,677,313 bytes**, SHA256
`45ecddaad7abe3ae6aafc95da0890ac63744cf2c554fec166ef8c6a481e484f1`.
All **247 package checks**, persistent v2 signing and 16 KiB alignment passed.
All **25 native payloads / 148 runtime entries** match code 46 byte-for-byte.
[Packaging proof](evidence/android-2026-10-28-private-view-budget-apk.json).

Application **272fb07dc779e66cb163be42941f9dcaa096fad7** passed foundation
**40810151183**: **214 tooling**, **122 qualification** (no skips), **581 Android
passed / one existing skip**, lint/assembly, native ownership, cache/JNI and full
runtime installation/rehash under 96 MiB heap. [CI evidence](evidence/ci-2026-10-28-code47-foundation-pass.json).
Same-commit ARM64 **40810151216** passed the synthetic startup observer, guarded
Wine, native storage and owned tracer cleanup gates.
[Host proof](evidence/host-2026-10-28-code47-guarded-observer-pass.json).

Code 46 failed at `client-preflight / view-copy-limit` after **130,618 ms**.
Full runtime precheck passed; client full verification, view creation and Wine
had not started. No pressure pause or matching Android exit. Earlier passes
remain intact. [Assessment](evidence/android-2026-10-28-thor-code46-view-copy-limit.json).
The report cannot identify which mutable subtree exceeded the 64 MiB cap.

Code 47 makes Cache/Logs/Errors/Screenshots fresh independent session folders,
preserving their original imported content and every source verification/audit
entry. WTF/configuration copies still use the 64 MiB limit. Complete fixed-group
costs are recorded before budget refusal. Regressions cover oversized sparse
historical outputs, preserved source/settings, source mutation, settings limits,
unsafe roots and the unchanged retained code-43 fixture plan identity.
The server source companion from code 46 remains exact; no native rebuild.

**Next:** update in place, 5 GiB free, airplane mode with Wi-Fi off, launcher
visible, tap **Test original client startup** once. Allow about an hour for the
full client verification plus runtime/prefix checks; export after completion,
failure or Stop before retrying. No visible game window: this is still a bounded
headless startup test. Original-client startup/rendering/login remain physically
unqualified. Do not reimport, clear data, delete imported caches, repeat Finish
or rerun passed storage/runtime/server tests. Future updates require code >=50.

## Superseded delivery: code 46

[Code-46 delivery and sources](code46-recovery.md) remain recovery history.
Its exact APK `5b93c145bf067ee64034ecc81e0db8593fe6a5d2a8dfdedb245c40b0a18d1c66`
failed preflight as assessed above. The original-client diagnostic, observer
contract and source protection remain in code 47, with the working-session
output policy fixed. Do not retry code 46. Retain its returned failure report.

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
APK. Future updates use code >=50. [Code-49 recovery](code49-recovery.md) identifies
the exact bridge/application source and refreshed reproduction companion for
the unchanged v15 native server. Both passed the strict packaging verifier. The code changes and measured evidence are pushed.

Native recovery: [code32 baseline](code32-recovery.md), then the reviewed 0019
seller-count patch/v15 relinks from [code34](code34-recovery.md). Restore pinned
NDK/timestamps and inspect actual sources/build inventory before Ninja. Native
builds use one job by default/max two after memory checks; never concurrent
heavyweight builds. Do not rebuild unchanged native payloads for Java/Kotlin fixes.
