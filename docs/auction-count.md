# Original auction COUNT getter correction

Code 33 successfully recovered the same failed code-32 attempt without starting
a new native session. The world log is 8,535,490 bytes. Its first/last 4 MiB
samples omit 146,882 bytes; the tail contains 67,108 warnings and 33,554 matches
for the shared Field aggregate-conversion hint. The final original Halting
message is present. This does not retroactively qualify Stop or postchecks.
[Measured recovery](evidence/android-2026-10-25-thor-code33-recovery.json).

The old 16-byte prefix cannot distinguish Field.cpp's double and uint64 hints.
The exact original source reveals a recurring, independently reproducible
caller error: `AuctionatorSeller::BuildAuctionHouseSnapshot` reads column 4's
`COUNT(*)` through `Get<uint32>()`. The original Field implementation logs its
type-conversion hint and retries `Get<uint64>()` for every row. The seller calls
this snapshot during its auction cycles. A source search must include ignored
module/custom files (`rg --no-ignore`); the archive inventory is authoritative.

Profile **android-arm64-world-v15**, patch **0019**, changes that one expression
to `static_cast<uint32>(fields[4].Get<uint64>())`. This preserves the original
fallback's conversion, including unsigned narrowing and snapshot accumulation.
The query, grouping, other getters, auction rules, all SQL/configurations and
all other source files are unchanged from v14. The v14 shutdown repair remains.
No warning category is disabled and the live 8 MiB guard remains unchanged.

This is a demonstrated caller defect consistent with the measured warnings,
not proof that this caller produced every Thor warning. Code 34 additionally
compares the two complete static hint hashes before the shared prefix. It
exports only their existing source IDs. A suffixed/unrecognized message remains
an ambiguous prefix match; no native values or raw message text are exported.

## Qualification

`tools/check_auction_count.py` checks the original private archive and patch
hashes, then compiles the complete original snapshot method with the unchanged
original Field/StringEqualI implementations. Only its database/result ownership
adapter and synthetic rows are fixtures. It tests empty results, present/absent
origin resolution, zero/ordinary counts, UINT32_MAX, UINT32_MAX+1 and UINT64_MAX.
Both old/new paths produce identical category, item and origin counts.
Original warnings: two per row, or three with strict type checks. Fixed: zero.
All four Release and four ASan/UBSan runs pass; LeakSanitizer is not claimed.
[Host proof](evidence/host-2026-10-25-auction-count.json).

The single-job ARM64 build reuses the completed graph, recompiles the seller,
then relinks the module archive and world. A repeated recompilation/link produces
the same executable hash. Complete source/file-API/archive/ELF checks preserve
52 modules, 224 module compilation units, C++20, static scripts/Lua 5.2, six exact
retained link libraries and 16 KiB load alignment.
[Native build proof](evidence/android-2026-10-25-world-auction-build.json).
The v13 executable still identifies the updater, realm and backup. The separate
session executable is now v15; old session receipts cannot qualify it.

Host rows are synthetic; this does not qualify the actual retained database,
original GT fallback, native Stop, or gameplay. A separate SUM/AVG alias branch
may recurse for incompatible metadata, but this report does not establish that
case and this patch deliberately leaves the original Field implementation intact.

## Next combined Thor gate

Use the [verified private code 34 and matching source](code34-recovery.md),
`0.12.7-auction-count`, with its measured identity and persistent signer. Install in place.

1. Select **Start native world test** once; keep the launcher visible through
   its automatic retained-file rehash and startup.
2. After healthy world/auth/database samples appear, lock the screen for three
   minutes, reopen, and select **Stop** once.
3. Export the diagnostic report immediately after success or failure, before
   any further attempt.

Preserve the realm, backup, assets and accounts. No reimport, seed replay,
uninstall, storage clear or repeat standalone database/auth diagnostics.
The complete gate must prove graceful world/auth exits, SQL database shutdown,
closed listeners and strict metadata/GT postconditions. If warnings persist,
use the new exact hint IDs to narrow the remaining path before another change.
