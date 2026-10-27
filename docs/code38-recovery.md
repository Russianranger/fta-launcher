# Code 38 — private client runtime diagnostic

[FTA-Launcher-0.14.0-client-probe.apk](https://chatgpt.com/library?file_id=libfile_1e7885dd71b881918205c1a4a90c7759&account_id=personal)

**Version 0.14.0-client-probe / code 38**, 60,139,725 bytes. SHA256:
`32f4480d11e8af20402d2db7f679cbe1f36b9601c07338bd280cf3610e7907e2`.
Original signing certificate
`3e6705b6dc4a6bf3e7109f5322ec51d5ff094a4d912560cd9c1bb826f88754a6`;
v2 signature, 16 KiB alignment and **235 packaged-input checks** passed.
[Measured APK proof](evidence/android-2026-10-27-private-client-probe-apk.json).

Application commit **e22f9b563b5f532da6cb5245f382c31039a8f8aa**;
[foundation CI 40424860004](https://github.com/Russianranger/fta-launcher/actions/runs/40424860004)
passed all three jobs: 205 tooling tests, 73 qualification tests, 515 Android
tests passed / one existing private-fixture skip, 45 suites, lint/assembly,
cache/JNI, packaged checks and full runtime installation under a 96 MiB heap.
[ARM64 CI 40424860026](https://github.com/Russianranger/fta-launcher/actions/runs/40424860026)
passed the exact guest program, original mixed Wine tests, ownership cases, and
full pre/post rehash through the Android directory layout under Linux PRoot.
[Host evidence](evidence/host-2026-10-27-client-runtime-path-pass.json).
Linux results do not qualify the Android kernel/runtime path.

All **21 retained native payloads**, **148 runtime entries**, server metadata and
notices are byte-identical to code 37. Two separately verified 16 KiB PRoot
components bring the native count to 23. Small synthetic x86/x64 fixtures and
their notices are added; no original client, game assets or database are bundled.
The original compiled world and its 52 modules are unchanged. No native server
rebuild, signing-key regeneration or data reseed was performed.

## First physical gate

Code 38 has **not yet run on Thor**. Keep existing storage and install this APK
**in place**. Do not uninstall, clear data, reimport the client or Data.zip, or
repeat completed database/auth/world/screen-off tests.

1. Allow at least **6 GiB free internal storage** and keep the launcher visible.
2. Select **Prepare client runtime** once. It downloads the pinned 612,917,105-byte
   public runtime, verifies every byte and publishes a separate runtime tree.
3. After preparation completes, select **Test client runtime** once. This fully
   rehashes the runtime, tests normal/Stop/forced tracer-death ownership, runs
   synthetic x86/x64 entry and IPC in a disposable prefix, stops Wine, and
   rehashes the runtime again. The managed client and realm are not opened.
4. Select **Export diagnostic report**. On either failure, export immediately
   before another attempt. Stop is available throughout.

Expected runtime archive SHA256:
`f036c00a290abb953bec26be80c4d8fe492fd986e7a589c51008124432c8641e`.
The installer uses the passed code-37 memory/I/O safeguards. Native pressure stops
the owned process tree. Interrupted synthetic attempts remain private for evidence;
at most three are permitted, with a 2 GiB free-space gate before each probe.
Passing this gate still does not qualify original WarcraftXL, graphics or login.

## Corresponding sources

Both source sets accompany the update. They are recovery/build inputs, not
installation inputs:

- [Client bridge and fixture sources](https://chatgpt.com/library?file_id=libfile_f179129918fc819184d2c234d7ad330e&account_id=personal),
  `FTA-client-bridge-corresponding-source.zip`, 2,456,082 bytes,
  SHA256 `4965825cc78172c1bb967ae55be2293c04f36ef222eda1508c6fbb445b48e89d`.
  Exact PRoot/talloc archives, GPL/LGPL/MIT notices, patches, generated recipe,
  build evidence and the qualified public source snapshot are included.
- [Retained server sources — part 1](https://chatgpt.com/library?file_id=libfile_48e9f5cc1aac8191bebc2bc388b075f0&account_id=personal)
  and [part 2](https://chatgpt.com/library?file_id=libfile_ceb13c41e9a88191a8b34b6fd67db8a5&account_id=personal).
  Reassemble the two consecutive byte parts as below. Every original native
  archive/notice and the v15 build evidence remains byte-identical; only public
  reproduction/packaging helpers are refreshed. [Exact part identities](evidence/android-2026-10-27-code38-source-recovery.json).

```sh
cat FTA-Launcher-0.14.0-corresponding-source.zip.part1 FTA-Launcher-0.14.0-corresponding-source.zip.part2 > FTA-Launcher-0.14.0-corresponding-source.zip
sha256sum FTA-Launcher-0.14.0-corresponding-source.zip
```

Reassembled size **540,190,148 bytes**, SHA256
`70637f0c662509fd43d79a03463e46631e8cfcc2a0360e1990542a4dea9bd7f0`.
`tools/refresh_world_session_source.py` requires the exact previous companion,
rejects changes to retained native reproduction inputs, preserves the original
source checksums/build proof, and runs the unchanged strict final verifier.
The unchanged Wine/FEX download retains its package notices and version list;
its pinned upstream source archives and recipe are linked in the new APK notice.
