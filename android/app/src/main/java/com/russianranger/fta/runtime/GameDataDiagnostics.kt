package com.russianranger.fta.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.io.File

/** Android-only observations; no trace streams, exception messages or process identifiers are exported. */
object GameDataDiagnostics {
    fun store(context: Context) = GameDataOperation(File(context.filesDir, "runtime/game-data-operation.properties"))
    fun markProcess(context: Context, attempt: String) {
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            context.getSystemService(ActivityManager::class.java).setProcessStateSummary(GameDataOperation.token(attempt).toByteArray())
        }
    }
    @Suppress("DEPRECATION")
    fun export(context: Context, attempt: String): Map<String, Any> {
        val operation = store(context)
        val record = operation.read(attempt)
        val manager = context.getSystemService(ActivityManager::class.java)
        val observed = record?.let { runCatching {
            !ProcessObservation.ended(it.owner) &&
                manager.getRunningServices(20).any { service ->
                    service.pid == it.owner.pid && service.service.className == RuntimeDiagnosticService::class.java.name
                }
        }.getOrNull() }
        val result = operation.export(attempt, observed).toMutableMap()
        result["exitHistorySupported"] = Build.VERSION.SDK_INT >= 30
        if (Build.VERSION.SDK_INT >= 30) {
            val history = runCatching {
                manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                    .filter { it.processName == context.packageName + ":runtime" }.take(3).map {
                        GameDataExitEvidence.export(GameDataExitEvidence.Sample(it.pid, it.timestamp, it.reason, it.status,
                            it.pss, it.rss, it.processStateSummary?.takeIf { bytes -> bytes.size <= 128 }?.toString(Charsets.UTF_8)),
                            record, System.currentTimeMillis())
                    }
            }
            result["exitHistoryAvailable"] = history.isSuccess
            result["recentRuntimeExits"] = history.getOrDefault(emptyList())
        }
        return result
    }
}
