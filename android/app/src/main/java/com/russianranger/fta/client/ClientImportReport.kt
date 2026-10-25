package com.russianranger.fta.client

import android.app.ActivityManager
import android.os.Build
import android.content.Context
import com.russianranger.fta.runtime.GameDataExitEvidence
import org.json.JSONArray
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
        "client-build-mismatch", "no-client", "memory-pressure", "memory-observation-unavailable")
    fun export(context: Context, includeExitHistory: Boolean = true): JSONObject {
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
            "peakRssKiB", "elapsedMs", "heapUsedBytes", "heapMaxBytes", "availableMemoryBytes", "totalMemoryBytes",
            "lowMemoryThresholdBytes", "workerImportance", "psiSomeMilliPercent", "psiFullMilliPercent", "ioBytes",
            "ioLimitBytesPerSecond", "ioWindowBytes", "throttledMs", "pressureWaitMs", "pressurePauses", "sourceCacheRequests", "sourceCacheAccepted", "destinationCacheRequests", "destinationCacheAccepted")) {
            val value = raw.opt(key)
            if ((value is Int || value is Long) && (value as Number).toLong() >= 0) result.put(key, value)
        }
        for (key in listOf("memoryObservationAvailable", "psiObservationAvailable", "systemLowMemory", "activityBound",
            "trimPressure", "memoryPaused", "cacheEvictionMeasured")) if (raw.opt(key) is Boolean) result.put(key, raw.getBoolean(key))
        val terminal = raw.optString("state") in setOf("complete", "failed", "stopped")
        val running = runCatching { ClientImportService.running(context) }.getOrNull()
        result.put("workerObservation", when (running) { true -> "active"; false -> "inactive"; null -> "unknown" })
        result.put("interrupted", !terminal && running == false)
        result.put("exitHistorySupported", Build.VERSION.SDK_INT >= 30)
        if (includeExitHistory && Build.VERSION.SDK_INT >= 30) {
            val now = System.currentTimeMillis()
            val history = runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
                    .filter { it.processName == context.packageName + ":client" }.take(3).map { exit ->
                        val token = exit.processStateSummary?.takeIf { it.size <= 128 }?.toString(Charsets.UTF_8)
                        val sample = GameDataExitEvidence.Sample(exit.pid, exit.timestamp, exit.reason, exit.status, exit.pss, exit.rss, token)
                        JSONObject(GameDataExitEvidence.export(sample, null, now)).put("attemptMatched",
                            ClientExitEvidence.matches(raw.optString("attemptId"), raw.optInt("ownerPid"), raw.optLong("startedAtEpochMs"),
                                if (terminal) raw.optLong("updatedAtEpochMs") else now, exit.pid, exit.timestamp, token))
                    }
            }
            result.put("exitHistoryAvailable", history.isSuccess).put("recentClientExits", JSONArray(history.getOrDefault(emptyList())))
        }
        raw.optString("catalogSha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { result.put("catalogSha256", it) }
        val complete = result.optString("state") == "complete" && result.has("catalogSha256")
        return result.put("clientImported", complete).put("copyReadbackValidated", complete)
            .put("retainedRehashValidated", complete && result.optString("operation") == "verify")
    }
}
