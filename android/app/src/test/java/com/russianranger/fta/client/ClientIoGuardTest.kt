package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientIoGuardTest {
    private val healthy = ClientIoGuard.Memory(8L shl 30, 16L shl 30, 256L shl 20, false)
    private class Clock { var time = 0L; val sleeps = mutableListOf<Long>(); fun sleep(ms: Long) { sleeps += ms; time += ms } }
    private fun fails(reason: String, action: () -> Unit) {
        try { action(); fail("Expected $reason") } catch (error: ClientImportFailure) { assertEquals(reason, error.reason) }
    }
    @Test fun rateIncludesEveryReadWriteAndReadbackWithoutIdleBurstCredit() {
        val clock = Clock(); val guard = ClientIoGuard({ clock.time }, clock::sleep, { healthy }, {})
        repeat(192) { guard.beforeIo(256 * 1024) }
        assertEquals(48L shl 20, guard.ioBytes); assertTrue(clock.time >= 1000)
        clock.time += 60000; val before = clock.time
        guard.beforeIo(256 * 1024)
        assertTrue(clock.time - before >= 5); assertTrue(clock.sleeps.all { it in 1..100 })
    }
    @Test fun pressureStopsIoUntilFiveSecondsOfSustainedRecovery() {
        val clock = Clock(); var paused = false
        val guard = ClientIoGuard({ clock.time }, clock::sleep,
            { if (clock.time < 3000) healthy.copy(low = true) else healthy }, {}, { _, p -> paused = p })
        guard.beforeIo(1)
        assertTrue(clock.time >= 8000); assertEquals(1L, guard.pressurePauses); assertFalse(paused)
        assertEquals(1L, guard.ioBytes)
    }
    @Test fun persistentPressureStopsSafelyWithoutPayloadIo() {
        val clock = Clock(); val guard = ClientIoGuard({ clock.time }, clock::sleep, { healthy.copy(low = true) }, {})
        fails("memory-pressure") { guard.beforeIo(256 * 1024) }
        assertEquals(120000L, clock.time); assertEquals(0L, guard.ioBytes)
    }
    @Test fun cancellationRemainsResponsiveDuringBackoff() {
        val clock = Clock(); val guard = ClientIoGuard({ clock.time }, clock::sleep, { healthy.copy(trim = true) }, {
            if (clock.time >= 750) throw ClientImportFailure("cancelled")
        })
        fails("cancelled") { guard.beforeIo(1) }; assertEquals(750L, clock.time)
    }
    @Test fun missingMemoryObservationFailsClosed() {
        val clock = Clock(); val guard = ClientIoGuard({ clock.time }, clock::sleep, { null }, {})
        fails("memory-observation-unavailable") { guard.beforeIo(1) }; assertEquals(0L, guard.ioBytes)
    }
    @Test fun lostObservationDuringPressureCannotResume() {
        val clock = Clock(); val guard = ClientIoGuard({ clock.time }, clock::sleep,
            { if (clock.time == 0L) healthy.copy(low = true) else null }, {})
        fails("memory-observation-unavailable") { guard.check() }
    }
    @Test fun recoveryHysteresisDoesNotOscillateNearLowWatermark() {
        assertTrue(healthy.copy(available = 3L shl 30).pressured())
        assertFalse(healthy.copy(available = 7L * (1L shl 29)).pressured())
        assertFalse(healthy.copy(available = 7L * (1L shl 29)).recovered())
        assertTrue(healthy.copy(available = 4L shl 30).recovered())
    }
    @Test fun psiAndTrimCanPauseDespiteHighAvailableMemory() {
        assertTrue(healthy.copy(someMilliPercent = 10000).pressured())
        assertTrue(healthy.copy(fullMilliPercent = 1000).pressured())
        assertTrue(healthy.copy(trim = true).pressured())
        assertFalse(healthy.copy(someMilliPercent = 2000).recovered())
        assertTrue(healthy.recovered()) // optional PSI unavailable is explicit, not invented zero data
    }
    @Test fun pressureReturningDuringRecoveryRestartsHealthyInterval() {
        val clock = Clock(); val guard = ClientIoGuard({ clock.time }, clock::sleep,
            { healthy.copy(low = clock.time < 1000 || clock.time in 4000..4999) }, {})
        guard.check(); assertEquals(10000L, clock.time)
    }
    @Test fun psiParsingIsBoundedAndRejectsMalformedOrOutOfRangeData() {
        assertEquals(1250L to 20L, ClientIoGuard.psi("some avg10=1.25 avg60=0.0 total=77\nfull avg10=0.02 avg60=0.0 total=2\n"))
        for (text in listOf("", "some avg10=NaN\nfull avg10=0", "some avg10=101\nfull avg10=0",
            "some avg10=-1\nfull avg10=0", "some avg10=0\nsome avg10=0\nfull avg10=0", "x".repeat(4097))) assertNull(ClientIoGuard.psi(text))
    }
    @Test fun samplesAreBoundedToOncePerSecondDuringNormalIo() {
        val clock = Clock(); var samples = 0
        val guard = ClientIoGuard({ clock.time }, clock::sleep, { samples++; healthy }, {})
        repeat(100) { guard.beforeIo(1000) }
        assertEquals(1, samples)
    }
    @Test fun lowMemoryCallbackHasPriorityOverHealthyPsi() {
        assertTrue(healthy.copy(low = true, someMilliPercent = 0, fullMilliPercent = 0).pressured())
        assertFalse(healthy.copy(trim = true).recovered())
    }
    @Test fun fasterRateStillStopsBeforePayloadWhenPressureAppears() {
        val clock = Clock(); var pressure = false
        val guard = ClientIoGuard({ clock.time }, clock::sleep, { healthy.copy(low = pressure) }, {})
        repeat(192) { guard.beforeIo(256 * 1024) }
        val before = guard.ioBytes; pressure = true; clock.time += 1000
        fails("memory-pressure") { guard.beforeIo(256 * 1024) }
        assertEquals(before, guard.ioBytes); assertEquals(1L, guard.pressurePauses)
    }
}
