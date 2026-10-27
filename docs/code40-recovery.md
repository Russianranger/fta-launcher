# Code 40 — cold Wine initialization

[FTA-Launcher-0.14.2-client-prefix.apk](https://chatgpt.com/library?file_id=libfile_254cd4f171d08191a66d626e36b33285&account_id=personal)

**40 / 0.14.2-client-prefix**, 60,152,013 bytes, SHA256:
`434b067ff2fa12781fe186814a1baf894f2a818a51b755fa07c48f210a096dae`.
Original certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`;
v2 signature, 16 KiB alignment and 235 packaged-input checks passed.
[Exact APK proof](evidence/android-2026-10-27-private-client-prefix-apk.json).
All 23 native payloads and 148 retained runtime entries match code 39 exactly.

Application commit **fdf7eb5fedaaa0beaac0a239e1b3b6949bef47c9**.
[Foundation CI 40587205325](https://github.com/Russianranger/fta-launcher/actions/runs/40587205325)
passed all three jobs: 207 tooling and 82 qualification tests (no skips),
522 Android tests passed / one existing skip, lint/assembly, packaged checks,
cache/JNI and full runtime installation/rehash with a 96 MiB heap.
[ARM64 CI 40587205346](https://github.com/Russianranger/fta-launcher/actions/runs/40587205346)
passed all ten guest stages, normal/Stop/tracer-death cleanup, socket controls
and complete pre/post runtime rehash. Prefix progress was observed at 0, 15,
30 and 38.831 seconds through Linux PRoot.
[Measured evidence](evidence/host-2026-10-27-client-prefix-pass.json).

Code 39's real socket/display repair passed on Thor, but Wine initialization
timed out after 180 seconds. Code 40 uses fresh-prefix initialization, a finite
600-second allowance, visible bounded progress, late diagnostic classification
and corrected handling of inherited output pipes. [Changes and limits](client-prefix-initialization.md).
The report did not distinguish slow boot from a persistent Wine hang; this
candidate still needs Android validation. Original client/login remain unqualified.

## One physical test

1. Install **in place**, retaining all existing storage. Allow at least **2 GiB
   free** and keep the launcher visible.
2. Select **Test client runtime** once. Initialization can now take up to ten
   minutes; the remaining checks and runtime rehash run automatically. Stop
   remains available.
3. Select **Export diagnostic report** on either outcome and send the JSON.
   If it fails, export immediately before retrying.

Success reads **Runtime process and compatibility tests passed.** These are
synthetic tests; the game does not open. Do not uninstall, clear storage,
prepare/download again, reimport the client/Data.zip or repeat completed server
tests. Prior attempts remain intact under the existing three-attempt limit.

## Exact corresponding sources

- [FTA-client-bridge-code40-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_21c55de4d9c4819188e7427f7c9ddb35&account_id=personal):
  2,490,427 bytes, SHA256
  `f9a34d9aa6777e6e1a3af96aef2465bf63438e37ee78ce2628c1a71602cc5cc4`.
  Exact qualified public source snapshot, original PRoot/talloc archives,
  patches, generated build recipe, evidence and complete notices.
- The unchanged retained-server source companion remains the [code-38 source
  distribution](code38-recovery.md#corresponding-sources), parts
  [one](https://chatgpt.com/library?file_id=libfile_48e9f5cc1aac8191bebc2bc388b075f0&account_id=personal)
  and [two](https://chatgpt.com/library?file_id=libfile_ceb13c41e9a88191a8b34b6fd67db8a5&account_id=personal).
  Reassembled: 540,190,148 bytes, SHA256
  `70637f0c662509fd43d79a03463e46631e8cfcc2a0360e1990542a4dea9bd7f0`.
  The strict source verifier accepted it unchanged for this exact delivery.

Source companions are recovery/build inputs, not installation inputs. The
unchanged downloaded Wine/FEX runtime retains its notices and source references.
Use the private signed APK above; the public CI foundation APK is not its
substitute. Future private updates must use code >=41 and the original signer.
