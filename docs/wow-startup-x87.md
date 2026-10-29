# Original-client startup: x87 condition-code investigation

The supplied Wow.exe exactly matches the retained client identity:
7,702,016 bytes, SHA256
`c4ea8503112878fa47d06580de9d936b184f9bb6b452f6931dfe793fe2f0a47e`.
Read-only PE/disassembly analysis locates the repeated fault candidate RVA
0x5c7898 in a small x87 scalar-store initialization loop. The on-disk loop has
48 iterations of three 32-bit zero stores, spanning only 576 bytes of writable
image data, with the branch controlled by the sign flag after decrementing a
32-bit counter. x87 FST is not supposed to change that integer condition code.
The reported write target RVA 0x9fe000 is far outside this intended range, at
the start of the read/execute .c1024 section. It must not simply be made writable.
No original executable/DLL bytes or modifications are committed.
[Numeric static observations](evidence/wow-startup-static-analysis.json) bind this
analysis to the supplied file hash.

This is consistent with a loop overrun but does not alone prove runtime control
flow, a fatal exception or a specific emulator defect. Exact WarcraftXL/proxy
binaries contain no direct immediate references to the loop addresses examined;
that limited negative search does not exclude pattern-based runtime changes.

The pinned [FEX-2510 x87 optimization pass](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/FEXCore/Source/Interface/IR/Passes/x87StackOptimizationPass.cpp)
uses an ARM floating comparison in SilenceNaN during scalar stores. Its reduced
precision path omits that operation when strict reduced precision is disabled.
A synthetic fixture compares the unchanged default, reduced and strict-reduced
profiles on the exact Wine/FEX runtime. It checks integer flags and a bounded
48-iteration zero-fill loop, with a no-access guard page after the test buffer.
It contains no private game code/data. Each invocation owns and stops Wine;
source/runtime integrity is checked before and after the comparison.

Upstream [PR 4981](https://github.com/FEX-Emu/FEX/pull/4981) reverted this NaN
handling in commit bc6295a78dd8d4bff49eb7f567881bd92d10b860 after it broke
Bayonetta startup. That is supporting history, not proof of this client's cause.

Native x86-64 reference execution passed: flags 149 before and after, 48
iterations, 576 zero bytes, intact leading canary.

## Measured ARM64 reproduction and code 53 workaround

[Exact runtime comparison](evidence/host-2026-10-29-x87-reproduction.json), commit
4ec7a6b074f23c21423b63b80e06e2cfa3def09a, run 40945702361:

| Profile | Integer flags before/after | Loop | Fixture exit |
| --- | --- | --- | --- |
| Default | 149 / 84 | Write into no-access guard | 21 |
| Reduced, strict disabled | 149 / 149 | 48 iterations, 576 zero bytes, canary intact | 0 |
| Reduced, strict enabled | 149 / 84 | Write into no-access guard | 21 |

Every case stopped its own Wine processes; full runtime rehash and unchanged
fixture snapshots passed. This demonstrates the pinned emulator's store/flags
regression on native ARM64 Linux. It strongly supports the observed client-loop
overrun explanation, but the original game was not executed on this host.

Code 53 passes FEX_X87REDUCEDPRECISION=1 and FEX_X87STRICTREDUCEDPRECISION=0
only in the original-startup child's environment, inherited by its Wine children.
Prefix preparation, cleanup and other gates retain their own environment. No
runtime configuration, original client bytes, section permissions or installed
runtime files are edited. The CI reduced profile imports this exact production
mapping and now requires the demonstrated failure/pass/failure matrix.

This uses 64-bit floating-point precision for x87 instead of full 80-bit extended
precision. It is a targeted startup workaround; future rendering/numerical
accuracy still needs qualification. A runtime rebuild with a reviewed upstream
correction can replace the workaround later. Fivefold verification pacing,
full hashes, imports/settings, ownership and pressure handling are unchanged.
Physical code-53 startup, rendering and login remain unqualified until observed.
