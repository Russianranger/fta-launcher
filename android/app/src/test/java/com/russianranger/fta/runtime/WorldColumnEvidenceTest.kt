package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test

class WorldColumnEvidenceTest {
    private val names = listOf("creature_template", "fta_release_updates", "mod_fake_player_ambience_go_target",
        "mod_fake_player_ambience_npc_target", "mod_fake_player_ambience_profile")
    private val seedNames = names.filterNot { it == "fta_release_updates" }
    private val schema = WorldUpdaterSchema("acore_world", names, emptyList(), 0, 0, 1, 10, "a".repeat(64),
        1, 10, "b".repeat(64), 10, 500, "c".repeat(64))
    private val attempt = "12345678-1234-1234-1234-123456789abc"
    private val world = "d".repeat(64)
    private val lock = "e".repeat(64)
    private val tree = "f".repeat(64)
    private fun output(tables: List<String> = names) =
        "FTA_COLUMNS=${tables.size * 2}:${tables.size * 100}:${tables.size * 100}:0:${"c".repeat(64)}\n" +
            tables.joinToString("") { "FTA_COLUMN_TABLE=${names.indexOf(it)}:2:100:100:0:${"a".repeat(64)}\n" } +
            (0..3).joinToString("") { index -> "FTA_COLUMN_PROBE=$index:" + List(6) { "b".repeat(64) }.joinToString(":") + "\n" }
    private fun snapshot(tables: List<String> = names) = WorldColumnEvidence.parseSnapshot(output(tables), schema, tables)
    private fun receipt(before: WorldColumnSnapshot? = snapshot(seedNames), after: WorldColumnSnapshot? = snapshot()) =
        WorldColumnEvidence.receiptMap(attempt, world, lock, tree, schema, seedNames, before, after)
    private fun safe(text: String) = WorldColumnEvidence.safeReceipt(text, attempt, world, lock, tree, schema, seedNames)
    private fun json(value: Any): String = when (value) {
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key as String) + ":" + json(it.value!!) }
        is List<*> -> value.joinToString(",", "[", "]") { json(it!!) }
        is String -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Number, is Boolean -> value.toString()
        else -> error("Unsupported fixture value")
    }

    @Test fun completeBeforeAndAfterUseOneStableSignedTableIndexWithoutRawCatalogValues() {
        assertEquals(listOf(0, 2, 3, 4), snapshot(seedNames).tables.map { it.tableIndex })
        assertEquals(listOf(0, 1, 2, 3, 4), snapshot().tables.map { it.tableIndex })
        val text = json(receipt())
        val exported = safe(text)
        assertNotNull(exported)
        assertEquals(receipt(), exported)
        for (privateText in names + listOf("speed_run", "stand_distance", "interact_distance", "1.14286", "2.3", "SELECT", "/private/"))
            assertFalse(text.contains(privateText))
        assertTrue(text.toByteArray().size < WorldColumnEvidence.MAX_RECEIPT_BYTES)
    }

    @Test fun coverageIsExactAndUnknownDuplicateMissingOrReorderedTablesCannotBeShared() {
        val good = output()
        val table = "FTA_COLUMN_TABLE=0:2:100:100:0:${"a".repeat(64)}\n"
        for (bad in listOf(good.replace(table, ""), good.replace(table, table + table),
            good.replace("FTA_COLUMN_TABLE=0:", "FTA_COLUMN_TABLE=99:"),
            good.replace("FTA_COLUMN_TABLE=0:", "FTA_COLUMN_TABLE=-1:"),
            good.replace("FTA_COLUMN_TABLE=0:", "FTA_COLUMN_TABLE=1:"),
            good.replace("FTA_COLUMN_TABLE=1:", "FTA_COLUMN_TABLE=0:"), good + "private query rows\n")) {
            assertThrows(Exception::class.java) { WorldColumnEvidence.parseSnapshot(bad, schema, names) }
        }
        assertThrows(Exception::class.java) { WorldColumnEvidence.parseSnapshot(output(seedNames), schema, names) }
        assertThrows(Exception::class.java) { WorldColumnEvidence.parseSnapshot(output(), schema, seedNames) }
    }

    @Test fun malformedTruncatedOversizedAndInconsistentAggregatesFailClosed() {
        val good = output()
        for (bad in listOf(good.removeSuffix("\n"), good.replace("500:500", "500:499"),
            good.replace("10:500:500", "9:500:500"), good.replace("10:500:500", "10:499:499"),
            good.replace("2:100:100:0", "2:100:100:1"), good.replace("2:100:100:0", "0:100:100:0"),
            good.replace("c".repeat(64), "C".repeat(64)), good.replace("FTA_COLUMNS=", "FTA_COLUMNS=\u0000"),
            good + "x".repeat(WorldColumnEvidence.MAX_OUTPUT_BYTES))) {
            assertThrows(Exception::class.java) { WorldColumnEvidence.parseSnapshot(bad, schema, names) }
        }
    }

    @Test fun eachFixedProbeAndEveryComponentHashIsRequiredExactlyOnce() {
        val good = output()
        val probe = "FTA_COLUMN_PROBE=0:" + List(6) { "b".repeat(64) }.joinToString(":") + "\n"
        for (bad in listOf(good.replace(probe, ""), good.replace(probe, probe + probe),
            good.replace("FTA_COLUMN_PROBE=1:", "FTA_COLUMN_PROBE=0:"),
            good.replace("FTA_COLUMN_PROBE=3:", "FTA_COLUMN_PROBE=4:"),
            good.replace(probe, probe.replace(":" + "b".repeat(64), "")),
            good.replace(probe, probe.replaceFirst("b".repeat(64), "private default value")))) {
            assertThrows(Exception::class.java) { WorldColumnEvidence.parseSnapshot(bad, schema, names) }
        }
    }

    @Test fun staleUnboundIncompleteOrInjectedReceiptsAreNotExportable() {
        val text = json(receipt())
        for (bad in listOf(text.replace(attempt, "00000000-0000-0000-0000-000000000000"),
            text.replace(world, "0".repeat(64)), text.replace(lock, "1".repeat(64)), text.replace(tree, "2".repeat(64)),
            text.replace("\"diagnosticOnly\":true", "\"diagnosticOnly\":false"),
            text.replace("\"count\":10", "\"count\":11"),
            text.replace("\"tableIndex\":0", "\"tableIndex\":\"0\""),
            text.dropLast(1) + ",\"compiledUpdaterPassed\":true}",
            text.dropLast(1) + ",\"attemptId\":\"$attempt\"}",
            text.replace("\"rowSha256\":\"${"b".repeat(64)}\"", "\"rowSha256\":\"private SQL\""),
            " ".repeat(WorldColumnEvidence.MAX_RECEIPT_BYTES) + text)) {
            assertNull(safe(bad))
        }
        assertThrows(Exception::class.java) { receipt(before = null) }
        assertNull(safe(json(receipt(before = null, after = null))))
        assertNotNull(safe(json(receipt(after = null))))
    }

    @Test fun queryUsesOnlyBoundedCatalogHashesAndRejectsUntrustedIdentifiers() {
        val sql = WorldColumnEvidence.schemaSql(schema)
        assertTrue(sql.contains("group_concat_max_len=1048576"))
        assertTrue(sql.contains("GROUP BY BINARY table_name"))
        assertTrue(sql.contains("ORDER BY BINARY table_name,ordinal_position"))
        assertTrue(sql.contains("ELSE -1 END"))
        assertFalse(Regex("(?i)\\b(INSERT|UPDATE|DELETE|DROP|ALTER|CREATE|GRANT|LOAD)\\b").containsMatchIn(sql))
        assertThrows(Exception::class.java) { WorldColumnEvidence.schemaSql(schema.copy(name = "private_schema")) }
        assertThrows(Exception::class.java) { WorldColumnEvidence.schemaSql(schema.copy(tableNames = names + "unsafe';DELETE")) }
        assertThrows(Exception::class.java) { WorldColumnEvidence.schemaSql(schema.copy(tableNames = names.reversed())) }
    }

    @Test fun validObservationCannotOverrideTheUnchangedStrictColumnMismatch() {
        val values = WorldUpdaterEvidence.validationFields.filterNot {
            it in setOf("receiptBaselineMatches", "releaseReceiptBaselineMatches", "columnInventoryMatches")
        }.joinToString("\n") { "$it=1" } + "\nFTA_RECEIPTS=1:10:10:0:${"a".repeat(64)}\n" +
            "FTA_RELEASES=1:10:10:0:${"b".repeat(64)}\nFTA_COLUMNS=10:500:500:0:${"0".repeat(64)}\n"
        assertNotNull(safe(json(receipt())))
        assertFalse(WorldUpdaterEvidence.parseValidation(values, schema).getValue("columnInventoryMatches"))
        assertNull(safe(json(receipt() + ("compiledUpdaterQualified" to true))))
    }
}
