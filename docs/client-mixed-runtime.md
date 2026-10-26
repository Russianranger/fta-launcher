# Synthetic mixed-architecture Wine/FEX gate

The matched client/DLL/proxy are x86; the matched asset host is x64. Before running
original files, qualify the cross-architecture Windows process/IPC boundary with
synthetic executables on native ARM64 Linux. This is distinct from Android,
PRoot, graphics, original host requests, WarcraftXL and gameplay qualification.

## Pinned candidate

Reuse the public [UO launcher v0.2.0 runtime](https://github.com/Russianranger/uo-android-launcher/releases/tag/v0.2.0),
also referenced by the EVE launcher. No other repository is modified. Its exact
612,917,105-byte archive SHA256 is
`f036c00a290abb953bec26be80c4d8fe492fd986e7a589c51008124432c8641e`;
450-byte manifest SHA256 is
`d375adf23b621f83ef7b5fff95365312377399704abb486fd8d4164a1fe29a13`.
Wine commit `a6844d10622fc1a973ec1f22fc4f78a0fcd6cb29`; FEX commit
`320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab`. The existing build recipe selects
Wine arm64ec/aarch64/i386 and both FEX translation DLLs.

The [lock](../android/qualification/client-runtime-lock.json) pins the manifest,
archive, marker, 12 critical component identities and three source archive
identities. Source archive hashes here come from published release metadata;
those archives have not yet been downloaded or license/source closure audited
for a new Android distribution. The runtime is a candidate, not an APK payload.
The AMD64 PE machine field in libarm64ecfex.dll is recorded literally; it is not
independently treated as proof of that translator's execution architecture.

The [read-only audit](evidence/host-2026-10-26-wine-fex-static-audit.json) verifies
all 14,092 archive entries / 2,637,799,241 file bytes and the 12 component hashes.
Wine/wineserver are ARM64 ELF64; i386 Windows libraries and both translator DLLs
are present. Input hashes are checked before parsing. A streamed scan rejects
unsafe/duplicate paths, sparse/special files, traversal links, nonregular required
components and any archive member beneath a symlink/hardlink. Guest-root absolute
symlinks are allowed only under that no-descendant rule. The audit extracts and
executes nothing, and leaves all runtime qualification flags false.

## Executable fixture

The separate `Synthetic mixed Wine runtime` workflow runs on `ubuntu-24.04-arm`.
It cross-compiles two small MIT fixture programs sequentially, audits the pinned
archive, then uses a new disposable chroot/prefix. Original host, client, configs,
assets and database never enter public CI. No native translator rebuild occurs.

The x86 parent launches its x64 sibling with its actual Windows PID and inherited
environment. The child creates fixture-only named mappings and auto-reset events;
headers use the measured original magic/protocol/layout/stride. Synthetic request
words do not implement original WarcraftXL operations. Two- and sixteen-channel
cases require 64 and 512 exact request/response round trips, then explicit Stop
and child exit 37. A separate x64 observer retains process handles while an x86
parent exits; its x64 child must observe parent loss and exit 38. All waits have
deadlines and failure cleanup is scoped to the fixture's owned process handles.

Logs and exact fixture hashes are retained as a CI artifact. Wine services are
finally stopped with `wineserver -k`; this cleanup is forced and does not qualify
graceful Wine/client shutdown. The outer chroot and display are disposable.
Do not infer Android process ownership, native display, original-host IPC or
playable login from a Linux ARM64 fixture pass. The installed private APK stays 37.

Ten synthetic archive regressions join the qualification suite (62 total).
CI execution evidence for the new mixed-runtime workflow is pending until its
exact pushed commit completes; archive presence alone does not pass that gate.

Next after this gate: recover and pin the Android PRoot/loader bridge and its
source/license closure, prepare an owned service and writable runtime view,
then one substantial Thor mixed-runtime/original-host test. No repeat of the
passed client import, retained rehash or native server gates is requested.
