package com.russianranger.fta.runtime

import com.russianranger.fta.ArchiveSpec
import com.russianranger.fta.Inputs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipFile

data class AuthSeedEntry(val path: String, val sha256: String, val size: Long, val role: String, val targetPath: String)
data class AuthSeedLock(
    val archiveSha256: String,
    val archiveSize: Long,
    val database: String = "acore_auth",
    val files: List<AuthSeedEntry>,
    val directories: List<String>,
    val sourceDirectory: String = "Data"
)
data class AuthSeedStage(val tableFiles: List<File>, val runtimeDirectory: File)

/** Stages exact pinned auth inputs for a disposable diagnostic; never connects to a database. */
class AuthSeedStore(private val expectedArchive: ArchiveSpec = Inputs.database) {
    companion object {
        private const val MAX_FILE_BYTES = 512L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        private val requiredDirectories = setOf(
            "Data/data/sql/archive/db_auth", "Data/data/sql/custom/db_auth",
            "Data/data/sql/updates/db_auth", "Data/data/sql/updates/pending_db_auth"
        )
    }

    fun stage(archive: File, lock: AuthSeedLock, targetFreshDirectory: File): AuthSeedStage {
        validateLock(lock)
        if (Files.exists(targetFreshDirectory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Auth diagnostic staging requires a new disposable directory")
        }
        val parent = targetFreshDirectory.parentFile
        if (parent == null || !Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Auth diagnostic staging parent is unavailable")
        }
        VerifiedInputReader.verify(archive, lock.archiveSha256, lock.archiveSize)
        PinnedDatabaseArchive.validate(archive)
        var created = false
        try {
            if (!targetFreshDirectory.mkdir()) throw IOException("Cannot create fresh auth diagnostic staging")
            created = true
            makePrivate(targetFreshDirectory, directory = true)
            lock.directories.forEach { path ->
                val directory = File(targetFreshDirectory, path)
                if (!directory.mkdirs() && !directory.isDirectory) throw IOException("Cannot stage auth runtime directories")
                makePrivate(directory, directory = true)
            }
            ZipFile(archive).use { zip ->
                lock.files.forEach { entry ->
                    val source = zip.getEntry(entry.path) ?: throw IOException("Pinned auth seed entry is missing")
                    if (source.isDirectory || source.size != entry.size) throw IOException("Pinned auth seed entry has the wrong size")
                    val target = File(targetFreshDirectory, entry.targetPath)
                    if (!target.parentFile!!.mkdirs() && !target.parentFile!!.isDirectory) throw IOException("Cannot stage auth seed directories")
                    makePrivate(target.parentFile!!, directory = true)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var bytes = 0L
                    zip.getInputStream(source).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw IOException("Auth seed staging interrupted")
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                bytes += read
                                if (bytes > entry.size) throw IOException("Pinned auth seed entry exceeds its size")
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    }
                    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                    if (bytes != entry.size || actual != entry.sha256) throw IOException("Pinned auth seed entry digest differs from the lock")
                    makePrivate(target, directory = false)
                }
            }
            return AuthSeedStage(lock.files.filter { it.role == "auth-table" }.sortedBy { it.targetPath }
                .map { File(targetFreshDirectory, it.targetPath) }, File(targetFreshDirectory, lock.sourceDirectory))
        } catch (error: Exception) {
            if (created) deleteTree(targetFreshDirectory)
            throw error
        }
    }

    private fun validateLock(lock: AuthSeedLock) {
        require(lock.archiveSha256 == expectedArchive.sha256 && lock.archiveSize == expectedArchive.bytes) {
            "Auth seed lock differs from the reviewed database archive"
        }
        require(lock.database == "acore_auth" && lock.sourceDirectory == "Data") { "Unsupported auth diagnostic database or source directory" }
        require(lock.directories.size == requiredDirectories.size && lock.directories.toSet() == requiredDirectories) {
            "Auth runtime directory inventory differs from the reviewed release"
        }
        require(lock.files.size == 28 && lock.files.count { it.role == "auth-table" } == 27 &&
            lock.files.count { it.role == "auth-update" } == 1) { "Auth seed inventory must contain exactly 27 tables and one update" }
        require(lock.files.map { it.path.lowercase() }.toSet().size == lock.files.size &&
            lock.files.map { it.targetPath.lowercase() }.toSet().size == lock.files.size) { "Duplicate auth seed path or target" }
        lock.files.forEach { entry ->
            safePath(entry.path)
            safePath(entry.targetPath)
            require(entry.sha256.matches(Regex("[0-9a-f]{64}")) && entry.size in 1..MAX_FILE_BYTES) { "Invalid auth seed entry identity" }
            when (entry.role) {
                "auth-table" -> {
                    require(entry.path.matches(Regex("Database/acore_auth/table-[A-Za-z0-9_+-]+\\.sql")) &&
                        entry.targetPath == "auth-seed/" + entry.path.substringAfterLast('/')) { "Invalid auth seed table route" }
                }
                "auth-update" -> {
                    require(entry.path.matches(Regex("Database/RuntimeSQL/Data/data/sql/updates/db_auth/[A-Za-z0-9_+-]+\\.sql")) &&
                        entry.targetPath == entry.path.removePrefix("Database/RuntimeSQL/")) { "Invalid auth runtime SQL route" }
                }
                else -> throw IllegalArgumentException("Unsupported auth seed role")
            }
        }
        require(lock.files.sumOf { it.size } <= MAX_TOTAL_BYTES) { "Auth diagnostic staging exceeds size limit" }
    }

    private fun makePrivate(file: File, directory: Boolean) {
        if (!file.setReadable(false, false) || !file.setWritable(false, false) || !file.setExecutable(false, false) ||
            !file.setReadable(true, true) || !file.setWritable(true, true) ||
            (directory && !file.setExecutable(true, true))) throw IOException("Cannot restrict auth staging permissions")
    }

    private fun safePath(path: String, directory: Boolean = false) {
        val value = if (directory) path.removeSuffix("/") else path
        require(value.isNotEmpty() && value.length <= 240 && !value.startsWith('/') && !value.contains('\\') &&
            value.split('/').all { it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._+-]{1,120}")) }) { "Unsafe auth seed path" }
    }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

}
