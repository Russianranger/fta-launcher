package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import java.util.Properties
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class NativeInvocationJournalTest {
    private val root = Files.createTempDirectory("fta-invocation-test").toFile()
    private val file = File(root, "evidence.properties")
    private val journal = NativeInvocationJournal(file)
    @After fun cleanup() { root.deleteRecursively() }
    private fun records() = NativeInvocationJournal.read(file)!!.first

    @Test fun nonzeroAuthExitSurvivesCleanupStatus() {
        val id = journal.begin("tool-38", "auth-server", "AUTH_UPDATER", 180_000)
        journal.finish(id, "exited", 134, 4096, 120)
        journal.finish(id, "stopped", null, 0, 0)
        journal.terminated(id, 134)
        val result = records().single()
        assertEquals("AUTH_UPDATER", result.stage)
        assertEquals("auth-server", result.role)
        assertEquals(134, result.exitCode)
        assertEquals(134, result.terminationExitCode)
        assertEquals("exited", result.outcome)
        assertFalse(result.timedOut)
        assertEquals(4096L, result.stdoutBytes)
    }

    @Test fun timeoutAndKillAreDistinctFromCommandExit() {
        val id = journal.begin("tool-2", "database-client", "STARTING", 2_000)
        journal.finish(id, "timed_out", null, 0, 0)
        journal.terminated(id, 137)
        val result = records().single()
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
        assertEquals(137, result.terminationExitCode)
        assertEquals(2_000L, result.timeoutMs)
    }

    @Test fun successfulExitWithMissingMarkerRetainsItsReason() {
        val id = journal.begin("tool-38", "auth-server", "AUTH_UPDATER", 180_000)
        journal.finish(id, "exited", 0, 100, 0)
        journal.validationFailure("auth-server", "AUTH_UPDATER", "completion_marker_missing")
        assertEquals(0, records().single().exitCode)
        assertEquals("completion_marker_missing", records().single().validationFailure)
    }

    @Test fun cleanDatabaseShutdownRequiresObservedReadySqlAndZeroExit() {
        val id = journal.begin("server", "database-server", "STARTING", null)
        assertThrows(IllegalArgumentException::class.java) { journal.checkpoint(id, "clean_shutdown") }
        journal.checkpoint(id, "database_ready")
        journal.checkpoint(id, "sql_shutdown_requested")
        journal.finish(id, "exited", 0, 0, 100)
        journal.checkpoint(id, "clean_shutdown")
        val result = records().single()
        assertTrue(result.readyObserved)
        assertTrue(result.sqlShutdownRequested)
        assertTrue(result.cleanShutdownObserved)
    }

    @Test fun forcedStopDoesNotClaimCleanSqlShutdown() {
        val id = journal.begin("server", "database-server", "STARTING", null)
        journal.checkpoint(id, "database_ready")
        journal.finish(id, "cancelled", null, 0, 0)
        journal.terminated(id, 137)
        val result = records().single()
        assertTrue(result.cancelled)
        assertFalse(result.sqlShutdownRequested)
        assertFalse(result.cleanShutdownObserved)
    }

    @Test fun fixedSchemaRejectsArgumentsPathsAndCredentialText() {
        assertThrows(IllegalArgumentException::class.java) { journal.begin("tool-1 --password=private", "auth-server", "AUTH_UPDATER", 10_000) }
        assertThrows(IllegalArgumentException::class.java) { journal.begin("tool-1", "/private/path", "AUTH_UPDATER", 10_000) }
        assertThrows(IllegalArgumentException::class.java) { journal.begin("tool-1", "auth-server", "password=private", 10_000) }
        val id = journal.begin("tool-1", "auth-server", "AUTH_UPDATER", 10_000)
        assertThrows(IllegalArgumentException::class.java) { journal.validationFailure("auth-server", "AUTH_UPDATER", "SQL: private") }
        journal.finish(id, "start_failed", null, 0, 0)
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        assertFalse(properties.values.any { it.toString().contains("private") })
        assertFalse(properties.keys.any { it.toString().contains("arg") || it.toString().contains("path") || it.toString().contains("password") })
    }

    @Test fun boundEvictsFinishedToolsWhileRetainingRunningDatabase() {
        val server = journal.begin("server", "database-server", "STARTING", null)
        repeat(140) { index ->
            val id = journal.begin("tool-${index + 1}", "database-client", "CHECKING", 5_000)
            journal.finish(id, "exited", 0, 0, 0)
        }
        val read = NativeInvocationJournal.read(file)!!
        assertEquals(NativeInvocationJournal.MAX_RECORDS, read.first.size)
        assertTrue(read.second > 0)
        assertTrue(read.first.any { it.id == server && it.outcome == "running" })
        assertTrue(file.length() <= NativeInvocationJournal.MAX_FILE_BYTES)
    }

    @Test fun malformedStoredMetadataCannotBecomeExportedText() {
        journal.begin("tool-1", "auth-server", "AUTH_VERSION", 10_000)
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        properties.setProperty("0.role", "secret-in-a-private-uri")
        file.outputStream().use { properties.store(it, null) }
        assertNull(NativeInvocationJournal.read(file))
    }

    @Test fun currentAttemptCannotSelectPreviousAttemptEvidence() {
        val old = "11111111-1111-1111-1111-111111111111"
        val current = "22222222-2222-2222-2222-222222222222"
        val oldJournal = NativeInvocationJournal(file, old)
        val id = oldJournal.begin("tool-1", "auth-server", "AUTH_UPDATER", 180_000)
        oldJournal.finish(id, "exited", 0, 100, 0)
        assertNotNull(NativeInvocationJournal.read(file, old))
        assertNull(NativeInvocationJournal.read(file, current))
        val properties = Properties().apply { file.inputStream().use { load(it) } }
        properties.remove("attemptId")
        file.outputStream().use { properties.store(it, null) }
        assertNull(NativeInvocationJournal.read(file, current))
    }

    @Test fun evidenceWriteFailureCannotPreventNativeStop() {
        var stopped = false
        val result = NativeEvidenceCleanup.stop(
            recordBefore = { throw java.io.IOException("simulated storage full") },
            nativeStop = { stopped = true; 0 },
            recordAfter = { throw java.io.IOException("simulated storage full") }
        )
        assertTrue(stopped)
        assertEquals(0, result)
    }

    @Test fun nativeStopFailureIsNotReplacedByAnEvidenceFailure() {
        val problem = java.io.IOException("simulated native stop failure")
        val observed = assertThrows(java.io.IOException::class.java) {
            NativeEvidenceCleanup.stop(
                recordBefore = { throw java.io.IOException("simulated storage full") },
                nativeStop = { throw problem },
                recordAfter = { _: Int -> error("No native exit was observed") }
            )
        }
        assertSame(problem, observed)
    }

    @Test fun startFailureIsRecordedWithoutInventingAnExitCode() {
        val id = journal.begin("tool-1", "auth-server", "AUTH_VERSION", 10_000)
        journal.finish(id, "start_failed", null, 0, 0)
        assertEquals("start_failed", records().single().outcome)
        assertNull(records().single().exitCode)
    }
}
