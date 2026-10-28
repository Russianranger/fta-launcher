# Code 42 — disposable client storage isolation

[FTA-Launcher-0.15.0-storage-isolation.apk](https://chatgpt.com/library?file_id=libfile_5a5cb7cc29e48191ae775dbe49032ffe&account_id=personal)

**42 / 0.15.0-storage-isolation**, 60,291,597 bytes, SHA256:
`e86336eafd6a5e49733e88033024db8e9c8c6b0d5fdaec4207eba30e3d8b1bd5`.
Original certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`.
V2 signing, 16 KiB alignment and **239 packaged checks passed**. All **23** donor
native payloads and **148** retained runtime entries are byte-identical to code 41;
two separately pinned native additions bring the total to 25. The old runtime
bridge, retained world, all 52 source modules and realm data are preserved.
[Packaging proof](evidence/android-2026-10-28-private-storage-isolation-apk.json).

Application commit **29008511b1860c07a01b58b0b799e6b06d600165** passed
[foundation CI 40614566601](https://github.com/Russianranger/fta-launcher/actions/runs/40614566601):
212 tooling, 93 qualification (no skips), **540 Android tests passed / one existing
fixture skip**, lint, assembly, process ownership, cache/JNI, and runtime
installation/full rehash with a 96 MiB heap. The same commit passed all 50 native
fixture checks on x86-64 and ARM64 in
[guard CI 40614566581](https://github.com/Russianranger/fta-launcher/actions/runs/40614566581),
with unchanged protected trees and seven rejected root cases on each architecture.
[Measured CI proof](evidence/host-2026-10-28-storage-isolation-pass.json).
These host results do not qualify physical Android execution.

## Test once on the Thor

1. Install the APK **over code 41**, keeping all app data.
2. Tap **Test client storage isolation**. Keep the launcher visible. It normally
   takes seconds and has a two-minute overall limit. Stop remains available.
3. Expected: **Storage isolation passed: 50 checks.** Export the diagnostic report
   and return the JSON, including if it fails, stops or is interrupted.

[Download test instructions](https://chatgpt.com/library?file_id=libfile_36bf179c48c48191af099cdee84f7a1b&account_id=personal).
Do not repeat import, full rehash, Wine/runtime or server tests. The new test
uses only 65,545 bytes of disposable fixture files, preserves the existing runtime
report, and exports a separate `clientStorageIsolation` section. It does not open
the imported client or Wine runtime. Original working-view creation, guarded Wine,
WarcraftXL startup, rendering and login remain later gates.
[Implementation and scope](client-storage-isolation.md).

## Exact corresponding sources

- [FTA-client-bridge-code42-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_86951159280481919cba757d7b762392&account_id=personal):
  2,549,994 bytes, SHA256
  `a632fe7fc994f46d06a36df12ce34f645c9cd857b2894e0399783c3618d263b4`.
  Includes the exact application source snapshot, original PRoot/talloc archives,
  ownership/read-only patches, fixture, generated recipes, build evidence and
  complete notices for both the retained and new bridges.
- Refreshed retained-server source: [part one](https://chatgpt.com/library?file_id=libfile_f7c3f449334c819187dd657e5995018a&account_id=personal) and
  [part two](https://chatgpt.com/library?file_id=libfile_1f18df60152481919746c069c3156a93&account_id=personal).
  Concatenate in order to recover `FTA-Launcher-0.15.0-world-corresponding-source.zip`:
  540,231,265 bytes, SHA256
  `7b81dd77319f5a7b1ba3c4c5a037af22c08875813acf9096adecc634630cc190`.
  Original native archives/notices/build evidence remain unchanged; reproduction
  and packaging helpers include this delivery's updates. Strict source verification passed.

Sources are recovery/build inputs, not installation inputs. The downloaded
Wine/FEX runtime retains its separate original source references and notices.
Use the private signed APK above; the public foundation APK is not a substitute.
Future private updates require code >=43 and the original signing capsule.

Next development gate: assess the physical storage report, then qualify this guard
with PE32/PE64 file access and build the bounded original-client working view.
Keep the passed code-34 world, code-37 import and code-41 runtime evidence.
