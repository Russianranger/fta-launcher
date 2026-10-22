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

class FullSeedStoreTest {
    private val root = Files.createTempDirectory("fta-full-seed").toFile()
    private fun sha(bytes: ByteArray) = FullSeedStore.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private data class Fixture(val archive: File, val spec: ArchiveSpec, val lock: FullSeedLock)
    private fun fixture(changed: Boolean = false, extraPath: String? = null): Fixture {
        val entries = FullSeedStore.SCHEMAS.flatMapIndexed { schemaIndex, schema ->
            List(listOf(27,165,371)[schemaIndex]) { index ->
                val bytes = "-- fixture $schema $index\n".toByteArray()
                FullSeedEntry("Database/$schema/table-fixture_$index.sql", schema, "table", "fixture_$index", bytes.size.toLong(), sha(bytes)) to bytes
            }
        } + run {
            val bytes = "-- fixture trigger\n".toByteArray()
            listOf(FullSeedEntry("Database/acore_world/trigger-fixture_trigger.sql", "acore_world", "trigger", "fixture_trigger", bytes.size.toLong(), sha(bytes)) to bytes)
        }
        val bytes = ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            entries.forEachIndexed { index, (entry, content) ->
                zip.putNextEntry(ZipEntry(entry.path))
                zip.write(if (changed && index == 1) ByteArray(content.size) { 42 } else content)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("Database/RuntimeSQL/Data/data/sql/updates/db_world/never_apply.sql"))
            zip.write("-- not part of seed-only gate\n".toByteArray()); zip.closeEntry()
            if (extraPath != null) { zip.putNextEntry(ZipEntry(extraPath)); zip.write(byteArrayOf(1)); zip.closeEntry() }
        } }.toByteArray()
        val archive = File(root, "database.zip").apply { writeBytes(bytes) }
        val spec = ArchiveSpec("database", "Synthetic", sha(bytes), bytes.size.toLong())
        val schemas = FullSeedStore.SCHEMAS.mapIndexed { index, name -> FullSeedSchema(name, listOf(27,165,371)[index],
            if (index == 2) listOf("fixture_trigger") else emptyList(), 0, 0, 1, 1, "a".repeat(64)) }
        return Fixture(archive, spec, FullSeedLock(spec.sha256, spec.bytes, entries.map { it.first }.reversed(), schemas))
    }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun stagesOnlyUnchangedSeedBytesAndAllTablesBeforeTrigger() {
        val f = fixture()
        val target = File(root, "fresh")
        val result = FullSeedStore(f.spec).stage(f.archive, f.lock, target)
        assertEquals(564, result.entries.size)
        assertTrue(result.entries.take(563).all { it.first.kind == "table" })
        assertEquals("trigger", result.entries.last().first.kind)
        assertTrue(result.entries.all { (entry, file) -> file.isFile && !file.canExecute() && sha(file.readBytes()) == entry.sha256 })
        assertEquals(564, target.walkTopDown().count { it.isFile })
        assertFalse(target.walkTopDown().any { "never_apply" in it.name })
    }

    @Test fun existingTargetAndPrivateMarkerArePreserved() {
        val f = fixture()
        val target = File(root, "existing").apply { mkdir(); File(this, "save-marker").writeText("preserve") }
        assertThrows(Exception::class.java) { FullSeedStore(f.spec).stage(f.archive, f.lock, target) }
        assertEquals("preserve", File(target, "save-marker").readText())
    }

    @Test fun wrongPerFileDigestCleansOnlyNewPartialTarget() {
        val f = fixture(changed = true)
        val target = File(root, "fresh")
        assertThrows(Exception::class.java) { FullSeedStore(f.spec).stage(f.archive, f.lock, target) }
        assertFalse(target.exists()); assertTrue(f.archive.exists())
    }

    @Test fun syntheticArchiveIsNotAcceptedByProductionDefault() {
        val f = fixture()
        assertThrows(Exception::class.java) { FullSeedStore().stage(f.archive, f.lock, File(root, "fresh")) }
        assertFalse(File(root, "fresh").exists())
    }

    @Test fun cancellationDuringHashVerificationCreatesNoTarget() {
        val f = fixture()
        var checks = 0
        val target = File(root, "fresh")
        assertThrows(IllegalStateException::class.java) {
            FullSeedStore(f.spec).stage(f.archive, f.lock, target, { checks++; if (checks == 1) error("cancel") })
        }
        assertFalse(target.exists()); assertTrue(f.archive.exists())
    }

    @Test fun cancellationBetweenFilesRemovesPartialStagingAndKeepsInput() {
        val f = fixture()
        var cancel = false
        val target = File(root, "fresh")
        assertThrows(IllegalStateException::class.java) {
            FullSeedStore(f.spec).stage(f.archive, f.lock, target,
                { if (cancel) error("cancel") }, { done, _ -> if (done == 3) cancel = true })
        }
        assertFalse(target.exists()); assertTrue(f.archive.exists())
    }

    @Test fun invalidInventoryAndUnsafeRoutesFailBeforeCreatingTarget() {
        val f = fixture()
        val changes = listOf(f.lock.copy(files = f.lock.files.drop(1)),
            f.lock.copy(files = f.lock.files.mapIndexed { index, entry -> if (index == 0) entry.copy(path = "../escape") else entry }),
            f.lock.copy(schemas = f.lock.schemas.reversed()),
            f.lock.copy(schemas = f.lock.schemas.map { it.copy(routineCount = 1) }),
            f.lock.copy(schemas = f.lock.schemas.map { it.copy(receiptBytes = 0) }))
        changes.forEach { lock -> assertThrows(Exception::class.java) { FullSeedStore(f.spec).stage(f.archive, lock, File(root, "fresh")) } }
        assertFalse(File(root, "fresh").exists())
    }

    @Test fun evenUnselectedUnsafeZipMetadataIsRejected() {
        val f = fixture(extraPath = "../unused")
        assertThrows(Exception::class.java) { FullSeedStore(f.spec).stage(f.archive, f.lock, File(root, "fresh")) }
        assertFalse(File(root, "fresh").exists())
    }
}
