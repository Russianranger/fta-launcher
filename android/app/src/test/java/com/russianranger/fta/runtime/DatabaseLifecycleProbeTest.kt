package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class DatabaseLifecycleProbeTest {
    private class Model : DatabaseLifecycleProbe.Driver {
        var clock = 0L
        var lock = false
        var online = true
        var durable = false
        var screen = true
        var forceExit = 137
        var loseCommit = false
        var ignoreCancellation = false
        var cancelAt: String? = null
        var stage = ""
        var next = 0L
        val live = mutableSetOf<Long>()
        override fun query(sql: String): String = when (sql) {
            DatabaseLifecycleProbe.CREATE -> { durable = true; "" }
            DatabaseLifecycleProbe.CHECK -> { check(online); if (durable) "1" else "0" }
            DatabaseLifecycleProbe.ACTIVE -> if (lock) "1" else "0"
            else -> error("Unexpected SQL")
        }
        override fun beginClient(sql: String): Long { check(online); lock = true; return (++next).also { live += it } }
        override fun clientRunning(handle: Long) = handle in live
        override fun cancelClient(handle: Long): Int { if (!ignoreCancellation) { live -= handle; lock = false }; return 143 }
        override fun stopDatabase(force: Boolean): Int { online = false; live.clear(); lock = false; return if (force) forceExit else 0 }
        override fun restartDatabase() { check(!online); online = true; if (loseCommit) durable = false }
        override fun stage(value: String) { stage = value }
        override fun checkActive() { check(stage != cancelAt) { "Cancelled" } }
        override fun pause(ms: Long) { checkActive(); clock += ms }
        override fun monotonicMs() = clock
        override fun screenOff() = screen
    }
    private fun run(model: Model = Model()): Set<String> = mutableSetOf<String>().also { result ->
        DatabaseLifecycleProbe.run(model) { result += it }
    }
    @Test fun recordsOnlyMeasuredScreenOff() {
        assertEquals(DatabaseLifecycleProbe.fields.toSet(), run())
        assertEquals(DatabaseLifecycleProbe.requiredFields.toSet(), run(Model().apply { screen = false }))
    }
    @Test fun refusesUnobservedForceKill() {
        assertThrows(IllegalStateException::class.java) { run(Model().apply { forceExit = 0 }) }
    }
    @Test fun catchesLostDurableData() {
        assertThrows(IllegalStateException::class.java) { run(Model().apply { loseCommit = true }) }
    }
    @Test fun catchesClientThatSurvivesCancellation() {
        assertThrows(IllegalStateException::class.java) { run(Model().apply { ignoreCancellation = true }) }
    }
    @Test fun userCancellationStopsTheScreenOffWindow() {
        val model = Model().apply { cancelAt = "LIFECYCLE_SCREEN_OFF" }
        assertThrows(IllegalStateException::class.java) { run(model) }
        assertEquals(0L, model.clock)
    }
    @Test fun rejectsMissingAndNonBooleanEvidence() {
        val flags = DatabaseLifecycleProbe.fields.associateWith { true }
        assertEquals(flags, DatabaseLifecycleProbe.safeValues(flags))
        assertTrue(DatabaseLifecycleProbe.safeValues(flags - "crashRecovery").isEmpty())
        assertTrue(DatabaseLifecycleProbe.safeValues(flags + ("crashRecovery" to "true")).isEmpty())
    }
    private fun records(): List<NativeInvocationEvidence> {
        fun db(id: Int, crash: Boolean) = NativeInvocationEvidence(id, "tool-$id", "database-server", "LIFECYCLE_RESTART", 1, null,
            outcome = if (crash) "stopped" else "exited", exitCode = if (crash) 137 else 0,
            readyObserved = true, sqlShutdownRequested = !crash, cleanShutdownObserved = !crash)
        return listOf(db(1, false), db(2, true), db(3, false),
            NativeInvocationEvidence(4, "tool-4", "database-client", "LIFECYCLE_CANCEL", 1, 300000,
                outcome = "cancelled", exitCode = 143, cancelled = true),
            NativeInvocationEvidence(5, "tool-5", "database-client", "LIFECYCLE_CRASH", 1, 300000,
                outcome = "cancelled", exitCode = 1, cancelled = true))
    }
    @Test fun qualificationRequiresObservedOutcomesAndCurrentSuccess() {
        val flags = DatabaseLifecycleProbe.fields.associateWith { true }
        fun qualifies(records: List<NativeInvocationEvidence> = records(), state: String = DatabaseLifecycleProbe.PASSED, dropped: Int = 0) =
            DatabaseLifecycleProbe.qualified(flags, state, records, dropped)
        assertTrue(qualifies())
        assertFalse(qualifies(state = "STOPPED"))
        assertFalse(qualifies(dropped = 1))
        assertFalse(qualifies(records().drop(1)))
        for (index in records().indices) {
            assertFalse(qualifies(records().mapIndexed { i, value -> if (i == index) value.copy(timedOut = true) else value }))
        }
        assertFalse(qualifies(records() + NativeInvocationEvidence(6, "tool-6", "database-client", "CHECKING", 1, 1000, outcome = "exited", exitCode = 1)))
        assertFalse(DatabaseLifecycleProbe.qualified(flags + ("crashRecovery" to false), DatabaseLifecycleProbe.PASSED, records(), 0))
    }
}
