package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class RuntimeCoordinatorTest {
    private class Fake(override val component: RuntimeComponent, private val events: MutableList<String>) : ControlledComponent {
        var alive = false
        var failStart = false
        var failStop = false
        var refuseStop = false
        override fun start() { events += "start:$component"; alive = true; if (failStart) error("Failed readiness") }
        override fun stop() { events += "stop:$component"; alive = refuseStop; if (failStop) error("Failed graceful stop") }
        override fun isAlive() = alive
    }
    private fun drivers(events: MutableList<String>) = RuntimeComponent.entries.map { Fake(it, events) }

    @Test fun startAndStopFollowDependencyOrder() {
        val events = mutableListOf<String>()
        val runtime = RuntimeCoordinator(drivers(events))
        runtime.startThrough(RuntimeComponent.WORLDSERVER)
        runtime.stopAll()
        assertEquals(listOf("start:DATABASE", "start:AUTHSERVER", "start:WORLDSERVER", "stop:WORLDSERVER", "stop:AUTHSERVER", "stop:DATABASE"), events)
    }
    @Test fun failedWorldStartRollsBackOnlyNewComponents() {
        val events = mutableListOf<String>()
        val drivers = drivers(events)
        val runtime = RuntimeCoordinator(drivers)
        runtime.startThrough(RuntimeComponent.DATABASE)
        drivers.last().failStart = true
        assertThrows(IllegalStateException::class.java) { runtime.startThrough(RuntimeComponent.WORLDSERVER) }
        assertEquals(setOf(RuntimeComponent.DATABASE), runtime.running())
        assertTrue(drivers.first().alive)
        assertEquals(listOf("start:DATABASE", "start:AUTHSERVER", "start:WORLDSERVER", "stop:WORLDSERVER", "stop:AUTHSERVER"), events)
    }
    @Test fun worldRestartPreservesDatabaseAndAuth() {
        val events = mutableListOf<String>()
        val runtime = RuntimeCoordinator(drivers(events))
        runtime.startThrough(RuntimeComponent.WORLDSERVER)
        events.clear()
        runtime.restartWorld()
        assertEquals(listOf("stop:WORLDSERVER", "start:WORLDSERVER"), events)
    }
    @Test fun missingDriverPreventsAnyStart() {
        val events = mutableListOf<String>()
        val runtime = RuntimeCoordinator(listOf(Fake(RuntimeComponent.DATABASE, events)))
        assertThrows(IllegalStateException::class.java) { runtime.startThrough(RuntimeComponent.WORLDSERVER) }
        assertTrue(events.isEmpty())
    }
    @Test fun stoppedDependencyBlocksFurtherStarts() {
        val events = mutableListOf<String>()
        val drivers = drivers(events)
        val runtime = RuntimeCoordinator(drivers)
        runtime.startThrough(RuntimeComponent.DATABASE)
        drivers.first().alive = false
        assertThrows(IllegalStateException::class.java) { runtime.startThrough(RuntimeComponent.WORLDSERVER) }
        assertEquals(listOf("start:DATABASE"), events)
    }
    @Test fun liveDependentRetainsOwnershipAndKeepsDependenciesUp() {
        val events = mutableListOf<String>()
        val drivers = drivers(events)
        val runtime = RuntimeCoordinator(drivers)
        runtime.startThrough(RuntimeComponent.WORLDSERVER)
        drivers.last().refuseStop = true
        assertThrows(IllegalStateException::class.java) { runtime.stopAll() }
        assertEquals(RuntimeComponent.entries.toSet(), runtime.running())
        assertFalse(events.contains("stop:DATABASE"))
        assertFalse(events.contains("stop:AUTHSERVER"))
    }

    @Test fun shutdownStillStopsDependenciesAfterFailure() {
        val events = mutableListOf<String>()
        val drivers = drivers(events)
        val runtime = RuntimeCoordinator(drivers)
        runtime.startThrough(RuntimeComponent.WORLDSERVER)
        drivers.last().failStop = true
        assertThrows(IllegalStateException::class.java) { runtime.stopAll() }
        assertTrue(runtime.running().isEmpty())
        assertTrue(drivers.none { it.alive })
        assertEquals("stop:DATABASE", events.last())
    }
}
