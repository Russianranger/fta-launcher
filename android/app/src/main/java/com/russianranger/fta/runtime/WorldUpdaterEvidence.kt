package com.russianranger.fta.runtime

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Only fixed booleans are exported. Native logs and SQL remain private. */
internal object WorldUpdaterEvidence {
    // The immutable host fixture was measured in UTC+07:00. TIMESTAMP defaults
    // in information_schema are rendered in the inspecting session's time zone.
    // Pin that representation only for metadata reads; never change global/server
    // time, importer/updater sessions, stored timestamps, or expected hashes.
    const val COLUMN_TIME_ZONE_SQL = "SET SESSION time_zone='+07:00';\n"
    const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    const val PASSED = "COMPILED_UPDATER_DIAGNOSTIC_PASSED"
    val boundaryFields = listOf("authUpdaterCompleted", "characterUpdaterCompleted", "worldUpdaterCompleted",
        "allStatementsPrepared", "originalMissingMapBoundaryObserved", "normalStartupOnly", "completeNativeLogs", "noNativeSqlFailures")
    val validationFields = listOf("tableInventoryMatches", "triggerInventoryMatches", "routinesMatch", "eventsMatch",
        "receiptBaselineMatches", "releaseReceiptBaselineMatches", "columnInventoryMatches", "includeInventoryMatches")
    private val logLine = Regex("(FATAL|ERROR|WARN|INFO) \\[([A-Za-z0-9_.-]+)] (.*)")
    private const val missingMap = "Failed to find map files for starting areas"
    private val rawFailure = Regex("(?im)(?:^|\\n)(?:ERROR\\s+[0-9]|(?:.*?)(?:SQL Error|SQL_ERROR|mysql_stmt_prepare|query failed|update failed|Applying of file .* failed|Could not (?:update|prepare|open|populate)|Cannot (?:connect|load)|CANNOT LINK EXECUTABLE|Fatal signal|AddressSanitizer|runtime error:))")
    private val rawWarning = Regex("(?im)(?:^|\\n)Warning(?: \\(Code [0-9]+\\)|[ \\t]+[0-9]|[ \\t]+SQLSTATE)")
    private val orphanWarning = Regex(">> The file '[A-Za-z0-9_.+-]{1,240}[.]sql' was applied to the database, but is missing in your update directory now!")

    fun readComplete(file: File): String {
        require(file.isFile && file.length() in 0..MAX_OUTPUT_BYTES.toLong()) { "Native log is unavailable or exceeds its complete-output limit" }
        val bytes = file.readBytes()
        require(bytes.size <= MAX_OUTPUT_BYTES && bytes.none { it == 0.toByte() })
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    /** The synchronous file appender preserves cross-stream ordering. Stdout/stderr are also complete. */
    fun boundary(exitCode: Int, orderedLog: String, stdout: String, stderr: String,
        phases: List<WorldUpdatePhase>): Map<String, Boolean> {
        require(phases.map { it.database } == listOf("Auth", "Character", "World"))
        require(exitCode == 1) { "World process did not reach its original missing-map exit" }
        listOf(orderedLog, stdout, stderr).forEach {
            require(it.toByteArray().size <= MAX_OUTPUT_BYTES && '\u0000' !in it && !rawFailure.containsMatchIn(it) && !rawWarning.containsMatchIn(it))
        }
        require(orderedLog.endsWith('\n') && orderedLog.isNotBlank()) { "World log is incomplete" }
        require(orderedLog.lineSequence().lastOrNull { it.isNotBlank() } == "ERROR [server.loading] $missingMap") {
            "World output continued beyond its expected boundary"
        }
        listOf(orderedLog, stdout, stderr).forEach { output ->
            output.lineSequence().filter { it.startsWith("ERROR") || it.startsWith("FATAL") }.forEach { line ->
                require(line.removeSuffix("\r") == "ERROR [server.loading] $missingMap") { "Unexpected native error output" }
            }
        }
        val records = orderedLog.lineSequence().mapNotNull { line ->
            logLine.matchEntire(line.removeSuffix("\r"))?.let { Triple(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
        }.toList()
        require(records.isNotEmpty())
        val errors = records.filter { it.first in setOf("ERROR", "FATAL") }
        require(errors.size == 1 && errors.single() == Triple("ERROR", "server.loading", missingMap)) { "World process logged an unexpected error" }
        require(records.none { "Given update include directory" in it.third ||
            "Automatic database updates are disabled" in it.third || "Dry run" in it.third })
        // All three success summaries must belong to exactly one ordered phase.
        var phase = -1
        val completed = BooleanArray(3)
        var prepared = false
        var boundary = false
        val orphanWarnings = IntArray(3)
        val labels = listOf("Auth", "Character", "World")
        records.forEach { (level, category, message) ->
            if (message.startsWith("Updating ") && message.endsWith(" database...")) {
                val next = labels.indexOf(message.removePrefix("Updating ").removeSuffix(" database..."))
                require(level == "INFO" && category == "sql.updates" && next == phase + 1 &&
                    (phase < 0 || completed[phase]) && !prepared && !boundary) { "Unexpected updater phase ordering" }
                phase = next
            }
            if (level == "WARN" && (category == "sql" || category.startsWith("sql."))) {
                require(category == "sql.updates" && phase in 0..2 && !completed[phase] && orphanWarning.matches(message)) {
                    "Unexpected native SQL warning"
                }
                orphanWarnings[phase]++
                require(orphanWarnings[phase] <= phases[phase].orphanedReceiptWarnings)
            }
            val summary = Regex(">> (?:Applied [1-9][0-9]{0,4} quer(?:y|ies)\\.|(Auth|Character|World) database is up-to-date!) Containing [0-9]{1,5} new and [0-9]{1,5} archived updates\\.").matchEntire(message)
            if (summary != null) {
                require(level == "INFO" && category == "sql.updates" && phase in 0..2 && !completed[phase] && !prepared && !boundary)
                require(summary.groupValues[1].isEmpty() || summary.groupValues[1] == labels[phase])
                require(message == phases[phase].completionMarker) { "Native updater result differs from the exact source policy" }
                require(orphanWarnings[phase] == phases[phase].orphanedReceiptWarnings) { "Native orphan receipt warning count differs" }
                completed[phase] = true
            }
            if (message == "Loading World Information...") {
                require(level == "INFO" && category == "server.loading" && completed.all { it } && !prepared && !boundary)
                prepared = true
            }
            if (message == missingMap) {
                require(prepared && !boundary && completed.all { it }); boundary = true
            }
            if (boundary) require(message == missingMap) { "World execution continued beyond the expected stop boundary" }
        }
        require(completed.all { it } && prepared && boundary) { "Compiled updater completion evidence is incomplete" }
        return boundaryFields.associateWith { true }
    }

    fun qualified(receiptPassed: Boolean, state: String?, invocations: List<NativeInvocationEvidence>, dropped: Int): Boolean =
        receiptPassed && state == PASSED && dropped == 0 &&
            invocations.any { it.log == "server" && it.role == "database-server" && it.readyObserved &&
                it.sqlShutdownRequested && it.cleanShutdownObserved && it.outcome == "exited" && it.exitCode == 0 } &&
            invocations.filter { it.stage == "SEED_IMPORT" }.let { it.size == 564 && it.all { value ->
                value.role == "database-client" && value.outcome == "exited" && value.exitCode == 0 } } &&
            invocations.count { it.role == "world-server" && it.stage == "WORLD_UPDATER" && it.outcome == "exited" && it.exitCode == 1 } == 1 &&
            invocations.count { it.role == "database-client" && it.stage == "WORLD_VALIDATION" && it.outcome == "exited" && it.exitCode == 0 } == 3 &&
            invocations.all { !it.timedOut && !it.cancelled && it.validationFailure == null && it.outcome == "exited" &&
                (it.exitCode == 0 || (it.role == "world-server" && it.stage == "WORLD_UPDATER" && it.exitCode == 1)) }

    fun includeSql(schema: String, directories: List<WorldIncludeDirectory>): String {
        require(schema in FullSeedStore.SCHEMAS)
        val selected = directories.filter { it.schema == schema }
        require(selected.isNotEmpty())
        val valid = selected.joinToString(" OR ") { "(BINARY path='\$/${it.path}' AND BINARY state='${it.state}')" }
        return "SELECT CONCAT('includeInventoryMatches=',COUNT(*)=${selected.size} AND COALESCE(SUM($valid),0)=${selected.size}) FROM `$schema`.`updates_include`;"
    }

    fun schemaSql(schema: WorldUpdaterSchema, includes: List<WorldIncludeDirectory>): String {
        val seedShape = FullSeedSchema(schema.name, schema.tableNames.size, schema.triggerNames,
            schema.routineCount, schema.eventCount, schema.receiptCount, schema.receiptBytes, schema.receiptSha256)
        val files = schema.tableNames.map { FullSeedEntry("", schema.name, "table", it, 1, "") }
        val columns = "CONCAT(HEX(table_name),':',HEX(column_name),':',ordinal_position,':',HEX(column_type),':'," +
            "HEX(is_nullable),':',IF(column_default IS NULL,'~',HEX(column_default)),':'," +
            "IF(collation_name IS NULL,'~',HEX(collation_name)),':',HEX(extra),CHAR(10))"
        val release = "CONCAT(release_id,CHAR(9),name,CHAR(9),sha256,CHAR(9),status,CHAR(9),once_only,CHAR(10))"
        val invalidRelease = "release_id IS NULL OR name IS NULL OR sha256 IS NULL OR status IS NULL OR once_only IS NULL OR " +
            "BINARY release_id NOT REGEXP '^[A-Za-z0-9_.+-]{1,120}$' OR BINARY name NOT REGEXP '^Sql/[A-Za-z0-9_.+-]{1,186}$' OR " +
            "BINARY sha256 NOT REGEXP '^[A-Fa-f0-9]{64}$' OR BINARY status<>'APPLIED' OR once_only<>1"
        val releaseSql = if ("fta_release_updates" in schema.tableNames)
            aggregateSql("FTA_RELEASES", "`${schema.name}`.`fta_release_updates`", release, "BINARY release_id,BINARY name", invalidRelease)
            else "SELECT 'FTA_RELEASES=0:0:0:0:$EMPTY_SHA256';"
        return COLUMN_TIME_ZONE_SQL + FullSeedEvidence.inventorySql(seedShape, files) + "\n" + FullSeedEvidence.receiptSql(seedShape) + "\n" +
            aggregateSql("FTA_COLUMNS", "information_schema.columns WHERE table_schema='${schema.name}'", columns,
                "BINARY table_name,ordinal_position", "0") + "\n" + releaseSql + "\n" + includeSql(schema.name, includes)
    }

    private fun aggregateSql(prefix: String, source: String, row: String, order: String, invalid: String): String {
        val joined = "GROUP_CONCAT($row ORDER BY $order SEPARATOR '')"
        return "SET SESSION group_concat_max_len=1048576;\nSELECT CONCAT('$prefix=',COUNT(*),':'," +
            "COALESCE(SUM(OCTET_LENGTH($row)),0),':',COALESCE(OCTET_LENGTH($joined),0),':'," +
            "COALESCE(SUM($invalid),0),':',COALESCE(SHA2($joined,256),SHA2('',256))) FROM $source;"
    }

    fun parseValidation(output: String, schema: WorldUpdaterSchema): Map<String, Boolean> {
        require(output.length <= 2048)
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        val aggregates = listOf("FTA_RECEIPTS", "FTA_RELEASES", "FTA_COLUMNS")
        val expected = listOf(Triple(schema.receiptCount, schema.receiptBytes, schema.receiptSha256),
            Triple(schema.releaseReceiptCount, schema.releaseReceiptBytes, schema.releaseReceiptSha256),
            Triple(schema.columnCount, schema.columnBytes, schema.columnSha256))
        val flags = FullSeedEvidence.parseFlags(lines.filter { line -> aggregates.none { line.startsWith("$it=") } }
            .joinToString("\n"), validationFields.filterNot { it in setOf("receiptBaselineMatches", "releaseReceiptBaselineMatches", "columnInventoryMatches") })
            .toMutableMap()
        aggregates.forEachIndexed { index, prefix ->
            val matching = lines.filter { it.startsWith("$prefix=") }
            require(matching.size == 1)
            val match = Regex("$prefix=([0-9]{1,6}):([0-9]{1,8}):([0-9]{1,8}):([0-9]{1,6}):([a-f0-9]{64})")
                .matchEntire(matching.single()) ?: error("Unexpected compiled updater metadata")
            val count = match.groupValues[1].toInt()
            val bytes = match.groupValues[2].toInt()
            require(count in 0..100000 && bytes in 0..1048576 && match.groupValues[3].toInt() == bytes && match.groupValues[4] == "0")
            require((count == 0) == (bytes == 0) && (count != 0 || match.groupValues[5] == EMPTY_SHA256))
            val (expectedCount, expectedBytes, expectedHash) = expected[index]
            flags[listOf("receiptBaselineMatches", "releaseReceiptBaselineMatches", "columnInventoryMatches")[index]] =
                count == expectedCount && bytes == expectedBytes && match.groupValues[5] == expectedHash
        }
        return flags
    }

    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    fun safeValues(values: Map<String, Any?>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        for ((field, expected) in mapOf("plannedRuntimeSqlFiles" to 236, "moduleCount" to 52, "moduleSqlCount" to 0)) {
            if (values[field] !is Int || values[field] != expected) return emptyMap()
            result[field] = expected
        }
        val staged = values["stagedRuntimeSqlFiles"]
        if (staged !is Int || staged !in 0..236) return emptyMap()
        result["stagedRuntimeSqlFiles"] = staged
        for (field in boundaryFields + listOf("freshSeedBaselinePassed", "compiledUpdaterPassed", "runtimeSqlAttempted", "runtimeSqlCompletionQualified")) {
            val value = values[field]
            if (value != null && value !is Boolean) return emptyMap()
            result[field] = value ?: false
        }
        if (result["compiledUpdaterPassed"] == true && (staged != 236 || result["freshSeedBaselinePassed"] != true ||
            result["runtimeSqlAttempted"] != true || result["runtimeSqlCompletionQualified"] != true || boundaryFields.any { result[it] != true })) return emptyMap()
        return result
    }
}
