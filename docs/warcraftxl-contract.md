# Matched WarcraftXL DLL: host launch and header contract

The owner's supplied WarcraftXL.dll is **2,259,968 bytes**, SHA256
`9c935e27679a8161dbbdc3390bbb908d0ad444a5fe114ed0624c3db52656eedd`.
It exactly matches the x86 DLL recorded by the physical client inspection and
retained by the passed full-client import. The two supplied configuration files
were inspected privately and left unchanged. Neither their bytes nor their hashes
are published. The 1.1 GB source archive is not required for this bounded inspection.

## Required files and original launch behavior

The original DLL resolves its own directory, appends `Utils`, then appends
`WarcraftXLHost.exe`. This is the required location relative to the client root:
**`Utils/WarcraftXLHost.exe`**, not a new standalone launcher executable.
Its `CreateProcessA` call supplies the resulting application path, a command line
containing `--client-pid` with `GetCurrentProcessId()`, inherited environment and
the Utils directory as the child working directory. Its optional console branch
adds `--console`. Preserve this behavior; do not independently start a guessed
host or substitute an Android process ID for the client PID inside Wine.

Read-only disassembly locations, expressed as RVAs in the exact DLL:

| Observation | RVA |
| --- | --- |
| Resolve this DLL's module filename using GetModuleHandleExA/GetModuleFileNameA | `0x24b70` |
| Append Utils and WarcraftXLHost.exe | `0x22dad`, `0x22df9` |
| Format the client PID and optional console argument | `0x22f8d`–`0x22fa0` |
| Call CreateProcessA with the explicit application path and Utils working directory | `0x23059` |
| Read a 64-byte header from the client-specific shared mapping | `0x238e0` |
| Validate the copied header | `0x219e0`, 71 bytes |

This is static evidence of the DLL's intended path and behavior. The host EXE has
not been supplied or inspected. The earlier report's two anonymous x64 hashes
cannot yet be assigned to this path merely from their sizes or version labels.
Original request/channel layouts and live IPC still require qualification.

The import directory also requires these **six named exports from root d3d9.dll**:
`WxlGetControlOption`, `WxlGetControlSettings`, `WxlGetSsaaFactor`,
`WxlResetControlSettings`, `WxlSaveControlSettings`, `WxlSetControlOption`.
The embedded DXVK D3D9 backend is a separate component. Preserve the original
root proxy and its matching DLL; a generic DXVK DLL cannot be assumed to satisfy
these custom imports. This reinforces the existing source-tested Wine overrides.

`android/qualification/warcraftxl_contract.py` verifies the exact supplied DLL,
parses PE imports through unique section-backed RVAs, checks the expected host
references and validator hash, and writes fixed, sanitized observations. Optional
`--proxy` additionally requires the original Thor proxy hash and six direct
executable exports. Forwarders, missing/null exports, wrong architecture and
identity mismatches do not qualify that narrow export check. It never claims
full ABI compatibility, host pairing, Wine startup or gameplay.

The PE metadata reader caps input at 16 MiB, parser reads at 1 MiB, sections at
96, import libraries at 128, symbols at 8,192, export names at 4,096 and strings
at 256 ASCII bytes. It does not scan arbitrary strings as import/export evidence.
19 synthetic regressions include both PE widths, ordinal imports, lookup fallback,
unreferenced strings, truncation, overlapping/unmapped ranges, malformed tables,
forwarders, missing exports, bounds and pinned-identity rejection.

```sh
python3 android/qualification/warcraftxl_contract.py \
  --dll /private/WarcraftXL.dll --proxy /private/client-root/d3d9.dll \
  --output /private/new-contract.json
```

Omit `--proxy` when it is unavailable; the export gate stays false. Existing
outputs are never overwritten. Owner files remain private and are never loaded
as executable modules. [Measured static evidence](evidence/host-2026-10-25-warcraftxl-contract.json).

## Exact header checks; isolated original-code comparison

The DLL first copies 64 bytes from `Local\WarcraftXLShm_<client-pid>`. Its leaf
validator checks these little-endian DWORDs, in this order:

| Offset | Original condition | Rejection status |
| --- | --- | --- |
| 0 | Magic `0x4c585857` | 4 |
| 4 | Protocol 4 | 5 |
| 24 | Unsigned channel count 2 through 16 | 6 |
| 28 | Nonzero host process word | 7 |

The successful status is 1. The leaf does not validate the other snapshot words;
the new `WarcraftXlHostHeader` preserves those semantics and rejects non-64-byte
inputs before reading them. Acceptance means only these four header conditions,
not trustworthy process identity, request-layout compatibility or a healthy host.
The helper does not connect to any mapping or expose the process word.

`check_warcraftxl_header.py` extracts the **unchanged 71-byte function** from the
hash-pinned private DLL and runs only that leaf in Unicorn 2.1.4 x86-32. Code and
input pages are read/execute and read-only respectively; the stack is a separate
page. Each case has a 64-instruction limit and 10 ms timeout, no OS API mapping,
and must return to a sentinel with input unchanged. The full DLL, DllMain, Wine,
asset host and game never execute. No original machine code enters Git.

All **1,456** synthetic cases match production Kotlin exactly: valid channel
bounds, rejected magic/version/channel/PID, unsigned extremes, combined failure
precedence and randomized ignored words. [Measured comparison](evidence/host-2026-10-25-warcraftxl-header.json).
This is an isolated header compatibility test; every live runtime flag stays false.

Reproduce with JDK 17, Kotlin 2.0.21 and an isolated Python environment containing
the prebuilt `unicorn==2.1.4` package. Compile `WarcraftXlHostHeader.kt` with
`android/qualification/WarcraftXlHeaderHost.kt`, then run:

```sh
python3 android/qualification/check_warcraftxl_header.py \
  --dll /private/WarcraftXL.dll \
  --kotlin-classpath /private/classes:/private/kotlin-stdlib-2.0.21.jar \
  --output /private/new-header-evidence.json
```

Ten Android unit tests cover the snapshot checker; two more cover the now-known
host inventory role. Future inspection identifies only the exact Utils path as
`asset-host`; other executable filenames remain anonymous. This does not add a
host requirement to the already-passed importer or rewrite its plan/checkpoints.
The app remains on private code 37; these preparation changes need no device test.

## Next minimal input

Supply **`Utils/WarcraftXLHost.exe`** and **`d3d9.dll` beside Wow.exe** from this
same client. The recorded root proxy is 202,240 bytes with SHA256
`4c3522dd17e2ef09cbe13ac82c1e4360c7beb98cbe7af7675d751d1f5bc330e8`.
Do not substitute `Utils/WarcraftXL/backends/dxvk/d3d9.dll`. The 60 GB game data,
database, private configs and complete 1.1 GB source archive are unnecessary for
this next inspection. Match host identity to the earlier x64 observations,
inspect its imports/protocol, then continue the shared Wine environment and
owned-process runtime gate. No repeated import or retained verification is needed.

References: [Microsoft PE/COFF](https://learn.microsoft.com/en-us/windows/win32/debug/pe-format),
[CreateProcessA](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessa),
[Unicorn CPU emulation](https://www.unicorn-engine.org/docs/tutorial.html).
