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
    @Test fun largeHistoricalOutputsStayInTheSourceAndTheSessionStartsEmpty() {
        val original = source(); val size = ClientWorkingView.COPY_LIMIT + 1
        for (name in listOf("cAcHe", "Logs", "Errors", "Screenshots")) {
            val child = File(original, "$name/nested").apply { mkdirs() }
            RandomAccessFile(File(child, "old-output.bin"), "rw").use { it.setLength(size) }
        }
        val before = ClientWorkingView.plan(original)
        assertEquals(4 * size, before.excludedBytes)
        assertEquals(30L, before.copiedBytes); assertEquals(15L, before.sharedBytes)
        assertEquals(size, before.inputBytes.getValue("cache")); assertEquals(1, before.inputFiles.getValue("cache"))
        for (attempt in 1..2) {
            val view = File(temp.newFolder(), "view"); var ioBytes = 0
            val built = ClientWorkingView.build(original, view, beforeIo = { ioBytes += it })
            assertEquals(4 * size, built.excludedBytes); assertEquals(4, built.excludedFiles)
            assertEquals(1, built.sharedFiles); assertEquals(2, built.copiedFiles)
            assertTrue(ioBytes < 4 * 1024 * 1024)
            for (name in listOf("cAcHe", "Logs", "Errors", "Screenshots")) {
                val directory = File(view, name)
                assertTrue(directory.isDirectory); assertTrue(directory.list()!!.isEmpty())
                assertFalse(Files.isSameFile(directory.toPath(), File(original, name).toPath()))
                File(directory, "new-output").writeText("new")
                assertEquals(size, File(original, "$name/nested/old-output.bin").length())
                assertFalse(File(original, "$name/new-output").exists())
            }
            assertEquals(before, ClientWorkingView.plan(original))
        }
    }
    @Test fun oversizedSettingsStillFailWithCompleteBudgetBeforeAnyViewPublication() {
        val original = source(); val view = File(temp.newFolder(), "view")
        RandomAccessFile(File(original, "wTf/saved.lua"), "rw").use { it.setLength(ClientWorkingView.COPY_LIMIT + 1) }
        var observed: ClientWorkingView.Plan? = null
        rejected("view-copy-limit") { ClientWorkingView.plan(original, observed = { observed = it }) }
        assertEquals(ClientWorkingView.COPY_LIMIT + 16, observed!!.inputBytes["wtf"])
        assertEquals(15L, observed!!.inputBytes["configuration"])
        assertEquals(2, observed!!.inputFiles["wtf"])
        rejected("view-copy-limit") { ClientWorkingView.build(original, view) }
        assertFalse(view.exists())
    }
    @Test fun excludedOutputsRemainAuditedAndCannotHideUnsafeRoots() {
        val original = source(); val cache = File(original, "Cache").apply { mkdir() }
        val old = File(cache, "old").apply { writeText("old") }; val view = File(temp.newFolder(), "view")
        var changed = false
        rejected("runtime-changed") { ClientWorkingView.build(original, view, beforeIo = {
            if (!changed) { changed = true; old.appendText("changed") }
        }) }
        assertFalse(view.exists())
        old.delete(); cache.delete(); cache.writeText("not a directory")
        rejected("unsafe-runtime-store") { ClientWorkingView.plan(original) }
        cache.delete(); cache.mkdir()
        Files.createSymbolicLink(File(cache, "linked").toPath(), temp.newFile().toPath())
        rejected("unsafe-runtime-store") { ClientWorkingView.plan(original) }
    }
    @Test fun retainedCode43FixturePlanIdentityIsUnchanged() {
        val original = temp.newFolder()
        for (name in listOf("Data", "WTF", "Logs")) File(original, name).mkdir()
        File(original, "Data/canary.bin").writeBytes(ByteArray(65536))
        File(original, "WTF/Config.wtf").writeText("fixture-original-settings\n")
        for (name in listOf("probe32.exe", "probe64.exe"))
            RandomAccessFile(File(original, name), "rw").use { it.setLength(ClientGuardedFinish.assets.getValue(name).bytes.toLong()) }
        assertEquals("f2fff40f7c10693870a08fc649f3adfca3a8b692aeed5436dfb808d40539735c", ClientWorkingView.plan(original).sha256)
    }
    @Test fun thorSizedSettingsAreCopiedIntactWithSeparateBoundedSessionHeadroom() {
        // Reproduce the reported totals/counts with synthetic sparse files only.
        val original = source(); val parent = temp.newFolder(); val view = File(parent, "view")
        val settings = File(original, "wTf/saved.lua")
        RandomAccessFile(settings, "rw").use {
            it.setLength(112058085L - 15)
            it.writeUTF("synthetic first bytes"); it.seek(it.length() - 32); it.writeUTF("synthetic last bytes")
        }
        for (index in 1..4489) File(original, "wTf/saved-$index.lua").createNewFile()
        RandomAccessFile(File(original, "WarcraftXL.cfg"), "rw").use { it.setLength(4117) }
        RandomAccessFile(File(original, "Data/asset.mpq"), "rw").use { it.setLength(60130036502L) }
        File(original, "Cache").mkdir()
        RandomAccessFile(File(original, "Cache/old.bin"), "rw").use { it.setLength(1113628) }
        val plan = ClientWorkingView.plan(original)
        assertEquals(112062202L, plan.copiedBytes); assertEquals(4491, plan.inputFiles["wtf"])
        assertEquals(1113628L, plan.excludedBytes); assertEquals(60130036502L, plan.sharedBytes)
        rejected("insufficient-space") {
            ClientWorkingView.build(original, view, available = { plan.copiedBytes + ClientRuntimeStore.RESERVE - 1 })
        }
        assertTrue(parent.list()!!.isEmpty())
        var maxIo = 0
        val built = ClientWorkingView.build(original, view, beforeIo = { maxIo = maxOf(maxIo, it) })
        assertEquals(plan.copiedBytes, built.copiedBytes); assertTrue(maxIo <= 256 * 1024)
        fun digest(file: File): String {
            val hash = runtimeHash(); val buffer = ByteArray(256 * 1024)
            file.inputStream().use { input -> while (true) {
                val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n)
            } }
            return runtimeHex(hash.digest())
        }
        val copied = File(view, "wTf/saved.lua"); val originalHash = digest(settings)
        assertEquals(originalHash, digest(copied)); assertFalse(Files.isSameFile(settings.toPath(), copied.toPath()))
        val budget = ClientWorkingView.sessionBudget(view)
        assertEquals(112062202L, budget.initialBytes)
        assertEquals(budget.initialBytes + 128L * 1024 * 1024, budget.limitBytes)
        val output = File(view, "Cache/new.bin")
        RandomAccessFile(output, "rw").use { it.setLength(ClientWorkingView.GROWTH_LIMIT) }
        assertEquals(budget.limitBytes, ClientWorkingView.checkGrowth(view, budget.limitBytes))
        RandomAccessFile(output, "rw").use { it.setLength(ClientWorkingView.GROWTH_LIMIT + 1) }
        rejected("view-growth-limit") { ClientWorkingView.checkGrowth(view, budget.limitBytes) }
        RandomAccessFile(copied, "rw").use { it.writeUTF("working copy changed") }
        assertEquals(originalHash, digest(settings)); assertEquals(plan, ClientWorkingView.plan(original))
        assertFalse(File(original, "Cache/new.bin").exists())
    }
    @Test fun sessionBudgetRejectsOversizedBaselineAndNeverCountsSharedTargets() {
        val view = temp.newFolder(); val settings = File(view, "settings")
        val maximum = ClientWorkingView.COPY_LIMIT + ClientLaunchSettings.LIMIT
        RandomAccessFile(settings, "rw").use { it.setLength(maximum) }
        Files.createSymbolicLink(File(view, "shared").toPath(), java.nio.file.Paths.get("/"))
        assertEquals(maximum + ClientWorkingView.GROWTH_LIMIT, ClientWorkingView.sessionBudget(view).limitBytes)
        RandomAccessFile(settings, "rw").use { it.setLength(maximum + 1) }
        rejected("view-growth-limit") { ClientWorkingView.sessionBudget(view) }
    }
}
