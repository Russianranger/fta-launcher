package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files

class RecoveryProtocolTest {
    private val attempt = "00000000-0000-0000-0000-000000000001"
    private val cancelRoot = "g-00000000-0000-0000-0000-000000000002"
    private val crashRoot = "g-00000000-0000-0000-0000-000000000003"
    @Test fun parsesCommWithSpacesAndParentheses() {
        val fields = MutableList(20) { "0" }.apply { this[0] = "S"; this[1] = "123"; this[19] = "987654" }
        val sample = ProcessObservation.parse("456 (a ) strange (name)) ${fields.joinToString(" ")}", 456)
        assertEquals(ProcessIdentity(456, 987654, 123), sample.identity)
        assertTrue(sample.alive)
        assertThrows(IllegalArgumentException::class.java) { ProcessObservation.parse("truncated", 456) }
    }
    @Test fun reparentingDoesNotMeanDeathButPidReuseDoes() {
        val original = ProcessIdentity(123, 900, 100)
        assertFalse(ProcessObservation.ended(original, ProcessSample(original.copy(parentPid = 1), 'S')))
        assertTrue(ProcessObservation.ended(original, ProcessSample(original.copy(startTicks = 901), 'S')))
        assertTrue(ProcessObservation.ended(original, ProcessSample(original, 'Z')))
        assertTrue(ProcessObservation.ended(original, null))
    }
    @Test fun actualHostProcIdentityIsReadableAndMissingPidIsAbsent() {
        val text = File("/proc/self/stat").readText()
        val pid = text.substringBefore(' ').toInt()
        assertEquals(ProcessObservation.parse(text, pid).identity, ProcessObservation.live(pid))
        assertEquals(ProcessObservation.live(pid), ProcessObservation.self(pid))
        assertThrows(IllegalArgumentException::class.java) { ProcessObservation.self(if (pid == 1) 2 else 1) }
        assertNull(ProcessObservation.read(Int.MAX_VALUE))
    }
    @Test fun checkpointRoundTripRejectsAnotherAttemptAndTraversal() {
        val dir = Files.createTempDirectory("fta-recovery-record").toFile()
        try {
            val store = RecoveryStore(dir, attempt)
            val record = ready(RecoveryPhase.CRASH)
            store.write(record); assertEquals(record, store.read(RecoveryPhase.CRASH))
            assertThrows(IllegalArgumentException::class.java) {
                RecoveryStore(dir, "00000000-0000-0000-0000-000000000009").read(RecoveryPhase.CRASH)
            }
            assertThrows(IllegalArgumentException::class.java) { store.write(record.copy(rootName = "../database")) }
            store.state(RecoveryPhase.CRASH, "STOPPED", true)
            assertTrue(store.read(RecoveryPhase.CRASH)!!.destroyed)
            assertEquals(record.children, store.read(RecoveryPhase.CRASH)!!.children)
        } finally { dir.deleteRecursively() }
    }
    @Test fun checkpointCannotReopenSymlinkedDatadirRoot() {
        val parent = Files.createTempDirectory("fta-recovery-roots").toFile()
        val outside = Files.createTempDirectory("fta-recovery-outside").toFile()
        try {
            Files.createSymbolicLink(File(parent, crashRoot).toPath(), outside.toPath())
            assertThrows(IllegalArgumentException::class.java) { RecoveryStore(parent, attempt).verifiedRoot(parent, ready(RecoveryPhase.CRASH)) }
        } finally { File(parent, crashRoot).delete(); parent.deleteRecursively(); outside.deleteRecursively() }
    }
    private fun ready(phase: RecoveryPhase): RecoveryWorkerRecord {
        val owner = ProcessIdentity(if (phase == RecoveryPhase.CANCEL) 100 else 200, 1000, 1)
        return RecoveryWorkerRecord(phase, "READY", if (phase == RecoveryPhase.CANCEL) cancelRoot else crashRoot,
            owner, listOf(ProcessIdentity(owner.pid + 1, 1100, owner.pid), ProcessIdentity(owner.pid + 2, 1101, owner.pid)))
    }
    private inner class Model : RuntimeRecoveryProtocol.Driver {
        val checkpoints = mutableMapOf<RecoveryPhase, RecoveryWorkerRecord>()
        val started = mutableListOf<RecoveryPhase>()
        val dead = mutableSetOf<ProcessIdentity>()
        var clock = 0L
        var keepOwner = false
        var keepChild = false
        var destroyStop = true
        var wrongRoot = false
        var reuseOwner = false
        var observationDenied = false
        var cancelled = false
        override fun start(phase: RecoveryPhase) {
            started += phase
            checkpoints[phase] = if (phase == RecoveryPhase.RESUME)
                RecoveryWorkerRecord(phase, "RECOVERED", if (wrongRoot) cancelRoot else crashRoot,
                    if (reuseOwner) ready(RecoveryPhase.CRASH).owner else ProcessIdentity(300, 2000, 1), destroyed = true)
            else ready(phase)
        }
        override fun checkpoint(phase: RecoveryPhase) = checkpoints[phase]
        override fun stopAction() {
            checkpoints[RecoveryPhase.CANCEL] = ready(RecoveryPhase.CANCEL).copy(state = "STOPPED", destroyed = destroyStop)
            dead += ready(RecoveryPhase.CANCEL).children
        }
        override fun killOwner() {
            val crash = ready(RecoveryPhase.CRASH)
            if (!keepOwner) dead += crash.owner!!
            dead += if (keepChild) crash.children.take(1) else crash.children
        }
        override fun ended(identity: ProcessIdentity): Boolean {
            if (observationDenied) throw AccessDeniedException("synthetic")
            return identity in dead
        }
        override fun checkActive() { check(!cancelled) }
        override fun pause() { clock += 1000 }
        override fun nowMs() = clock
    }
    private fun run(model: Model): Set<String> = mutableSetOf<String>().also { result -> RuntimeRecoveryProtocol.run(model) { result += it } }
    @Test fun completeProtocolRequiresAllThreePhases() {
        val model = Model()
        assertEquals(RuntimeRecoveryProtocol.fields.toSet(), run(model))
        assertEquals(RecoveryPhase.entries, model.started)
    }
    @Test fun survivingOwnerPreventsRestart() {
        val model = Model().apply { keepOwner = true }
        assertThrows(IllegalStateException::class.java) { run(model) }
        assertFalse(RecoveryPhase.RESUME in model.started)
    }
    @Test fun survivingChildPreventsRestart() {
        val model = Model().apply { keepChild = true }
        assertThrows(IllegalStateException::class.java) { run(model) }
        assertFalse(RecoveryPhase.RESUME in model.started)
    }
    @Test fun inaccessibleProcessCannotCountAsTerminated() {
        val model = Model().apply { observationDenied = true }
        assertThrows(AccessDeniedException::class.java) { run(model) }
        assertEquals(listOf(RecoveryPhase.CANCEL), model.started)
    }
    @Test fun stopMustFinishServiceDestructionBeforeNextStart() {
        val model = Model().apply { destroyStop = false }
        assertThrows(IllegalStateException::class.java) { run(model) }
        assertEquals(listOf(RecoveryPhase.CANCEL), model.started)
    }
    @Test fun restoredDatabaseAndNewOwnerAreMandatory() {
        assertThrows(IllegalStateException::class.java) { run(Model().apply { wrongRoot = true }) }
        assertThrows(IllegalStateException::class.java) { run(Model().apply { reuseOwner = true }) }
    }
    @Test fun cancellationStopsAdvancement() {
        assertThrows(IllegalStateException::class.java) { run(Model().apply { cancelled = true }) }
    }
    private fun journals(): Map<RecoveryPhase, Pair<List<NativeInvocationEvidence>, Int>> {
        fun db(outcome: String, exit: Int? = null, termination: Int? = null, clean: Boolean = false) =
            NativeInvocationEvidence(1, "server", "database-server", "STARTING", 1, null,
                outcome = outcome, exitCode = exit, terminationExitCode = termination, readyObserved = true,
                sqlShutdownRequested = clean, cleanShutdownObserved = clean)
        fun client(outcome: String, termination: Int? = null) = NativeInvocationEvidence(2, "tool-1", "database-client", "RECOVERY_HOLD", 1, 300000,
            outcome = outcome, terminationExitCode = termination)
        return mapOf(RecoveryPhase.CANCEL to (listOf(db("cancelled", termination = 0), client("cancelled", 143)) to 0),
            RecoveryPhase.CRASH to (listOf(db("running"), client("running")) to 0),
            RecoveryPhase.RESUME to (listOf(db("exited", 0, clean = true)) to 0))
    }
    @Test fun qualificationRequiresCompletePhaseJournals() {
        val model = Model(); run(model)
        val flags = RuntimeRecoveryProtocol.fields.associateWith { true }
        fun qualifies(journals: Map<RecoveryPhase, Pair<List<NativeInvocationEvidence>, Int>?>) =
            RuntimeRecoveryEvidence.qualified(flags, model.checkpoints, journals)
        assertTrue(qualifies(journals()))
        assertFalse(qualifies(journals() - RecoveryPhase.CRASH))
        for (phase in RecoveryPhase.entries) {
            assertFalse(qualifies(journals() + (phase to (journals().getValue(phase).first to 1))))
            val changed = journals().getValue(phase).first.map { it.copy(timedOut = true) }
            assertFalse(qualifies(journals() + (phase to (changed to 0))))
        }
        val falseClean = journals().getValue(RecoveryPhase.RESUME).first.map { it.copy(cleanShutdownObserved = false) }
        assertFalse(qualifies(journals() + (RecoveryPhase.RESUME to (falseClean to 0))))
        val falseCancel = journals().getValue(RecoveryPhase.CANCEL).first.map { it.copy(terminationExitCode = null) }
        assertFalse(qualifies(journals() + (RecoveryPhase.CANCEL to (falseCancel to 0))))
        assertFalse(RuntimeRecoveryEvidence.qualified(flags + ("nativeChildrenTerminated" to false), model.checkpoints, journals()))
    }
    @Test fun diskEvidenceKeepsCrashLogsAndRejectsMissingOrTruncatedOutput() {
        val files = Files.createTempDirectory("fta-recovery-export").toFile()
        try {
            val model = Model(); run(model)
            val store = RecoveryStore(File(files, "runtime/recovery-attempts/$attempt"), attempt)
            for (phase in RecoveryPhase.entries) {
                val checkpoint = model.checkpoints.getValue(phase)
                store.write(checkpoint)
                val root = File(files, "runtime/database-diagnostics/${checkpoint.rootName}").apply { mkdirs() }
                val logs = if (phase == RecoveryPhase.RESUME) File(root, "recovery-logs").apply { mkdir() } else root
                val journal = NativeInvocationJournal(File(root,
                    if (phase == RecoveryPhase.RESUME) "native-recovery-resume.properties" else "native-invocations.properties"), attempt)
                for (value in journals().getValue(phase).first) {
                    val id = journal.begin(value.log, value.role, value.stage, value.timeoutMs)
                    File(logs, "${value.log}.out").writeText(phase.name)
                    File(logs, "${value.log}.err").writeText("")
                    if (value.readyObserved) journal.checkpoint(id, "database_ready")
                    if (value.outcome != "running") journal.finish(id, value.outcome, value.exitCode, 0, 0)
                    value.terminationExitCode?.let { journal.terminated(id, it) }
                    if (value.sqlShutdownRequested) journal.checkpoint(id, "sql_shutdown_requested")
                    if (value.cleanShutdownObserved) journal.checkpoint(id, "clean_shutdown")
                }
            }
            val flags = RuntimeRecoveryProtocol.fields.associateWith { true }
            assertTrue(RuntimeRecoveryEvidence.read(files, attempt, flags).qualified)
            val root = File(files, "runtime/database-diagnostics/$crashRoot")
            val crashLog = File(root, "server.out")
            assertEquals("CRASH", crashLog.readText())
            assertEquals("RESUME", File(root, "recovery-logs/server.out").readText())
            java.io.RandomAccessFile(crashLog, "rw").use { it.setLength(NativeInvocationJournal.MAX_LOG_BYTES) }
            assertFalse(RuntimeRecoveryEvidence.read(files, attempt, flags).qualified)
            crashLog.writeText("CRASH")
            File(root, "recovery-logs/server.out").delete()
            assertFalse(RuntimeRecoveryEvidence.read(files, attempt, flags).qualified)
        } finally { files.deleteRecursively() }
    }
}
