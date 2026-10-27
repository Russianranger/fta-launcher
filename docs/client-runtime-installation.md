# Bounded client runtime installation

`RuntimeArchivePlan` and `ClientRuntimeStore` prepare only the pinned public
Wine/FEX runtime. They do not open the managed client, realm, server assets or
configuration. `ClientRuntimeService` owns explicit preparation, verification and
synthetic native tests in a separate `:clientruntime` process. Original client
execution is not enabled.

`android/qualification/build_runtime_catalog.py` checks the original archive
identity and existing strict audit before deriving a catalog of all paths,
types, modes, file ranges/hashes and link targets. Catalog SHA256
`510d1ff3f0e88c73496b5e87215d18174c6eebd2b7d548caeb44474225f15cd0`, 1,722,721 bytes.
CI generates this metadata from the unchanged public archive; neither archive
nor runtime binaries enter Git. Gradle accepts the generated catalog through
`-PftaClientRuntimeCatalog`, and bundles the public identity lock.

The installer authenticates the compressed source before extraction and again
during extraction. It also checks the complete expanded-stream hash. It never
interprets archive headers or paths while writing: only authenticated catalog
ranges are copied. Metadata validation rejects duplicates, missing/non-directory
parents, overlapping ranges, special permission bits, escaping links and hard
links to nonregular targets. Guest-absolute symlinks are retained literally,
created after payloads, and never traversed for installation or verification.

An OS file lease spans installation or the full rehash plus the eventual consumer
callback. A private staging directory becomes `ready` by atomic rename only after
every file, mode, link and inventory entry passes full rehash. An interrupted
staging tree is removed without following links on the next explicit attempt;
only a rehashed compressed source is reused. A corrupt published tree is rejected.
Existing client/realm directories are never used as installation destinations.

Payload buffers are 256 KiB. Each output window is at most 1 MiB and is synced,
independently read back, then offered to the existing advisory cache helper.
Every source read, write and readback has an I/O governor hook. Free-space checks
retain a 1 GiB reserve and account for both compressed and expanded runtime.
The Android worker supplies the existing memory-pressure/24 MiB/s governor,
foreground notification and visible-activity binding, explicit Stop, a 90-minute
deadline and attempt-matched exit evidence. It never restarts work automatically.
The fixed public download checks every HTTPS redirect, rejects unknown hosts,
credentials and unexpected ports, bounds socket timeouts and checks declared size.
Six regressions cover redirects, errors, length, cancellation and connection closure.
Exported diagnostics allow only fixed states/reasons, measurements and hashes;
URLs, private paths, command lines and exception text are not exported.

Seventeen regression tests cover publication/reopen, source/cached/published
corruption, Stop/retry, mode/link/inventory drift, traversal, low storage,
bounded I/O and lease exclusion. The full public candidate additionally passed
host installation and both publication/retained rehashes with `-Xmx96m`:
14,092 entries, 2,637,799,241 file bytes. CI repeats that complete test after
Android compilation. All three foundation jobs in
[CI 40420915737](https://github.com/Russianranger/fta-launcher/actions/runs/40420915737)
passed at installer commit `00f040412915b989b1a716b59a45cabd8d68e8b0`:
200 tooling, 66 qualification, 503 Android tests passed / one existing skip,
lint/assembly, cache/JNI and packaged-input checks. The full runtime fixture
measured maximum I/O request 262,144 bytes, maximum source-cache advisory range
1,048,611 bytes, and 100,663,296-byte maximum heap. The same commit's synthetic
ARM64 Wine/ownership workflow 40420915731 passed. This is host evidence, not Android installation,
cache eviction, PRoot execution, original WarcraftXL or gameplay qualification.

`android_runtime_probe.py` is the next guest diagnostic program: bounded output,
per-stage deadlines, real CPU topology, disposable prefix, x86/x64 entry,
2/16-channel IPC, owner loss and synthetic Wine shutdown. Five host regressions
cover markers, output limits, timeouts and redaction; ARM64 CI runs the complete
program alongside the previous original-runtime fixture. Android native execution
must still be integrated and qualified separately.

Preparation worker commit `1c3321508934b8d95b4a8d4e6c77e6119c7c61e3` passed all
foundation jobs (40421996504). Its first new guest-program CI reached display and
failed prefix setup: the runner owns the extracted empty `/prefix`, while chroot
runs as root. The original pinned Wine source rejects a prefix owned by another
UID. CI now checks that this disposable directory is empty and gives it to the
actual chroot owner. No original Wine ownership rule or runtime archive changes.

`ClientProbeProtocol` adds three gates before Wine: normal tree exit, owned Stop,
then SIGKILL of the tracer with three observed tracees (two detached session
leaders). Identities include start ticks and initial parent/session relationships;
reparenting cannot count as death. The initial tracee signals only its own captured
tracer parent after a private handle-bound control request. All fixture processes
also have a 20-second alarm. Every handle is stopped in `finally`, and all three
tracees must end within three seconds. Five JVM regressions cover ordering,
deadline, cleanup, unrelated trees, survival and incomplete or contradictory
success markers. The same guest program has a native ARM64 PRoot CI gate.
The corrected guest program and its PRoot ownership gate passed ARM64 Linux CI
40422957424 at `fcab94bfcead3ef62daa42c65a5467b979939e4c`. The same checkpoint's
foundation CI 40422957436 passed 514 Android tests / one existing skip, lint,
assembly, packaged checks and full-candidate installation under the 96 MiB heap.

The Android driver now binds only the verified runtime and a new synthetic
attempt directory. It hashes the two packaged bridge binaries and all probe
assets, holds the runtime OS lease, and uses `NativeProcessBridge` handles for
every tracer. It observes PID/start-tick/parent/session relationships before Stop
or the exact tracer-death request. Native pressure checks abort and stop the
tree; they do not pause a still-running guest. Both import and realm services
are excluded while preparation/probing runs. A successful probe must also pass
another full runtime rehash before any success flag is exported.

Python starts with `-B`/PYTHONDONTWRITEBYTECODE; its prefix, temporary files and
font cache live in separate attempt binds. PRoot is not claimed to be a security
sandbox or to provide kernel read-only binds. No original managed client or realm
path is mounted. Attempts remain private for diagnosis and are capped at three,
with a 2 GiB free-space gate before starting; future recovery must observe owned
process termination before removing an interrupted attempt.

Foundation builds PRoot with one job, then both synthetic Windows fixtures
sequentially. `tools/stage_client_probe.py` verifies their metadata and exact
bridge hashes, preserves GPL/LGPL/MIT notices, and supplies the corresponding
source snapshot, original PRoot/talloc archives, patches and build recipe. Its
source companion accompanies the public CI APK and is mandatory for private
packaging. The Wine/FEX runtime remains an unchanged direct upstream download;
its package notices/version list and pinned upstream source links are preserved.
The private donor's complete server source companion remains separately required.

`check_android_runtime_path.py` separately exercises the same private prefix,
temporary/cache binds and bytecode-disabled Python invocation through native
Linux PRoot. It freshly extracts the pinned runtime and checks every original
file, mode, link and inventory entry before and after the full synthetic program.
Two regressions reject unexpected files, changed payloads/permissions and replaced
links. This supplements the original chroot test; Android kernel behavior remains
the physical gate.

Qualify the exact Android assembly and package/sign code 38 before requesting the
first Thor runtime test. Preserve installed code 37 and all passed physical gates.
