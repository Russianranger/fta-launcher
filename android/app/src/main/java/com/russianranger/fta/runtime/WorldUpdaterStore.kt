package com.russianranger.fta.runtime

import com.russianranger.fta.ArchiveSpec
import com.russianranger.fta.Inputs
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class WorldSqlEntry(val path: String, val targetPath: String, val schema: String,
    val size: Long, val sha256: String, val sha1: String)
internal data class WorldIncludeDirectory(val path: String, val state: String, val schema: String)
internal data class WorldUpdatePhase(val database: String, val startMarker: String, val completionMarker: String,
    val orphanedReceiptWarnings: Int = 0)
internal data class WorldUpdaterSchema(val name: String, val tableNames: List<String>, val triggerNames: List<String>,
    val routineCount: Int, val eventCount: Int, val receiptCount: Int, val receiptBytes: Int, val receiptSha256: String,
    val releaseReceiptCount: Int, val releaseReceiptBytes: Int, val releaseReceiptSha256: String,
    val columnCount: Int, val columnBytes: Int, val columnSha256: String)
internal data class WorldUpdaterLock(val archiveSha256: String, val archiveSize: Long,
    val sourceArchiveSha256: String, val sourceProfile: String, val sourceTreeSha256: String,
    val moduleDirectories: List<String>, val moduleSqlDirectories: List<String>,
    val runtimeSqlFiles: List<WorldSqlEntry>, val includeDirectories: List<WorldIncludeDirectory>,
    val schemas: List<WorldUpdaterSchema>, val updatePhases: List<WorldUpdatePhase>)

/** Materializes only exact pinned SQL and measured source directories in a new private tree. */
internal class WorldUpdaterStore(private val databaseSpec: ArchiveSpec = Inputs.database) {
    fun stage(database: File, lock: WorldUpdaterLock, destination: File,
        checkCancelled: () -> Unit = {}): File {
        validate(lock)
        require(!Files.exists(destination.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            destination.parentFile?.let { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) } == true)
        VerifiedInputReader.verify(database, databaseSpec.sha256, databaseSpec.bytes, checkCancelled)
        PinnedDatabaseArchive.validate(database)
        var created = false
        try {
            checkCancelled()
            check(destination.mkdir()); created = true; privatePermissions(destination, true)
            val sourceDirectory = File(destination, "Data").apply { check(mkdir()); privatePermissions(this, true) }
            // The signed lock's zero-module-SQL fact is checked against the exact source
            // archive by the private APK stager. The device only materializes its inventory.
            (lock.includeDirectories.map { it.path } + lock.moduleDirectories +
                lock.moduleSqlDirectories).distinct().forEach { path ->
                checkCancelled()
                File(sourceDirectory, path).apply { check(mkdirs() || isDirectory); privatePermissions(this, true) }
            }
            ZipFile(database).use { zip ->
                lock.runtimeSqlFiles.forEach { entry ->
                    checkCancelled()
                    val inputEntry = zip.getEntry(entry.path) ?: error("Original runtime SQL is missing")
                    require(!inputEntry.isDirectory && inputEntry.size == entry.size)
                    val target = File(destination, entry.targetPath)
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    val sha256 = MessageDigest.getInstance("SHA-256")
                    val sha1 = MessageDigest.getInstance("SHA-1")
                    var size = 0L
                    zip.getInputStream(inputEntry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                checkCancelled()
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count; require(size <= entry.size)
                                sha256.update(buffer, 0, count); sha1.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    require(size == entry.size && FullSeedStore.hex(sha256.digest()) == entry.sha256 &&
                        FullSeedStore.hex(sha1.digest()).uppercase() == entry.sha1) { "Original runtime SQL identity differs" }
                    privatePermissions(target, false)
                }
            }
            return sourceDirectory
        } catch (failure: Exception) {
            if (created) destination.deleteRecursively()
            throw failure
        }
    }

    internal fun validate(lock: WorldUpdaterLock) {
        require(lock.archiveSha256 == databaseSpec.sha256 && lock.archiveSize == databaseSpec.bytes &&
            lock.sourceArchiveSha256 == Inputs.source.sha256 && SHA256.matches(lock.sourceTreeSha256) &&
            lock.sourceProfile.matches(Regex("android-arm64-world-v[0-9]{1,3}")))
        require(lock.moduleDirectories.size == 52 && lock.moduleDirectories.distinct().size == 52 &&
            lock.moduleDirectories.all { it.matches(Regex("modules/[A-Za-z0-9_+-]{1,100}")) })
        require(lock.moduleSqlDirectories.distinct().size == lock.moduleSqlDirectories.size &&
            lock.moduleSqlDirectories.all { path -> safePath(path) && path.startsWith("modules/") && "/data/sql" in path })
        require(lock.runtimeSqlFiles.size == 236 && lock.runtimeSqlFiles.map { it.targetPath.lowercase() }.toSet().size == 236 &&
            lock.runtimeSqlFiles.sumOf { it.size } <= 128L * 1024 * 1024)
        lock.runtimeSqlFiles.forEach { entry ->
            require(entry.schema in FullSeedStore.SCHEMAS && safePath(entry.path) && safePath(entry.targetPath) &&
                entry.targetPath.startsWith("Data/data/sql/") && entry.targetPath.endsWith(".sql") &&
                entry.path == "Database/RuntimeSQL/${entry.targetPath}" && entry.size in 1..16L * 1024 * 1024 &&
                SHA256.matches(entry.sha256) && entry.sha1.matches(Regex("[A-F0-9]{40}")))
        }
        require(lock.includeDirectories.isNotEmpty() && lock.includeDirectories.size <= 64 &&
            lock.includeDirectories.map { it.schema to it.path }.distinct().size == lock.includeDirectories.size)
        lock.includeDirectories.forEach { entry ->
            require(entry.schema in FullSeedStore.SCHEMAS && safePath(entry.path) && entry.path.startsWith("data/sql/") &&
                entry.state in setOf("RELEASED", "ARCHIVED", "CUSTOM", "MODULE", "PENDING"))
        }
        require(lock.schemas.map { it.name } == FullSeedStore.SCHEMAS)
        require(lock.updatePhases.map { it.database } == listOf("Auth", "Character", "World") &&
            lock.updatePhases.all { it.orphanedReceiptWarnings in 0..10000 && it.startMarker == "Updating ${it.database} database..." &&
                Regex(">> (?:Applied [1-9][0-9]{0,4} quer(?:y|ies)\\.|${it.database} database is up-to-date!) Containing [0-9]{1,5} new and [0-9]{1,5} archived updates\\.").matches(it.completionMarker) })
        lock.schemas.forEach { schema ->
            require(schema.tableNames.size in 1..1000 && schema.tableNames.distinct().size == schema.tableNames.size &&
                (schema.tableNames + schema.triggerNames).all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) } &&
                schema.triggerNames.distinct().size == schema.triggerNames.size && schema.routineCount == 0 && schema.eventCount == 0 &&
                schema.receiptCount in 1..10000 && schema.receiptBytes in 1..1048576 && SHA256.matches(schema.receiptSha256) &&
                schema.releaseReceiptCount in 0..10000 && schema.releaseReceiptBytes in 0..1048576 && SHA256.matches(schema.releaseReceiptSha256) &&
                schema.columnCount in 1..100000 && schema.columnBytes in 1..1048576 && SHA256.matches(schema.columnSha256))
        }
    }

    private fun privatePermissions(file: File, directory: Boolean) {
        check(file.setReadable(false, false) && file.setWritable(false, false) && file.setExecutable(false, false) &&
            file.setReadable(true, true) && file.setWritable(true, true) && (!directory || file.setExecutable(true, true)))
    }
    private fun safePath(path: String) = path.length in 1..240 && path.split('/').all {
        it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9._+-]{1,120}")) }
    companion object { private val SHA256 = Regex("[a-f0-9]{64}") }
}
