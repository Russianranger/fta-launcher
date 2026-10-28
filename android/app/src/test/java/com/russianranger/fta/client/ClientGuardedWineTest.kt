package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ClientGuardedWineTest {
    @get:Rule val temp = TemporaryFolder()
    private fun success() = (ClientGuardedWine.stages.map { "FTA_ANDROID_STAGE $it 0" } +
        ClientGuardedWine.keys.map { "FTA_GUARDED_WIN ${it.replace(':', ' ')} 0" } +
        listOf("FTA_GUARDED_DELETE x86 1 0", "FTA_GUARDED_DELETE x64 1 0", "FTA_GUARDED_WINE_PASS")).joinToString("\n")
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected rejection") } catch (e: RuntimeInstallFailure) { assertEquals("probe-failed", e.reason) }
    }
    @Test fun everyStageCheckAndDeletionObservationMustBeUniqueAndComplete() {
        val good = success()
        assertEquals(ClientGuardedWine.stages, ClientGuardedWine.parse(good, 0).keys.toList())
        for (line in good.lines()) {
            rejected { ClientGuardedWine.parse(good.replace(line, ""), 0) }
            rejected { ClientGuardedWine.parse(good + "\n" + line, 0) }
        }
        for (bad in listOf(good.replace("seek 0", "seek 1"), good.replace("x64 seek", "x64 unknown"),
            good + "\nFTA_GUARDED_WINE_FAILED", good + "\nFTA_GUARDED_ERROR x86 seek 5")) rejected { ClientGuardedWine.parse(bad, 0) }
        rejected { ClientGuardedWine.parse(good, 1) }
        rejected { ClientGuardedWine.parse("x".repeat(65537), 0) }
    }
    @Test fun progressExportsOnlyTypedKnownVocabularyAndKeepsDeletionSemanticsVisible() {
        val result = ClientGuardedWine.observe("private path\nFTA_GUARDED_WIN x86 seek 0\nFTA_GUARDED_WIN x64 unknown 0\n" +
            "FTA_GUARDED_ERROR x86 seek 5\nFTA_GUARDED_ERROR x64 seek 999999\nFTA_GUARDED_DELETE x86 1 0\n")
        assertEquals(mapOf("x86:seek" to 0), result.checks)
        assertEquals(mapOf("x86:seek" to 5), result.errors)
        assertEquals(mapOf("x86ReturnedSuccess" to 1, "x86Error" to 0), result.deletion)
    }
    @Test fun fixtureUsesWorkingViewBuilderAndSnapshotDetectsDataMetadataOrInventoryChange() {
        val run = temp.newFolder(); val probe = temp.newFolder()
        for (name in listOf("probe32.exe", "probe64.exe")) File(probe, name).writeText("synthetic")
        val built = ClientGuardedWine.create(run, probe, {}, {})
        assertEquals(26L, built.copiedBytes); assertEquals(1, built.copiedFiles); assertEquals(3, built.sharedFiles)
        assertEquals(65554L, built.sharedBytes)
        val source = File(run, "immutable")
        val stamp: (File) -> List<Long> = { listOf(it.lastModified(), it.length()) }
        val before = ClientGuardedWine.snapshot(source, stamp)
        File(run, "view/WTF/Config.wtf").writeText("private")
        assertEquals(before, ClientGuardedWine.snapshot(source, stamp))
        File(source, "Data/canary.bin").writeBytes(ByteArray(65536))
        assertNotEquals(before, ClientGuardedWine.snapshot(source, stamp))
        Files.createSymbolicLink(File(source, "link").toPath(), File(probe, "probe32.exe").toPath())
        rejected { ClientGuardedWine.snapshot(source, stamp) }
    }
}
