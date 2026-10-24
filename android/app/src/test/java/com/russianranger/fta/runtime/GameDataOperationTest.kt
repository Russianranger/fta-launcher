package com.russianranger.fta.runtime

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GameDataOperationTest {
    private val root = Files.createTempDirectory("fta-operation-test").toFile()
    private val file = File(root, "operation.properties")
    private val store = GameDataOperation(file)
    private val attempt = "12345678-1234-4234-8234-123456789abc"
    private val owner = ProcessIdentity(123, 456, 1)
    private fun record() = GameDataOperation.Record(attempt, owner, "import", 1000, 2000,
        stage = "copy", completed = 1758461952, copied = 1758461952, heapUsed = 8_000_000, heapMax = 64_000_000)
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun runningInterruptedAndUnknownObservationsRemainDistinct() {
        store.write(record())
        assertEquals("RUNNING", store.export(attempt, true)["state"])
        assertEquals("INTERRUPTED", store.export(attempt, false)["state"])
        assertEquals("UNKNOWN", store.export(attempt, null)["state"])
        assertEquals("RUNNING", store.read(attempt)!!.state)
        assertFalse(store.export(attempt, false).containsKey("preflightPassed"))
    }
    @Test fun checkpointRetainsCopiedBytesAndArchiveHashDuringIndex() {
        val hash = "a".repeat(64)
        store.write(record().copy(stage = "archive_index", completed = 128, total = 40000, archive = hash))
        val report = GameDataOperation(file).export(attempt, false)
        assertEquals(1758461952L, report["copiedBytes"])
        assertEquals(128L, report["completedUnits"])
        assertEquals("entries", report["unit"])
        assertEquals(hash, report["archiveSha256"])
    }
    @Test fun finalizedOutcomeIsNotReplacedByLaterProcessDeath() {
        for (state in listOf("FINISHED", "FAILED", "STOPPED")) {
            store.write(record().copy(state = state))
            assertEquals(state, store.export(attempt, false)["state"])
        }
    }
    @Test fun anotherAttemptCannotReusePriorProgressOrFailure() {
        store.write(record())
        assertEquals(mapOf("available" to false), store.export("87654321-1234-4234-8234-123456789abc", false))
    }
    @Test fun unfinishedTemporaryWriteDoesNotReplaceCommittedCheckpoint() {
        store.write(record())
        File(root, ".operation.properties.new").writeText("partial")
        assertEquals(record(), GameDataOperation(file).read(attempt))
    }
    @Test fun invalidOrOversizedRecordsCannotPartiallyExportSuccess() {
        store.write(record().copy(state = "FINISHED")); val good = file.readText()
        for (bad in listOf(good.replace("stage=copy", "stage=private-path"), good.replace("heapMax=64000000", "heapMax=1"),
            good.replace("completed=1758461952", "completed=-1"), good + "archive=not-a-hash\n", "x".repeat(8193))) {
            file.writeText(bad)
            assertEquals(mapOf("available" to false), store.export(attempt, false))
        }
    }
    @Test fun writerRejectsInvalidPhasesCountsAndFramesBeforeReplacingPriorRecord() {
        store.write(record())
        for (bad in listOf(record().copy(stage = "secret"), record().copy(total = 1),
            record().copy(copied = Long.MAX_VALUE), record().copy(failure = "private exception"),
            record().copy(frames = listOf("/private/path")))) {
            assertThrows(IllegalArgumentException::class.java) { store.write(bad) }
            assertEquals(record(), store.read(attempt))
        }
    }
    @Test fun failureCategoriesDoNotExportMessagesOrExternalFrames() {
        val failure = OutOfMemoryError("private source URI or data")
        failure.stackTrace = arrayOf(StackTraceElement("outside.private.Secret", "method", "/private/source", 55),
            StackTraceElement("com.russianranger.fta.runtime.GameDataZip", "inspect", "SecretName.kt", 80))
        val saved = record().copy(state = "FAILED", failure = GameDataOperation.failureKind(failure, false), frames = GameDataOperation.safeFrames(failure))
        store.write(saved)
        val report = store.export(attempt, false)
        assertEquals("out_of_memory", report["failureKind"])
        assertEquals(listOf("runtime.GameDataZip.inspect:80"), report["applicationFrames"])
        assertFalse(report.toString().contains("private")); assertFalse(report.toString().contains("Secret"))
        assertFalse(report.containsKey("pid")); assertFalse(report.containsKey("owner"))
    }
    @Test fun fixedFailureCategoriesIncludeTimeoutStopAndVmErrors() {
        assertEquals("cancelled", GameDataOperation.failureKind(IllegalStateException(), true))
        assertEquals("timeout", GameDataOperation.failureKind(java.util.concurrent.TimeoutException(), false))
        assertEquals("stack_overflow", GameDataOperation.failureKind(StackOverflowError(), false))
        assertEquals("linkage", GameDataOperation.failureKind(NoClassDefFoundError(), false))
        assertEquals("io", GameDataOperation.failureKind(java.io.IOException(), false))
        assertEquals("security", GameDataOperation.failureKind(SecurityException(), false))
        assertEquals("invalid_argument", GameDataOperation.failureKind(IllegalArgumentException(), false))
        assertEquals("operation_failed", GameDataOperation.failureKind(IllegalStateException(), false))
    }
    @Test fun applicationFramesAreBoundedAndExcludeUnsafeNames() {
        val failure = Error()
        failure.stackTrace = (0..20).map { StackTraceElement("com.russianranger.fta.runtime.Safe", "stage", null, it) }.toTypedArray()
        assertEquals(4, GameDataOperation.safeFrames(failure).size)
        failure.stackTrace = arrayOf(StackTraceElement("com.russianranger.fta.private/path", "secret", null, 4))
        assertTrue(GameDataOperation.safeFrames(failure).isEmpty())
    }
    @Test fun androidExitMustMatchTokenPidAndAttemptTimeWindow() {
        val sample = GameDataExitEvidence.Sample(owner.pid, 2100, 3, 0, 1000, 2000, GameDataOperation.token(attempt))
        assertEquals(true, GameDataExitEvidence.export(sample, record(), 3000)["attemptMatched"])
        for (unmatched in listOf(sample.copy(pid = 999), sample.copy(token = null), sample.copy(token = "other"),
            sample.copy(timestamp = 999), sample.copy(timestamp = 3001))) {
            assertEquals(false, GameDataExitEvidence.export(unmatched, record(), 3000)["attemptMatched"])
        }
        assertEquals(false, GameDataExitEvidence.export(sample, null, 3000)["attemptMatched"])
    }
    @Test fun androidExitExportOmitsTokenPidAndNeverGuessesUnknownReason() {
        val sample = GameDataExitEvidence.Sample(owner.pid, 2100, 999, 9, 1000, 2000, "private opaque state")
        val report = GameDataExitEvidence.export(sample, record(), 3000)
        assertEquals("unknown", report["reason"]); assertEquals(999, report["reasonCode"])
        assertFalse(report.toString().contains("private")); assertFalse(report.containsKey("pid"))
        assertEquals(1000L, report["lastSampledPssKiB"])
    }
}
