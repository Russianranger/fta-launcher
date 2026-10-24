package com.russianranger.fta.runtime

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object GameDataImport {
    const val PASSED = "GAME_DATA_PREFLIGHT_PASSED"
    fun store(context: Context) = GameDataStore(File(context.filesDir, "game-data"),
        context.assets.open(GameDataPolicy.ASSET).bufferedReader().use { it.readText() })
    fun failureSummary(failure: Exception, cancelled: Boolean): String {
        val detail = if (cancelled) "Import stopped at your request."
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
    fun run(context: Context, uri: Uri?, cancelled: AtomicBoolean, status: (String, String) -> Unit): String {
        val end = SystemClock.elapsedRealtime() + 3_600_000
        val checkActive = { check(!cancelled.get() && SystemClock.elapsedRealtime() < end) }
        val store = store(context)
        var lastUpdate = 0L
        val progress: (String, Long, Long) -> Unit = { stage, done, total ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate >= 500 || done == total) {
                lastUpdate = now
                val sizes = "${done / (1024 * 1024)} MiB" + if (total > 0) " / ${total / (1024 * 1024)} MiB" else ""
                status("GAME_DATA_${stage.uppercase(java.util.Locale.ROOT)}", when (stage) {
                    "copy" -> "Copying the game-data ZIP: $sizes. Keep enough free space for the ZIP and unpacked assets."
                    "extract" -> "Importing game data: $sizes. Your retained database is untouched."
                    "verify" -> "Rechecking every imported game-data hash: $sizes."
                    else -> "Checking the original server's game-data formats and required files."
                })
            }
        }
        val receipt = if (uri == null) store.verify(checkActive) { done, total -> progress("verify", done, total) }
            else context.contentResolver.openInputStream(uri)?.use { store.import(it, checkActive, progress) }
                ?: error("Game-data document could not be opened")
        return "Game-data preflight passed: ${receipt.files} files, ${receipt.bytes / (1024 * 1024)} MiB. Original required DBCs, startup map files and supplied file headers match. World startup and complete terrain/navigation loading still need native qualification. Export the diagnostic report."
    }
}
