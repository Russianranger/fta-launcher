# Code 33 private diagnostic and source recovery

[FTA-Launcher-0.12.6-log-evidence.apk](https://chatgpt.com/library?file_id=libfile_0c8e511f801c8191b428838b78088c74&account_id=personal)

APK SHA256 `c1b6f5434f1a43852ba7948f69232a7da51e53e56848eb872cdfda0f43fc1553`, **59,000,019 bytes**.
Persistent certificate, v2 signing, 16 KiB alignment and 225 packaged-input
checks passed. All 21 native payloads are byte-identical to code 32.

**Install in place, reopen, and Export diagnostic report only.** Do not start
another world session, import or verify game data before recovering the retained
code-32 attempt. [Evidence and procedure](world-log-recovery.md).

## Matching corresponding source

The complete archive preserves all 178 original source/notice members and adds
the current reproduction tools and build evidence. Download both consecutive
byte parts into one private directory:

- [FTA-Launcher-0.12.6-corresponding-source.zip.part1](https://chatgpt.com/library?file_id=libfile_8e7cec68b6d48191b98555a5b6035892&account_id=personal)
- [FTA-Launcher-0.12.6-corresponding-source.zip.part2](https://chatgpt.com/library?file_id=libfile_c8ff19ec00308191ab9db193834cf95e&account_id=personal)

```sh
cat FTA-Launcher-0.12.6-corresponding-source.zip.part1 FTA-Launcher-0.12.6-corresponding-source.zip.part2 > FTA-Launcher-0.12.6-corresponding-source.zip
sha256sum FTA-Launcher-0.12.6-corresponding-source.zip
```

Expected SHA256 `c929ce36a13a73389eb8ad182b52393cf9a81811343cd1a00b3f31fe2db59db6`; **540,090,184 bytes**.
Individual part sizes/hashes are in the [source recovery proof](evidence/android-2026-10-25-code33-source-recovery.json).

The [existing v14 private build cache](code32-recovery.md) remains valid. No
native rebuild or new cache was needed. Preserve the original signing capsule,
retained realm, assets and verified backup. Private binaries and source archives
stay out of the public repository.
