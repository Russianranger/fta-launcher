package com.russianranger.fta.runtime

import java.io.File

data class RuntimeRecoveryProof(val qualified: Boolean, val phases: Map<String, Map<String, Any>>)

object RuntimeRecoveryEvidence {
    fun read(filesDir: File, attempt: String, flags: Map<String, Boolean>): RuntimeRecoveryProof {
        val store = RecoveryStore(File(filesDir, "runtime/recovery-attempts/$attempt"), attempt)
        val checkpoints = RecoveryPhase.entries.associateWith { runCatching { store.read(it) }.getOrNull() }
        val journals = RecoveryPhase.entries.associateWith { phase -> runCatching {
            val record = checkpoints[phase] ?: return@runCatching null
            val root = store.verifiedRoot(File(filesDir, "runtime/database-diagnostics"), record)
            val journal = NativeInvocationJournal.read(File(root, if (phase == RecoveryPhase.RESUME) "native-recovery-resume.properties" else "native-invocations.properties"), attempt)
                ?: return@runCatching null
            val logs = if (phase == RecoveryPhase.RESUME) File(root, "recovery-logs") else root
            journal.first.map { record ->
                fun bytes(suffix: String): Long {
                    val file = File(logs, "${record.log}.$suffix")
                    require(java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    return file.length()
                }
                record.copy(stdoutBytes = bytes("out"), stderrBytes = bytes("err"))
            } to journal.second
        }.getOrNull() }
        val phases = RecoveryPhase.entries.associate { phase ->
            val checkpoint = checkpoints[phase]; val journal = journals[phase]
            val records = journal?.first.orEmpty()
            phase.name.lowercase() to mapOf<String, Any>(
                "state" to (checkpoint?.state ?: "UNAVAILABLE"), "workerDestroyed" to (checkpoint?.destroyed == true),
                "nativeEvidenceAvailable" to (journal != null), "recordCount" to records.size,
                "droppedCount" to (journal?.second ?: 0),
                "nativeInvocations" to records.map { value -> mapOf(
                    "id" to value.id, "role" to value.role, "stage" to value.stage, "outcome" to value.outcome,
                    "exitCode" to value.exitCode, "terminationExitCode" to value.terminationExitCode,
                    "durationMs" to value.durationMs, "timedOut" to value.timedOut, "cancelled" to value.cancelled,
                    "stdoutBytes" to value.stdoutBytes, "stderrBytes" to value.stderrBytes,
                    "validationFailure" to value.validationFailure, "databaseReadyObserved" to value.readyObserved,
                    "sqlShutdownRequested" to value.sqlShutdownRequested, "cleanShutdownObserved" to value.cleanShutdownObserved)
                })
        }
        return RuntimeRecoveryProof(qualified(flags, checkpoints, journals), phases)
    }

    internal fun qualified(flags: Map<String, Boolean>, checkpoints: Map<RecoveryPhase, RecoveryWorkerRecord?>,
                           journals: Map<RecoveryPhase, Pair<List<NativeInvocationEvidence>, Int>?>): Boolean {
        if (flags.keys != RuntimeRecoveryProtocol.fields.toSet() || flags.values.any { !it }) return false
        val cancel = checkpoints[RecoveryPhase.CANCEL] ?: return false
        val crash = checkpoints[RecoveryPhase.CRASH] ?: return false
        val resume = checkpoints[RecoveryPhase.RESUME] ?: return false
        if (cancel.state != "STOPPED" || !cancel.destroyed || !RuntimeRecoveryProtocol.ready(cancel.copy(state = "READY", destroyed = false)) ||
            !RuntimeRecoveryProtocol.ready(crash) || resume.state != "RECOVERED" || !resume.destroyed ||
            cancel.rootName == crash.rootName || resume.rootName != crash.rootName || resume.owner == null ||
            crash.owner!!.sameProcess(resume.owner)) return false
        for (phase in RecoveryPhase.entries) {
            val (records, dropped) = journals[phase] ?: return false
            if (records.isEmpty() || dropped != 0 || records.any { it.timedOut || it.validationFailure != null ||
                    it.stdoutBytes >= NativeInvocationJournal.MAX_LOG_BYTES || it.stderrBytes >= NativeInvocationJournal.MAX_LOG_BYTES }) return false
            val databases = records.filter { it.role == "database-server" && it.readyObserved }
            if (databases.size != 1) return false
            val database = databases.single()
            when (phase) {
                RecoveryPhase.CANCEL -> {
                    if (database.outcome != "cancelled" || database.terminationExitCode !in setOf(0, 137, 143)) return false
                    val interrupted = records.filter { it.outcome == "cancelled" }
                    if (interrupted.size != 2 || interrupted.count { it.role == "database-client" && it.stage == "RECOVERY_HOLD" &&
                            it.terminationExitCode in setOf(137, 143) } != 1) return false
                    if (records.any { it !in interrupted && (it.outcome != "exited" || it.exitCode != 0) }) return false
                }
                RecoveryPhase.CRASH -> {
                    // A dead writer cannot finalize its journal. The independent observer
                    // must attest termination of exactly these two previously live children.
                    val interrupted = records.filter { it.outcome == "running" }
                    if (database !in interrupted || interrupted.size != 2 || interrupted.count {
                            it.role == "database-client" && it.stage == "RECOVERY_HOLD" } != 1) return false
                    if (records.any { it !in interrupted && (it.outcome != "exited" || it.exitCode != 0) }) return false
                }
                RecoveryPhase.RESUME -> if (!database.sqlShutdownRequested || !database.cleanShutdownObserved ||
                    records.any { it.outcome != "exited" || it.exitCode != 0 || it.cancelled }) return false
            }
        }
        return true
    }
}
