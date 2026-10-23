package com.russianranger.fta.runtime

/** User-owned session. Stop requests finish health work, then stop auth before SQL shutdown. */
object LocalServiceProtocol {
    const val MAX_SESSION_MS = 30 * 60 * 1000L
    const val SAMPLE_MS = 10_000L
    const val SCREEN_OFF_REQUIRED_MS = 120_000L
    val fields = listOf("protocolReady", "authCleanStop", "listenerClosed")
    data class Progress(val healthySamples: Int, val elapsedMs: Long, val screenOffHealthyMs: Long,
        val stopReason: String = "running")
    interface Driver {
        fun checkActive()
        fun start()
        /** Must verify both original auth protocol and live database SQL. */
        fun health()
        fun interactive(): Boolean
        fun nowMs(): Long
        fun pause(ms: Long)
        fun stop(): Int
        fun listenerClosed(): Boolean
    }
    fun run(driver: Driver, stopRequested: () -> Boolean, observe: (String, Boolean) -> Unit,
        progress: (Progress) -> Unit, limitMs: Long = MAX_SESSION_MS): Progress {
        require(limitMs in 1..MAX_SESSION_MS)
        driver.checkActive()
        if (stopRequested()) return Progress(0, 0, 0, "before_start").also(progress)
        try {
            driver.start(); driver.checkActive(); driver.health()
            observe("protocolReady", true)
            val start = driver.nowMs(); var previous = start
            var wasOff = !driver.interactive(); var samples = 1; var offMs = 0L
            var result = Progress(samples, 0, 0).also(progress)
            while (!stopRequested() && driver.nowMs() - start < limitMs) {
                driver.checkActive()
                driver.pause(100)
                if (driver.interactive()) wasOff = false
                val now = driver.nowMs()
                check(now >= previous) { "Monotonic session clock moved backwards" }
                if (now - previous < SAMPLE_MS) continue
                // Discard an interval if the device was observed interactive between probes.
                driver.health(); driver.checkActive()
                val after = driver.nowMs(); val isOff = !driver.interactive()
                if (wasOff && isOff && after - previous <= SAMPLE_MS + 5_000) offMs += after - previous
                previous = after; wasOff = isOff; samples++
                result = Progress(samples, after - start, offMs).also(progress)
            }
            driver.checkActive()
            check(driver.stop() == 0) { "Original authserver did not stop cleanly" }
            observe("authCleanStop", true)
            check(driver.listenerClosed()) { "Auth listener remained open" }
            observe("listenerClosed", true)
            return result.copy(elapsedMs = driver.nowMs() - start,
                stopReason = if (stopRequested()) "user" else "session_limit").also(progress)
        } catch (failure: Exception) {
            runCatching { driver.stop() }
            throw failure
        }
    }
}

class LocalServiceControl(val stopRequested: () -> Boolean, val progress: (LocalServiceProtocol.Progress) -> Unit)
