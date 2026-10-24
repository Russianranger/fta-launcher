package com.russianranger.fta.runtime

/** Visible-activity priority lease only. Neither callback starts or restarts work. */
class GameDataBinding(private val bind: () -> Boolean, private val unbind: () -> Unit) {
    private var registered = false
    fun update(visible: Boolean, running: Boolean, scope: String?, stage: String?) {
        val needed = visible && running && ((scope == "game-data" &&
            (stage == "CHECKING" || stage in RUNNING_STAGES)) ||
            (scope == "world-service" && stage in setOf("CHECKING", "WORLD_ASSET_VERIFY", "WORLD_SESSION_START")))
        if (!needed) release()
        else if (!registered) {
            // Android requires unbind even when bindService returns false.
            registered = true
            try { if (!bind()) release() } catch (_: Exception) { release() }
        }
    }
    fun release() {
        if (registered) { registered = false; runCatching(unbind) }
    }
    companion object {
        private val RUNNING_STAGES = GameDataOperation.STAGES.map { "GAME_DATA_${it.uppercase(java.util.Locale.ROOT)}" }.toSet()
    }
}
