package com.russianranger.fta.client

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ

/** Read-only completion of an already reaped synthetic attempt. Never starts a process. */
object ClientGuardedFinish {
    data class Pin(val bytes: Int, val sha256: String) {
        init { require(bytes in 1..2 * 1024 * 1024 && sha256.matches(Regex("[0-9a-f]{64}"))) }
    }
    // Exact code-43 fixture identity, independent of a later compiler's output.
    val assets = mapOf(
        "NOTICE.txt" to Pin(335, "9110b57a8ae8f8349b072c0c0f3e083e55080cc9889c1df9239e7f268a9c7bad"),
        "android_probe.py" to Pin(13724, "f79e11410cffc88508b5918680af1d3a22132516d1b1b4f171e8332a7a4a89f4"),
        "guarded_wine.py" to Pin(3969, "a20d0af3db2d349336e1686e133d5fdcbea17cbf3e9407c54ed7372911daa823"),
        "probe32.exe" to Pin(248241, "bd3842ad9988f591bd2fa3ba6166ae6522999d9bfd9e1449cdb548ea7bbb0d39"),
        "probe64.exe" to Pin(259770, "8f5b1fb6d9102c37ef3bdfa24fad4779e3d161f557aa0a2fb81e7d314a58fe50")
    )
    const val ARCHIVE = "f036c00a290abb953bec26be80c4d8fe492fd986e7a589c51008124432c8641e"
    const val CATALOG = "510d1ff3f0e88c73496b5e87215d18174c6eebd2b7d548caeb44474225f15cd0"
    private val originalConfig = "fixture-original-settings\n".toByteArray()
    private val changedConfig = "private-original-settings\n".toByteArray()
    val retainedFields = setOf("ownershipNormalExit", "ownershipStopExit", "ownershipKilledExit", "stoppedTracees", "killedTracees",
        "probeCleanupComplete", "runtimePreRehashValidated", "viewCopiedBytes", "viewSharedBytes", "viewCopiedFiles", "viewSharedFiles",
        "viewPlanSha256", "protectedFixtureUnchanged", "guardedPayloadSha256", "windowsChecks", "windowsErrors", "windowsDeletion",
        "probeStages", "displayEvent", "socketProbeErrno", "socketHostPathBytes", "prefixElapsedMs", "prefixSystem32Entries",
        "prefixSyswow64Entries", "prefixRegistryBytes", "prefixUpdateMarker", "probeDiagnostics")
    private fun integer(value: Any?): Long? = when (value) { is Int -> value.toLong(); is Long -> value; else -> null }
    private fun zeroMap(value: Any?, keys: Collection<String>): Boolean {
        val map = value as? Map<*, *> ?: return false
        return map.keys == keys.toSet() && map.values.all { integer(it) == 0L }
    }
    fun eligible(report: Map<String, Any?>): Boolean = runCatching {
        report["state"] == "failed" && report["reason"] == "io-failed" && report["operation"] == "probe" &&
            (report["attemptId"] as? String)?.matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) == true &&
            report["archiveSha256"] == ARCHIVE && report["catalogSha256"] == CATALOG &&
            report["runtimePreRehashValidated"] == true && report["protectedFixtureUnchanged"] == true &&
            integer(report["ownershipNormalExit"]) == 0L && integer(report["ownershipStopExit"])?.let { it in 0L..255L } == true &&
            integer(report["ownershipKilledExit"]) == 137L && integer(report["stoppedTracees"]) == 3L &&
            integer(report["killedTracees"]) == 3L && integer(report["probeCleanupComplete"]) == 1L &&
            integer(report["viewCopiedBytes"]) == 26L && integer(report["viewSharedBytes"]) == 573547L &&
            integer(report["viewCopiedFiles"]) == 1L && integer(report["viewSharedFiles"]) == 3L &&
            (report["viewPlanSha256"] as? String)?.matches(Regex("[0-9a-f]{64}")) == true &&
            report["guardedPayloadSha256"] == ClientGuardedWine.bridges && report["displayEvent"] == "ready" &&
            integer(report["socketProbeErrno"]) == 0L && zeroMap(report["windowsChecks"], ClientGuardedWine.keys) &&
            zeroMap(report["probeStages"], ClientGuardedWine.stages) && (report["windowsErrors"] == null || (report["windowsErrors"] as? Map<*, *>)?.isEmpty() == true) &&
            (report["windowsDeletion"] as? Map<*, *>)?.let { deletion ->
                deletion.keys == setOf("x86ReturnedSuccess", "x86Error", "x64ReturnedSuccess", "x64Error") &&
                    listOf("x86", "x64").all { arch ->
                        val success = integer(deletion[arch + "ReturnedSuccess"]); val error = integer(deletion[arch + "Error"])
                        (success == 1L && error == 0L) || (success == 0L && error in setOf(5L, 19L))
                    }
            } == true
    }.getOrDefault(false)
    data class Result(val transcriptSha256: String, val planSha256: String, val deletion: Map<String, Int>)
    fun validateFiles(files: File, attempt: String, expectedPlan: String, active: () -> Unit = {},
        beforeIo: (Int) -> Unit = {}, links: (File) -> Long,
        stamp: (File) -> List<Long> = { listOf(it.length(), it.lastModified()) },
        pins: Map<String, Pin> = assets): Result {
        val parent = File(files.canonicalFile, "cr")
        val run = ClientProbePaths.attempt(files.canonicalFile, attempt)
        fun demand(ok: Boolean) = runtimeRequire(ok, "retained-probe-changed")
        fun directory(file: File) { demand(file.absoluteFile == file.canonicalFile && Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) }
        directory(parent); directory(run)
        fun read(file: File, max: Int): ByteArray {
            active(); demand(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && links(file) == 1L && file.length() in 1..max)
            val data = java.io.ByteArrayOutputStream(); val buffer = ByteArray(minOf(max + 1, 65536))
            Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                while (true) {
                    active(); beforeIo(buffer.size); val n = input.read(buffer); if (n < 0) break
                    demand(n > 0 && data.size() + n <= max); data.write(buffer, 0, n)
                }
            }
            return data.toByteArray()
        }
        fun hash(bytes: ByteArray) = runtimeHex(runtimeHash().digest(bytes))
        demand(read(File(run, "owned-cleanup-v1"), 64).contentEquals("FTA_OWNED_CLEANUP_V1\n".toByteArray()))
        val probe = File(run, "probe"); val source = File(run, "immutable"); val view = File(run, "view")
        for (dir in listOf(probe, source, view)) directory(dir)
        val before = ClientGuardedWine.snapshot(source, stamp)
        demand(probe.list()?.toSet() == pins.keys)
        for ((name, pin) in pins) {
            val bytes = read(File(probe, name), pin.bytes)
            demand(bytes.size == pin.bytes && hash(bytes) == pin.sha256)
        }
        val originals = mapOf("Data/canary.bin" to ByteArray(65536) { (it % 251).toByte() }, "WTF/Config.wtf" to originalConfig)
        val sourceNames = setOf("Data", "WTF", "Logs", "Data/canary.bin", "WTF/Config.wtf", "probe32.exe", "probe64.exe")
        val plan = ClientWorkingView.plan(source, active)
        demand(plan.entries.map { it.path }.toSet() == sourceNames && plan.sha256 == expectedPlan && plan.copiedBytes == 26L)
        for (name in listOf("Data", "WTF", "Logs")) directory(File(source, name))
        for ((name, bytes) in originals) demand(read(File(source, name), bytes.size).contentEquals(bytes))
        for (name in listOf("probe32.exe", "probe64.exe")) {
            val pin = pins.getValue(name); val bytes = read(File(source, name), pin.bytes)
            demand(bytes.size == pin.bytes && hash(bytes) == pin.sha256)
        }
        val viewDirectories = setOf("Data", "WTF", "Logs", "Cache", "Errors", "Screenshots")
        val shared = setOf("Data/canary.bin", "probe32.exe", "probe64.exe")
        val expectedView = viewDirectories + shared + "WTF/Config.wtf"
        val actualView = mutableSetOf<String>()
        Files.walk(view.toPath()).use { paths -> paths.forEach { path ->
            active(); if (path != view.toPath()) {
                demand(actualView.size < expectedView.size)
                val name = view.toPath().relativize(path).toString(); demand(name in expectedView && actualView.add(name))
                when (name) {
                    in viewDirectories -> directory(path.toFile())
                    in shared -> demand(Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString() == "/immutable/$name")
                    else -> demand(read(path.toFile(), changedConfig.size).contentEquals(changedConfig) &&
                        !Files.isSameFile(path, File(source, "WTF/Config.wtf").toPath()))
                }
            }
        } }
        demand(actualView == expectedView)
        val transcript = read(File(run, "step-3.out"), 65536)
        ClientGuardedWine.parse(transcript.toString(Charsets.UTF_8), 0)
        val deletion = ClientGuardedWine.observe(transcript.toString(Charsets.UTF_8)).deletion
        demand(listOf("x86", "x64").all { arch ->
            val success = deletion[arch + "ReturnedSuccess"]; val error = deletion[arch + "Error"]
            (success == 1 && error == 0) || (success == 0 && error in setOf(5, 19))
        })
        demand(ClientWorkingView.plan(source, active) == plan && ClientGuardedWine.snapshot(source, stamp) == before)
        return Result(hash(transcript), plan.sha256, deletion)
    }
}
