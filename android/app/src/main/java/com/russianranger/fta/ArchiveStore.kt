package com.russianranger.fta

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

/** A pinned input is preparation evidence only, never database or server readiness. */
data class ArchiveSpec(val id: String, val label: String, val sha256: String, val bytes: Long) {
    init {
        require(id.matches(Regex("[a-z]+")))
        require(sha256.matches(Regex("[0-9a-f]{64}")))
        require(bytes > 0)
    }
}

data class ArchiveReceipt(val spec: ArchiveSpec, val generation: String, val verifiedAt: Long)

object Inputs {
    val source = ArchiveSpec(
        "source", "Server source",
        "907a0a0bfde4f86b0471f798d93d2a74fdc50ece398cbb31c0ba22ddbc8e81bb", 201_758_731
    )
    val database = ArchiveSpec(
        "database", "Database seed",
        "56a6d3187d6c5fe308e2a5772a19bb087cb55dc999e585db5409bc5469a107f7", 103_160_479
    )
    val all = listOf(source, database)
}

/**
 * Stream into a private generation, verify pinned bytes, then atomically publish a pointer.
 * A failed replacement never changes the pointer to an existing verified generation.
 * Orphan staging/generations left by process death are cleaned on next initialization;
 * retry restarts copying from the beginning. This class never extracts or executes ZIPs.
 */
class ArchiveStore(private val root: File, private val specs: List<ArchiveSpec> = Inputs.all) {
    private val writer = ReentrantLock()
    private val generationPattern = Regex("g-[0-9a-f-]{36}")

    init {
        check(root.mkdirs() || root.isDirectory) { "Private import storage unavailable" }
        specs.forEach { spec ->
            val folder = folder(spec)
            check(folder.mkdirs() || folder.isDirectory) { "Private import storage unavailable" }
            cleanUnreferenced(spec)
            // Executed on the application worker, never on the UI thread.
            // Receipt metadata alone cannot establish integrity after a restart.
            if (activeGeneration(spec) != null) {
                val valid = receipt(spec) != null && runCatching {
                    val generation = activeGeneration(spec)!!
                    val digest = MessageDigest.getInstance("SHA-256")
                    File(folder, "$generation/archive.zip").inputStream().use { input ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                        }
                    }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == spec.sha256
                }.getOrDefault(false)
                if (!valid) {
                    File(folder, "active").delete()
                    cleanUnreferenced(spec)
                }
            }
        }
    }

    private fun folder(spec: ArchiveSpec): File {
        require(spec in specs) { "Unknown archive" }
        return File(root, spec.id)
    }

    private fun activeGeneration(spec: ArchiveSpec): String? {
        val pointer = File(folder(spec), "active")
        if (!pointer.isFile || pointer.length() > 128) return null
        val name = pointer.readText().trim()
        return name.takeIf { generationPattern.matches(it) }
    }

    fun receipt(spec: ArchiveSpec): ArchiveReceipt? = try {
        val generation = activeGeneration(spec)
        if (generation == null) null else {
            val directory = File(folder(spec), generation)
            val metadata = File(directory, "receipt.properties")
            val archive = File(directory, "archive.zip")
            if (!metadata.isFile || metadata.length() > 2048 || !archive.isFile || archive.length() != spec.bytes) null
            else {
                val fields = Properties().apply { metadata.inputStream().use { load(it) } }
                if (fields.getProperty("sha256") != spec.sha256 ||
                    fields.getProperty("bytes") != spec.bytes.toString() ||
                    fields.getProperty("kind") != spec.id) null
                else ArchiveReceipt(spec, generation, fields.getProperty("verifiedAt").toLong())
            }
        }
    } catch (_: Exception) { null }

    fun import(spec: ArchiveSpec, input: InputStream, progress: (Long, Long) -> Unit): ArchiveReceipt {
        check(writer.tryLock()) { "Another archive import is running" }
        val parent = folder(spec)
        val generation = "g-${UUID.randomUUID()}"
        val staging = File(parent, ".partial-${UUID.randomUUID()}")
        val destination = File(parent, generation)
        var published = false
        try {
            check(staging.mkdir()) { "Could not prepare private import storage" }
            val archive = File(staging, "archive.zip")
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            FileOutputStream(archive).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Import interrupted")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copied += read
                    if (copied > spec.bytes) throw IOException("Archive size differs from the approved input")
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    progress(copied, spec.bytes)
                }
                output.fd.sync()
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (copied != spec.bytes || actual != spec.sha256) {
                throw IOException("Archive does not match the approved ${spec.label.lowercase()} ZIP")
            }
            val verifiedAt = System.currentTimeMillis()
            val fields = Properties().apply {
                setProperty("kind", spec.id)
                setProperty("sha256", spec.sha256)
                setProperty("bytes", spec.bytes.toString())
                setProperty("verifiedAt", verifiedAt.toString())
            }
            FileOutputStream(File(staging, "receipt.properties")).use { output ->
                fields.store(output, "FTA verified input, not runtime readiness")
                output.fd.sync()
            }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val pointerTemp = File(parent, ".active-${UUID.randomUUID()}")
            try {
                FileOutputStream(pointerTemp).use { output ->
                    output.write(generation.toByteArray(Charsets.US_ASCII))
                    output.fd.sync()
                }
                Files.move(pointerTemp.toPath(), File(parent, "active").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                published = true
            } finally { pointerTemp.delete() }
            cleanUnreferenced(spec)
            return ArchiveReceipt(spec, generation, verifiedAt)
        } finally {
            staging.deleteRecursively()
            if (!published) destination.deleteRecursively()
            writer.unlock()
        }
    }

    private fun cleanUnreferenced(spec: ArchiveSpec) {
        val active = activeGeneration(spec)
        folder(spec).listFiles()?.forEach { file ->
            if (file.name.startsWith(".partial-") || file.name.startsWith(".active-") ||
                (generationPattern.matches(file.name) && file.name != active)) {
                file.deleteRecursively()
            }
        }
    }
}
