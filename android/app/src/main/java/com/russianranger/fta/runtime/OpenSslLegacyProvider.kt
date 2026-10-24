package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest

/** A separate pinned module leaves retained realm/backup provider identities unchanged. */
internal object OpenSslLegacyProvider {
    const val APK_NAME = "libfta_openssl_legacy.so"
    const val MODULE_SHA256 = "432951dda01dada3ca6cb2bbe522c35b43a17b37a8f796c0257ba3fd8a9a1cec"
    const val MODULE_BYTES = 162336L
    const val CRYPTO_NAME = "libfta_db_libcrypto_so_3_4122f5c950.so"
    const val CRYPTO_SHA256 = "4122f5c95088f11a43c14e49e257cc1ce700960e855840cf9b9f39a8acbcd7e8"
    private val roles = setOf("database-server", "database-client", "auth-server", "world-server")

    fun prepare(root: File, nativeDirectory: File, files: List<RuntimeFile>): File {
        val crypto = files.singleOrNull { it.logicalName == "libcrypto.so.3" }
        require(crypto != null && crypto.role == "library" && crypto.path == "native/$CRYPTO_NAME" &&
            crypto.sha256 == CRYPTO_SHA256 && crypto.size == 5_205_472L) { "World crypto dependency identity differs" }
        return preparePinned(root, nativeDirectory, RuntimeFile("native/$APK_NAME", MODULE_SHA256, MODULE_BYTES, "library"))
    }

    internal fun preparePinned(root: File, nativeDirectory: File, pin: RuntimeFile): File {
        require(pin.path == "native/$APK_NAME" && pin.role == "library")
        val nativeRoot = nativeDirectory.canonicalFile
        val source = File(nativeRoot, APK_NAME)
        require(source.canonicalFile.parentFile == nativeRoot && !Files.isSymbolicLink(source.toPath())) {
            "World crypto provider must be an extracted APK file"
        }
        RuntimeManifest.verifyFile(source, pin)
        val identity = MessageDigest.getInstance("SHA-256")
            .digest((pin.sha256 + "\n" + nativeRoot.absolutePath).toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(root.mkdirs() || root.isDirectory)
        check(!Files.isSymbolicLink(root.toPath())) { "World crypto provider directory is linked" }
        val directory = File(root.canonicalFile, identity)
        check(directory.mkdir() || directory.isDirectory)
        check(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile.parentFile == root.canonicalFile)
        val link = File(directory, "legacy.so").toPath()
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createSymbolicLink(link, source.toPath()) }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* Same install, another app process. */ }
        }
        check(Files.isSymbolicLink(link) && Files.readSymbolicLink(link) == source.toPath()) {
            "World crypto provider link differs"
        }
        return directory
    }

    /** Only the actual world session receives this override; no global environment is changed. */
    fun forRole(base: Array<String>, role: String, directory: File?): Array<String> {
        require(role in roles && base.none { it.substringBefore('=') in setOf("OPENSSL_MODULES", "OPENSSL_CONF") })
        if (role != "world-server") return base.copyOf()
        require(directory != null && directory.isAbsolute &&
            directory.absolutePath.none { it in "\r\n\u0000" } &&
            Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            Files.isSymbolicLink(File(directory, "legacy.so").toPath())) { "World crypto provider is unavailable" }
        return base + arrayOf("OPENSSL_MODULES=${directory.absolutePath}", "OPENSSL_CONF=/dev/null")
    }
}
