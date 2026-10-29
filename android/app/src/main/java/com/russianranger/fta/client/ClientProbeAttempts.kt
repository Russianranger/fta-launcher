package com.russianranger.fta.client

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Caller holds the exclusive runtime lease. Legacy/unproven attempts are never removed.
 * A fourth slot recovers the three legacy attempts; only positively retired new work is reusable. */
object ClientProbeAttempts {
    private const val RECEIPT = "owned-cleanup-v1"
    private val contents = "FTA_OWNED_CLEANUP_V1\n".toByteArray()
    fun create(files: File, id: String): File {
        val run = ClientProbePaths.attempt(files, id)
        val parent = run.parentFile!!
        runtimeRequire(!Files.isSymbolicLink(parent.toPath()) && (parent.mkdir() || parent.isDirectory), "unsafe-runtime-store")
        val legacy = File(files, "client-runtime-attempts")
        runtimeRequire(!Files.isSymbolicLink(legacy.toPath()), "unsafe-runtime-store")
        for (entry in parent.listFiles() ?: throw RuntimeInstallFailure("unsafe-runtime-store")) {
            if (!entry.name.matches(Regex("[0-9a-f]{32}")) || !Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)) continue
            val receipt = File(entry, RECEIPT)
            if (Files.isRegularFile(receipt.toPath(), NOFOLLOW_LINKS) && receipt.length() == contents.size.toLong() &&
                receipt.readBytes().contentEquals(contents)) remove(entry.toPath())
        }
        val oldCount = if (legacy.exists()) legacy.list()?.size ?: throw RuntimeInstallFailure("unsafe-runtime-store") else 0
        val count = parent.list()?.size ?: throw RuntimeInstallFailure("unsafe-runtime-store")
        runtimeRequire(count.toLong() + oldCount < 4, "probe-attempt-limit")
        runtimeRequire(parent.usableSpace >= 2 * ClientRuntimeStore.RESERVE, "insufficient-space")
        runtimeRequire(run.mkdir(), "unsafe-runtime-store")
        return run
    }
    /** Called after owned readers are reaped, or when no native reader was acquired. */
    fun retire(run: File) {
        runtimeRequire(Files.isDirectory(run.toPath(), NOFOLLOW_LINKS), "unsafe-runtime-store")
        val file = File(run, RECEIPT)
        runtimeRequire(file.createNewFile(), "unsafe-runtime-store")
        FileOutputStream(file).use { it.write(contents); it.fd.sync() }
    }
    private fun remove(root: Path) {
        // walkFileTree does not follow symlinks: Wine's DOS links must never reach imported data.
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
