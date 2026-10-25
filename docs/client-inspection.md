# Read-only client folder inspection

Code 35 begins the client milestone after the complete code-34 native world pass.
It does not import or run the client. Select the already extracted **complete
client folder containing Wow.exe**, keep the launcher visible, then export the
diagnostic report. Do not repeat the passed server/import/screen-off tests.

The storage picker requests a temporary read grant only. No persistent source
grant, source write, rename, copy, config change or native invocation occurs.
The separate atomic `client-inspection/report.json` receipt leaves the successful
world attempt, database, backup and assets intact. Cancellation and a three-minute
cancellation timer stop work through the provider signal and current descriptor.
Process death leaves an explicitly unfinished receipt; there is no auto-resume.

Metadata traversal is bounded to 100,000 entries, 32 levels, 2,048-character paths
and four million path characters; directory identities have a separate bound.
Case collisions, cycles, unsafe names, inaccessible directories and exceeded
bounds fail the inventory. MPQ counts and provider sizes are **metadata only**,
not full client integrity. Unknown sizes are counted explicitly.

Known binary roles are root Wow.exe, d3d9.dll, WarcraftXL.dll and
Utils/WarcraftXL/backends/dxvk/d3d9.dll. Up to 32 other EXEs receive anonymous role
numbers, enabling x64 asset-host candidate discovery without exporting filenames.
Each seekable component is limited to 128 MiB, with 512 MiB total hashing budget;
unsupported or unreadable components remain unqualified. Config files are only
counted for presence; their contents and hashes are not exported. Actual host
identity, component pairing, MPQ completeness and configuration semantics await
the owner's measured inventory and original client source.

PE checks use checked file offsets, section-backed RVA mapping, at most 96
sections, a one-MiB read budget and bounded resource traversal. Version data comes
only from RT_VERSION / VS_VERSION_INFO / VS_FIXEDFILEINFO, never a signature scan.
Conflicting resource versions are rejected. The WoW build observation requires
x86 PE32, a non-DLL image and exact numeric file version **3.3.5.12340**. This is
metadata evidence, not executable authentication or Wine compatibility.

## Source references

Original private `Launcher.zip`, 3,684,707 bytes, SHA256
`b1ab843535307a5a384b30ed29ef80733abf5e45b7967250408a80f79a6380f0`:
`Launcher/src/main.cpp` requires the root proxy and WarcraftXL DLL, defines the
embedded DXVK path and writes `SET specular "0"` before desktop startup.
`Launcher/defaults/WarcraftXL.cfg` retains Modern Assets, its host and Back Sheath
Flip defaults. Inspection does not apply these settings or modify per-client
WarcraftXL.cfg / WTF/Config.wtf. No original launcher code is copied into Git.

Microsoft specifications: [PE/COFF](https://learn.microsoft.com/en-us/windows/win32/debug/pe-format),
[VS_VERSIONINFO](https://learn.microsoft.com/en-us/windows/win32/menurc/vs-versioninfo),
[VS_FIXEDFILEINFO](https://learn.microsoft.com/en-us/windows/win32/api/verrsrc/ns-verrsrc-vs_fixedfileinfo).

Next: examine the physical inventory, resolve the exact matching client/host
layout, then implement transactional resumable managed import. Wine/translator,
32-bit client to 64-bit host IPC, renderer, controllers and playable login remain
later gates. Never overwrite the original folder or provision an account silently.
