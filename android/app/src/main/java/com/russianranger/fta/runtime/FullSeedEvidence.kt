package com.russianranger.fta.runtime

/** Only aggregate booleans/counts are shareable. Metadata rows remain in the private native logs. */
internal object FullSeedEvidence {
    val validationFields = listOf("tableInventoryMatches", "triggerInventoryMatches", "routinesMatch", "eventsMatch", "receiptBaselineMatches")
    val counts = mapOf("plannedTableCount" to 563, "importedTableCount" to 563,
        "plannedTriggerCount" to 1, "importedTriggerCount" to 1)

    fun parseFlags(output: String, fields: List<String> = validationFields): Map<String, Boolean> {
        require(output.length <= 1024) { "Full seed metadata result exceeds its limit" }
        val values = linkedMapOf<String, Boolean>()
        output.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split('=')
            require(parts.size == 2 && parts[0] in fields && parts[1] in setOf("0", "1") && parts[0] !in values) { "Unexpected full seed metadata result" }
            values[parts[0]] = parts[1] == "1"
        }
        require(values.keys == fields.toSet()) { "Incomplete full seed metadata result" }
        return values
    }

    fun parseValidation(output: String, schema: FullSeedSchema): Map<String, Boolean> {
        require(output.length <= 1024) { "Full seed validation output exceeds its limit" }
        val lines = output.lineSequence().filter { it.isNotBlank() }.toList()
        val receipts = lines.filter { it.startsWith("FTA_RECEIPTS=") }
        require(receipts.size == 1) { "Full seed receipt summary is unavailable" }
        val match = Regex("FTA_RECEIPTS=([0-9]{1,5}):([0-9]{1,8}):([0-9]{1,8}):([0-9]{1,5}):([a-f0-9]{64})")
            .matchEntire(receipts.single()) ?: throw IllegalArgumentException("Unexpected full seed receipt summary")
        val count = match.groupValues[1].toInt()
        val expectedBytes = match.groupValues[2].toInt()
        val joinedBytes = match.groupValues[3].toInt()
        val invalid = match.groupValues[4].toInt()
        require(count in 1..10000 && expectedBytes in 1..1048576 && expectedBytes == joinedBytes && invalid == 0) {
            "Full seed receipt metadata is invalid or truncated"
        }
        return parseFlags(lines.filterNot { it.startsWith("FTA_RECEIPTS=") }.joinToString("\n"), validationFields.dropLast(1)) +
            ("receiptBaselineMatches" to (count == schema.receiptCount && expectedBytes == schema.receiptBytes && match.groupValues[5] == schema.receiptSha256))
    }

    fun receiptSql(schema: FullSeedSchema): String {
        require(schema.name in FullSeedStore.SCHEMAS)
        val row = "CONCAT(`name`,CHAR(9),`hash`,CHAR(9),`state`,CHAR(10))"
        val joined = "GROUP_CONCAT($row ORDER BY BINARY `name` SEPARATOR '')"
        val invalid = "`name` IS NULL OR BINARY `name` NOT REGEXP '^[A-Za-z0-9_.+-]{1,240}[.]sql$' " +
            "OR `hash` IS NULL OR BINARY `hash` NOT REGEXP '^[A-Fa-f0-9]{40}$' " +
            "OR `state` IS NULL OR BINARY `state` NOT IN ('RELEASED','ARCHIVED','CUSTOM','MODULE','PENDING')"
        return "SET SESSION group_concat_max_len=1048576;\n" +
            "SELECT CONCAT('FTA_RECEIPTS=',COUNT(*),':'," +
            "COALESCE(SUM(OCTET_LENGTH($row)),0),':',COALESCE(OCTET_LENGTH($joined),0),':'," +
            "COALESCE(SUM($invalid),0),':',COALESCE(SHA2($joined,256),'')) FROM `${schema.name}`.`updates`;"
    }

    /** Stored JSON types must be exact; strings, stale partial passes and extra fields are not forwarded. */
    fun safeValues(values: Map<String, Any?>): Map<String, Any> {
        val result = linkedMapOf<String, Any>()
        for ((field, maximum) in counts) {
            val number = values[field]
            if (number !is Int || number !in 0..maximum) return emptyMap()
            result[field] = number
        }
        if (result["plannedTableCount"] != 563 || result["plannedTriggerCount"] != 1) return emptyMap()
        for (field in listOf("fullSeedImported", "validationPassed")) {
            val value = values[field]
            if (value !is Boolean) return emptyMap()
            result[field] = value
        }
        if (result["fullSeedImported"] == true && (result["importedTableCount"] != 563 || result["importedTriggerCount"] != 1)) return emptyMap()
        if (result["validationPassed"] == true && result["fullSeedImported"] != true) return emptyMap()
        return result
    }

    fun qualified(seedValidated: Boolean, state: String?, invocations: List<NativeInvocationEvidence>, dropped: Int): Boolean =
        seedValidated && state in setOf("FULL_SEED_DIAGNOSTIC_PASSED", WorldUpdaterEvidence.PASSED) && dropped == 0 &&
            invocations.any { it.role == "database-server" && it.log == "server" && it.readyObserved &&
                it.sqlShutdownRequested && it.cleanShutdownObserved && it.outcome == "exited" && it.exitCode == 0 } &&
            invocations.filter { it.stage == "SEED_IMPORT" }.let { imports ->
                imports.size == 564 && imports.all { it.role == "database-client" && it.outcome == "exited" && it.exitCode == 0 &&
                    !it.timedOut && !it.cancelled && it.validationFailure == null }
            }

    fun inventorySql(schema: FullSeedSchema, files: List<FullSeedEntry>): String {
        require(schema.name in FullSeedStore.SCHEMAS)
        val tableNames = files.filter { it.schema == schema.name && it.kind == "table" }.map { it.name }
        val triggers = schema.triggerNames
        fun literals(names: List<String>): String {
            require(names.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) })
            return names.joinToString(",") { "'$it'" }
        }
        val triggerMatch = if (triggers.isEmpty()) "COUNT(*)=0" else
            "COUNT(*)=${triggers.size} AND COALESCE(SUM(BINARY trigger_name IN (${literals(triggers)})),0)=${triggers.size}"
        return """
            SELECT CONCAT('tableInventoryMatches=', COUNT(*)=${schema.tableCount} AND
                COALESCE(SUM(BINARY table_name IN (${literals(tableNames)}) AND table_type='BASE TABLE'),0)=${schema.tableCount})
                FROM information_schema.tables WHERE table_schema='${schema.name}';
            SELECT CONCAT('triggerInventoryMatches=', $triggerMatch)
                FROM information_schema.triggers WHERE trigger_schema='${schema.name}';
            SELECT CONCAT('routinesMatch=',COUNT(*)=${schema.routineCount}) FROM information_schema.routines WHERE routine_schema='${schema.name}';
            SELECT CONCAT('eventsMatch=',COUNT(*)=${schema.eventCount}) FROM information_schema.events WHERE event_schema='${schema.name}';
        """.trimIndent()
    }
}
