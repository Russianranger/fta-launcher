# Code 37 private client memory-backpressure update

[FTA-Launcher-0.13.2-client-memory.apk](https://chatgpt.com/library?file_id=libfile_43831b1a9b0c81918d349e3a5625198f&account_id=personal)

**Version 0.13.2-client-memory / code 37**, 59,073,805 bytes. SHA256:
`105bf65924ebabedb7dde71c2e3f880e463180a45ccf1fd4f7a6fe14b061cf0b`.
Persistent certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`;
v2 signing, 16 KiB alignment and all 225 packaged-input checks passed.
[Measured APK proof](evidence/android-2026-10-25-private-client-memory-apk.json).

Application commit **39516bb1b0a499d84279ef9eefea0c5c9e9abcf4**;
[CI 40216989093](https://github.com/Russianranger/fta-launcher/actions/runs/40216989093)
passed all three jobs. 186 tooling tests; 457 Android tests passed / one existing
private-fixture skip, zero errors/failures, 40 suites. 20 new regressions, lint,
assembly and native cache-helper checks passed. A separate 64 MiB-heap host run
resumed 4,865,392,640 bytes from the unchanged code-36 store, completed 10,476
synthetic files / 5,368,719,624 bytes, and passed the full retained rehash.
[Host evidence and reproduction](client-memory-host.md).

All **21 native payloads**, **148 runtime entries** and the runtime manifest are
byte-identical to code 36 and the native code-34 pass. The [existing v15 source
archive and both saved parts](code34-recovery.md#matching-corresponding-source)
still match every native reproduction input; full archive SHA256
`02695902ac274ad33feb881b1d2f53f9933c7a2a6db08fe164fa27edc1a8c98c`.
No native rebuild or signing-key regeneration. Public CI APKs are foundation
previews and cannot replace this private signed update.

## Resume the interrupted client copy

1. Install this APK **in place** over code 36. Keep the partial copy, original
   client folder, realm, server imports and backups. Do not uninstall or clear storage.
2. Choose **Import / resume client folder** and select the **same original folder
   containing Wow.exe**. Keep the launcher visible. The already-checkpointed prefix
   is rechecked first, then the copy continues without rewriting verified chunks.
3. Allow a slower copy: the aggregate I/O limit corresponds to roughly 8 MiB/s
   for new data before storage/hash overhead; the complete 60.24 GB client can
   take more than two hours. The three-hour operation deadline remains bounded.
   Memory pressure automatically pauses work and can safely stop it after two
   minutes. Stop remains available; no automatic restart/retry occurs.
4. After completion, reopen and choose **Verify imported client**, then **Export
   diagnostic report** and return the JSON. If either operation stops or fails,
   export immediately before another attempt.

Only remaining bytes plus the existing 1 GiB reserve are required; no second full
staging copy is created. Never delete the original folder to gain space. This
update preserves the 16 MiB checkpoint format and uses 1 MiB sync/readback/cache
windows plus memory/PSI backpressure. [Behavior and limits](client-import.md).

The owner-reported code-36 low-memory interruption is recorded honestly: its report
does not contain client exit or pressure evidence, so the exact kernel/provider
cause is not proven. Code 37 adds client-specific attempt matching and pressure
observations. **Physical low-memory survival and full client import/rehash remain
pending.** WarcraftXL execution, x64 host pairing, Wine/translator IPC and playable
login remain later gates. No repeat inspection, native-world, screen-off, DB/auth
or imported-server-data test is needed.
