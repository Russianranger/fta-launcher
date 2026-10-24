package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class WorldServiceProtocolTest {
    private class Fixture {
        val calls = mutableListOf<String>(); val checks = mutableMapOf<String, Boolean>()
        var time = 0L; var failStart = false; var failHealth = false; var worldExit = 0; var worldClosed = true
        val auth = object : LocalServiceProtocol.Driver {
            override fun checkActive() = Unit
            override fun start() { calls += "auth.start" }
            override fun health() { calls += "auth.health" }
            override fun interactive() = false
            override fun nowMs() = time
            override fun pause(ms: Long) { time += ms }
            override fun stop(): Int { calls += "auth.stop"; return 0 }
            override fun listenerClosed() = true
        }
        val world = object : WorldServiceProtocol.World {
            override fun start() { calls += "world.start"; check(!failStart) }
            override fun health() { calls += "world.health"; check(!failHealth) }
            override fun stop(): Int { calls += "world.stop"; return worldExit }
            override fun closed() = worldClosed
        }
        fun run(limit: Long = 120_000) = LocalServiceProtocol.run(
            WorldServiceProtocol.combine(auth, world) { k, v -> checks[k] = v }, { false },
            { k, v -> checks[k] = v }, {}, limit)
    }
    @Test fun threeComponentHealthAndReverseStopCoverFullScreenOffGate() {
        val f = Fixture(); val result = f.run()
        assertEquals(listOf("auth.start", "world.start", "auth.health", "world.health"), f.calls.take(4))
        assertEquals(listOf("world.stop", "auth.stop"), f.calls.takeLast(2))
        assertEquals(120_000L, result.screenOffHealthyMs)
        assertTrue(f.checks.values.all { it })
        assertEquals(f.calls.count { it == "auth.health" }, f.calls.count { it == "world.health" })
    }
    @Test fun startupAndHealthFailuresStillStopWorldBeforeAuth() {
        for (startup in listOf(false, true)) {
            val f = Fixture(); f.failStart = startup; f.failHealth = !startup
            val failure = assertThrows(WorldSessionFailure::class.java) { f.run() }
            assertEquals(if (startup) "WORLD_SESSION_START" else "LOCAL_SERVICE_RUNNING", failure.stage)
            assertEquals(listOf("world.stop", "auth.stop"), f.calls.takeLast(2))
            assertFalse(f.checks["protocolReady"] == true)
        }
    }
    @Test fun nonzeroWorldExitOrOpenListenerCannotQualifyButAuthStillStops() {
        for (nonzero in listOf(false, true)) {
            val f = Fixture(); f.worldExit = if (nonzero) 137 else 0; f.worldClosed = nonzero
            assertThrows(Exception::class.java) { f.run(1) }
            assertTrue(f.calls.contains("auth.stop"))
            assertEquals(true, f.checks["authCleanStop"])
            assertFalse(f.checks["worldCleanStop"] == true && f.checks["worldListenerClosed"] == true)
        }
    }
    @Test fun physicalCode27Exit139KeepsStartupFailureAfterAuthCleanup() {
        val f = Fixture(); f.failStart = true; f.worldExit = 139
        val failure = assertThrows(WorldSessionFailure::class.java) { f.run() }
        assertEquals("WORLD_SESSION_START", failure.stage)
        assertEquals(listOf("world.stop", "auth.stop"), f.calls.takeLast(2))
        assertEquals(true, f.checks["authCleanStop"])
        assertEquals(false, f.checks["worldCleanStop"])
        assertFalse(f.checks["protocolReady"] == true)
    }
    @Test fun liveOwnedWorldPreventsDependencyShutdown() {
        val f = Fixture(); f.worldExit = Int.MIN_VALUE
        assertThrows(Exception::class.java) { f.run(1) }
        assertFalse(f.calls.contains("auth.stop"))
    }
    @Test fun exactGtCoverageRejectsMissingExtraNullAndFailedRows() {
        WorldSessionEvidence.requireCoverage("1\n".repeat(10))
        for (value in listOf("1\n".repeat(9), "1\n".repeat(11), "1\n".repeat(9) + "0", "1\n".repeat(9) + "NULL"))
            assertThrows(Exception::class.java) { WorldSessionEvidence.requireCoverage(value) }
        val sql = WorldSessionEvidence.gtSql()
        assertEquals(10, sql.lines().size)
        assertEquals(10, Regex("MIN\\(ID\\)=0").findAll(sql).count())
        assertFalse(sql.contains("INSERT")); assertFalse(sql.contains("UPDATE"))
    }
    @Test fun sourceMarkersDoNotAcceptPartialOrWrongDbcCount() {
        assertEquals(emptySet<String>(), WorldSessionEvidence.markers(">> Initialized 112 Data Stores in 4 ms\nworldserver ready"))
        assertEquals(setOf("dbcStoresLoaded"), WorldSessionEvidence.markers(">> Initialized 113 Data Stores in 9 ms"))
        assertEquals(setOf("nativeReadyMarker", "worldHalted", "freezeGuardStarted"), WorldSessionEvidence.markers(
            "core (worldserver-daemon) ready...\nHalting process...\nStarting up anti-freeze thread (60 seconds max stuck time)..."))
    }
}
