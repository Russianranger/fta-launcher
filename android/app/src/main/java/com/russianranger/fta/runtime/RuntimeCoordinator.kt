package com.russianranger.fta.runtime

/** Dependency order is protocol-specific: AzerothCore authserver, not CMaNGOS realmd. */
enum class RuntimeComponent { DATABASE, AUTHSERVER, WORLDSERVER }

interface ControlledComponent {
    val component: RuntimeComponent
    /** Must return only after the component is actually ready, or throw. */
    fun start()
    /** Graceful stop must complete within the driver's own deadline or throw. */
    fun stop()
    fun isAlive(): Boolean
}

/**
 * Serialized lifecycle policy. Drivers supply measured readiness and graceful protocol stops.
 * No placeholder driver is supplied for databases or servers in the diagnostic foundation.
 */
class RuntimeCoordinator(components: List<ControlledComponent>) {
    private val drivers = components.associateBy { it.component }
    private val active = linkedSetOf<RuntimeComponent>()
    init { require(drivers.size == components.size) { "Duplicate runtime component" } }

    @Synchronized fun running(): Set<RuntimeComponent> = active.toSet()

    @Synchronized fun startThrough(target: RuntimeComponent) {
        val needed = RuntimeComponent.entries.take(target.ordinal + 1)
        check(needed.all { drivers.containsKey(it) }) { "Required native component is unavailable" }
        val newlyStarted = mutableListOf<RuntimeComponent>()
        try {
            needed.forEach { component ->
                if (component in active) {
                    check(drivers.getValue(component).isAlive()) { "An active dependency exited unexpectedly" }
                } else {
                    val driver = drivers.getValue(component)
                    // Track ownership before spawn so failed readiness cannot lose a live child.
                    active += component
                    newlyStarted += component
                    driver.start()
                    check(driver.isAlive()) { "Native component exited before readiness" }
                }
            }
        } catch (failure: Exception) {
            newlyStarted.asReversed().forEach { component ->
                // Keep dependencies alive if a dependent could not be stopped.
                if (active.none { it.ordinal > component.ordinal }) {
                    try { stopComponent(component) }
                    catch (stopFailure: Exception) { failure.addSuppressed(stopFailure) }
                }
            }
            throw failure
        }
    }

    private fun stopComponent(component: RuntimeComponent) {
        if (component !in active) return
        val driver = drivers.getValue(component)
        var failure: Exception? = null
        try { driver.stop() } catch (stopFailure: Exception) { failure = stopFailure }
        val remainsAlive = runCatching { driver.isAlive() }.getOrDefault(true)
        if (!remainsAlive) active -= component
        if (remainsAlive) throw failure ?: IllegalStateException("Native component could not be stopped")
        failure?.let { throw it }
    }

    @Synchronized fun stopAll() {
        var firstFailure: Exception? = null
        for (component in RuntimeComponent.entries.asReversed()) {
            if (component in active) {
                try { stopComponent(component) }
                catch (failure: Exception) { if (firstFailure == null) firstFailure = failure }
                // Refuse to shut dependencies beneath a still-owned live component.
                if (component in active) break
            }
        }
        firstFailure?.let { throw it }
    }

    @Synchronized fun restartWorld() {
        check(RuntimeComponent.WORLDSERVER in active) { "Worldserver is not running" }
        check(listOf(RuntimeComponent.DATABASE, RuntimeComponent.AUTHSERVER).all {
            it in active && drivers.getValue(it).isAlive()
        }) { "Worldserver dependencies are not ready" }
        stopComponent(RuntimeComponent.WORLDSERVER)
        startThrough(RuntimeComponent.WORLDSERVER)
    }
}
