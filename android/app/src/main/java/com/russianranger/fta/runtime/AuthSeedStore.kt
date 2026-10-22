package com.russianranger.fta.runtime

import com.russianranger.fta.ArchiveSpec
import com.russianranger.fta.Inputs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
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
        private const val MAX_ENTRIES = 4096
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
        validateZipMetadata(archive)
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

    /** Validate every central entry, including unused world/characters files; none are extracted. */
    private fun validateZipMetadata(archive: File) {
        RandomAccessFile(archive, "r").use { input ->
            if (input.length() < 22) throw IOException("Database ZIP is incomplete")
            val tailSize = minOf(input.length(), 65_557L).toInt()
            val tail = ByteArray(tailSize)
            input.seek(input.length() - tailSize); input.readFully(tail)
            fun short(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
            fun unsigned(bytes: ByteArray, offset: Int) = short(bytes, offset).toLong() or (short(bytes, offset + 2).toLong() shl 16)
            val end = (tail.size - 22 downTo 0).firstOrNull {
                unsigned(tail, it) == 0x06054b50L && it + 22 + short(tail, it + 20) == tail.size
            } ?: throw IOException("Database ZIP central directory is missing")
            val count = short(tail, end + 10)
            val offset = unsigned(tail, end + 16)
            val size = unsigned(tail, end + 12)
            if (count !in 1..MAX_ENTRIES || short(tail, end + 4) != 0 || short(tail, end + 6) != 0 ||
                short(tail, end + 8) != count || offset + size != input.length() - tailSize + end) {
                throw IOException("Split, ZIP64 or oversized database ZIP metadata is unsupported")
            }
            input.seek(offset)
            val names = mutableSetOf<String>()
            var uncompressed = 0L
            repeat(count) {
                val header = ByteArray(46); input.readFully(header)
                val nameSize = short(header, 28)
                if (unsigned(header, 0) != 0x02014b50L || short(header, 8) and 1 != 0 ||
                    short(header, 10) !in setOf(0, 8) || nameSize !in 1..241 || short(header, 34) != 0) {
                    throw IOException("Unsupported database ZIP entry metadata")
                }
                val nameBytes = ByteArray(nameSize); input.readFully(nameBytes)
                if (nameBytes.any { it.toInt() !in 32..126 }) throw IOException("Database ZIP path is not portable ASCII")
                val name = nameBytes.toString(Charsets.US_ASCII)
                safePath(name, name.endsWith('/'))
                if (!names.add(name.lowercase())) throw IOException("Duplicate database ZIP path")
                val mode = ((unsigned(header, 38) ushr 16) and 0xf000).toInt()
                if (mode !in setOf(0, 0x8000, 0x4000) || (mode == 0x4000 && !name.endsWith('/'))) {
                    throw IOException("Database ZIP symlinks and special entries are forbidden")
                }
                val bytes = unsigned(header, 24)
                uncompressed += bytes
                if (bytes > MAX_FILE_BYTES || uncompressed > MAX_TOTAL_BYTES || (name.endsWith('/') && bytes != 0L)) {
                    throw IOException("Database ZIP decompressed-size limit exceeded")
                }
                val extra = ByteArray(short(header, 30)); input.readFully(extra)
                var cursor = 0
                while (cursor < extra.size) {
                    if (cursor + 4 > extra.size) throw IOException("Malformed database ZIP extra field")
                    val field = short(extra, cursor)
                    val length = short(extra, cursor + 2)
                    if (field == 1 || cursor + 4 + length > extra.size) throw IOException("ZIP64 or malformed database ZIP extra field")
                    cursor += 4 + length
                }
                input.seek(input.filePointer + short(header, 32))
                if (input.filePointer > offset + size) throw IOException("Database ZIP central-directory bounds mismatch")
            }
            if (input.filePointer != offset + size) throw IOException("Database ZIP central-directory size mismatch")
        }
    }
}
