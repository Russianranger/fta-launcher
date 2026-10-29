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
    @Test fun code48CallbackNeedsNewQuietPsiEvidenceBeforeAnyGrace() {
        val reported = ClientIoGuard.Memory(8570064896L, 15996096512L, 226492416L, false)
        val guard = ClientProbeMemory(); guard.trim(15, 1000)
        // The actual code-48 native sample omitted PSI: it cannot prove safety.
        assertEquals(ClientProbeMemory.Decision("critical-trim", true), guard.evaluate(reported, 1585))
        val measuredQuiet = reported.copy(someMilliPercent = 0, fullMilliPercent = 0)
        for (now in 1585L..10585L step 1000)
            assertEquals(ClientProbeMemory.Decision("critical-trim-grace", false), guard.evaluate(measuredQuiet, now))
        assertEquals(ClientProbeMemory.Decision("none", false), guard.evaluate(measuredQuiet, 11000))
        assertEquals(1L, guard.criticalGraceEpisodes); assertEquals(9585L, guard.criticalGraceMaxMs)
    }
    @Test fun criticalGraceRefusesMissingPsiElevatedStallsAndInsufficientHeadroom() {
        val quiet = healthy.copy(someMilliPercent = 0, fullMilliPercent = 0)
        for (sample in listOf(quiet.copy(someMilliPercent = null), quiet.copy(fullMilliPercent = null),
            quiet.copy(someMilliPercent = 2000), quiet.copy(fullMilliPercent = 200),
            quiet.copy(available = quiet.total / 3 - 1), quiet.copy(threshold = 5L shl 30))) {
            val guard = ClientProbeMemory(); guard.trim(15, 1000)
            assertTrue(guard.evaluate(sample, 1000).stop); assertEquals(0L, guard.criticalGraceEpisodes)
        }
        val guard = ClientProbeMemory(); guard.trim(19, 1000)
        assertFalse(guard.evaluate(quiet.copy(available = quiet.total / 3, someMilliPercent = 1999, fullMilliPercent = 199), 1000).stop)
    }
    @Test fun measuredPressureStopsDuringGraceAndWithoutAnyCallback() {
        val quiet = healthy.copy(someMilliPercent = 0, fullMilliPercent = 0)
        val bad = listOf("system-low" to quiet.copy(low = true),
            "available-floor" to quiet.copy(available = quiet.floor - 1),
            "psi-some" to quiet.copy(someMilliPercent = 10000),
            "psi-full" to quiet.copy(fullMilliPercent = 1000))
        for (duringGrace in listOf(false, true)) for ((source, sample) in bad) {
            val guard = ClientProbeMemory()
            if (duringGrace) { guard.trim(15, 1000); assertFalse(guard.evaluate(quiet, 1000).stop) }
            assertEquals(ClientProbeMemory.Decision(source, true), guard.evaluate(sample, 2000))
        }
    }
    @Test fun repeatedCriticalCallbacksCannotExtendTheTenSecondWindow() {
        val guard = ClientProbeMemory(); val quiet = healthy.copy(someMilliPercent = 0, fullMilliPercent = 0)
        for (now in 0L..10000L step 1000) {
            guard.trim(15, now)
            assertEquals(now == 10000L, guard.evaluate(quiet, now).stop)
        }
        assertEquals("sustained-critical-trim", guard.evaluate(quiet, 10001).source)
        assertEquals(1L, guard.criticalGraceEpisodes); assertEquals(10001L, guard.criticalGraceMaxMs)
    }
    @Test fun advisoryCannotDowngradeCriticalIdentityOrResetItsWindow() {
        val guard = ClientProbeMemory(); val quiet = healthy.copy(someMilliPercent = 0, fullMilliPercent = 0)
        guard.trim(15, 0); val original = guard.snapshot(); guard.trim(15, 250); guard.trim(5, 500)
        assertEquals(0L, guard.snapshot().criticalAt)
        assertEquals("critical-trim-grace", guard.evaluate(quiet, 1000).source)
        guard.trim(15, 9000); guard.trim(10, 9500)
        assertEquals(ClientProbeMemory.Decision("sustained-critical-trim", true), guard.evaluate(quiet, 10000))
        assertEquals(15, original.level); assertEquals(10000L, original.criticalUntil)
    }
    @Test fun lowMemoryAndSevereCallbacksRemainImmediateAfterMilderCallbacks() {
        val quiet = healthy.copy(someMilliPercent = 0, fullMilliPercent = 0)
        for (level in listOf(80, 100, 101)) {
            val guard = ClientProbeMemory(); guard.trim(level, 1000); guard.trim(15, 2000); guard.trim(5, 3000)
            assertEquals(ClientProbeMemory.Decision("critical-trim", true), guard.evaluate(quiet, 3000))
            assertEquals(0L, guard.criticalGraceEpisodes)
        }
        val guard = ClientProbeMemory(); guard.trim(15, 1000); assertFalse(guard.evaluate(quiet, 1000).stop)
        guard.low(2000); guard.trim(5, 3000)
        assertEquals(ClientProbeMemory.Decision("critical-trim", true), guard.evaluate(quiet, 3000))
    }
    @Test fun expiredCriticalSignalStartsANewBoundedEpisode() {
        val guard = ClientProbeMemory(); val quiet = healthy.copy(someMilliPercent = 0, fullMilliPercent = 0)
        guard.trim(15, 0); assertFalse(guard.evaluate(quiet, 9000).stop)
        assertEquals("none", guard.evaluate(quiet, 10000).source)
        guard.trim(15, 20000); assertFalse(guard.evaluate(quiet, 21000).stop)
        assertEquals(2L, guard.criticalGraceEpisodes); assertEquals(9000L, guard.criticalGraceMaxMs)
    }
}
