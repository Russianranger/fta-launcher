# Code 32 private delivery and recovery

[FTA-Launcher-0.12.5-world-stop.apk](https://chatgpt.com/library?file_id=libfile_2ac409c7f814819181b82c6ec4020e02&account_id=personal)

APK SHA256 `abaa6cbc199817cecd016db8b542642b9561da86819a824b3887ddf3ca7e0fda`,
58,987,731 bytes. Install in place; use the [one Thor gate](world-session-repair.md).
The sources and private cache below are recovery inputs, not installers.

The original large archives are preserved as consecutive byte parts. Download
all parts for the desired archive into one private directory. Concatenate in
numeric order, then verify the complete hash before opening. All individual part
sizes/hashes are in [the saved-artifact proof](evidence/android-2026-10-25-private-recovery-artifacts.json).

## Matching corresponding source

- [FTA-Launcher-0.12.5-corresponding-source.zip.part1](https://chatgpt.com/library?file_id=libfile_c5d99a5e7f0481918e4195cba39e4c64&account_id=personal)
- [FTA-Launcher-0.12.5-corresponding-source.zip.part2](https://chatgpt.com/library?file_id=libfile_aa43c3ea3048819197c7ec01c2ead6fd&account_id=personal)

```sh
cat FTA-Launcher-0.12.5-corresponding-source.zip.part1 FTA-Launcher-0.12.5-corresponding-source.zip.part2 > FTA-Launcher-0.12.5-corresponding-source.zip
sha256sum FTA-Launcher-0.12.5-corresponding-source.zip
```

Expected SHA256 `d824893644c3167193630bc4813866272b2fefe46d6cb3a5f13b0a9914acc8d0`; 540,086,927 bytes.

## Completed private native build cache

- [FTA-World-v14-private-build-cache.tar.zst.part1](https://chatgpt.com/library?file_id=libfile_32cac90afe0c8191bb2a07a8b213794b&account_id=personal)
- [FTA-World-v14-private-build-cache.tar.zst.part2](https://chatgpt.com/library?file_id=libfile_2493033f72208191a8c27d6079f7858c&account_id=personal)
- [FTA-World-v14-private-build-cache.tar.zst.part3](https://chatgpt.com/library?file_id=libfile_4b1a6f4c464481919601a247848a6af0&account_id=personal)

```sh
cat FTA-World-v14-private-build-cache.tar.zst.part1 FTA-World-v14-private-build-cache.tar.zst.part2 FTA-World-v14-private-build-cache.tar.zst.part3 > FTA-World-v14-private-build-cache.tar.zst
sha256sum FTA-World-v14-private-build-cache.tar.zst
```

Expected SHA256 `f4c76e94d9941b97da45d573a9fdd82211ba11bfc9884afa2acf0a045b8cf624`; 909,252,695 bytes.

The source archive contains every original source archive and notice, the
reviewed v14 reproduction sources and actual build evidence. Its byte identity
matches the source companion verified during private APK packaging.

The cache contains the verified v14 source, completed build and target dependency
prefix; it contains no signing key, live database or imported game assets. Keep
compiled private binaries out of Git. Read `RECOVERY.json` before restoring.
Restore the pinned NDK at the recorded absolute prefix and apply the exact
nanosecond times using `RESTORE_TIMES.py` / `NANOSECOND_TIMES.json` before Ninja.
Use the code-31 provider stage if CMake needs its recorded `MYSQL_EXECUTABLE`.
Do not rebuild completed objects merely because a session or scratch directory
was lost. One compiler job by default, at most two after memory checks.

The cache was streamed and checked without extraction: 24,120 regular files,
32,573 timestamp records and the exact repaired world ELF. This qualifies
recovery bytes, not Android runtime or graceful shutdown.
