package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientProbeProtocolTest {
    private class Driver : ClientProbeProtocol.Driver {
        var time = 0L; var next = 0L; var killed = 0L; var corruptTree = false; var survivor = false
        var killExit = 137; var neverReady = false; var cancelled = false
        val modes = linkedMapOf<Long, String>(); val stopped = mutableSetOf<Long>()
        override fun now() = time
        override fun pause(ms: Long) { time += ms }
        override fun active() { if (cancelled) throw RuntimeInstallFailure("cancelled") }
        override fun start(mode: String): Long { val id = ++next; modes[id] = mode; return id }
        override fun tracer(handle: Long) = 10
        override fun output(handle: Long) = if (modes[handle] == "--wine") success() else if (neverReady) "" else
            "FTA_ANDROID_OWNED 20\nFTA_ANDROID_OWNED 21\nFTA_ANDROID_OWNED 22\n"
        override fun identity(pid: Int) = ClientProbeProtocol.Identity(pid, if (corruptTree) 99 else when(pid) { 20 -> 10; 21 -> 20; else -> 21 },
            if (pid == 20) 10 else pid, 200L + pid)
        override fun ended(identity: ClientProbeProtocol.Identity) = !survivor
        override fun waitFor(handle: Long, ms: Long): Int {
            time += ms
            return if (handle == killed) killExit else if (modes[handle] != "--ownership-hold") 0 else ClientProbeProtocol.RUNNING
        }
        override fun stop(handle: Long, graceMs: Long): Int { stopped.add(handle); return 143 }
        override fun requestTracerDeath(handle: Long) { killed = handle }
    }
    @Test fun allOwnershipGatesPrecedeWineAndEveryHandleIsStopped() {
        val driver = Driver(); val result = ClientProbeProtocol.run(driver)
        assertEquals(listOf("--ownership-normal", "--ownership-hold", "--ownership-hold", "--wine"), driver.modes.values.toList())
        assertEquals(driver.modes.keys, driver.stopped); assertEquals(137, result.killedExit)
        assertEquals(3, result.stoppedTracees); assertEquals(3, result.killedTracees)
        assertEquals(ClientProbeProtocol.stages.toSet(), result.stages.keys)
    }
    @Test fun wrongTreeAndSurvivingDescendantsBlockWine() {
        for (driver in listOf(Driver().apply { corruptTree = true }, Driver().apply { survivor = true })) {
            try { ClientProbeProtocol.run(driver); fail() } catch (e: RuntimeInstallFailure) { assertEquals("ownership-failed", e.reason) }
            assertFalse("--wine" in driver.modes.values); assertEquals(driver.modes.keys, driver.stopped)
        }
    }
    @Test fun gracefulTracerExitCannotQualifyForcedKill() {
        val driver = Driver().apply { killExit = 143 }
        try { ClientProbeProtocol.run(driver); fail() } catch (e: RuntimeInstallFailure) { assertEquals("ownership-failed", e.reason) }
        assertFalse("--wine" in driver.modes.values); assertEquals(driver.modes.keys, driver.stopped)
    }
    @Test fun missingReadinessHasDeadlineAndCleanup() {
        val driver = Driver().apply { neverReady = true }
        try { ClientProbeProtocol.run(driver); fail() } catch (e: RuntimeInstallFailure) { assertEquals("ownership-failed", e.reason) }
        assertTrue(driver.time <= 6000); assertEquals(driver.modes.keys, driver.stopped)
    }
    @Test fun partialDuplicateOutOfOrderNonzeroAndMissingPassEvidenceRejected() {
        val valid = success()
        for (bad in listOf(valid.replace("FTA_ANDROID_SYNTHETIC_PASS", ""), valid + "FTA_ANDROID_STAGE prefix 0\n",
            valid.replace("prefix 0", "prefix 1"), valid.replace("FTA_ANDROID_STAGE x64-entry 0\n", ""),
            valid.replace("cpu-topology", "unexpected"))) {
            try { ClientProbeProtocol.parseStages(bad, 0); fail() } catch (e: RuntimeInstallFailure) { assertEquals("probe-failed", e.reason) }
        }
        try { ClientProbeProtocol.parseStages(valid, 137); fail() } catch (e: RuntimeInstallFailure) { assertEquals("probe-failed", e.reason) }
    }
    companion object {
        fun success() = ClientProbeProtocol.stages.joinToString("") { "FTA_ANDROID_STAGE $it 0\n" } + "FTA_ANDROID_SYNTHETIC_PASS\n"
    }
}
