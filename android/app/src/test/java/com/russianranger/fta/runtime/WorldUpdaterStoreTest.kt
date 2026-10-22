package com.russianranger.fta.runtime

import com.russianranger.fta.ArchiveSpec
import com.russianranger.fta.Inputs
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WorldUpdaterStoreTest {
    private val root = Files.createTempDirectory("fta-world-sql-test").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private fun hash(bytes: ByteArray, algorithm: String = "SHA-256") = FullSeedStore.hex(MessageDigest.getInstance(algorithm).digest(bytes))
    private fun fixture(): Triple<File, ArchiveSpec, WorldUpdaterLock> {
        val bytes = "SELECT 1;\n".toByteArray()
        val entries = List(236) { index ->
            val schema = FullSeedStore.SCHEMAS[index % 3]
            val path = "Data/data/sql/updates/db_${schema.removePrefix("acore_")}/fixture_$index.sql"
            WorldSqlEntry("Database/RuntimeSQL/$path", path, schema, bytes.size.toLong(), hash(bytes), hash(bytes, "SHA-1").uppercase())
        }
        val archive = File(root, "Database.zip")
        ZipOutputStream(archive.outputStream()).use { zip -> entries.forEach {
            zip.putNextEntry(ZipEntry(it.path)); zip.write(bytes); zip.closeEntry()
        } }
        val spec = ArchiveSpec("database", "fixture", hash(archive.readBytes()), archive.length())
        val schemas = FullSeedStore.SCHEMAS.map { WorldUpdaterSchema(it, listOf("fixture"), emptyList(), 0, 0,
            1, 100, "a".repeat(64), 0, 0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", 1, 100, "b".repeat(64)) }
        val phases = listOf("Auth", "Character", "World").map {
            WorldUpdatePhase(it, "Updating $it database...", ">> $it database is up-to-date! Containing 1 new and 0 archived updates.")
        }
        return Triple(archive, spec, WorldUpdaterLock(spec.sha256, spec.bytes, Inputs.source.sha256, "android-arm64-world-v7", "c".repeat(64),
            List(52) { "modules/mod-fixture-$it" }, emptyList(), entries,
            FullSeedStore.SCHEMAS.map { WorldIncludeDirectory("data/sql/updates/db_${it.removePrefix("acore_")}", "RELEASED", it) }, schemas, phases))
    }

    @Test fun exactArchiveBytesKeepOriginalDataTreeAndOriginalSha1Case() {
        val (archive, spec, lock) = fixture()
        val destination = File(root, "stage")
        val source = WorldUpdaterStore(spec).stage(archive, lock, destination)
        assertEquals(File(destination, "Data"), source)
        assertEquals(236, destination.walkTopDown().count { it.isFile })
        assertFalse(File(source, "Data").exists())
        lock.runtimeSqlFiles.forEach { assertEquals(it.sha256, hash(File(destination, it.targetPath).readBytes())) }
        assertEquals(52, File(source, "modules").listFiles()!!.size)
    }

    @Test fun wrongArchiveDigestDuplicateTargetTraversalAndUnreviewedSourceFailClosed() {
        val (archive, spec, lock) = fixture()
        val mutations = listOf(lock.copy(sourceArchiveSha256 = "f".repeat(64)),
            lock.copy(runtimeSqlFiles = lock.runtimeSqlFiles.dropLast(1)),
            lock.copy(runtimeSqlFiles = lock.runtimeSqlFiles.mapIndexed { index, entry -> if (index == 1) lock.runtimeSqlFiles[0] else entry }),
            lock.copy(runtimeSqlFiles = lock.runtimeSqlFiles.mapIndexed { index, entry -> if (index == 0) entry.copy(targetPath = "../outside.sql") else entry }),
            lock.copy(runtimeSqlFiles = lock.runtimeSqlFiles.mapIndexed { index, entry -> if (index == 0) entry.copy(sha1 = entry.sha1.lowercase()) else entry }))
        mutations.forEach { invalid -> assertThrows(Exception::class.java) { WorldUpdaterStore(spec).stage(archive, invalid, File(root, "stage")) } }
        archive.appendBytes(byteArrayOf(1))
        assertThrows(Exception::class.java) { WorldUpdaterStore(spec).stage(archive, lock, File(root, "stage")) }
        assertFalse(File(root, "stage").exists())
    }

    @Test fun corruptPinnedMemberOrCancellationRemovesOnlyItsDisposableStage() {
        val (archive, spec, lock) = fixture()
        val sentinel = File(root, "unrelated-save").apply { writeText("untouched") }
        val invalid = lock.copy(runtimeSqlFiles = lock.runtimeSqlFiles.mapIndexed { index, entry -> if (index == 0) entry.copy(sha256 = "f".repeat(64)) else entry })
        val destination = File(root, "stage")
        assertThrows(Exception::class.java) { WorldUpdaterStore(spec).stage(archive, invalid, destination) }
        assertFalse(destination.exists())
        var afterCreation = 0
        assertThrows(Exception::class.java) { WorldUpdaterStore(spec).stage(archive, lock, destination, {
            if (destination.exists() && ++afterCreation > 8) error("cancelled")
        }) }
        assertFalse(destination.exists()); assertEquals("untouched", sentinel.readText())
    }

    private fun canonical(): File = listOf("manifests/world-updater-lock.json", "../manifests/world-updater-lock.json", "../../manifests/world-updater-lock.json")
        .map(::File).first { it.isFile }

    @Test fun canonicalMetadataParsesStrictlyAndBindsExactSourcePolicy() {
        val json = canonical().readText()
        val lock = WorldUpdaterMetadata.parse(json)
        assertEquals(236, lock.runtimeSqlFiles.size); assertEquals(52, lock.moduleDirectories.size)
        assertEquals(listOf(27, 166, 372), lock.schemas.map { it.tableNames.size })
        assertEquals(listOf(12, 53, 3194), lock.schemas.map { it.receiptCount })
        assertEquals(listOf(0, 20, 2), lock.schemas.map { it.releaseReceiptCount })
        assertTrue(lock.moduleSqlDirectories.isEmpty())
        assertThrows(Exception::class.java) { WorldUpdaterMetadata.parse(json.replaceFirst("\"moduleCount\": 52", "\"moduleCount\": \"52\"")) }
        assertThrows(Exception::class.java) { WorldUpdaterMetadata.parse(json.replaceFirst("\"formatVersion\": 1", "\"formatVersion\": 1,\"formatVersion\": 1")) }
    }

    /** Optional private proof. It never executes SQL and is absent from ordinary/public CI. */
    @Test fun privateOriginalArchiveStagesAll236UnchangedSqlFilesAtExactSourceDirectory() {
        val path = System.getenv("FTA_PRIVATE_DATABASE_ZIP")
        assumeTrue(path != null)
        val lock = WorldUpdaterMetadata.parse(canonical().readText())
        val destination = File(root, "real-stage")
        val source = WorldUpdaterStore().stage(File(path!!), lock, destination)
        assertEquals(File(destination, "Data"), source)
        assertEquals(236, destination.walkTopDown().count { it.isFile && it.extension == "sql" })
        lock.runtimeSqlFiles.forEach { entry ->
            val file = File(destination, entry.targetPath)
            assertEquals(entry.size, file.length()); assertEquals(entry.sha256, hash(file.readBytes()))
        }
        lock.includeDirectories.forEach { assertTrue(File(source, it.path).isDirectory) }
        assertFalse(File(source, "Data").exists())
    }
}
