# World log pressure and shutdown fault evidence

Physical code 29 passed the original world challenge and two combined healthy
samples, then hit the unchanged 8 MiB stdout cap. During cleanup the original
world printed Main.cpp:420's `Halting process...` and subsequently exited 139.
Its stderr was empty. This is separate from the now-resolved original RC4 assertion.
[Redacted measurements](evidence/android-2026-10-24-thor-code29-runtime.json).
Neither the instruction nor the responsible cleanup object is known. Do not infer
it from the last module message or change gameplay/cleanup behavior speculatively.

## Original logging policy

The original enum orders FATAL=1, ERROR=2, WARN=3, INFO=4. Actual retained world
sessions now generate root level 3, a level-4 file appender and level-2 console
appender. `server.loading` and `server.worldserver` retain level-4 file output for
DBC, ready, freeze and halting milestones. Every original warning/error remains
in the file; unrelated routine INFO is suppressed. All 49 original module config
files remain unchanged. Updater-only invocations retain their previous profile.

`tools/check_world_logging.py` compiles the original Logger, Appender, LogMessage
and Timer implementations without modifying them. Fifty thousand synthetic INFO
messages produce no routine output; warnings, errors, fatal messages and all four
required lifecycle markers remain. The fixture verifies source message categories.
[Host proof](evidence/host-2026-10-24-world-logging.json). This does not measure
future Thor log volume; the same 8 MiB limit still fails closed.

The live scanner uses 8 KiB reads and 4 KiB maximum lines, reads at most 8 MiB,
rejects symlinks and never accepts partial/oversized lines as milestones. It records
world-file milestones before checking stdout/stderr limits. Terminal log export
still requires stable, bounded, attempt-matched files and exports fingerprints only.

## Independent fatal-signal witness

`native/world-fault/world_fault.c` is a new MIT diagnostic helper, not a replacement
worldserver. `manifests/world-fault.json` binds its source, compiler, bytes and ELF.
It is verified before realm opening and preloaded only into the actual world role.
No other role receives `LD_PRELOAD`; inherited overrides are refused. Its READY
marker is mandatory before accepting the world challenge.

At process initialization it records executable ELF load ranges, then installs
SIGSEGV/SIGBUS handlers while preserving the existing disposition. Its capture
performs one bounded write with signal/code and image-relative PC/LR, restores the
prior action and chains the original handler with the original siginfo/context.
Default actions receive the same fatal signal; one-shot semantics remain intact.
Explicit ignored fatal signals are refused. The capture itself does not unwind,
allocate, resolve symbols or use stdio/locks. The chained platform handler retains
its own existing crash-report behavior. SIGTERM/SIGABRT are untouched. The helper
does not open application files or issue SQL. An existing alternate stack is
retained; otherwise the helper installs one for the initializing thread. Other
native threads retain their existing stack arrangements. This is not a general
crash reporter.

The parser accepts exactly one complete READY and fault line, only known image IDs,
offsets bounded to 512 MiB, and an attempt-matched native exit of 128 + signal.
Unknown addresses have image 0 and offset 0. Duplicates, malformed records, partial
lines, wrong exit status and raw paths cannot qualify evidence. Raw addresses,
register dumps, memory contents and private log text are never exported.

| Image ID | ELF identity |
| --- | --- |
| 0 | Unknown; offset zero only |
| 1 | Original worldserver |
| 2 | Retained libcrypto.so.3 |
| 3 | Fault witness |
| 4 | libmariadb.so |
| 5 | libssl.so.3 |
| 6 | libreadline.so.8 |
| 7 | libz.so.1 |
| 8 | libc++_shared.so |
| 9 | libandroid-support.so |
| 10 | libcrypt.so |
| 11 | libedit.so |
| 12 | libncursesw.so.6 |
| 13 | libpcre2-8.so |
| 100–103 | Android libc, libdl, libm, linker64 |

Only initial executable ranges are captured. Later-loaded modules may be unknown.
READY proves constructor execution, not that another library never changes the
handler. PC/LR are limited clues, not a stack trace or proof of root cause. A missing
witness remains missing evidence. Any helper/setup failure is a blocker, not a
reason to bypass the gate.

`tools/check_world_fault.py` builds with one compiler job after a memory check.
Real host SIGSEGV and SIGBUS preserve fatal status; normal exit, SIGTERM and
SIGABRT are unchanged. Five ASLR-separated fault runs yield the same ELF offset,
which matches the fixture's actual `fault_here` symbol. The Android helper uses
NDK r28c/API33, needs only libc/libdl and has 16 KiB LOAD alignment.
[Corrected host/Android build proof](evidence/host-android-2026-10-24-world-signals.json).
A main-executable preinit fixture installs handlers before the preload constructor,
as Android does. It verifies unchanged startup, fatal status, original signal info,
context, signal mask and alternate stack, with traditional/SA_SIGINFO/one-shot
handlers. The original code-30 helper exits 126 on this fixture; code 31 passes.
No Android execution is claimed from these host checks.

For the next report, match image 1 against the unchanged private world ELF SHA256
`97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766`.
It retains `.symtab`, but no DWARF. Feed the exported ELF-relative offset to the
NDK `llvm-symbolizer --obj=<verified-private-ELF>` or inspect `llvm-nm -nC`; never
add a guessed process load address. Other images require their exact APK hash.
A symbol boundary may identify the function without proving a source line/cause.
Keep private binaries and detailed symbolization logs outside Git; record only the
minimal source-backed diagnosis. Rebuild original code only after that evidence.

Code 31 does not qualify native GT fallback, clean world shutdown, the required
120-second screen-off interval or gameplay. Follow the current handoff for the
single combined physical gate; preserve the retained realm and backup.

## Android startup correction and provenance

Code 30 was withdrawn before a Thor test. Android 13 installs its debuggerd handler
before the shared-library constructors; requiring SIG_DFL would block startup.
Code 31 chains that existing behavior. In particular, it restores the prior action
before calling debuggerd because debuggerd itself checks the installed SA_SIGINFO
flag. It does not replace platform crash collection with a guessed default exit.

Inspected AOSP tag `android-13.0.0_r1` files (downloaded bytes, SHA256):

- [linker_main.cpp](https://android.googlesource.com/platform/bionic/+/refs/tags/android-13.0.0_r1/linker/linker_main.cpp): `85c3a9b70b85f4d3644046ad16ff79c1d5b1024c2fd7242db43960930beafce6`; debuggerd initialization precedes constructors.
- [debuggerd_handler.cpp](https://android.googlesource.com/platform/system/core/+/refs/tags/android-13.0.0_r1/debuggerd/handler/debuggerd_handler.cpp): `79e2199b5ef181c12ea74bd8d0f067d46f5ca8bc12c19b95f8a097038e761b53`; handler registration, `have_siginfo`, and original fatal re-delivery.

These platform sources are references, not copied implementations or claims that
the host fixture executes Android debuggerd. Actual Thor execution remains pending.
