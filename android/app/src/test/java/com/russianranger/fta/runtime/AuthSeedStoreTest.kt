package com.russianranger.fta.runtime

import com.russianranger.fta.ArchiveSpec
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AuthSeedStoreTest {
    private val root = Files.createTempDirectory("fta-auth-stage").toFile()
    private val directories = listOf("Data/data/sql/archive/db_auth", "Data/data/sql/custom/db_auth",
        "Data/data/sql/updates/db_auth", "Data/data/sql/updates/pending_db_auth")
    private fun digest(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun payload(): LinkedHashMap<String, ByteArray> = linkedMapOf<String, ByteArray>().apply {
        repeat(27) { this["Database/acore_auth/table-fixture_$it.sql"] = "-- synthetic table fixture $it\n".toByteArray() }
        this["Database/RuntimeSQL/Data/data/sql/updates/db_auth/rev_fixture.sql"] = "-- synthetic updater fixture\n".toByteArray()
        this["Database/acore_characters/table-private.sql"] = "-- never extract unrelated content\n".toByteArray()
    }
    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> files.forEach { (path, bytes) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }
    private data class Fixture(val archive: File, val spec: ArchiveSpec, val lock: AuthSeedLock)
    private fun fixture(files: Map<String, ByteArray> = payload(), locked: Map<String, ByteArray> = payload(), bytes: ByteArray = zip(files)): Fixture {
        val archive = File(root, "input.zip").apply { writeBytes(bytes) }
        val spec = ArchiveSpec("database", "Synthetic database", digest(bytes), bytes.size.toLong())
        val entries = locked.filterKeys { "/acore_auth/" in it || "/db_auth/" in it }.map { (path, data) ->
            val table = "/acore_auth/" in path
            AuthSeedEntry(path, digest(data), data.size.toLong(), if (table) "auth-table" else "auth-update",
                if (table) "auth-seed/" + path.substringAfterLast('/') else path.removePrefix("Database/RuntimeSQL/"))
        }
        return Fixture(archive, spec, AuthSeedLock(spec.sha256, spec.bytes, files = entries, directories = directories))
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun stagesExactTablesAndOriginalUpdaterIntoFreshDirectory() {
        val fixture = fixture()
        val destination = File(root, "fresh")
        val result = AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, destination)
        assertEquals(27, result.tableFiles.size)
        assertTrue(result.tableFiles.all { it.isFile && !it.canExecute() })
        assertEquals(File(destination, "Data"), result.runtimeDirectory)
        assertTrue(File(result.runtimeDirectory, "data/sql/updates/db_auth/rev_fixture.sql").isFile)
        assertTrue(directories.all { File(destination, it).isDirectory })
        assertFalse(File(destination, "Database").exists())
        assertEquals(28, destination.walkTopDown().count { it.isFile })
    }

    @Test fun existingTargetEvenEmptyIsRefusedAndPreserved() {
        val fixture = fixture()
        val target = File(root, "existing").apply { mkdir() }
        assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, target) }
        assertTrue(target.isDirectory)
        File(target, "save-marker").writeText("preserve")
        assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, target) }
        assertEquals("preserve", File(target, "save-marker").readText())
    }

    @Test fun productionDefaultRejectsSyntheticArchiveAndUntrustedLock() {
        val fixture = fixture()
        assertThrows(Exception::class.java) { AuthSeedStore().stage(fixture.archive, fixture.lock, File(root, "fresh")) }
        assertFalse(File(root, "fresh").exists())
    }

    @Test fun archiveReplacedOrTruncatedAfterInputVerificationIsRejected() {
        val fixture = fixture()
        val original = fixture.archive.readBytes()
        fixture.archive.writeBytes(original.copyOf().apply { this[0] = (this[0] + 1).toByte() })
        assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, File(root, "fresh")) }
        fixture.archive.writeBytes(original.copyOf(original.size - 1))
        assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, File(root, "fresh")) }
        assertFalse(File(root, "fresh").exists())
    }

    @Test fun perFileDigestFailureRemovesOnlyNewPartialTarget() {
        val files = payload().apply { this["Database/acore_auth/table-fixture_9.sql"] = "-- changed untrusted table bytes\n".toByteArray() }
        val fixture = fixture(files)
        val target = File(root, "fresh")
        assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, target) }
        assertFalse(target.exists())
        assertTrue(fixture.archive.exists())
    }

    @Test fun missingPinnedEntryFailsAndCleansPartialStaging() {
        val files = payload().apply { remove("Database/acore_auth/table-fixture_10.sql") }
        val fixture = fixture(files)
        assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, File(root, "fresh")) }
        assertFalse(File(root, "fresh").exists())
    }

    @Test fun unsafeOrDuplicateUnusedZipEntriesStillFailBeforeExtraction() {
        for (path in listOf("../escape", "Database\\escape.sql", "/absolute", "Database/acore_auth/TABLE-FIXTURE_0.SQL")) {
            val fixture = fixture(payload().apply { this[path] = "-- unused\n".toByteArray() })
            assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, File(root, "fresh")) }
            assertFalse(File(root, "fresh").exists())
        }
    }

    @Test fun unsafeTargetsWrongDirectoriesAndWrongCountsAreRefused() {
        val fixture = fixture()
        val variants = listOf(
            fixture.lock.copy(files = fixture.lock.files.drop(1)),
            fixture.lock.copy(database = "live_database"),
            fixture.lock.copy(sourceDirectory = "../Data"),
            fixture.lock.copy(directories = directories.drop(1)),
            fixture.lock.copy(files = fixture.lock.files.mapIndexed { i, entry -> if (i == 0) entry.copy(targetPath = "../escape") else entry })
        )
        variants.forEach { lock -> assertThrows(Exception::class.java) {
            AuthSeedStore(fixture.spec).stage(fixture.archive, lock, File(root, "fresh"))
        } }
        assertFalse(File(root, "fresh").exists())
    }

    @Test fun symlinkEntryInPinnedZipIsRejected() {
        val archive = zip(payload())
        val central = (0..archive.size - 46).first { offset ->
            archive[offset] == 0x50.toByte() && archive[offset + 1] == 0x4b.toByte() &&
                archive[offset + 2] == 1.toByte() && archive[offset + 3] == 2.toByte()
        }
        archive[central + 40] = 0xff.toByte(); archive[central + 41] = 0xa1.toByte()
        val fixture = fixture(bytes = archive)
        assertThrows(Exception::class.java) { AuthSeedStore(fixture.spec).stage(fixture.archive, fixture.lock, File(root, "fresh")) }
        assertFalse(File(root, "fresh").exists())
    }
}
