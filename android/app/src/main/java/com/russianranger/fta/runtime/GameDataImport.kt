package com.russianranger.fta.runtime

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object GameDataImport {
    const val PASSED = "GAME_DATA_PREFLIGHT_PASSED"
    fun store(context: Context, cache: GameDataCache? = null) = GameDataStore(File(context.filesDir, "game-data"),
        context.assets.open(GameDataPolicy.ASSET).bufferedReader().use { it.readText() }, cache = cache)
    fun failureSummary(failure: Exception, cancelled: Boolean): String {
        val detail = if (cancelled) "Import stopped at your request."
        else if (failure is java.util.concurrent.TimeoutException) "The game-data operation reached its one-hour time limit."
        else when ((failure as? GameDataImportFailure)?.reason) {
            "unsupported_compression" -> "This ZIP uses unsupported compression. ZIP Store and Deflate are supported."
            "encrypted_zip" -> "Encrypted ZIPs are not supported."
            "unsafe_archive_path", "duplicate_archive_path", "unsupported_file_mode" -> "The ZIP contains an unsafe or conflicting entry."
            "unsupported_asset_path" -> "The ZIP contains a file outside the supported game-data layout."
            "archive_limit" -> "The ZIP exceeds a supported file-size or entry-count limit."
            "insufficient_space" -> "There is not enough free internal storage for the ZIP and extracted files."
            "asset_integrity" -> "An extracted file failed its size or CRC check."
            "invalid_zip" -> "The ZIP index is incomplete, malformed or unsupported."
            else -> "Game-data import or verification failed."
        }
        return "$detail Your previous game data and retained database are preserved. Use Export diagnostic report and send the .json file; it records the stage and any supported archive details."
    }
    fun run(context: Context, uri: Uri?, cancelled: AtomicBoolean, attempt: String, activityBound: () -> Boolean,
        status: (String, String) -> Unit): String {
        val end = SystemClock.elapsedRealtime() + 3_600_000
        val checkActive = {
            check(!cancelled.get())
            if (SystemClock.elapsedRealtime() >= end) throw java.util.concurrent.TimeoutException()
        }
        val cache = GameDataCache()
        val journal = GameDataDiagnostics.store(context)
        val now = System.currentTimeMillis()
        var record = GameDataOperation.Record(attempt, ProcessObservation.self(android.os.Process.myPid()),
            if (uri == null) "verify" else "import", now, now)
        fun checkpoint() {
            val vm = Runtime.getRuntime()
            record = record.copy(updated = System.currentTimeMillis(), freeStorage = context.filesDir.usableSpace.coerceAtLeast(0),
                heapUsed = (vm.totalMemory() - vm.freeMemory()).coerceIn(0, vm.maxMemory()), heapMax = vm.maxMemory(),
                memory = GameDataDiagnostics.memory(context, activityBound()), cache = cache.stats())
            journal.write(record)
        }
        checkpoint()
        GameDataDiagnostics.markProcess(context, attempt)
        val boundary: (String, String?) -> Unit = { stage, archive ->
            record = record.copy(stage = stage, completed = 0, total = 0, archive = archive ?: record.archive)
            checkpoint()
        }
        var lastUpdate = 0L; var lastStage = ""
        val progress: (String, Long, Long) -> Unit = { stage, done, total ->
            val elapsed = SystemClock.elapsedRealtime()
            if (stage != lastStage || elapsed - lastUpdate >= 500 || done == total) {
                lastUpdate = elapsed; lastStage = stage
                record = record.copy(stage = stage, completed = done, total = total,
                    copied = if (stage in setOf("copy", "copy_sync")) done else record.copied)
                checkpoint()
                val sizes = "${done / (1024 * 1024)} MiB" + if (total > 0) " / ${total / (1024 * 1024)} MiB" else ""
                status("GAME_DATA_${stage.uppercase(java.util.Locale.ROOT)}", when (stage) {
                    "copy" -> "Copying the game-data ZIP: $sizes. Keep enough free space for the ZIP and unpacked assets."
                    "copy_sync" -> "Finishing the game-data ZIP copy: $sizes."
                    "archive_index" -> "Checking the ZIP index: $done / $total entries."
                    "extract" -> "Importing game data: $sizes. Your retained database is untouched."
                    "verify" -> "Rechecking every imported game-data hash: $sizes."
                    "publish" -> "Saving the verified game-data candidate."
                    else -> "Checking the original server's game-data formats and required files."
                })
            }
        }
        try {
            val store = store(context, cache)
            if (uri == null) { boundary("verify", null); progress("verify", 0, 0) }
            val receipt = if (uri == null) store.verify(checkActive) { done, total -> progress("verify", done, total) }
            else context.contentResolver.openInputStream(uri)?.use { input ->
                // Preserve openInputStream's provider/slice semantics. Duplicate only a real
                // file descriptor for advice, and never alter the stream's shared offset.
                val descriptor = (input as? java.io.FileInputStream)?.let { stream ->
                    runCatching { android.os.ParcelFileDescriptor.dup(stream.fd) }.getOrNull()
                }
                descriptor.use { fd ->
                    val start = fd?.let { runCatching { android.system.Os.lseek(it.fileDescriptor, 0, android.system.OsConstants.SEEK_CUR) }.getOrNull() }
                    cache.sourceAvailable = start != null && start >= 0
                    val source = if (fd != null && start != null && start >= 0) cache.input(input) { at, size ->
                        if (at > Long.MAX_VALUE - start) -1 else GameDataCacheNative.releaseDescriptor(fd.fd, start + at, size)
                    } else input
                    store.import(source, checkActive, progress, boundary)
                }
            }
                ?: error("Game-data document could not be opened")
            record = record.copy(state = "FINISHED", archive = receipt.archiveSha256)
            checkpoint()
            return "Game-data preflight passed: ${receipt.files} files, ${receipt.bytes / (1024 * 1024)} MiB. Original required DBCs, startup map files and supplied file headers match. World startup and complete terrain/navigation loading still need native qualification. Export the diagnostic report."
        } catch (failure: Throwable) {
            // Best effort for VM errors; the previous checkpoint and OS exit record survive
            // even when the failing process cannot allocate enough memory to write another.
            runCatching {
                record = record.copy(state = if (cancelled.get()) "STOPPED" else "FAILED",
                    failure = GameDataOperation.failureKind(failure, cancelled.get()), frames = GameDataOperation.safeFrames(failure))
                checkpoint()
            }
            throw failure
        }
    }
}
