package com.russianranger.fta.client

/** Native guests cannot be paused by sleeping their Java monitor. Sample without blocking;
 * stop immediately for measured pressure and severe callbacks. A running-critical
 * callback gets only bounded confirmation with ample memory AND measured quiet PSI. */
class ClientProbeMemory {
    data class Signal(val level: Int, val at: Long, val until: Long, val criticalUntil: Long,
        val criticalAt: Long = 0, val severeUntil: Long = 0) {
        fun active(now: Long) = now < until
        fun critical(now: Long) = now < criticalUntil
    }
    @Volatile private var signal = Signal(0, 0, 0, 0)
    private var advisorySince: Long? = null
    private var criticalSince: Long? = null
    var criticalGraceEpisodes = 0L; private set
    var criticalGraceMaxMs = 0L; private set
    var advisoryEpisodes = 0L; private set
    var advisoryElapsedMs = 0L; private set

    /** 20..39 is UI lifecycle, not pressure. Background/LRU callbacks remain advisory. */
    @Synchronized fun trim(level: Int, now: Long) {
        if (level !in 5..19 && level < 40) return
        val critical = level in 15..19 || level >= 80
        signal = Signal(level.coerceAtMost(100), now, now + SIGNAL_MS,
            if (critical) now + SIGNAL_MS else signal.criticalUntil,
            if (critical && !signal.critical(now)) now else signal.criticalAt,
            if (level >= 80) now + SIGNAL_MS else signal.severeUntil)
    }
    @Synchronized fun low(now: Long) { signal = Signal(100, now, now + SIGNAL_MS, now + SIGNAL_MS, now, now + SIGNAL_MS) }
    fun snapshot() = signal

    data class Decision(val source: String, val stop: Boolean)
    fun evaluate(memory: ClientIoGuard.Memory, now: Long, observed: Signal = snapshot()): Decision {
        val immediate = when {
            memory.low -> "system-low"
            memory.available < memory.floor -> "available-floor"
            (memory.someMilliPercent ?: 0) >= 10000 -> "psi-some"
            (memory.fullMilliPercent ?: 0) >= 1000 -> "psi-full"
            now < observed.severeUntil -> "critical-trim"
            else -> null
        }
        if (immediate != null) return Decision(immediate, true)
        if (observed.critical(now)) {
            // Missing PSI, limited headroom or elevated stalls cannot justify grace.
            // Do not sleep: the native guest would keep allocating while unmonitored.
            val quiet = memory.available >= maxOf(memory.floor, memory.total / 3) &&
                memory.someMilliPercent != null && memory.someMilliPercent < 2000 &&
                memory.fullMilliPercent != null && memory.fullMilliPercent < 200
            if (!quiet) return Decision("critical-trim", true)
            if (criticalSince == null) { criticalSince = observed.criticalAt; criticalGraceEpisodes++ }
            val elapsed = maxOf(0, now - criticalSince!!)
            criticalGraceMaxMs = maxOf(criticalGraceMaxMs, elapsed)
            return Decision(if (elapsed >= CRITICAL_MS) "sustained-critical-trim" else "critical-trim-grace", elapsed >= CRITICAL_MS)
        }
        criticalSince = null
        if (!observed.active(now)) {
            advisorySince = null; advisoryElapsedMs = 0
            return Decision("none", false)
        }
        if (advisorySince == null) { advisorySince = now; advisoryEpisodes++ }
        advisoryElapsedMs = now - advisorySince!!
        return if (advisoryElapsedMs >= ADVISORY_MS) Decision("sustained-trim", true)
            else Decision("trim-advisory", false)
    }
    companion object {
        const val SIGNAL_MS = 10_000L
        const val CRITICAL_MS = 10_000L
        const val ADVISORY_MS = 30_000L
        val sources = setOf("none", "system-low", "available-floor", "critical-trim", "trim-advisory",
            "sustained-trim", "critical-trim-grace", "sustained-critical-trim", "psi-some", "psi-full", "trim")
    }
}
