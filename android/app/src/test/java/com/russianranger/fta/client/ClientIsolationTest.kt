package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ClientIsolationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun id(n: Int) = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
    private fun transcript() = ClientIsolationProtocol.checks.joinToString("") { "FTA_RO $it 0\n" } + "FTA_RO_COMPLETE 1\n"
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected refusal") } catch (_: RuntimeInstallFailure) { }
    }
    @Test fun passRequiresEveryUniqueCheckInOrderAndZeroExit() {
        val output = transcript()
        assertTrue(ClientIsolationProtocol.passed(output, 0))
        assertFalse(ClientIsolationProtocol.passed(output, 137))
        for (line in output.lines().filter { it.isNotEmpty() }) {
            assertFalse(ClientIsolationProtocol.passed(output.replace("$line\n", ""), 0))
            assertFalse(ClientIsolationProtocol.passed(output.replace("$line\n", "$line\n$line\n"), 0))
        }
        assertFalse(ClientIsolationProtocol.passed(output.replace("FTA_RO proc-fd-write 0", "FTA_RO proc-fd-write 1"), 0))
        assertFalse(ClientIsolationProtocol.passed(output + "unexpected\n", 0))
    }
    @Test fun progressCannotInventOrDuplicateChecks() {
        assertEquals(listOf("read-open"), ClientIsolationProtocol.completed("FTA_RO read-open 0\nFTA_RO read-open 0\nFTA_RO invented 0\nFTA_RO seek 1\n"))
    }
    @Test fun setupIsSmallDistinctAndSnapshotsCatchMutation() {
        val files = temp.newFolder(); val a = ClientIsolationFiles.create(files, id(1))
        val b = ClientIsolationFiles.create(files, id(2)); assertNotEquals(a, b)
        val before = ClientIsolationFiles.snapshot(a) { listOf(it.length(), it.lastModified()) }
        assertEquals(4, before.size)
        assertEquals(65536L, File(a, "immutable/canary.bin").length())
        assertTrue(Files.isSymbolicLink(File(a, "writable/alias").toPath()))
        File(a, "immutable/canary.bin").writeText("changed")
        assertNotEquals(before, ClientIsolationFiles.snapshot(a) { listOf(it.length(), it.lastModified()) })
        assertEquals(65536L, File(b, "immutable/canary.bin").length())
    }
    @Test fun snapshotsRejectNewMissingOrLinkedEntries() {
        for (kind in 1..3) {
            val a = ClientIsolationFiles.create(temp.newFolder(), id(kind))
            val canary = File(a, "immutable/canary.bin")
            when (kind) {
                1 -> File(a, "immutable/unlisted").writeText("extra")
                2 -> canary.delete()
                3 -> { canary.delete(); Files.createSymbolicLink(canary.toPath(), temp.newFile().toPath()) }
            }
            rejected { ClientIsolationFiles.snapshot(a) { emptyList() } }
        }
    }
    @Test fun collisionsAndLinkedParentsCannotReuseOldWork() {
        val files = temp.newFolder(); ClientIsolationFiles.create(files, id(1))
        rejected { ClientIsolationFiles.create(files, id(1)) }
        val linked = temp.newFolder()
        Files.createSymbolicLink(File(linked, "client-isolation-attempts").toPath(), files.toPath())
        rejected { ClientIsolationFiles.create(linked, id(2)) }
    }
    @Test fun retainedAttemptLimitFailsWithoutDeletingAnything() {
        val files = temp.newFolder()
        for (n in 1..16) ClientIsolationFiles.create(files, id(n))
        rejected { ClientIsolationFiles.create(files, id(17)) }
        assertEquals(16, File(files, "client-isolation-attempts").list()!!.size)
    }
}
