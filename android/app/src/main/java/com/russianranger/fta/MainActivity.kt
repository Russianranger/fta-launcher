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
import java.io.File
import android.content.Intent
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
    private lateinit var authDiagnosticButton: Button
    private lateinit var fullSeedDiagnosticButton: Button
    private var pendingAuthCheck = false
    private var pendingFullSeedCheck = false
    private lateinit var diagnosticStopButton: Button
    private lateinit var exportReportButton: Button
    private val refresh = Handler(Looper.getMainLooper())
    private var requestedAt = 0L
    private val refresher = object : Runnable {
        override fun run() { update(); refresh.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingAuthCheck = savedInstanceState?.getBoolean("pendingAuthCheck") ?: false
        pendingFullSeedCheck = savedInstanceState?.getBoolean("pendingFullSeedCheck") ?: false
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
        checklist.addView(label("DBC, maps, vmaps and mmaps will come from your full client later.", 13, muted))

        val status = panel()
        status.addView(label("Runtime", 21, ink, true))
        status.addView(label("Awaiting qualification", 15, gold, true).apply { setPadding(0, dp(8), 0, dp(4)) })
        status.addView(label("This build prepares verified inputs. Server start becomes available after the database and ARM64 server gates pass.", 14, muted))
        status.addView(action("Start game server") {}.apply { isEnabled = false })
        diagnosticButton = action("Test native database") { requestDatabaseCheck() }
        status.addView(diagnosticButton)
        authDiagnosticButton = action("Test original auth updater") { requestDatabaseCheck(true) }
        status.addView(authDiagnosticButton)
        authTestHelp = label("For the auth test, only Database.zip is needed. It imports the matching auth files into a fresh temporary database. No source ZIP is needed.", 13, muted)
        status.addView(authTestHelp)
        fullSeedDiagnosticButton = action("Test full original database seed") { requestDatabaseCheck(includeFullSeed = true) }
        status.addView(fullSeedDiagnosticButton)
        status.addView(label("Imports all 563 tables and the world trigger into a fresh temporary database, then checks the original update receipts. Requires Database.zip and 3 GiB free storage; allow up to 30 minutes. Keep the test running or use Stop to cancel.", 13, muted))
        status.addView(label("Creates a fresh disposable database, tests local operation, then shuts it down. Your imported seed and saves are untouched.", 13, muted))
        diagnosticStopButton = action("Stop database test") {
            startService(Intent(this, RuntimeDiagnosticService::class.java).setAction(RuntimeDiagnosticService.ACTION_STOP))
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
        status.addView(label("WarcraftXL client import comes in a later milestone. Your original folders stay unchanged.", 13, muted).apply { setPadding(0, dp(8), 0, dp(16)) })
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
        app.observer = { update() }
        refresh.post(refresher)
    }

    override fun onStop() {
        app.observer = null
        refresh.removeCallbacks(refresher)
        super.onStop()
    }

    private fun update() {
        val source = app.store?.receipt(Inputs.source) != null
        val database = app.store?.receipt(Inputs.database) != null
        sourceStatus.text = "${if (source) "✓" else "○"}  Server source ${if (source) "verified" else "ZIP"}"
        databaseStatus.text = "${if (database) "✓" else "○"}  Database seed ${if (database) "verified" else "ZIP"}"
        countText.text = "${listOf(source, database).count { it }} verified inputs · game server build pending"
        providerStatus.text = app.providerMessage
        val testing = databaseCheckRunning()
        val requested = requestedAt != 0L && SystemClock.elapsedRealtime() - requestedAt < 3000
        diagnosticButton.isEnabled = app.packagedRuntime?.manifest?.bootstrap != null && !app.busy && !testing && !requested
        val authPackaged = app.packagedRuntime?.manifest?.let { manifest ->
            "authserver" in manifest.capabilities && manifest.files.any { it.path == "data/metadata/auth-seed-lock.json" }
        } == true
        serverStatus.text = when {
            app.busy && app.store == null -> "Checking included server components…"
            authPackaged -> "Auth server included for testing. World server build pending."
            else -> "Auth and world servers are not included in this build."
        }
        authTestHelp.visibility = if (authPackaged) View.VISIBLE else View.GONE
        authDiagnosticButton.visibility = if (authPackaged) View.VISIBLE else View.GONE
        authDiagnosticButton.isEnabled = authPackaged && database && !app.busy && !testing && !requested
        fullSeedDiagnosticButton.isEnabled = app.packagedRuntime?.manifest?.bootstrap != null && database && !app.busy && !testing && !requested
        diagnosticStopButton.isEnabled = testing || requested
        exportReportButton.isEnabled = !app.busy
        runtimeBundleButton.isEnabled = app.runtimeBundles != null && !app.busy && !testing
        val bundle = app.runtimeBundles?.active()
        runtimeBundleStatus.text = if (bundle == null) "Optional. Use a matching bundle only when supplied with an update."
            else "Provider ${bundle.manifest.providerVersion} bundle verified. The app's packaged provider remains authoritative."
        val diagnostic = File(filesDir, "runtime/diagnostic-status.txt")
        val diagnosticLines = if (diagnostic.isFile && diagnostic.length() <= 8192)
            runCatching { diagnostic.readLines() }.getOrDefault(emptyList()) else emptyList()
        diagnosticStatus.text = if (diagnosticLines.size >= 3) diagnosticLines.drop(2).joinToString("\n")
            else "No native database test has completed on this device."
        if (!testing && !requested && diagnosticLines.firstOrNull() in setOf("CHECKING", "STAGING", "BOOTSTRAPPING", "STARTING", "STOPPING", "AUTH_INPUT", "AUTH_IMPORT", "AUTH_VERSION", "AUTH_UPDATER", "SEED_INPUT", "SEED_STAGING", "SEED_IMPORT", "SEED_VALIDATION")) {
            diagnosticStatus.text = "The previous test did not finish. No readiness result was recorded."
        }
        if (testing) diagnosticStatus.text = "Database test in progress.\n${diagnosticStatus.text}"
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
        .getRunningServices(20).any { it.service.className == RuntimeDiagnosticService::class.java.name }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingAuthCheck", pendingAuthCheck)
        outState.putBoolean("pendingFullSeedCheck", pendingFullSeedCheck)
        super.onSaveInstanceState(outState)
    }

    private fun requestDatabaseCheck(includeAuth: Boolean = false, includeFullSeed: Boolean = false) {
        pendingAuthCheck = includeAuth
        pendingFullSeedCheck = includeFullSeed
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
        } else startDatabaseCheck(pendingAuthCheck, pendingFullSeedCheck)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Notification permission is optional for an explicitly requested foreground service.
        if (requestCode == 201) startDatabaseCheck(pendingAuthCheck, pendingFullSeedCheck)
    }

    private fun startDatabaseCheck(includeAuth: Boolean = false, includeFullSeed: Boolean = false) {
        try {
            startForegroundService(Intent(this, RuntimeDiagnosticService::class.java).setAction(if (includeFullSeed) RuntimeDiagnosticService.ACTION_CHECK_FULL_SEED else if (includeAuth) RuntimeDiagnosticService.ACTION_CHECK_AUTH else RuntimeDiagnosticService.ACTION_CHECK))
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
        if (requestCode == 104) { data?.data?.let { app.exportDiagnosticReport(it) }; return }
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
