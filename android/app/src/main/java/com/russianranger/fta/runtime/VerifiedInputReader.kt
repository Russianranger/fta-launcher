package com.russianranger.fta.runtime

import com.russianranger.fta.ArchiveSpec
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Properties

/** Read-only resolution for service processes; never instantiate the UI's mutating ArchiveStore. */
object VerifiedInputReader {
    private val generation = Regex("g-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun resolve(importsRoot: File, spec: ArchiveSpec): File {
        val folder = File(importsRoot, spec.id)
        val pointer = File(folder, "active")
        if (!regular(pointer) || pointer.length() !in 1..64) throw IOException("Verified input pointer is unavailable")
        val name = pointer.readText(Charsets.US_ASCII).trim()
        if (!generation.matches(name)) throw IOException("Verified input pointer is invalid")
        val directory = File(folder, name)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Verified input generation is unavailable")
        }
        val metadata = File(directory, "receipt.properties")
        if (!regular(metadata) || metadata.length() !in 1..2048) throw IOException("Verified input receipt is unavailable")
        val fields = try {
            Properties().apply { metadata.inputStream().use { load(it) } }
        } catch (_: Exception) { throw IOException("Verified input receipt is invalid") }
        if (fields.getProperty("kind") != spec.id || fields.getProperty("sha256") != spec.sha256 ||
            fields.getProperty("bytes") != spec.bytes.toString()) throw IOException("Verified input receipt differs from the approved input")
        val archive = File(directory, "archive.zip")
        verify(archive, spec.sha256, spec.bytes)
        return archive
    }

    internal fun verify(file: File, expectedSha256: String, expectedSize: Long) {
        if (!regular(file) || file.length() != expectedSize) throw IOException("Verified input archive is missing or has the wrong size")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw IOException("Input verification interrupted")
                val read = input.read(buffer)
                if (read < 0) break
                bytes += read
                if (bytes > expectedSize) throw IOException("Verified input archive changed during verification")
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (bytes != expectedSize || actual != expectedSha256) throw IOException("Verified input archive digest differs from the approved input")
    }

    private fun regular(file: File) = Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
}
