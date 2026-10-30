package com.russianranger.fta.client

import org.junit.Assert.*
import org.junit.Test

class ClientProbePsiTest {
    @Test fun totalsAreStrictBoundedAndDoNotNeedOrExposeRawMessages() {
        assertEquals(ClientProbePsi.Totals(70, 2), ClientProbePsi.totals(
            "some avg10=1.25 avg60=0.0 total=70\nfull avg10=0.02 avg60=0.0 total=2\n"))
        for (text in listOf("some total=-1\nfull total=0", "some total=1\nfull total=2",
            "some total=1 total=2\nfull total=0", "some total=1\nsome total=1\nfull total=0",
            "some total=9223372036854775808\nfull total=0", "some total=+1\nfull total=0",
            "some total=1\nfull avg10=0", "x".repeat(4097))) assertNull(ClientProbePsi.totals(text))
    }
    @Test fun recentRatesUseElapsedTimeAndMicrosecondCounters() {
        val psi = ClientProbePsi()
        psi.observe(ClientProbePsi.Totals(1000000, 500000), 1000)
        assertTrue(psi.first); assertNull(psi.interval)
        psi.observe(ClientProbePsi.Totals(1022800, 520700), 2000)
        assertEquals(ClientProbePsi.Interval(1000, 2280, 2070), psi.interval)
        psi.observe(ClientProbePsi.Totals(1022800, 520700), 3500)
        assertEquals(ClientProbePsi.Interval(1500, 0, 0), psi.interval)
        assertFalse(psi.first)
    }
    @Test fun counterResetMissingReadStaleClockAndImpossibleDeltaAreNotQuietEvidence() {
        for ((at, totals) in listOf(999L to ClientProbePsi.Totals(100, 10),
            1499L to ClientProbePsi.Totals(100, 10), 3501L to ClientProbePsi.Totals(100, 10),
            2000L to ClientProbePsi.Totals(99, 10), 2000L to ClientProbePsi.Totals(100, 9),
            2000L to ClientProbePsi.Totals(1000101, 10),
            2000L to ClientProbePsi.Totals(101, 12), 2000L to null)) {
            val psi = ClientProbePsi(); psi.observe(ClientProbePsi.Totals(100, 10), 1000)
            psi.observe(totals, at); assertNull(psi.interval); assertFalse(psi.first)
        }
    }
    @Test fun largeCumulativeCountersCannotOverflowRateArithmetic() {
        val psi = ClientProbePsi()
        psi.observe(ClientProbePsi.Totals(Long.MAX_VALUE - 2500000, Long.MAX_VALUE - 2500000), 0)
        psi.observe(ClientProbePsi.Totals(Long.MAX_VALUE, Long.MAX_VALUE), 2500)
        assertEquals(ClientProbePsi.Interval(2500, 100000, 100000), psi.interval)
    }
}
