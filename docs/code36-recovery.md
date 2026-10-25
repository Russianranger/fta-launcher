# Code 36 private managed client import update

**Historical build:** the physical copy was interrupted at 4.87 GB. Use
[code 37](code37-recovery.md) for the qualified memory-backpressure update and
resume the retained checkpoints. Do not repeat the old code-36 run.

[FTA-Launcher-0.13.1-client-import.apk](https://chatgpt.com/library?file_id=libfile_07e3d6c61ed88191be1ea56e13ec5e76&account_id=personal)

**Version 0.13.1-client-import / code 36**, 59,061,517 bytes. SHA256:
`8feb0cff4bf08ae740be0a038c991e80ce3d9c04eb749a0af60968366baeca8a`.
Persistent certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`;
v2 signing, 16 KiB alignment and all 225 packaged-input checks passed.
[Measured APK proof](evidence/android-2026-10-25-private-client-import-apk.json).

Application commit **7f2c59df3579044f70777f7d2f5382f088257e46**;
[CI 40208480192](https://github.com/Russianranger/fta-launcher/actions/runs/40208480192)
passed all three jobs. 186 tooling tests; 437 Android tests passed with one
existing private-fixture skip, zero errors/failures, 38 suites. This includes 32
new import regressions. Lint, assembly and native cache-helper checks passed.
The earlier application commit 6921a35 also passed CI 40208093273; the final code
adds exact candidate membership validation and its negative regression.

All **21 native payloads**, all **148 runtime entries** and the runtime manifest
are byte-identical to code 35 (and the native code-34 world pass). No native rebuild.
The [existing v15 corresponding source archive and both saved parts](code34-recovery.md#matching-corresponding-source)
still match every native reproduction input; verified archive SHA256
`02695902ac274ad33feb881b1d2f53f9933c7a2a6db08fe164fa27edc1a8c98c`.
Android changes are public at the application commit above. Workspace recovery
verified the saved donor and source archive and extracted only the already-pinned
native payloads; no signing key was regenerated and no original native build was
repeated. Public CI APKs remain foundation previews, not this private update.

## Next physical gate

1. Allow about **62 GB free internal storage** for the 60.24 GB client and reserve.
   Install this update **in place** over code 35.
2. Choose **Import / resume client folder**, select the same complete extracted
   folder containing **Wow.exe**, and keep the launcher visible until complete.
3. Reopen the app, choose **Verify imported client**, then **Export diagnostic
   report** and return the JSON.

On failure, interruption or a deliberate Stop, export before resuming. The original
folder must remain unchanged. Durable chunks are rechecked on resume and retained
without rewriting them; uncommitted tails are recopied. No original files, active
client generation, realm, imported server assets or backups are deleted.

This qualifies a new copy/retained-integrity milestone. It does not run Wine,
WarcraftXL or the client. Exact x64 host pairing and playable login remain open.
The code-35 inspection and code-34 native world/screen-off/Stop gates have already
passed; **do not repeat them**. See [implementation and bounds](client-import.md).
