package com.russianranger.fta.client

/** Handle-owned synthetic gates. No caller-selected PID is ever signalled. */
object ClientProbeProtocol {
    data class Identity(val pid: Int, val parent: Int, val session: Int, val ticks: Long)
    data class Result(val normalExit: Int, val stopExit: Int, val killedExit: Int,
        val stoppedTracees: Int, val killedTracees: Int, val stages: Map<String, Int>)
    interface Driver {
        fun now(): Long
        fun pause(ms: Long)
        fun active()
        fun start(mode: String): Long
        fun tracer(handle: Long): Int
        fun output(handle: Long): String
        fun identity(pid: Int): Identity?
        fun ended(identity: Identity): Boolean
        fun waitFor(handle: Long, ms: Long): Int
        fun stop(handle: Long, graceMs: Long): Int
        /** A private control file asks only the initial tracee to kill its own tracer parent. */
        fun requestTracerDeath(handle: Long)
        fun recordOwnership(key: String, value: Int) { }
    }
    val stages = listOf("cpu-topology", "display", "prefix", "cpu-registry", "x86-entry", "x64-entry",
        "two-channels", "sixteen-channels", "owner-exit", "wine-stop")
    const val RUNNING = Int.MIN_VALUE
    const val WINE_TIMEOUT_MS = 1_200_000L
    fun parseStages(output: String, exit: Int): Map<String, Int> {
        runtimeRequire(exit == 0 && output.length <= 64 * 1024, "probe-failed")
        val seen = linkedMapOf<String, Int>(); var passed = 0
        for (line in output.lineSequence()) {
            if (line == "FTA_ANDROID_SYNTHETIC_PASS") { passed++; continue }
            if (!line.startsWith("FTA_ANDROID_STAGE ")) continue
            val parts = line.split(' ')
            runtimeRequire(parts.size == 3 && parts[1] in stages && parts[1] !in seen && parts[2] == "0", "probe-failed")
            seen[parts[1]] = 0
        }
        runtimeRequire(passed == 1 && seen.keys.toList() == stages, "probe-failed")
        return seen
    }
    private fun awaitExit(driver: Driver, handle: Long, ms: Long): Int {
        val end = driver.now() + ms
        var nextObservation = driver.now()
        while (true) {
            driver.active(); val status = driver.waitFor(handle, 100)
            if (status != RUNNING) return status
            runtimeRequire(driver.now() < end, "probe-timeout")
            if (driver.now() >= nextObservation) {
                driver.output(handle)
                nextObservation = driver.now() + 1000
            }
        }
    }
    private fun children(driver: Driver, handle: Long): List<Identity> {
        val end = driver.now() + 5000
        while (true) {
            driver.active(); val text = driver.output(handle)
            runtimeRequire(text.length <= 8192, "ownership-failed")
            val pids = text.lineSequence().filter { it.startsWith("FTA_ANDROID_OWNED ") }.map {
                it.removePrefix("FTA_ANDROID_OWNED ").toIntOrNull() ?: throw RuntimeInstallFailure("ownership-failed")
            }.toList()
            runtimeRequire(pids.size <= 3 && pids.distinct().size == pids.size && pids.all { it > 1 }, "ownership-failed")
            if (pids.size == 3) {
                val tracer = driver.tracer(handle)
                val identities = pids.map { driver.identity(it) ?: throw RuntimeInstallFailure("ownership-failed") }
                runtimeRequire(identities.all { it.ticks > 0 && it.pid != tracer && (it.parent == tracer || it.parent in pids) } &&
                    identities.count { it.parent == tracer } == 1 && identities.count { it.session == it.pid } == 2, "ownership-failed")
                // Every node must reach the actual owned tracer, never a cycle/unrelated tree.
                for (node in identities) {
                    var parent = node.parent; var hops = 0
                    while (parent != tracer) {
                        runtimeRequire(++hops <= 2, "ownership-failed")
                        parent = identities.singleOrNull { it.pid == parent }?.parent ?: throw RuntimeInstallFailure("ownership-failed")
                    }
                }
                return identities
            }
            runtimeRequire(driver.waitFor(handle, 0) == RUNNING && driver.now() < end, "ownership-failed")
            driver.pause(50)
        }
    }
    private fun ended(driver: Driver, identities: List<Identity>) {
        val end = driver.now() + 3000
        while (!identities.all(driver::ended)) {
            driver.active(); runtimeRequire(driver.now() < end, "ownership-failed"); driver.pause(50)
        }
    }
    fun run(driver: Driver): Result {
        fun <T> owned(mode: String, action: (Long) -> T): T {
            driver.active(); val handle = driver.start(mode)
            try { return action(handle) }
            finally { runtimeRequire(driver.stop(handle, 2000) != RUNNING, "ownership-failed") }
        }
        val normal = owned("--ownership-normal") { handle ->
            awaitExit(driver, handle, 10000).also { runtimeRequire(it == 0, "ownership-failed") }
        }
        driver.recordOwnership("ownershipNormalExit", normal)
        val stopped = owned("--ownership-hold") { handle ->
            val identities = children(driver, handle)
            val exit = driver.stop(handle, 2000)
            runtimeRequire(exit != RUNNING, "ownership-failed"); ended(driver, identities); exit
        }
        driver.recordOwnership("ownershipStopExit", stopped)
        driver.recordOwnership("stoppedTracees", 3)
        val killed = owned("--ownership-hold") { handle ->
            val identities = children(driver, handle)
            driver.requestTracerDeath(handle)
            val exit = awaitExit(driver, handle, 3000)
            runtimeRequire(exit == 137, "ownership-failed"); ended(driver, identities); exit
        }
        driver.recordOwnership("ownershipKilledExit", killed)
        driver.recordOwnership("killedTracees", 3)
        val results = owned("--wine") { handle -> parseStages(driver.outputAfterExit(handle), 0) }
        return Result(normal, stopped, killed, 3, 3, results)
    }
    private fun Driver.outputAfterExit(handle: Long): String {
        val status = awaitExit(this, handle, WINE_TIMEOUT_MS)
        runtimeRequire(status == 0, "probe-failed"); return output(handle)
    }
}
