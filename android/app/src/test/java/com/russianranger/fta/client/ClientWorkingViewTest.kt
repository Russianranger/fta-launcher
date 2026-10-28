package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class ClientWorkingViewTest {
    @get:Rule val temp = TemporaryFolder()
    private fun source(): File = temp.newFolder().apply {
        File(this, "Data").mkdir(); File(this, "Data/asset.mpq").writeText("shared-original")
        File(this, "wTf").mkdir(); File(this, "wTf/Config.wtf").writeText("copied-original")
        File(this, "WarcraftXL.cfg").writeText("original-config")
    }
    private fun rejected(reason: String, action: () -> Unit) {
        try { action(); fail("Expected refusal") } catch (e: RuntimeInstallFailure) { assertEquals(reason, e.reason) }
    }
    @Test fun sharesOnlyGuardedGuestPathsAndCopiesIndependentMutableFilesWithOriginalCasing() {
        val original = source(); val view = File(temp.newFolder(), "view")
        val result = ClientWorkingView.build(original, view)
        assertEquals(15L, result.sharedBytes); assertEquals(30L, result.copiedBytes)
        assertEquals(1, result.sharedFiles); assertEquals(2, result.copiedFiles)
        assertEquals("/immutable/Data/asset.mpq", Files.readSymbolicLink(File(view, "Data/asset.mpq").toPath()).toString())
        assertFalse(Files.isSymbolicLink(File(view, "wTf/Config.wtf").toPath()))
        assertFalse(Files.isSameFile(File(view, "wTf/Config.wtf").toPath(), File(original, "wTf/Config.wtf").toPath()))
        File(view, "wTf/Config.wtf").writeText("changed")
        assertEquals("copied-original", File(original, "wTf/Config.wtf").readText())
        assertTrue(File(view, "Logs").isDirectory); assertFalse(File(view, "WTF").exists())
    }
    @Test fun copyBudgetAndDiskReserveAreCheckedBeforeCreatingAnything() {
        val original = source(); val parent = temp.newFolder(); val view = File(parent, "view")
        RandomAccessFile(File(original, "WarcraftXL.cfg"), "rw").use { it.setLength(ClientWorkingView.COPY_LIMIT + 1) }
        rejected("view-copy-limit") { ClientWorkingView.build(original, view) }
        assertTrue(parent.list()!!.isEmpty())
        File(original, "WarcraftXL.cfg").writeText("small")
        rejected("insufficient-space") { ClientWorkingView.build(original, view, available = { 1 }) }
        assertTrue(parent.list()!!.isEmpty())
    }
    @Test fun cancellationAndLowSpaceDuringCopyRemoveOnlyTheUnpublishedView() {
        for (cancel in listOf(true, false)) {
            val original = source(); val parent = temp.newFolder(); val view = File(parent, "view")
            var requests = 0
            rejected(if (cancel) "cancelled" else "insufficient-space") {
                ClientWorkingView.build(original, view, beforeIo = {
                    requests++; if (cancel) throw RuntimeInstallFailure("cancelled")
                }, available = { if (requests > 0) 0 else Long.MAX_VALUE })
            }
            assertTrue(parent.list()!!.isEmpty())
            assertEquals("shared-original", File(original, "Data/asset.mpq").readText())
            assertEquals("copied-original", File(original, "wTf/Config.wtf").readText())
        }
    }
    @Test fun existingLinkedNestedAndAmbiguousDestinationsAreRefused() {
        val original = source()
        rejected("unsafe-runtime-store") { ClientWorkingView.build(original, File(original, "nested")) }
        rejected("unsafe-runtime-store") { ClientWorkingView.build(original, temp.newFolder()) }
        val link = File(temp.newFolder(), "linked"); Files.createSymbolicLink(link.toPath(), original.toPath())
        rejected("unsafe-runtime-store") { ClientWorkingView.build(original, link) }
        for (name in listOf("nul.txt", "bad:", "trailing.")) {
            val bad = File(original, name).apply { writeText("bad") }
            rejected("unsafe-runtime-store") { ClientWorkingView.plan(original) }; bad.delete()
        }
        Files.createSymbolicLink(File(original, "linked").toPath(), temp.newFile().toPath())
        rejected("unsafe-runtime-store") { ClientWorkingView.plan(original) }
    }
    @Test fun metadataChangeDuringCopyCannotPublishAView() {
        val original = source(); val parent = temp.newFolder(); var changed = false
        rejected("runtime-changed") { ClientWorkingView.build(original, File(parent, "view"), beforeIo = {
            if (!changed) { changed = true; File(original, "added.txt").writeText("concurrent change") }
        }) }
        assertTrue(parent.list()!!.isEmpty())
    }
    @Test fun growthCheckNeverFollowsLinksAndStopsAtItsThreshold() {
        val root = temp.newFolder(); File(root, "log").writeBytes(ByteArray(20))
        Files.createSymbolicLink(File(root, "root-link").toPath(), java.nio.file.Paths.get("/"))
        assertEquals(20L, ClientWorkingView.checkGrowth(root, 20))
        rejected("view-growth-limit") { ClientWorkingView.checkGrowth(root, 19) }
    }
}
