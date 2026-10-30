# Live pressure confirmation — code 55

The physical code-54 attempt failed after **48,479 ms**, during disposable prefix
preparation, before launching either original process. The reason was `psi-full`:
the final ten-second averages were some **2.28%**, full **2.07%**. Android reported
8,418,881,536 bytes available, a 3,199,219,302-byte floor, no system low-memory flag,
and a running-moderate trim (5), not a critical trim. Prefix cleanup completed.
There is no attempt-matched Android exit. The report contains only the terminal
averages; it cannot establish whether stalls were ongoing or had subsided.
[Measured assessment](evidence/android-2026-10-29-thor-code54-prefix-pressure.json).
This attempt supplies no new evidence about the code-53 post-host failure.

Linux [PSI documentation](https://docs.kernel.org/accounting/psi.html) distinguishes
the ten-second trend from cumulative microsecond stall counters. Code 55 reads
both from the same bounded, read-only `/proc/pressure/memory` sample. It derives
fresh some/full rates from counter deltas over **500–2500 ms**; the native monitor
still samples once per second without sleeping or suspending the guest.

## Policy

- Android low-memory, available memory below the existing floor, and severe trim
  callbacks still stop immediately. The existing critical-trim and sustained
  advisory limits remain; confirmation cannot mask either.
- Some >=20% or full >=5% in either the average or fresh interval stops immediately.
- The existing moderate triggers (some >=10%, full >=1%) start at most **3 seconds**
  of confirmation only when both averages and valid counters exist, memory is at
  least `max(floor, total/3)`, and there is no critical callback. A first valid
  counter sample can establish the baseline; subsequent intervals must be fresh.
- A fresh quiet interval (some <2%, full <0.2%) clears the episode even if avg10 is
  still elevated. Intermediate rates do not reset its deadline. Ongoing pressure
  fails at the deadline. Missing/reset/inconsistent counters, a stale interval or
  insufficient headroom cannot establish recovery during an episode.
- Fresh intervals can also detect stalls before the average reaches its trigger.
  Counter arithmetic is bounded and rejects decreases, impossible deltas, and
  malformed input. Only rates/window length/episode count/maximum elapsed time
  are exported; raw proc text and cumulative counters are not.

These are explicit application policy choices, not Android LMKD defaults or proof
that the physical code-54 event was harmless. No kernel/LMKD setting changes.
The shared file-I/O governor retains its existing pressure pauses, buffers,
hashes and **240 MiB/s** ceiling. Original startup pass criteria, original client
binaries, runtime, x87 profile, retained receipts, imports and realm are unchanged.

## Qualification and next gate

Regression tests replay the exact code-54 terminal sample without inventing its
unobserved history. Without counters it still fails. Independent synthetic
histories test quiet recovery, sustained stalls, severe spikes, early detection,
hysteresis, critical/advisory callbacks, missing observations, counter overflow
and stale intervals. These qualify policy behavior, not physical recovery.

Run the exact-commit foundation checks before packaging with the persistent
signer. No native/Windows observer sources changed, so retain code-54 ARM64
qualification rather than rerunning its unchanged standalone gates.

Next on Thor: install in place, run **Test original client startup** once, and
export JSON on completion/failure/Stop before retrying. Keep 5 GiB free, power
connected, airplane mode with Wi-Fi off, launcher visible. Allow 8–10 minutes
plus pressure pauses. Inspect the new fresh PSI fields if prefix preparation
stops; otherwise inspect code-54 process exit and fault observations. No reimport,
clear data or passed standalone-gate repeats. Original startup, rendering and
gameplay remain unqualified.
