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

Native x86-64 reference execution passed: flags 149 before and after, 48
iterations, 576 zero bytes, intact leading canary. ARM64 results are pending.
This investigation does not yet change application behavior or claim a fix.
