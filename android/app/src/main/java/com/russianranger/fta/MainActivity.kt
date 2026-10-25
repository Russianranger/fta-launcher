package com.russianranger.fta

import android.app.Activity
import android.app.ActivityManager
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.russianranger.fta.runtime.RuntimeDiagnosticService
import com.russianranger.fta.runtime.RuntimeRecoveryService
import java.io.File
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import com.russianranger.fta.runtime.GameDataBinding
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val app get() = application as FtaApplication
    private val ink = Color.rgb(238, 229, 213)
    private val muted = Color.rgb(177, 168, 151)
    private val gold = Color.rgb(214, 170, 101)
    private lateinit var gameDataButton: Button
    private lateinit var gameDataVerifyButton: Button
    private lateinit var gameDataStatus: TextView
    private lateinit var clientInspectButton: Button
    private lateinit var clientCancelButton: Button
    private lateinit var clientStatus: TextView
    private var pendingGameData = false
    private var pendingGameDataUri: android.net.Uri? = null
    private lateinit var sourceButton: Button
    private lateinit var databaseButton: Button
    private lateinit var sourceStatus: TextView
    private lateinit var databaseStatus: TextView
    private lateinit var progressLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var logText: TextView
    private lateinit var countText: TextView
    private lateinit var providerStatus: TextView
    private lateinit var serverStatus: TextView
    private lateinit var authTestHelp: TextView
    private lateinit var diagnosticStatus: TextView
    private lateinit var runtimeBundleStatus: TextView
    private lateinit var runtimeBundleButton: Button
    private lateinit var diagnosticButton: Button
    private lateinit var recoveryDiagnosticButton: Button
    private lateinit var realmStorageButton: Button
    private lateinit var localServiceButton: Button
    private lateinit var worldServiceButton: Button
    private lateinit var localAuthButton: Button
    private lateinit var realmBackupButton: Button
    private lateinit var exportBackupButton: Button
    private lateinit var lifecycleDiagnosticButton: Button
    private lateinit var authDiagnosticButton: Button
    private lateinit var fullSeedDiagnosticButton: Button
    private lateinit var worldUpdaterDiagnosticButton: Button
    private lateinit var worldUpdaterTestHelp: TextView
    private var pendingRecoveryCheck = false
    private var pendingRealmStorage = false
    private var pendingLocalService = false
    private var pendingWorldService = false
    private var pendingLocalAuth = false
    private var pendingRealmBackup = false
    private var pendingLifecycleCheck = false
    private var pendingAuthCheck = false
    private var pendingFullSeedCheck = false
    private var pendingWorldUpdaterCheck = false
    private lateinit var diagnosticStopButton: Button
    private lateinit var exportReportButton: Button
    private val refresh = Handler(Looper.getMainLooper())
    private var requestedAt = 0L
    private var activityVisible = false
    private val gameDataConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: ComponentName?) { gameDataBinding.release() }
        override fun onBindingDied(name: ComponentName?) { gameDataBinding.release() }
        override fun onNullBinding(name: ComponentName?) { gameDataBinding.release() }
    }
    private val gameDataBinding: GameDataBinding by lazy { GameDataBinding(
        { bindService(Intent(this, RuntimeDiagnosticService::class.java).setAction(RuntimeDiagnosticService.ACTION_BIND_GAME_DATA),
            gameDataConnection, BIND_IMPORTANT or BIND_ADJUST_WITH_ACTIVITY) },
        { unbindService(gameDataConnection) }) }
    private val refresher = object : Runnable {
        override fun run() { update(); refresh.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingGameData = savedInstanceState?.getBoolean("pendingGameData") ?: false
        pendingGameDataUri = savedInstanceState?.getString("pendingGameDataUri")?.let(android.net.Uri::parse)
        pendingRecoveryCheck = savedInstanceState?.getBoolean("pendingRecoveryCheck") ?: false
        pendingRealmStorage = savedInstanceState?.getBoolean("pendingRealmStorage") ?: false
        pendingWorldService = savedInstanceState?.getBoolean("pendingWorldService") ?: false
        pendingLocalService = savedInstanceState?.getBoolean("pendingLocalService") ?: false
        pendingLocalAuth = savedInstanceState?.getBoolean("pendingLocalAuth") ?: false
        pendingRealmBackup = savedInstanceState?.getBoolean("pendingRealmBackup") ?: false
        pendingLifecycleCheck = savedInstanceState?.getBoolean("pendingLifecycleCheck") ?: false
        pendingAuthCheck = savedInstanceState?.getBoolean("pendingAuthCheck") ?: false
        pendingFullSeedCheck = savedInstanceState?.getBoolean("pendingFullSeedCheck") ?: false
        pendingWorldUpdaterCheck = savedInstanceState?.getBoolean("pendingWorldUpdaterCheck") ?: false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(21, 20, 18))
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(dp(20) + insets.systemWindowInsetLeft,
                    dp(12) + insets.systemWindowInsetTop,
                    dp(20) + insets.systemWindowInsetRight,
                    dp(12) + insets.systemWindowInsetBottom)
                insets
            }
        }
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.addView(label("FROM THE ASHES", 24, gold, true), LinearLayout.LayoutParams(0, -2, 1f))
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty().substringBefore('-')
        title.addView(label("DIAGNOSTIC $version", 12, muted))
        root.addView(title)
        root.addView(label("Your realm begins here", 15, ink).apply { setPadding(0, dp(4), 0, dp(12)) })

        val wide = resources.configuration.screenWidthDp >= 760
        val body = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val checklist = panel()
        checklist.addView(label("Prepare your server", 21, ink, true))
        countText = label("", 13, gold).apply { setPadding(0, dp(6), 0, dp(10)) }
        checklist.addView(countText)
        sourceStatus = label("", 16, ink, true)
        checklist.addView(sourceStatus)
        checklist.addView(label("Select From_The_Ashes_Source.zip (193 MiB).", 13, muted))
        sourceButton = action("Select source ZIP") { selectArchive(101) }
        checklist.addView(sourceButton)
        databaseStatus = label("", 16, ink, true).apply { setPadding(0, dp(12), 0, 0) }
        checklist.addView(databaseStatus)
        checklist.addView(label("Select Database.zip (99 MiB). Kept private; fresh-install seed only.", 13, muted))
        databaseButton = action("Select database ZIP") { selectArchive(102) }
        checklist.addView(databaseButton)
        checklist.addView(label("○  Native database provider", 16, ink, true).apply { setPadding(0, dp(16), 0, 0) })
        providerStatus = label("", 13, muted)
        checklist.addView(providerStatus)
        checklist.addView(label("○  Auth and world servers", 16, ink, true).apply { setPadding(0, dp(14), 0, 0) })
        serverStatus = label("", 13, muted)
        checklist.addView(serverStatus)
        checklist.addView(label("○  Matching 3.3.5 game data", 16, ink, true).apply { setPadding(0, dp(14), 0, 0) })
        gameDataStatus = label("", 13, muted)
        checklist.addView(gameDataStatus)
        gameDataButton = action("Import extracted game-data ZIP") { selectArchive(106) }
        checklist.addView(gameDataButton)
        gameDataVerifyButton = action("Verify imported game data") { pendingGameDataUri = null; requestDatabaseCheck(includeGameData = true) }
        checklist.addView(gameDataVerifyButton)
        checklist.addView(label("Select the original Data.zip containing dbc, maps, vmaps and mmaps. Stock DBC backups and unused DB2 companion files can stay in the archive. Keep enough internal storage for both ZIP and unpacked files. This does not import a full client or MPQ archives.", 13, muted))

        val status = panel()
        status.addView(label("Runtime", 21, ink, true))
        status.addView(label("Local realm preparation", 15, gold, true).apply { setPadding(0, dp(8), 0, dp(4)) })
        status.addView(label("Prepare retained realm storage here. Game-server startup still requires matching game data and service orchestration.", 14, muted))
        status.addView(action("Start game server") {}.apply { isEnabled = false })
        realmStorageButton = action("Prepare / verify persistent realm") { requestDatabaseCheck(includeRealm = true) }
        status.addView(realmStorageButton)
        status.addView(label("Creates the realm database once, then checks two restarts. Later runs reuse it without importing the seed. Keep 3 GiB free for first preparation; existing realm data is retained. World startup still awaits matching game data.", 13, muted))
        localServiceButton = action("Start local database + auth") { requestDatabaseCheck(includeLocalService = true) }
        status.addView(localServiceButton)
        worldServiceButton = action("Start native world test") { requestDatabaseCheck(includeWorldService = true) }
        status.addView(worldServiceButton)
        status.addView(label("Verifies retained game files, then starts the original world with your retained realm. Keep this screen open until health checks begin. Lock the screen for three minutes, reopen, Stop and export the report.", 13, muted))
        status.addView(label("Keeps your retained database and local auth running until Stop, with a 30-minute session limit. Use the notification to stop cleanly. Game login and world startup await matching game data.", 13, muted))
        localAuthButton = action("Test local auth service") { requestDatabaseCheck(includeLocalAuth = true) }
        status.addView(localAuthButton)
        status.addView(label("Requires a verified realm backup. Opens the retained database, checks two local auth listener starts and clean stops, then shuts down the database. No game account is created. Allow about two minutes.", 13, muted))
        realmBackupButton = action("Create / verify realm backup") { requestDatabaseCheck(includeBackup = true) }
        status.addView(realmBackupButton)
        status.addView(label("Backs up the stopped realm and verifies two restarts of an isolated restored copy. The active realm is preserved. Allow space for two additional database copies.", 13, muted))
        exportBackupButton = action("Export verified realm backup") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "FTA-realm-backup-${System.currentTimeMillis()}.zip")
            }, 105)
        }
        status.addView(exportBackupButton)
        status.addView(label("The backup includes private account data and database credentials. Keep it private; share only the diagnostic JSON for testing.", 13, muted))
        diagnosticButton = action("Test native database") { requestDatabaseCheck() }
        status.addView(diagnosticButton)
        lifecycleDiagnosticButton = action("Test database lifecycle") { requestDatabaseCheck(includeLifecycle = true) }
        status.addView(lifecycleDiagnosticButton)
        status.addView(label("Tests cancellation, restart and crash recovery without importing the seed. When the screen-off stage appears, lock the Thor for two minutes, then export the report.", 13, muted))
        recoveryDiagnosticButton = action("Test runtime recovery") { requestDatabaseCheck(includeRecovery = true) }
        status.addView(recoveryDiagnosticButton)
        status.addView(label("Automatically tests Stop and recovery after the runtime service dies. Keep this screen open until it finishes, then export the report.", 13, muted))
        authDiagnosticButton = action("Test original auth updater") { requestDatabaseCheck(true) }
        status.addView(authDiagnosticButton)
        authTestHelp = label("For the auth test, only Database.zip is needed. It imports the matching auth files into a fresh temporary database. No source ZIP is needed.", 13, muted)
        status.addView(authTestHelp)
        fullSeedDiagnosticButton = action("Test full original database seed") { requestDatabaseCheck(includeFullSeed = true) }
        status.addView(fullSeedDiagnosticButton)
        status.addView(label("Imports all 563 tables and the world trigger into a fresh temporary database, then checks the original update receipts. Requires Database.zip and 3 GiB free storage; allow up to 30 minutes. Keep the test running or use Stop to cancel.", 13, muted))
        worldUpdaterDiagnosticButton = action("Test compiled three-database updater") { requestDatabaseCheck(includeWorldUpdater = true) }
        status.addView(worldUpdaterDiagnosticButton)
        worldUpdaterTestHelp = label("Imports a fresh full seed, then runs the original compiled updaters and checks exact resulting receipts and schema metadata. Requires Database.zip and 3 GiB free; allow up to 40 minutes. This test stops at missing maps and does not qualify world startup or gameplay.", 13, muted)
        status.addView(worldUpdaterTestHelp)
        status.addView(label("Creates a fresh disposable database, tests local operation, then shuts it down. Your imported seed and saves are untouched.", 13, muted))
        diagnosticStopButton = action("Stop local service / test") {
            if (runtimeRecoveryRunning()) startService(Intent(this, RuntimeRecoveryService::class.java).setAction(RuntimeRecoveryService.ACTION_STOP))
            else RuntimeDiagnosticService.stopPendingIntent(this).send()
        }
        status.addView(diagnosticStopButton)
        diagnosticStatus = label("", 13, ink).apply { setPadding(0, dp(8), 0, dp(8)) }
        status.addView(diagnosticStatus)
        exportReportButton = action("Export diagnostic report") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "FTA-database-diagnostic-${System.currentTimeMillis()}.json")
            }, 104)
        }
        status.addView(exportReportButton)
        status.addView(label("Advanced runtime bundle validation", 16, ink, true).apply { setPadding(0, dp(12), 0, 0) })
        runtimeBundleStatus = label("", 13, muted)
        status.addView(runtimeBundleStatus)
        runtimeBundleButton = action("Select runtime bundle ZIP") { selectArchive(103) }
        status.addView(runtimeBundleButton)
        status.addView(label("WarcraftXL client", 17, ink, true).apply { setPadding(0, dp(16), 0, dp(8)) })
        clientInspectButton = action("Inspect client folder") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }, 107)
        }
        status.addView(clientInspectButton)
        clientCancelButton = action("Cancel client inspection") { app.cancelClientInspection() }
        status.addView(clientCancelButton)
        clientStatus = label("", 13, muted)
        status.addView(clientStatus)
        status.addView(label("Select your complete extracted client folder containing Wow.exe. Keep this screen open, then export the diagnostic report. This read-only check counts files and inspects executable metadata; full import and client launch come later.", 13, muted))
        progressLabel = label("", 14, ink)
        status.addView(progressLabel)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            contentDescription = "Archive copy progress"
        }
        status.addView(progress, LinearLayout.LayoutParams(-1, dp(14)))
        status.addView(label("Setup log", 17, ink, true).apply { setPadding(0, dp(18), 0, dp(8)) })
        logText = label("", 12, muted).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        status.addView(logText)

        if (wide) {
            body.addView(scroll(checklist), LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginEnd = dp(14) })
            body.addView(scroll(status), LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            body.addView(checklist, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
            body.addView(status, LinearLayout.LayoutParams(-1, -2))
            root.addView(scroll(body), LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        root.requestApplyInsets()
        update()
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        app.observer = { update() }
        refresh.post(refresher)
    }

    override fun onStop() {
        activityVisible = false
        gameDataBinding.release()
        app.observer = null
        refresh.removeCallbacks(refresher)
        super.onStop()
    }

    private fun update() {
        val source = app.store?.receipt(Inputs.source) != null
        val database = app.store?.receipt(Inputs.database) != null
        sourceStatus.text = "${if (source) "✓" else "○"}  Server source ${if (source) "verified" else "ZIP"}"
        databaseStatus.text = "${if (database) "✓" else "○"}  Database seed ${if (database) "verified" else "ZIP"}"
        countText.text = "${listOf(source, database).count { it }} verified inputs · server qualification pending"
        providerStatus.text = app.providerMessage
        val testing = databaseCheckRunning()
        val requested = requestedAt != 0L && SystemClock.elapsedRealtime() - requestedAt < 3000
        diagnosticButton.isEnabled = app.packagedRuntime?.manifest?.bootstrap != null && !app.busy && !testing && !requested
        lifecycleDiagnosticButton.isEnabled = diagnosticButton.isEnabled
        recoveryDiagnosticButton.isEnabled = diagnosticButton.isEnabled
        val authPackaged = app.packagedRuntime?.manifest?.let { manifest ->
            "authserver" in manifest.capabilities && manifest.files.any { it.path == "data/metadata/auth-seed-lock.json" }
        } == true
        val worldUpdaterPackaged = app.packagedRuntime?.manifest?.let { manifest ->
            "worldserver" in manifest.capabilities && manifest.files.any { it.role == "world-server" } &&
                manifest.files.any { it.path == "data/metadata/world-updater-lock.json" }
        } == true
        serverStatus.text = when {
            app.busy && app.store == null -> "Checking included server components…"
            worldUpdaterPackaged -> "Auth and world components included for disposable updater testing. World startup awaits qualification."
            authPackaged -> "Auth server included for testing. World server build pending."
            else -> "Auth and world servers are not included in this build."
        }
        authTestHelp.visibility = if (authPackaged) View.VISIBLE else View.GONE
        authDiagnosticButton.visibility = if (authPackaged) View.VISIBLE else View.GONE
        authDiagnosticButton.isEnabled = authPackaged && database && !app.busy && !testing && !requested
        fullSeedDiagnosticButton.isEnabled = app.packagedRuntime?.manifest?.bootstrap != null && database && !app.busy && !testing && !requested
        worldUpdaterDiagnosticButton.visibility = if (worldUpdaterPackaged) View.VISIBLE else View.GONE
        worldUpdaterTestHelp.visibility = if (worldUpdaterPackaged) View.VISIBLE else View.GONE
        worldUpdaterDiagnosticButton.isEnabled = worldUpdaterPackaged && database && !app.busy && !testing && !requested
        realmStorageButton.isEnabled = worldUpdaterPackaged && (database || File(filesDir, "persistent-realm/active.properties").exists()) && !app.busy && !testing && !requested
        realmBackupButton.isEnabled = worldUpdaterPackaged && File(filesDir, "persistent-realm/active.properties").exists() && !app.busy && !testing && !requested
        localAuthButton.isEnabled = realmBackupButton.isEnabled && database && File(filesDir, "realm-backups/latest.json").isFile
        localServiceButton.isEnabled = localAuthButton.isEnabled
        worldServiceButton.isEnabled = localAuthButton.isEnabled && File(filesDir, "game-data/active").isFile
        exportBackupButton.isEnabled = File(filesDir, "realm-backups/latest.json").isFile && !app.busy && !testing && !requested
        gameDataButton.isEnabled = !app.busy && !testing && !requested
        clientInspectButton.isEnabled = gameDataButton.isEnabled
        clientCancelButton.isEnabled = app.inspectingClient
        val client = com.russianranger.fta.client.ClientInspection.export(this)
        clientStatus.text = when (client.optString("state")) {
            "complete" -> "Inspection complete: ${client.optInt("files")} files counted. Export the report; client compatibility is not yet qualified."
            "running" -> if (app.inspectingClient) "Inspection running…" else "Inspection interrupted. Export the report before retrying."
            "failed", "cancelled", "invalid" -> "Inspection did not finish. Export the report before retrying."
            else -> "No client folder inspected yet."
        }
        gameDataVerifyButton.isEnabled = gameDataButton.isEnabled && File(filesDir, "game-data/active").isFile
        gameDataStatus.text = if (File(filesDir, "game-data/active").isFile) "Game-data candidate stored. Verify hashes before native world qualification." else "Matching extracted game data is needed for world startup."
        diagnosticStopButton.isEnabled = testing || requested
        exportReportButton.isEnabled = !app.busy
        runtimeBundleButton.isEnabled = app.runtimeBundles != null && !app.busy && !testing
        val bundle = app.runtimeBundles?.active()
        runtimeBundleStatus.text = if (bundle == null) "Optional. Use a matching bundle only when supplied with an update."
            else "Provider ${bundle.manifest.providerVersion} bundle verified. The app's packaged provider remains authoritative."
        val diagnostic = File(filesDir, "runtime/diagnostic-status.txt")
        val diagnosticLines = if (diagnostic.isFile && diagnostic.length() <= 8192)
            runCatching { diagnostic.readLines() }.getOrDefault(emptyList()) else emptyList()
        val diagnosticScope = File(filesDir, "runtime/diagnostic-scope.txt")
        val scope = if (diagnosticScope.isFile && diagnosticScope.length() <= 64) runCatching { diagnosticScope.readText() }.getOrNull() else null
        gameDataBinding.update(activityVisible, gameDataWorkerRunning(), scope, diagnosticLines.firstOrNull())
        diagnosticStatus.text = if (diagnosticLines.size >= 3) diagnosticLines.drop(2).joinToString("\n")
            else "No native database test has completed on this device."
        if (!testing && !requested && ((diagnosticLines.firstOrNull()?.startsWith("LIFECYCLE_") == true || diagnosticLines.firstOrNull()?.startsWith("BACKUP_") == true || diagnosticLines.firstOrNull() in setOf("GAME_DATA_COPY", "GAME_DATA_EXTRACT", "GAME_DATA_VERIFY", "GAME_DATA_PREFLIGHT") || diagnosticLines.firstOrNull() in setOf("LOCAL_SERVICE_RUNNING", "LOCAL_AUTH_SETUP", "LOCAL_AUTH_START", "LOCAL_AUTH_HOLD", "LOCAL_AUTH_STOP") || diagnosticLines.firstOrNull() == "RUNTIME_RECOVERY_RUNNING" || diagnosticLines.firstOrNull() in setOf("REALM_PREPARING", "REALM_REOPEN", "REALM_VERIFY", "REALM_METADATA")) || diagnosticLines.firstOrNull() in setOf("CHECKING", "STAGING", "BOOTSTRAPPING", "STARTING", "STOPPING", "AUTH_INPUT", "AUTH_IMPORT", "AUTH_VERSION", "AUTH_UPDATER", "SEED_INPUT", "SEED_STAGING", "SEED_IMPORT", "SEED_VALIDATION"))) {
            diagnosticStatus.text = "The previous test did not finish. No readiness result was recorded."
        }
        if (!testing && !requested && diagnosticLines.firstOrNull()?.startsWith("GAME_DATA_") == true &&
            diagnosticLines.firstOrNull() !in setOf("GAME_DATA_PREFLIGHT_PASSED", "GAME_DATA_REJECTED")) {
            diagnosticStatus.text = "The game-data operation did not finish. Export the diagnostic report before retrying. Your retained database is unchanged."
        }
        if (testing) diagnosticStatus.text = "Runtime active.\n${diagnosticStatus.text}"
        if (requested && !testing) diagnosticStatus.text = "Starting the isolated database test…"
        sourceButton.isEnabled = !app.busy && !testing && app.store != null
        databaseButton.isEnabled = !app.busy && !testing && app.store != null
        sourceButton.text = if (source) "Replace source ZIP" else "Select source ZIP"
        databaseButton.text = if (database) "Replace database ZIP" else "Select database ZIP"
        progressLabel.text = app.progressText
        progress.isIndeterminate = app.busy && (app.store == null || app.progressText.startsWith("Runtime bundle"))
        progress.progress = app.progressPercent
        progress.visibility = if (app.busy) View.VISIBLE else View.GONE
        logText.text = app.logs()
        if (app.busy || testing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Suppress("DEPRECATION")
    private fun databaseCheckRunning(): Boolean = getSystemService(ActivityManager::class.java)
        .getRunningServices(20).any { it.service.className in setOf(RuntimeDiagnosticService::class.java.name, RuntimeRecoveryService::class.java.name) }

    @Suppress("DEPRECATION")
    private fun runtimeRecoveryRunning(): Boolean = getSystemService(ActivityManager::class.java)
        .getRunningServices(20).any { it.service.className == RuntimeRecoveryService::class.java.name }

    @Suppress("DEPRECATION")
    private fun gameDataWorkerRunning(): Boolean = getSystemService(ActivityManager::class.java)
        .getRunningServices(20).any { it.service.className == RuntimeDiagnosticService::class.java.name && it.started && it.pid > 0 }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingGameData", pendingGameData)
        outState.putString("pendingGameDataUri", pendingGameDataUri?.toString())
        outState.putBoolean("pendingRecoveryCheck", pendingRecoveryCheck)
        outState.putBoolean("pendingRealmStorage", pendingRealmStorage)
        outState.putBoolean("pendingLocalService", pendingLocalService)
        outState.putBoolean("pendingWorldService", pendingWorldService)
        outState.putBoolean("pendingLocalAuth", pendingLocalAuth)
        outState.putBoolean("pendingRealmBackup", pendingRealmBackup)
        outState.putBoolean("pendingLifecycleCheck", pendingLifecycleCheck)
        outState.putBoolean("pendingAuthCheck", pendingAuthCheck)
        outState.putBoolean("pendingFullSeedCheck", pendingFullSeedCheck)
        outState.putBoolean("pendingWorldUpdaterCheck", pendingWorldUpdaterCheck)
        super.onSaveInstanceState(outState)
    }

    private fun requestDatabaseCheck(includeAuth: Boolean = false, includeFullSeed: Boolean = false, includeWorldUpdater: Boolean = false, includeLifecycle: Boolean = false, includeRecovery: Boolean = false, includeRealm: Boolean = false, includeBackup: Boolean = false, includeLocalAuth: Boolean = false, includeLocalService: Boolean = false, includeGameData: Boolean = false, includeWorldService: Boolean = false) {
        pendingWorldService = includeWorldService
        pendingGameData = includeGameData
        pendingLocalService = includeLocalService
        pendingLocalAuth = includeLocalAuth
        pendingRealmBackup = includeBackup
        pendingRealmStorage = includeRealm
        pendingRecoveryCheck = includeRecovery
        pendingLifecycleCheck = includeLifecycle
        pendingAuthCheck = includeAuth
        pendingFullSeedCheck = includeFullSeed
        pendingWorldUpdaterCheck = includeWorldUpdater
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
        } else startDatabaseCheck(pendingAuthCheck, pendingFullSeedCheck, pendingWorldUpdaterCheck, pendingLifecycleCheck, pendingRecoveryCheck, pendingRealmStorage, pendingRealmBackup, pendingLocalAuth, pendingLocalService, pendingGameData, pendingWorldService)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Notification permission is optional for an explicitly requested foreground service.
        if (requestCode == 201) startDatabaseCheck(pendingAuthCheck, pendingFullSeedCheck, pendingWorldUpdaterCheck, pendingLifecycleCheck, pendingRecoveryCheck, pendingRealmStorage, pendingRealmBackup, pendingLocalAuth, pendingLocalService, pendingGameData, pendingWorldService)
    }

    private fun startDatabaseCheck(includeAuth: Boolean = false, includeFullSeed: Boolean = false, includeWorldUpdater: Boolean = false, includeLifecycle: Boolean = false, includeRecovery: Boolean = false, includeRealm: Boolean = false, includeBackup: Boolean = false, includeLocalAuth: Boolean = false, includeLocalService: Boolean = false, includeGameData: Boolean = false, includeWorldService: Boolean = false) {
        try {
            if (includeGameData) startForegroundService(Intent(this, RuntimeDiagnosticService::class.java)
                .setAction(if (pendingGameDataUri == null) RuntimeDiagnosticService.ACTION_VERIFY_GAME_DATA else RuntimeDiagnosticService.ACTION_IMPORT_GAME_DATA)
                .setData(pendingGameDataUri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            else if (includeWorldService) startForegroundService(Intent(this, RuntimeDiagnosticService::class.java).setAction(RuntimeDiagnosticService.ACTION_WORLD_SERVICE))
            else if (includeLocalService) startForegroundService(Intent(this, RuntimeDiagnosticService::class.java).setAction(RuntimeDiagnosticService.ACTION_LOCAL_SERVICE))
            else if (includeLocalAuth) startForegroundService(Intent(this, RuntimeDiagnosticService::class.java).setAction(RuntimeDiagnosticService.ACTION_LOCAL_AUTH))
            else if (includeBackup) startForegroundService(Intent(this, RuntimeDiagnosticService::class.java).setAction(RuntimeDiagnosticService.ACTION_REALM_BACKUP))
            else if (includeRealm) startForegroundService(Intent(this, RuntimeDiagnosticService::class.java).setAction(RuntimeDiagnosticService.ACTION_REALM_STORAGE))
            else if (includeRecovery) startForegroundService(Intent(this, RuntimeRecoveryService::class.java).setAction(RuntimeRecoveryService.ACTION_START))
            else startForegroundService(Intent(this, RuntimeDiagnosticService::class.java).setAction(if (includeLifecycle) RuntimeDiagnosticService.ACTION_CHECK_LIFECYCLE else if (includeWorldUpdater) RuntimeDiagnosticService.ACTION_CHECK_WORLD_UPDATER else if (includeFullSeed) RuntimeDiagnosticService.ACTION_CHECK_FULL_SEED else if (includeAuth) RuntimeDiagnosticService.ACTION_CHECK_AUTH else RuntimeDiagnosticService.ACTION_CHECK))
            requestedAt = SystemClock.elapsedRealtime()
            update()
        } catch (_: Exception) {
            Toast.makeText(this, "The database test could not start. Reopen this screen and try again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun selectArchive(requestCode: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, requestCode)
    }

    @Deprecated("Platform activity result API keeps this foundation free of AndroidX dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == 107) {
            if (!databaseCheckRunning()) data?.data?.let { app.inspectClient(it) }
            return
        }
        if (requestCode == 106) { data?.data?.let { pendingGameDataUri = it; requestDatabaseCheck(includeGameData = true) }; return }
        if (requestCode == 104) { data?.data?.let { app.exportDiagnosticReport(it) }; return }
        if (requestCode == 105) { data?.data?.let { app.exportRealmBackup(it) }; return }
        if (requestCode == 103) { data?.data?.let { app.importRuntimeBundle(it) }; return }
        val spec = when (requestCode) { 101 -> Inputs.source; 102 -> Inputs.database; else -> return }
        data?.data?.let { app.importArchive(spec, it) }
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun action(value: String, click: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply {
            setColor(Color.rgb(35, 32, 27))
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), Color.rgb(82, 66, 44))
        }
    }
    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(content)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
