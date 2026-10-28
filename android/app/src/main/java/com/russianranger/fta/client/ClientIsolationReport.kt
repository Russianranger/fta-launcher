package com.russianranger.fta.client

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Separate report preserves the already completed runtime test. No paths or raw native logs leave the app. */
object ClientIsolationReport {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "client-isolation-report.json"))
    fun write(context: Context, report: JSONObject) {
        val bytes = report.toString().toByteArray(); require(bytes.size <= 8192)
        val atomic = file(context); val out = atomic.startWrite()
        try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
    }
    fun export(context: Context, exits: Boolean = true): JSONObject {
        val result = JSONObject().put("available", false).put("storageIsolationValidated", false)
            .put("importedClientAccessed", false).put("wineExecuted", false).put("originalClientExecuted", false)
        val raw = runCatching { file(context).openRead().use { input ->
            val bytes = ByteArray(8193); var n = 0
            while (n < bytes.size) { val count = input.read(bytes, n, bytes.size - n); if (count < 0) break; require(count > 0); n += count }
            require(n <= 8192); JSONObject(String(bytes, 0, n, Charsets.UTF_8))
        } }.getOrNull() ?: return result
        result.put("available", true)
        raw.optString("state").takeIf { it in setOf("preparing", "testing", "complete", "failed", "stopped") }?.let { result.put("state", it) }
        raw.optString("reason").takeIf { it in ClientRuntimeReport.reasons }?.let { result.put("reason", it) }
        raw.optString("attemptId").takeIf { it.matches(Regex("[0-9a-f-]{36}")) }?.let { result.put("attemptId", it) }
        for (key in listOf("startedAtEpochMs", "updatedAtEpochMs", "elapsedMs", "fixtureExit", "fixtureBytes", "fixtureChecksPassed"))
            (raw.opt(key) as? Number)?.takeIf { (it is Int || it is Long) && it.toLong() >= 0 }?.let { result.put(key, it) }
        for (key in listOf("protectedTreeUnchanged", "ownedHandlesReaped")) if (raw.opt(key) is Boolean) result.put(key, raw.getBoolean(key))
        val checks = JSONObject()
        for (name in ClientIsolationProtocol.checks) (raw.optJSONObject("checks")?.opt(name) as? Int)?.takeIf { it == 0 }?.let { checks.put(name, it) }
        result.put("checks", checks)
        val rejected = JSONObject()
        for (name in listOf("missing", "noncanonical", "symlink", "hardlink"))
            if (raw.optJSONObject("rootRejections")?.opt(name) == true) rejected.put(name, true)
        result.put("rootRejections", rejected)
        val payloads = JSONObject()
        for ((name, expected) in ClientIsolationPins.payloads)
            if (raw.optJSONObject("payloadSha256")?.optString(name) == expected) payloads.put(name, expected)
        result.put("payloadSha256", payloads)
        val running = runCatching { ClientRuntimeService.running(context) }.getOrNull()
        val terminal = result.optString("state") in setOf("complete", "failed", "stopped")
        result.put("workerObservation", when (running) { true -> "active"; false -> "inactive"; null -> "unknown" })
            .put("interrupted", !terminal && running == false)
        if (exits && Build.VERSION.SDK_INT >= 30) {
            val observations = runCatching {
                context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, 0, 16)
                    .filter { it.processName == context.packageName + ":clientruntime" }.take(3).map { exit ->
                        val token = exit.processStateSummary?.takeIf { it.size <= 128 }?.toString(Charsets.UTF_8)
                        JSONObject().put("reason", exit.reason).put("status", exit.status).put("timestamp", exit.timestamp)
                            .put("attemptMatched", ClientExitEvidence.matches(raw.optString("attemptId"), raw.optInt("ownerPid"),
                                raw.optLong("startedAtEpochMs"), if (terminal) raw.optLong("updatedAtEpochMs") else System.currentTimeMillis(),
                                exit.pid, exit.timestamp, token))
                    }
            }
            result.put("exitHistoryAvailable", observations.isSuccess).put("recentExits", JSONArray(observations.getOrDefault(emptyList())))
        }
        return result.put("storageIsolationValidated", result.optString("state") == "complete" &&
            result.optInt("fixtureExit", -1) == 0 && result.optInt("fixtureBytes") == 65545 &&
            checks.length() == ClientIsolationProtocol.checks.size && rejected.length() == 4 &&
            payloads.length() == ClientIsolationPins.payloads.size && result.optBoolean("protectedTreeUnchanged") &&
            result.optBoolean("ownedHandlesReaped") && running == false)
    }
}
