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
    private fun file(context: Context, guarded: Boolean = false) = AtomicFile(File(context.filesDir, if (guarded) "client-guarded-wine-report.json" else "client-runtime-report.json"))
    val states = setOf("preparing", "archive-verify", "download", "extract", "verify-runtime", "probe-ownership", "probe-wine", "complete", "stopped", "failed")
    val reasons = setOf("cancelled", "timeout", "busy", "invalid-catalog", "archive-changed", "runtime-changed",
        "unsafe-runtime-store", "storage-unavailable", "insufficient-space", "runtime-unavailable", "download-failed",
        "memory-pressure", "memory-observation-unavailable", "io-failed", "ownership-failed", "probe-failed", "probe-timeout", "probe-unavailable", "socket-path-too-long", "probe-attempt-limit", "view-copy-limit", "view-growth-limit")
    fun write(context: Context, report: JSONObject, guarded: Boolean = false) {
        val atomic = file(context, guarded); val output = atomic.startWrite()
        try { val bytes = report.toString().toByteArray(); require(bytes.size <= 8192); output.write(bytes); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    fun export(context: Context, includeExitHistory: Boolean = true, guarded: Boolean = false): JSONObject {
        val expectedStages = if (guarded) ClientGuardedWine.stages else ClientProbeProtocol.stages
        val result = JSONObject().put("available", false).put("originalClientExecuted", false)
            .put("androidWineQualified", false).put("warcraftXLCompatible", false).put("gameLoginQualified", false)
        val raw = runCatching { file(context, guarded).openRead().use { input ->
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
            "ownershipNormalExit", "ownershipStopExit", "ownershipKilledExit", "stoppedTracees", "killedTracees", "socketHostPathBytes", "socketProbeErrno",
            "memoryThresholdBytes", "memoryFloorBytes", "lastTrimAgeMs", "probeMemoryAdvisories", "probeMemoryAdvisoryMs")) {
            val value = raw.opt(key)
            if ((value is Int || value is Long) && (value as Number).toLong() >= 0) result.put(key, value)
        }
        for ((key, max) in mapOf("lastTrimLevel" to 100, "memoryPsiSomeMilliPercent" to 100000,
            "memoryPsiFullMilliPercent" to 100000, "probeCleanupComplete" to 1)) {
            (raw.opt(key) as? Int)?.takeIf { it in 0..max }?.let { result.put(key, it) }
            (raw.opt(key) as? Long)?.takeIf { it in 0..max.toLong() }?.let { result.put(key, it) }
        }
        raw.optString("memoryPressureSource").takeIf { it in ClientProbeMemory.sources }?.let { result.put("memoryPressureSource", it) }
        for (key in listOf("memoryPaused", "activityBound", "systemLowMemory", "cacheEvictionMeasured", "trimActive", "criticalTrimActive"))
            if (raw.opt(key) is Boolean) result.put(key, raw.getBoolean(key))
        for ((key, max) in ClientProbeEvidence.prefixBounds) {
            val value = raw.opt(key)
            if ((value is Int || value is Long) && (value as Number).toLong() in 0..max) result.put(key, value)
        }
        for (key in listOf("archiveSha256", "catalogSha256"))
            raw.optString(key).takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { result.put(key, it) }
        if (guarded) {
            for (key in listOf("viewCopiedBytes", "viewSharedBytes", "viewCopiedFiles", "viewSharedFiles", "viewBytesObserved", "prefixBytesObserved")) {
                val value = raw.opt(key)
                if ((value is Int || value is Long) && (value as Number).toLong() in 0..(8L * 1024 * 1024 * 1024)) result.put(key, value)
            }
            raw.optString("viewPlanSha256").takeIf { it.matches(Regex("[0-9a-f]{64}")) }?.let { result.put("viewPlanSha256", it) }
            if (raw.opt("protectedFixtureUnchanged") is Boolean) result.put("protectedFixtureUnchanged", raw.getBoolean("protectedFixtureUnchanged"))
            val payloads = JSONObject()
            for ((key, value) in ClientGuardedWine.bridges)
                if (raw.optJSONObject("guardedPayloadSha256")?.optString(key) == value) payloads.put(key, value)
            result.put("guardedPayloadSha256", payloads)
            for ((field, maximum) in mapOf("windowsChecks" to 1, "windowsErrors" to 65535)) {
                val values = JSONObject()
                for (key in ClientGuardedWine.keys) (raw.optJSONObject(field)?.opt(key) as? Int)?.takeIf { it in 0..maximum }?.let { values.put(key, it) }
                result.put(field, values)
            }
            val deletion = JSONObject()
            for (arch in listOf("x86", "x64")) for ((suffix, max) in mapOf("ReturnedSuccess" to 1, "Error" to 65535)) {
                val key = arch + suffix
                (raw.optJSONObject("windowsDeletion")?.opt(key) as? Int)?.takeIf { it in 0..max }?.let { deletion.put(key, it) }
            }
            result.put("windowsDeletion", deletion)
        }
        val stages = JSONObject()
        for (key in expectedStages) {
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
        val synthetic = !guarded && complete && ownership && preRehash && raw.optBoolean("syntheticMixedRuntimeValidated") &&
            raw.optBoolean("runtimePostRehashValidated") && result.optInt("socketProbeErrno", -1) == 0 &&
            result.optString("displayEvent") == "ready" &&
            stages.length() == ClientProbeProtocol.stages.size && ClientProbeProtocol.stages.all { stages.optInt(it, -1) == 0 }
        result.put("ownershipValidated", ownership).put("runtimePreRehashValidated", preRehash)
        result.put("syntheticMixedRuntimeValidated", synthetic).put("runtimePostRehashValidated", synthetic)
        if (guarded) {
            val checks = result.getJSONObject("windowsChecks")
            val qualified = complete && ownership && preRehash && raw.optBoolean("runtimePostRehashValidated") &&
                raw.optBoolean("guardedWineStorageValidated") && result.optBoolean("protectedFixtureUnchanged") &&
                result.optInt("probeCleanupComplete") == 1 && result.optInt("socketProbeErrno", -1) == 0 &&
                result.optString("displayEvent") == "ready" && stages.length() == expectedStages.size &&
                expectedStages.all { stages.optInt(it, -1) == 0 } && checks.length() == ClientGuardedWine.keys.size &&
                ClientGuardedWine.keys.all { checks.optInt(it, -1) == 0 } &&
                result.getJSONObject("guardedPayloadSha256").length() == ClientGuardedWine.bridges.size &&
                result.getJSONObject("windowsErrors").length() == 0 && result.getJSONObject("windowsDeletion").length() == 4 &&
                result.optLong("viewCopiedBytes", -1) == 26L && result.optInt("viewCopiedFiles", -1) == 1 &&
                result.optInt("viewSharedFiles", -1) == 3 && result.has("viewPlanSha256")
            result.put("guardedWineStorageValidated", qualified).put("runtimePostRehashValidated", qualified)
                .put("originalImportAccessed", false).put("originalWorkingViewQualified", false)
        }
        return result.put("runtimeTreeVerified", complete).put("retainedRehashValidated", complete && result.optString("operation") == "verify")
    }
}
