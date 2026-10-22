package com.russianranger.fta.runtime

import android.content.Context

/** This lock is part of the signed, byte-verified private runtime; it contains no SQL. */
internal object WorldUpdaterMetadata {
    const val PATH = "data/metadata/world-updater-lock.json"
    fun load(context: Context): WorldUpdaterLock {
        val bytes = context.assets.open("runtime/$PATH").use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 256 * 1024)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        return parse(bytes.toString(Charsets.UTF_8))
    }

    fun parse(json: String): WorldUpdaterLock {
        require(json.toByteArray().size <= 256 * 1024)
        val value = StrictJson(json).parse().objectValue()
        value.onlyKeys("formatVersion", "purpose", "archiveSha256", "archiveSize", "requiredProviderVersion",
            "sourceArchiveSha256", "sourceProfile", "sourceTreeSha256", "moduleCount", "moduleSqlCount",
            "moduleDirectories", "moduleSqlDirectories", "sourceAudit", "sourceDirectory", "policy", "runtimeSqlFiles",
            "includeDirectories", "schemas", "canonicalReceipts", "canonicalColumns", "canonicalReleaseReceipts",
            "aggregateConcatLimitBytes", "expectedResultEvidence", "nativeBoundary", "constraints")
        require(value.getInt("formatVersion") == 1 && value.getString("purpose") == "fresh-disposable-compiled-world-updater-only" &&
            value.getString("requiredProviderVersion") == "12.3.2" && value.getInt("moduleCount") == 52 &&
            value.getInt("moduleSqlCount") == 0 && value.getString("sourceDirectory") == "Data" &&
            value.getInt("aggregateConcatLimitBytes") == 1048576)
        val policy = value.getJSONObject("policy")
        require(policy.getInt("enableDatabases") == 7 && policy.getBoolean("redundancy") &&
            policy.getBoolean("allowRehash") && !policy.getBoolean("archivedRedundancy") &&
            policy.getInt("cleanDeadRefMaxCount") == 3 && !policy.getBoolean("dryRun"))
        val boundary = value.getJSONObject("nativeBoundary")
        require(boundary.getString("mode") == "normal" && boundary.getInt("exitCode") == 1 &&
            strings(boundary.getJSONArray("databaseOrder")) == listOf("Auth", "Character", "World") &&
            boundary.getString("preparedCompletionMarker") == "Loading World Information..." &&
            boundary.getString("expectedStopMarker") == "Failed to find map files for starting areas")
        val result = WorldUpdaterLock(value.getString("archiveSha256"), value.getLong("archiveSize"),
            value.getString("sourceArchiveSha256"), value.getString("sourceProfile"), value.getString("sourceTreeSha256"),
            strings(value.getJSONArray("moduleDirectories")), strings(value.getJSONArray("moduleSqlDirectories")),
            objects(value.getJSONArray("runtimeSqlFiles")).map { WorldSqlEntry(it.getString("path"), it.getString("targetPath"),
                it.getString("schema"), it.getLong("size"), it.getString("sha256"), it.getString("sha1")) },
            objects(value.getJSONArray("includeDirectories")).map { WorldIncludeDirectory(it.getString("path"), it.getString("state"), it.getString("schema")) },
            objects(value.getJSONArray("schemas")).map { WorldUpdaterSchema(it.getString("name"), strings(it.getJSONArray("tableNames")),
                strings(it.getJSONArray("triggerNames")), it.getInt("routineCount"), it.getInt("eventCount"),
                it.getInt("receiptCount"), it.getInt("receiptBytes"), it.getString("receiptSha256"),
                it.getInt("releaseReceiptCount"), it.getInt("releaseReceiptBytes"), it.getString("releaseReceiptSha256"),
                it.getInt("columnCount"), it.getInt("columnBytes"), it.getString("columnSha256")) },
            objects(boundary.getJSONArray("updatePhases")).map { WorldUpdatePhase(it.getString("database"),
                it.getString("startMarker"), it.getString("completionMarker"), it.getInt("orphanedReceiptWarnings")) })
        WorldUpdaterStore().validate(result)
        return result
    }
    private fun strings(array: List<Any>) = array.map { it.stringValue() }
    private fun objects(array: List<Any>) = array.map { it.objectValue() }
    private fun Map<String, Any>.getString(key: String) = required(key).stringValue()
    private fun Map<String, Any>.getLong(key: String) = required(key).integerValue()
    private fun Map<String, Any>.getInt(key: String) = getLong(key).also { require(it in Int.MIN_VALUE..Int.MAX_VALUE) }.toInt()
    private fun Map<String, Any>.getBoolean(key: String) = required(key).booleanValue()
    private fun Map<String, Any>.getJSONObject(key: String) = required(key).objectValue()
    private fun Map<String, Any>.getJSONArray(key: String) = required(key).arrayValue()
}
