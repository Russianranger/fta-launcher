package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class FullSeedEvidenceTest {
    private val schema = FullSeedSchema("acore_world", 371, listOf("fixture_trigger"), 0, 0, 3191, 227261, "a".repeat(64))
    private fun validation(receipts: String = "3191:227261:227261:0:${"a".repeat(64)}") =
        FullSeedEvidence.validationFields.dropLast(1).joinToString("\n") { "$it=1" } + "\nFTA_RECEIPTS=$receipts\n"
    private fun values() = mapOf<String, Any?>("plannedTableCount" to 563, "importedTableCount" to 563,
        "plannedTriggerCount" to 1, "importedTriggerCount" to 1, "fullSeedImported" to true, "validationPassed" to true)
    private fun server(clean: Boolean = true) = NativeInvocationEvidence(1, "server", "database-server", "STARTING", 0, null,
        outcome = "exited", exitCode = 0, readyObserved = true, sqlShutdownRequested = true, cleanShutdownObserved = clean)
    private fun imports() = List(564) { index -> NativeInvocationEvidence(index + 2, "tool-${index + 1}", "database-client", "SEED_IMPORT", 0, 300000,
        outcome = "exited", exitCode = 0) }

    @Test fun receiptDigestUsesExactHashAndVerifiedUntruncatedBytes() {
        assertTrue(FullSeedEvidence.parseValidation(validation(), schema).values.all { it })
        assertFalse(FullSeedEvidence.parseValidation(validation("3191:227261:227261:0:${"b".repeat(64)}"), schema).getValue("receiptBaselineMatches"))
        assertFalse(FullSeedEvidence.parseValidation(validation("3190:227261:227261:0:${"a".repeat(64)}"), schema).getValue("receiptBaselineMatches"))
    }

    @Test fun truncatedMalformedOrInvalidReceiptMetadataFailsClosed() {
        for (summary in listOf("3191:227261:1024:0:${"a".repeat(64)}", "3191:227261:227261:1:${"a".repeat(64)}",
            "0:0:0:0:${"a".repeat(64)}", "3191:227261:227261:0:private data", "3191:1048577:1048577:0:${"a".repeat(64)}")) {
            assertThrows(Exception::class.java) { FullSeedEvidence.parseValidation(validation(summary), schema) }
        }
    }

    @Test fun onlyExpectedBooleanMarkersAreParsed() {
        for (output in listOf(validation() + "tableInventoryMatches=1\n", validation().replace("eventsMatch=1", "eventsMatch=true"),
            validation().replace("eventsMatch=1\n", ""), validation() + "name=private\n", "x".repeat(1025))) {
            assertThrows(Exception::class.java) { FullSeedEvidence.parseValidation(output, schema) }
        }
    }

    @Test fun storedTypesAndCrossFieldCompletionCannotBeCoerced() {
        assertEquals(6, FullSeedEvidence.safeValues(values()).size)
        assertTrue(FullSeedEvidence.safeValues(values() + ("importedTableCount" to "563")).isEmpty())
        assertTrue(FullSeedEvidence.safeValues(values() + ("fullSeedImported" to "true")).isEmpty())
        assertTrue(FullSeedEvidence.safeValues(values() + ("importedTableCount" to 562)).isEmpty())
        assertTrue(FullSeedEvidence.safeValues(values() + ("fullSeedImported" to false)).isEmpty())
        assertTrue(FullSeedEvidence.safeValues(values() + ("plannedTableCount" to 562)).isEmpty())
        assertFalse(FullSeedEvidence.safeValues(values() + mapOf("fullSeedImported" to false, "validationPassed" to false)).isEmpty())
    }

    @Test fun qualificationNeedsEveryImportCurrentPassAndCleanShutdown() {
        val records = listOf(server()) + imports()
        assertTrue(FullSeedEvidence.qualified(true, "FULL_SEED_DIAGNOSTIC_PASSED", records, 0))
        assertFalse(FullSeedEvidence.qualified(false, "FULL_SEED_DIAGNOSTIC_PASSED", records, 0))
        assertFalse(FullSeedEvidence.qualified(true, "FAILED", records, 0))
        assertFalse(FullSeedEvidence.qualified(true, "SEED_VALIDATION", records, 0))
        assertFalse(FullSeedEvidence.qualified(true, "FULL_SEED_DIAGNOSTIC_PASSED", records, 1))
        assertFalse(FullSeedEvidence.qualified(true, "FULL_SEED_DIAGNOSTIC_PASSED", listOf(server(false)) + imports(), 0))
        assertFalse(FullSeedEvidence.qualified(true, "FULL_SEED_DIAGNOSTIC_PASSED", records.dropLast(1), 0))
        assertFalse(FullSeedEvidence.qualified(true, "FULL_SEED_DIAGNOSTIC_PASSED", listOf(server()) + imports().mapIndexed { index, it -> if (index == 0) it.copy(exitCode = 1) else it }, 0))
        assertFalse(FullSeedEvidence.qualified(true, "FULL_SEED_DIAGNOSTIC_PASSED", listOf(server()) + imports().mapIndexed { index, it -> if (index == 0) it.copy(timedOut = true) else it }, 0))
    }

    @Test fun generatedInventorySqlChecksCatalogOnlyAndRejectsUnsafeNames() {
        val files = listOf(FullSeedEntry("", "acore_world", "table", "fixture_table", 1, "a".repeat(64)))
        val sql = FullSeedEvidence.inventorySql(schema, files)
        assertTrue(sql.contains("BINARY table_name IN ('fixture_table')"))
        assertTrue(sql.contains("trigger_name IN ('fixture_trigger')"))
        assertTrue(sql.contains("FROM information_schema.routines"))
        assertFalse(sql.contains("INSERT")); assertFalse(sql.contains("UPDATE ")); assertFalse(sql.contains("DROP"))
        assertThrows(Exception::class.java) { FullSeedEvidence.inventorySql(schema.copy(name = "live;DROP"), files) }
        assertThrows(Exception::class.java) { FullSeedEvidence.inventorySql(schema, files.map { it.copy(name = "x';DROP") }) }
    }

    @Test fun receiptSqlReturnsOnlyMetadataSummaryAndUsesBoundedServerDigest() {
        val sql = FullSeedEvidence.receiptSql(schema)
        assertTrue(sql.contains("group_concat_max_len=1048576"))
        assertTrue(sql.contains("ORDER BY BINARY `name`"))
        assertTrue(sql.contains("SUM(OCTET_LENGTH")); assertTrue(sql.contains("OCTET_LENGTH(GROUP_CONCAT"))
        assertTrue(sql.contains("SHA2(")); assertTrue(sql.contains("FROM `acore_world`.`updates`"))
        assertThrows(Exception::class.java) { FullSeedEvidence.receiptSql(schema.copy(name = "live")) }
    }
}
