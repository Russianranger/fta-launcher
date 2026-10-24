package com.russianranger.fta.runtime

/** Source-bound milestones only. Never retain native log lines or database rows. */
internal object WorldSessionEvidence {
    const val MAX_LOG_BYTES = 8 * 1024 * 1024L
    data class Scan(val markers: Set<String>, val limitReached: Boolean)
    fun scan(file: java.io.File): Scan {
        val found = mutableSetOf<String>()
        val line = java.io.ByteArrayOutputStream(4096)
        var oversized = false; var count = 0L
        java.nio.file.Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(8192)
            while (count < MAX_LOG_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_LOG_BYTES - count).toInt())
                if (read < 0) return Scan(found, false)
                count += read
                for (i in 0 until read) {
                    if (buffer[i] == 10.toByte()) {
                        if (!oversized) found.addAll(markers(line.toString(Charsets.UTF_8.name())))
                        line.reset(); oversized = false
                    } else if (line.size() < 4096) line.write(buffer[i].toInt()) else oversized = true
                }
            }
        }
        return Scan(found, true)
    }
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
