package com.russianranger.fta.client

/** Read-only PSI totals give recent stalls independently of the decaying avg10 trend. */
class ClientProbePsi {
    data class Totals(val some: Long, val full: Long) {
        init { require(some >= 0 && full in 0..some) }
    }
    data class Interval(val elapsedMs: Long, val someMilliPercent: Long, val fullMilliPercent: Long)
    private var previous: Pair<Long, Totals>? = null
    var interval: Interval? = null; private set
    var first = false; private set

    fun observe(totals: Totals?, now: Long) {
        val old = previous
        first = old == null && totals != null
        previous = totals?.let { now to it }
        interval = null
        if (old == null || totals == null) return
        val elapsed = now - old.first
        if (elapsed !in 500..2500) return
        if (totals.some < old.second.some || totals.full < old.second.full) return
        val some = totals.some - old.second.some; val full = totals.full - old.second.full
        if (some > elapsed * 1000 || full > some) return
        // delta microseconds / elapsed milliseconds * 100 = thousandths of one percent.
        interval = Interval(elapsed, some * 100 / elapsed, full * 100 / elapsed)
    }

    companion object {
        fun totals(text: String): Totals? = runCatching {
            require(text.length <= 4096)
            fun value(kind: String): Long {
                val fields = text.lineSequence().single { it.startsWith("$kind ") }.split(' ')
                val raw = fields.single { it.startsWith("total=") }.substringAfter('=')
                require(raw.isNotEmpty() && raw.length <= 19 && raw.all { it in '0'..'9' })
                return raw.toLong()
            }
            Totals(value("some"), value("full"))
        }.getOrNull()
    }
}
