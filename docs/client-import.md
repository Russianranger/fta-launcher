# Managed client import — code 36

Physical code 35 inspected the owner's complete extracted client successfully:
10,476 files, 1,175 directories, 60,243,212,332 bytes, no unknown sizes and 33 MPQs.
The exact x86 Wow.exe reports 3.3.5.12340; the proxy, WarcraftXL DLL and embedded
DXVK are x86 DLLs. Two auxiliary EXEs are x64 candidates; their role/pairing is
not established. [Measured proof](evidence/android-2026-10-25-thor-code35-client-inspection.json).
The retained code-34 world qualification still passes. No server retest is needed.

## Implementation and limits

Code 36 imports the **whole extracted folder containing Wow.exe** through a
persisted read-only SAF grant. It copies all supplied files and empty directories,
including original MPQs, WarcraftXL.cfg and WTF/Config.wtf, without editing them.
No account provisioning, specular setting change, renderer selection or executable
launch occurs. Actual host identity, Wine/translator IPC and gameplay remain open.

A separate nonexported `:client` foreground data-sync service owns one worker,
with visible-activity binding, bounded wake lock, explicit Stop and a three-hour
cancellation deadline. Process death never auto-restarts work. Original native
payloads and runtime/world receipts are unchanged. The UI prevents overlapping
server diagnostics and client import; the store also holds an exclusive file lock.

Private internal storage must have the remaining client bytes **plus 1 GiB** free.
For this initial 60.24 GB client, allow roughly **61.32 GB free** in addition to
the original folder and retained server data. Space is checked before payload
copying and each new chunk. There is no second temporary full-client copy.

Each file streams through a 256 KiB buffer. At most 16 MiB of newly written data
is unsynced. Each chunk is flushed, independently read back and SHA256-compared
before its checksum-protected checkpoint is appended and synced. Advisory source
and destination cache eviction reuses the existing helper; advice failure cannot
change integrity decisions. The report records peak RSS and advice counters.

On resume, the selected tree identity, full metadata plan and source IDs must
match. Every previously committed chunk is re-read from both source and destination
and checked against its receipt; those bytes are not rewritten. An incomplete
last checkpoint record and uncommitted destination tail can be discarded. A
corrupt committed receipt, changed source or damaged destination fails closed.
Source verification on resume can take time for a large completed prefix.

The candidate remains inert until all source bytes and destination readbacks pass,
the final source metadata inventory is unchanged, and copied WoW/proxy/WarcraftXL
PE metadata passes. Publication syncs directories and atomically replaces only the
active generation pointer. A prior active generation survives a failed candidate.
No previous generation, original folder, realm or backup is deleted automatically.
A crash after the atomic publication but before the success report can be recovered
using **Verify imported client**.

Retained verification rehashes every managed file, checks exact catalog/plan
membership, directories, lengths and PE metadata, and rejects missing/extra files
and symlinks. Client files remain inert. These are copy-integrity observations,
not proof that the user-provided client is complete/authentic or can run on Thor.
Keep the source folder unchanged throughout import; SAF is not a snapshot filesystem.

Plans, source URIs/IDs, filenames, settings and file-level hashes remain private.
The exported `clientImport` section contains fixed states/reasons, counts, bytes,
timings, memory/cache counters and a combined catalog digest. It does not replace
the passed native-world attempt. `warcraftXLCompatible` and `gameLoginQualified`
remain false.

Inventory bounds remain 100,000 entries, 32 levels, 2,048-character paths, four Mi
path/identity characters and a 16 MiB encoded plan. Aggregate client size is capped
at 1 TiB. Unknown file sizes, case collisions, invalid parent types, duplicate
source identities and unsafe destination links are rejected. No source schemas
or original server asset policies change.

## Qualification and next physical gate

Synthetic host tests cover exact copy/reopen, empty files/directories, bounded
reads, mid-file interruption/resume, partial/tampered checkpoints, changed sources,
destination corruption, shrinking/growing files, quota loss, lease contention,
symlink escape, failed validation/publication and preservation of a previous active
generation. Actual 60 GB SAF copy, Android lifecycle and physical retained rehash
remain pending until the owner returns the new report.

Install the verified private update in place. Choose **Import / resume client
folder**, select the same complete extracted folder, and keep the launcher visible.
When complete, reopen the app, select **Verify imported client**, then **Export
diagnostic report**. On any failure or interruption, export before resuming; do
not delete originals or clear app storage. No repeated server/game-data test.

Android references: [SAF grants](https://developer.android.com/training/data-storage/shared/documents-files),
[foreground data sync](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync).
Private delivery identity and current CI are recorded in the short handoff.
