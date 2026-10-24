# From the Ashes Android — current handoff

Only `Russianranger/fta-launcher`, **main**. Owner authorizes qualified commits and
pushes. Target AYN Thor, Android 13, ARM64. Read `AGENTS.md`, this file,
`game-data-import.md`, `private-diagnostics.md`, `native-world-gate.md`.
Earlier history: [DEVELOPMENT-HISTORY.md](DEVELOPMENT-HISTORY.md).

## Current increment — world logging and shutdown evidence

Starting remote HEAD **cef17c93226087964b835cfcab65da13e445b806**; exact CI
[40125171817](https://github.com/Russianranger/fta-launcher/actions/runs/40125171817)
passed all three jobs. The starting private APK was code **29**,
`0.12.2-world-crypto`, SHA256
`93c08fabf51fd5a1e35503b101c953f6ed280d7d5a650cfa5d232cae5e09d62b`.
Application commit **974ea36bd4a2de5fe2a8d2971cfe62452046b3aa**;
[APK evidence](evidence/android-2026-10-24-private-world-crypto-apk.json).

**Physical code 29 passed the original world challenge and two combined healthy
samples.** Report `FTA-database-diagnostic-1792874414598.json`, SHA256
`3dbbecb62c63e72867f79b5ceedbdee05443cb0ba8ac7b7446671c77d6055497`.
The RC4/provider correction worked. Automatic retained rehash passed all **22,817
files / 7,625,943,031 bytes**, all 24 metadata prechecks and ten GT coverage queries
passed, and the original world reached 113 DBC stores, ready and freeze guard.
[Measured physical evidence](evidence/android-2026-10-24-thor-code29-runtime.json).

The primary failure was **stdout reaching the unchanged 8 MiB limit** after
10,312 ms of measured health. During cleanup world exited **139**, after 53,331 ms
total, with empty stderr. Retained world-log fingerprint `w0132` independently
identifies original Main.cpp's **Halting process** message before that crash.
The old live scan missed it because it checked stdout's cap first. That message
proves entry into late cleanup, not clean exit or a particular fault instruction.
Auth exited zero; database exited zero without observed SQL shutdown. Postchecks
were not reached; false results are unmeasured, not proof of lost realm data.
Screen-off duration is zero. Native gate, GT fallback and gameplay remain unqualified.

**Private code 30, `0.12.3-world-shutdown`, is qualified, signed and saved.**
Application commit **c8e51358452c3452df9fffe058748a6b783b4f63**; exact CI
[40132133219](https://github.com/Russianranger/fta-launcher/actions/runs/40132133219)
passed all three jobs. Only documentation/evidence follow this application commit.
APK `FTA-Launcher-0.12.3-world-shutdown.apk`, **42,283,996 bytes**, SHA256
`37295978f21fca4819f948e606b71ba605dab249cb63689f8e16e1643ad66efe`.
[APK evidence](evidence/android-2026-10-24-private-world-shutdown-apk.json).
Saved private file ID: `libfile_2068edc56dac819180d1eabde38de722`.
All **223** packaged-input checks, persistent-certificate v2 signature and 16 KiB
alignment passed. This APK has **not run on Thor**. Public CI APKs are not this update.

It uses original logger thresholds to retain all WARN/ERROR/FATAL messages plus
required lifecycle INFO, with ERROR/FATAL console output. The 8 MiB limit remains.
A bounded live scanner records complete lifecycle lines before checking other
streams. An independently pinned, 13,664-byte ARM64 fault witness is preloaded only
into world. It reports fixed image IDs and executable-relative PC/LR offsets for
SIGSEGV/SIGBUS, then preserves the fatal signal. No memory, raw addresses, paths,
credentials or log text is exported. It does **not** fix the cleanup crash.
[Design, qualification and symbolization](world-shutdown-diagnostic.md).

The original worldserver, crypto provider and all 18 code-29 native files remain
byte identical. The new witness makes 19 native files. All 148 runtime-manifest
entries, provider/backup identity, original SQL/configurations and game assets
remain unchanged. All 52 modules, C++20 and NPCBots are preserved. Source companion
v8 remains valid for the unchanged original binaries; the new MIT helper source
is public here and its license is copied into APK metadata.

Qualification: **174 tooling tests**, **375 Android unit tests in 35 classes**
(source test inventory, no ignored tests; exact CI test task passed), lint/assembly,
original logging pressure fixture and real fatal-signal host fixture passed.
NDK r28c/API33 witness build passed ELF/dependency/16 KiB checks. The private package
retains all 18 donor native payloads and all 148 runtime-manifest entries exactly.
Host signal tests are not Thor evidence. Local full Gradle was not run.
`databaseFallbackValidated=false`, world readiness and game login remain false.

## Next physical gate

Install the private code-30 APK **in place** over code 29. Keep all existing
realm/database/backup/assets. Select **Start native world test** once and keep the
launcher visible until healthy. Turn the screen off for **three minutes**, reopen,
select **Stop**, then **Export diagnostic report**. On failure export immediately
before another attempt. No manual import, manual rehash or already-passed database/auth
test is needed. The automatic full rehash binds its new asset lease. Never uninstall
or clear storage. Public CI APKs are not private native updates.

Expected archive: `407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225`.
Expected catalog: `0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff`.
Persistent certificate: `3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
Recover `FTA-Launcher-Development-Signing.zip`; never regenerate or publish it.

## Preserved milestones and limits

Host original DBC loaders/ResultSet/Field, Connector/C 3.4.9 and isolated exact
MariaDB 12.3.2 restored Thor backup loaded all **8,922** GT floats bit-for-bit; ten
empty-table negatives and ASan passed. Strict metadata/data/backup stayed unchanged.
[Live evidence](evidence/host-2026-10-24-live-dbc.json). Host adapters are not Android
execution. The original 44-byte challenge probe sends zero application bytes.
Physical database/updater, persistent realm, backup/restore, process-death recovery,
local auth and Stop/restart gates already passed. The earlier 112,127 ms screen-off
interval remains short of 120,000 ms; qualify it during the native world gate.
Client import, WarcraftXL and playable login are later milestones. Deferred
Attriboost Settings field 8 vs 9 bug remains gameplay work.

Native builds: one job by default, at most two after memory checks; no concurrent
heavy builds. Keep logs/private binaries/assets/SQL/credentials outside Git. Run
Python, Android tests/lint/assembly, package/signature/16 KiB checks and exact CI.
