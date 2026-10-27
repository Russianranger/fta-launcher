package com.russianranger.fta.client

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.russianranger.fta.runtime.GameDataExitEvidence
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit allowlist: never exports URLs, exception text, paths, command lines or raw logs. */
object ClientRuntimeReport {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "client-runtime-report.json"))
    val states = setOf("preparing", "archive-verify", "download", "extract", "verify-runtime", "probe-ownership", "probe-wine", "complete", "stopped", "failed")
    val reasons = setOf("cancelled", "timeout", "busy", "invalid-catalog", "archive-changed", "runtime-changed",
        "unsafe-runtime-store", "storage-unavailable", "insufficient-space", "runtime-unavailable", "download-failed",
        "memory-pressure", "memory-observation-unavailable", "io-failed", "ownership-failed", "probe-failed", "probe-timeout", "probe-unavailable", "socket-path-too-long")
    fun write(context: Context, report: JSONObject) {
        val atomic = file(context); val output = atomic.startWrite()
        try { val bytes = report.toString().toByteArray(); require(bytes.size <= 8192); output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun export(context: Context, includeExitHistory: Boolean = true): JSONObject {
        val result = JSONObject().put("available", false).put("originalClientExecuted", false)
            .put("androidWineQualified", false).put("warcraftXLCompatible", false).put("gameLoginQualified", false)
        val raw = runCatching { file(context).openRead().use { input ->
            val bytes = ByteArray(8193); var count = 0
            while (count < bytes.size) { val n = input.read(bytes, count, bytes.size - count); if (n < 0) break; require(n > 0); count += n }
            require(count <= 8192); JSONObject(String(bytes, 0, count, Charsets.UTF_8))
        } }.getOrNull() ?: return result
        result.put("available", true)
        for ((key, allowed) in mapOf("state" to states, "operation" to setOf("prepare", "verify", "probe"), "reason" to reasons))
            raw.optString(key).takeIf { it in allowed }?.let { result.put(key, it) }
        raw.optString("attemptId").takeIf { it.matches(Regex("[0-9a-f-]{36}")) }?.let { result.put("attemptId", it) }
        for (key in listOf("startedAtEpochMs", "updatedAtEpochMs", "elapsedMs", "bytes", "totalBytes", "entries", "peakRssKiB",
            "heapUsedBytes", "heapMaxBytes", "availableMemoryBytes", "totalMemoryBytes", "ioBytes", "throttledMs",
            "pressureWaitMs", "pressurePauses", "ioLimitBytesPerSecond", "ioWindowBytes", "cacheRequests", "cacheAccepted",
            "ownershipNormalExit", "ownershipStopExit", "ownershipKilledExit", "stoppedTracees", "killedTracees", "socketHostPathBytes", "socketProbeErrno")) {
            val value = raw.opt(key)
            if ((value is Int || value is Long) && (value as Number).toLong() >= 0) result.put(key, value)
        }
        for (key in listOf("memoryPaused", "activityBound", "systemLowMemory", "cacheEvictionMeasured"))
            if (raw.opt(key) is Boolean) result.put(key, raw.getBoolean(key))
        for ((key, max) in ClientProbeEvidence.prefixBounds) {
            val value = raw.opt(key)
            if ((value is Int || value is Long) && (value as Number).toLong() in 0..max) result.put(key, value)
        }
        for (key in listOf("archiveSha256", "catalogSha256"))
            raw.optString(key).takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { result.put(key, it) }
        val stages = JSONObject()
        for (key in ClientProbeProtocol.stages) {
            val value = raw.optJSONObject("probeStages")?.opt(key)
            if (value is Int && value in -255..255) stages.put(key, value)
        }
        if (stages.length() > 0) result.put("probeStages", stages)
        raw.optString("displayEvent").takeIf { it in ClientProbeEvidence.displayEvents }?.let { result.put("displayEvent", it) }
        (raw.opt("displayExit") as? Int)?.takeIf { it in -255..255 }?.let { result.put("displayExit", it) }
        val diagnostics = raw.optJSONArray("probeDiagnostics")
        if (diagnostics != null) result.put("probeDiagnostics", JSONArray((0 until minOf(diagnostics.length(), 16))
            .map { diagnostics.optString(it) }.filter { it in ClientProbeEvidence.categories }.distinct()))
        val terminal = raw.optString("state") in setOf("complete", "failed", "stopped")
        val running = runCatching { ClientRuntimeService.running(context) }.getOrNull()
        result.put("workerObservation", when (running) { true -> "active"; false -> "inactive"; null -> "unknown" })
            .put("interrupted", !terminal && running == false).put("exitHistorySupported", Build.VERSION.SDK_INT >= 30)
        if (includeExitHistory && Build.VERSION.SDK_INT >= 30) {
            val now = System.currentTimeMillis()
            val exits = runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
                    .filter { it.processName == context.packageName + ":clientruntime" }.take(3).map { exit ->
                        val token = exit.processStateSummary?.takeIf { it.size <= 128 }?.toString(Charsets.UTF_8)
                        val sample = GameDataExitEvidence.Sample(exit.pid, exit.timestamp, exit.reason, exit.status, exit.pss, exit.rss, token)
                        JSONObject(GameDataExitEvidence.export(sample, null, now)).put("attemptMatched",
                            ClientExitEvidence.matches(raw.optString("attemptId"), raw.optInt("ownerPid"), raw.optLong("startedAtEpochMs"),
                                if (terminal) raw.optLong("updatedAtEpochMs") else now, exit.pid, exit.timestamp, token))
                    }
            }
            result.put("exitHistoryAvailable", exits.isSuccess).put("recentClientRuntimeExits", JSONArray(exits.getOrDefault(emptyList())))
        }
        val complete = result.optString("state") == "complete" && result.has("archiveSha256") && result.has("catalogSha256")
        val ownership = result.optString("operation") == "probe" && result.optInt("ownershipNormalExit", -1) == 0 &&
            result.optInt("ownershipStopExit", -1) in 0..255 &&
            result.optInt("ownershipKilledExit", -1) == 137 && result.optInt("stoppedTracees") == 3 && result.optInt("killedTracees") == 3
        val preRehash = result.optString("operation") == "probe" && result.has("archiveSha256") && result.has("catalogSha256") &&
            raw.optBoolean("runtimePreRehashValidated")
        val synthetic = complete && ownership && preRehash && raw.optBoolean("syntheticMixedRuntimeValidated") &&
            raw.optBoolean("runtimePostRehashValidated") && result.optInt("socketProbeErrno", -1) == 0 &&
            result.optString("displayEvent") == "ready" &&
            stages.length() == ClientProbeProtocol.stages.size && ClientProbeProtocol.stages.all { stages.optInt(it, -1) == 0 }
        result.put("ownershipValidated", ownership).put("runtimePreRehashValidated", preRehash)
        result.put("syntheticMixedRuntimeValidated", synthetic).put("runtimePostRehashValidated", synthetic)
        return result.put("runtimeTreeVerified", complete).put("retainedRehashValidated", complete && result.optString("operation") == "verify")
    }
}
