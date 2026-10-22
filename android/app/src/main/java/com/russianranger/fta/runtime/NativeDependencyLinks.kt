package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** Install-path-scoped links never repoint a library directory that a live child may use. */
object NativeDependencyLinks {
    fun prepare(root: File, manifestIdentity: String, nativeDirectory: File, libraries: Map<String, File>): File {
        val nativeRoot = nativeDirectory.canonicalFile
        val identity = MessageDigest.getInstance("SHA-256")
            .digest((manifestIdentity + "\n" + nativeRoot.absolutePath).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
        val directory = File(root, identity)
        check(directory.mkdirs() || directory.isDirectory) { "Private native dependency directory is unavailable" }
        libraries.forEach { (name, input) ->
            require(name.matches(Regex("lib[A-Za-z0-9_+.-]+\\.so(?:\\.[0-9]+)*")))
            val source = input.canonicalFile
            require(source.parentFile == nativeRoot && source.isFile) { "Dependency target must be an APK native file" }
            val link = File(directory, name).toPath()
            if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createSymbolicLink(link, source.toPath()) }
                catch (_: java.nio.file.FileAlreadyExistsException) { /* Another app process prepared the same link. */ }
            }
            check(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == source.toPath()) {
                "Private native dependency link is invalid"
            }
        }
        return directory
    }
}
