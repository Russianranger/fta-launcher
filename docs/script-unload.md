# Shutdown hook lifetime correction

Physical code 31 passed 19 combined healthy samples and **144,884 ms of healthy
screen-off operation**, exceeding the 120,000 ms duration requirement. Its full
22,817-file retained rehash, metadata/GT prechecks, native DBC loading, challenge
and freeze guard passed. Log pressure stayed below the unchanged 8 MiB cap.
However, Stop failed after the Halting message: world exit 139, no SQL shutdown
observation and no final metadata/GT checks. The aggregate runtime gate remains
false. [Physical evidence](evidence/android-2026-10-24-thor-code31-runtime.json).

The attempt-matched fault witness initialized and captured SIGSEGV, code 2, an
unknown executable PC and world-relative LR `0x220b8ac`. Against the verified
original ELF SHA256 `97e578f7e3013715c2d9254dc29dd87d4a33598583093110eaad24157ee67766`,
LR falls inside `ScriptMgr::OnDestructObject(Object*)`, offset `0x40` from symbol
start `0x220b86c`. The immediately preceding ARM64 instruction at `0x220b8a8` is
`blr x8`, after loading the callback from vtable byte slot `0x30`.
This locates a virtual-hook dispatch; it does not reveal the target address,
identify the specific script instance or prove a complete native stack trace.

## Source defect and narrow correction

Original `MiscScript.cpp` dispatches `OnDestructObject` through
`ScriptRegistry<MiscScript>::EnabledHooks`. `Object::~Object` calls it. Original
`ScriptMgr::Unload` deletes all 48 registries' owned script objects and clears
their owning maps, but leaves the enabled-hook vectors pointing at deleted
objects. Later object destruction or reentrant destruction can invoke them.

Patch `0018-detach-enabled-hooks-before-unload.patch` adds one pass over the same
48 registry types before any script deletion. It clears each inner hook list,
retaining the outer indexed slots required by the unchanged dispatch macros.
It preserves deletion order, owning-map cleanup, every active callback before
Unload, all 52 source modules, NPCBots, SQL and original configurations. It does
not skip object destructors or suppress the world Stop failure check.

Profile `android-arm64-world-v14` contains every v13 patch unchanged plus this
single-file patch. Full source tree SHA256:
`c85f87ebc192e78263fa14789223628c9df6e225bc0a632e67f3a1a720db11f5`.
The original source and v13 profile remain intact.

## Qualification and limits

`tools/check_script_unload.py` extracts unchanged original registry, TypeList,
hook enum, dispatch macro, dispatch function and Unload body from the exact source
ZIP into a temporary fixture. It applies only the pinned patch for the fixed
variant. Three synthetic script types model early, callback and late destruction;
this is not a full-world fixture and does not replace an Android test.

Original active callbacks pass. Both a post-unload callback and reentrant callback
reproduce an ASan heap-use-after-free. The fixed body passes all modes in Release
and ASan/UBSan, retaining callback counts during operation, deletion counts and
hook slot dimensions. LeakSanitizer is not claimed. No native database or Android
execution occurs in the host fixture.
[Host evidence](evidence/host-2026-10-24-script-unload.json).

The matching dispatch location and reproduced source defect support this repair;
physical resolution remains unproven until the rebuilt world passes Stop and
postconditions. Keep `databaseFallbackValidated=false` and world readiness false
until that complete native gate passes. No player/client login is qualified.

Reproduce with one compiler job and logs outside the public checkout:

```sh
python3 tools/check_script_unload.py --source-zip /private/From_The_Ashes_Source.zip \
  --output /private/unload-proof.json --log-directory /private/unload-logs
```

During toolchain recovery, pinned Boost B2's `--version` printed the version but
returned 1. Its supported `-v` returned 0. The dependency driver now uses `-v`,
with a composition regression. Completed earlier build steps were retained and
the exact recorded final Boost build/install command resumed; no source, library
or version check was bypassed.

## Completed ARM64 repair build

The v14 world executable is 62,665,968 bytes, SHA256
`a7f7d3f2018703f931da68031972d1468a0c120d8f815608fcaf0e153322b20e`.
All 52 modules / 224 module translation units, static scripts and Lua 5.2, C++20,
actual link inputs and 16 KiB ELF alignment passed independent verification.
[Build evidence](evidence/android-2026-10-25-world-session-build.json).
[Additive integration](world-session-repair.md) preserves retained identities.
Physical repair validation remains pending; compilation does not qualify Stop.
