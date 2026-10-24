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

/** Explicit user-driven diagnostics and retained local service, isolated from the UI process. */
class RuntimeDiagnosticService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val gracefulStop = AtomicBoolean(false)
    private var localService = false
    @Volatile private var diagnostic: DatabaseDiagnostic? = null
    private var started = false
    private var attemptId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var lastStage = "CHECKING"
    private var recoveryStore: RecoveryStore? = null
    private var recoveryPhase: RecoveryPhase? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECOVERY_KILL) {
            val store = recoveryStore
            val ready = runCatching { store?.read(RecoveryPhase.CRASH) }.getOrNull()
            if (started && recoveryPhase == RecoveryPhase.CRASH && intent.getStringExtra(EXTRA_ATTEMPT) == store?.attemptId &&
                ready != null && RuntimeRecoveryProtocol.ready(ready) && runCatching {
                    ready.owner!!.sameProcess(ProcessObservation.self(android.os.Process.myPid())) &&
                        ready.children.none { ProcessObservation.ended(it) }
                }.getOrDefault(false)) {
                // Never signal a caller-supplied PID or the UI/controller process.
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            if (localService) gracefulStop.set(true) else cancelled.set(true)
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action !in setOf(ACTION_CHECK, ACTION_CHECK_AUTH, ACTION_CHECK_FULL_SEED, ACTION_CHECK_WORLD_UPDATER, ACTION_CHECK_LIFECYCLE, ACTION_RECOVERY_WORKER, ACTION_REALM_STORAGE, ACTION_REALM_BACKUP, ACTION_LOCAL_AUTH, ACTION_LOCAL_SERVICE, ACTION_IMPORT_GAME_DATA, ACTION_VERIFY_GAME_DATA)) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        localService = intent?.action == ACTION_LOCAL_SERVICE
        val includeGameData = intent?.action in setOf(ACTION_IMPORT_GAME_DATA, ACTION_VERIFY_GAME_DATA)
        val gameDataUri = if (intent?.action == ACTION_IMPORT_GAME_DATA) intent.data else null
        val includeAuth = intent?.action == ACTION_CHECK_AUTH
        val includeFullSeed = intent?.action == ACTION_CHECK_FULL_SEED
        val includeWorldUpdater = intent?.action == ACTION_CHECK_WORLD_UPDATER
        val includeLifecycle = intent?.action == ACTION_CHECK_LIFECYCLE
        val includeRealm = intent?.action == ACTION_REALM_STORAGE
        val includeBackup = intent?.action == ACTION_REALM_BACKUP
        val includeLocalAuth = intent?.action == ACTION_LOCAL_AUTH
        val scope = File(filesDir, "runtime").apply { mkdirs() }
        if (intent?.action == ACTION_RECOVERY_WORKER) {
            val requested = intent.getStringExtra(EXTRA_ATTEMPT).orEmpty()
            val phase = runCatching { RecoveryPhase.valueOf(intent.getStringExtra(EXTRA_PHASE).orEmpty()) }.getOrNull()
            val current = File(scope, "diagnostic-attempt.id").takeIf { it.isFile && it.length() <= 64 }?.readText()?.trim()
            if (!NativeInvocationJournal.validAttemptId(requested) || phase == null || requested != current ||
                runCatching { File(scope, "diagnostic-scope.txt").readText() }.getOrNull() != "runtime-recovery") { stopSelf(); return START_NOT_STICKY }
            attemptId = requested
            recoveryPhase = phase
            recoveryStore = RecoveryStore(File(scope, "recovery-attempts/$requested"), requested).also {
                if (it.read(phase) != null) { stopSelf(); return START_NOT_STICKY }
                it.write(RecoveryWorkerRecord(phase))
            }
        } else {
            attemptId = java.util.UUID.randomUUID().toString()
            writeAtomicText(File(scope, "diagnostic-attempt.id"), attemptId!!)
        }
        writeStatus("CHECKING", "Preparing a fresh native diagnostic attempt.")
        if (recoveryStore == null) File(scope, "diagnostic-scope.txt").writeText(if (includeGameData) "game-data" else if (localService) "local-service" else if (includeLocalAuth) "local-auth" else if (includeBackup) "realm-backup" else if (includeRealm) "realm-storage" else if (includeLifecycle) "database-lifecycle" else if (includeWorldUpdater) "compiled-updater" else if (includeFullSeed) "full-seed" else if (includeAuth) "auth-updater" else "database")
        if (includeLifecycle) AtomicFile(File(scope, "database-lifecycle.json")).delete()
        if (includeAuth) AtomicFile(File(scope, "auth-diagnostic.json")).delete()
        if (includeFullSeed || includeWorldUpdater) AtomicFile(File(scope, "full-seed-diagnostic.json")).delete()
        if (includeWorldUpdater) AtomicFile(File(scope, "world-updater-diagnostic.json")).delete()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Local database and auth", NotificationManager.IMPORTANCE_LOW))
        val content = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = stopPendingIntent(this)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_ashes)
            .setContentTitle(if (includeGameData) "From the Ashes game-data import" else if (localService) "From the Ashes local service" else if (includeLocalAuth) "From the Ashes local auth service" else if (includeBackup) "From the Ashes realm backup" else if (includeRealm) "From the Ashes persistent realm storage" else if (includeLifecycle) "From the Ashes database lifecycle test" else if (includeWorldUpdater) "From the Ashes compiled updater test" else if (includeFullSeed) "From the Ashes full seed test" else if (includeAuth) "From the Ashes auth updater test" else "From the Ashes database test")
            .setContentText(if (includeGameData) "Checking game assets. Stop cancels this import safely." else if (localService) "Database and local auth session. Use Stop to shut down cleanly." else if (includeLocalAuth) "Checking the retained database and local auth listener." else if (includeBackup) "Backing up and verifying an isolated restore." else if (includeRealm) "Preparing or checking retained realm storage." else "Testing a fresh private database. Existing saves are untouched.")
            .setContentIntent(content)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fta:databaseDiagnostic").apply { acquire(if (includeGameData) 3_660_000L else if (localService) 2_160_000L else if (includeBackup) 1_980_000L else if (includeRealm) 3_180_000L else if (includeWorldUpdater) 2_580_000L else if (includeFullSeed) 1_980_000L else if (includeAuth) 480_000L else 360_000L) }
        worker.execute {
            try {
                writeStatus("CHECKING", "Verifying immutable packaged provider files.")
                if (includeGameData) {
                    require(intent?.action != ACTION_IMPORT_GAME_DATA || gameDataUri != null)
                    try {
                        val result = GameDataImport.run(this, gameDataUri, cancelled, ::writeStatus)
                        writeStatus(GameDataImport.PASSED, result)
                    } catch (failure: GameDataRejected) {
                        writeStatus("GAME_DATA_REJECTED", "Game-data preflight found ${failure.preflight.problemCount} missing or incompatible files. Your previous game data and retained database are preserved. Export the report for the exact format results.")
                    }
                    return@execute
                }
                if (localService) {
                    val result = LocalServiceRuntime.run(this, cancelled, gracefulStop, ::writeStatus) { diagnostic = it }
                    writeStatus(LocalServiceRuntime.STOPPED, result)
                    return@execute
                }
                if (includeLocalAuth) {
                    val result = LocalAuthDiagnostic.run(this, cancelled, ::writeStatus) { diagnostic = it }
                    writeStatus(LocalAuthDiagnostic.PASSED, result)
                    return@execute
                }
                if (includeBackup) {
                    val result = RealmBackupDiagnostic.run(this, cancelled, ::writeStatus) { diagnostic = it }
                    writeStatus(RealmBackupDiagnostic.PASSED, result)
                    return@execute
                }
                if (includeRealm) {
                    val result = RealmStorageDiagnostic.run(this, cancelled, ::writeStatus) { diagnostic = it }
                    writeStatus(RealmStorageDiagnostic.PASSED, result)
                    return@execute
                }
                val test = DatabaseDiagnostic(this, cancelled, ::writeStatus, includeAuth, includeFullSeed, includeWorldUpdater, includeLifecycle, recoveryStore, recoveryPhase)
                diagnostic = test
                val result = test.run()
                writeStatus(if (includeLifecycle) DatabaseLifecycleProbe.PASSED else if (includeWorldUpdater) WorldUpdaterEvidence.PASSED else if (includeFullSeed) "FULL_SEED_DIAGNOSTIC_PASSED" else if (includeAuth) "AUTH_UPDATER_DIAGNOSTIC_PASSED" else "DATABASE_DIAGNOSTIC_PASSED", result)
            } catch (failure: Exception) {
                recoveryStore?.state(recoveryPhase!!, if (cancelled.get()) "STOPPED" else "FAILED")
                writeStatus(if (cancelled.get()) "STOPPED" else "FAILED",
                    if (includeGameData) GameDataImport.failureSummary(failure, cancelled.get())
                    else if (localService) "Local service failed during $lastStage. The realm and verified backup are retained. Export the diagnostic report before retrying."
                    else if (includeLocalAuth) "Local auth service ${if (cancelled.get()) "stopped" else "failed"} during $lastStage. The realm and verified backup are retained. Normal auth startup may mark realms offline. Export the diagnostic report."
                    else if (cancelled.get()) "Database diagnostic stopped. Existing saves were untouched."
                    else "Native database diagnostic failed during $lastStage. Private native logs are retained; existing saves were untouched.")
            } catch (_: LinkageError) {
                recoveryStore?.state(recoveryPhase!!, "FAILED")
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
        if (recoveryStore != null) return // The independent controller owns overall status.
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
        recoveryStore?.let { store ->
            runCatching {
                val phase = recoveryPhase!!
                val state = store.read(phase)?.state?.takeIf { it in setOf("STOPPED", "RECOVERED", "FAILED") } ?: "FAILED"
                store.state(phase, state, destroyed = true)
            }
        }
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
        const val ACTION_CHECK_LIFECYCLE = "com.russianranger.fta.CHECK_NATIVE_DATABASE_LIFECYCLE"
        const val ACTION_REALM_STORAGE = "com.russianranger.fta.PREPARE_REALM_STORAGE"
        const val ACTION_IMPORT_GAME_DATA = "com.russianranger.fta.IMPORT_GAME_DATA"
        const val ACTION_VERIFY_GAME_DATA = "com.russianranger.fta.VERIFY_GAME_DATA"
        const val ACTION_LOCAL_SERVICE = "com.russianranger.fta.START_LOCAL_SERVICE"
        const val ACTION_LOCAL_AUTH = "com.russianranger.fta.CHECK_LOCAL_AUTH"
        const val ACTION_REALM_BACKUP = "com.russianranger.fta.VERIFY_REALM_BACKUP"
        const val ACTION_RECOVERY_WORKER = "com.russianranger.fta.CHECK_RUNTIME_RECOVERY_WORKER"
        const val ACTION_RECOVERY_KILL = "com.russianranger.fta.KILL_RUNTIME_RECOVERY_OWNER"
        const val EXTRA_ATTEMPT = "recoveryAttempt"
        const val EXTRA_PHASE = "recoveryPhase"
        const val ACTION_STOP = "com.russianranger.fta.STOP_NATIVE_DATABASE_CHECK"
        private const val CHANNEL = "fta-native-diagnostics"
        private const val NOTIFICATION = 41
        fun stopPendingIntent(context: android.content.Context): PendingIntent = PendingIntent.getService(context, 1,
            Intent(context, RuntimeDiagnosticService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    }
}
