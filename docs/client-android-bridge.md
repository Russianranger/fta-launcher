# Android client bridge build

The synthetic ARM64 Linux Wine/FEX process and IPC gate
[passed](evidence/host-2026-10-26-mixed-wine-runtime-pass.json). Android execution
needs a PRoot tracer/loader launched through the existing owned native process
bridge. No new service, APK payload or original client execution is enabled yet.

## Exact sources and build

`tools/build_client_bridge.py` verifies the UO v0.2.0
`runtime-corresponding-sources.tar.gz` SHA256
`492043660b1370e1910c8b8ca2b93be1f637f4929034f575591f14e67e015243`, then recovers only
five pinned source/recipe inputs. It uses Termux PRoot commit
`7266fb3e8516535682f5a9c8f3a7e70f6506eddb`, talloc 2.4.3, and the existing public
Android build recipe with its seccomp-observation and SysV memfd patches. The
recipe also supplies the original missing-header and loader-symbol helper fixes.
No other project is modified and the build performs no source download.

The measured prebuilt loader from `runtime-bridges.zip` had 4 KiB load alignment;
it is not staged. The new build uses this repository's pinned NDK r28c, API 26,
one compiler job after the existing memory check, and explicit 16 KiB alignment
on the loader's separate link rule. Main tracer alignment stays 16 KiB.
The original compatibility patches are retained. In addition to local source
selection, NDK, job count and loader alignment, the FtA ownership patch requires
`PTRACE_O_EXITKILL` in the default tracing options. Both SETOPTIONS attempts retain
it; failure aborts before guest entry. Fork/clone children inherit the option.
This closes a tracer-SIGKILL gap that normal PRoot atexit/signal cleanup cannot
cover, including descendants that leave the original process group/session.

Run outside Git with the two verified public archives:

```sh
python3 tools/build_client_bridge.py \
  --sources /private/runtime-corresponding-sources.tar.gz \
  --ndk-zip /private/android-ndk-r28c-linux.zip \
  --work /private/new-client-bridge-build
```

The new work directory retains original sources, generated recipe, toolchain,
build log and output; existing directories are refused. It must remain outside
Git. Rebuilds have newly measured identities; no reproducible-byte claim is made.
The [measured build](evidence/host-2026-10-26-android-client-bridge-build.json):

| Output | Bytes | SHA256 | Minimum load alignment |
|---|---:|---|---:|
| libproot.so | 263712 | `46dcafabc77b386a7127f6cb7c71bdac03c372ff6bb4db0e1e9493ee29332adb` | 16384 |
| libproot-loader.so | 18296 | `614591192bb34c98cc6cef528fb74ecfc1c97d0fabf4e352df1f16873c71d447` | 16384 |

The tracer is ARM64 PIE using `/system/bin/linker64`, with only `libc.so` and
`libdl.so` dependencies and no RPATH/RUNPATH. The freestanding loader is ARM64
ET_EXEC, has no interpreter/dynamic dependencies, requires a file-backed executable
entry, non-writable executable segments, and a non-executable stack. Its separate
validator does not weaken the existing PIE requirement for server payloads.
Fourteen synthetic regressions cover architecture/type, program-table/segment bounds,
4 KiB and incongruent mappings, dynamic linkage, entry, stack, source identity,
duplicate source members, recipe drift, and exact process-image/mapping identity.

## Tracer-death regression

`tools/check_client_bridge_ownership.py` builds the original and patched PRoot
on native Linux, one job, from the same pinned sources/compatibility patches.
Its fixture creates three processes, including two descendants in separate
sessions. An independent subreaper verifies the known loader plus the exact executable
fixture mapping before retaining pidfds for signals,
kills only its tracer and observes descendant lifetimes. The negative control
requires all three original tracees to survive; the fixed case requires all
three to die. A normal three-process exit must still return zero. Fixture alarms,
readiness deadlines and pidfd cleanup bound failure paths; no client is loaded.

**Passed on native ARM64 Linux** at commit
`969660f0d9c028537d1be53e735bedf1ed50e655`,
[CI 40400465375](https://github.com/Russianranger/fta-launcher/actions/runs/40400465375),
[exact proof](evidence/host-2026-10-26-proot-ownership-pass.json).
Normal tree exit is zero; original tracer death leaves all three tracees alive;
patched tracer death terminates all three, including both detached session leaders.
The same run repeats the full x86/x64 Wine process/IPC/owner-loss gate successfully.

The Linux fixture builds only its native ELF64 loader and uses PROOT_LOADER;
the optional ARM32 loader make dependency is disabled because ARM64 GCC has no
`-m32`. Original arch.h and the Android build are unchanged by this fixture option.
The local workspace denies PTRACE_TRACEME, so live qualification comes from this
ARM64 CI artifact. This does not replace Android process-death/Stop observation.

## Distribution and next gate

PRoot source files specify GPL-2.0-or-later; talloc specifies LGPL-3.0-or-later.
Their original archives and notices are recovered. Before distributing a new APK,
assemble its exact corresponding source companion, including the original archives,
patches, this build driver/helpers, generated recipe and complete license notices.
Full runtime Debian/Wine/FEX source/notice closure remains separate work. No bridge
binary is committed or currently bundled in the application.

Next implement the owned Android diagnostic service, bounded runtime installation,
and disposable Wine prefix. Preserve native parent-death/Stop supervision and
verify CPU topology through Android's actual PRoot path. Synthetic Windows fixtures
must pass on Thor before original host/graphics/client claims. The later client
view must preserve imported files: no writable hard links or unbounded 60 GB copy.
Installed private code 37 and all passed import/server gates remain unchanged.
