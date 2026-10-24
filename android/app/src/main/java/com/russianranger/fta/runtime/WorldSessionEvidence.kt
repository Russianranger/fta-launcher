package com.russianranger.fta.runtime

/** Source-bound milestones only. Never retain native log lines or database rows. */
internal object WorldSessionEvidence {
    const val MAX_LOG_BYTES = 8 * 1024 * 1024L
    val fields = listOf("assetsRehashed", "gtCoverageBefore", "dbcStoresLoaded", "nativeReadyMarker", "freezeGuardStarted",
        "worldChallenge", "worldHalted", "worldCleanStop", "worldListenerClosed", "gtCoverageAfter")
    val faults = mapOf(
        "missingStartingMaps" to "Failed to find map files for starting areas",
        "dbcMissingOrIncompatible" to "Some required *.dbc files",
        "incorrectDataDirectory" to "Incorrect DataDir value in worldserver.conf",
        "worldLoopFrozen" to "World Thread hangs for ")
    val diagnosticFields = faults.keys + "worldLogLimitExceeded"
    private val dbc = Regex(">> Initialized 113 Data Stores in [0-9]+ ms")
    fun markers(text: String): Set<String> = buildSet {
        faults.forEach { (key, phrase) -> if (text.contains(phrase)) add(key) }
        if (dbc.containsMatchIn(text)) add("dbcStoresLoaded")
        if (text.contains("Starting up anti-freeze thread (60 seconds max stuck time)...")) add("freezeGuardStarted")
        if (text.contains("Halting process...")) add("worldHalted")
        if (text.contains("(worldserver-daemon) ready...")) add("nativeReadyMarker")
    }
    fun gtSql(): String = GameDataDbcCompatibility.databaseRoutes.values.joinToString("\n") { route ->
        "SELECT COUNT(*)=${route.rows} AND COUNT(DISTINCT ID)=${route.rows} AND MIN(ID)=0 AND MAX(ID)=${route.rows - 1} AND SUM(Data IS NULL)=0 FROM acore_world.`${route.table}`;"
    }
    fun requireCoverage(output: String) {
        require(output.trim().lines() == List(10) { "1" }) { "Original GT row coverage differs" }
    }
}

class WorldSessionControl(val directory: java.io.File, val observe: (String, Boolean) -> Unit)
