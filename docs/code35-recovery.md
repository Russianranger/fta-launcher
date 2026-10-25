# Code 35 private client inspection update

[FTA-Launcher-0.13.0-client-inspection.apk](https://chatgpt.com/library?file_id=libfile_941aa08e51ec819191b699978078ee35&account_id=personal)

**Version 0.13.0-client-inspection / code 35**, 59,020,557 bytes. SHA256:
`a4fc4d4b177a40c61ed7a6bc145914a93af0b543590c49a4b684c8eaa4448b8b`.
Persistent certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`;
v2 signature, 16 KiB alignment and all 225 packaged-input checks passed.
[Measured APK proof](evidence/android-2026-10-25-private-client-inspection-apk.json).

Application commit **66529c938ea6c4ed8c5b9a3363b40a730a54ee11**;
[CI 40160920921](https://github.com/Russianranger/fta-launcher/actions/runs/40160920921)
passed all three jobs. 186 tooling tests; 405 Android unit tests passed, one
existing private-fixture skip, zero errors/failures, 37 suites. This includes 22
new bounded inventory/PE regressions. Lint, assembly and cache-helper JNI checks
passed. Public CI APKs are foundation previews, not this private update.

All **21 native payloads**, all **148 runtime entries** and the runtime manifest
are byte-identical to physical code 34. No native rebuild. The existing
[v15 corresponding source archive and both saved parts](code34-recovery.md#matching-corresponding-source)
remain an exact match to the current native reproduction inputs; verified SHA256
`02695902ac274ad33feb881b1d2f53f9933c7a2a6db08fe164fa27edc1a8c98c`.
The Android launcher changes are public at the application commit above.

## Next physical action

1. Install this private update **in place** over code 34.
2. Select **Inspect client folder**, then choose the complete already extracted
   WarcraftXL client folder containing **Wow.exe**. Keep the launcher visible.
3. When inspection ends, select **Export diagnostic report** and return the JSON.
   If it fails or is interrupted, export before another attempt.

This is a read-only inventory and executable-metadata check. It does not copy,
import, launch or modify the client. Folder names, document URIs, configuration
contents, account data and raw executable strings are not exported. MPQ counts
are metadata, not full asset hashes. See [bounds and scope](client-inspection.md).

Preserve the realm, backups and imported assets. The complete native world gate,
including screen-off and clean Stop, already passed; **do not repeat it** for this
milestone. Managed client import, exact WarcraftXL/host pairing, Wine/translator
IPC and playable login remain unqualified pending actual client evidence.
