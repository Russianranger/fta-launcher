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
}
