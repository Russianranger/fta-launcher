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

    private val code54 = ClientIoGuard.Memory(8418881536L, 15996096512L, 226492416L, false, 2280, 2070)
    private fun totals(full: Long = 0, some: Long = full) = ClientProbePsi.Totals(some, full)

    @Test fun code54AverageAloneCannotProveThatPressureHasRecovered() {
        val guard = ClientProbeMemory(); guard.trim(5, 0)
        assertEquals(ClientProbeMemory.Decision("psi-full", true), guard.evaluate(code54, 3047))
        assertEquals(0L, guard.psiGraceEpisodes)
    }
    @Test fun freshQuietCountersLetCode54AverageDecayWithoutDestroyingPrefix() {
        val guard = ClientProbeMemory(); guard.trim(5, 0)
        assertEquals(ClientProbeMemory.Decision("psi-confirming", false), guard.evaluate(code54, 3047, totals = totals()))
        for (now in 4047L..16047L step 1000)
            assertEquals(ClientProbeMemory.Decision("psi-recovering", false), guard.evaluate(code54, now, totals = totals()))
        assertEquals(1L, guard.psiGraceEpisodes)
        assertEquals(0L, guard.psiGraceMaxMs)
        assertEquals(ClientProbePsi.Interval(1000, 0, 0), guard.psiInterval)
    }
    @Test fun ongoingModerateStallsStopAfterThreeSecondsAndCallbackCannotExtendIt() {
        val guard = ClientProbeMemory()
        for (i in 0L..3L) {
            guard.trim(5, i * 1000)
            val decision = guard.evaluate(code54, i * 1000, totals = totals(i * 20700, i * 22800))
            assertEquals(i == 3L, decision.stop)
        }
        assertEquals(1L, guard.psiGraceEpisodes); assertEquals(3000L, guard.psiGraceMaxMs)
    }
    @Test fun newStallsAreDetectedBeforeTheTenSecondAverageRises() {
        val quiet = healthy.copy(someMilliPercent = 0, fullMilliPercent = 0)
        val guard = ClientProbeMemory()
        assertFalse(guard.evaluate(quiet, 0, totals = totals()).stop)
        for (i in 1L..4L) assertEquals(i == 4L,
            guard.evaluate(quiet, i * 1000, totals = totals(i * 10000)).stop)
        assertEquals(3000L, guard.psiGraceMaxMs)
    }
    @Test fun severeRecentOrAverageStallsAndLowMemoryRemainImmediate() {
        for (sample in listOf(code54.copy(low = true), code54.copy(available = code54.floor - 1),
            code54.copy(fullMilliPercent = 5000), code54.copy(someMilliPercent = 20000))) {
            val guard = ClientProbeMemory()
            assertTrue(guard.evaluate(sample, 0, totals = totals()).stop)
            assertEquals(0L, guard.psiGraceEpisodes)
        }
        for (counter in listOf(totals(50000), totals(0, 200000))) {
            val guard = ClientProbeMemory(); guard.evaluate(code54, 0, totals = totals())
            assertTrue(guard.evaluate(code54, 1000, totals = counter).stop)
        }
    }
    @Test fun graceCannotHideCriticalCallbacksMissingTelemetryOrInsufficientHeadroom() {
        for (level in listOf(15, 19, 80, 100)) {
            val guard = ClientProbeMemory(); guard.trim(level, 0)
            assertTrue(guard.evaluate(code54, 0, totals = totals()).stop)
        }
        for (sample in listOf(code54.copy(available = code54.total / 3 - 1),
            code54.copy(someMilliPercent = null), code54.copy(fullMilliPercent = null))) {
            val guard = ClientProbeMemory(); guard.evaluate(code54, 0, totals = totals())
            assertTrue(guard.evaluate(sample, 1000, totals = totals()).stop)
        }
        val missing = ClientProbeMemory(); missing.evaluate(code54, 0, totals = totals())
        assertTrue(missing.evaluate(code54, 1000).stop)
        val stale = ClientProbeMemory(); stale.evaluate(code54, 0, totals = totals())
        assertTrue(stale.evaluate(code54, 2501, totals = totals()).stop)
    }
    @Test fun onlyQuietRecoveryResetsConfirmationAndSustainedTrimStillStops() {
        val guard = ClientProbeMemory(); guard.evaluate(code54, 0, totals = totals())
        // Below the 1% trigger, above the 0.2% recovery threshold: do not restart grace.
        for (i in 1L..3L) assertEquals(i == 3L,
            guard.evaluate(code54, i * 1000, totals = totals(i * 2000)).stop)
        val recovering = ClientProbeMemory(); recovering.evaluate(code54, 0, totals = totals())
        assertFalse(recovering.evaluate(code54, 1000, totals = totals(1999)).stop)
        assertFalse(recovering.evaluate(code54, 2000, totals = totals(21999)).stop)
        assertEquals(2L, recovering.psiGraceEpisodes)
        val trim = ClientProbeMemory()
        for (i in 0L..30L) {
            trim.trim(5, i * 1000)
            val result = trim.evaluate(code54, i * 1000, totals = totals())
            assertEquals(i == 30L, result.stop)
            if (i == 30L) assertEquals("sustained-trim", result.source)
        }
    }
}
