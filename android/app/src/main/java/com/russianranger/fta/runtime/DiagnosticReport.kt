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
            .put("worldserverQualified", false)
        val scopeFile = File(context.filesDir, "runtime/diagnostic-scope.txt")
        val scope = if (scopeFile.isFile && scopeFile.length() < 32) scopeFile.readText() else "database"
        result.put("requestedDiagnostic", if (scope == "auth-updater") scope else "database")
        result.put("scope", if (scope == "auth-updater") "disposable-native-auth-updater-diagnostic" else "disposable-native-database-diagnostic")
        val attemptFile = File(context.filesDir, "runtime/diagnostic-attempt.id")
        val currentAttempt = attemptFile.takeIf { it.isFile && it.length() <= 64 }?.readText()?.trim()
            ?.takeIf { NativeInvocationJournal.validAttemptId(it) }
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
            val executableHash = auth.optString("authExecutableSha256")
            if (executableHash.matches(Regex("[a-f0-9]{64}"))) safe.put("authExecutableSha256", executableHash)
            result.put("authUpdaterDiagnostic", safe)
        }
        val helper = File(context.applicationInfo.nativeLibraryDir, "libfta_process.so")
        if (helper.isFile) result.put("processSupervisorSha256", hash(helper))
        val rawManifest = runCatching { context.assets.open(RuntimeManifest.FILE_NAME).use { bounded(it, RuntimeManifest.MAX_MANIFEST_BYTES.toInt()) } }.getOrNull()
        if (rawManifest != null) {
            // Parsing rejects unknown keys/unsafe strings before any manifest data is shared.
            RuntimeManifest.parse(rawManifest.toString(Charsets.UTF_8))
            result.put("providerManifestSha256", sha(rawManifest))
            result.put("providerManifest", JSONObject(rawManifest.toString(Charsets.UTF_8)))
        } else result.put("providerManifest", JSONObject.NULL)

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
        val sessions = File(context.filesDir, "runtime/database-diagnostics").listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("g-[0-9a-f-]{36}")) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val matching = if (currentAttempt == null) null else sessions.firstNotNullOfOrNull { directory ->
            NativeInvocationJournal.read(File(directory, "native-invocations.properties"), currentAttempt)?.let { directory to it }
        }
        val session = matching?.first
        val journal = matching?.second
        val invocations = journal?.first.orEmpty()
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
        val priority = (invocations.filter { it.role == "auth-server" }.asReversed() +
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
