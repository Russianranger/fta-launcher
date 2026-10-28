# Guarded Windows file access

The code-43 gate uses new synthetic PE32/PE64 fixtures and the exact code-42
`libproot_ro.so` / retained loader. It never opens the imported client. All 25
native APK payloads must remain byte-identical to the code-42 donor.

The Android action is **Test protected Wine access**. It preserves the separate
code-41 runtime and code-42 storage receipts. A fresh owned attempt holds the
runtime lease, performs full pre/post runtime rehash, and checks normal exit,
controlled Stop and forced tracer death before Wine starts. Its six stages are
CPU topology, display, prefix, x86 storage, x64 storage and Wine Stop. Each
architecture must complete 28 ordered checks, including mixed-architecture
child startup. All owned handles must be reaped before success.

The disposable source contains a 64 KiB canary, the synthetic executables and a
26-byte configuration. `ClientWorkingView` creates guest `/immutable` links for
shared data, independent config copies and writable standard directories. The
actual native guard, not the symlink, prevents source mutation. The builder
rejects symlinks, special files, Windows-ambiguous names, case collisions,
existing/nested output and inventories beyond the existing client limits. It
copies at most 64 MiB, keeps the 1 GiB reserve, syncs and reads back copies, then
atomically publishes only its fresh view. It never copies the whole client.
Future original-client use must hold `ClientStore.withVerifiedClient` until all
readers end; this release does not expose that path.

The controller requires 3 GiB free after reclaiming only positively retired
probe attempts. It polls view size (128 MiB) and prefix size (2 GiB) every five
seconds, applies existing memory/space handling and controlled Stop. These are
polling thresholds, not filesystem quotas or hostile-code confinement.

Protected read/mapping/private-copy, write/truncate/create/rename/attribute/link
refusals, independent config/log operations and child access are checked. On
native ARM64 Linux, Wine's DeleteFile returns success while the guard prevents
the underlying deletion. Consequently `delete-preserved` checks that the file
still exists with its original size/content, records the Windows return/error,
and independently compares the entire source hash/metadata inventory after all
tracees exit. This behavior must not be described as a Windows API error return.

`clientGuardedWine` exports only fixed stage/check names, numeric errors, hashes,
bounds and ownership/memory observations. `guardedWineStorageValidated` requires
all checks, source integrity, runtime rehash, independent copies and cleanup.
Original import access, original working-view qualification, WarcraftXL startup,
rendering and login remain false/unqualified. No raw guest output is exported.

Reproduction: `.github/workflows/guarded-wine.yml` runs native ARM64 Linux against
the pinned Wine/FEX archive; foundation builds the Android controller, runs unit
and tooling qualification, stages the exact sources and compiles both synthetic
PEs sequentially. `tools/stage_guarded_wine.py` verifies all six packaged assets
and native identities and appends corresponding source evidence. A physical Thor
report is still required; a Linux pass does not establish Android execution.
