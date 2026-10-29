package com.russianranger.fta.client

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import java.util.UUID

/** Caller retains its verified-client lease until all readers end. Shared links
 * require the guarded tracer and its exact /immutable binding; links alone do
 * not protect anything. This builds a fresh view, never edits a retained view. */
object ClientWorkingView {
    const val COPY_LIMIT = 64L * 1024 * 1024
    const val GROWTH_LIMIT = 128L * 1024 * 1024
    private val freshDirectories = setOf("cache", "logs", "errors", "screenshots")
    val budgetGroups = listOf("wtf", "configuration", "cache", "logs", "errors", "screenshots", "shared")
    fun budgetField(group: String, suffix: String) = "viewInput${group.replaceFirstChar { it.uppercaseChar() }}$suffix"
    private val reserved = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }
    data class Entry(val path: String, val directory: Boolean, val bytes: Long, val copied: Boolean, val excluded: Boolean = false)
    data class Plan(val entries: List<Entry>, val copiedBytes: Long, val sharedBytes: Long, val sha256: String,
        val excludedBytes: Long, val inputBytes: Map<String, Long>, val inputFiles: Map<String, Int>)
    data class Result(val copiedBytes: Long, val sharedBytes: Long, val copiedFiles: Int, val sharedFiles: Int, val planSha256: String,
        val excludedBytes: Long, val excludedFiles: Int)
    private fun mutable(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.substringBefore('/') == "wtf" || lower.substringAfterLast('.', "") in setOf("cfg", "wtf", "ini")
    }
    fun plan(source: File, active: () -> Unit = {}, observed: (Plan) -> Unit = {}): Plan {
        runtimeRequire(source.absoluteFile == source.canonicalFile && Files.isDirectory(source.toPath(), NOFOLLOW_LINKS), "unsafe-runtime-store")
        val inventory = ClientInventory(); val entries = mutableListOf<Entry>(); var copies = 0L; var shared = 0L; var excludedBytes = 0L
        val groupBytes = budgetGroups.associateWith { 0L }.toMutableMap()
        val groupFiles = budgetGroups.associateWith { 0 }.toMutableMap()
        Files.walkFileTree(source.toPath(), object : SimpleFileVisitor<Path>() {
            private fun add(path: Path, attrs: BasicFileAttributes) {
                active(); if (path == source.toPath()) return
                runtimeRequire(attrs.isDirectory || attrs.isRegularFile, "unsafe-runtime-store")
                val relative = source.toPath().relativize(path).toString()
                runtimeRequire(relative.split('/').all { ':' !in it && !it.endsWith('.') && !it.endsWith(' ') &&
                    it.substringBefore('.').lowercase(Locale.ROOT) !in reserved }, "unsafe-runtime-store")
                try { inventory.add(relative, attrs.isDirectory, if (attrs.isDirectory) 0 else attrs.size()) }
                catch (_: IllegalArgumentException) { throw RuntimeInstallFailure("unsafe-runtime-store") }
                val top = relative.substringBefore('/').lowercase(Locale.ROOT)
                runtimeRequire(top !in freshDirectories || '/' in relative || attrs.isDirectory, "unsafe-runtime-store")
                // Keep every source entry in the audit, but give session outputs
                // fresh independent directories instead of copying old content.
                val excluded = top in freshDirectories && '/' in relative
                val copy = !attrs.isDirectory && !excluded && mutable(relative)
                val size = if (attrs.isDirectory) 0 else attrs.size()
                runtimeRequire(size <= ClientPlan.MAX_BYTES - copies - shared - excludedBytes, "view-copy-limit")
                if (excluded) excludedBytes += size else if (copy) copies += size else shared += size
                if (!attrs.isDirectory) {
                    val group = if (top in freshDirectories || top == "wtf") top else if (copy) "configuration" else "shared"
                    groupBytes[group] = groupBytes.getValue(group) + size
                    groupFiles[group] = groupFiles.getValue(group) + 1
                }
                entries += Entry(relative, attrs.isDirectory, size, copy, excluded)
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { add(dir, attrs); return FileVisitResult.CONTINUE }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { add(file, attrs); return FileVisitResult.CONTINUE }
        })
        runtimeRequire(entries.isNotEmpty(), "unsafe-runtime-store")
        val ordered = entries.sortedBy { it.path }
        val hash = runtimeHash()
        for (entry in ordered) {
            // Preserve the existing digest for retained fixtures without outputs.
            val excluded = if (entry.excluded) "\tfresh-output-excluded" else ""
            hash.update("${entry.path}\t${entry.directory}\t${entry.bytes}\t${entry.copied}$excluded\n".toByteArray())
        }
        val plan = Plan(ordered, copies, shared, runtimeHex(hash.digest()), excludedBytes, groupBytes, groupFiles)
        observed(plan) // Report complete bounded totals before rejecting the copy budget.
        runtimeRequire(copies <= COPY_LIMIT, "view-copy-limit")
        return plan
    }
    fun build(source: File, destination: File, active: () -> Unit = {}, beforeIo: (Int) -> Unit = {},
        available: () -> Long = { destination.parentFile!!.usableSpace }, reserve: Long = ClientRuntimeStore.RESERVE,
        syncDirectory: (File) -> Unit = { java.nio.channels.FileChannel.open(it.toPath(), java.nio.file.StandardOpenOption.READ).use { c -> c.force(true) } }): Result {
        val planned = plan(source, active)
        val target = destination.absoluteFile
        runtimeRequire(target == destination.canonicalFile && !Files.exists(target.toPath(), NOFOLLOW_LINKS) &&
            !target.toPath().startsWith(source.toPath()) && !source.toPath().startsWith(target.toPath()), "unsafe-runtime-store")
        runtimeRequire(reserve >= 0 && available() >= planned.copiedBytes + reserve, "insufficient-space")
        val building = File(target.parentFile, ".view-${UUID.randomUUID()}")
        runtimeRequire(building.mkdir(), "storage-unavailable")
        try {
            for (entry in planned.entries.filter { it.directory && !it.excluded }.sortedBy { it.path.count { c -> c == '/' } }) {
                active(); runtimeRequire(File(building, entry.path).mkdir(), "storage-unavailable")
            }
            val buffer = ByteArray(256 * 1024)
            for (entry in planned.entries.filter { !it.directory && !it.excluded }) {
                active(); val original = File(source, entry.path); val output = File(building, entry.path)
                runtimeRequire(Files.isRegularFile(original.toPath(), NOFOLLOW_LINKS) && original.length() == entry.bytes, "runtime-changed")
                if (!entry.copied) {
                    Files.createSymbolicLink(output.toPath(), java.nio.file.Paths.get("/immutable/${entry.path}"))
                    continue
                }
                val hash = runtimeHash(); var total = 0L
                original.inputStream().use { input -> FileOutputStream(output).use { out ->
                    while (true) {
                        active(); beforeIo(buffer.size); val n = input.read(buffer); if (n < 0) break
                        runtimeRequire(n > 0 && total + n <= entry.bytes, "runtime-changed")
                        runtimeRequire(available() >= n + reserve, "insufficient-space")
                        beforeIo(n); out.write(buffer, 0, n); out.fd.sync(); hash.update(buffer, 0, n); total += n
                    }
                } }
                runtimeRequire(total == entry.bytes, "runtime-changed")
                val actual = runtimeHash(); var readback = 0L
                output.inputStream().use { input -> while (true) {
                    active(); beforeIo(buffer.size); val n = input.read(buffer); if (n < 0) break
                    actual.update(buffer, 0, n); readback += n
                } }
                runtimeRequire(readback == total && actual.digest().contentEquals(hash.digest()), "runtime-changed")
            }
            // Always supply independent writable standard directories without changing source casing.
            val roots = planned.entries.filter { '/' !in it.path }.associateBy { it.path.lowercase(Locale.ROOT) }
            for (name in listOf("WTF", "Cache", "Logs", "Errors", "Screenshots")) {
                val existing = roots[name.lowercase(Locale.ROOT)]
                runtimeRequire(existing == null || existing.directory, "unsafe-runtime-store")
                if (existing == null) runtimeRequire(File(building, name).mkdir(), "storage-unavailable")
            }
            runtimeRequire(plan(source, active) == planned, "runtime-changed")
            Files.walk(building.toPath()).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { syncDirectory(it.toFile()) } }
            active(); runtimeRequire(!Files.exists(target.toPath(), NOFOLLOW_LINKS), "unsafe-runtime-store")
            Files.move(building.toPath(), target.toPath(), ATOMIC_MOVE)
            syncDirectory(target.parentFile!!)
            return Result(planned.copiedBytes, planned.sharedBytes, planned.entries.count { it.copied },
                planned.entries.count { !it.directory && !it.copied && !it.excluded }, planned.sha256,
                planned.excludedBytes, planned.entries.count { !it.directory && it.excluded })
        } finally {
            if (Files.exists(building.toPath(), NOFOLLOW_LINKS)) Files.walkFileTree(building.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir); return FileVisitResult.CONTINUE
                }
            })
        }
    }
    /** Polling growth threshold, not a filesystem quota. Never follows DOS/source links. */
    fun checkGrowth(root: File, limit: Long): Long {
        runtimeRequire(limit >= 0 && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS), "unsafe-runtime-store")
        var total = 0L; var count = 0
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            private fun add(attrs: BasicFileAttributes) {
                runtimeRequire(++count <= 100000, "view-growth-limit")
                if (attrs.isRegularFile) { runtimeRequire(attrs.size() <= limit - total, "view-growth-limit"); total += attrs.size() }
            }
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult { add(attrs); return FileVisitResult.CONTINUE }
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult { add(attrs); return FileVisitResult.CONTINUE }
            override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult {
                if (error is java.nio.file.NoSuchFileException) return FileVisitResult.CONTINUE
                throw error
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null && error !is java.nio.file.NoSuchFileException) throw error
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }
}
