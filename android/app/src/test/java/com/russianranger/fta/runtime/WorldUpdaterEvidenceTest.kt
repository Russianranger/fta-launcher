package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class WorldUpdaterEvidenceTest {
    private val phases = listOf(
        WorldUpdatePhase("Auth", "Updating Auth database...", ">> Auth database is up-to-date! Containing 11 new and 1 archived updates."),
        WorldUpdatePhase("Character", "Updating Character database...", ">> Applied 22 queries. Containing 26 new and 24 archived updates."),
        WorldUpdatePhase("World", "Updating World database...", ">> Applied 3 queries. Containing 871 new and 2320 archived updates."))
    private val stop = "ERROR [server.loading] Failed to find map files for starting areas\n"
    private fun log() = "INFO [server.worldserver] Original build banner\n" + phases.joinToString("") {
        "INFO [sql.updates] ${it.startMarker}\nINFO [sql.updates] ${it.completionMarker}\n"
    } + "INFO [server.loading] Loading World Information...\nINFO [server.loading] WORLD: VMap support included.\n" + stop
    private fun boundary(value: String = log(), exit: Int = 1, stdout: String = "", stderr: String = stop) =
        WorldUpdaterEvidence.boundary(exit, value, stdout, stderr, phases)
    private val schema = WorldUpdaterSchema("acore_world", listOf("fta_release_updates", "fixture_table"), listOf("fixture_trigger"),
        0, 0, 10, 800, "a".repeat(64), 2, 200, "b".repeat(64), 20, 2000, "c".repeat(64))
    private fun validation() = WorldUpdaterEvidence.validationFields.filterNot {
        it in setOf("receiptBaselineMatches", "releaseReceiptBaselineMatches", "columnInventoryMatches")
    }.joinToString("\n") { "$it=1" } +
        "\nFTA_RECEIPTS=10:800:800:0:${"a".repeat(64)}\nFTA_RELEASES=2:200:200:0:${"b".repeat(64)}\nFTA_COLUMNS=20:2000:2000:0:${"c".repeat(64)}\n"

    @Test fun requiresOriginalNormalExitExactOrderedUpdaterOutcomesAndPrepareBoundary() {
        assertTrue(boundary().values.all { it })
        for (value in listOf(log().replace(phases[0].startMarker, "Updating World database..."),
            log().replace(phases[1].completionMarker, ">> Applied 21 queries. Containing 26 new and 24 archived updates."),
            log().replace("INFO [server.loading] Loading World Information...\n", ""),
            log().replace(phases[0].completionMarker, phases[0].completionMarker + "\nINFO [sql.updates] " + phases[0].completionMarker),
            stop + log(), log().removeSuffix("\n"), log() + "unexpected continued execution\n")) {
            assertThrows(Exception::class.java) { boundary(value) }
        }
        for (exit in listOf(0, 2, 139, Int.MIN_VALUE)) assertThrows(Exception::class.java) { boundary(exit = exit) }
    }

    @Test fun receiptsCannotHideEarlierSqlFailuresMissingDirectoriesOrUnknownErrors() {
        for (line in listOf("ERROR [sql.sql] Unexpected database failure", "FATAL [sql.updates] Applying of file failed!",
            "WARN [sql.updates] DBUpdater: Given update include directory missing does not exist, skipped!",
            "ERROR 1064 (42000) at line 1: private SQL", "ERROR malformed native failure", "INFO [server] Dry run completed, terminating.")) {
            assertThrows(Exception::class.java) { boundary(log().replace(stop, "$line\n$stop")) }
        }
        assertThrows(Exception::class.java) { boundary(stderr = "ERROR 1064 at line 1\n" + stop) }
        assertThrows(Exception::class.java) { boundary(stdout = "CANNOT LINK EXECUTABLE private/path\n") }
        assertThrows(Exception::class.java) { boundary(stdout = "Warning (Code 1265): private SQL warning\n") }
        assertThrows(Exception::class.java) { boundary(log().replace(stop, "WARN [sql.sql] Unexpected server warning\n$stop")) }
    }

    @Test fun onlyExactSourceOrphanWarningsAtTheMeasuredPhaseCountAreAccepted() {
        val warning = "WARN [sql.updates] >> The file 'old_fixture.sql' was applied to the database, but is missing in your update directory now!\n"
        val value = log().replace("INFO [sql.updates] ${phases[0].completionMarker}", warning + "INFO [sql.updates] ${phases[0].completionMarker}")
        val expected = phases.mapIndexed { index, phase -> if (index == 0) phase.copy(orphanedReceiptWarnings = 1) else phase }
        assertTrue(WorldUpdaterEvidence.boundary(1, value, "", stop, expected).values.all { it })
        assertThrows(Exception::class.java) { boundary(value) }
        assertThrows(Exception::class.java) { WorldUpdaterEvidence.boundary(1, value.replace(warning, warning + warning), "", stop, expected) }
        assertThrows(Exception::class.java) { WorldUpdaterEvidence.boundary(1, value.replace("old_fixture.sql' was", "unsafe/path.sql' was"), "", stop, expected) }
    }

    @Test fun completeLogReaderRejectsOversizeMalformedUtf8AndNul() {
        val file = Files.createTempFile("fta-world-output", ".log").toFile()
        try {
            file.writeText(log()); assertEquals(log(), WorldUpdaterEvidence.readComplete(file))
            for (bytes in listOf(byteArrayOf(0), byteArrayOf(0xc3.toByte(), 0x28))) {
                file.writeBytes(bytes); assertThrows(Exception::class.java) { WorldUpdaterEvidence.readComplete(file) }
            }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(WorldUpdaterEvidence.MAX_OUTPUT_BYTES + 1L) }
            assertThrows(Exception::class.java) { WorldUpdaterEvidence.readComplete(file) }
        } finally { file.delete() }
    }

    @Test fun postconditionsRequireExactReceiptsColumnsReleaseHashesAndUntruncatedAggregates() {
        assertTrue(WorldUpdaterEvidence.parseValidation(validation(), schema).values.all { it })
        assertFalse(WorldUpdaterEvidence.parseValidation(validation().replace("a".repeat(64), "d".repeat(64)), schema).getValue("receiptBaselineMatches"))
        for (value in listOf(validation().replace("2000:2000", "2000:1024"), validation().replace("200:200:0", "200:200:1"),
            validation() + "tableInventoryMatches=1\n", validation().replace("eventsMatch=1\n", ""),
            validation().replace("routinesMatch=1", "routinesMatch=true"))) {
            assertThrows(Exception::class.java) { WorldUpdaterEvidence.parseValidation(value, schema) }
        }
    }

    @Test fun postconditionSqlUsesOnlyCatalogAndMetadataWithCaseSensitiveCanonicalOrdering() {
        val sql = WorldUpdaterEvidence.schemaSql(schema, listOf(WorldIncludeDirectory("data/sql/updates/db_world", "RELEASED", "acore_world")))
        assertTrue(sql.contains("BINARY release_id,BINARY name")); assertTrue(sql.contains("^Sql/"))
        assertTrue(sql.contains("BINARY table_name,ordinal_position")); assertTrue(sql.contains("column_default IS NULL,'~'"))
        assertTrue(sql.contains("routines")); assertTrue(sql.contains("events")); assertTrue(sql.contains("SHA2("))
        assertFalse(sql.contains("UPDATE ")); assertFalse(sql.contains("INSERT ")); assertFalse(sql.contains("DROP "))
    }

    @Test fun shareableCompletionCannotCoerceTypesOrTreatAttemptAsSuccess() {
        val values = (WorldUpdaterEvidence.boundaryFields + listOf("freshSeedBaselinePassed", "compiledUpdaterPassed", "runtimeSqlAttempted", "runtimeSqlCompletionQualified"))
            .associateWith<String, Any?> { true } + mapOf("plannedRuntimeSqlFiles" to 236, "stagedRuntimeSqlFiles" to 236, "moduleCount" to 52, "moduleSqlCount" to 0)
        assertFalse(WorldUpdaterEvidence.safeValues(values).isEmpty())
        for (change in listOf("moduleCount" to 51, "stagedRuntimeSqlFiles" to 235, "compiledUpdaterPassed" to "true", "runtimeSqlCompletionQualified" to false)) {
            assertTrue(WorldUpdaterEvidence.safeValues(values + change).isEmpty())
        }
    }

    @Test fun qualificationNeedsCurrentPassAllImportsExpectedWorldExitThreePostChecksAndCleanSqlShutdown() {
        val server = NativeInvocationEvidence(1, "server", "database-server", "STARTING", 0, null,
            outcome = "exited", exitCode = 0, readyObserved = true, sqlShutdownRequested = true, cleanShutdownObserved = true)
        val imports = List(564) { NativeInvocationEvidence(it + 2, "tool-${it + 1}", "database-client", "SEED_IMPORT", 0, 300000, outcome = "exited", exitCode = 0) }
        val world = NativeInvocationEvidence(566, "tool-565", "world-server", "WORLD_UPDATER", 0, 600000, outcome = "exited", exitCode = 1)
        val checks = List(3) { NativeInvocationEvidence(it + 567, "tool-${it + 566}", "database-client", "WORLD_VALIDATION", 0, 30000, outcome = "exited", exitCode = 0) }
        val records = listOf(server) + imports + world + checks
        assertTrue(WorldUpdaterEvidence.qualified(true, WorldUpdaterEvidence.PASSED, records, 0))
        assertTrue(FullSeedEvidence.qualified(true, WorldUpdaterEvidence.PASSED, records, 0))
        assertFalse(WorldUpdaterEvidence.qualified(true, "FAILED", records, 0))
        assertFalse(WorldUpdaterEvidence.qualified(true, WorldUpdaterEvidence.PASSED, records, 1))
        assertFalse(WorldUpdaterEvidence.qualified(true, WorldUpdaterEvidence.PASSED, records.dropLast(1), 0))
        assertFalse(WorldUpdaterEvidence.qualified(true, WorldUpdaterEvidence.PASSED, records.map { if (it == server) it.copy(cleanShutdownObserved = false) else it }, 0))
        assertFalse(WorldUpdaterEvidence.qualified(true, WorldUpdaterEvidence.PASSED, records.map { if (it == world) it.copy(validationFailure = "world_boundary_invalid") else it }, 0))
    }
}
