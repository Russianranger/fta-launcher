# Code 34 private diagnostic and source recovery

[FTA-Launcher-0.12.7-auction-count.apk](https://chatgpt.com/library?file_id=libfile_8fa18bc7cfe0819199ea34f580703c71&account_id=personal)

APK SHA256 `45ade0fd1cb78c52527f46bfe0c0ac338c40d8948debb838d1bb0db85a6431a8`,
**59,000,019 bytes**. Persistent certificate, v2 signing, 16 KiB alignment and
225 packaged-input checks pass. Twenty native payloads and all 148 runtime entries
match code 33; only the separate world-session executable changes to reviewed v15.
[Measured APK proof](evidence/android-2026-10-25-private-auction-count-apk.json).

Application **0885153f71061751bb7e189ed8d8e52479badfcb**, exact
[CI 40155455259](https://github.com/Russianranger/fta-launcher/actions/runs/40155455259):
all three jobs passed; 186 tooling tests, Android 383 passed/one existing
private-fixture skip, no failures/errors, lint/assembly passed. The original-code
fixture and single-job ARM64 build passed separately. Public CI downloads are
foundation previews, not this private diagnostic.

**Install in place. Start native world test once, wait for healthy samples,
lock the screen for three minutes, reopen, Stop once, then Export diagnostic
report.** Export immediately on any failure before retrying. Preserve all data;
no reimport or repeated standalone database/auth tests.
[Diagnosis and full gate](auction-count.md).

## Matching corresponding source

The complete archive preserves all 178 original source/notice members and adds
v15's reviewed patch, current reproduction tools and measured build evidence.
Download both consecutive byte parts into one private directory:

- [FTA-Launcher-0.12.7-corresponding-source.zip.part1](https://chatgpt.com/library?file_id=libfile_69e672469cc8819184849abc0d4a52ef&account_id=personal)
- [FTA-Launcher-0.12.7-corresponding-source.zip.part2](https://chatgpt.com/library?file_id=libfile_75a7323ebf888191b52ba626ed51cde8&account_id=personal)

```sh
cat FTA-Launcher-0.12.7-corresponding-source.zip.part1 FTA-Launcher-0.12.7-corresponding-source.zip.part2 > FTA-Launcher-0.12.7-corresponding-source.zip
sha256sum FTA-Launcher-0.12.7-corresponding-source.zip
```

Reassembled SHA256 `02695902ac274ad33feb881b1d2f53f9933c7a2a6db08fe164fa27edc1a8c98c`,
540,135,812 bytes. [Part identities](evidence/android-2026-10-25-code34-source-recovery.json).
The original [v14 cache](code32-recovery.md) remains the recovery baseline. Restore
its toolchain/timestamps, apply patch 0019 and build v15 with one job; only the
seller object and module/world relinks need rebuilding. Preserve the signing
capsule. Private binaries, source archives, database and assets stay out of Git.
