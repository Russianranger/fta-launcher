package com.russianranger.fta.runtime

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** A bounded shareable report. Native output is allowlisted, never copied verbatim. */
object DiagnosticReport {
    fun create(context: Context): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val result = JSONObject()
            .put("formatVersion", 1)
            .put("createdAtEpochMs", System.currentTimeMillis())
            .put("scope", "disposable-native-database-diagnostic")
            .put("packageName", context.packageName)
            .put("appVersionName", info.versionName)
            .put("appVersionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong())
            .put("apkSha256", hash(File(context.applicationInfo.sourceDir)))
            .put("platform", JSONObject()
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("androidRelease", Build.VERSION.RELEASE)
                .put("manufacturer", Build.MANUFACTURER.take(80))
                .put("model", Build.MODEL.take(80))
                .put("supportedAbis", JSONArray(Build.SUPPORTED_ABIS.toList())))
            .put("fullSeedImported", false)
            .put("seedCompatibilityQualified", false)
            .put("authserverQualified", false)
            .put("compiledUpdaterQualified", false)
            .put("worldserverQualified", false)
        val scopeFile = File(context.filesDir, "runtime/diagnostic-scope.txt")
        val scope = if (scopeFile.isFile && scopeFile.length() < 32) scopeFile.readText() else "database"
        val gameData = runCatching { GameDataImport.store(context).export() }.getOrElse { mapOf("available" to false, "worldReady" to false) }
        result.put("gameDataDiagnostic", JSONObject(gameData))
        result.put("requestedDiagnostic", if (scope in setOf("game-data", "auth-updater", "full-seed", "compiled-updater", "database-lifecycle", "runtime-recovery", "realm-storage", "realm-backup", "local-auth", "local-service")) scope else "database")
        result.put("scope", when (scope) { "game-data" -> "inert-game-data-preflight"; "local-service" -> "retained-realm-local-service"; "local-auth" -> "retained-realm-local-auth"; "realm-backup" -> "persistent-realm-backup"; "realm-storage" -> "persistent-realm-storage"; "runtime-recovery" -> "disposable-runtime-recovery-diagnostic"; "database-lifecycle" -> "disposable-native-database-lifecycle-diagnostic"; "auth-updater" -> "disposable-native-auth-updater-diagnostic"; "full-seed" -> "disposable-native-full-seed-diagnostic"; "compiled-updater" -> "disposable-native-compiled-updater-diagnostic"; else -> "disposable-native-database-diagnostic" })
        val attemptFile = File(context.filesDir, "runtime/diagnostic-attempt.id")
        val currentAttempt = attemptFile.takeIf { it.isFile && it.length() <= 64 }?.readText()?.trim()
            ?.takeIf { NativeInvocationJournal.validAttemptId(it) }
        if (scope == "game-data" && currentAttempt != null) result.put("gameDataOperation",
            JSONObject(runCatching { GameDataDiagnostics.export(context, currentAttempt) }.getOrElse { mapOf("available" to false) }))
        val authFile = File(context.filesDir, "runtime/auth-diagnostic.json")
        val auth = if (authFile.isFile && authFile.length() <= 4096) runCatching { JSONObject(authFile.readText()) }.getOrNull() else null
        if (currentAttempt != null && auth != null && auth.optString("attemptId") == currentAttempt &&
            auth.optString("archiveSha256").matches(Regex("[a-f0-9]{64}"))) {
            val safe = JSONObject().put("attemptId", currentAttempt).put("archiveSha256", auth.getString("archiveSha256"))
                .put("plannedTableCount", auth.optInt("plannedTableCount").coerceIn(0, 27))
                .put("importedTableCount", auth.optInt("importedTableCount").coerceIn(0, 27))
                .put("runtimeAuthUpdates", auth.optInt("runtimeAuthUpdates").coerceIn(0, 1))
                .put("authTcpListener", false).put("dryRunPassed", auth.optBoolean("dryRunPassed"))
                .put("startedAtEpochMs", auth.optLong("startedAtEpochMs"))
                .put("completedAtEpochMs", auth.optLong("completedAtEpochMs"))
            RealmEligibilityEvidence.forward(RealmEligibilityEvidence.fields.associateWith { auth.opt(it) })
                .forEach { (field, observed) -> safe.put(field, observed) }
            val executableHash = auth.optString("authExecutableSha256")
            if (executableHash.matches(Regex("[a-f0-9]{64}"))) safe.put("authExecutableSha256", executableHash)
            result.put("authUpdaterDiagnostic", safe)
        }
        val fullSeedFile = File(context.filesDir, "runtime/full-seed-diagnostic.json")
        val fullSeed = if (scope in setOf("full-seed", "compiled-updater") && fullSeedFile.isFile && fullSeedFile.length() <= 8192)
            runCatching { JSONObject(fullSeedFile.readText()) }.getOrNull() else null
        var seedValidated = false
        if (currentAttempt != null && fullSeed != null && fullSeed.opt("attemptId") == currentAttempt &&
            fullSeed.opt("archiveSha256") == com.russianranger.fta.Inputs.database.sha256 && fullSeed.opt("runtimeSqlApplied") == false) {
            val fields = (FullSeedEvidence.counts.keys + setOf("fullSeedImported", "validationPassed"))
                .associateWith { fullSeed.opt(it) }
            val safeValues = FullSeedEvidence.safeValues(fields)
            if (safeValues.isNotEmpty()) {
                val safe = JSONObject(safeValues).put("attemptId", currentAttempt)
                    .put("archiveSha256", com.russianranger.fta.Inputs.database.sha256).put("runtimeSqlApplied", false)
                    .put("baselineBeforeCompiledUpdater", scope == "compiled-updater")
                val safeSchemas = JSONObject()
                val schemas = fullSeed.optJSONObject("schemas")
                var schemaPasses = 0
                FullSeedStore.SCHEMAS.forEach { schema ->
                    val value = schemas?.optJSONObject(schema)
                    val flags = FullSeedEvidence.validationFields.associateWith { value?.opt(it) }
                    if (flags.values.all { it is Boolean }) {
                        safeSchemas.put(schema, JSONObject(flags))
                        if (flags.values.all { it == true }) schemaPasses++
                    }
                }
                safe.put("schemas", safeSchemas)
                for (field in listOf("startedAtEpochMs", "completedAtEpochMs")) {
                    val value = fullSeed.opt(field)
                    if (value is Number && (value is Int || value is Long) && value.toLong() >= 0) safe.put(field, value)
                }
                seedValidated = safeValues["validationPassed"] == true && schemaPasses == 3
                result.put("fullSeedDiagnostic", safe)
                result.put("fullSeedImported", seedValidated)
            }
        }
        val updaterFile = File(context.filesDir, "runtime/world-updater-diagnostic.json")
        val updater = if (scope == "compiled-updater" && updaterFile.isFile && updaterFile.length() <= 16384)
            runCatching { JSONObject(updaterFile.readText()) }.getOrNull() else null
        var updaterValidated = false
        if (currentAttempt != null && updater != null && updater.opt("attemptId") == currentAttempt &&
            updater.opt("archiveSha256") == com.russianranger.fta.Inputs.database.sha256 &&
            updater.opt("sourceArchiveSha256") == com.russianranger.fta.Inputs.source.sha256 &&
            updater.opt("worldStartupQualified") == false && updater.opt("worldTcpListener") == false) {
            val fields = WorldUpdaterEvidence.boundaryFields + listOf("plannedRuntimeSqlFiles", "stagedRuntimeSqlFiles", "moduleCount", "moduleSqlCount",
                "freshSeedBaselinePassed", "compiledUpdaterPassed", "runtimeSqlAttempted", "runtimeSqlCompletionQualified")
            val safeValues = WorldUpdaterEvidence.safeValues(fields.associateWith { updater.opt(it) })
            val hashes = listOf("sourceTreeSha256", "worldExecutableSha256", "worldUpdaterLockSha256")
            val profile = updater.opt("sourceProfile")
            if (safeValues.isNotEmpty() && hashes.all { updater.opt(it) is String && updater.getString(it).matches(Regex("[a-f0-9]{64}")) } &&
                profile is String && profile.matches(Regex("android-arm64-world-v[0-9]{1,3}"))) {
                val safe = JSONObject(safeValues).put("attemptId", currentAttempt).put("sourceProfile", profile)
                    .put("archiveSha256", com.russianranger.fta.Inputs.database.sha256)
                    .put("sourceArchiveSha256", com.russianranger.fta.Inputs.source.sha256)
                    .put("worldStartupQualified", false).put("worldTcpListener", false)
                hashes.forEach { safe.put(it, updater.getString(it)) }
                val schemas = updater.optJSONObject("schemas")
                val safeSchemas = JSONObject()
                var passes = 0
                FullSeedStore.SCHEMAS.forEach { name ->
                    val value = schemas?.optJSONObject(name)
                    val flags = WorldUpdaterEvidence.validationFields.associateWith { value?.opt(it) }
                    if (flags.values.all { it is Boolean }) {
                        safeSchemas.put(name, JSONObject(flags))
                        if (flags.values.all { it == true }) passes++
                    }
                }
                safe.put("schemas", safeSchemas)
                for (field in listOf("startedAtEpochMs", "completedAtEpochMs")) {
                    val value = updater.opt(field)
                    if ((value is Int || value is Long) && (value as Number).toLong() >= 0) safe.put(field, value)
                }
                updaterValidated = seedValidated && passes == 3 && safeValues["compiledUpdaterPassed"] == true
                result.put("compiledUpdaterDiagnostic", safe)
            }
        }
        val helper = File(context.applicationInfo.nativeLibraryDir, "libfta_process.so")
        if (helper.isFile) result.put("processSupervisorSha256", hash(helper))
        val rawManifest = runCatching { context.assets.open(RuntimeManifest.FILE_NAME).use { bounded(it, RuntimeManifest.MAX_MANIFEST_BYTES.toInt()) } }.getOrNull()
        if (rawManifest != null) {
            // Parsing rejects unknown keys/unsafe strings before any manifest data is shared.
            val parsed = RuntimeManifest.parse(rawManifest.toString(Charsets.UTF_8))
            updaterValidated = updaterValidated && parsed.worldBuild?.let { build ->
                updater != null && updater.opt("worldExecutableSha256") == build.worldserverSha256 &&
                    updater.opt("worldUpdaterLockSha256") == build.compiledUpdaterLockSha256 &&
                    updater.opt("sourceProfile") == build.portProfile && updater.opt("sourceTreeSha256") == build.sourceTreeSha256 &&
                    updater.opt("sourceArchiveSha256") == build.sourceArchiveSha256
            } == true
            result.put("providerManifestSha256", sha(rawManifest))
            result.put("providerManifest", JSONObject(rawManifest.toString(Charsets.UTF_8)))
            // Observational hashes are exported separately and never participate in qualification.
            if (scope == "compiled-updater" && currentAttempt != null && parsed.worldBuild != null) {
                val columns = File(context.filesDir, "runtime/world-column-diagnostic.json")
                if (columns.isFile && columns.length() <= WorldColumnEvidence.MAX_RECEIPT_BYTES) {
                    val columnEvidence = runCatching {
                        val lock = WorldUpdaterMetadata.load(context)
                        val build = parsed.worldBuild
                        require(lock.sourceTreeSha256 == build.sourceTreeSha256 && lock.sourceProfile == build.portProfile)
                        val schema = lock.schemas.single { it.name == "acore_world" }
                        val seedTables = FullSeedMetadata.load(context).files
                            .filter { it.schema == "acore_world" && it.kind == "table" }.map { it.name }.sorted()
                        WorldColumnEvidence.safeReceipt(columns.readText(), currentAttempt, build.worldserverSha256,
                            build.compiledUpdaterLockSha256, build.sourceTreeSha256, schema, seedTables)
                    }.getOrNull()
                    columnEvidence?.let {
                        val safe = JSONObject(it)
                        if (JSONObject().put("worldColumnDiagnostic", safe).toString(2).toByteArray().size <= WorldColumnEvidence.MAX_EXPORT_BYTES)
                            result.put("worldColumnDiagnostic", safe)
                    }
                }
            }
        } else { result.put("providerManifest", JSONObject.NULL); updaterValidated = false }

        val statusAttempt = File(context.filesDir, "runtime/diagnostic-status-attempt.id")
            .takeIf { it.isFile && it.length() <= 64 }?.readText()?.trim()
        val state = File(context.filesDir, "runtime/diagnostic-status.txt")
        val lines = if (currentAttempt != null && statusAttempt == currentAttempt && state.isFile && state.length() <= 8192)
            state.readLines() else emptyList()
        result.put("lastDiagnostic", JSONObject()
            .put("attemptId", currentAttempt ?: JSONObject.NULL)
            .put("state", lines.getOrNull(0) ?: "NO_CURRENT_RESULT")
            .put("updatedAtEpochMs", lines.getOrNull(1)?.toLongOrNull() ?: JSONObject.NULL)
            .put("summary", if (lines.size >= 3) lines.drop(2).joinToString("\n").take(4096)
                else "No status result was recorded for the current attempt."))
        if (scope == "game-data") {
            result.put("gameDataImportQualified", lines.firstOrNull() == GameDataImport.PASSED &&
                gameData["preflightPassed"] == true && gameData["activeCatalogMatchesReport"] == true)
                .put("privacy", "Only bounded asset paths, counts, fingerprints, progress, memory/storage measurements, fixed application frame names and Android exit codes/times are exported; no game bytes, source URI, process IDs, raw traces, exception messages, database rows or credentials.")
            return result.toString(2)
        }
        if (scope == "local-service" && currentAttempt != null) {
            val proof = LocalServiceRuntime.export(context, currentAttempt, lines.firstOrNull())
            result.put("localServiceDiagnostic", JSONObject(proof)).put("localServiceQualified", proof["qualified"] == true)
                .put("localServiceRestartQualified", proof["restartQualified"] == true)
                .put("screenOffLocalServiceQualified", proof["screenOffQualified"] == true)
                .put("privacy", "No account names, credentials, rows, SQL, private paths or native log text are exported.")
            return result.toString(2)
        }
        if (scope == "local-auth" && currentAttempt != null) {
            val proof = LocalAuthDiagnostic.export(context, currentAttempt, lines.firstOrNull())
            result.put("localAuthDiagnostic", JSONObject(proof)).put("localAuthQualified", proof["qualified"] == true)
                .put("privacy", "No account names, credentials, rows, SQL, private paths or native log text are exported.")
            return result.toString(2)
        }
        if (scope == "realm-backup" && currentAttempt != null) {
            val proof = RealmBackupDiagnostic.export(context, currentAttempt, lines.firstOrNull())
            result.put("realmBackupDiagnostic", JSONObject(proof)).put("realmBackupQualified", proof["qualified"] == true)
                .put("privacy", "This diagnostic omits database contents, file inventories, credentials, paths and process identities. The separately exported backup contains private database data and credentials; keep it private.")
            return result.toString(2)
        }
        if (scope == "realm-storage" && currentAttempt != null) {
            val proof = RealmStorageDiagnostic.export(context, currentAttempt, lines.firstOrNull())
            result.put("realmStorageDiagnostic", JSONObject(proof)).put("persistentRealmStorageQualified", proof["qualified"] == true)
                .put("privacy", "SQL, rows, credentials, paths and process identities are omitted. Persistent realm storage is separate from disposable diagnostics and is never pruned by them.")
            return result.toString(2)
        }
        val sessions = File(context.filesDir, "runtime/database-diagnostics").listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("g-[0-9a-f-]{36}")) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val matching = if (currentAttempt == null) null else sessions.firstNotNullOfOrNull { directory ->
            NativeInvocationJournal.read(File(directory, "native-invocations.properties"), currentAttempt)?.let { directory to it }
        }
        val session = matching?.first
        val journal = matching?.second
        val invocations = journal?.first.orEmpty()
        if (scope == "runtime-recovery" && currentAttempt != null) {
            val file = File(context.filesDir, "runtime/runtime-recovery.json")
            val raw = if (file.isFile && file.length() <= 4096) runCatching { JSONObject(file.readText()) }.getOrNull() else null
            val fields = RuntimeRecoveryProtocol.fields
            val valid = raw?.opt("formatVersion") == 1 && raw.opt("attemptId") == currentAttempt && raw.opt("seedImported") == false &&
                fields.all { raw.opt(it) is Boolean }
            val flags = if (valid) fields.associateWith { raw!!.getBoolean(it) } else emptyMap()
            val proof = RuntimeRecoveryEvidence.read(context.filesDir, currentAttempt, flags)
            val passed = proof.qualified && lines.firstOrNull() == RuntimeRecoveryProtocol.PASSED
            result.put("runtimeRecoveryDiagnostic", JSONObject(flags as Map<*, *>).put("attemptId", currentAttempt)
                .put("phases", JSONObject(proof.phases)).put("seedImported", false)
                .put("nativeExitSignalsObserved", false))
                .put("runtimeRecoveryQualified", passed).put("serviceProcessDeathQualified", passed)
                .put("stopActionQualified", passed)
                .put("privacy", "Raw SQL, rows, credentials, paths, PIDs and process start times are omitted. Phase journals are bound to this attempt. Historical running entries in the killed worker are interpreted only with independent termination evidence.")
            return result.toString(2)
        }
        if (scope == "database-lifecycle" && currentAttempt != null) {
            val file = File(context.filesDir, "runtime/database-lifecycle.json")
            val raw = if (file.isFile && file.length() <= 4096) runCatching { JSONObject(file.readText()) }.getOrNull() else null
            val flags = if (raw?.opt("formatVersion") == 1 && raw.opt("attemptId") == currentAttempt &&
                raw.opt("seedImported") == false && raw.opt("serviceProcessDeathQualified") == false)
                DatabaseLifecycleProbe.safeValues(DatabaseLifecycleProbe.fields.associateWith { raw.opt(it) }) else emptyMap()
            if (flags.isNotEmpty()) result.put("databaseLifecycleDiagnostic", JSONObject(flags as Map<*, *>)
                .put("attemptId", currentAttempt).put("seedImported", false).put("serviceProcessDeathQualified", false))
            val qualified = DatabaseLifecycleProbe.qualified(flags, lines.firstOrNull(), invocations, journal?.second ?: 0)
            result.put("databaseLifecycleQualified", qualified)
                .put("screenOffLifecycleQualified", qualified && flags["screenOffObserved"] == true && flags["screenOffHealth"] == true)
                .put("serviceProcessDeathQualified", false)
        }
        result.put("seedCompatibilityQualified", FullSeedEvidence.qualified(seedValidated,
            lines.firstOrNull(), invocations, journal?.second ?: 0))
        result.put("compiledUpdaterQualified", WorldUpdaterEvidence.qualified(updaterValidated,
            lines.firstOrNull(), invocations, journal?.second ?: 0))
        result.put("nativeInvocationEvidence", JSONObject()
            .put("formatVersion", 1)
            .put("available", journal != null)
            .put("attemptId", currentAttempt ?: JSONObject.NULL)
            .put("note", if (journal != null) "Evidence is bound to the current diagnostic attempt."
                else "No structured native evidence was recorded for the current attempt; previous attempt logs are omitted.")
            .put("recordCount", invocations.size)
            .put("droppedCount", journal?.second ?: 0)
            .put("truncated", (journal?.second ?: 0) > 0))
        val commands = JSONArray()
        invocations.forEach { value ->
            commands.put(JSONObject().put("id", value.id).put("log", value.log)
                .put("role", value.role).put("stage", value.stage).put("outcome", value.outcome)
                .put("startedAtEpochMs", value.startedAtEpochMs).put("durationMs", value.durationMs)
                .put("timeoutMs", value.timeoutMs ?: JSONObject.NULL)
                .put("exitCode", value.exitCode ?: JSONObject.NULL)
                .put("terminationExitCode", value.terminationExitCode ?: JSONObject.NULL)
                .put("timedOut", value.timedOut).put("cancelled", value.cancelled)
                .put("stdoutBytes", value.stdoutBytes).put("stderrBytes", value.stderrBytes)
                .put("validationFailure", value.validationFailure ?: JSONObject.NULL)
                .put("databaseReadyObserved", value.readyObserved)
                .put("sqlShutdownRequested", value.sqlShutdownRequested)
                .put("cleanShutdownObserved", value.cleanShutdownObserved))
        }
        result.put("nativeInvocations", commands)
        val errors = JSONArray()
        val availableLogs = session?.listFiles()?.filter { file ->
            file.isFile && file.name.matches(Regex("(?:tool-[0-9]+|server)\\.(?:err|out)"))
        }.orEmpty().associateBy { it.name }
        val failed = invocations.filter { value -> value.validationFailure != null ||
            value.exitCode?.let { it != 0 } == true || value.timedOut ||
            value.outcome in setOf("start_failed", "wait_failed", "supervisor_failed") }
        // Keep the actual auth failure and persistent daemon ahead of successful tool chatter.
        val priority = (invocations.filter { it.role in setOf("auth-server", "world-server") }.asReversed() +
            invocations.filter { it.log == "server" } + failed.asReversed() + invocations.asReversed())
            .map { it.log }.distinct()
        val orderedLogs = (priority.flatMap { listOf("$it.err", "$it.out") } + availableLogs.keys.sorted()).distinct()
        orderedLogs.mapNotNull { availableLogs[it] }.take(64).forEach { file ->
            val summaries = DiagnosticSanitizer.summarize(tail(file, DiagnosticSanitizer.MAX_INPUT_BYTES))
            if (summaries.isNotEmpty()) {
                val entry = JSONObject().put("log", file.name).put("summaries", JSONArray(summaries))
                invocations.lastOrNull { file.name == "${it.log}.out" || file.name == "${it.log}.err" }?.let {
                    entry.put("invocationId", it.id).put("role", it.role).put("stage", it.stage)
                }
                errors.put(entry)
            }
        }
        result.put("nativeDiagnostics", errors)
        result.put("privacy", "Raw SQL, query rows, native output, credentials, private paths and selected source URIs are omitted.")
        return result.toString(2)
    }

    private fun tail(file: File, limit: Int): String = RandomAccessFile(file, "r").use { input ->
        val size = minOf(input.length(), limit.toLong()).toInt()
        input.seek(input.length() - size)
        val bytes = ByteArray(size)
        input.readFully(bytes)
        bytes.toString(Charsets.UTF_8)
    }
    private fun bounded(input: InputStream, limit: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return result.toByteArray()
            check(result.size() + read <= limit)
            result.write(buffer, 0, read)
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
