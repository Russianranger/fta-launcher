package com.russianranger.fta.client

/** Single-worker I/O governor. Counts reads, writes AND readbacks; no accumulated idle credit. */
class ClientIoGuard(
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val sample: () -> Memory?,
    private val active: () -> Unit,
    private val observe: (Memory?, Boolean) -> Unit = { _, _ -> }
) {
    data class Memory(val available: Long, val total: Long, val threshold: Long, val low: Boolean,
        val someMilliPercent: Long? = null, val fullMilliPercent: Long? = null,
        val trim: Boolean = false) {
        init {
            require(total > 0 && available in 0..total && threshold in 0..total)
            require(someMilliPercent == null || someMilliPercent in 0..100000)
            require(fullMilliPercent == null || fullMilliPercent in 0..100000)
        }
        val floor get() = maxOf(2L * 1024 * 1024 * 1024, total / 5, threshold * 2)
        fun pressured() = low || trim || available < floor ||
            (someMilliPercent ?: 0) >= 10000 || (fullMilliPercent ?: 0) >= 1000
        fun recovered() = !low && !trim && available >= maxOf(floor, total / 4) &&
            (someMilliPercent ?: 0) < 2000 && (fullMilliPercent ?: 0) < 200
    }
    var ioBytes = 0L; private set
    var throttledMs = 0L; private set
    var pressureWaitMs = 0L; private set
    var pressurePauses = 0L; private set
    var latest: Memory? = null; private set
    private var due = now()
    // Exact fractional milliseconds: rounding each 256 KiB request would halve
    // a 240 MiB/s ceiling. Carry the remainder across operations instead.
    private var dueRemainder = 0L
    private var nextSample = Long.MIN_VALUE

    /** Called before each bounded payload read or write, including resumed/retained reads. */
    fun beforeIo(bytes: Int) {
        require(bytes in 1..(256 * 1024))
        check()
        val duration = bytes * 1000L + dueRemainder
        due += duration / IO_BYTES_PER_SECOND
        dueRemainder = duration % IO_BYTES_PER_SECOND
        var deadline = due + if (dueRemainder == 0L) 0 else 1
        val start = now()
        // Reading/hashing time already satisfies pacing. After a stall, permit
        // only the current bounded operation, then restart without idle credit.
        if (deadline < start) {
            due = start; dueRemainder = 0; deadline = start
        }
        while (true) {
            val remaining = deadline - now()
            if (remaining <= 0) break
            active(); val wait = minOf(100, remaining); sleep(wait); throttledMs += wait
            check()
        }
        ioBytes += bytes
    }

    /** Also usable before inventory/validation, where no payload bytes are charged. */
    fun check() {
        active()
        if (now() < nextSample) return
        latest = sample(); nextSample = now() + 1000
        observe(latest, false)
        val first = latest ?: throw ClientImportFailure("memory-observation-unavailable")
        if (!first.pressured()) return
        pressurePauses++
        val start = now(); var healthySince: Long? = null
        while (true) {
            active(); observe(latest, true)
            if (now() - start >= MAX_PRESSURE_WAIT_MS) throw ClientImportFailure("memory-pressure")
            sleep(250); pressureWaitMs += 250
            active()
            if (now() >= nextSample) {
                latest = sample(); nextSample = now() + 1000
                val memory = latest ?: throw ClientImportFailure("memory-observation-unavailable")
                if (memory.recovered()) {
                    if (healthySince == null) healthySince = now()
                    if (now() - healthySince >= 5000) break
                } else healthySince = null
            }
        }
        due = now(); dueRemainder = 0; observe(latest, false)
    }
    companion object {
        // Owner-requested 5x rate; code 51 recorded 1,699,713 ms of pacing and
        // zero pressure pauses. Buffers, dirty windows and pressure gates stay bounded.
        const val IO_BYTES_PER_SECOND = 240L * 1024 * 1024
        const val MAX_PRESSURE_WAIT_MS = 120000L
        /** PSI numbers are percentages, exported as integer thousandths of one percent. */
        fun psi(text: String): Pair<Long, Long>? = runCatching {
            require(text.length <= 4096)
            fun value(kind: String): Long {
                val fields = text.lineSequence().single { it.startsWith("$kind ") }.split(' ')
                val raw = fields.single { it.startsWith("avg10=") }.substringAfter('=').toBigDecimal()
                require(raw >= java.math.BigDecimal.ZERO && raw <= java.math.BigDecimal(100))
                return raw.multiply(java.math.BigDecimal(1000)).toLong()
            }
            value("some") to value("full")
        }.getOrNull()
    }
}
