package com.russianranger.fta.client

/** Native guests cannot be paused by sleeping their Java monitor. Sample without blocking;
 * stop immediately for low memory, severe stalls and critical callbacks with pressure.
 * Moderate PSI gets short confirmation only with fresh counters and ample headroom. */
class ClientProbeMemory {
    data class Signal(val level: Int, val at: Long, val until: Long, val criticalUntil: Long,
        val criticalAt: Long = 0, val severeUntil: Long = 0) {
        fun active(now: Long) = now < until
        fun critical(now: Long) = now < criticalUntil
    }
    @Volatile private var signal = Signal(0, 0, 0, 0)
    private var advisorySince: Long? = null
    private var criticalSince: Long? = null
    private val psi = ClientProbePsi()
    private var psiSince: Long? = null
    private var psiSource = "psi-full"
    val psiInterval get() = psi.interval
    var psiGraceEpisodes = 0L; private set
    var psiGraceMaxMs = 0L; private set
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
    fun evaluate(memory: ClientIoGuard.Memory, now: Long, observed: Signal = snapshot(),
        totals: ClientProbePsi.Totals? = null): Decision {
        psi.observe(totals, now)
        val interval = psi.interval
        val averageSource = when {
            (memory.someMilliPercent ?: 0) >= 10000 -> "psi-some"
            (memory.fullMilliPercent ?: 0) >= 1000 -> "psi-full"
            else -> null
        }
        val recentSource = when {
            (interval?.someMilliPercent ?: 0) >= 10000 -> "psi-some"
            (interval?.fullMilliPercent ?: 0) >= 1000 -> "psi-full"
            else -> null
        }
        val immediate = when {
            memory.low -> "system-low"
            memory.available < memory.floor -> "available-floor"
            now < observed.severeUntil -> "critical-trim"
            (memory.someMilliPercent ?: 0) >= 20000 || (interval?.someMilliPercent ?: 0) >= 20000 -> "psi-some"
            (memory.fullMilliPercent ?: 0) >= 5000 || (interval?.fullMilliPercent ?: 0) >= 5000 -> "psi-full"
            else -> null
        }
        if (immediate != null) return Decision(immediate, true)
        val pressure = recentSource ?: averageSource
        if (pressure != null || psiSince != null) {
            val measurable = memory.someMilliPercent != null && memory.fullMilliPercent != null && totals != null
            val headroom = memory.available >= maxOf(memory.floor, memory.total / 3)
            if (!measurable || !headroom || observed.critical(now) || (interval == null && !psi.first))
                return Decision(pressure ?: psiSource, true)
            // A quiet fresh interval can distinguish a decaying average from ongoing stalls.
            // Hysteresis prevents threshold oscillations from resetting a live episode.
            if (interval != null && interval.someMilliPercent < 2000 && interval.fullMilliPercent < 200) {
                psiSince = null
            } else {
                if (psiSince == null) { psiSince = now; psiSource = pressure ?: psiSource; psiGraceEpisodes++ }
                val elapsed = maxOf(0, now - psiSince!!)
                psiGraceMaxMs = maxOf(psiGraceMaxMs, elapsed)
                if (elapsed >= PSI_CONFIRM_MS) return Decision(pressure ?: psiSource, true)
                // Continue evaluating callback limits below; PSI cannot hide sustained trim.
            }
        }
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
            return Decision(if (psiSince != null) "psi-confirming" else if (averageSource != null) "psi-recovering" else "none", false)
        }
        if (advisorySince == null) { advisorySince = now; advisoryEpisodes++ }
        advisoryElapsedMs = now - advisorySince!!
        return if (advisoryElapsedMs >= ADVISORY_MS) Decision("sustained-trim", true)
            else Decision(if (psiSince != null) "psi-confirming" else if (averageSource != null) "psi-recovering" else "trim-advisory", false)
    }
    companion object {
        const val SIGNAL_MS = 10_000L
        const val CRITICAL_MS = 10_000L
        const val ADVISORY_MS = 30_000L
        const val PSI_CONFIRM_MS = 3_000L
        val sources = setOf("none", "system-low", "available-floor", "critical-trim", "trim-advisory",
            "sustained-trim", "critical-trim-grace", "sustained-critical-trim", "psi-some", "psi-full", "trim",
            "psi-confirming", "psi-recovering")
    }
}
