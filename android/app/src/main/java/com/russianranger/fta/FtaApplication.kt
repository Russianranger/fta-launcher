package com.russianranger.fta

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

class FtaApplication : Application() {
    var store: ArchiveStore? = null
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
        logFile = File(filesDir, "setup.log")
        if (logFile.isFile && logFile.length() <= 32 * 1024) {
            logFile.readLines().takeLast(30).forEach { events.addLast(it) }
        }
        record("Setup opened. Checking saved input integrity.")
        executor.execute {
            try {
                val loadedStore = ArchiveStore(File(filesDir, "imports"))
                main.post {
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
