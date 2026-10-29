package com.russianranger.fta.client

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

/** Only the two absent code-43 bind targets, after retained ownership/file validation.
 * Caller holds the runtime lease. Never chmods, traverses or recursively deletes them. */
object ClientRuntimeBindings {
    val targets = setOf("immutable", "view")
    data class Identity(val mode: Int, val uid: Int, val links: Long)
    fun deleteEmptyDirectory(file: File) {
        Files.newDirectoryStream(file.parentFile!!.toPath()).use { stream ->
            runtimeRequire(stream is SecureDirectoryStream<*>, "runtime-bind-recovery-refused")
            @Suppress("UNCHECKED_CAST")
            (stream as SecureDirectoryStream<Path>).deleteDirectory(file.toPath().fileName)
        }
    }
    fun recover(root: File, plan: RuntimeArchivePlan, owner: Int, stat: (File) -> Identity,
        rmdir: (File) -> Unit, active: () -> Unit = {}, removed: (Int) -> Unit = {}): Int {
        runtimeRequire(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS), "unsafe-runtime-store")
        val base = root.canonicalFile
        runtimeRequire(targets.none { target -> plan.entries.any { it.path == target || it.path.startsWith("$target/") } }, "runtime-bind-recovery-refused")
        val candidates = targets.map { File(base, it) }.filter { Files.exists(it.toPath(), NOFOLLOW_LINKS) }
        for (file in candidates) {
            active()
            val identity = stat(file)
            runtimeRequire(file.canonicalFile == file && identity.mode == 0x4000 && identity.uid == owner && identity.links == 2L,
                "runtime-bind-recovery-refused")
        }
        var count = 0
        for (file in candidates) {
            active()
            val identity = stat(file)
            runtimeRequire(identity.mode == 0x4000 && identity.uid == owner && identity.links == 2L, "runtime-bind-recovery-refused")
            // Directory-only deletion refuses files, links and nonempty directories.
            rmdir(file); count++; removed(count)
        }
        return count
    }
}
