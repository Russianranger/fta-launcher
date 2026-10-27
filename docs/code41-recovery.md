# Code 41 — memory monitoring and bounded retry recovery

[FTA-Launcher-0.14.3-client-memory.apk](https://chatgpt.com/library?file_id=libfile_9d1be56740fc8191a8013af6fe703eff&account_id=personal)

**41 / 0.14.3-client-memory**, 60,156,109 bytes, SHA256:
`3e26e4bdeac5bd856b5ad95dd6d957e98d3734e1dcc5c6d4186056c53b104f26`.
Original certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`;
v2 signature, 16 KiB alignment and 235 packaged checks passed.
All 23 native payloads and 148 retained runtime entries match code 40 exactly.
[Exact APK proof](evidence/android-2026-10-27-private-client-memory-apk.json).

Application commit **85a3462dc461577c39849680ea01e5133f9a8c32**.
[Foundation CI 40603687890](https://github.com/Russianranger/fta-launcher/actions/runs/40603687890)
passed all three jobs: 207 tooling and 82 qualification tests (no skips),
534 Android tests passed / one existing fixture-dependent skip, lint/assembly,
native process ownership, cache/JNI and full runtime installation/rehash with
a 96 MiB heap. [Measured proof](evidence/host-2026-10-27-client-memory-pass.json).
Guest scripts and bridge are unchanged from the previous ten-stage ARM64 pass;
that job was not rerun. These results do not qualify physical Android execution.

The [code-40 report](evidence/android-2026-10-27-thor-code40-memory-guard.json)
showed advancing Windows initialization before the launcher's memory guard
stopped it. The last successful sample was healthy, but the triggering sample
was not recorded, so the exact pressure source remains unknown. Code 41 permits
brief advisory signals while preserving immediate measured/critical stops and
captures the actual trigger. It also preserves the three old attempts while
allowing one additional slot, reusable only after confirmed owned cleanup.
[Design and limitations](client-memory-recovery.md).

## One physical test

1. Install **in place**, keeping existing storage. Allow at least **2 GiB free**
   and keep the launcher visible.
2. Select **Test client runtime** once. Windows initialization can use up to ten
   minutes; subsequent compatibility checks and full rehash run automatically.
   Stop remains available.
3. Select **Export diagnostic report** on either outcome and send the JSON.
   If it fails, export immediately before retrying.

Success reads **Runtime process and compatibility tests passed.** The game does
not open during this synthetic test. Do not uninstall, clear storage, prepare or
download the runtime again, reimport the client/Data.zip, or repeat completed
database/auth/world tests. If `probe-attempt-limit` appears, export the report;
do not delete unproven attempt directories. No code-41 Thor pass is claimed yet.

## Exact corresponding sources

- [FTA-client-bridge-code41-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_780d24259ad48191b163cba53159d552&account_id=personal):
  2,504,923 bytes, SHA256
  `08de2970f27c0d4a83c28ba07cca43eaee875d78fd8b21b18229b15c86b84f46`.
  Exact qualified source snapshot, original PRoot/talloc archives, patches,
  generated build recipe, evidence and complete notices.
- Unchanged retained-server source: [part one](https://chatgpt.com/library?file_id=libfile_48e9f5cc1aac8191bebc2bc388b075f0&account_id=personal)
  and [part two](https://chatgpt.com/library?file_id=libfile_ceb13c41e9a88191a8b34b6fd67db8a5&account_id=personal).
  Concatenate in order: 540,190,148 bytes, SHA256
  `70637f0c662509fd43d79a03463e46631e8cfcc2a0360e1990542a4dea9bd7f0`.
  The strict source verifier accepted it unchanged for this delivery.

Source companions are recovery/build inputs, not installation inputs. The
downloaded Wine/FEX runtime retains its notices and source references. Use the
private signed APK above; the public CI foundation APK is not its substitute.
Future private updates must use code >=42 and the original signing capsule.
