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
            .put("seedImported", false)
            .put("seedCompatibilityQualified", false)
            .put("authserverQualified", false)
            .put("worldserverQualified", false)
        val helper = File(context.applicationInfo.nativeLibraryDir, "libfta_process.so")
        if (helper.isFile) result.put("processSupervisorSha256", hash(helper))
        val rawManifest = runCatching { context.assets.open(RuntimeManifest.FILE_NAME).use { bounded(it, RuntimeManifest.MAX_MANIFEST_BYTES.toInt()) } }.getOrNull()
        if (rawManifest != null) {
            // Parsing rejects unknown keys/unsafe strings before any manifest data is shared.
            RuntimeManifest.parse(rawManifest.toString(Charsets.UTF_8))
            result.put("providerManifestSha256", sha(rawManifest))
            result.put("providerManifest", JSONObject(rawManifest.toString(Charsets.UTF_8)))
        } else result.put("providerManifest", JSONObject.NULL)

        val state = File(context.filesDir, "runtime/diagnostic-status.txt")
        val lines = if (state.isFile && state.length() <= 8192) state.readLines() else emptyList()
        result.put("lastDiagnostic", JSONObject()
            .put("state", lines.getOrNull(0) ?: "NOT_RUN")
            .put("updatedAtEpochMs", lines.getOrNull(1)?.toLongOrNull() ?: JSONObject.NULL)
            .put("summary", lines.drop(2).joinToString("\n").take(4096)))
        val sessions = File(context.filesDir, "runtime/database-diagnostics").listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("g-[0-9a-f-]{36}")) }
            ?.sortedByDescending { it.lastModified() }.orEmpty()
        val errors = JSONArray()
        sessions.firstOrNull()?.listFiles()?.filter { file ->
            file.isFile && file.name.matches(Regex("(?:tool-[0-9]+|server)\\.(?:err|out)"))
        }?.sortedBy { it.name }?.take(64)?.forEach { file ->
            val summaries = DiagnosticSanitizer.summarize(tail(file, DiagnosticSanitizer.MAX_INPUT_BYTES))
            if (summaries.isNotEmpty()) errors.put(JSONObject().put("log", file.name).put("summaries", JSONArray(summaries)))
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
