package com.russianranger.fta.client

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Disposable PE32/PE64 checks only. No original-client path is accepted. */
object ClientGuardedWine {
    /** Resolve Android's app-directory alias before the strict view/root audit. */
    fun createAttempt(files: File, id: String): File = ClientProbeAttempts.create(files.canonicalFile, id)
    val bridges = ClientIsolationPins.payloads.filterKeys { it != "libfta_storage_probe.so" }
    val assets = setOf("probe32.exe", "probe64.exe", "android_probe.py", "guarded_wine.py", "NOTICE.txt")
    val stages = listOf("cpu-topology", "display", "prefix", "x86-storage", "x64-storage", "wine-stop")
    val checks = listOf("shared-read-open", "read-content", "seek", "read-mapping", "mapped-content", "private-mapping",
        "private-view", "direct-read", "private-write-isolated", "directory-list", "shared-write-denied",
        "direct-write-denied", "truncate-denied", "overwrite-denied", "protected-create-denied", "delete-preserved",
        "rename-denied", "attributes-denied", "hardlink-denied", "mkdir-denied", "config-open", "config-write",
        "log-create", "log-write", "log-rename", "log-delete", "mixed-child-start", "mixed-child-read-protection")
    val keys = listOf("x86", "x64").flatMap { arch -> checks.map { "$arch:$it" } }
    data class Observation(val stages: Map<String, Int>, val checks: Map<String, Int>,
        val errors: Map<String, Int>, val deletion: Map<String, Int>)
    fun observe(text: String): Observation {
        runtimeRequire(text.length <= 65536, "probe-failed")
        val observed = linkedMapOf<String, Int>(); val steps = linkedMapOf<String, Int>()
        val errors = linkedMapOf<String, Int>(); val deletion = linkedMapOf<String, Int>()
        for (line in text.lineSequence()) {
            val f = line.split(' ')
            if (f.size == 3 && f[0] == "FTA_ANDROID_STAGE" && f[1] in stages)
                f[2].toIntOrNull()?.takeIf { it in -255..255 }?.let { steps[f[1]] = it }
            if (f.size != 4 || f[1] !in setOf("x86", "x64")) continue
            val key = "${f[1]}:${f[2]}"; val number = f[3].toIntOrNull() ?: continue
            if (f[0] == "FTA_GUARDED_WIN" && key in keys && number in 0..1) observed[key] = number
            if (f[0] == "FTA_GUARDED_ERROR" && key in keys && number in 0..65535) errors[key] = number
            if (f[0] == "FTA_GUARDED_DELETE" && f[2] in setOf("0", "1") && number in 0..65535) {
                deletion["${f[1]}ReturnedSuccess"] = f[2].toInt(); deletion["${f[1]}Error"] = number
            }
        }
        return Observation(steps, observed, errors, deletion)
    }
    fun parse(text: String, exit: Int): Map<String, Int> {
        val observed = observe(text); val lines = text.lineSequence().toList()
        runtimeRequire(exit == 0 && lines.count { it == "FTA_GUARDED_WINE_PASS" } == 1 &&
            lines.none { it == "FTA_GUARDED_WINE_FAILED" || it.startsWith("FTA_GUARDED_ERROR ") } &&
            lines.filter { it.startsWith("FTA_ANDROID_STAGE ") } == stages.map { "FTA_ANDROID_STAGE $it 0" } &&
            lines.filter { it.startsWith("FTA_GUARDED_WIN ") } == keys.map { "FTA_GUARDED_WIN ${it.replace(':', ' ')} 0" } &&
            lines.count { it.startsWith("FTA_GUARDED_DELETE ") } == 2 && observed.deletion.size == 4,
            "probe-failed")
        return observed.stages
    }
    fun create(run: File, probe: File, active: () -> Unit, beforeIo: (Int) -> Unit): ClientWorkingView.Result {
        val source = File(run, "immutable")
        runtimeRequire(source.mkdir(), "storage-unavailable")
        for (name in listOf("Data", "WTF", "Logs")) runtimeRequire(File(source, name).mkdir(), "storage-unavailable")
        File(source, "Data/canary.bin").writeBytes(ByteArray(65536) { (it % 251).toByte() })
        File(source, "WTF/Config.wtf").writeText("fixture-original-settings\n")
        for (name in listOf("probe32.exe", "probe64.exe")) File(probe, name).copyTo(File(source, name))
        return ClientWorkingView.build(source, File(run, "view"), active, beforeIo)
    }
    /** Bounded fixture snapshot only, including the source root and every inode's metadata. */
    fun snapshot(root: File, stamp: (File) -> List<Long>): Map<String, Pair<List<Long>, String>> {
        val result = linkedMapOf<String, Pair<List<Long>, String>>()
        Files.walk(root.toPath()).use { paths -> paths.forEach { path ->
            runtimeRequire(result.size < 16 && !Files.isSymbolicLink(path), "probe-failed")
            val regular = Files.isRegularFile(path, NOFOLLOW_LINKS)
            runtimeRequire(regular || Files.isDirectory(path, NOFOLLOW_LINKS), "probe-failed")
            val file = path.toFile()
            runtimeRequire(!regular || file.length() in 1..2 * 1024 * 1024, "probe-failed")
            result[root.toPath().relativize(path).toString()] = stamp(file) to
                if (regular) runtimeHex(runtimeHash().digest(file.readBytes())) else "directory"
        } }
        return result
    }
}
