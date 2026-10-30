package com.russianranger.fta.client

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.system.Os
import com.russianranger.fta.MainActivity
import com.russianranger.fta.R
import com.russianranger.fta.runtime.GameDataCache
import com.russianranger.fta.runtime.GameDataDiagnostics
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit, non-restarting preparation and bounded owned client diagnostics. */
class ClientRuntimeService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    private val download = ClientRuntimeDownload(::active)
    @Volatile private var activityBound = false
    private val probeMemory = ClientProbeMemory()
    private var started = false
    private var probing = false
    private var isolating = false
    private var guarded = false
    private var finishing = false
    private var original = false
    private var wakeLock: PowerManager.WakeLock? = null
    private fun cancel() { cancelled.set(true); download.cancel() }
    private fun active() { if (cancelled.get()) throw RuntimeInstallFailure(if (timedOut.get()) "timeout" else "cancelled") }
    override fun onBind(intent: Intent?): IBinder? = if (intent?.action == BIND) { activityBound = true; Binder() } else null
    override fun onUnbind(intent: Intent?): Boolean { activityBound = false; return true }
    override fun onRebind(intent: Intent?) { activityBound = intent?.action == BIND }
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        probeMemory.trim(level, SystemClock.elapsedRealtime())
    }
    @Suppress("DEPRECATION")
    override fun onLowMemory() { super.onLowMemory(); probeMemory.low(SystemClock.elapsedRealtime()) }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { cancel(); if (!started) stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        if (intent?.action !in setOf(PREPARE, VERIFY, PROBE, ISOLATE, GUARDED_PROBE, GUARDED_FINISH, ORIGINAL)) { stopSelf(); return START_NOT_STICKY }
        started = true
        isolating = intent?.action == ISOLATE
        finishing = intent?.action == GUARDED_FINISH
        original = intent?.action == ORIGINAL
        guarded = intent?.action == GUARDED_PROBE || finishing
        probing = intent?.action == PROBE || isolating || guarded || original
        notification(if (isolating) "Testing client storage isolation…" else "Preparing client runtime…", true)
        val minutes = if (original) 180L else if (isolating) 2L else 90L
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fta:clientRuntime")
            .apply { acquire(minutes * 60 * 1000L) }
        timer.schedule({ timedOut.set(true); cancel() }, minutes, TimeUnit.MINUTES)
        worker.execute { if (isolating) prepareIsolation() else prepare(intent?.action == VERIFY) }
        return START_NOT_STICKY
    }
    private fun prepareIsolation() {
        val began = SystemClock.elapsedRealtime()
        val report = JSONObject().put("attemptId", UUID.randomUUID().toString()).put("state", "preparing")
            .put("startedAtEpochMs", System.currentTimeMillis()).put("ownerPid", android.os.Process.myPid())
        fun write() {
            report.put("updatedAtEpochMs", System.currentTimeMillis()).put("elapsedMs", SystemClock.elapsedRealtime() - began)
            ClientIsolationReport.write(this, report)
        }
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            getSystemService(ActivityManager::class.java).setProcessStateSummary(ClientExitEvidence.token(report.getString("attemptId")).toByteArray())
        }
        try {
            write(); active()
            @Suppress("DEPRECATION")
            runtimeRequire(getSystemService(ActivityManager::class.java).getRunningServices(30).none {
                it.service.className in setOf("com.russianranger.fta.runtime.RuntimeDiagnosticService",
                    "com.russianranger.fta.runtime.RuntimeRecoveryService", ClientImportService::class.java.name) && it.started
            }, "busy")
            ClientIsolationNative.run(this, report.getString("attemptId"), ::active) { key, value ->
                report.put(key, value); write()
            }
            report.put("state", "complete")
        } catch (e: Exception) {
            val reason = if (cancelled.get()) { if (timedOut.get()) "timeout" else "cancelled" }
                else (e as? RuntimeInstallFailure)?.reason ?: "io-failed"
            report.put("state", if (cancelled.get()) "stopped" else "failed")
                .put("reason", reason.takeIf { it in ClientRuntimeReport.reasons } ?: "io-failed")
        } finally {
            runCatching { write() }; timer.shutdownNow(); releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun prepare(verify: Boolean) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val began = SystemClock.elapsedRealtime()
        val report = JSONObject().put("attemptId", UUID.randomUUID().toString())
            .put("operation", if (original) "original" else if (finishing) "finish" else if (probing) "probe" else if (verify) "verify" else "prepare").put("state", "preparing")
            .put("startedAtEpochMs", System.currentTimeMillis()).put("ownerPid", android.os.Process.myPid())
        if (Build.VERSION.SDK_INT >= 30) runCatching {
            getSystemService(ActivityManager::class.java).setProcessStateSummary(ClientExitEvidence.token(report.getString("attemptId")).toByteArray())
        }
        val cache = GameDataCache(); var guard: ClientIoGuard? = null
        var phase = "preparing"
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
            report.put("completionPhase", phase)
            try { ClientRuntimeReport.write(this, report, guarded, finishing, original) }
            catch (e: Exception) { throw ClientFailureEvidence.AtPhase("report-write", e) }
            try { notification("${report.optString("state")}: ${report.optLong("bytes") / 1048576} / ${report.optLong("totalBytes") / 1048576} MiB") }
            catch (e: Exception) { throw ClientFailureEvidence.AtPhase("notification-update", e) }
        }
        fun milestone(next: String) {
            phase = next
            if (next == "runtime-post-verified" || (finishing && next == "runtime-pre-verified"))
                report.put("runtimePostRehashCompleted", true)
            write(true)
        }
        fun recordMemory(memory: ClientIoGuard.Memory, signal: ClientProbeMemory.Signal, now: Long) {
            report.put("availableMemoryBytes", memory.available).put("totalMemoryBytes", memory.total)
                .put("memoryThresholdBytes", memory.threshold).put("memoryFloorBytes", memory.floor)
                .put("systemLowMemory", memory.low).put("trimActive", signal.active(now))
                .put("criticalTrimActive", signal.critical(now)).put("lastTrimLevel", signal.level)
                .put("lastTrimAgeMs", if (signal.level == 0) 0 else maxOf(0, now - signal.at))
            report.remove("memoryPsiSomeMilliPercent"); report.remove("memoryPsiFullMilliPercent")
            memory.someMilliPercent?.let { report.put("memoryPsiSomeMilliPercent", it) }
            memory.fullMilliPercent?.let { report.put("memoryPsiFullMilliPercent", it) }
        }
        var latestPsiTotals: ClientProbePsi.Totals? = null
        fun memorySample(signal: ClientProbeMemory.Signal): ClientIoGuard.Memory? {
            latestPsiTotals = null
            val memory = GameDataDiagnostics.memory(this, activityBound) ?: return null
            val psi = runCatching { File("/proc/pressure/memory").bufferedReader().use { input ->
                val chars = CharArray(4097); val n = input.read(chars)
                if (n in 1..4096) {
                    val text = String(chars, 0, n)
                    latestPsiTotals = ClientProbePsi.totals(text)
                    ClientIoGuard.psi(text)
                } else null
            } }.getOrNull()
            return ClientIoGuard.Memory(memory.available, memory.total, memory.threshold, memory.low,
                psi?.first, psi?.second, signal.active(SystemClock.elapsedRealtime()))
        }
        fun recordProbeDecision(memory: ClientIoGuard.Memory, signal: ClientProbeMemory.Signal, now: Long): ClientProbeMemory.Decision {
            val decision = probeMemory.evaluate(memory, now, signal, latestPsiTotals)
            recordMemory(memory, signal, now)
            report.put("memoryPressureSource", decision.source)
                .put("probeMemoryAdvisories", probeMemory.advisoryEpisodes)
                .put("probeMemoryAdvisoryMs", probeMemory.advisoryElapsedMs)
                .put("probeMemoryCriticalGraceEpisodes", probeMemory.criticalGraceEpisodes)
                .put("probeMemoryCriticalGraceMaxMs", probeMemory.criticalGraceMaxMs)
                .put("probeMemoryCriticalGraceLimitMs", ClientProbeMemory.CRITICAL_MS)
                .put("probeMemoryPsiGraceEpisodes", probeMemory.psiGraceEpisodes)
                .put("probeMemoryPsiGraceMaxMs", probeMemory.psiGraceMaxMs)
                .put("probeMemoryPsiGraceLimitMs", ClientProbeMemory.PSI_CONFIRM_MS)
            for (key in listOf("probeMemoryPsiWindowMs", "probeMemoryPsiSomeMilliPercent", "probeMemoryPsiFullMilliPercent")) report.remove(key)
            probeMemory.psiInterval?.let {
                report.put("probeMemoryPsiWindowMs", it.elapsedMs)
                    .put("probeMemoryPsiSomeMilliPercent", it.someMilliPercent)
                    .put("probeMemoryPsiFullMilliPercent", it.fullMilliPercent)
            }
            write(decision.stop)
            return decision
        }
        val governor = ClientIoGuard(SystemClock::elapsedRealtime, { Thread.sleep(it) }, {
            val now = SystemClock.elapsedRealtime(); val signal = probeMemory.snapshot()
            val memory = memorySample(signal)
            memory?.also { sample ->
                recordMemory(sample, signal, now)
                report.put("memoryPressureSource", when {
                    sample.low -> "system-low"
                    sample.available < sample.floor -> "available-floor"
                    sample.trim -> "trim"
                    (sample.someMilliPercent ?: 0) >= 10000 -> "psi-some"
                    (sample.fullMilliPercent ?: 0) >= 1000 -> "psi-full"
                    else -> "none"
                })
            }
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
            if (original) {
                val saved = ClientRuntimeReport.stored(this, guarded = true)
                val finished = ClientRuntimeReport.export(this, includeExitHistory = false, guarded = true, finish = true)
                runtimeRequire(saved != null && finished.optBoolean("guardedWineStorageValidated") &&
                    finished.optString("sourceProbeAttemptId") == saved.first.optString("attemptId") &&
                    finished.optString("sourceReportSha256") == saved.second, "retained-probe-unavailable")
                store.withVerified(governor::check, progress, reverifyAfter = true, milestone = ::milestone) { runtime ->
                    report.put("runtimePreRehashValidated", true)
                    val run = ClientProbeAttempts.create(filesDir.canonicalFile, report.getString("attemptId"))
                    fun nativeMonitor(): () -> Unit {
                        var nextMemory = 0L
                        return {
                            active(); val now = SystemClock.elapsedRealtime()
                            if (now >= nextMemory) {
                                nextMemory = now + 1000
                                val signal = probeMemory.snapshot()
                                val memory = memorySample(signal) ?: throw RuntimeInstallFailure("memory-observation-unavailable")
                                val decision = recordProbeDecision(memory, signal, now)
                                runtimeRequire(!decision.stop, "memory-pressure")
                            }
                        }
                    }
                    fun observeNative(text: String, preparingPrefix: Boolean) {
                        if (!preparingPrefix) {
                            report.put("startupEvents", JSONObject(ClientStartupProtocol.values(text)))
                            report.put("startupFault", JSONObject(ClientStartupFault.parse(text)))
                            report.put("startupHostFault", JSONObject(ClientStartupFault.parse(text, host = true)))
                            ClientStartupFault.runtimeErrorMask(text)?.let { report.put("startupClientErrorMask", it) }
                            ClientStartupFault.runtimeErrorMask(text, host = true)?.let { report.put("startupHostErrorMask", it) }
                        }
                        val stages = JSONObject()
                        val expected = if (preparingPrefix) ClientStartupProtocol.prefixStages else ClientStartupProtocol.stages
                        for (line in text.lineSequence()) {
                            val f = line.split(' ')
                            if (f.size == 3 && f[0] == "FTA_ANDROID_STAGE" && f[1] in expected)
                                f[2].toIntOrNull()?.let { stages.put(f[1], it) }
                        }
                        report.put(if (preparingPrefix) "prefixPreparationStages" else "probeStages", stages)
                        val observations = ClientProbeEvidence.parse(text)
                        observations.displayEvent?.let { report.put("displayEvent", it) }
                        observations.socketStatus?.let { report.put("socketProbeErrno", it) }
                        if (observations.diagnostics.isNotEmpty()) report.put("probeDiagnostics", JSONArray(observations.diagnostics.toList()))
                        for ((key, value) in observations.prefixProgress) report.put(key, value)
                        if (preparingPrefix) for ((key, value) in ClientStartupProtocol.cacheValues(text)) report.put(key, value)
                        write()
                    }
                    try {
                        // Prefix setup never binds or reads the imported client. Its owned
                        // tracer must exit before beginning the expensive client verification.
                        report.put("state", "prefix-preparation"); milestone("prefix-preparation")
                        val prepared = ClientSessionNative.prepare(this, runtime, run, nativeMonitor(),
                            { text -> observeNative(text, true) }, { key, value -> report.put(key, value); write() })
                        val prefixBytes = ClientWorkingView.checkGrowth(File(run, "prefix"), 3L * ClientRuntimeStore.RESERVE)
                        val clientStore = ClientStore(File(filesDir, "client-store"), beforeIo = governor::beforeIo, releaseCache = cache::release)
                        ClientManagedView.run(clientStore, File(run, "view"), active = governor::check, beforeIo = governor::beforeIo,
                            preparedPrefixBytes = prefixBytes,
                            progress = { p -> report.put("state", "client-verify").put("bytes", p.bytes).put("totalBytes", p.totalBytes)
                                .put("clientFilesVerified", p.files); write() },
                            stamp = { file -> Os.lstat(file.absolutePath).let { st ->
                                runtimeRequire(!file.isFile || st.st_nlink == 1L, "unsafe-runtime-store")
                                listOf(st.st_dev, st.st_ino, st.st_mode.toLong(), st.st_nlink, st.st_size, st.st_mtime, st.st_ctime)
                            } }, phase = { stage -> report.put("state", stage); milestone(stage) },
                            evidence = { key, value -> report.put(key, value); write() }) { source, view ->
                            ClientSessionNative.run(this, runtime, run, source, view, prepared, nativeMonitor(),
                                { text -> observeNative(text, false) }, { key, value -> report.put(key, value); write() })
                        }
                    } finally {
                        // Both native entry points reap their owned readers before returning.
                        // Pre-native/preflight failures acquired no readers; every path retires once.
                        ClientProbeAttempts.retire(run)
                    }
                }
                report.put("runtimePostRehashValidated", true)
            } else if (finishing) {
                milestone("retained-receipt")
                val saved = ClientRuntimeReport.stored(this, guarded = true)
                runtimeRequire(saved != null && ClientGuardedFinish.eligible(ClientRuntimeReport.values(saved.first)), "retained-probe-unavailable")
                val source = saved!!.first
                runtimeRequire(plan.archiveSha256 == source.getString("archiveSha256") && plan.catalogSha256 == source.getString("catalogSha256"), "retained-probe-changed")
                for (key in ClientGuardedFinish.retainedFields) if (source.has(key)) report.put(key, source.get(key))
                report.put("sourceProbeAttemptId", source.getString("attemptId")).put("sourceReportSha256", saved.second)
                    .put("sourceProbeStartedAtEpochMs", source.getLong("startedAtEpochMs"))
                    .put("sourceProbeUpdatedAtEpochMs", source.getLong("updatedAtEpochMs"))
                    .put("sourceProbeElapsedMs", source.getLong("elapsedMs"))
                    .put("nativeProbeReexecuted", false).put("retainedProbeValidated", true)
                write(true)
                store.withVerified(governor::check, progress, milestone = ::milestone, beforeVerify = { runtime ->
                    report.put("state", "retained-verify"); milestone("retained-files")
                    val result = ClientGuardedFinish.validateFiles(filesDir, source.getString("attemptId"), source.getString("viewPlanSha256"),
                        governor::check, governor::beforeIo, links = { file -> Os.lstat(file.absolutePath).st_nlink },
                        stamp = { file -> Os.lstat(file.absolutePath).let { st -> listOf(st.st_dev, st.st_ino, st.st_mode.toLong(), st.st_nlink,
                            st.st_uid.toLong(), st.st_gid.toLong(), st.st_size, st.st_mtime, st.st_ctime) } })
                    runtimeRequire(result.deletion == ClientRuntimeReport.values(source.getJSONObject("windowsDeletion")), "retained-probe-changed")
                    report.put("retainedTranscriptSha256", result.transcriptSha256).put("retainedFilesValidated", true)
                        .put("retainedFilesUnchanged", true)
                    milestone("retained-complete")
                    milestone("runtime-bind-cleanup")
                    report.put("runtimeBindPlaceholdersRemoved", 0); write(true)
                    ClientRuntimeBindings.recover(runtime, plan, android.os.Process.myUid(),
                        stat = { file -> Os.lstat(file.absolutePath).let { ClientRuntimeBindings.Identity(it.st_mode, it.st_uid, it.st_nlink) } },
                        rmdir = ClientRuntimeBindings::deleteEmptyDirectory, active = governor::check,
                        removed = { count -> report.put("runtimeBindPlaceholdersRemoved", count); write(true) })
                    report.put("runtimeBindPlaceholdersChecked", true); write(true)
                }) { }
                report.put("guardedWineStorageValidated", true).put("runtimePostRehashValidated", true)
            } else if (probing) {
                var nextMemory = 0L
                val result = store.withVerified(governor::check, progress, reverifyAfter = true, milestone = ::milestone) { runtime ->
                    report.put("runtimePreRehashValidated", true); write(true)
                    val deadline = SystemClock.elapsedRealtime() + ClientProbeProtocol.WINE_TIMEOUT_MS + 60_000
                    ClientNativeProbe.run(this, runtime, report.getString("attemptId"), {
                        active(); val now = SystemClock.elapsedRealtime()
                        runtimeRequire(now < deadline, "probe-timeout")
                        if (now >= nextMemory) {
                            nextMemory = now + 1000
                            val signal = probeMemory.snapshot()
                            val sample = memorySample(signal) ?: throw RuntimeInstallFailure("memory-observation-unavailable")
                            // Persist the exact triggering sample before controlled teardown.
                            val decision = recordProbeDecision(sample, signal, now)
                            runtimeRequire(!decision.stop, "memory-pressure")
                            runtimeRequire(runtime.usableSpace >= ClientRuntimeStore.RESERVE, "insufficient-space")
                        }
                    }, { stage, output ->
                        phase = stage
                        report.put("state", stage)
                        val observed = ClientProbeEvidence.parse(output)
                        if (guarded) {
                            val guardedObservation = ClientGuardedWine.observe(output)
                            if (guardedObservation.stages.isNotEmpty()) report.put("probeStages", JSONObject(guardedObservation.stages))
                            if (guardedObservation.checks.isNotEmpty()) report.put("windowsChecks", JSONObject(guardedObservation.checks))
                            if (guardedObservation.errors.isNotEmpty()) report.put("windowsErrors", JSONObject(guardedObservation.errors))
                            if (guardedObservation.deletion.isNotEmpty()) report.put("windowsDeletion", JSONObject(guardedObservation.deletion))
                        }
                        for ((key, value) in observed.prefixProgress) report.put(key, value)
                        if (!guarded && observed.stages.isNotEmpty()) report.put("probeStages", JSONObject(observed.stages))
                        if (observed.diagnostics.isNotEmpty()) report.put("probeDiagnostics", JSONArray(observed.diagnostics.toList()))
                        observed.displayEvent?.let { report.put("displayEvent", it) }
                        observed.displayExit?.let { report.put("displayExit", it) }
                        observed.socketStatus?.let { report.put("socketProbeErrno", it) }
                        write()
                    }, { key, value -> report.put(key, value); write(true) }, guarded, governor::beforeIo)
                }
                report.put("ownershipNormalExit", result.normalExit).put("ownershipStopExit", result.stopExit)
                    .put("ownershipKilledExit", result.killedExit).put("stoppedTracees", result.stoppedTracees)
                    .put("killedTracees", result.killedTracees).put("probeStages", JSONObject(result.stages))
                    .put(if (guarded) "guardedWineStorageValidated" else "syntheticMixedRuntimeValidated", true).put("runtimePostRehashValidated", true)
            } else if (verify) store.withVerified(governor::check, progress) { } else
                store.install({ download.open(plan.archiveBytes) }, governor::check, progress)
            milestone("result-commit")
            report.put("state", "complete").put("bytes", plan.fileBytes).put("totalBytes", plan.fileBytes).put("entries", plan.entries.size)
            write(true)
        } catch (e: Exception) {
            val cause = (e as? ClientFailureEvidence.AtPhase)?.cause ?: e
            report.put("failurePhase", (e as? ClientFailureEvidence.AtPhase)?.phase ?: phase)
                .put("failureCategory", ClientFailureEvidence.category(e))
            var nested: Throwable? = cause
            repeat(8) {
                val current = nested
                if (current is android.system.ErrnoException) report.put("failureErrno", current.errno)
                nested = current?.cause
            }
            val reason = if (cancelled.get()) { if (timedOut.get()) "timeout" else "cancelled" }
                else (cause as? RuntimeInstallFailure)?.reason ?: (cause as? ClientImportFailure)?.reason ?: "io-failed"
            report.put("state", if (cancelled.get()) "stopped" else "failed")
                .put("reason", reason.takeIf { it in ClientRuntimeReport.reasons || (original && (it in ClientImportReport.reasons || it in setOf("client-config-refused", "client-startup-failed"))) } ?: "io-failed")
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
        const val GUARDED_FINISH = "com.russianranger.fta.FINISH_GUARDED_WINE"
        const val ORIGINAL = "com.russianranger.fta.TEST_ORIGINAL_CLIENT_STARTUP"
        const val GUARDED_PROBE = "com.russianranger.fta.TEST_GUARDED_WINE"
        const val ISOLATE = "com.russianranger.fta.TEST_CLIENT_STORAGE_ISOLATION"
        const val STOP = "com.russianranger.fta.STOP_CLIENT_RUNTIME"
        const val BIND = "com.russianranger.fta.BIND_CLIENT_RUNTIME"
        private const val CHANNEL = "fta-client-runtime"
        @Suppress("DEPRECATION")
        fun running(context: Context) = context.getSystemService(ActivityManager::class.java).getRunningServices(30)
            .any { it.service.className == ClientRuntimeService::class.java.name && it.started }
    }
}
