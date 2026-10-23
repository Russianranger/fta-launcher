package com.russianranger.fta.runtime

/** Real SQL/ownership exercise, shared by the Android diagnostic and host JNI harness.
 * The caller supplies a fresh disposable database, never a realm or imported seed.
 */
object DatabaseLifecycleProbe {
    const val PASSED = "DATABASE_LIFECYCLE_PASSED"
    const val WINDOW_MS = 90_000L
    val fields = listOf("durableWrite", "gracefulRestart", "transactionObserved",
        "clientCancellation", "cancelledTransactionRolledBack", "screenOffObserved",
        "screenOffHealth", "forcedStopObserved", "crashRecovery", "finalCleanShutdown")
    val requiredFields = fields - setOf("screenOffObserved", "screenOffHealth")
    const val CREATE = "CREATE DATABASE fta_lifecycle; CREATE TABLE fta_lifecycle.probe(id INT PRIMARY KEY, marker VARBINARY(16)) ENGINE=InnoDB; INSERT INTO fta_lifecycle.probe VALUES(1,0x4654412D44555241424C45);"
    const val CHECK = "SELECT IF(COUNT(*)=1 AND SUM(id=1 AND marker=0x4654412D44555241424C45)=1,1,0) FROM fta_lifecycle.probe;"
    const val TRANSACTION = "START TRANSACTION; INSERT INTO fta_lifecycle.probe VALUES(2,0x4654412D524F4C4C4241434B); SELECT GET_LOCK('fta_lifecycle_transaction',0); DO SLEEP(300); COMMIT;"
    const val ACTIVE = "SELECT IS_USED_LOCK('fta_lifecycle_transaction') IS NOT NULL;"

    interface Driver {
        fun query(sql: String): String
        fun beginClient(sql: String): Long
        fun clientRunning(handle: Long): Boolean
        fun cancelClient(handle: Long): Int
        /** Clean SQL shutdown when false; zero-grace owned-group termination when true. */
        fun stopDatabase(force: Boolean): Int
        fun restartDatabase()
        fun stage(value: String)
        fun checkActive()
        fun pause(ms: Long)
        fun monotonicMs(): Long
        fun screenOff(): Boolean
    }

    fun run(driver: Driver, record: (String) -> Unit) {
        fun query(sql: String) = driver.query(sql).trim()
        fun unchanged() { check(query(CHECK) == "1") { "Disposable lifecycle canary changed" } }
        fun await(expected: String, handle: Long? = null) {
            val deadline = driver.monotonicMs() + 10_000
            while (true) {
                driver.checkActive()
                if (handle != null) check(driver.clientRunning(handle)) { "Transaction client exited early" }
                if (query(ACTIVE) == expected) return
                check(driver.monotonicMs() < deadline) { "Transaction connection did not reach expected state" }
                driver.pause(100)
            }
        }
        driver.stage("LIFECYCLE_WRITE")
        query(CREATE); unchanged(); record("durableWrite")
        driver.stage("LIFECYCLE_RESTART")
        check(driver.stopDatabase(false) == 0) { "Lifecycle database did not stop cleanly" }
        driver.restartDatabase(); unchanged(); record("gracefulRestart")
        driver.stage("LIFECYCLE_CANCEL")
        val client = driver.beginClient(TRANSACTION)
        await("1", client); record("transactionObserved")
        val cancelled = driver.cancelClient(client)
        check(cancelled == 143 || cancelled == 137) { "Transaction cancellation was not observed" }
        check(!driver.clientRunning(client)); record("clientCancellation")
        await("0"); unchanged(); record("cancelledTransactionRolledBack")

        driver.stage("LIFECYCLE_SCREEN_OFF")
        val end = driver.monotonicMs() + WINDOW_MS
        var offSince: Long? = null
        var screenOffObserved = false
        while (driver.monotonicMs() < end) {
            driver.checkActive()
            val off = driver.screenOff()
            unchanged()
            if (off && driver.screenOff()) {
                val now = driver.monotonicMs()
                if (offSince == null) offSince = now
                if (now - offSince >= 10_000) screenOffObserved = true
            } else offSince = null
            driver.pause(minOf(2_000, (end - driver.monotonicMs()).coerceAtLeast(0)))
        }
        if (screenOffObserved) { record("screenOffObserved"); record("screenOffHealth") }

        driver.stage("LIFECYCLE_CRASH")
        // Leave a second transaction open across actual SIGKILL recovery, not merely
        // a clean shutdown of an idle database. The named lock proves it is active.
        val interrupted = driver.beginClient(TRANSACTION)
        await("1", interrupted)
        check(driver.stopDatabase(true) == 137) { "Forced SIGKILL was not observed; recovery is unqualified" }
        record("forcedStopObserved")
        driver.cancelClient(interrupted)
        check(!driver.clientRunning(interrupted))
        driver.restartDatabase()
        await("0"); unchanged(); record("crashRecovery")
        driver.stage("LIFECYCLE_FINISH")
        check(driver.stopDatabase(false) == 0)
        record("finalCleanShutdown")
    }

    fun safeValues(values: Map<String, Any?>): Map<String, Boolean> =
        if (values.keys == fields.toSet() && values.values.all { it is Boolean })
            fields.associateWith { values[it] as Boolean } else emptyMap()

    internal fun qualified(flags: Map<String, Boolean>, state: String?, records: List<NativeInvocationEvidence>, dropped: Int): Boolean {
        if (state != PASSED || dropped != 0 || requiredFields.any { flags[it] != true }) return false
        val databases = records.filter { it.role == "database-server" && it.readyObserved }
        if (databases.size != 3 || databases.count { it.cleanShutdownObserved && it.sqlShutdownRequested && it.exitCode == 0 } != 2 ||
            databases.count { it.outcome == "stopped" && it.exitCode == 137 && !it.cleanShutdownObserved } != 1) return false
        if (records.count { it.role == "database-client" && it.stage == "LIFECYCLE_CANCEL" && it.outcome == "cancelled" && it.exitCode in setOf(137, 143) } != 1) return false
        if (records.count { it.role == "database-client" && it.stage == "LIFECYCLE_CRASH" && it.outcome == "cancelled" && it.exitCode in setOf(1, 137, 143) } != 1) return false
        return records.all { value ->
            !value.timedOut && value.validationFailure == null && value.stdoutBytes < NativeInvocationJournal.MAX_LOG_BYTES &&
                value.stderrBytes < NativeInvocationJournal.MAX_LOG_BYTES && when {
                    value.role == "database-client" && value.stage in setOf("LIFECYCLE_CANCEL", "LIFECYCLE_CRASH") && value.outcome == "cancelled" ->
                        value.exitCode in (if (value.stage == "LIFECYCLE_CANCEL") setOf(137, 143) else setOf(1, 137, 143))
                    value.role == "database-server" && value.outcome == "stopped" -> value.readyObserved && value.exitCode == 137
                    else -> value.outcome == "exited" && value.exitCode == 0 && !value.cancelled
                }
        }
    }
}
