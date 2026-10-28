package com.russianranger.fta.client

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** At most sixteen tiny disposable attempts. No imported path is accepted or traversed. */
object ClientIsolationFiles {
    private val entries = setOf("", "empty", "canary.bin", "read-only.txt")
    fun create(files: File, attempt: String): File {
        runtimeRequire(attempt.matches(Regex("[0-9a-f-]{36}")), "probe-failed")
        val parent = File(files, "client-isolation-attempts")
        runtimeRequire(!Files.isSymbolicLink(parent.toPath()) && (parent.mkdir() || parent.isDirectory), "unsafe-runtime-store")
        runtimeRequire((parent.list()?.size ?: 16) < 16, "probe-attempt-limit")
        runtimeRequire(parent.usableSpace >= 16 * 1024 * 1024, "insufficient-space")
        val run = File(parent, attempt).canonicalFile
        runtimeRequire(run.mkdir(), "unsafe-runtime-store")
        for (name in listOf("immutable", "immutable/empty", "writable", "tmp"))
            runtimeRequire(File(run, name).mkdir(), "storage-unavailable")
        fun write(name: String, data: ByteArray) = FileOutputStream(File(run, name)).use { it.write(data); it.fd.sync() }
        write("immutable/canary.bin", ByteArray(65536) { (it % 251).toByte() })
        write("immutable/read-only.txt", "read-only".toByteArray())
        Files.createSymbolicLink(File(run, "writable/alias").toPath(), File(run, "immutable").toPath())
        return run
    }
    data class Stamp(val metadata: List<Long>, val sha256: String?)
    fun snapshot(run: File, metadata: (File) -> List<Long>): Map<String, Stamp> {
        val root = File(run, "immutable")
        val actual = linkedMapOf<String, Stamp>()
        fun visit(file: File, relative: String) {
            runtimeRequire(relative in entries && !Files.isSymbolicLink(file.toPath()), "runtime-changed")
            val directory = relative in setOf("", "empty")
            runtimeRequire(if (directory) Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)
                else Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS), "runtime-changed")
            runtimeRequire(directory || file.length() <= 65536, "runtime-changed")
            actual[relative] = Stamp(metadata(file), if (directory) null else runtimeHex(runtimeHash().digest(file.readBytes())))
            if (directory) for (child in file.listFiles() ?: throw RuntimeInstallFailure("runtime-changed"))
                visit(child, if (relative.isEmpty()) child.name else "$relative/${child.name}")
        }
        visit(root, "")
        runtimeRequire(actual.keys == entries, "runtime-changed")
        return actual
    }
}
