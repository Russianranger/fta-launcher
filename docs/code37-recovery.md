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

## Current physical status and next action

Physical code 37 completed all **10,476 files / 60,243,212,332 bytes** in 2h40m05s,
resuming 4,898,874,959 bytes. Peak RSS 186,548 KiB; no pressure pause or attempt-matched
exit. [Measured pass](evidence/android-2026-10-25-thor-code37-client-import-pass.json).
The native world receipt remains qualified. No copy or server test needs repeating.

1. Keep the already-installed code 37 and reopen the launcher.
2. Choose **Verify imported client** and keep the app visible until complete.
3. Choose **Export diagnostic report** and return the JSON. On failure or interruption,
   export immediately before another attempt.

Do not select Import again, uninstall or clear storage. Original source files,
managed client files, realm, imported server assets and backups must remain intact.
No new private APK is required for the report-assessment tooling increment.

The report's `operation=import` and `retainedRehashValidated=false` mean the later
full rehash remains pending. Its catalog is
`fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13`.
The next agent must [bind the verification report to this import](client-report-qualification.md).

The 24 MiB/s aggregate governor and 1 MiB sync/readback/cache windows remain active
for retained verification. Successful copying does not measure actual cache eviction
or exercise forced-pressure backoff (zero pauses occurred). WarcraftXL execution,
exact x64 host pairing, Wine/translator IPC and playable login remain later gates.
