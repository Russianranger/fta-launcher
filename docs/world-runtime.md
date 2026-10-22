# Private ARM64 world runtime staging

`tools/stage_world_runtime.py` extends the exact reviewed database/auth stage
with an actually completed original worldserver build. It preserves the
historical authserver and database provider, verifies the compiled source/module
and dependency evidence, and adds the world payload, original module defaults,
updater metadata and notices in a new private stage. The stager never starts a
native process or imports a database.

The completed original Android world build and actual private staging passed
under reviewed profile `android-arm64-world-v13`. The private stage was verified
against the original source, completed CMake target evidence and pinned base.
See [the stage receipt](evidence/android-2026-10-22-private-world-stage.json).
APK packaging and the physical-device updater run remain separate milestones.

| Evidence | Current status |
| --- | --- |
| Final world source profile and completed all-module build report | Passed `android-arm64-world-v13`; tree `847f2e60…bd0e064` |
| World ELF SHA256, byte length and inspected dependency closure | Passed; 62,661,136 bytes; SHA256 `97e578f7…e67766` |
| Private world-stage inventory and preservation verification | Passed; 221 APK input files; all 96 base runtime and 17 base legal files unchanged |
| Signed world diagnostic APK SHA256 and packaged-file verification | Pending APK build |
| Compiled Login/Character/World updater execution on Android | Pending physical-device milestone |
| World initialization, listener, game login and gameplay | Unqualified |

The final canonical updater lock binds the same v13 source profile/tree and has
SHA256 `466c1c6673d7f939b14c4b6f4dc787efa58ac6c8fa91e982c76151a3f1f8c02f`.
The verified stage has 148 runtime files (15 native and 133 data), 72 legal files
and the runtime manifest. All 49 original module defaults are included. The
historical resolver-v3 authserver remains unchanged; the newly compiled world
build's auth prerequisite is recorded separately and is not packaged over it.

## Inputs and command

Keep source inputs, dependencies, builds and outputs outside the checkout.
The build report and source directory must describe the same final reviewed
profile in `native-port/profiles.json`.

```sh
python3 tools/stage_world_runtime.py \
  --base-stage /private/fta-auth-runtime \
  --worldserver /private/android-world-build/src/server/apps/worldserver \
  --authserver /private/android-world-build/src/server/apps/authserver \
  --target-libs /private/android-dependencies/lib \
  --build-report /private/android-world-build/fta-build-report.json \
  --source /private/fta-world-final-source \
  --build-dir /private/android-world-build \
  --source-archive /private/inputs/From_The_Ashes_Source.zip \
  --source-archives /private/inputs/dependency-sources \
  --dependency-notices /private/inputs/dependency-notices \
  --ndk /private/android-ndk-r28c \
  --updater-lock manifests/world-updater-lock.json \
  --dependency-provenance /private/world-dependency-evidence.json \
  --readelf /private/android-ndk-r28c/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf \
  --output /private/fta-world-runtime
```

`--updater-lock` defaults to the public canonical lock above; `--readelf`
defaults to the host `readelf`. All other flags are required. The executable
paths must be the real artifacts identified by the completed build report.
The auth artifact is a prerequisite of that world build. It does not replace or
requalify the historical authserver already present in the base stage.

The dependency source directory uses the exact filenames in
`licenses/world-runtime/SOURCE_EVIDENCE.json`: Boost, Connector/C, OpenSSL,
Readline, ncurses and Lua archives plus all three official Readline patches.
The notice directory contains the separately pinned full dependency notices.
The stager verifies their bytes and NDK revision before producing output.

The output must be fresh and outside every input tree. The tool builds a sibling
temporary stage, verifies its payloads, and publishes it by rename. A failure
cleans its temporary output and leaves the existing inputs intact.

## Preserve the base and original module defaults

`manifests/auth-runtime-lock.json` pins the exact base manifest, its 96 runtime
files and 17 legal files. The runtime inventory contains 13 native ELF files and
83 data files. Every declared payload and legal notice is hash-checked, and
undeclared files or filesystem links in the base are refused. All 96 runtime
payloads, all 17 legal files and the recovered historical auth proof retain their
original bytes. The extended runtime manifest records the added world capability
and entries; the original provider/auth provenance remains separate.

The worldserver is added as `jniLibs/arm64-v8a/libfta_worldserver.so`. Only shared
libraries reached through its actual `DT_NEEDED` closure are eligible for
addition. The reviewed new dependency is GNU Readline 8.3.3; the existing
provider's ncurses and Android support libraries retain their original bytes.
No stage operation strips, patches or rewrites an ELF file.

The supplied source has 49 module configuration templates across its 52 module
directories. Each original `modules/*/conf/*.conf.dist` file is copied without
changing its contents, with the `.dist` suffix removed, under
`assets/runtime/data/world-config/modules/`. The stager rejects unsafe or
colliding names and compares the complete list with the actual compiled
`CONFIG_FILE_LIST`. These are source defaults, rather than saved user settings.

The Android world build uses `CONF_DIR=configs`. During the disposable updater
diagnostic, the app materializes those exact 49 signed defaults into
`configs/modules` beneath the supervised process working directory. The native
module loader therefore reads the complete source-bound configuration set from
that private relative path. The main world diagnostic config and credentials
are generated separately for that disposable run. No live installation or player
configuration is opened.

## Require completed source, link and ELF proof

A report alone cannot assert successful module compilation. The stager rereads
the real source inventory, artifacts and CMake file-API/link evidence:

- The exact source tree must match the final reviewed profile, preserving all
  52 supplied module directories and every original license file.
- The report must record a completed Android ARM64 world build, both real auth
  prerequisite and world artifacts, exact artifact sizes/hashes, and unchanged
  source identity before and after the build. Configure-only, auth-only, host or
  incomplete reports are refused.
- The actual configuration must retain Release, C++20, `APPS_BUILD=all`, static
  modules/scripts, `LUA_VERSION=lua52`, `LUA_STATIC=ON`, `CONF_DIR=configs` and
  `c++_static`. Each required module must have compiled sources in the actual
  module archive.
- CMake must identify `worldserver` as an executable and `modules`, `scripts`
  and `lualib` as real static-library artifacts. Their recorded hashes must match
  the actual files; the world link must include all three static targets.
  World, module and script C++ compilation must use C++20. The resolved Android
  ncurses link input is recorded as
  `compiled_world_evidence.target_dependency_artifacts.ncurses`; its actual
  regular file, byte length and SHA256 must match the preserved base
  `libncursesw.so.6`. A library name or reported link flag alone is insufficient.
- Android executable linking must use the exact configuration flag
  `-Wl,--strip-debug`; actual CMake link evidence must confirm the
  `linker-strip-debug` policy and refuse full-strip flags. The final linked ELF
  is copied unchanged. Packaged runtime files are limited to 512 MiB each and
  2 GiB total, with a 1 MiB manifest. Private static build archives have a
  separate 4 GiB verification bound and are never copied into the runtime.
- Every native payload must be ELF64 AArch64 with at least 16 KiB `PT_LOAD`
  alignment. Executables must be Android PIEs using `/system/bin/linker64`;
  shared libraries must have no interpreter. New world ELF/library inputs must
  have no `RPATH` or `RUNPATH`. The preserved provider's historical search-path
  metadata remains unchanged.
- Recursive loader dependencies must resolve to the pinned base, verified new
  target libraries, or the explicit Android platform allowlist. Conflicting
  target candidates, escaped library links, host ELFs and provider replacement
  bytes are refused. The world ELF must retain static Boost, Lua and C++ linkage.

New Readline provenance uses `formatVersion: 1` with a `libraries` entry for
`libreadline.so.8`: the built SHA256, `GPL-3.0-or-later`, exact source project,
version, URL and archive SHA256, `builtVersion: 8.3.3`, and all three numbered
patch URL/hash records. It must match the public source pins; arbitrary source
URLs or a self-consistent substitute library are not accepted.

The output manifest exposes bounded `worldBuild` metadata: final profile/source
identity, exact world ELF identity, 52-module/C++20/static linkage declarations,
relative configuration directory and compiled-updater lock hash. Full private
build paths and link evidence stay in `WORLD_BUILD_PROVENANCE.json`. That proof
records preserved base identities, added libraries/notices and module defaults,
while keeping `androidRuntimeExecuted`, `worldReady` and
`publicDistributionQualified` false.

## Notices and corresponding source

For this private combined worldserver/Readline diagnostic, the original core's
GPL version 2-or-later option is exercised under GPL version 3. Original
component notices remain intact. `licenses/world-runtime/SOURCE_EVIDENCE.json`
pins the source archive, dependencies, Readline patches and notice files;
`FTA_WORLD_RUNTIME_NOTICES.md` explains the private distribution scope.

The stage adds 55 legal files: all 43 original core/module license files plus
`AUTHORS`, the full Boost/Lua/Connector/C/Readline notices, both NDK r28c notices,
the world runtime notice and its source-evidence JSON. Existing provider notices,
including the separate provider NDK r29 notice, remain unchanged.

The separate private corresponding-source companion includes the exact original
source ZIP, final public port patches/profiles/build recipes, all pinned new
dependency sources and Readline patches, unchanged-provider source archives and
historical recipe evidence, full notices, a reproduction README and SHA256
inventory. It is prepared through an explicit allowlist, bound to the final
world profile/build and public code commit. It excludes the private database
seed, generated configs, player data, runtime binaries, APKs, keys and logs.
Original source templates and updater SQL remain inside the unchanged original
source archive.

Local input verification is recorded as `correspondingSourceInputsVerified`.
Staging does not deliver the companion and therefore leaves
`correspondingSourceDeliveryQualified` false. The final private APK and companion
must be delivered together with their exact identities. Source-to-provider
binary equivalence and public redistribution remain unqualified.

## Connect staging to the compiled updater milestone

The stager also verifies the exact canonical
`manifests/world-updater-lock.json` and packages it as
`assets/runtime/data/metadata/world-updater-lock.json`. The lock must bind this
same source profile/tree/archive, all 52 module names, the original 236 runtime
SQL files and the measured absence of module SQL. It contains metadata, not seed
rows or credentials. A profile change requires regenerating that source binding.

The next execution gate follows [world-updater-gate.md](world-updater-gate.md):
import the unchanged full seed into a fresh disposable private database, stage
the complete original SQL tree, run normal world startup without `--dry-run`,
and require the original three-database updater/preparation evidence followed
by the specific original missing-starting-map exit. Exact receipt/schema
postconditions, complete output, process-group cleanup and MariaDB shutdown must
also pass. A matching receipt count or a nonzero missing-data exit alone cannot
qualify this gate. The host expected-result model remains separate from actual
compiled updater execution.

The intentional empty game-data directory provides the bounded stopping point
before world listener startup. The original map/DBC checks remain intact.
Successful staging, a passing full-seed import or even that bounded updater run
does not establish complete world initialization, gameplay or device lifecycle
qualification. See [native-build.md](native-build.md) for build reproduction and
[auth-runtime.md](auth-runtime.md) for the separately qualified historical auth
payload.
