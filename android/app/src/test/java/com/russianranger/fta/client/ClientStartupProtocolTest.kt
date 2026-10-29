package com.russianranger.fta.client
import org.junit.Assert.*
import org.junit.Test

class ClientStartupProtocolTest {
    private fun good() = ClientStartupProtocol.stages.joinToString("\n") { "FTA_ANDROID_STAGE $it 0" } + "\n" +
        ClientStartupProtocol.required.joinToString("\n") { "FTA_CLIENT_EVENT $it 1" } + "\nFTA_CLIENT_EVENT host-channels 16\nFTA_CLIENT_STARTUP_PASS\n"
    private fun rejects(text: String, exit: Int = 0) {
        try { ClientStartupProtocol.validate(text, exit); fail() } catch (e: RuntimeInstallFailure) { assertEquals("client-startup-failed", e.reason) }
    }
    @Test fun strictCompleteTranscriptIncludesOwnedHostAndStop() {
        assertEquals(16L, ClientStartupProtocol.validate(good(), 0)["host-channels"])
        assertEquals(1L, ClientStartupProtocol.validate(good() + "FTA_CLIENT_EVENT client-forced-stop 1\n", 0)["client-forced-stop"])
    }
    @Test fun missingRepeatedFailedAndContradictoryEventsCannotQualify() {
        for (event in ClientStartupProtocol.required) {
            rejects(good().replace("FTA_CLIENT_EVENT $event 1\n", ""))
            rejects(good() + "FTA_CLIENT_EVENT $event 1\n")
        }
        rejects(good(), 1); rejects(good() + "FTA_CLIENT_STARTUP_FAILED\n")
        rejects(good() + "FTA_CLIENT_EVENT client-early-exit 0\n")
        rejects(good().replace("host-channels 16", "host-channels 17"))
        rejects(good().replace("original-startup 0", "original-startup 1"))
    }
    @Test fun completionRequiresEveryVerificationCleanupAndTypedObservation() {
        val complete = mutableMapOf<String, Any?>("state" to "complete", "operation" to "original",
            "clientCatalogSha256" to ClientManagedView.CATALOG, "sessionExit" to 0,
            "probeStages" to ClientStartupProtocol.stages.associateWith { 0 },
            "prefixPreparationStages" to ClientStartupProtocol.prefixStages.associateWith { 0 }, "prefixPreparationExit" to 0,
            "startupEvents" to ClientStartupProtocol.validate(good(), 0))
        for (flag in ClientStartupProtocol.completionFlags) complete[flag] = true
        assertTrue(ClientStartupProtocol.qualified(complete))
        for (key in complete.keys) assertFalse(key, ClientStartupProtocol.qualified(complete - key))
        for (flag in ClientStartupProtocol.completionFlags) {
            assertFalse(flag, ClientStartupProtocol.qualified(complete + (flag to false)))
            assertFalse(flag, ClientStartupProtocol.qualified(complete + (flag to "true")))
        }
        for ((key, value) in listOf("sessionExit" to "0", "prefixPreparationExit" to "0", "state" to "failed", "clientCatalogSha256" to "0".repeat(64),
            "prefixPreparationStages" to ClientStartupProtocol.prefixStages.associateWith { "0" },
            "probeStages" to ClientStartupProtocol.stages.associateWith { "0" },
            "startupEvents" to (ClientStartupProtocol.validate(good(), 0) + ("client-early-exit" to 0L))))
            assertFalse(key, ClientStartupProtocol.qualified(complete + (key to value)))
    }
    @Test fun prefixRequiresExactStagesAndCleanupWithoutClientExecution() {
        val text = ClientStartupProtocol.prefixStages.joinToString("\n") { "FTA_ANDROID_STAGE $it 0" } + "\nFTA_CLIENT_PREFIX_PASS\n"
        ClientStartupProtocol.validatePrefix(text, 0)
        for (bad in listOf(text + "FTA_CLIENT_PREFIX_PASS\n", text + "FTA_CLIENT_STARTUP_FAILED\n",
            text + "FTA_CLIENT_EVENT client-started 1\n", text + "FTA_CLIENT_STARTUP_PASS\n",
            text + "FTA_CLIENT_PREFIX_FAILED\n", text.replace("wine-stop 0", "wine-stop 1"),
            text.replace("FTA_ANDROID_STAGE prefix 0\n", ""))) {
            try { ClientStartupProtocol.validatePrefix(bad, 0); fail() }
            catch (e: RuntimeInstallFailure) { assertEquals("client-startup-failed", e.reason) }
        }
        try { ClientStartupProtocol.validatePrefix(text, 1); fail() }
        catch (e: RuntimeInstallFailure) { assertEquals("client-startup-failed", e.reason) }
    }
    @Test fun cacheTelemetryIsBoundedAndUsesTheLastObservation() {
        val values = ClientStartupProtocol.cacheValues("FTA_PREFIX_CACHE 2 1 1 1024\nFTA_PREFIX_CACHE 5 3 2 2048\n")
        assertEquals(mapOf("prefixCacheSyncedFiles" to 5L, "prefixCacheAcceptedFiles" to 3L,
            "prefixCacheErrors" to 2L, "prefixCacheBytes" to 2048L), values)
        for (bad in listOf("FTA_PREFIX_CACHE 1 2 0 100", "FTA_PREFIX_CACHE 1 1 -1 100",
            "FTA_PREFIX_CACHE 1 1 0 ${ClientPlan.MAX_BYTES + 1}", "FTA_PREFIX_CACHE 1 1 0 private", "FTA_PREFIX_CACHE 1 1 0"))
            assertTrue(ClientStartupProtocol.cacheValues(bad).isEmpty())
    }
}
