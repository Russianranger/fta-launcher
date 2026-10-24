package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WorldFaultCaptureTest {
    private val root = Files.createTempDirectory("fta-fault-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private val line = "FTA_FAULT_V1 signal=11 code=1 pc=1:0000000000123456 lr=2:0000000000001234"
    private fun evidence(lines: List<String>, exit: Int? = 139): Map<String, Any> = WorldFaultCapture.Evidence().run {
        lines.forEach(::accept); export(exit)
    }
    @Test fun oneInitializedWitnessMatchesOnlyItsActualFatalExit() {
        val result = evidence(listOf(WorldFaultCapture.READY, line))
        assertEquals(true, result["available"]); assertEquals(11, result["signal"])
        assertEquals(mapOf("imageId" to 1, "elfOffsetHex" to "0000000000123456"), result["pc"])
        for (exit in listOf(null, 0, 135, 137)) assertEquals(false, evidence(listOf(WorldFaultCapture.READY, line), exit)["available"])
        assertEquals(true, evidence(listOf(WorldFaultCapture.READY, line.replace("signal=11 code=1", "signal=7 code=-6")), 135)["available"])
    }
    @Test fun missingDuplicateMalformedOrUnmappedLocationsCannotClaimEvidence() {
        for (lines in listOf(listOf(line), listOf(WorldFaultCapture.READY, line, line),
            listOf(WorldFaultCapture.READY, WorldFaultCapture.READY, line),
            listOf(WorldFaultCapture.READY, line, "FTA_FAULT_V1 malformed")))
            assertEquals(false, evidence(lines)["available"])
        for (bad in listOf(line + " /private/path", line.replace("pc=1:", "pc=99:"),
            line.replace("0000000000123456", "ffffffffffffffff"), line.replace("0000000000123456", "0000000100000000"),
            line.replace("pc=1:", "pc=0:"), line.replace("code=1", "code=private")))
            assertEquals(false, evidence(listOf(WorldFaultCapture.READY, bad))["available"])
    }
    @Test fun unknownAddressHasOnlyZeroImageAndZeroOffset() {
        val unknown = line.replace("pc=1:0000000000123456", "pc=0:0000000000000000")
        assertEquals(true, evidence(listOf(WorldFaultCapture.READY, unknown))["available"])
        assertFalse(evidence(listOf(WorldFaultCapture.READY, "private character data", line)).toString().contains("private character"))
    }
    @Test fun readyPrefixIsBoundedAndRejectsMissingPartialAndLinkedFiles() {
        val file = File(root, "stderr")
        assertFalse(WorldFaultCapture.ready(file))
        file.writeText(WorldFaultCapture.READY); assertFalse(WorldFaultCapture.ready(file))
        file.writeText(WorldFaultCapture.READY + "\n" + "x".repeat(100000)); assertTrue(WorldFaultCapture.ready(file))
        val link = File(root, "link"); Files.createSymbolicLink(link.toPath(), file.toPath())
        assertFalse(WorldFaultCapture.ready(link))
    }
    @Test fun preloadIsWorldOnlyAndNeverAcceptsInheritedOverride() {
        val file = File(root, WorldFaultCapture.NAME); val base = arrayOf("PATH=/system/bin")
        for (role in listOf("database-server", "database-client", "auth-server"))
            assertArrayEquals(base, WorldFaultCapture.forRole(base, role, file))
        assertArrayEquals(base + "LD_PRELOAD=${file.absolutePath}", WorldFaultCapture.forRole(base, "world-server", file))
        assertThrows(IllegalArgumentException::class.java) { WorldFaultCapture.forRole(arrayOf("LD_PRELOAD=/other"), "auth-server", file) }
        assertThrows(IllegalArgumentException::class.java) { WorldFaultCapture.forRole(base, "world-server", null) }
        assertThrows(IllegalArgumentException::class.java) { WorldFaultCapture.forRole(base, "unknown", file) }
    }
    @Test fun changedOrLinkedCaptureLibraryFailsBeforeLaunch() {
        val file = File(root, WorldFaultCapture.NAME).apply { writeText("changed") }
        assertThrows(Exception::class.java) { WorldFaultCapture.prepare(root) }
        file.delete(); val elsewhere = File(root, "other").apply { writeText("changed") }
        Files.createSymbolicLink(file.toPath(), elsewhere.toPath())
        assertThrows(Exception::class.java) { WorldFaultCapture.prepare(root) }
    }
}
