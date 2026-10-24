package com.russianranger.fta.runtime

/** Preserve the failing phase before reverse-order cleanup reports its own stages. */
internal class WorldSessionFailure private constructor(val stage: String, cause: Exception) : Exception(stage, cause) {
    companion object {
        fun <T> during(stage: String, work: () -> T): T {
            require(stage in setOf("WORLD_SESSION_START", "LOCAL_SERVICE_RUNNING", "WORLD_SESSION_STOP"))
            try { return work() }
            catch (failure: WorldSessionFailure) { throw failure }
            catch (failure: Exception) { throw WorldSessionFailure(stage, failure) }
        }
    }
}
