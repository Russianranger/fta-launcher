package com.russianranger.fta.client

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.russianranger.fta.MainActivity
import com.russianranger.fta.R
import com.russianranger.fta.runtime.GameDataDiagnostics
import com.russianranger.fta.runtime.GameDataCache
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Single owned, explicitly started copy worker. Native realm services/receipts are never opened. */
class ClientImportService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    @Volatile private var source: ClientTreeSource? = null
    @Volatile private var activityBound = false
    @Volatile private var trimUntil = 0L
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private fun cancel() { cancelled.set(true); source?.cancel() }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == BIND) { activityBound = true; Binder() } else null
    override fun onUnbind(intent: Intent?): Boolean { activityBound = false; return true }
    override fun onRebind(intent: Intent?) { activityBound = intent?.action == BIND }
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN is a visibility event, not a pressure notification.
        if (level in 5..15 || level >= 40) trimUntil = SystemClock.elapsedRealtime() + 10000
    }
    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); trimUntil = SystemClock.elapsedRealtime() + 10000 }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { cancel(); if (!started) stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        if (intent?.action !in setOf(IMPORT, VERIFY)) { stopSelf(); return START_NOT_STICKY }
        started = true
        val verify = intent?.action == VERIFY
        notify("Preparing managed client ${if (verify) "verification" else "import"}…", true)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fta:clientImport")
            .apply { acquire(3 * 60 * 60 * 1000L) }
        timer.schedule({ timedOut.set(true); cancel() }, 3, TimeUnit.HOURS)
        worker.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val startedAt = SystemClock.elapsedRealtime()
            val report = JSONObject().put("attemptId", UUID.randomUUID().toString())
                .put("operation", if (verify) "verify" else "import").put("state", "planning")
                .put("startedAtEpochMs", System.currentTimeMillis()).put("ownerPid", android.os.Process.myPid())
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                getSystemService(ActivityManager::class.java).setProcessStateSummary(ClientExitEvidence.token(report.getString("attemptId")).toByteArray())
            }
            var lastWrite = 0L; var lastStage = ""; var peakRss = 0L
            val cache = GameDataCache()
            var guard: ClientIoGuard? = null
            fun write(force: Boolean = false) {
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastWrite < 1000 && lastStage == report.optString("state")) return
                lastWrite = now; lastStage = report.optString("state")
                val rss = runCatching { File("/proc/self/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmHWM:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
                } }.getOrDefault(0)
                peakRss = maxOf(peakRss, rss)
                report.put("updatedAtEpochMs", System.currentTimeMillis()).put("elapsedMs", now - startedAt).put("peakRssKiB", peakRss)
                cache.stats().let { report.put("destinationCacheRequests", it.requests).put("destinationCacheAccepted", it.accepted) }
                source?.cache?.stats()?.let { report.put("sourceCacheRequests", it.requests).put("sourceCacheAccepted", it.accepted) }
                val heap = Runtime.getRuntime()
                report.put("heapUsedBytes", heap.totalMemory() - heap.freeMemory()).put("heapMaxBytes", heap.maxMemory())
                report.put("ioLimitBytesPerSecond", ClientIoGuard.IO_BYTES_PER_SECOND).put("ioWindowBytes", ClientStore.IO_WINDOW)
                    .put("cacheEvictionMeasured", false)
                guard?.let { report.put("ioBytes", it.ioBytes).put("throttledMs", it.throttledMs)
                    .put("pressureWaitMs", it.pressureWaitMs).put("pressurePauses", it.pressurePauses) }
                ClientImportReport.write(this, report)
                notify("${report.optString("state")}: ${report.optLong("bytes") / (1024 * 1024)} / ${report.optLong("totalBytes") / (1024 * 1024)} MiB")
            }
            fun active() { if (cancelled.get()) throw ClientImportFailure(if (timedOut.get()) "timeout" else "cancelled") }
            val governor = ClientIoGuard(SystemClock::elapsedRealtime, { Thread.sleep(it) }, {
                val memory = GameDataDiagnostics.memory(this, activityBound)
                val psi = runCatching { File("/proc/pressure/memory").bufferedReader().use { reader ->
                    val buffer = CharArray(4097); val count = reader.read(buffer)
                    if (count in 1..4096) ClientIoGuard.psi(String(buffer, 0, count)) else null
                } }.getOrNull()
                report.put("memoryObservationAvailable", memory != null).put("psiObservationAvailable", psi != null)
                if (memory != null) {
                    report.put("availableMemoryBytes", memory.available).put("totalMemoryBytes", memory.total)
                        .put("lowMemoryThresholdBytes", memory.threshold).put("systemLowMemory", memory.low)
                        .put("workerImportance", memory.importance).put("activityBound", memory.activityBound)
                } else for (key in listOf("availableMemoryBytes", "totalMemoryBytes", "lowMemoryThresholdBytes", "systemLowMemory", "workerImportance", "activityBound")) report.remove(key)
                for (key in listOf("psiSomeMilliPercent", "psiFullMilliPercent")) report.remove(key)
                psi?.let { report.put("psiSomeMilliPercent", it.first).put("psiFullMilliPercent", it.second) }
                val trim = SystemClock.elapsedRealtime() < trimUntil
                report.put("trimPressure", trim)
                memory?.let { ClientIoGuard.Memory(it.available, it.total, it.threshold, it.low, psi?.first, psi?.second, trim) }
            }, ::active, { _, paused -> report.put("memoryPaused", paused); write() }).also { guard = it }
            try {
                write(true); governor.check()
                @Suppress("DEPRECATION")
                demand(getSystemService(ActivityManager::class.java).getRunningServices(30).none {
                    it.service.className in setOf("com.russianranger.fta.runtime.RuntimeDiagnosticService", "com.russianranger.fta.runtime.RuntimeRecoveryService")
                }, "busy")
                val store = ClientStore(File(filesDir, "client-store"), releaseCache = cache::release, beforeIo = governor::beforeIo)
                val progress: (ClientStore.Progress) -> Unit = { p ->
                    report.put("state", p.stage).put("files", p.files).put("totalFiles", p.totalFiles)
                        .put("bytes", p.bytes).put("totalBytes", p.totalBytes).put("resumedBytes", p.resumedBytes)
                    write()
                }
                val result = if (verify) store.verify(governor::check, progress, ClientCandidate::validate) else {
                    val tree = intent?.data ?: throw ClientImportFailure("invalid-source")
                    val input = ClientTreeSource(this, tree, governor::check).also { source = it }
                    governor.check(); input.preflight(); governor.check(); store.import(input, governor::check, progress, ClientCandidate::validate)
                }
                report.put("state", "complete").put("files", result.files).put("totalFiles", result.files)
                    .put("bytes", result.bytes).put("totalBytes", result.bytes).put("resumedBytes", result.resumedBytes)
                    .put("catalogSha256", result.catalogSha256)
            } catch (error: Exception) {
                report.put("state", if (cancelled.get()) "stopped" else "failed")
                    .put("reason", if (cancelled.get()) { if (timedOut.get()) "timeout" else "cancelled" }
                        else (error as? ClientImportFailure)?.reason?.takeIf { it in ClientImportReport.reasons } ?: "provider-error")
            } finally {
                report.put("memoryPaused", false)
                runCatching { write(true) }; source?.cancel(); timer.shutdownNow()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun notify(text: String, foreground: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Client import", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 36, Intent(this, ClientImportService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 36, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_ashes).setContentTitle("From the Ashes client files")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(43, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(43, notification)
        } else manager.notify(43, notification)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { timedOut.set(true); cancel(); stopSelf() }
    @Synchronized private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onDestroy() { cancel(); timer.shutdownNow(); worker.shutdown(); releaseWakeLock(); super.onDestroy() }
    companion object {
        const val IMPORT = "com.russianranger.fta.IMPORT_CLIENT"
        const val VERIFY = "com.russianranger.fta.VERIFY_CLIENT"
        const val STOP = "com.russianranger.fta.STOP_CLIENT_IMPORT"
        const val BIND = "com.russianranger.fta.BIND_CLIENT_IMPORT"
        private const val CHANNEL = "fta-client-import"
        @Suppress("DEPRECATION")
        fun running(context: Context) = context.getSystemService(ActivityManager::class.java).getRunningServices(30)
            .any { it.service.className == ClientImportService::class.java.name && it.started }
    }
}
