# Bounded evidence recovery after the code-32 log limit

**Recovery completed on code 33.** The same attempt's world log is 8,535,490
bytes; its tail is dominated by the original aggregate conversion hint pair.
[Measured result and subsequent caller correction](auction-count.md).
The procedure below is historical; do not repeat this recovery before code 34.

Physical code 32 matches the delivered APK and repaired world hashes. It passes
64 receipt health samples (65 native probes), 672,534 ms elapsed and 651,866 ms
healthy screen-off. Full retained asset rehash, strict metadata/GT prechecks,
original 113 DBC stores, ready/freeze markers and world challenge pass.
The unchanged 8 MiB log limit ends the session during LOCAL_SERVICE_RUNNING.
World exits zero after 713,107 ms; auth and database also exit zero. No fatal signal
is captured. SQL shutdown and metadata/GT postchecks are not reached. This does
not qualify user Stop, database fallback or complete runtime readiness.
[Physical evidence](evidence/android-2026-10-25-thor-code32-runtime.json).

Code 32 exports stdout (1,042,275 bytes) and stderr (19 bytes), but rejects the
oversized world-file export. At that checkpoint its size/messages were unknown.
The previous readable code-31 world log had 9,416 warnings; the old 160-entry
startup dictionary did not identify those warning sources. No warning is assumed
benign, suppressed or fixed without its measured source evidence.

## Code-33 implementation

The terminated-attempt exporter retains the exact attempt/journal/visit binding,
no-running-process condition, no-follow file access and before/after identity,
size and modification-time checks. It never starts a process or writes a log.
Logs at or below 8 MiB retain complete-file hashes. For larger files it reads only
the first and last 4 MiB, with an 8 KiB buffer and 4 KiB maximum lines. It reports
full size, omitted bytes, range offsets/digests and range-local line numbers;
there is no claimed full-file hash. Leading/trailing sample fragments cannot
become source messages. Sampled logs cannot qualify a fatal-signal witness.

Each range exports at most 64 recent source-message groups and 64 frequency
entries. All counters refer only to the sampled bytes; ambiguous prefix matches
remain explicit candidates. Raw lines, logger names, values, SQL, paths and
credentials are not exported. The original live log guard, logging thresholds,
asset validation, receipt checks and qualification flags are unchanged.

The exact original source ZIP adds 287 warning-site fingerprints outside the
startup selection: 447 total, preserving every original ID/hash. Only hashes of
16 literal ASCII bytes and fixed IDs enter the APK. The source index names the
original source locations, including NPCBots and all applicable module warnings.
A further 22 hashed, source-defined logger categories count warnings whose
messages begin with variable values. Category IDs identify candidate source sites,
not exact messages. Category names/values remain private, unknown categories stay
unrecognized, and each range exports at most 64 category count groups. A matched
prefix or category does not prove a cause, completed hook or harmless message.

Regression fixtures cover a sparse file larger than 1 GiB while reading only
8 MiB, independent sample hashes, discarded boundary fragments, unchanged bytes/
mtime, symlink/active/stale-attempt rejection, capped occurrence groups and exact
runtime-limit rejection. The original complete fatal-witness behavior remains.

## Verified historical delivery and recovery action

[Code-33 private APK and source](code33-recovery.md) passed 185 tooling tests,
382 Android tests (one existing private-fixture skip), lint/assembly, 225 packaged
checks, persistent signing and 16 KiB alignment. All 21 native payloads are unchanged.

Install code 33 in place, reopen, and select **Export diagnostic report**. Do not
start another world session or import/verify data first: the existing failed
attempt contains the evidence needed to diagnose the log pressure. No native
rebuild, asset reimport, seed replay, storage clear or old database/auth test is
needed. [Measured APK identity](evidence/android-2026-10-25-private-log-evidence-apk.json).

After receiving the report, resolve measured warning IDs against the exact source,
then implement and qualify any source-backed correction. The complete combined
world Stop/database postcondition gate remains deferred until that diagnosis.
