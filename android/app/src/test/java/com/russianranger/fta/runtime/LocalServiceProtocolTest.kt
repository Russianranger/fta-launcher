package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class LocalServiceProtocolTest {
    private class Driver : LocalServiceProtocol.Driver {
        var clock = 0L; var checks = 0; var stops = 0; var starts = 0
        var interactiveAt: (Long) -> Boolean = { false }
        var failedHealth = 0; var stopExit = 0; var openAfterStop = false; var cancelled = false
        var healthDelay = 0L
        override fun checkActive() { check(!cancelled) }
        override fun start() { starts++ }
        override fun health() { checks++; check(checks != failedHealth); clock += healthDelay }
        override fun interactive() = interactiveAt(clock)
        override fun nowMs() = clock
        override fun pause(ms: Long) { clock += ms }
        override fun stop(): Int { stops++; return stopExit }
        override fun listenerClosed() = !openAfterStop
    }
    @Test fun requestedStopCompletesOneSessionWithOrderedProof() {
        val driver = Driver(); val flags = mutableListOf<String>(); val updates = mutableListOf<LocalServiceProtocol.Progress>()
        val result = LocalServiceProtocol.run(driver, { driver.clock >= 25_000 }, { k, v -> assertTrue(v); flags += k }, updates::add)
        assertEquals(LocalServiceProtocol.fields, flags); assertEquals("user", result.stopReason)
        assertEquals(3, result.healthySamples); assertEquals(1, driver.starts); assertEquals(1, driver.stops)
        assertEquals(20_000L, result.screenOffHealthyMs); assertEquals(result, updates.last())
    }
    @Test fun automaticLimitIsReportedSeparatelyFromUserStop() {
        val driver = Driver()
        val result = LocalServiceProtocol.run(driver, { false }, { _, _ -> }, {}, 25_000)
        assertEquals("session_limit", result.stopReason); assertEquals(1, driver.stops)
    }
    @Test fun earlyStopNeverLaunchesAuth() {
        val driver = Driver(); val flags = mutableListOf<String>()
        val result = LocalServiceProtocol.run(driver, { true }, { k, _ -> flags += k }, {})
        assertEquals("before_start", result.stopReason); assertEquals(0, driver.starts); assertEquals(0, driver.stops)
        assertEquals(0, result.healthySamples); assertTrue(flags.isEmpty())
    }
    @Test fun twoMinutesOfMeasuredScreenOffHealthAreCounted() {
        val driver = Driver()
        val result = LocalServiceProtocol.run(driver, { driver.clock >= 130_000 }, { _, _ -> }, {})
        assertTrue(result.screenOffHealthyMs >= LocalServiceProtocol.SCREEN_OFF_REQUIRED_MS)
        assertEquals(14, result.healthySamples)
    }
    @Test fun screenOnBetweenTwoOffProbesInvalidatesThatInterval() {
        val driver = Driver().apply { interactiveAt = { it in 5_000..6_000 } }
        val result = LocalServiceProtocol.run(driver, { driver.clock >= 20_000 }, { _, _ -> }, {})
        assertEquals(10_000L, result.screenOffHealthyMs)
    }
    @Test fun litScreenNeverQualifiesAsScreenOff() {
        val driver = Driver().apply { interactiveAt = { true } }
        val result = LocalServiceProtocol.run(driver, { driver.clock >= 130_000 }, { _, _ -> }, {})
        assertEquals(0L, result.screenOffHealthyMs)
    }
    @Test fun longHealthGapDoesNotBecomeScreenOffEvidence() {
        val driver = Driver().apply { healthDelay = 6_000 }
        val result = LocalServiceProtocol.run(driver, { driver.clock >= 40_000 }, { _, _ -> }, {})
        assertTrue(result.healthySamples > 1); assertEquals(0L, result.screenOffHealthyMs)
    }
    @Test fun failedHealthStopsAuthWithoutPublishingCleanCompletion() {
        for (failed in listOf(1, 2)) {
            val driver = Driver().apply { failedHealth = failed }; val flags = mutableListOf<String>()
            assertThrows(IllegalStateException::class.java) {
                LocalServiceProtocol.run(driver, { false }, { k, _ -> flags += k }, {})
            }
            assertEquals(1, driver.stops); assertFalse(flags.contains("authCleanStop")); assertFalse(flags.contains("listenerClosed"))
        }
    }
    @Test fun nonzeroAuthStopAndOpenListenerCannotQualify() {
        for (failStop in listOf(true, false)) {
            val driver = Driver().apply { if (failStop) stopExit = 137 else openAfterStop = true }
            val flags = mutableListOf<String>()
            assertThrows(IllegalStateException::class.java) {
                LocalServiceProtocol.run(driver, { driver.clock >= 100 }, { k, _ -> flags += k }, {})
            }
            assertFalse(flags.contains("listenerClosed"))
        }
    }
    @Test fun unexpectedCancellationCannotBecomeNormalStop() {
        val driver = Driver(); val flags = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            LocalServiceProtocol.run(driver, { driver.cancelled = driver.clock >= 500; driver.cancelled }, { k, _ -> flags += k }, {})
        }
        assertEquals(1, driver.stops); assertFalse(flags.contains("authCleanStop"))
    }
    @Test fun invalidSessionLimitsAreRejectedBeforeLaunch() {
        for (limit in listOf(0L, -1L, LocalServiceProtocol.MAX_SESSION_MS + 1)) {
            val driver = Driver()
            assertThrows(IllegalArgumentException::class.java) { LocalServiceProtocol.run(driver, { false }, { _, _ -> }, {}, limit) }
            assertEquals(0, driver.starts)
        }
    }
    @Test fun fullLengthSessionStaysWithinNativeJournalCapacity() {
        val driver = Driver()
        val result = LocalServiceProtocol.run(driver, { false }, { _, _ -> }, {})
        assertEquals("session_limit", result.stopReason)
        assertEquals(181, result.healthySamples)
        assertTrue(result.healthySamples + 30 < NativeInvocationJournal.MAX_RECORDS)
    }
    @Test fun serviceJournalRequiresAuthStopSqlShutdownAndStrictMetadata() {
        val root = Files.createTempDirectory("fta-service-proof").toFile()
        try {
            val attempt = UUID.randomUUID().toString(); val file = File(root, "journal.properties")
            val journal = NativeInvocationJournal(file, attempt)
            val db = journal.begin("server", "database-server", "REALM_REOPEN", null)
            journal.checkpoint(db, "database_ready"); var i = 0
            fun client(stage: String) { val id = journal.begin("tool-${++i}", "database-client", stage, 1000); journal.finish(id, "exited", 0, 1, 0) }
            repeat(6) { client("REALM_METADATA") }; repeat(2) { client("REALM_VERIFY") }
            repeat(3) { client("LOCAL_SERVICE_RUNNING") }
            val auth = journal.begin("tool-${++i}", "auth-server", "LOCAL_AUTH_START", null)
            journal.finish(auth, "exited", 0, 1, 0); journal.terminated(auth, 0)
            journal.checkpoint(db, "sql_shutdown_requested"); journal.finish(db, "exited", 0, 0, 0)
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt, true)["qualified"])
            journal.checkpoint(db, "clean_shutdown")
            assertEquals(true, LocalAuthDiagnostic.nativeEvidence(file, attempt, true)["qualified"])
            assertEquals(3, LocalAuthDiagnostic.nativeEvidence(file, attempt, true)["healthySamples"])
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt)["qualified"])
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt, true, true)["qualified"])
            val world = journal.begin("tool-${++i}", "world-server", "WORLD_SESSION_START", 600000)
            journal.finish(world, "exited", 0, 1, 0); journal.terminated(world, 0)
            assertEquals(true, LocalAuthDiagnostic.nativeEvidence(file, attempt, true, true)["qualified"])
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt, true)["qualified"])
            journal.terminated(world, 137)
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt, true, true)["qualified"])
            journal.terminated(auth, 137)
            assertEquals(false, LocalAuthDiagnostic.nativeEvidence(file, attempt, true)["qualified"])
        } finally { root.deleteRecursively() }
    }
}
