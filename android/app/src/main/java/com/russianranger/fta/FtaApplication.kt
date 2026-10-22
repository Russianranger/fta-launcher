package com.russianranger.fta

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.russianranger.fta.runtime.DiagnosticReport
import com.russianranger.fta.runtime.PackagedRuntime
import com.russianranger.fta.runtime.RuntimeBundleStore
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class FtaApplication : Application() {
    var store: ArchiveStore? = null
        private set
    var runtimeBundles: RuntimeBundleStore? = null
        private set
    var packagedRuntime: PackagedRuntime? = null
        private set
    var providerMessage = "Checking packaged native provider…"
        private set
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val events = ArrayDeque<String>()
    var observer: (() -> Unit)? = null
    var busy = true
        private set
    var progressText = "Checking private archive integrity…"
        private set
    var progressPercent = 0
        private set
    private lateinit var logFile: File

    override fun onCreate() {
        super.onCreate()
        // The nonexported runtime service must not initialize or clean UI import generations.
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
            else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")
        if (processName.endsWith(":runtime")) return
        logFile = File(filesDir, "setup.log")
        if (logFile.isFile && logFile.length() <= 32 * 1024) {
            logFile.readLines().takeLast(30).forEach { events.addLast(it) }
        }
        record("Setup opened. Checking saved input integrity.")
        executor.execute {
            try {
                val loadedStore = ArchiveStore(File(filesDir, "imports"))
                val loadedBundles = RuntimeBundleStore(File(filesDir, "runtime-bundles"))
                val provider = runCatching { PackagedRuntime.inspect(this) }
                main.post {
                    runtimeBundles = loadedBundles
                    packagedRuntime = provider.getOrNull()
                    providerMessage = if (provider.isSuccess)
                        "MariaDB ${provider.getOrThrow().manifest.providerVersion} is packaged. Run the isolated database test to qualify execution."
                        else "This APK has no qualified native provider. Import validation is available; database testing awaits provider packaging."
                    store = loadedStore
                    busy = false
                    progressText = "Ready to prepare your local server inputs."
                    record("Saved inputs checked. Interrupted copies can be selected again.")
                    observer?.invoke()
                }
            } catch (_: Exception) {
                main.post {
                    busy = false
                    progressText = "Private storage is unavailable. Free storage and reopen the app."
                    record("Private import storage could not be initialized.")
                    observer?.invoke()
                }
            }
        }
    }

    fun logs(): String = events.joinToString("\n")

    fun importArchive(spec: ArchiveSpec, uri: Uri) {
        val targetStore = store ?: return
        if (busy) return
        busy = true
        progressPercent = 0
        progressText = "Copying ${spec.label.lowercase()} into private storage…"
        observer?.invoke()
        executor.execute {
            var lastUpdate = 0L
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    targetStore.import(spec, input) { copied, total ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 150 || copied == total) {
                            lastUpdate = now
                            main.post {
                                progressPercent = ((copied * 100) / total).toInt()
                                progressText = "${spec.label}: ${copied / (1024 * 1024)} / ${total / (1024 * 1024)} MiB"
                                observer?.invoke()
                            }
                        }
                    }
                } ?: error("Selected document could not be opened")
                main.post {
                    progressText = "${spec.label} verified and saved. Runtime qualification is still pending."
                    record("${spec.label} SHA-256 verified. No archive extraction or SQL execution performed.")
                    finishImport()
                }
            } catch (_: Exception) {
                main.post {
                    progressText = "Import did not finish. Select the matching ZIP and retry. Any previous verified copy is retained."
                    record("${spec.label} import failed. Check the selected file and available private storage.")
                    finishImport()
                }
            }
        }
    }

    fun importRuntimeBundle(uri: Uri) {
        val targetStore = runtimeBundles ?: return
        if (busy) return
        busy = true
        progressPercent = 0
        progressText = "Validating runtime bundle into private storage…"
        observer?.invoke()
        executor.execute {
            var lastUpdate = 0L
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    targetStore.import(input) { copied ->
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 150) {
                            lastUpdate = now
                            main.post {
                                progressText = "Runtime bundle: ${copied / (1024 * 1024)} MiB validated"
                                observer?.invoke()
                            }
                        }
                    }
                } ?: error("Selected runtime bundle could not be opened")
                main.post {
                    progressText = "Runtime bundle validated and stored. APK native provider qualification remains separate."
                    record("Runtime bundle paths, ARM64 ELF metadata and manifest digests verified; imported ELF files remain inert.")
                    finishImport()
                }
            } catch (_: Exception) {
                main.post {
                    progressText = "Runtime bundle validation failed. Any prior verified generation is retained."
                    record("Runtime bundle rejected; no imported executable was launched.")
                    finishImport()
                }
            }
        }
    }

    fun exportDiagnosticReport(uri: Uri) {
        if (busy) return
        busy = true
        progressText = "Preparing a private-data-safe diagnostic report…"
        observer?.invoke()
        executor.execute {
            try {
                val report = DiagnosticReport.create(this)
                contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(report) }
                    ?: error("Selected report destination could not be opened")
                main.post {
                    progressText = "Diagnostic report exported."
                    record("Diagnostic report exported with provider hashes and redacted native error summaries.")
                    finishImport()
                }
            } catch (_: Exception) {
                main.post {
                    progressText = "The diagnostic report could not be exported. Choose a writable destination and retry."
                    record("Diagnostic report export failed.")
                    finishImport()
                }
            }
        }
    }

    private fun finishImport() {
        busy = false
        observer?.invoke()
    }

    private fun record(event: String) {
        events.addLast("${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}  $event")
        while (events.size > 30) events.removeFirst()
        // Logs contain only fixed application messages, never source URIs, SQL, or credentials.
        runCatching { logFile.writeText(events.joinToString("\n")) }
    }
}
