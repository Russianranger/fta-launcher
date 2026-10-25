package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class WorldLogEvidenceTest {
    private val prefix = "A source message"
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun dictionary() = WorldLogEvidence.dictionary((sha(prefix.toByteArray()) + "\tw0000\n").byteInputStream())
    private fun fixture(work: (File, String, NativeInvocationJournal) -> Unit) {
        val root = Files.createTempDirectory("world-log-test").toFile()
        val attempt = UUID.randomUUID().toString()
        val visit = File(root, "$attempt-1").apply { mkdir() }
        try { work(visit, attempt, NativeInvocationJournal(File(visit, "native-invocations.properties"), attempt)) }
        finally { root.deleteRecursively() }
    }
    private fun end(journal: NativeInvocationJournal, log: String = "tool-1") {
        val id = journal.begin(log, "world-server", "WORLD_SESSION_START", null)
        journal.finish(id, "exited", 139, 0, 0); journal.terminated(id, 139)
    }
    @Test fun finishedHistoricalAttemptIsReadOnlyAndNeverExportsVariableText() = fixture { visit, attempt, journal ->
        end(journal)
        val log = File(visit, "world-session.log")
        log.writeText("INFO [module] $prefix password=secret-private account=42 path=/private/realm\nunknown SQL SELECT sensitive\n")
        val before = log.readBytes(); val stamp = log.lastModified()
        val result = WorldLogEvidence.export(visit, attempt, true, dictionary())
        assertEquals(true, result["available"]); assertEquals(139, result["worldExitCode"])
        val text = result.toString()
        for (secret in listOf(prefix, "secret-private", "account=42", "/private/realm", "SELECT", "sensitive")) assertFalse(text.contains(secret))
        assertTrue(text.contains("w0000")); assertTrue(text.contains("linesAfterLastMatch=1"))
        assertEquals(false, result["sourceMessageMatchIsCrashCause"])
        assertArrayEquals(before, log.readBytes()); assertEquals(stamp, log.lastModified())
        assertFalse(File(visit, "tool-1.out").exists())
    }
    @Test fun staleRunningWrongScopeAndDuplicateWorldEvidenceAreRejected() = fixture { visit, attempt, journal ->
        end(journal)
        assertEquals(false, WorldLogEvidence.export(visit, attempt, false, dictionary())["available"])
        assertEquals(false, WorldLogEvidence.export(visit, UUID.randomUUID().toString(), true, dictionary())["available"])
        journal.begin("tool-2", "database-client", "REALM_METADATA", null)
        assertEquals(false, WorldLogEvidence.export(visit, attempt, true, dictionary())["available"])
        journal.finish(2, "exited", 0, 0, 0); end(journal, "tool-3")
        assertEquals(false, WorldLogEvidence.export(visit, attempt, true, dictionary())["available"])
    }
    @Test fun partialLargeAndUnrecognizedLinesCannotBecomeSourceMessages() = fixture { visit, _, _ ->
        val log = File(visit, "world-session.log")
        log.writeText("INFO [module] $prefix" + "x".repeat(5000) + "\nINFO [module] unfamiliar secret\nINFO [module] $prefix trailing-private")
        val result = WorldLogEvidence.scan(log, dictionary())
        assertEquals(3, result["lines"]); assertEquals(1, result["oversizedLinesSkipped"])
        assertEquals(1, result["matchedLines"]); assertEquals(0, result["linesAfterLastMatch"])
        assertFalse(result.toString().contains("trailing-private"))
    }
    @Test fun eventTailIsBoundedAndCollisionsRemainExplicitCandidates() = fixture { visit, _, _ ->
        val digest = sha(prefix.toByteArray())
        val dict = WorldLogEvidence.dictionary("$digest\tw0000\n$digest\tw0001\n".byteInputStream())
        val log = File(visit, "world-session.log").apply { writeText("INFO [module] $prefix\n".repeat(100)) }
        val result = WorldLogEvidence.scan(log, dict)
        assertEquals(100, result["matchedLines"])
        val tail = result["lastSourceMessages"] as List<*>
        assertEquals(64, tail.size)
        assertTrue(tail.first().toString().contains("line=37"))
        assertTrue(tail.last().toString().contains("sourceMessageIds=[w0000, w0001]"))
        val counts = result["sourceMessageCounts"] as List<*>
        assertEquals(1, counts.size)
        assertTrue(counts.single().toString().contains("count=100"))
        assertEquals(0, result["sourceMessageGroupsOmitted"])
    }
    @Test fun symlinksAreRejectedAndOversizedLogsRemainPartialEvidence() = fixture { visit, attempt, journal ->
        end(journal)
        val actual = File(visit, "other").apply { writeText("INFO [module] $prefix\n") }
        val log = File(visit, "world-session.log")
        Files.createSymbolicLink(log.toPath(), actual.toPath())
        assertEquals(false, WorldLogEvidence.scan(log, dictionary())["available"])
        log.delete()
        java.io.RandomAccessFile(log, "rw").use { it.setLength(WorldSessionEvidence.MAX_LOG_BYTES + 1) }
        val sampled = WorldLogEvidence.scan(log, dictionary())
        assertEquals(true, sampled["available"])
        assertEquals(false, sampled["completeFileRead"])
        assertEquals(true, sampled["limitReached"])
        assertEquals(false, sampled["runtimeQualificationEvidence"])
        assertFalse(sampled.containsKey("sha256"))
        assertFalse(sampled.containsKey("faultWitness"))
        assertTrue(WorldSessionEvidence.scan(log).limitReached)
        val redirected = File(visit.parentFile, "link").apply { mkdir() }
        Files.createSymbolicLink(File(redirected, visit.name).toPath(), visit.toPath())
        assertEquals(false, WorldLogEvidence.export(File(redirected, visit.name), attempt, true, dictionary())["available"])
    }
    @Test fun frequencyExportIsBoundedAndPrioritizesRepeatedSourceGroups() = fixture { visit, _, _ ->
        val prefixes = (0 until 100).map { "source-msg-%05d".format(it) }
        val dict = WorldLogEvidence.dictionary(prefixes.mapIndexed { index, value ->
            sha(value.toByteArray().copyOf(16)) + "\tw%04d\n".format(index)
        }.joinToString("").byteInputStream())
        val log = File(visit, "world-session.log")
        log.writeText((prefixes + List(9) { prefixes[0] }).joinToString("") { "WARN [module] $it private-content\n" })
        val result = WorldLogEvidence.scan(log, dict)
        val counts = result["sourceMessageCounts"] as List<*>
        assertEquals(64, counts.size)
        assertEquals(36, result["sourceMessageGroupsOmitted"])
        assertEquals(109, result["matchedLines"])
        assertTrue(counts.first().toString().contains("sourceMessageIds=[w0000]"))
        assertTrue(counts.first().toString().contains("count=10"))
        assertFalse(result.toString().contains("private-content"))
    }
    @Test fun hugeSparseLogReadsOnlyBoundedHeadAndTailWithConservativeLineBoundaries() = fixture { visit, _, _ ->
        val log = File(visit, "world-session.log")
        val half = WorldSessionEvidence.MAX_LOG_BYTES / 2
        val size = 1024 * 1024 * 1024L + 17
        val known = "WARN [module] $prefix private-value=must-not-export\n"
        val partial = "WARN [module] $prefix incomplete-private"
        java.io.RandomAccessFile(log, "rw").use {
            it.setLength(size)
            it.writeBytes(known)
            it.seek(half - partial.length); it.writeBytes(partial)
            it.seek(size - half); it.writeBytes(known + known)
            it.seek(size - partial.length); it.writeBytes(partial)
        }
        val stamp = log.lastModified()
        val result = WorldLogEvidence.scan(log, dictionary(), 139)
        assertEquals(true, result["available"])
        assertEquals(size, result["bytes"])
        assertEquals(WorldSessionEvidence.MAX_LOG_BYTES, result["sampledBytes"])
        assertEquals(size - WorldSessionEvidence.MAX_LOG_BYTES, result["omittedBytes"])
        val ranges = result["regions"] as List<*>
        assertEquals(2, ranges.size)
        for ((index, raw) in ranges.withIndex()) {
            val range = raw as Map<*, *>
            assertEquals(if (index == 0) 0L else size - half, range["offsetBytes"])
            assertEquals(half, range["bytes"])
            assertEquals(1, range["matchedLines"])
            assertEquals(1, range["lines"])
            assertTrue((range["trailingFragmentBytesSkipped"] as Long) > 0)
            assertFalse(range.containsKey("faultWitness"))
            val expectedBytes = ByteArray(half.toInt())
            java.io.RandomAccessFile(log, "r").use {
                it.seek(range["offsetBytes"] as Long); it.readFully(expectedBytes)
            }
            assertEquals(sha(expectedBytes), range["sha256"])
        }
        assertEquals(known.length.toLong(), (ranges[1] as Map<*, *>)["leadingFragmentBytesSkipped"])
        assertFalse(result.toString().contains("private-value"))
        assertFalse(result.toString().contains("incomplete-private"))
        assertEquals(size, log.length()); assertEquals(stamp, log.lastModified())
    }
    @Test fun exactRuntimeLimitIsStillRejectedWithoutPromotingForensicMarkers() = fixture { visit, _, _ ->
        val log = File(visit, "world-session.log")
        java.io.RandomAccessFile(log, "rw").use { it.setLength(WorldSessionEvidence.MAX_LOG_BYTES) }
        val result = WorldLogEvidence.scan(log, dictionary())
        assertEquals(true, result["completeFileRead"])
        assertEquals(true, result["limitReached"])
        assertTrue(WorldSessionEvidence.scan(log).limitReached)
        assertTrue(WorldSessionEvidence.scan(log).markers.isEmpty())
    }
    @Test fun dictionaryRejectsUnboundedOrUnvalidatedOutputIdentifiers() {
        val digest = sha(prefix.toByteArray())
        for (text in listOf("", "$digest\tprivate-data\n", "$digest\tw0000\n$digest\tw0000\n", "bad\tw0000\n", "x".repeat(80001)))
            assertThrows(Exception::class.java) { WorldLogEvidence.dictionary(text.byteInputStream()) }
    }
    @Test fun originalFailurePhaseSurvivesCleanupFailure() {
        val original = IllegalStateException("private details")
        val failure = assertThrows(WorldSessionFailure::class.java) {
            WorldSessionFailure.during("WORLD_SESSION_START") { throw original }
        }
        assertEquals("WORLD_SESSION_START", failure.stage); assertSame(original, failure.cause)
        val nested = assertThrows(WorldSessionFailure::class.java) {
            WorldSessionFailure.during("WORLD_SESSION_STOP") { throw failure }
        }
        assertSame(failure, nested); assertFalse(failure.message!!.contains("private details"))
    }
    @Test fun faultWitnessRequiresCompleteStderrAndAttemptMatchedFatalExit() = fixture { visit, attempt, journal ->
        end(journal)
        val text = WorldFaultCapture.READY + "\nFTA_FAULT_V1 signal=11 code=1 pc=1:0000000000123456 lr=0:0000000000000000"
        val stderr = File(visit, "tool-1.err").apply { writeText(text + "\n") }
        fun witness(result: Map<String, Any>) = result["faultWitness"] as Map<*, *>
        val exported = WorldLogEvidence.export(visit, attempt, true, dictionary())
        val logs = exported["logs"] as Map<*, *>
        val record = logs["stderr"] as Map<*, *>
        assertEquals(true, (record["faultWitness"] as Map<*, *>)["available"])
        assertEquals(false, witness(WorldLogEvidence.scan(stderr, dictionary(), 0))["available"])
        stderr.writeText(text)
        assertEquals(false, witness(WorldLogEvidence.scan(stderr, dictionary(), 139))["available"])
    }
}
