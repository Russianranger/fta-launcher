# Client memory and old-checkpoint recovery qualification

[Measured results](evidence/host-2026-10-25-client-memory.json) cover the production
store/governor with synthetic bytes. The fixture has 10,476 files, 1,175 directories
and 5,368,719,624 bytes, exceeding the physical code-36 interruption point.
The unchanged code-36 store halts with exit 79 at 4,865,392,640 verified bytes;
code 37 resumes that journal, completes publication and fully rehashes the result.
Both JVMs use `-Xmx64m`. The governor uses a virtual clock; this is not a physical
throughput, Android PSI, SAF or low-memory-survival test. PE validation is covered
separately; this inert-byte harness supplies a no-op candidate validator.

Reproduce using JDK 17 and Kotlin 2.0.21. Keep all generated JARs and synthetic
files outside Git. Place the fixture under an isolated temporary directory, not a
folder that another process synchronizes while the store checks exact membership.

1. Compile `ClientPlan.kt`, `ClientInventory.kt`, `PeMetadata.kt`, `ClientStore.kt`
   and `ClientIoGuard.kt` together into the current core JAR. Use the Kotlin stdlib
   on the classpath, JVM target 17. Compile
   `android/qualification/ClientMemoryHost.kt` against that JAR.
2. Extract `ClientStore.kt` from commit
   `577f6c7d4dc3abf776f25721618ab9bdade1e443` without editing its contents. Compile
   it with the unchanged `ClientPlan.kt` into a separate legacy JAR. For the legacy
   harness, use `ClientMemoryHost.kt` through its `if (mode == "legacy")` block,
   omitting the following virtual-clock block and closing `main` and the object.
   This avoids linking the new constructor into the old class. Use the current
   core JAR only for the other unchanged classes.
3. Run `java -Xmx64m -cp <legacy-harness-and-store>:<core>:<stdlib>
   com.russianranger.fta.client.ClientMemoryHost legacy <isolated-store>`. Require
   exit 79 and the recorded completed-prefix byte count.
4. Run `java -Xmx64m -cp <current-harness>:<core>:<stdlib>
   com.russianranger.fta.client.ClientMemoryHost resume <same-isolated-store>`.
   Require exit zero and all built-in assertions, including the exact old prefix,
   matching import/rehash catalog, bounded cache windows, accounted I/O and one
   pressure-pause recovery. Never reuse this harness with owner assets.

The normal Android unit suite adds 20 regressions for rate accounting, no idle
burst credit, pressure hysteresis, persistent-pressure stop, unavailable sampling,
responsive cancellation, PSI parsing, old-runtime exit rejection, 1 MiB sync/cache
limits, governed retained rehash, and checkpoint retention on pressure/sync failure.
All prior corruption, membership, source-change and publication tests still apply.
