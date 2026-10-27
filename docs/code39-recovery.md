# Code 39 — client socket repair

[FTA-Launcher-0.14.1-client-sockets.apk](https://chatgpt.com/library?file_id=libfile_495de40c9848819184c3ce74dd46179f&account_id=personal)

**39 / 0.14.1-client-sockets**, 60,147,917 bytes, SHA256:
`63b42422a083f3a0e866c6d9df7be1e0048a111b21637ea3dd63a81022d8b0f5`.
Original certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`;
v2 signature, 16 KiB alignment and 235 packaged-input checks passed.
[Exact APK proof](evidence/android-2026-10-26-private-client-sockets-apk.json).
All 23 code-38 native payloads and 148 private runtime entries are byte-identical.
No signing key, original runtime archive, client file, realm or database changes.

Application commit **51358069f8d958c5e05b343cd6bf1d22be4f13f9**;
[foundation CI 40444054488](https://github.com/Russianranger/fta-launcher/actions/runs/40444054488)
passed 207 tooling, 77 qualification and 520 Android tests (one existing skip),
lint/assembly, cache/JNI, packaged checks and full 96 MiB-heap runtime installation.
[ARM64 CI 40444054525](https://github.com/Russianranger/fta-launcher/actions/runs/40444054525)
reproduced the old 118-byte socket-path failure and passed the shorter path,
display, all ten synthetic runtime stages, ownership and full pre/post rehash.
[Measured host proof](evidence/host-2026-10-26-client-socket-repair-pass.json).
These results do not qualify Android execution or WarcraftXL gameplay.

## One physical repeat

1. Install this private update **in place**. Keep the launcher visible and at
   least **2 GiB free** for a fresh synthetic prefix.
2. Select **Test client runtime** once. The prepared runtime is retained; do not
   select Prepare again or reimport the client or Data.zip.
3. **Export diagnostic report**. If it fails, export immediately before retrying.

Keep all existing storage. Do not uninstall, clear data, or repeat completed
client full-rehash, database/auth/world/screen-off gates. The runtime's own
pre/post rehash is automatic and measures the new 48 MiB/s aggregate I/O ceiling.
The same 256 KiB buffers, 1 MiB fsync/readback windows and pressure safeguards
remain. No faster-throughput or low-memory-immunity claim is made without Thor
measurements. The new diagnostics distinguish socket errors, display exits and
timeouts and preserve completed ownership/pre-rehash evidence on a later failure.

## Exact corresponding sources

- [FTA-client-bridge-code39-corresponding-source.zip](https://chatgpt.com/library?file_id=libfile_e520c08d28a48191ba266ff8c444f3a2&account_id=personal):
  2,475,972 bytes, SHA256
  `80fa4cba29cd141a0cbc4b207b629f35370c0a506b705a3c6c86ceb52a1f9882`.
  Exact qualified public source snapshot, original PRoot/talloc archives,
  patches, generated recipe, build evidence and complete license notices.
- The unchanged retained-server companion remains the code-38 distribution:
  [part 1](https://chatgpt.com/library?file_id=libfile_48e9f5cc1aac8191bebc2bc388b075f0&account_id=personal)
  and [part 2](https://chatgpt.com/library?file_id=libfile_ceb13c41e9a88191a8b34b6fd67db8a5&account_id=personal).
  [Reassembly and exact part identities](code38-recovery.md#corresponding-sources).
  Reassembled 540,190,148 bytes, SHA256
  `70637f0c662509fd43d79a03463e46631e8cfcc2a0360e1990542a4dea9bd7f0`.
  The strict source verifier accepts it unchanged for this delivery.

The unchanged public Wine/FEX runtime retains its bundled notices and pinned
upstream source/recipe references. Source companions are recovery/build inputs,
not installation inputs. Do not substitute the public CI debug APK for this
persistently signed private update. Future updates must use code >=40.
