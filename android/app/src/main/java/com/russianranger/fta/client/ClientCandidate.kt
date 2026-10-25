package com.russianranger.fta.client

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

object ClientCandidate {
    /** Metadata compatibility only; does not qualify WarcraftXL execution or asset-host identity. */
    fun validate(data: File) {
        val root = data.listFiles()?.associateBy { it.name.lowercase(java.util.Locale.ROOT) }
            ?: throw ClientImportFailure("client-files-missing")
        for (name in listOf("wow.exe", "d3d9.dll", "warcraftxl.dll")) {
            val file = root[name] ?: throw ClientImportFailure("client-files-missing")
            demand(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS), "unsafe-destination")
            val info = try { RandomAccessFile(file, "r").use { input ->
                PeMetadata.inspect(input.length()) { offset, count -> ByteArray(count).also { input.seek(offset); input.readFully(it) } }
            } } catch (_: Exception) { throw ClientImportFailure("client-build-mismatch") }
            demand(if (name == "wow.exe") ClientInventory.matchesWow(info) else info.architecture == "x86" && info.dll, "client-build-mismatch")
        }
    }
}
