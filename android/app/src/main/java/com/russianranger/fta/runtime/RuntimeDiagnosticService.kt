package com.russianranger.fta.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.AtomicFile
import com.russianranger.fta.MainActivity
import com.russianranger.fta.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit user-driven disposable database probe, isolated from the UI process. */
class RuntimeDiagnosticService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var diagnostic: DatabaseDiagnostic? = null
    private var started = false
    private var attemptId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastStage = "CHECKING"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled.set(true)
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action !in setOf(ACTION_CHECK, ACTION_CHECK_AUTH, ACTION_CHECK_FULL_SEED, ACTION_CHECK_WORLD_UPDATER)) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        val includeAuth = intent?.action == ACTION_CHECK_AUTH
        val includeFullSeed = intent?.action == ACTION_CHECK_FULL_SEED
        val includeWorldUpdater = intent?.action == ACTION_CHECK_WORLD_UPDATER
        val scope = File(filesDir, "runtime").apply { mkdirs() }
        attemptId = java.util.UUID.randomUUID().toString()
        writeAtomicText(File(scope, "diagnostic-attempt.id"), attemptId!!)
        writeStatus("CHECKING", "Preparing a fresh native diagnostic attempt.")
        File(scope, "diagnostic-scope.txt").writeText(if (includeWorldUpdater) "compiled-updater" else if (includeFullSeed) "full-seed" else if (includeAuth) "auth-updater" else "database")
        if (includeAuth) AtomicFile(File(scope, "auth-diagnostic.json")).delete()
        if (includeFullSeed || includeWorldUpdater) AtomicFile(File(scope, "full-seed-diagnostic.json")).delete()
        if (includeWorldUpdater) AtomicFile(File(scope, "world-updater-diagnostic.json")).delete()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Local database diagnostics", NotificationManager.IMPORTANCE_LOW))
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, RuntimeDiagnosticService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ashes)
            .setContentTitle(if (includeWorldUpdater) "From the Ashes compiled updater test" else if (includeFullSeed) "From the Ashes full seed test" else if (includeAuth) "From the Ashes auth updater test" else "From the Ashes database test")
            .setContentText("Testing a fresh private database. Existing saves are untouched.")
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fta:databaseDiagnostic").apply { acquire(if (includeWorldUpdater) 2_580_000L else if (includeFullSeed) 1_980_000L else if (includeAuth) 480_000L else 360_000L) }
        worker.execute {
            try {
                writeStatus("CHECKING", "Verifying immutable packaged provider files.")
                val test = DatabaseDiagnostic(this, cancelled, ::writeStatus, includeAuth, includeFullSeed, includeWorldUpdater)
                diagnostic = test
                val result = test.run()
                writeStatus(if (includeWorldUpdater) WorldUpdaterEvidence.PASSED else if (includeFullSeed) "FULL_SEED_DIAGNOSTIC_PASSED" else if (includeAuth) "AUTH_UPDATER_DIAGNOSTIC_PASSED" else "DATABASE_DIAGNOSTIC_PASSED", result)
            } catch (_: Exception) {
                writeStatus(if (cancelled.get()) "STOPPED" else "FAILED",
                    if (cancelled.get()) "Database diagnostic stopped. Existing saves were untouched."
                    else "Native database diagnostic failed during $lastStage. Private native logs are retained; existing saves were untouched.")
            } catch (_: LinkageError) {
                writeStatus("FAILED", "The packaged process supervisor could not be loaded. No existing database was opened.")
            } finally {
                diagnostic?.stopOwned()
                diagnostic = null
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun writeStatus(state: String, message: String) {
        lastStage = state
        val directory = File(filesDir, "runtime").apply { mkdirs() }
        val status = AtomicFile(File(directory, "diagnostic-status.txt"))
        val output = status.startWrite()
        try {
            output.write("$state\n${System.currentTimeMillis()}\n${message.take(4096)}".toByteArray())
            status.finishWrite(output)
        } catch (failure: Exception) { status.failWrite(output); throw failure }
        attemptId?.let { writeAtomicText(File(directory, "diagnostic-status-attempt.id"), it) }
    }

    private fun writeAtomicText(file: File, text: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray()); atomic.finishWrite(output) }
        catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }

    override fun onDestroy() {
        cancelled.set(true)
        // Ordinary shutdown is bounded by the worker; unexpected JVM death is handled by
        // the native helper's process groups and Linux parent-death signal before exec.
        diagnostic?.stopOwned()
        worker.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    @Synchronized private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val ACTION_CHECK = "com.russianranger.fta.CHECK_NATIVE_DATABASE"
        const val ACTION_CHECK_AUTH = "com.russianranger.fta.CHECK_NATIVE_AUTH_UPDATER"
        const val ACTION_CHECK_FULL_SEED = "com.russianranger.fta.CHECK_NATIVE_FULL_SEED"
        const val ACTION_CHECK_WORLD_UPDATER = "com.russianranger.fta.CHECK_NATIVE_WORLD_UPDATER"
        const val ACTION_STOP = "com.russianranger.fta.STOP_NATIVE_DATABASE_CHECK"
        private const val CHANNEL = "fta-native-diagnostics"
        private const val NOTIFICATION = 41
    }
}
