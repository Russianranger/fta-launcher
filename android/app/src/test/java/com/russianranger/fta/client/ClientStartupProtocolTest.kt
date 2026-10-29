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
            "startupEvents" to ClientStartupProtocol.validate(good(), 0))
        for (flag in ClientStartupProtocol.completionFlags) complete[flag] = true
        assertTrue(ClientStartupProtocol.qualified(complete))
        for (key in complete.keys) assertFalse(key, ClientStartupProtocol.qualified(complete - key))
        for (flag in ClientStartupProtocol.completionFlags) {
            assertFalse(flag, ClientStartupProtocol.qualified(complete + (flag to false)))
            assertFalse(flag, ClientStartupProtocol.qualified(complete + (flag to "true")))
        }
        for ((key, value) in listOf("sessionExit" to "0", "state" to "failed", "clientCatalogSha256" to "0".repeat(64),
            "probeStages" to ClientStartupProtocol.stages.associateWith { "0" },
            "startupEvents" to (ClientStartupProtocol.validate(good(), 0) + ("client-early-exit" to 0L))))
            assertFalse(key, ClientStartupProtocol.qualified(complete + (key to value)))
    }
}
