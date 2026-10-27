package com.russianranger.fta.client

import java.io.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermission
import java.security.DigestInputStream
import java.util.zip.GZIPInputStream

/** Isolated runtime only. Never opens client-store, realm data or external assets. */
class ClientRuntimeStore(private val root: File, private val plan: RuntimeArchivePlan,
    private val available: () -> Long = { root.usableSpace },
    private val beforeIo: (Int) -> Unit = {},
    private val releaseCache: (File, Long, Long) -> Unit = { _, _, _ -> },
    private val syncDirectory: (File) -> Unit = { FileChannel.open(it.toPath(), READ).use { c -> c.force(true) } }
) {
    data class Progress(val stage: String, val bytes: Long, val totalBytes: Long, val entries: Int)
    private val ready = File(root, "ready")
    private val staging = File(root, "staging")
    private val archive = File(root, "archive.gz")
    private val buffer = ByteArray(BUFFER)
    private fun regular(file: File) = Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun directory(file: File) {
        runtimeRequire(!Files.isSymbolicLink(file.toPath()), "unsafe-runtime-store")
        if (!exists(file)) { runtimeRequire(file.mkdir(), "storage-unavailable"); syncDirectory(file.parentFile!!) }
        runtimeRequire(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS), "unsafe-runtime-store")
    }
    private fun <T> exclusive(block: () -> T): T {
        directory(root)
        return FileChannel.open(File(root, "lease").toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = try { channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
            runtimeRequire(lock != null, "busy"); lock!!.use { block() }
        }
    }
    private fun location(base: File, path: String): File {
        var current = base
        for (part in path.split('/').dropLast(1)) {
            current = File(current, part)
            runtimeRequire(Files.isDirectory(current.toPath(), NOFOLLOW_LINKS), "runtime-changed")
        }
        return File(base, path)
    }
    private fun cleanStaging(active: () -> Unit) {
        if (!exists(staging)) return
        runtimeRequire(Files.isDirectory(staging.toPath(), NOFOLLOW_LINKS), "unsafe-runtime-store")
        // No FOLLOW_LINKS: guest-absolute symlinks are removed, never traversed.
        var count = 0
        Files.walkFileTree(staging.toPath(), object : SimpleFileVisitor<Path>() {
            fun check() { active(); count++; runtimeRequire(count <= 50002, "unsafe-runtime-store") }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { check(); return FileVisitResult.CONTINUE }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                check(); Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                active(); Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
        syncDirectory(root)
    }
    private fun read(file: File): InputStream {
        runtimeRequire(regular(file), "runtime-changed")
        val input = Files.newInputStream(file.toPath(), READ, NOFOLLOW_LINKS)
        return object : FilterInputStream(input) {
            var consumed = 0L; var released = 0L
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                val request = minOf(length, BUFFER); beforeIo(request)
                val count = `in`.read(bytes, offset, request)
                if (count > 0) consumed += count
                if (consumed - released >= WINDOW || count < 0) {
                    if (consumed > released) releaseCache(file, released, consumed - released)
                    released = consumed
                }
                return count
            }
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255 }
            override fun close() {
                try { if (consumed > released) releaseCache(file, released, consumed - released) } finally { super.close() }
            }
        }
    }
    private fun hash(file: File, bytes: Long, active: () -> Unit): String {
        runtimeRequire(regular(file) && file.length() == bytes, "runtime-changed")
        val digest = runtimeHash(); var count = 0L
        read(file).use { input ->
            while (count < bytes) {
                active(); val n = input.read(buffer, 0, minOf(BUFFER.toLong(), bytes - count).toInt())
                runtimeRequire(n > 0, "runtime-changed"); count += n; digest.update(buffer, 0, n)
            }
            active(); runtimeRequire(input.read() == -1, "runtime-changed")
        }
        runtimeRequire(count == bytes, "runtime-changed"); return runtimeHex(digest.digest())
    }
    /** <=1 MiB dirty windows, sync then independent readback before cache advice. */
    private fun copy(input: InputStream, output: File, length: Long, expected: String,
        active: () -> Unit, progress: (Long) -> Unit) {
        runtimeRequire(!exists(output), "unsafe-runtime-store")
        val written = runtimeHash(); val checked = runtimeHash()
        RandomAccessFile(output, "rw").use { destination ->
            var offset = 0L
            while (offset < length) {
                active(); val window = minOf(WINDOW.toLong(), length - offset).toInt()
                runtimeRequire(available() >= window + RESERVE, "insufficient-space")
                var remaining = window
                while (remaining > 0) {
                    active(); val n = input.read(buffer, 0, minOf(BUFFER, remaining))
                    runtimeRequire(n > 0, "archive-changed"); written.update(buffer, 0, n)
                    beforeIo(n); destination.write(buffer, 0, n); remaining -= n
                }
                destination.fd.sync(); destination.seek(offset); remaining = window
                while (remaining > 0) {
                    active(); val n = minOf(BUFFER, remaining); beforeIo(n)
                    destination.readFully(buffer, 0, n); checked.update(buffer, 0, n); remaining -= n
                }
                releaseCache(output, offset, window.toLong()); offset += window; progress(offset)
            }
            destination.fd.sync()
        }
        runtimeRequire(runtimeHex(written.digest()) == expected && runtimeHex(checked.digest()) == expected, "runtime-changed")
    }
    private fun permissions(file: File, mode: Int) {
        val values = PosixFilePermission.values()
        Files.setPosixFilePermissions(file.toPath(), values.filterIndexed { index, _ -> mode and (1 shl (8 - index)) != 0 }.toSet())
    }
    private fun mode(file: File): Int = Files.getPosixFilePermissions(file.toPath(), NOFOLLOW_LINKS).fold(0) { value, p ->
        value or (1 shl (8 - p.ordinal))
    }

    fun install(source: () -> InputStream, active: () -> Unit = {}, progress: (Progress) -> Unit = {}): File = exclusive {
        active()
        if (exists(ready)) return@exclusive verifyLocked(active, progress)
        cleanStaging(active)
        // Source, extraction and one GiB reserve; a failed attempt cannot fill the device.
        runtimeRequire(available() >= plan.fileBytes + plan.archiveBytes + RESERVE, "insufficient-space")
        if (exists(archive)) runtimeRequire(hash(archive, plan.archiveBytes, active) == plan.archiveSha256, "archive-changed")
        else {
            val pending = File(root, "archive.partial")
            if (exists(pending)) { runtimeRequire(regular(pending), "unsafe-runtime-store"); Files.delete(pending.toPath()) }
            source().use { input ->
                val governed = object : FilterInputStream(input) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int { beforeIo(minOf(len, BUFFER)); return `in`.read(b, off, minOf(len, BUFFER)) }
                }
                copy(governed, pending, plan.archiveBytes, plan.archiveSha256, active) {
                    progress(Progress("download", it, plan.archiveBytes, 0))
                }
                active(); runtimeRequire(input.read() == -1, "archive-changed")
            }
            Files.move(pending.toPath(), archive.toPath(), ATOMIC_MOVE); syncDirectory(root)
        }
        directory(staging)
        // Make only real directories. All links are delayed until every payload is closed.
        plan.entries.filter { it.kind == 'd' }.sortedBy { it.path.count { c -> c == '/' } }.forEach {
            active(); directory(location(staging, it.path))
        }
        val compressed = runtimeHash(); val expanded = runtimeHash(); var offset = 0L; var copied = 0L; var files = 0
        read(archive).use { raw ->
            DigestInputStream(raw, compressed).use { checked ->
                GZIPInputStream(checked, 64 * 1024).use { zip ->
                    val stream = object : FilterInputStream(zip) {
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            active(); val n = `in`.read(b, off, minOf(len, BUFFER))
                            if (n > 0) { offset += n; runtimeRequire(offset <= plan.expandedBytes, "archive-changed"); expanded.update(b, off, n) }
                            return n
                        }
                    }
                    for (entry in plan.entries.filter { it.kind == 'f' }) {
                        while (offset < entry.offset) {
                            val n = stream.read(buffer, 0, minOf(BUFFER.toLong(), entry.offset - offset).toInt())
                            runtimeRequire(n > 0, "archive-changed")
                        }
                        runtimeRequire(offset == entry.offset, "archive-changed")
                        val output = location(staging, entry.path)
                        copy(stream, output, entry.bytes, entry.sha256, active) {
                            progress(Progress("extract", copied + it, plan.fileBytes, files))
                        }
                        permissions(output, entry.mode); copied += entry.bytes; files++
                    }
                    while (stream.read(buffer) >= 0) { active() }
                    runtimeRequire(offset == plan.expandedBytes && runtimeHex(expanded.digest()) == plan.expandedSha256, "archive-changed")
                    // Consume any buffered gzip trailer through the raw digest, too.
                    while (checked.read(buffer) >= 0) { active() }
                    runtimeRequire(runtimeHex(compressed.digest()) == plan.archiveSha256, "archive-changed")
                }
            }
        }
        for (entry in plan.entries.filter { it.kind in "sh" }) {
            active(); val output = location(staging, entry.path)
            runtimeRequire(!exists(output), "unsafe-runtime-store")
            if (entry.kind == 's') Files.createSymbolicLink(output.toPath(), File(entry.link).toPath())
            else Files.createLink(output.toPath(), location(staging, entry.link).toPath())
        }
        plan.entries.filter { it.kind == 'd' }.sortedByDescending { it.path.count { c -> c == '/' } }.forEach {
            val dir = location(staging, it.path); permissions(dir, it.mode); syncDirectory(dir)
        }
        syncDirectory(staging); active()
        // Includes inventory, links, modes and full rehash before atomic publication.
        verifyTree(staging, active, progress)
        Files.move(staging.toPath(), ready.toPath(), ATOMIC_MOVE); syncDirectory(root)
        progress(Progress("complete", plan.fileBytes, plan.fileBytes, plan.entries.size)); ready
    }
    fun <T> withVerified(active: () -> Unit = {}, progress: (Progress) -> Unit = {},
        reverifyAfter: Boolean = false, block: (File) -> T): T = exclusive {
        val data = verifyLocked(active, progress); val result = block(data)
        if (reverifyAfter) verifyTree(data, active, progress)
        result
    }
    private fun verifyLocked(active: () -> Unit, progress: (Progress) -> Unit): File {
        runtimeRequire(Files.isDirectory(ready.toPath(), NOFOLLOW_LINKS), "runtime-unavailable")
        verifyTree(ready, active, progress); return ready
    }
    private fun verifyTree(data: File, active: () -> Unit, progress: (Progress) -> Unit) {
        var count = 0; var bytes = 0L
        Files.walk(data.toPath()).use { paths -> paths.forEach { path ->
            active(); if (path != data.toPath()) {
                count++; runtimeRequire(count <= plan.entries.size, "runtime-changed")
                val entry = plan.byPath[data.toPath().relativize(path).toString()] ?: throw RuntimeInstallFailure("runtime-changed")
                val file = location(data, entry.path)
                when (entry.kind) {
                    'd' -> runtimeRequire(Files.isDirectory(path, NOFOLLOW_LINKS) && mode(file) == entry.mode, "runtime-changed")
                    's' -> runtimeRequire(Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString() == entry.link, "runtime-changed")
                    'h' -> runtimeRequire(regular(file) && Files.isSameFile(path, location(data, entry.link).toPath()), "runtime-changed")
                    'f' -> {
                        runtimeRequire(mode(file) == entry.mode && hash(file, entry.bytes, active) == entry.sha256, "runtime-changed")
                        bytes += entry.bytes; progress(Progress("verify-runtime", bytes, plan.fileBytes, count))
                    }
                }
            }
        } }
        runtimeRequire(count == plan.entries.size && bytes == plan.fileBytes, "runtime-changed")
    }
    companion object {
        const val BUFFER = 256 * 1024
        const val WINDOW = 1024 * 1024
        const val RESERVE = 1024L * 1024 * 1024
    }
}
