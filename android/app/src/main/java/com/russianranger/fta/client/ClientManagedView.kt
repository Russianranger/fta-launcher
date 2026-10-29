package com.russianranger.fta.client

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Full verification and the complete owned session share one client-store lease.
 * The action must reap every guest reader before returning, even on failure. */
object ClientManagedView {
    const val CATALOG = "fe49bee4d96be084cfda4740112f1a73a10b87ab222e51ccb8730be38c300f13"
    val originalPins = mapOf(
        "Wow.exe" to (7702016L to "c4ea8503112878fa47d06580de9d936b184f9bb6b452f6931dfe793fe2f0a47e"),
        "d3d9.dll" to (202240L to "4c3522dd17e2ef09cbe13ac82c1e4360c7beb98cbe7af7675d751d1f5bc330e8"),
        "WarcraftXL.dll" to (2259968L to "9c935e27679a8161dbbdc3390bbb908d0ad444a5fe114ed0624c3db52656eedd"),
        "Utils/WarcraftXLHost.exe" to (1071104L to "6349752018efba461a2ac4bf19a98d1293aa7c21f98cb90f50615f33a7701955"))
    data class Result(val client: ClientStore.Result, val view: ClientWorkingView.Result, val sourceMetadataUnchanged: Boolean)

    fun run(store: ClientStore, destination: File, expectedCatalog: String = CATALOG,
        active: () -> Unit = {}, beforeIo: (Int) -> Unit = {}, progress: (ClientStore.Progress) -> Unit = {},
        validate: (File) -> Unit = ClientCandidate::validate,
        pins: Map<String, Pair<Long, String>> = originalPins,
        stamp: (File) -> List<Long>, available: () -> Long = { destination.parentFile!!.usableSpace },
        preparedPrefixBytes: Long = 0, phase: (String) -> Unit = {}, evidence: (String, Any) -> Unit = { _, _ -> },
        action: (File, File) -> Unit): Result {
        runtimeRequire(preparedPrefixBytes in 0..3L * ClientRuntimeStore.RESERVE, "view-growth-limit")
        var planned: ClientWorkingView.Plan? = null
        return store.withVerifiedClient(expectedCatalog, active, progress, validate, beforeVerify = { source ->
            phase("client-preflight")
            runtimeRequire(!Files.exists(destination.toPath(), NOFOLLOW_LINKS) && destination.absoluteFile == destination.canonicalFile &&
                !destination.toPath().startsWith(source.canonicalFile.toPath()) && !source.canonicalFile.toPath().startsWith(destination.toPath()), "unsafe-runtime-store")
            val plan = ClientWorkingView.plan(source.canonicalFile, active) { cost ->
                evidence("plannedCopiedBytes", cost.copiedBytes); evidence("plannedSharedBytes", cost.sharedBytes)
                evidence("plannedExcludedBytes", cost.excludedBytes); evidence("viewCopyLimitBytes", ClientWorkingView.COPY_LIMIT)
                for (group in ClientWorkingView.budgetGroups) {
                    evidence(ClientWorkingView.budgetField(group, "Bytes"), cost.inputBytes.getValue(group))
                    evidence(ClientWorkingView.budgetField(group, "Files"), cost.inputFiles.getValue(group))
                }
            }
            runtimeRequire(available() >= plan.copiedBytes + 4L * ClientRuntimeStore.RESERVE - preparedPrefixBytes, "insufficient-space")
            for ((path, pin) in pins) runtimeRequire(plan.entries.any {
                it.path == path && !it.directory && !it.copied && it.bytes == pin.first
            }, "client-build-mismatch")
            planned = plan
            phase("client-verify")
        }) { rawSource, client ->
            val source = rawSource.canonicalFile
            phase("client-identity")
            runtimeRequire(ClientWorkingView.plan(source, active) == planned, "runtime-changed")
            for ((path, pin) in pins) {
                val file = File(source, path); val hash = runtimeHash(); val buffer = ByteArray(256 * 1024)
                file.inputStream().use { input -> while (true) {
                    active(); beforeIo(buffer.size); val n = input.read(buffer); if (n < 0) break
                    runtimeRequire(n > 0, "runtime-changed"); hash.update(buffer, 0, n)
                } }
                runtimeRequire(runtimeHex(hash.digest()) == pin.second, "client-build-mismatch")
            }
            fun snapshot(): Map<String, List<Long>> {
                runtimeRequire(ClientWorkingView.plan(source, active) == planned, "runtime-changed")
                return (listOf("") + planned!!.entries.map { it.path }).associateWith { path -> active(); stamp(File(source, path)) }
            }
            val before = snapshot()
            evidence("clientCatalogSha256", client.catalogSha256); evidence("clientFiles", client.files)
            evidence("clientBytes", client.bytes); evidence("clientFullRehashValidated", true)
            phase("view-build")
            val built = ClientWorkingView.build(source, destination, active, beforeIo, available)
            evidence("viewCopiedBytes", built.copiedBytes); evidence("viewSharedBytes", built.sharedBytes)
            evidence("viewCopiedFiles", built.copiedFiles); evidence("viewSharedFiles", built.sharedFiles)
            evidence("viewExcludedBytes", built.excludedBytes); evidence("viewExcludedFiles", built.excludedFiles)
            evidence("freshOutputDirectoriesPrepared", true)
            evidence("viewPlanSha256", built.planSha256)
            // Existing spelling is retained; the builder creates WTF if absent.
            val settingsDir = destination.listFiles()!!.single { it.name.equals("WTF", true) }
            val settings = settingsDir.listFiles()!!.singleOrNull { it.name.equals("Config.wtf", true) }
                ?: File(settingsDir, "Config.wtf")
            runtimeRequire(!Files.exists(settings.toPath(), NOFOLLOW_LINKS) ||
                (Files.isRegularFile(settings.toPath(), NOFOLLOW_LINKS) && settings.length() <= ClientLaunchSettings.LIMIT), "client-config-refused")
            val bytes = ClientLaunchSettings.update(if (settings.exists()) settings.readBytes() else byteArrayOf())
            FileOutputStream(settings).use { it.write(bytes); it.fd.sync() }
            runtimeRequire(settings.readBytes().contentEquals(bytes), "runtime-changed")
            evidence("independentLaunchSettingsPrepared", true)
            runtimeRequire(snapshot() == before, "runtime-changed")
            try {
                active(); phase("client-session"); action(source, destination)
            } catch (e: Exception) {
                throw if (e is ClientFailureEvidence.AtPhase) e else ClientFailureEvidence.AtPhase("client-session", e)
            } finally {
                // Cleanup belongs to action; this check runs only after it returns.
                // Do not let an already-requested Stop skip the metadata audit.
                phase("client-postconditions")
                val after = (listOf("") + planned!!.entries.map { it.path }).associateWith { stamp(File(source, it)) }
                val unchanged = before == after && ClientWorkingView.plan(source) == planned
                evidence("sourceMetadataUnchanged", unchanged)
                runtimeRequire(unchanged, "runtime-changed")
            }
            Result(client, built, true)
        }
    }
}
