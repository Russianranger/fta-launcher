package com.russianranger.fta.runtime

/** Compose the existing health/screen-off clock with strict reverse shutdown order. */
internal object WorldServiceProtocol {
    interface World {
        fun start()
        fun health()
        fun stop(): Int
        fun closed(): Boolean
    }
    fun combine(auth: LocalServiceProtocol.Driver, world: World,
        observe: (String, Boolean) -> Unit): LocalServiceProtocol.Driver = object : LocalServiceProtocol.Driver by auth {
        override fun start() { auth.start(); world.start() }
        override fun health() { auth.health(); world.health() }
        override fun stop(): Int {
            val worldExit = world.stop()
            // Int.MIN_VALUE means a child is still owned; never remove its database.
            check(worldExit != Int.MIN_VALUE) { "World process has not ended" }
            val closed = world.closed()
            observe("worldCleanStop", worldExit == 0)
            observe("worldListenerClosed", closed)
            val authExit = auth.stop()
            check(worldExit == 0 && closed) { "World shutdown did not qualify" }
            return authExit
        }
        override fun listenerClosed() = world.closed() && auth.listenerClosed()
    }
}
