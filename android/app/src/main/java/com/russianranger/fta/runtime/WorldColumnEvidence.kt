package com.russianranger.fta.runtime

/** Observational catalog hashes only. These never decide whether the updater passed. */
internal data class WorldColumnDigest(val count: Int, val bytes: Int, val sha256: String)
internal data class WorldColumnTable(val tableIndex: Int, val digest: WorldColumnDigest)
internal data class WorldColumnProbe(val probeIndex: Int, val rowSha256: String, val typeSha256: String,
    val nullableSha256: String, val defaultSha256: String, val collationSha256: String, val extraSha256: String)
internal data class WorldColumnSnapshot(val aggregate: WorldColumnDigest, val tables: List<WorldColumnTable>,
    val probes: List<WorldColumnProbe>)

internal object WorldColumnEvidence {
    const val MAX_OUTPUT_BYTES = 128 * 1024
    const val MAX_RECEIPT_BYTES = 160 * 1024
    const val MAX_EXPORT_BYTES = 200 * 1024
    private const val CONCAT_LIMIT = 1048576
    private val hash = Regex("[a-f0-9]{64}")
    private val identifier = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
    private val attempt = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    // Exact canonical row used by the unchanged strict updater postcondition.
    private const val row = "CONCAT(HEX(table_name),':',HEX(column_name),':',ordinal_position,':',HEX(column_type),':'," +
        "HEX(is_nullable),':',IF(column_default IS NULL,'~',HEX(column_default)),':'," +
        "IF(collation_name IS NULL,'~',HEX(collation_name)),':',HEX(extra),CHAR(10))"
    private val probeColumns = listOf("creature_template" to "speed_run",
        "mod_fake_player_ambience_go_target" to "stand_distance",
        "mod_fake_player_ambience_npc_target" to "stand_distance",
        "mod_fake_player_ambience_profile" to "interact_distance")

    /** One current-session read-only batch: aggregate, every table, and four fixed component hashes. */
    fun schemaSql(schema: WorldUpdaterSchema): String {
        validateSchema(schema)
        val source = "information_schema.columns WHERE table_schema='acore_world'"
        val joined = "GROUP_CONCAT($row ORDER BY BINARY table_name,ordinal_position SEPARATOR '')"
        val tableCase = schema.tableNames.mapIndexed { index, name -> "WHEN '$name' THEN $index" }.joinToString(" ")
        val probeCases = probeColumns.mapIndexed { index, (table, column) ->
            "WHEN BINARY table_name='$table' AND BINARY column_name='$column' THEN $index"
        }.joinToString(" ")
        val predicates = probeColumns.joinToString(" OR ") { (table, column) ->
            "(BINARY table_name='$table' AND BINARY column_name='$column')"
        }
        val components = listOf("HEX(column_type)", "HEX(is_nullable)",
            "IF(column_default IS NULL,'~',HEX(column_default))",
            "IF(collation_name IS NULL,'~',HEX(collation_name))", "HEX(extra)")
        return WorldUpdaterEvidence.COLUMN_TIME_ZONE_SQL + "SET SESSION group_concat_max_len=$CONCAT_LIMIT;\n" +
            "SELECT CONCAT('FTA_COLUMNS=',COUNT(*),':',COALESCE(SUM(OCTET_LENGTH($row)),0),':'," +
            "COALESCE(OCTET_LENGTH($joined),0),':',COALESCE(SUM(0),0),':',COALESCE(SHA2($joined,256),SHA2('',256))) FROM $source;\n" +
            "SELECT CONCAT('FTA_COLUMN_TABLE=',CASE BINARY tableName $tableCase ELSE -1 END,':',columnCount,':'," +
            "columnBytes,':',joinedBytes,':',0,':',columnHash) FROM (SELECT MIN(table_name) AS tableName," +
            "COUNT(*) AS columnCount,SUM(OCTET_LENGTH($row)) AS columnBytes,OCTET_LENGTH($joined) AS joinedBytes," +
            "SHA2($joined,256) AS columnHash FROM $source GROUP BY BINARY table_name) AS columnTables " +
            "ORDER BY BINARY tableName;\n" +
            "SELECT CONCAT('FTA_COLUMN_PROBE=',CASE $probeCases ELSE -1 END,':',SHA2($row,256),':'," +
            components.joinToString(",':',") { "SHA2($it,256)" } + ") FROM $source AND ($predicates) " +
            "ORDER BY CASE $probeCases ELSE -1 END;"
    }

    fun parseSnapshot(output: String, schema: WorldUpdaterSchema, expectedTableNames: List<String>): WorldColumnSnapshot {
        require(output.toByteArray().size <= MAX_OUTPUT_BYTES && output.endsWith('\n') && '\u0000' !in output)
        val lines = output.removeSuffix("\n").split('\n')
        require(lines.size == expectedTableNames.size + 5)
        val aggregate = parseDigest(lines.first(), "FTA_COLUMNS=")
        val tables = lines.drop(1).take(expectedTableNames.size).map { line ->
            val match = Regex("FTA_COLUMN_TABLE=([0-9]{1,4}):(.*)").matchEntire(line)
                ?: error("Invalid column table metadata")
            WorldColumnTable(match.groupValues[1].toInt(), parseDigest(match.groupValues[2], ""))
        }
        val probes = lines.takeLast(4).map { line ->
            val parts = line.removePrefix("FTA_COLUMN_PROBE=").split(':')
            require(line.startsWith("FTA_COLUMN_PROBE=") && parts.size == 7 && parts[0].matches(Regex("[0-3]")))
            WorldColumnProbe(parts[0].toInt(), parts[1], parts[2], parts[3], parts[4], parts[5], parts[6])
        }
        return WorldColumnSnapshot(aggregate, tables, probes).also { validateSnapshot(it, schema, expectedTableNames) }
    }

    /** Both snapshots are optional while running, but an after snapshot requires a complete before snapshot. */
    fun receiptMap(attemptId: String, worldSha256: String, lockSha256: String, sourceTreeSha256: String,
        schema: WorldUpdaterSchema, seedTableNames: List<String>, before: WorldColumnSnapshot?,
        after: WorldColumnSnapshot?): Map<String, Any> {
        require(attempt.matches(attemptId) && listOf(worldSha256, lockSha256, sourceTreeSha256).all(hash::matches))
        validateSchema(schema)
        require(after == null || before != null)
        before?.let { validateSnapshot(it, schema, seedTableNames) }
        after?.let { validateSnapshot(it, schema, schema.tableNames) }
        return linkedMapOf<String, Any>("formatVersion" to 1, "diagnosticOnly" to true,
            "attemptId" to attemptId, "worldExecutableSha256" to worldSha256,
            "worldUpdaterLockSha256" to lockSha256, "sourceTreeSha256" to sourceTreeSha256,
            "expectedPost" to digestMap(WorldColumnDigest(schema.columnCount, schema.columnBytes, schema.columnSha256))).apply {
            before?.let { put("before", snapshotMap(it)) }
            after?.let { put("after", snapshotMap(it)) }
        }
    }

    /** Reconstruct only exact typed counts/hashes from a matching current-attempt receipt. */
    fun safeReceipt(text: String, attemptId: String, worldSha256: String, lockSha256: String,
        sourceTreeSha256: String, schema: WorldUpdaterSchema, seedTableNames: List<String>): Map<String, Any>? = runCatching {
        require(text.toByteArray().size <= MAX_RECEIPT_BYTES)
        val value = StrictJson(text).parse().objectValue()
        value.onlyKeys("formatVersion", "diagnosticOnly", "attemptId", "worldExecutableSha256",
            "worldUpdaterLockSha256", "sourceTreeSha256", "expectedPost", "before", "after")
        require(value.required("formatVersion").integerValue() == 1L && value.required("diagnosticOnly").booleanValue())
        require(value.required("attemptId").stringValue() == attemptId &&
            value.required("worldExecutableSha256").stringValue() == worldSha256 &&
            value.required("worldUpdaterLockSha256").stringValue() == lockSha256 &&
            value.required("sourceTreeSha256").stringValue() == sourceTreeSha256)
        require(readDigest(value.required("expectedPost")) == WorldColumnDigest(schema.columnCount, schema.columnBytes, schema.columnSha256))
        val before = value["before"]?.let(::readSnapshot)
        val after = value["after"]?.let(::readSnapshot)
        require(before != null)
        receiptMap(attemptId, worldSha256, lockSha256, sourceTreeSha256, schema, seedTableNames, before, after)
    }.getOrNull()

    private fun validateSchema(schema: WorldUpdaterSchema) {
        require(schema.name == "acore_world" && schema.tableNames.size in 1..1000 &&
            schema.tableNames == schema.tableNames.sorted().distinct() && schema.tableNames.all(identifier::matches) &&
            probeColumns.all { it.first in schema.tableNames })
        validateDigest(WorldColumnDigest(schema.columnCount, schema.columnBytes, schema.columnSha256))
    }

    private fun validateSnapshot(value: WorldColumnSnapshot, schema: WorldUpdaterSchema, names: List<String>) {
        validateSchema(schema)
        require(names.isNotEmpty() && names == names.sorted().distinct() && names.all { it in schema.tableNames })
        validateDigest(value.aggregate)
        val indices = names.map(schema.tableNames::indexOf)
        require(value.tables.map { it.tableIndex } == indices)
        value.tables.forEach { validateDigest(it.digest) }
        require(value.tables.sumOf { it.digest.count.toLong() } == value.aggregate.count.toLong() &&
            value.tables.sumOf { it.digest.bytes.toLong() } == value.aggregate.bytes.toLong())
        require(value.probes.map { it.probeIndex } == listOf(0, 1, 2, 3))
        value.probes.forEach {
            require(listOf(it.rowSha256, it.typeSha256, it.nullableSha256, it.defaultSha256, it.collationSha256, it.extraSha256).all(hash::matches))
        }
    }

    private fun validateDigest(value: WorldColumnDigest) {
        require(value.count in 1..100000 && value.bytes in 1..CONCAT_LIMIT && hash.matches(value.sha256))
    }

    private fun parseDigest(text: String, prefix: String): WorldColumnDigest {
        val match = Regex(Regex.escape(prefix) + "([0-9]{1,6}):([0-9]{1,8}):([0-9]{1,8}):0:([a-f0-9]{64})").matchEntire(text)
            ?: error("Invalid column aggregate metadata")
        require(match.groupValues[2] == match.groupValues[3]) { "Truncated column aggregate metadata" }
        return WorldColumnDigest(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[4])
            .also(::validateDigest)
    }

    private fun digestMap(value: WorldColumnDigest): Map<String, Any> =
        linkedMapOf("count" to value.count, "bytes" to value.bytes, "sha256" to value.sha256)

    private fun snapshotMap(value: WorldColumnSnapshot): Map<String, Any> = linkedMapOf(
        "aggregate" to digestMap(value.aggregate),
        "tables" to value.tables.map { linkedMapOf("tableIndex" to it.tableIndex) + digestMap(it.digest) },
        "probes" to value.probes.map { linkedMapOf("probeIndex" to it.probeIndex, "rowSha256" to it.rowSha256,
            "typeSha256" to it.typeSha256, "nullableSha256" to it.nullableSha256, "defaultSha256" to it.defaultSha256,
            "collationSha256" to it.collationSha256, "extraSha256" to it.extraSha256) })

    private fun integer(value: Any): Int = value.integerValue().also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt()

    private fun readDigest(raw: Any): WorldColumnDigest {
        val value = raw.objectValue()
        value.onlyKeys("count", "bytes", "sha256")
        return WorldColumnDigest(integer(value.required("count")), integer(value.required("bytes")), value.required("sha256").stringValue())
    }

    private fun readSnapshot(raw: Any): WorldColumnSnapshot {
        val value = raw.objectValue()
        value.onlyKeys("aggregate", "tables", "probes")
        return WorldColumnSnapshot(readDigest(value.required("aggregate")), value.required("tables").arrayValue().map { rawTable ->
            val table = rawTable.objectValue()
            table.onlyKeys("tableIndex", "count", "bytes", "sha256")
            WorldColumnTable(integer(table.required("tableIndex")), WorldColumnDigest(integer(table.required("count")),
                integer(table.required("bytes")), table.required("sha256").stringValue()))
        }, value.required("probes").arrayValue().map { rawProbe ->
            val probe = rawProbe.objectValue()
            probe.onlyKeys("probeIndex", "rowSha256", "typeSha256", "nullableSha256", "defaultSha256", "collationSha256", "extraSha256")
            WorldColumnProbe(integer(probe.required("probeIndex")), probe.required("rowSha256").stringValue(),
                probe.required("typeSha256").stringValue(), probe.required("nullableSha256").stringValue(),
                probe.required("defaultSha256").stringValue(), probe.required("collationSha256").stringValue(), probe.required("extraSha256").stringValue())
        })
    }
}
