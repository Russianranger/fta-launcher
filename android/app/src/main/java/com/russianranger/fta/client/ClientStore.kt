package com.russianranger.fta.client

import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.UUID

/** Inert managed generations. The source API cannot write; only the active pointer publishes. */
class ClientStore(private val root: File,
    private val availableBytes: () -> Long = { root.usableSpace },
    private val syncDirectory: (File) -> Unit = { FileChannel.open(it.toPath(), READ).use { c -> c.force(true) } },
    private val releaseCache: (File, Long, Long) -> Unit = { _, _, _ -> },
    private val chunkBytes: Int = 16 * 1024 * 1024) {
    data class Result(val files: Int, val bytes: Long, val catalogSha256: String, val resumedBytes: Long)
    data class Progress(val stage: String, val files: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long, val resumedBytes: Long)
    init { demand(chunkBytes in 1..(16 * 1024 * 1024), "invalid-chunk-size"); directory(root) }
    private fun directory(file: File) {
        demand(!Files.isSymbolicLink(file.toPath()), "unsafe-destination")
        if (!file.exists()) { demand(file.mkdir(), "storage-unavailable"); syncDirectory(file.parentFile!!) }
        demand(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS), "unsafe-destination")
    }
    private fun regular(file: File) = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
    private fun atomic(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, ".write-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING); syncDirectory(file.parentFile!!)
        } finally { temp.delete() }
    }
    private fun bounded(file: File, max: Long): ByteArray {
        demand(regular(file) && file.length() in 1..max, "invalid-checkpoint")
        return file.readBytes()
    }
    private fun pointer(name: String): String? {
        val file = File(root, name); if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        return bounded(file, 64).toString(Charsets.UTF_8).also { demand(it.matches(Regex("g-[a-f0-9-]{36}")), "invalid-checkpoint") }
    }
    private fun <T> exclusive(action: () -> T): T = FileChannel.open(File(root, "lease").toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
        val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
        demand(lock != null, "busy"); lock!!.use { action() }
    }
    private fun target(data: File, path: String, isDirectory: Boolean, create: Boolean = true): File {
        var current = data; val parts = path.split('/')
        for ((index, part) in parts.withIndex()) {
            demand(part.isNotEmpty() && part !in setOf(".", "..") && '\\' !in part, "invalid-path")
            current = File(current, part)
            if (index < parts.lastIndex || isDirectory) {
                if (create) directory(current) else demand(Files.isDirectory(current.toPath(), NOFOLLOW_LINKS), "destination-changed")
            }
            else demand(!current.exists() || regular(current), "unsafe-destination")
            demand(!Files.isSymbolicLink(current.toPath()), "unsafe-destination")
        }
        return current
    }

    fun import(source: ClientSource, active: () -> Unit = {}, progress: (Progress) -> Unit = {},
        validate: (File) -> Unit): Result = exclusive {
        active()
        val plan = ClientPlan(source.identity, source.inventory())
        val files = plan.files
        progress(Progress("planning", 0, files.size, 0, plan.bytes, 0))
        var generation = pointer("pending")
        if (generation != null && generation == pointer("active") &&
            bounded(File(File(root, generation), "plan.bin"), 16L * 1024 * 1024).contentEquals(plan.encoded))
            return@exclusive verifyLocked(active, progress, validate)
        if (generation == null || generation == pointer("active")) {
            generation = "g-${UUID.randomUUID()}"
            val folder = File(root, generation); directory(folder)
            atomic(File(folder, "plan.bin"), plan.encoded)
            atomic(File(folder, "chunk-size"), chunkBytes.toString().toByteArray())
            atomic(File(root, "pending"), generation.toByteArray())
        }
        val folder = File(root, generation); directory(folder)
        demand(bounded(File(folder, "plan.bin"), 16L * 1024 * 1024).contentEquals(plan.encoded), "source-changed")
        demand(bounded(File(folder, "chunk-size"), 16).toString(Charsets.UTF_8) == chunkBytes.toString(), "invalid-checkpoint")
        val data = File(folder, "data"); directory(data)
        val chunks = File(folder, "chunks"); directory(chunks)
        plan.entries.filter { it.directory }.sortedBy { it.path.count { c -> c == '/' } }.forEach { target(data, it.path, true) }
        val destinations = files.map { target(data, it.path, false) }
        val allocated = destinations.indices.sumOf { minOf(destinations[it].length(), files[it].bytes) }
        demand(availableBytes() >= plan.bytes - allocated + RESERVE, "insufficient-space")
        val manifest = ByteArrayOutputStream()
        val catalog = DataOutputStream(manifest)
        catalog.writeInt(1); catalog.writeInt(files.size); catalog.writeLong(plan.bytes)
        var processed = 0L; var resumed = 0L
        val buffer = ByteArray(256 * 1024)
        for ((index, entry) in files.withIndex()) {
            active()
            val output = destinations[index]
            val journal = File(chunks, "$index.bin")
            demand(!Files.exists(journal.toPath(), NOFOLLOW_LINKS) || regular(journal), "invalid-checkpoint")
            val count = if (entry.bytes == 0L) 0L else (entry.bytes - 1) / chunkBytes + 1
            demand(journal.length() <= count * 64, "invalid-checkpoint")
            RandomAccessFile(journal, "rw").use { checkpoint ->
                syncDirectory(chunks)
                // A process death during append can leave only the final record incomplete.
                checkpoint.setLength(checkpoint.length() / 64 * 64)
                val committed = checkpoint.length() / 64
                val committedBytes = minOf(committed * chunkBytes, entry.bytes)
                demand(output.length() >= committedBytes, "destination-changed")
                RandomAccessFile(output, "rw").use { destination ->
                    syncDirectory(output.parentFile!!)
                    destination.setLength(committedBytes)
                    val full = MessageDigest.getInstance("SHA-256")
                    source.open(entry).use { input ->
                        var offset = 0L
                        while (offset < entry.bytes) {
                            active()
                            val length = minOf(chunkBytes.toLong(), entry.bytes - offset).toInt()
                            val existing = offset < committedBytes
                            var expected: ByteArray? = null
                            if (existing) {
                                expected = ByteArray(32); checkpoint.readFully(expected)
                                val checksum = ByteArray(32); checkpoint.readFully(checksum)
                                demand(digest(expected).contentEquals(checksum), "invalid-checkpoint")
                            } else demand(availableBytes() >= length + RESERVE, "insufficient-space")
                            val sourceHash = MessageDigest.getInstance("SHA-256")
                            destination.seek(offset)
                            var remaining = length
                            while (remaining > 0) {
                                active()
                                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                                demand(read > 0, "source-changed")
                                sourceHash.update(buffer, 0, read); full.update(buffer, 0, read)
                                if (!existing) destination.write(buffer, 0, read)
                                remaining -= read
                            }
                            val hash = sourceHash.digest()
                            if (existing) demand(hash.contentEquals(expected), "source-changed") else destination.fd.sync()
                            destination.seek(offset)
                            val readback = MessageDigest.getInstance("SHA-256"); remaining = length
                            while (remaining > 0) {
                                active(); val read = destination.read(buffer, 0, minOf(buffer.size, remaining))
                                demand(read > 0, "destination-changed"); readback.update(buffer, 0, read); remaining -= read
                            }
                            demand(hash.contentEquals(readback.digest()), "destination-changed")
                            if (!existing) {
                                checkpoint.write(hash); checkpoint.write(digest(hash)); checkpoint.fd.sync()
                            } else resumed += length
                            releaseCache(output, offset, length.toLong())
                            offset += length; processed += length
                            progress(Progress(if (existing) "resume-verify" else "copy", index, files.size, processed, plan.bytes, resumed))
                        }
                        demand(input.read() == -1, "source-changed")
                    }
                    destination.fd.sync()
                    catalog.writeUTF(entry.path); catalog.writeLong(entry.bytes); catalog.write(full.digest())
                }
            }
            progress(Progress("copy", index + 1, files.size, processed, plan.bytes, resumed))
        }
        active(); progress(Progress("validate", files.size, files.size, processed, plan.bytes, resumed))
        demand(ClientPlan(source.identity, source.inventory()).encoded.contentEquals(plan.encoded), "source-changed")
        val expectedPaths = plan.entries.associateBy { it.path }
        var actualEntries = 0
        Files.walk(data.toPath()).use { paths -> paths.forEach { path ->
            active(); demand(!Files.isSymbolicLink(path), "unsafe-destination")
            if (path != data.toPath()) {
                val expected = expectedPaths[data.toPath().relativize(path).toString()]
                demand(expected != null && expected.directory == Files.isDirectory(path, NOFOLLOW_LINKS), "destination-changed")
                if (expected?.directory == false) demand(Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) == expected.bytes, "destination-changed")
                actualEntries++
            }
        } }
        demand(actualEntries == expectedPaths.size, "destination-changed")
        validate(data); active()
        val encoded = manifest.toByteArray()
        demand(encoded.size <= 16 * 1024 * 1024, "inventory-limit")
        atomic(File(folder, "catalog.bin"), encoded)
        atomic(File(folder, "catalog.sha256"), hex(digest(encoded)).toByteArray())
        Files.walk(data.toPath()).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { syncDirectory(it.toFile()) } }
        syncDirectory(chunks); syncDirectory(folder); syncDirectory(root)
        active(); atomic(File(root, "active"), generation.toByteArray())
        Result(files.size, plan.bytes, hex(digest(encoded)), resumed)
    }

    fun verify(active: () -> Unit = {}, progress: (Progress) -> Unit = {}, validate: (File) -> Unit): Result =
        exclusive { verifyLocked(active, progress, validate) }

    private fun verifyLocked(active: () -> Unit, progress: (Progress) -> Unit, validate: (File) -> Unit): Result {
        val generation = pointer("active") ?: throw ClientImportFailure("no-client")
        val folder = File(root, generation); demand(Files.isDirectory(folder.toPath(), NOFOLLOW_LINKS), "unsafe-destination")
        val encoded = bounded(File(folder, "catalog.bin"), 16L * 1024 * 1024)
        val sha = hex(digest(encoded)); demand(bounded(File(folder, "catalog.sha256"), 64).toString(Charsets.UTF_8) == sha, "invalid-checkpoint")
        val data = File(folder, "data"); demand(Files.isDirectory(data.toPath(), NOFOLLOW_LINKS), "unsafe-destination")
        val plan = ClientPlan.decode(bounded(File(folder, "plan.bin"), 16L * 1024 * 1024))
        val plannedFiles = plan.files.associateBy { it.path }.toMutableMap()
        val plannedPaths = plan.entries.associateBy { it.path }
        plan.entries.filter { it.directory }.forEach { target(data, it.path, true, false) }
        var processed = 0L
        DataInputStream(ByteArrayInputStream(encoded)).use { input ->
            demand(input.readInt() == 1, "invalid-checkpoint")
            val count = input.readInt(); val total = input.readLong()
            demand(count in 1..100000 && total in 0..ClientPlan.MAX_BYTES, "invalid-checkpoint")
            demand(count == plannedFiles.size && total == plan.bytes, "invalid-checkpoint")
            val names = HashSet<String>(); val buffer = ByteArray(256 * 1024)
            repeat(count) { index ->
                active(); val path = input.readUTF(); val size = input.readLong(); val expected = ByteArray(32); input.readFully(expected)
                demand(path.length in 1..2048 && names.add(path.lowercase(java.util.Locale.ROOT)) && size in 0..(total - processed), "invalid-checkpoint")
                demand(plannedFiles.remove(path)?.bytes == size, "invalid-checkpoint")
                val file = target(data, path, false, false)
                demand(regular(file) && file.length() == size, "destination-changed")
                val hash = MessageDigest.getInstance("SHA-256"); var readTotal = 0L; var released = 0L
                file.inputStream().use { stream ->
                    while (true) {
                        active(); val read = stream.read(buffer); if (read < 0) break
                        demand(read > 0 && read <= size - readTotal, "destination-changed")
                        hash.update(buffer, 0, read); readTotal += read
                        if (readTotal - released >= chunkBytes) {
                            releaseCache(file, released, readTotal - released); released = readTotal
                            progress(Progress("verify", index, count, processed + readTotal, total, 0))
                        }
                    }
                }
                demand(readTotal == size && hash.digest().contentEquals(expected), "destination-changed")
                releaseCache(file, released, readTotal - released); processed += size
                progress(Progress("verify", index + 1, count, processed, total, 0))
            }
            demand(input.read() == -1 && processed == total, "invalid-checkpoint")
            var actual = 0
            Files.walk(data.toPath()).use { paths -> paths.forEach { path ->
                active(); demand(!Files.isSymbolicLink(path), "unsafe-destination")
                if (path != data.toPath()) {
                    val planned = plannedPaths[data.toPath().relativize(path).toString()]
                    demand(planned != null && planned.directory == Files.isDirectory(path, NOFOLLOW_LINKS), "destination-changed")
                }
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    demand(Files.isRegularFile(path, NOFOLLOW_LINKS), "unsafe-destination")
                    demand(data.toPath().relativize(path).toString().lowercase(java.util.Locale.ROOT) in names, "destination-changed")
                    actual++; demand(actual <= count, "destination-changed")
                }
            } }
            demand(actual == count, "destination-changed")
            validate(data); active(); return Result(count, total, sha, 0)
        }
    }
    companion object { const val RESERVE = 1024L * 1024 * 1024 }
}
