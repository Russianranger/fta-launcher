# Code 39 — short native socket paths and faster bounded I/O

The [code-38 physical report](evidence/android-2026-10-26-thor-code38-client-probe-failure.json)
identifies the exact delivered APK. The runtime probe failed after 196,339 ms:
CPU topology status 0, display status 124; 130,011 ms were deliberate I/O pacing.
Available memory was 9,020,596,224 bytes, peak reported worker RSS 200,664 KiB,
zero pressure pauses and no client-runtime process exit. This is not a measured
low-memory kill. Code 38 combined early display exit and timeout into 124 and
suppressed display stderr; its report cannot distinguish those cases.

Under the standard Android user-0 layout, the old temporary path is 105 bytes; appending `/.X11-unix/X8` requires
118 bytes. The unchanged pinned PRoot `src/syscall/socket.c` translates the guest
socket pathname into `sockaddr_un.sun_path` (108 bytes). Its long-path fallback
calls `create_temp_name(..., "proot")` from `src/path/temp.c`; that creates another
path under the same PROOT_TMP_DIR, so it is also too long and returns EINVAL.
The host gate now reproduces this exact pathname budget, requires errno 22 for
the old layout and success for the short layout, then runs all ten synthetic
stages and the complete runtime pre/post rehash. This isolates a concrete path
failure; the corrected Android display still requires a physical result.

Code 39 keeps the full random attempt identity but uses `files/cr/<UUID hex>/tmp`.
The standard user-0 package path becomes 79 bytes (X8: 92 bytes). Before
launch, UTF-8 byte checks cover both X8 and the original PRoot fallback with a
maximum signed PID and NUL. Legacy attempts remain intact and count toward the
same three-attempt limit. No bridge/native binary, runtime archive, client file,
realm, database or source-derived validation rule changes.

The guest first tests a real filesystem Unix socket. X output is drained with a
256 KiB retained bound and exported only as fixed diagnostic categories. An
actual display exit, timeout and socket-bind failure are distinct. Completed
ownership gates and pre-rehash evidence survive a later failure without claiming
Wine/client qualification. All native handles still stop in finally.

Aggregate client reads, writes and readbacks now have a 48 MiB/s ceiling, up from
24 MiB/s. The same 256 KiB buffers, 1 MiB fsync/readback/cache-advice windows,
reserve, low-memory/PSI/trim thresholds, recovery hysteresis and Stop remain.
This permits higher throughput; it does not promise a measured 2x device speed
or immunity to Android kills. The next probe measures the new retained rehash.

Regression scope: real long/short paths under unchanged native PRoot; display
exit/timeout/output bound/redaction; path byte/fallback bounds; partial ownership
evidence on Wine failure; and pressure blocking at the increased I/O rate.
The local host denies Unix sockets, so two live Python socket tests must pass in
CI; they are not replaced by mocks or a weakened runtime rule.

Deliver only a qualified, persistently signed code 39 update. Install in place,
keep the launcher visible, run **Test client runtime** once and export the report
immediately on failure before retrying. Do not prepare/reimport again. The full
WarcraftXL source is not required for this synthetic gate. Original client,
graphics and login remain unqualified.

Qualified application commit **51358069f8d958c5e05b343cd6bf1d22be4f13f9** passed
foundation CI 40444054488 and ARM64 CI 40444054525. [Measured host evidence](evidence/host-2026-10-26-client-socket-repair-pass.json)
records old-path EINVAL, fixed-path success, all ten stages and complete unchanged
runtime rehashes. The same-key [code-39 APK and sources](code39-recovery.md) are
delivered. Android display recovery and higher-rate memory behavior remain pending.
