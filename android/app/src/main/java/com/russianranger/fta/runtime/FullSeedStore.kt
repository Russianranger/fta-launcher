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

internal data class FullSeedEntry(val path: String, val schema: String, val kind: String,
    val name: String, val size: Long, val sha256: String)
internal data class FullSeedSchema(val name: String, val tableCount: Int, val triggerNames: List<String>,
    val routineCount: Int, val eventCount: Int, val receiptCount: Int, val receiptBytes: Int, val receiptSha256: String)
internal data class FullSeedLock(val archiveSha256: String, val archiveSize: Long,
    val files: List<FullSeedEntry>, val schemas: List<FullSeedSchema>)
internal data class FullSeedStage(val entries: List<Pair<FullSeedEntry, File>>)

/** Exact fresh-install SQL bytes only. No database connection, SQL rewriting or executable extraction. */
internal class FullSeedStore(private val expectedArchive: ArchiveSpec = Inputs.database) {
    fun stage(archive: File, lock: FullSeedLock, destination: File, checkCancelled: () -> Unit = {},
        progress: (Int, Int) -> Unit = { _, _ -> }): FullSeedStage {
        validate(lock)
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Full seed staging requires a new disposable directory" }
        require(destination.parentFile?.let { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) } == true)
        VerifiedInputReader.verify(archive, lock.archiveSha256, lock.archiveSize, checkCancelled)
        PinnedDatabaseArchive.validate(archive)
        var created = false
        try {
            checkCancelled()
            check(destination.mkdir()) { "Cannot create disposable seed staging" }
            created = true
            privatePermissions(destination, true)
            val ordered = lock.files.sortedWith(compareBy<FullSeedEntry>({ if (it.kind == "table") 0 else 1 },
                { SCHEMAS.indexOf(it.schema) }, { it.path }))
            val staged = ZipFile(archive).use { zip ->
                ordered.mapIndexed { index, entry ->
                    checkCancelled()
                    progress(index, ordered.size)
                    val source = zip.getEntry(entry.path) ?: throw IOException("Pinned full seed entry is missing")
                    require(!source.isDirectory && source.size == entry.size) { "Pinned full seed size differs" }
                    // Target names are derived from validated, pinned metadata, never arbitrary ZIP paths.
                    val target = File(destination, "${entry.schema}/${entry.kind}-${entry.name}.sql")
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    privatePermissions(target.parentFile!!, true)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    zip.getInputStream(source).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkCancelled()
                                if (Thread.currentThread().isInterrupted) throw IOException("Full seed staging interrupted")
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                copied += read
                                require(copied <= entry.size) { "Pinned full seed expanded beyond its size" }
                                digest.update(buffer, 0, read)
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    }
                    require(copied == entry.size && hex(digest.digest()) == entry.sha256) { "Pinned full seed digest differs" }
                    privatePermissions(target, false)
                    entry to target
                }
            }
            progress(ordered.size, ordered.size)
            return FullSeedStage(staged)
        } catch (failure: Exception) {
            if (created) destination.deleteRecursively()
            throw failure
        }
    }

    private fun validate(lock: FullSeedLock) {
        require(lock.archiveSha256 == expectedArchive.sha256 && lock.archiveSize == expectedArchive.bytes) { "Full seed lock differs from the reviewed release" }
        require(lock.schemas.map { it.name } == SCHEMAS && lock.schemas.map { it.tableCount } == listOf(27, 165, 371)) { "Full seed schema inventory differs" }
        require(lock.schemas.map { it.triggerNames.size } == listOf(0, 0, 1) &&
            lock.schemas.all { it.routineCount == 0 && it.eventCount == 0 && it.receiptCount in 1..10000 && it.receiptBytes in 1..1048576 && SHA256.matches(it.receiptSha256) }) { "Full seed metadata inventory differs" }
        require(lock.files.size == 564 && lock.files.map { it.path.lowercase() }.toSet().size == 564) { "Full seed file inventory differs" }
        require(lock.files.sumOf { it.size } <= 1024L * 1024 * 1024) { "Full seed expanded size exceeds its limit" }
        lock.files.forEach { entry ->
            require(entry.schema in SCHEMAS && entry.kind in setOf("table", "trigger") && IDENTIFIER.matches(entry.name) &&
                entry.path == "Database/${entry.schema}/${entry.kind}-${entry.name}.sql" &&
                entry.size in 1..256L * 1024 * 1024 && SHA256.matches(entry.sha256)) { "Invalid pinned full seed entry" }
        }
        lock.schemas.forEach { schema ->
            require(schema.triggerNames.all { IDENTIFIER.matches(it) } && schema.triggerNames.distinct().size == schema.triggerNames.size)
            require(lock.files.count { it.schema == schema.name && it.kind == "table" } == schema.tableCount &&
                lock.files.filter { it.schema == schema.name && it.kind == "trigger" }.map { it.name }.toSet() == schema.triggerNames.toSet()) { "Full seed objects differ from the reviewed inventory" }
        }
    }

    private fun privatePermissions(file: File, directory: Boolean) {
        check(file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false) &&
            file.setReadable(true, true) && file.setWritable(true, true) && (!directory || file.setExecutable(true, true))) { "Cannot restrict full seed staging permissions" }
    }

    companion object {
        val SCHEMAS = listOf("acore_auth", "acore_characters", "acore_world")
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
        private val SHA256 = Regex("[a-f0-9]{64}")
        internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
