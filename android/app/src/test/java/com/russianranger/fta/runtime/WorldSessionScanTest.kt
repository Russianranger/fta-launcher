package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorldSessionScanTest {
    private val root = Files.createTempDirectory("fta-world-scan").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    @Test fun completeMarkersRemainAvailableAtTheOriginalLogLimit() {
        val log = File(root, "world.log").apply { writeText("INFO [server.worldserver] Halting process...\n") }
        java.io.RandomAccessFile(log, "rw").use { it.setLength(WorldSessionEvidence.MAX_LOG_BYTES + 1) }
        val result = WorldSessionEvidence.scan(log)
        assertTrue(result.limitReached); assertTrue("worldHalted" in result.markers)
    }
    @Test fun partialAndOversizedLinesCannotBecomeMilestones() {
        val log = File(root, "world.log").apply { writeText("x".repeat(5000) + "Halting process...\nHalting process...") }
        assertTrue(WorldSessionEvidence.scan(log).markers.isEmpty())
        log.appendText("\n"); assertEquals(setOf("worldHalted"), WorldSessionEvidence.scan(log).markers)
    }
    @Test fun originalRequiredLifecycleMarkersAndFailuresAreRetained() {
        val log = File(root, "world.log").apply { writeText(listOf(
            ">> Initialized 113 Data Stores in 17 ms", "(worldserver-daemon) ready...",
            "Starting up anti-freeze thread (60 seconds max stuck time)...",
            "Failed to find map files for starting areas", "Halting process...").joinToString("\n", postfix = "\n")) }
        val result = WorldSessionEvidence.scan(log)
        assertFalse(result.limitReached)
        assertEquals(setOf("dbcStoresLoaded", "nativeReadyMarker", "freezeGuardStarted", "missingStartingMaps", "worldHalted"), result.markers)
        val link = File(root, "link"); Files.createSymbolicLink(link.toPath(), log.toPath())
        assertThrows(Exception::class.java) { WorldSessionEvidence.scan(link) }
    }
}
