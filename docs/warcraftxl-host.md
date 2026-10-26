# Matched x64 asset host and root D3D9 proxy

The owner supplied both remaining binaries on October 26, 2026. Their measured
identities match the physical code-35 inventory retained by the passed code-37
import and full rehash:

| Component | Bytes | SHA256 |
| --- | ---: | --- |
| Utils/WarcraftXLHost.exe, x64 EXE | 1,071,104 | `6349752018efba461a2ac4bf19a98d1293aa7c21f98cb90f50615f33a7701955` |
| Root d3d9.dll, x86 DLL | 202,240 | `4c3522dd17e2ef09cbe13ac82c1e4360c7beb98cbe7af7675d751d1f5bc330e8` |

The host is now assigned to the previously anonymous 1,071,104-byte observation
by exact hash. The other anonymous x64 executable remains unassigned. The root
proxy has all six direct executable exports imported by the matching WarcraftXL
DLL. This qualifies the named-export check, not their complete calling ABI or
live rendering. Original files and private configurations are unchanged.

## Static host contract

The bounded checker now accepts `--host`, requires the exact hash, checks an x64
EXE, and compares all five shared mapping/event/blob/singleton name templates to
the original x86 DLL. The host has 151 static imports from KERNEL32, USER32 and
WININET, no delay-import directory and no CLR directory. These are PE observations,
not a claim that dynamic loading, asset requirements or Windows API behavior have
been qualified. WININET presence alone does not prove a network request occurred.
[Measured contract](evidence/host-2026-10-26-warcraftxl-host-contract.json).

Preserve the DLL-owned `--client-pid` launch and Utils working directory described
in [the DLL contract](warcraftxl-contract.md). Host RVA `0x1b569` references that
argument; `0x17f40` opens the supplied Windows process with SYNCHRONIZE access.
When that succeeds, the watcher at `0x13810` waits for it, performs cleanup and
calls ExitProcess. Failure to open it can skip the watcher; independent owned
process cleanup is still mandatory. No Android PID substitution or separately
started host is appropriate. Both Windows architectures need one Wine prefix
and its shared wineserver/session object namespace.

## Original producer/consumer header comparison

The x64 initialization loop writes the same magic, protocol, channel count and
host process word accepted by the x86 validator. Each channel has a 64-byte
header plus 786,432 inline bytes, stride **786,496**. Channel selection clamps
reported hardware concurrency to 2..16, using four when it reports zero.
The full mapping size is not a bound on host/game/cache memory.

`check_warcraftxl_host.py` extracts two unchanged hash-pinned fragments:

| Fragment | RVA | Bytes |
| --- | --- | ---: |
| Channel selection after hardware-concurrency call | `0xc107` | 88 |
| Initialize all channel headers, before event creation | `0xc20d` | 101 |

Unicorn 2.1.4 runs only these fragments with synthetic hardware counts and a
GetCurrentProcessId return stub. Code is read/execute, constants and import slot
are read-only, and header pages have checked guard bytes. Maximum emulator memory
is 86,016 bytes; limits are 64/1,024 instructions and 10 ms per fragment. No
original machine code is modified or committed. No executable entry point,
Windows API, event, live mapping or asset request runs.

All **104 initializations / 1,140 channel headers** match their complete expected
64 bytes. The unchanged original x86 validator and production Kotlin agree on
all of them: **855 accepted**, **285 rejected with status 7** for the synthetic
zero host-process word. Every channel count 2..16 and unsigned process-word
extremes are exercised. [Measured comparison](evidence/host-2026-10-26-warcraftxl-host-headers.json).
This qualifies isolated header interoperability only; host pairing, live IPC,
Wine/WarcraftXL and gameplay flags remain false.

```sh
python3 android/qualification/warcraftxl_contract.py \
  --dll /private/WarcraftXL.dll --proxy /private/d3d9.dll \
  --host /private/WarcraftXLHost.exe --output /private/new-contract.json
PYTHONPATH=/private/unicorn-2.1.4 python3 android/qualification/check_warcraftxl_host.py \
  --dll /private/WarcraftXL.dll --proxy /private/d3d9.dll \
  --host /private/WarcraftXLHost.exe --kotlin-classpath /private/classes:/private/stdlib.jar \
  --output /private/new-host-headers.json
```

Nine synthetic regressions exercise architecture, missing/unexpected imports,
each required API, uninspected dependency directories, identity rejection and
unsigned fixture boundaries. All 52 qualification and 186 tooling tests pass
locally. Private probes remain separate from public CI, which never receives
the original binaries. No new private APK or Thor test is needed for this work.

Next: pin and inspect the mixed-architecture Wine/FEX runtime, then qualify
cross-architecture creation, shared mappings/events and owned cleanup with
synthetic executables before an original-host device gate. Preserve the managed
client; a writable runtime view must not mutate or hard-link its original files.
