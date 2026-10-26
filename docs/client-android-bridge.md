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
Original runtime behavior and patches are retained; only the local source
selection, NDK, job count and loader link alignment change from the public recipe.

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
| libproot.so | 263696 | `5f9ec53745d720e23fffc2b7f824c85877910001d2eb489322bffe2c48ff01ad` | 16384 |
| libproot-loader.so | 18296 | `614591192bb34c98cc6cef528fb74ecfc1c97d0fabf4e352df1f16873c71d447` | 16384 |

The tracer is ARM64 PIE using `/system/bin/linker64`, with only `libc.so` and
`libdl.so` dependencies and no RPATH/RUNPATH. The freestanding loader is ARM64
ET_EXEC, has no interpreter/dynamic dependencies, requires a file-backed executable
entry, non-writable executable segments, and a non-executable stack. Its separate
validator does not weaken the existing PIE requirement for server payloads.
Eleven synthetic regressions cover architecture/type, program-table/segment bounds,
4 KiB and incongruent mappings, dynamic linkage, entry, stack, source identity,
duplicate source members and recipe drift.

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
