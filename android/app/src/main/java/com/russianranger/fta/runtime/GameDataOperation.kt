package com.russianranger.fta.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Properties

/** Small atomic progress record, readable while the separate asset-store lease is held. */
class GameDataOperation(private val file: File) {
    data class Memory(val available: Long, val total: Long, val threshold: Long, val low: Boolean,
        val importance: Int, val activityBound: Boolean) {
        init { require(total > 0 && available in 0..total && threshold in 0..total && importance in 0..1000) }
    }
    data class Record(val attempt: String, val owner: ProcessIdentity, val mode: String,
        val started: Long, val updated: Long, val state: String = "RUNNING", val stage: String = "open",
        val completed: Long = 0, val total: Long = 0, val copied: Long = 0, val archive: String? = null,
        val freeStorage: Long = 0, val heapUsed: Long = 0, val heapMax: Long = 0,
        val failure: String? = null, val frames: List<String> = emptyList(), val memory: Memory? = null, val cache: GameDataCache.Stats? = null)

    fun write(record: Record) {
        validate(record)
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val values = linkedMapOf("formatVersion" to "1", "attempt" to record.attempt,
            "pid" to record.owner.pid.toString(), "ticks" to record.owner.startTicks.toString(),
            "parent" to record.owner.parentPid.toString(), "mode" to record.mode,
            "started" to record.started.toString(), "updated" to record.updated.toString(),
            "state" to record.state, "stage" to record.stage, "completed" to record.completed.toString(),
            "total" to record.total.toString(), "copied" to record.copied.toString(),
            "freeStorage" to record.freeStorage.toString(), "heapUsed" to record.heapUsed.toString(), "heapMax" to record.heapMax.toString())
        record.archive?.let { values["archive"] = it }; record.failure?.let { values["failure"] = it }
        record.frames.forEachIndexed { i, frame -> values["frame.$i"] = frame }
        record.memory?.let { m ->
            values["memory.available"] = m.available.toString(); values["memory.total"] = m.total.toString()
            values["memory.threshold"] = m.threshold.toString(); values["memory.low"] = m.low.toString()
            values["memory.importance"] = m.importance.toString(); values["memory.activityBound"] = m.activityBound.toString()
        }
        record.cache?.let { c ->
            values["cache.requests"] = c.requests.toString(); values["cache.accepted"] = c.accepted.toString()
            values["cache.rejected"] = c.rejected.toString(); values["cache.bytes"] = c.bytes.toString()
            values["cache.sourceAvailable"] = c.sourceAvailable.toString()
        }
        val temporary = File(file.parentFile, ".${file.name}.new")
        try {
            FileOutputStream(temporary).use { out -> out.write(values.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }.toByteArray()); out.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    fun read(attempt: String): Record? = runCatching {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() !in 1..8192) return null
        val p = Properties().apply { file.inputStream().use { load(it) } }
        require(p.getProperty("formatVersion") == "1" && p.getProperty("attempt") == attempt)
        fun number(key: String) = p.getProperty(key).toLong()
        Record(attempt, ProcessIdentity(p.getProperty("pid").toInt(), number("ticks"), p.getProperty("parent").toInt()),
            p.getProperty("mode"), number("started"), number("updated"), p.getProperty("state"), p.getProperty("stage"),
            number("completed"), number("total"), number("copied"), p.getProperty("archive"),
            number("freeStorage"), number("heapUsed"), number("heapMax"), p.getProperty("failure"),
            (0 until 4).mapNotNull { p.getProperty("frame.$it") },
            if (p.stringPropertyNames().any { it.startsWith("memory.") }) Memory(number("memory.available"), number("memory.total"),
                number("memory.threshold"), p.getProperty("memory.low").toBooleanStrict(), p.getProperty("memory.importance").toInt(),
                p.getProperty("memory.activityBound").toBooleanStrict()) else null,
            if (p.stringPropertyNames().any { it.startsWith("cache.") }) GameDataCache.Stats(number("cache.requests"), number("cache.accepted"),
                number("cache.rejected"), number("cache.bytes"), p.getProperty("cache.sourceAvailable").toBooleanStrict()) else null).also(::validate)
    }.getOrNull()

    fun export(attempt: String, workerObserved: Boolean?): Map<String, Any> {
        val record = read(attempt) ?: return mapOf("available" to false)
        val state = if (record.state != "RUNNING") record.state else when (workerObserved) {
            true -> "RUNNING"; false -> "INTERRUPTED"; null -> "UNKNOWN"
        }
        return linkedMapOf<String, Any>("available" to true, "attemptId" to attempt, "mode" to record.mode,
            "state" to state, "lastRecordedState" to record.state, "stage" to record.stage,
            "startedAtEpochMs" to record.started, "updatedAtEpochMs" to record.updated,
            "completedUnits" to record.completed, "totalUnits" to record.total,
            "unit" to if (record.stage == "archive_index") "entries" else "bytes",
            "copiedBytes" to record.copied, "freeStorageBytes" to record.freeStorage,
            "heapUsedBytes" to record.heapUsed, "heapMaxBytes" to record.heapMax,
            "workerObservation" to when (workerObserved) { true -> "active"; false -> "inactive"; null -> "unknown" }).apply {
            record.archive?.let { put("archiveSha256", it) }
            record.failure?.let { put("failureKind", it) }
            if (record.frames.isNotEmpty()) put("applicationFrames", record.frames)
            put("fileCacheAdviceAvailable", record.cache != null)
            record.cache?.let { c -> put("fileCacheAdvice", mapOf("requests" to c.requests, "accepted" to c.accepted,
                "rejected" to c.rejected, "requestedBytes" to c.bytes, "sourceDescriptorAvailable" to c.sourceAvailable,
                "intervalBytes" to GameDataCache.INTERVAL, "actualEvictionMeasured" to false)) }
            put("systemMemoryObservationAvailable", record.memory != null)
            record.memory?.let { m -> put("systemMemoryObservation", mapOf("availableBytes" to m.available, "totalBytes" to m.total,
                "lowMemoryThresholdBytes" to m.threshold, "lowMemory" to m.low, "workerImportance" to m.importance,
                "activityBound" to m.activityBound)) }
        }
    }

    companion object {
        val STAGES = setOf("open", "copy", "copy_sync", "archive_index", "extract", "preflight", "publish", "verify")
        val FAILURES = setOf("out_of_memory", "stack_overflow", "linkage", "io", "security", "invalid_argument", "operation_failed", "cancelled", "timeout")
        fun token(attempt: String): String { require(NativeInvocationJournal.validAttemptId(attempt)); return "fta-game-data:$attempt" }
        fun failureKind(failure: Throwable, cancelled: Boolean) = if (cancelled) "cancelled" else when (failure) {
            is OutOfMemoryError -> "out_of_memory"; is StackOverflowError -> "stack_overflow"; is LinkageError -> "linkage"
            is java.util.concurrent.TimeoutException -> "timeout"
            is java.io.IOException -> "io"; is SecurityException -> "security"; is IllegalArgumentException -> "invalid_argument"
            else -> "operation_failed"
        }
        fun safeFrames(failure: Throwable): List<String> = failure.stackTrace.asSequence()
            .filter { it.className.startsWith("com.russianranger.fta.") }
            .map { "${it.className.removePrefix("com.russianranger.fta.")}.${it.methodName}:${it.lineNumber.coerceAtLeast(0)}" }
            .filter { framePattern.matches(it) }.take(4).toList()
        private val framePattern = Regex("[A-Za-z0-9_.$<>]{1,180}:[0-9]{1,8}")
        private fun validate(r: Record) {
            require(NativeInvocationJournal.validAttemptId(r.attempt) && r.mode in setOf("import", "verify"))
            require(r.started > 0 && r.updated > 0 && r.state in setOf("RUNNING", "FINISHED", "FAILED", "STOPPED"))
            require(r.stage in STAGES && r.completed in 0..GameDataPolicy.MAX_TOTAL_BYTES && r.total in 0..GameDataPolicy.MAX_TOTAL_BYTES)
            require(r.total == 0L || r.completed <= r.total)
            require(r.copied in 0..GameDataPolicy.MAX_TOTAL_BYTES && (r.archive == null || r.archive.matches(Regex("[a-f0-9]{64}"))))
            require(r.freeStorage >= 0 && r.heapUsed >= 0 && r.heapMax >= r.heapUsed)
            require(r.failure == null || r.failure in FAILURES)
            require(r.frames.size <= 4 && r.frames.all { framePattern.matches(it) })
        }
    }
}
