package com.russianranger.fta.client

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.russianranger.fta.MainActivity
import com.russianranger.fta.R
import com.russianranger.fta.runtime.GameDataCache
import com.russianranger.fta.runtime.GameDataDiagnostics
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, non-restarting preparation and synthetic native probes. No original client execution. */
class ClientRuntimeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    private val download = ClientRuntimeDownload(::active)
    @Volatile private var activityBound = false
    @Volatile private var trimUntil = 0L
    private var started = false
    private var probing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private fun cancel() { cancelled.set(true); download.cancel() }
    private fun active() { if (cancelled.get()) throw RuntimeInstallFailure(if (timedOut.get()) "timeout" else "cancelled") }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == BIND) { activityBound = true; Binder() } else null
    override fun onUnbind(intent: Intent?): Boolean { activityBound = false; return true }
    override fun onRebind(intent: Intent?) { activityBound = intent?.action == BIND }
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level in 5..15 || level >= 40) trimUntil = SystemClock.elapsedRealtime() + 10000
    }
    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); trimUntil = SystemClock.elapsedRealtime() + 10000 }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { cancel(); if (!started) stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        if (intent?.action !in setOf(PREPARE, VERIFY, PROBE)) { stopSelf(); return START_NOT_STICKY }
        started = true
        probing = intent?.action == PROBE
        notification("Preparing client runtime…", true)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fta:clientRuntime")
            .apply { acquire(90 * 60 * 1000L) }
        timer.schedule({ timedOut.set(true); cancel() }, 90, TimeUnit.MINUTES)
        worker.execute { prepare(intent?.action == VERIFY) }
        return START_NOT_STICKY
    }
    private fun prepare(verify: Boolean) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val began = SystemClock.elapsedRealtime()
        val report = JSONObject().put("attemptId", UUID.randomUUID().toString())
            .put("operation", if (probing) "probe" else if (verify) "verify" else "prepare").put("state", "preparing")
            .put("startedAtEpochMs", System.currentTimeMillis()).put("ownerPid", android.os.Process.myPid())
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            getSystemService(ActivityManager::class.java).setProcessStateSummary(ClientExitEvidence.token(report.getString("attemptId")).toByteArray())
        }
        val cache = GameDataCache(); var guard: ClientIoGuard? = null
        var writtenAt = 0L; var writtenStage = ""; var peakRss = 0L
        fun write(force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            if (!force && now - writtenAt < 1000 && writtenStage == report.optString("state")) return
            writtenAt = now; writtenStage = report.optString("state")
            peakRss = maxOf(peakRss, runCatching { File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmHWM:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
            } }.getOrDefault(0))
            val heap = Runtime.getRuntime()
            report.put("updatedAtEpochMs", System.currentTimeMillis()).put("elapsedMs", now - began).put("peakRssKiB", peakRss)
                .put("heapUsedBytes", heap.totalMemory() - heap.freeMemory()).put("heapMaxBytes", heap.maxMemory())
                .put("ioLimitBytesPerSecond", ClientIoGuard.IO_BYTES_PER_SECOND).put("ioWindowBytes", ClientRuntimeStore.WINDOW)
                .put("cacheEvictionMeasured", false).put("activityBound", activityBound)
            cache.stats().let { report.put("cacheRequests", it.requests).put("cacheAccepted", it.accepted) }
            guard?.let { report.put("ioBytes", it.ioBytes).put("throttledMs", it.throttledMs)
                .put("pressureWaitMs", it.pressureWaitMs).put("pressurePauses", it.pressurePauses) }
            ClientRuntimeReport.write(this, report)
            notification("${report.optString("state")}: ${report.optLong("bytes") / 1048576} / ${report.optLong("totalBytes") / 1048576} MiB")
        }
        val governor = ClientIoGuard(SystemClock::elapsedRealtime, { Thread.sleep(it) }, {
            val memory = GameDataDiagnostics.memory(this, activityBound)
            val psi = runCatching { File("/proc/pressure/memory").bufferedReader().use { input ->
                val chars = CharArray(4097); val n = input.read(chars)
                if (n in 1..4096) ClientIoGuard.psi(String(chars, 0, n)) else null
            } }.getOrNull()
            memory?.let { report.put("availableMemoryBytes", it.available).put("totalMemoryBytes", it.total).put("systemLowMemory", it.low) }
            memory?.let { ClientIoGuard.Memory(it.available, it.total, it.threshold, it.low, psi?.first, psi?.second,
                SystemClock.elapsedRealtime() < trimUntil) }
        }, ::active, { _, paused -> report.put("memoryPaused", paused); write() }).also { guard = it }
        try {
            write(true); governor.check()
            @Suppress("DEPRECATION")
            runtimeRequire(getSystemService(ActivityManager::class.java).getRunningServices(30).none {
                it.service.className in setOf("com.russianranger.fta.runtime.RuntimeDiagnosticService",
                    "com.russianranger.fta.runtime.RuntimeRecoveryService", ClientImportService::class.java.name) && it.started
            }, "busy")
            val pin = assets.open("metadata/client-runtime-lock.json").use { JSONObject(it.bufferedReader().readText()) }
            val catalogSha = pin.getJSONObject("catalog").getString("sha256")
            val plan = assets.open("metadata/client-runtime-catalog.tsv").use { RuntimeArchivePlan.read(it, catalogSha) }
            runtimeRequire(plan.archiveSha256 == pin.getJSONObject("archive").getString("sha256") &&
                plan.archiveBytes == pin.getJSONObject("archive").getLong("bytes"), "invalid-catalog")
            report.put("archiveSha256", plan.archiveSha256).put("catalogSha256", plan.catalogSha256)
            val store = ClientRuntimeStore(File(filesDir, "client-runtime-store"), plan,
                beforeIo = governor::beforeIo, releaseCache = cache::release)
            val progress: (ClientRuntimeStore.Progress) -> Unit = {
                report.put("state", it.stage).put("bytes", it.bytes).put("totalBytes", it.totalBytes).put("entries", it.entries); write()
            }
            report.put("state", if (verify) "verify-runtime" else "archive-verify"); write(true)
            if (probing) {
                var nextMemory = 0L
                val result = store.withVerified(governor::check, progress, reverifyAfter = true) { runtime ->
                    val deadline = SystemClock.elapsedRealtime() + 960000
                    ClientNativeProbe.run(this, runtime, report.getString("attemptId"), {
                        active(); val now = SystemClock.elapsedRealtime()
                        runtimeRequire(now < deadline, "probe-timeout")
                        if (now >= nextMemory) {
                            nextMemory = now + 1000
                            val memory = GameDataDiagnostics.memory(this, activityBound) ?: throw RuntimeInstallFailure("memory-observation-unavailable")
                            runtimeRequire(!ClientIoGuard.Memory(memory.available, memory.total, memory.threshold, memory.low,
                                trim = now < trimUntil).pressured(), "memory-pressure")
                            runtimeRequire(runtime.usableSpace >= ClientRuntimeStore.RESERVE, "insufficient-space")
                            report.put("availableMemoryBytes", memory.available).put("totalMemoryBytes", memory.total); write()
                        }
                    }, { stage, output ->
                        report.put("state", stage)
                        val statuses = JSONObject()
                        for (line in output.lineSequence()) {
                            val fields = line.split(' ')
                            if (fields.size == 3 && fields[0] == "FTA_ANDROID_STAGE" && fields[1] in ClientProbeProtocol.stages) {
                                fields[2].toIntOrNull()?.takeIf { it in -255..255 }?.let { statuses.put(fields[1], it) }
                            }
                        }
                        if (statuses.length() > 0) report.put("probeStages", statuses)
                        write()
                    })
                }
                report.put("ownershipNormalExit", result.normalExit).put("ownershipStopExit", result.stopExit)
                    .put("ownershipKilledExit", result.killedExit).put("stoppedTracees", result.stoppedTracees)
                    .put("killedTracees", result.killedTracees).put("probeStages", JSONObject(result.stages))
                    .put("syntheticMixedRuntimeValidated", true).put("runtimePostRehashValidated", true)
            } else if (verify) store.withVerified(governor::check, progress) { } else
                store.install({ download.open(plan.archiveBytes) }, governor::check, progress)
            report.put("state", "complete").put("bytes", plan.fileBytes).put("totalBytes", plan.fileBytes).put("entries", plan.entries.size)
        } catch (e: Exception) {
            val reason = if (cancelled.get()) { if (timedOut.get()) "timeout" else "cancelled" }
                else (e as? RuntimeInstallFailure)?.reason ?: (e as? ClientImportFailure)?.reason ?: "io-failed"
            report.put("state", if (cancelled.get()) "stopped" else "failed")
                .put("reason", reason.takeIf { it in ClientRuntimeReport.reasons } ?: "io-failed")
        } finally {
            report.put("memoryPaused", false); runCatching { write(true) }
            download.cancel(); timer.shutdownNow(); releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun notification(text: String, foreground: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Client runtime", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 38, Intent(this, ClientRuntimeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 38, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_ashes).setContentTitle("From the Ashes client runtime")
            .setContentText(text).setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (foreground) {
            if (probing && Build.VERSION.SDK_INT >= 34) startForeground(44, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else if (!probing && Build.VERSION.SDK_INT >= 29) startForeground(44, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(44, notification)
        } else manager.notify(44, notification)
    }
    override fun onTimeout(startId: Int, fgsType: Int) { timedOut.set(true); cancel(); stopSelf() }
    @Synchronized private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    override fun onDestroy() { cancel(); timer.shutdownNow(); worker.shutdown(); releaseWakeLock(); super.onDestroy() }
    companion object {
        const val PREPARE = "com.russianranger.fta.PREPARE_CLIENT_RUNTIME"
        const val VERIFY = "com.russianranger.fta.VERIFY_CLIENT_RUNTIME"
        const val PROBE = "com.russianranger.fta.PROBE_CLIENT_RUNTIME"
        const val STOP = "com.russianranger.fta.STOP_CLIENT_RUNTIME"
        const val BIND = "com.russianranger.fta.BIND_CLIENT_RUNTIME"
        private const val CHANNEL = "fta-client-runtime"
        @Suppress("DEPRECATION")
        fun running(context: Context) = context.getSystemService(ActivityManager::class.java).getRunningServices(30)
            .any { it.service.className == ClientRuntimeService::class.java.name && it.started }
    }
}
