package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientProbeMemoryTest {
    private val healthy = ClientIoGuard.Memory(8L shl 30, 16L shl 30, 256L shl 20, false)
    @Test fun briefAdvisoryWithHealthyMemoryDoesNotDestroyAnInitializingPrefix() {
        for (level in listOf(5, 10, 14, 40, 60, 79)) {
            val guard = ClientProbeMemory(); guard.trim(level, 1000)
            for (now in 1000L..10000L step 1000) {
                assertEquals(ClientProbeMemory.Decision("trim-advisory", false), guard.evaluate(healthy, now))
            }
            assertEquals(ClientProbeMemory.Decision("none", false), guard.evaluate(healthy, 11000))
            assertEquals(1L, guard.advisoryEpisodes)
        }
    }
    @Test fun lifecycleAndUnknownLowLevelsDoNotCreatePressure() {
        val guard = ClientProbeMemory()
        for (level in listOf(-1, 0, 4, 20, 25, 39)) {
            guard.trim(level, 1000)
            assertEquals(ClientProbeMemory.Decision("none", false), guard.evaluate(healthy, 1000))
        }
    }
    @Test fun realLowMemoryAndAvailableFloorAlwaysStopImmediately() {
        val guard = ClientProbeMemory(); guard.trim(5, 1000)
        assertEquals(ClientProbeMemory.Decision("system-low", true), guard.evaluate(healthy.copy(low = true), 1000))
        assertEquals(ClientProbeMemory.Decision("available-floor", true), guard.evaluate(healthy.copy(available = 3L shl 30), 1000))
        assertEquals(ClientProbeMemory.Decision("available-floor", true), guard.evaluate(healthy.copy(threshold = 5L shl 30), 1000))
    }
    @Test fun criticalCallbacksCannotBeDowngradedByLaterAdvisories() {
        for (level in listOf(15, 19, 80, 100)) {
            val guard = ClientProbeMemory(); guard.trim(level, 1000); guard.trim(5, 2000)
            assertEquals(ClientProbeMemory.Decision("critical-trim", true), guard.evaluate(healthy, 2000))
        }
        val guard = ClientProbeMemory(); guard.low(1000)
        assertTrue(guard.evaluate(healthy, 1000).stop)
    }
    @Test fun repeatedAdvisoriesHaveThirtySecondCeilingWithoutBlockingTheMonitor() {
        val guard = ClientProbeMemory()
        for (now in 0L..30000L step 1000) {
            guard.trim(10, now)
            val result = guard.evaluate(healthy, now)
            assertEquals(now == 30000L, result.stop)
        }
        assertEquals(30000L, guard.advisoryElapsedMs); assertEquals(1L, guard.advisoryEpisodes)
        assertEquals("sustained-trim", guard.evaluate(healthy, 30001).source)
    }
    @Test fun expiredAdvisoryStartsANewEpisodeAndSnapshotsStayImmutable() {
        val guard = ClientProbeMemory(); guard.trim(5, 0); val old = guard.snapshot()
        guard.evaluate(healthy, 0); guard.evaluate(healthy, 10000)
        guard.trim(10, 20000); assertFalse(guard.evaluate(healthy, 20000).stop)
        assertEquals(2L, guard.advisoryEpisodes); assertEquals(0L, guard.advisoryElapsedMs)
        assertEquals(5, old.level); assertEquals(0L, old.at)
    }
}
