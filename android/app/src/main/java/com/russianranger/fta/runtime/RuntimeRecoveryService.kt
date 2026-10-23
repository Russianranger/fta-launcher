package com.russianranger.fta.runtime

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.AtomicFile
import com.russianranger.fta.MainActivity
import com.russianranger.fta.R
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Surviving observer in the UI process; only the separate test runtime may be killed. */
class RuntimeRecoveryService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var running = false
    @Volatile private var workerStarted = false
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var attemptId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { cancelled.set(true); if (!started) stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true; running = true
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Runtime recovery tests", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 12, Intent(this, RuntimeRecoveryService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_ashes)
            .setContentTitle("From the Ashes runtime recovery test")
            .setContentText("Testing Stop and recovery using a disposable database.")
            .setContentIntent(content).setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(42, notification)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fta:runtimeRecovery")
            .apply { acquire(600_000) }
        executor.execute {
            try {
                @Suppress("DEPRECATION")
                check(getSystemService(ActivityManager::class.java).getRunningServices(20)
                    .none { it.service.className == RuntimeDiagnosticService::class.java.name }) { "Another diagnostic is active" }
                val scope = File(filesDir, "runtime").apply { check(mkdirs() || isDirectory) }
                val id = UUID.randomUUID().toString().also { attemptId = it }
                atomic(File(scope, "diagnostic-attempt.id"), id)
                atomic(File(scope, "diagnostic-scope.txt"), "runtime-recovery")
                val store = RecoveryStore(File(scope, "recovery-attempts/$id"), id)
                val observed = RuntimeRecoveryProtocol.fields.associateWith { false }.toMutableMap()
                fun receipt() = atomic(File(scope, "runtime-recovery.json"), JSONObject(observed as Map<*, *>)
                    .put("formatVersion", 1).put("attemptId", id).put("seedImported", false).toString())
                receipt()
                val end = System.nanoTime() + 540_000_000_000L
                val driver = object : RuntimeRecoveryProtocol.Driver {
                    override fun start(phase: RecoveryPhase) {
                        checkActive()
                        writeStatus("RUNTIME_RECOVERY_RUNNING", when (phase) {
                            RecoveryPhase.CANCEL -> "Testing the app's Stop action with an active disposable transaction."
                            RecoveryPhase.CRASH -> "Testing automatic cleanup when the separate runtime service dies."
                            RecoveryPhase.RESUME -> "Recovering the same disposable database and checking its committed data."
                        })
                        startForegroundService(Intent(this@RuntimeRecoveryService, RuntimeDiagnosticService::class.java)
                            .setAction(RuntimeDiagnosticService.ACTION_RECOVERY_WORKER)
                            .putExtra(RuntimeDiagnosticService.EXTRA_ATTEMPT, id)
                            .putExtra(RuntimeDiagnosticService.EXTRA_PHASE, phase.name))
                        workerStarted = true
                    }
                    override fun checkpoint(phase: RecoveryPhase) = store.read(phase)
                    override fun stopAction() { RuntimeDiagnosticService.stopPendingIntent(this@RuntimeRecoveryService).send() }
                    override fun killOwner() {
                        checkActive()
                        startService(Intent(this@RuntimeRecoveryService, RuntimeDiagnosticService::class.java)
                            .setAction(RuntimeDiagnosticService.ACTION_RECOVERY_KILL).putExtra(RuntimeDiagnosticService.EXTRA_ATTEMPT, id))
                    }
                    override fun ended(identity: ProcessIdentity) = ProcessObservation.ended(identity)
                    override fun checkActive() { check(!cancelled.get() && System.nanoTime() < end) { "Recovery diagnostic cancelled or timed out" } }
                    override fun pause() { checkActive(); Thread.sleep(100) }
                    override fun nowMs() = System.nanoTime() / 1_000_000
                }
                RuntimeRecoveryProtocol.run(driver) { observed[it] = true; receipt() }
                // Independently require all phase journals before publishing a passing status.
                check(RuntimeRecoveryEvidence.read(filesDir, id, observed).qualified)
                writeStatus(RuntimeRecoveryProtocol.PASSED,
                    "Stop cleanup, runtime service death, termination of both owned native processes, recovery of the same disposable database, transaction rollback and final SQL shutdown passed. Imported inputs were retained. Persistent realm storage and gameplay remain separate gates.")
            } catch (_: Exception) {
                if (workerStarted) runCatching { RuntimeDiagnosticService.stopPendingIntent(this).send() }
                writeStatus(if (cancelled.get()) "STOPPED" else "FAILED",
                    if (cancelled.get()) "Runtime recovery test stopped. Imported inputs were retained."
                    else "Runtime recovery test did not qualify. Export the diagnostic report; imported inputs were retained.")
            } finally {
                running = false
                releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private fun writeStatus(state: String, text: String) {
        val id = attemptId ?: return
        val scope = File(filesDir, "runtime")
        atomic(File(scope, "diagnostic-status.txt"), "$state\n${System.currentTimeMillis()}\n$text")
        atomic(File(scope, "diagnostic-status-attempt.id"), id)
    }
    override fun onDestroy() {
        cancelled.set(true)
        if (running && workerStarted) runCatching { RuntimeDiagnosticService.stopPendingIntent(this).send() }
        executor.shutdown(); releaseWakeLock(); super.onDestroy()
    }
    @Synchronized private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
    companion object {
        const val ACTION_START = "com.russianranger.fta.CHECK_RUNTIME_RECOVERY"
        const val ACTION_STOP = "com.russianranger.fta.STOP_RUNTIME_RECOVERY"
        private const val CHANNEL = "fta-runtime-recovery"
        fun atomic(file: File, value: String) {
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(value.toByteArray()); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
    }
}
