# Bounded client runtime installation

`RuntimeArchivePlan` and `ClientRuntimeStore` prepare only the pinned public
Wine/FEX runtime. They do not open the managed client, realm, server assets or
configuration. No Android service or execution action is enabled by this increment.

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
Android integration must supply the existing memory-pressure/24 MiB/s governor,
foreground ownership, cancellation, report sanitization and operation deadline.

Seventeen regression tests cover publication/reopen, source/cached/published
corruption, Stop/retry, mode/link/inventory drift, traversal, low storage,
bounded I/O and lease exclusion. The full public candidate additionally passed
host installation and both publication/retained rehashes with `-Xmx96m`:
14,092 entries, 2,637,799,241 file bytes. CI repeats that complete test after
Android compilation. This is host filesystem evidence, not Android installation,
cache eviction, PRoot execution, original WarcraftXL or gameplay qualification.

Next wire the owned foreground worker, exact runtime download, disposable prefix
and synthetic Wine/PRoot diagnostic. Finish source/notice closure before issuing
a new private APK. Preserve installed code 37 and all passed physical gates.
