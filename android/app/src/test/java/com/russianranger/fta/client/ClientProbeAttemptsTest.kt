package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ClientProbeAttemptsTest {
    @get:Rule val temp = TemporaryFolder()
    private fun id(n: Int) = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
    private fun rejected(reason: String, action: () -> Unit) {
        try { action(); fail() } catch (e: RuntimeInstallFailure) { assertEquals(reason, e.reason) }
    }
    @Test fun threeLegacyAttemptsArePreservedWhileNewCompletedSlotCanBeReused() {
        val root = temp.newFolder()
        val legacy = File(root, "client-runtime-attempts").apply { mkdir() }
        File(legacy, id(1)).mkdir()
        val old = ClientProbePaths.attempt(root, id(2)).apply { mkdirs() }
        ClientProbePaths.attempt(root, id(3)).mkdir()
        val run = ClientProbeAttempts.create(root, id(4))
        File(run, "prefix").mkdir(); File(run, "prefix/data").writeText("synthetic")
        ClientProbeAttempts.retire(run)
        assertTrue(ClientProbeAttempts.create(root, id(5)).isDirectory)
        assertFalse(run.exists()); assertTrue(old.exists()); assertTrue(File(legacy, id(1)).exists())
    }
    @Test fun uncertainFourthAttemptBlocksWithoutDeletingAnything() {
        val root = temp.newFolder()
        for (n in 1..4) ClientProbePaths.attempt(root, id(n)).mkdirs()
        rejected("probe-attempt-limit") { ClientProbeAttempts.create(root, id(5)) }
        assertEquals(4, File(root, "cr").list()!!.size)
    }
    @Test fun cleanupNeverTraversesWineLinksIntoRetainedData() {
        val root = temp.newFolder(); val retained = temp.newFolder()
        val game = File(retained, "game").apply { writeText("untouched") }
        val run = ClientProbeAttempts.create(root, id(1))
        Files.createSymbolicLink(File(run, "dosdevices").toPath(), retained.toPath())
        ClientProbeAttempts.retire(run); ClientProbeAttempts.create(root, id(2))
        assertEquals("untouched", game.readText()); assertFalse(run.exists())
    }
    @Test fun fakeOrSymlinkReceiptsNeverAuthorizeRemoval() {
        val root = temp.newFolder()
        val a = ClientProbeAttempts.create(root, id(1))
        File(a, "owned-cleanup-v1").writeText("FTA_OWNED_CLEANUP")
        val b = ClientProbeAttempts.create(root, id(2))
        val external = temp.newFile().apply { writeText("FTA_OWNED_CLEANUP_V1\n") }
        Files.createSymbolicLink(File(b, "owned-cleanup-v1").toPath(), external.toPath())
        ClientProbeAttempts.create(root, id(3))
        assertTrue(a.isDirectory); assertTrue(b.isDirectory); assertTrue(external.exists())
    }
    @Test fun symlinkedAttemptRootsAreRejected() {
        for (name in listOf("cr", "client-runtime-attempts")) {
            val root = temp.newFolder()
            Files.createSymbolicLink(File(root, name).toPath(), temp.newFolder().toPath())
            rejected("unsafe-runtime-store") { ClientProbeAttempts.create(root, id(1)) }
        }
    }
}
