# Code 41 — native memory monitoring and bounded retries

The physical code-40 report stopped with `memory-pressure` while first-prefix
initialization was progressing. The native monitor immediately rejected any
active ten-second trim signal, even with healthy available memory. Unlike the
I/O governor, it had no recovery allowance. Worse, it checked before recording
the triggering sample, leaving stale available-memory and low-memory fields.
The report cannot identify whether that specific abort was trim, lowMemory or
the available-memory floor. It does not establish an Android low-memory kill.

Code 41 samples the native guest once a second without blocking its Java monitor.
Measured `lowMemory`, the unchanged available-memory floor (max of 2 GiB,
20% total and twice Android's threshold), critical trim (15..19 or >=80) and
`onLowMemory` still stop immediately through owned process cleanup. Other trim
signals receive at most 30 seconds of continuous advisory allowance while
measured memory remains healthy. A single signal expires after ten seconds;
repeated signals cannot extend the continuous allowance past 30 seconds.
UI-hidden levels 20..39 do not count as pressure. Critical callbacks cannot be
downgraded by a later advisory callback. No thread sleep pretends to suspend a
still-running guest. Stop, guest deadlines and storage reserve remain enforced.

The exact current memory sample, floor/threshold, fixed pressure category,
trim level/age and active/critical flags are saved **before** a pressure abort.
The I/O governor retains its PSI/trim backoff, five-second recovery interval,
two-minute ceiling, 48 MiB/s limit and bounded buffers/windows. Optional PSI
fields are removed when not sampled rather than carrying stale I/O values into
native monitoring. No raw logs, paths, commands or personal data are exported.

Android callback semantics: [official ComponentCallbacks2 reference](https://developer.android.com/reference/android/content/ComponentCallbacks2).
These semantics justify distinguishing critical, advisory and UI callbacks;
they do not identify the missing code-40 callback after the fact.

The original cap allowed three accumulated attempts, which codes 38–40 may
already occupy. Code 41 permits a fourth directory under the same exclusive
runtime lease and retains all legacy/unproven attempts. New attempts receive
a durable retirement receipt only after all ownership gates have passed and
every acquired native handle has completed controlled cleanup. An outer cleanup
also covers an exception after native start but before returning its handle to
the protocol. A later probe removes only positively retired directories, never
follows Wine/DOS symlinks, and reuses the fourth slot. Uncertain/crash-interrupted
work stays intact and counts against the cap; `probe-attempt-limit` now identifies
that condition separately from insufficient free storage. A partial receipt
cannot authorize deletion. The completed attempt's files remain until the next
probe for local investigation.

Tests cover a healthy transient trim, immediate measured/critical pressure,
repeated trim, lifecycle callbacks, signal downgrade/expiry, pressure-triggered
owned cleanup, three-attempt recovery, uncertain fourth attempts, malformed and
symlink receipts, symlink roots and a Wine link into retained data. The ten-stage
guest test and pre/post full runtime rehash remain mandatory for qualification.
Code 41 still needs physical Thor validation; original game execution is not
yet qualified.
