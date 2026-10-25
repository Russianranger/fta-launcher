package com.russianranger.fta.client

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

object ClientImportReport {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "client-import-report.json"))
    fun write(context: Context, report: JSONObject) {
        val atomic = file(context); val output = atomic.startWrite()
        try { output.write(report.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    val states = setOf("planning", "copy", "resume-verify", "validate", "verify", "complete", "stopped", "failed")
    val reasons = setOf("cancelled", "timeout", "busy", "provider-error", "invalid-source", "invalid-plan", "invalid-path",
        "invalid-parent", "duplicate-identity", "unknown-size", "inventory-limit", "invalid-checkpoint", "unsafe-destination",
        "storage-unavailable", "insufficient-space", "source-changed", "destination-changed", "client-files-missing",
        "client-build-mismatch", "no-client")
    fun export(context: Context): JSONObject {
        val result = JSONObject().put("available", false).put("sourceWritePerformed", false)
            .put("warcraftXLCompatible", false).put("gameLoginQualified", false)
        val raw = runCatching { file(context).openRead().use { input ->
            val data = ByteArray(8193); var count = 0
            while (count < data.size) { val read = input.read(data, count, data.size - count); if (read < 0) break; require(read > 0); count += read }
            require(count <= 8192); JSONObject(String(data, 0, count, Charsets.UTF_8))
        } }.getOrNull() ?: return result
        result.put("available", true)
        for ((key, allowed) in mapOf("state" to states, "operation" to setOf("import", "verify"), "reason" to reasons))
            raw.optString(key).takeIf { it in allowed }?.let { result.put(key, it) }
        raw.optString("attemptId").takeIf { it.matches(Regex("[0-9a-f-]{36}")) }?.let { result.put("attemptId", it) }
        for (key in listOf("startedAtEpochMs", "updatedAtEpochMs", "files", "totalFiles", "bytes", "totalBytes", "resumedBytes",
            "peakRssKiB", "elapsedMs", "sourceCacheRequests", "sourceCacheAccepted", "destinationCacheRequests", "destinationCacheAccepted")) {
            val value = raw.opt(key)
            if ((value is Int || value is Long) && (value as Number).toLong() >= 0) result.put(key, value)
        }
        raw.optString("catalogSha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { result.put("catalogSha256", it) }
        val complete = result.optString("state") == "complete" && result.has("catalogSha256")
        return result.put("clientImported", complete).put("copyReadbackValidated", complete)
            .put("retainedRehashValidated", complete && result.optString("operation") == "verify")
    }
}
