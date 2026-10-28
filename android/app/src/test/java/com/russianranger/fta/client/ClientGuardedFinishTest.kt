package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

class ClientGuardedFinishTest {
    @get:Rule val temp = TemporaryFolder()
    private val id = "12345678-1234-1234-1234-123456789abc"
    private fun receipt(): MutableMap<String, Any?> = mutableMapOf(
        "state" to "failed", "reason" to "io-failed", "operation" to "probe", "attemptId" to id,
        "archiveSha256" to ClientGuardedFinish.ARCHIVE, "catalogSha256" to ClientGuardedFinish.CATALOG,
        "runtimePreRehashValidated" to true, "protectedFixtureUnchanged" to true,
        "ownershipNormalExit" to 0, "ownershipStopExit" to 255, "ownershipKilledExit" to 137,
        "stoppedTracees" to 3, "killedTracees" to 3, "probeCleanupComplete" to 1,
        "viewCopiedBytes" to 26L, "viewSharedBytes" to 573547L, "viewCopiedFiles" to 1, "viewSharedFiles" to 3,
        "viewPlanSha256" to "a".repeat(64), "guardedPayloadSha256" to ClientGuardedWine.bridges,
        "displayEvent" to "ready", "socketProbeErrno" to 0,
        "windowsChecks" to ClientGuardedWine.keys.associateWith { 0 },
        "probeStages" to ClientGuardedWine.stages.associateWith { 0 },
        "windowsDeletion" to mapOf("x86ReturnedSuccess" to 1, "x86Error" to 0, "x64ReturnedSuccess" to 1, "x64Error" to 0))
    private fun transcript() = (ClientGuardedWine.stages.map { "FTA_ANDROID_STAGE $it 0" } +
        ClientGuardedWine.keys.map { "FTA_GUARDED_WIN ${it.replace(':', ' ')} 0" } +
        listOf("FTA_GUARDED_DELETE x86 1 0", "FTA_GUARDED_DELETE x64 1 0", "FTA_GUARDED_WINE_PASS")).joinToString("\n")
    private fun links(file: File) = (Files.getAttribute(file.toPath(), "unix:nlink", NOFOLLOW_LINKS) as Number).toLong()
    private data class Fixture(val files: File, val run: File, val plan: String, val pins: Map<String, ClientGuardedFinish.Pin>)
    private fun fixture(): Fixture {
        val files = temp.newFolder(); val run = ClientGuardedWine.createAttempt(files, id)
        val probe = File(run, "probe").apply { mkdir() }
        val pins = ClientGuardedWine.assets.associateWith { name ->
            val bytes = ("synthetic " + name).toByteArray(); File(probe, name).writeBytes(bytes)
            ClientGuardedFinish.Pin(bytes.size, runtimeHex(runtimeHash().digest(bytes)))
        }
        val view = ClientGuardedWine.create(run, probe, {}, {})
        File(run, "view/WTF/Config.wtf").writeText("private-original-settings\n")
        File(run, "step-3.out").writeText(transcript())
        ClientProbeAttempts.retire(run)
        return Fixture(files, run, view.planSha256, pins)
    }
    private fun validate(f: Fixture) = ClientGuardedFinish.validateFiles(f.files, id, f.plan, links = ::links, pins = f.pins)
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Expected refusal") } catch (e: RuntimeInstallFailure) {
            assertTrue(e.reason in setOf("retained-probe-changed", "probe-failed", "unsafe-runtime-store"))
        }
    }
    @Test fun receiptRequiresEveryGateWithTypedExactCountsAndNoContradictoryError() {
        val good = receipt(); assertTrue(ClientGuardedFinish.eligible(good))
        for (key in good.keys) {
            assertFalse("Missing $key", ClientGuardedFinish.eligible(good.toMutableMap().apply { remove(key) }))
            assertFalse("Wrong type $key", ClientGuardedFinish.eligible(good.toMutableMap().apply { put(key, emptyList<String>()) }))
        }
        for ((key, value) in mapOf("state" to "complete", "reason" to "runtime-changed", "attemptId" to "../escape",
            "ownershipKilledExit" to 0, "protectedFixtureUnchanged" to false, "viewSharedBytes" to 573548,
            "windowsErrors" to mapOf("x64:seek" to 5), "windowsChecks" to ClientGuardedWine.keys.associateWith { false })) {
            assertFalse(key, ClientGuardedFinish.eligible(good.toMutableMap().apply { put(key, value) }))
        }
        assertTrue(ClientGuardedFinish.eligible(good.toMutableMap().apply { put("windowsErrors", emptyMap<String, Int>()) }))
    }
    @Test fun retainedFilesQualifyReadOnlyWithSamePlanAndBoundedRequests() {
        val f = fixture(); val source = File(f.run, "immutable")
        val stamp: (File) -> List<Long> = { listOf(it.length(), it.lastModified(), links(it)) }
        val before = ClientGuardedWine.snapshot(source, stamp)
        var io = 0
        val result = ClientGuardedFinish.validateFiles(f.files, id, f.plan, beforeIo = { assertTrue(it in 1..65536); io += it }, links = ::links, pins = f.pins)
        assertEquals(f.plan, result.planSha256); assertEquals(64, result.transcriptSha256.length); assertTrue(io > 65536)
        assertEquals(receipt()["windowsDeletion"], result.deletion)
        assertEquals(before, ClientGuardedWine.snapshot(source, stamp))
        assertEquals("private-original-settings\n", File(f.run, "view/WTF/Config.wtf").readText())
    }
    @Test fun missingRetirementChangedAssetsAndChangedSourceAreRefused() {
        for (name in listOf("owned-cleanup-v1", "probe/android_probe.py", "immutable/Data/canary.bin", "immutable/WTF/Config.wtf")) {
            val f = fixture(); File(f.run, name).writeText("changed")
            rejected { validate(f) }
        }
    }
    @Test fun sharedTargetsConfigCopiesExtraEntriesAndHardLinksAreChecked() {
        val badLink = fixture(); val link = File(badLink.run, "view/Data/canary.bin").toPath()
        Files.delete(link); Files.createSymbolicLink(link, java.nio.file.Paths.get("/other/canary.bin"))
        rejected { validate(badLink) }
        for (name in listOf("view/WTF/Config.wtf", "view/Logs/unexpected", "immutable/unexpected", "probe/unlisted")) {
            val f = fixture(); File(f.run, name).writeText("changed"); rejected { validate(f) }
        }
        val hard = fixture(); Files.createLink(File(temp.newFolder(), "alias").toPath(), File(hard.run, "immutable/probe32.exe").toPath())
        rejected { validate(hard) }
    }
    @Test fun transcriptCannotBePartialDuplicatedFailedOrFromAnotherPlan() {
        for (text in listOf(transcript().replace("FTA_GUARDED_WINE_PASS", ""), transcript() + "\nFTA_ANDROID_STAGE prefix 0",
            transcript().replace("x64 seek 0", "x64 seek 1"), transcript().replace("FTA_GUARDED_DELETE x64 1 0", "FTA_GUARDED_DELETE x64 1 5"))) {
            val f = fixture(); File(f.run, "step-3.out").writeText(text); rejected { validate(f) }
        }
        val f = fixture(); rejected { ClientGuardedFinish.validateFiles(f.files, id, "0".repeat(64), links = ::links, pins = f.pins) }
    }
    @Test fun cancellationKeepsTheRetainedAttemptAndSourceAvailable() {
        val f = fixture()
        try { ClientGuardedFinish.validateFiles(f.files, id, f.plan, active = { throw RuntimeInstallFailure("cancelled") }, links = ::links, pins = f.pins); fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("cancelled", e.reason) }
        assertTrue(File(f.run, "owned-cleanup-v1").isFile); validate(f)
    }
}
