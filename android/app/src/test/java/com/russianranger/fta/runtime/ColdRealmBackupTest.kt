package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ColdRealmBackupTest {
    private val identity = PersistentRealmStore.IDENTITY_FIELDS.associateWith { "a".repeat(64) }
    private val noop: (File) -> Unit = {}
    private fun workspace(test: (File, PersistentRealmStore, RealmGeneration) -> Unit) {
        val base = Files.createTempDirectory("fta-backup-test").toFile()
        try {
            val store = PersistentRealmStore(File(base, "active"), identity)
            val realm = store.exclusive {
                store.createFresh().also {
                    File(it.directory, "client.cnf").writeText("private-test-credential")
                    File(it.directory, "database/empty").mkdirs()
                    File(it.directory, "database/retained").writeText("committed-value")
                    store.prepared(it); store.activate(it)
                }
            }
            test(base, store, realm)
        } finally { base.deleteRecursively() }
    }
    private fun backup(base: File, realm: RealmGeneration): Pair<File, RealmBackupManifest> {
        val m = RealmBackupManifest(realm.id, identity, ColdRealmBackup.inventory(realm.directory))
        val archive = File(base, "backup.zip")
        ColdRealmBackup.create(realm.directory, archive, m, noop) {}
        return archive to m
    }
    private fun restore(base: File, archive: File, m: RealmBackupManifest): File {
        val dest = File(base, "restored").apply { mkdir() }
        ColdRealmBackup.restore(archive, ColdRealmBackup.sha256(archive), m, dest, noop) {}
        return dest
    }
    private fun rewrite(archive: File, name: String? = null, edit: (String, ByteArray) -> ByteArray = { _, b -> b }) {
        val entries = ZipFile(archive).use { z -> z.entries().asSequence().map { it.name to z.getInputStream(it).readBytes() }.toList() }
        ZipOutputStream(archive.outputStream()).use { z ->
            entries.forEach { (path, bytes) -> z.putNextEntry(ZipEntry(if (path == "database/retained" && name != null) name else path)); z.write(edit(path, bytes)); z.closeEntry() }
        }
    }
    @Test fun roundTripPreservesEveryFileAndEmptyDirectoryButExcludesLogsAndSeed() = workspace { base, _, realm ->
        File(realm.directory, "seed.sql").writeText("must not be backed up")
        File(realm.directory, "private.log").writeText("must not be backed up")
        val (archive, m) = backup(base, realm)
        assertEquals(m, ColdRealmBackup.inspect(archive))
        val dest = restore(base, archive, m)
        assertEquals(m.entries, ColdRealmBackup.inventory(dest))
        assertTrue(File(dest, "database/empty").isDirectory)
        assertFalse(File(dest, "seed.sql").exists()); assertFalse(File(dest, "private.log").exists())
    }
    @Test fun sourceLinksAreRejectedWithoutReadingTargets() = workspace { base, _, realm ->
        val outside = File(base, "outside").apply { writeText("private") }
        Files.createSymbolicLink(File(realm.directory, "database/link").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.inventory(realm.directory) }
        assertEquals("private", outside.readText())
    }
    @Test fun changedColdSourceCannotPublishArchive() = workspace { base, _, realm ->
        val m = RealmBackupManifest(realm.id, identity, ColdRealmBackup.inventory(realm.directory))
        File(realm.directory, "database/retained").writeText("changed")
        val archive = File(base, "backup.zip")
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.create(realm.directory, archive, m, noop) {} }
        assertFalse(archive.exists())
    }
    @Test fun cancellationRemovesOnlyItsIncompleteArchive() = workspace { base, _, realm ->
        val m = RealmBackupManifest(realm.id, identity, ColdRealmBackup.inventory(realm.directory))
        val archive = File(base, "backup.zip")
        assertThrows(IllegalStateException::class.java) {
            ColdRealmBackup.create(realm.directory, archive, m, noop) { if (File(base, ".backup.zip.partial").exists()) error("cancel") }
        }
        assertFalse(archive.exists()); assertFalse(File(base, ".backup.zip.partial").exists())
        assertEquals("committed-value", File(realm.directory, "database/retained").readText())
    }
    @Test fun existingArchiveCannotBeOverwritten() = workspace { base, _, realm ->
        val (archive, m) = backup(base, realm); val before = archive.readBytes()
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.create(realm.directory, archive, m, noop) {} }
        assertArrayEquals(before, archive.readBytes())
    }
    @Test fun preexistingPartialFileIsNeverDeletedByAnotherAttempt() = workspace { base, _, realm ->
        val m = RealmBackupManifest(realm.id, identity, ColdRealmBackup.inventory(realm.directory))
        val partial = File(base, ".backup.zip.partial").apply { writeText("keep prior attempt") }
        assertThrows(IOException::class.java) { ColdRealmBackup.create(realm.directory, File(base, "backup.zip"), m, noop) {} }
        assertEquals("keep prior attempt", partial.readText())
    }
    @Test fun duplicateZipEntriesAreRejected() = workspace { base, _, realm ->
        File(realm.directory, "database/retaiNed").writeText("second")
        val (archive, _) = backup(base, realm)
        // Patch equally sized local/central names to make a ZIP that ZipOutputStream refuses to create directly.
        val bytes = archive.readBytes(); val from = "database/retaiNed".toByteArray(); val to = "database/retained".toByteArray()
        for (i in 0..(bytes.size - from.size)) if (from.indices.all { bytes[i + it] == from[it] }) to.copyInto(bytes, i)
        archive.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.inspect(archive) }
    }
    @Test fun exportedCopyMustMatchBothLengthAndHash() = workspace { base, _, realm ->
        val (archive, _) = backup(base, realm); val bytes = archive.readBytes(); val sha = ColdRealmBackup.sha256(archive)
        ColdRealmBackup.verifyExport(ByteArrayInputStream(bytes), bytes.size.toLong(), sha)
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.verifyExport(ByteArrayInputStream(bytes.copyOf(bytes.size - 1)), bytes.size.toLong(), sha) }
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.verifyExport(ByteArrayInputStream(bytes), bytes.size.toLong() - 1, sha) }
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.verifyExport(ByteArrayInputStream(bytes), bytes.size.toLong(), "0".repeat(64)) }
    }
    @Test fun restoreRefusesNonemptyDestinationAndWrongArchiveDigest() = workspace { base, _, realm ->
        val (archive, m) = backup(base, realm); val dest = File(base, "restored").apply { mkdir() }
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.restore(archive, "0".repeat(64), m, dest, noop) {} }
        File(dest, "existing-save").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.restore(archive, ColdRealmBackup.sha256(archive), m, dest, noop) {} }
        assertEquals("keep", File(dest, "existing-save").readText())
    }
    @Test fun traversalInManifestIsRejectedBeforeExtraction() = workspace { base, _, realm ->
        val (archive, _) = backup(base, realm)
        rewrite(archive, "../escaped") { path, bytes ->
            if (path == "fta-backup.properties") bytes.toString(Charsets.ISO_8859_1).replace("database/retained", "../escaped").toByteArray(Charsets.ISO_8859_1) else bytes
        }
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.inspect(archive) }
        assertFalse(File(base, "escaped").exists())
    }
    @Test fun oversizeDeclarationIsRejectedBeforeExtraction() = workspace { base, _, realm ->
        val (archive, _) = backup(base, realm)
        rewrite(archive) { path, bytes ->
            if (path != "fta-backup.properties") bytes else {
                val p = Properties().apply { load(ByteArrayInputStream(bytes)) }
                p.stringPropertyNames().first { it.endsWith(".size") }.let { p[it] = Long.MAX_VALUE.toString() }
                ByteArrayOutputStream().also { p.store(it, null) }.toByteArray()
            }
        }
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.inspect(archive) }
    }
    @Test fun tamperedFilePayloadFailsEvenWhenArchiveDigestIsRecomputed() = workspace { base, _, realm ->
        val (archive, m) = backup(base, realm)
        rewrite(archive) { name, bytes -> if (name == "database/retained") ByteArray(bytes.size) { 'x'.code.toByte() } else bytes }
        assertEquals(m, ColdRealmBackup.inspect(archive))
        assertThrows(IllegalArgumentException::class.java) { restore(base, archive, m) }
    }
    @Test fun extraEntryAndTruncatedArchiveAreRejected() = workspace { base, _, realm ->
        val (archive, _) = backup(base, realm)
        rewrite(archive, "database/unexpected")
        assertThrows(IllegalArgumentException::class.java) { ColdRealmBackup.inspect(archive) }
        RandomAccessFile(archive, "rw").use { it.setLength(archive.length() / 2) }
        assertThrows(IOException::class.java) { ColdRealmBackup.inspect(archive) }
    }
    @Test fun storeRestoreCannotReplaceAnActiveRealmOrAcceptDifferentSourceIdentity() = workspace { base, store, realm ->
        val (archive, m) = backup(base, realm)
        store.exclusive { assertThrows(IllegalStateException::class.java) { store.restorePrepared(realm.id) { fail("Restore entered live generation") } } }
        val wrong = PersistentRealmStore(File(base, "wrong"), identity + ("seedSha256" to "b".repeat(64)))
        wrong.exclusive { assertThrows(IllegalArgumentException::class.java) { wrong.restorePrepared(realm.id) { ColdRealmBackup.restore(archive, ColdRealmBackup.sha256(archive), m, it, noop) {} } } }
    }
    private inner class Driver : RealmBackupProtocol.Driver {
        var originalCalls = 0; var restoredCalls = 0; var failPass = 0; var cancel = false
        override fun checkActive() { check(!cancel) }
        override fun syncDirectory(directory: File) { }
        override fun verifyOriginal(realm: RealmGeneration) { originalCalls++ }
        override fun restoredStore(directory: File) = PersistentRealmStore(directory, identity)
        override fun stageRestored(realm: RealmGeneration) { }
        override fun verifyRestored(store: PersistentRealmStore, realm: RealmGeneration, pass: Int) {
            restoredCalls++; check(pass != failPass)
            check(File(realm.directory, "database/retained").readText() == "committed-value")
            // Native reopen changes its own database files; original equality must still pass.
            File(realm.directory, "database/restored-only").writeText("local")
        }
    }
    @Test fun completeProtocolKeepsActiveDataAndOnlyRemovesSuccessfulRehearsal() = workspace { base, store, realm ->
        val before = ColdRealmBackup.inventory(realm.directory); val driver = Driver(); val flags = mutableMapOf<String, Boolean>()
        val directory = File(base, "attempt")
        val result = RealmBackupProtocol.run(store, identity, directory, driver) { k, v -> flags[k] = v }
        assertEquals(1, driver.originalCalls); assertEquals(2, driver.restoredCalls)
        assertTrue(RealmBackupProtocol.fields.all { flags[it] == true }); assertTrue(result.archive.isFile)
        assertFalse(File(directory, "restore").exists()); assertEquals(before, ColdRealmBackup.inventory(realm.directory))
        store.exclusive { assertEquals(realm, store.active()) }
    }
    @Test fun eitherRestoreFailureCannotReplaceActiveRealmOrClaimSuccess() = workspace { base, store, realm ->
        for (pass in 1..2) {
            val flags = mutableMapOf<String, Boolean>(); val directory = File(base, "failed$pass")
            assertThrows(IllegalStateException::class.java) { RealmBackupProtocol.run(store, identity, directory, Driver().apply { failPass = pass }) { k, v -> flags[k] = v } }
            assertTrue(File(directory, "restore").exists()); assertNotEquals(true, flags["finalCleanShutdown"])
            store.exclusive { assertEquals(realm, store.active()) }
        }
    }
    @Test fun cancelledProtocolCannotOpenOrCopyDatabase() = workspace { base, store, _ ->
        val driver = Driver().apply { cancel = true }; val directory = File(base, "attempt")
        assertThrows(IllegalStateException::class.java) { RealmBackupProtocol.run(store, identity, directory, driver) { _, _ -> } }
        assertEquals(0, driver.originalCalls); assertFalse(directory.exists())
    }
}
