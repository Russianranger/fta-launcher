package com.russianranger.fta.client

/** Native guests cannot be paused by sleeping their Java monitor. Sample without blocking;
 * stop immediately for measured pressure/critical callbacks, and bound advisory-only grace. */
class ClientProbeMemory {
    data class Signal(val level: Int, val at: Long, val until: Long, val criticalUntil: Long) {
        fun active(now: Long) = now < until
        fun critical(now: Long) = now < criticalUntil
    }
    @Volatile private var signal = Signal(0, 0, 0, 0)
    private var advisorySince: Long? = null
    var advisoryEpisodes = 0L; private set
    var advisoryElapsedMs = 0L; private set

    /** 20..39 is UI lifecycle, not pressure. Background/LRU callbacks remain advisory. */
    @Synchronized fun trim(level: Int, now: Long) {
        if (level !in 5..19 && level < 40) return
        val critical = level in 15..19 || level >= 80
        signal = Signal(level.coerceAtMost(100), now, now + SIGNAL_MS,
            if (critical) now + SIGNAL_MS else signal.criticalUntil)
    }
    @Synchronized fun low(now: Long) { signal = Signal(100, now, now + SIGNAL_MS, now + SIGNAL_MS) }
    fun snapshot() = signal

    data class Decision(val source: String, val stop: Boolean)
    fun evaluate(memory: ClientIoGuard.Memory, now: Long, observed: Signal = snapshot()): Decision {
        val immediate = when {
            memory.low -> "system-low"
            memory.available < memory.floor -> "available-floor"
            observed.critical(now) -> "critical-trim"
            else -> null
        }
        if (immediate != null) return Decision(immediate, true)
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
        const val ADVISORY_MS = 30_000L
        val sources = setOf("none", "system-low", "available-floor", "critical-trim", "trim-advisory",
            "sustained-trim", "psi-some", "psi-full", "trim")
    }
}
