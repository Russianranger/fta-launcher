package com.russianranger.fta.client

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ClientProbeEvidenceTest {
    private val id = "123fd2c4-8809-4faf-b25a-4bc26080dc45"
    @Test fun code38AndroidPathExceedsSocketAndFallbackBudgetWhileNewPathFits() {
        val files = File("/data/user/0/com.russianranger.fta/files")
        val old = "$files/client-runtime-attempts/$id/tmp"
        assertEquals(118, "$old/.X11-unix/X8".toByteArray().size)
        try { ClientProbePaths.validateTemporaryPath(old); fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("socket-path-too-long", e.reason) }
        val run = ClientProbePaths.attempt(files, id)
        assertEquals(id.replace("-", ""), run.name)
        assertTrue(ClientProbePaths.validateTemporaryPath("$run/tmp") < 108)
        assertTrue(ClientProbePaths.validateTemporaryPath("${ClientProbePaths.attempt(File("/data/user/999/com.russianranger.fta/files"), id)}/tmp") < 108)
    }
    @Test fun pathBudgetUsesBytesAndBudgetsProotFallbackNotOnlyDisplaySocket() {
        val path = "/" + "a".repeat(90)
        assertTrue("$path/.X11-unix/X8".toByteArray().size < 108)
        for (bad in listOf(path, "/" + "é".repeat(45))) {
            try { ClientProbePaths.validateTemporaryPath(bad); fail() }
            catch (e: RuntimeInstallFailure) { assertEquals("socket-path-too-long", e.reason) }
        }
        try { ClientProbePaths.attempt(File("/unused"), "../" + "a".repeat(33)); fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("probe-unavailable", e.reason) }
    }
    @Test fun diagnosticParserExportsOnlyKnownVocabularyAndBoundedNumbers() {
        val result = ClientProbeEvidence.parse("FTA_ANDROID_STAGE display 1\nFTA_ANDROID_DIAGNOSTIC permission-denied\n" +
            "FTA_ANDROID_DIAGNOSTIC /private/path\nFTA_ANDROID_DISPLAY_EXIT -9\nFTA_ANDROID_DISPLAY_EVENT exited\n" +
            "FTA_ANDROID_SOCKET_STATUS 22\nprivate output\nFTA_ANDROID_DISPLAY_EXIT 99999\n")
        assertEquals(mapOf("display" to 1), result.stages)
        assertEquals(setOf("permission-denied"), result.diagnostics)
        assertEquals(-9, result.displayExit); assertEquals("exited", result.displayEvent); assertEquals(22, result.socketStatus)
        assertFalse(result.toString().contains("private"))
        try { ClientProbeEvidence.parse("x".repeat(65537)); fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("probe-failed", e.reason) }
    }
    @Test fun prefixProgressIsAllowlistedBoundedAndCannotGrantAStagePass() {
        val valid = "FTA_ANDROID_PREFIX_PROGRESS 195000 1200 1000 131072 1\n"
        val result = ClientProbeEvidence.parse(valid + "FTA_ANDROID_DIAGNOSTIC wineboot-event-timeout\n")
        assertEquals(195000L, result.prefixProgress["prefixElapsedMs"])
        assertEquals(131072L, result.prefixProgress["prefixRegistryBytes"])
        assertEquals(setOf("wineboot-event-timeout"), result.diagnostics)
        assertTrue(result.stages.isEmpty())
        for (bad in listOf("-1 0 0 0 0", "600001 0 0 0 0", "0 4097 0 0 0", "0 0 0 201326593 0",
            "0 0 0 0 2", "private path 0 0 0", "0 0 0 0", "0 0 0 0 0 extra")) {
            assertTrue(ClientProbeEvidence.parse("FTA_ANDROID_PREFIX_PROGRESS $bad\n").prefixProgress.isEmpty())
            assertEquals(result.prefixProgress, ClientProbeEvidence.parse(valid + "FTA_ANDROID_PREFIX_PROGRESS $bad\n").prefixProgress)
        }
    }
}
