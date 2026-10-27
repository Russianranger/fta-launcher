# Code 40 — bounded cold Wine initialization

The [exact code-39 Thor report](evidence/android-2026-10-27-thor-code39-prefix-timeout.json)
matches the delivered APK. The real socket bind, display startup, normal/Stop/
forced-death ownership and full 2,637,799,241-byte runtime pre-rehash passed.
The next stage, `prefix`, reached its fixed 180-second deadline (status 124).
Total elapsed time was 305,961 ms; deliberate I/O pacing was 80,171 ms. The report
has 11,095,248,896 available-memory bytes, no pressure pauses and no matching
Android exit. This is not evidence of another low-memory kill.

Code 39 cannot distinguish slow first initialization from a persistent Wine
startup hang. It has no prefix progress metadata and discards diagnostics after
the first 256 KiB. Do not claim a demonstrated underlying Wine defect or that
an increased deadline alone fixes Android execution.

Code 40 uses `wine wineboot.exe -i` for the always-fresh, disposable test prefix.
The exact original [Wine source at the pinned commit](https://github.com/wine-mirror/wine/blob/a6844d10622fc1a973ec1f22fc4f78a0fcd6cb29/programs/wineboot/wineboot.c)
defines `-i` as first-instance initialization; `-u` forces prefix updating.
Both paths retain Wine's original installation and architecture handling.
No registry, DLL, CPU feature or game asset is manufactured or patched.

Cold initialization now has a finite 600-second budget. The entire guest suite
has 1,200 seconds, with a further 60-second service allowance covering ownership
and cleanup. Every other stage retains its prior deadline and exact success
criteria. Stop and native memory-pressure cancellation remain active.

Every 15 seconds the guest emits only elapsed milliseconds, bounded directory
entry counts for system32/syswow64, summed sizes of three regular registry hives,
and an update-marker presence bit. No filenames or registry contents are exported.
The Android handle owner observes progress once per second, and the UI shows the
initialization time. These observations never qualify a stage or gameplay.
The registry/marker metadata can predate successful initialization; the actual
zero exit and subsequent CPU-registry/mixed-process gates remain mandatory.

Only `trace+wineboot` is enabled for initialization. A fixed-size rolling window
classifies allowlisted late/split diagnostics even when the retained prefix is
full. Raw text and arbitrary paths remain private. Marker validation still uses
the original 256 KiB retained bound and never accepts a marker outside it.

The command monitor also stops waiting once its direct command exits, drains at
most 256 KiB of final available output, and preserves the actual exit status.
A daemon's inherited, noisy output pipe can no longer prolong a completed
command until timeout. Closed output from a still-running command continues to
receive progress observations and the deadline; it cannot cause a false pass.

Regression coverage includes genuine slow success versus deadline expiry,
closed output with a still-running child, a noisy inherited pipe after parent
exit, late/split diagnostics, bounded metadata with symlink rejection, redacted
Android parsing and outer timeout/cleanup. Exact-commit Android assembly and
the full ARM64 PRoot/Wine suite must pass before delivery. Linux results do not
qualify this change on Android.

The physical test is one **Test client runtime** on an in-place code-40 update,
followed by **Export diagnostic report** on either outcome. Keep the launcher
visible and allow up to ten minutes for initialization. No preparation download,
client/Data.zip import or completed server gate needs repeating. The previous
attempts stay intact under the existing three-attempt limit.
