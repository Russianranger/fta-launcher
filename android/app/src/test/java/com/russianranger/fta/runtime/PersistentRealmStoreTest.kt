package com.russianranger.fta.runtime

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.channels.OverlappingFileLockException

class PersistentRealmStoreTest {
    private val identity = PersistentRealmStore.IDENTITY_FIELDS.associateWith { "a".repeat(64) }
    private fun workspace(test: (File) -> Unit) {
        val dir = Files.createTempDirectory("fta-realm-store").toFile()
        try { test(File(dir, "realm")) } finally { dir.deleteRecursively() }
    }
    private class Driver : RealmStorageProtocol.Driver {
        var preparations = 0
        var verifications = 0
        var failPrepare = false
        var failPass = 0
        var cancelled = false
        val roots = mutableListOf<File>()
        override fun checkActive() { check(!cancelled) }
        override fun prepare(realm: RealmGeneration) {
            preparations++; roots += realm.directory
            File(realm.directory, "retained-data").writeText("committed")
            check(!failPrepare)
        }
        override fun verify(realm: RealmGeneration, pass: Int) {
            verifications++; roots += realm.directory
            check(File(realm.directory, "retained-data").readText() == "committed")
            check(pass != failPass)
        }
    }
    private fun run(store: PersistentRealmStore, driver: Driver): Map<String, Boolean> {
        val observed = mutableMapOf<String, Boolean>()
        RealmStorageProtocol.run(store, driver) { key, value -> observed[key] = value }
        return observed
    }
    @Test fun firstPreparationAndLaterSessionRetainOneGenerationWithoutSeedReplay() = workspace { base ->
        val first = Driver(); val store = PersistentRealmStore(base, identity)
        val proof = run(store, first)
        assertTrue(proof.getValue("seedImportNeeded")); assertEquals(1, first.preparations); assertEquals(2, first.verifications)
        assertTrue(RealmStorageProtocol.fields.all { proof[it] == true })
        val pointer = File(base, "active.properties").readBytes()
        val next = Driver().apply { failPrepare = true }
        val repeated = run(PersistentRealmStore(base, identity), next)
        assertFalse(repeated.getValue("seedImportNeeded")); assertEquals(0, next.preparations); assertEquals(2, next.verifications)
        assertEquals(first.roots.first(), next.roots.first()); assertArrayEquals(pointer, File(base, "active.properties").readBytes())
    }
    @Test fun failedPreparationIsRetainedAndRetryAlwaysGetsAFreshGeneration() = workspace { base ->
        val first = Driver().apply { failPrepare = true }
        assertThrows(IllegalStateException::class.java) { run(PersistentRealmStore(base, identity), first) }
        assertFalse(File(base, "active.properties").exists())
        val next = Driver(); run(PersistentRealmStore(base, identity), next)
        assertNotEquals(first.roots.first(), next.roots.first())
        assertEquals("committed", File(first.roots.first(), "retained-data").readText())
    }
    @Test fun preparedCandidateResumesAfterEitherReopenFailsWithoutReimport() = workspace { base ->
        for (pass in 1..2) {
            val target = File(base.parentFile, "case$pass")
            val first = Driver().apply { failPass = pass }
            assertThrows(IllegalStateException::class.java) { run(PersistentRealmStore(target, identity), first) }
            assertFalse(File(target, "active.properties").exists())
            val retry = Driver().apply { failPrepare = true }
            assertFalse(run(PersistentRealmStore(target, identity), retry).getValue("seedImportNeeded"))
            assertEquals(0, retry.preparations); assertEquals(first.roots.first(), retry.roots.first())
        }
    }
    @Test fun malformedActivePointerNeverMeansFreshInstall() = workspace { base ->
        run(PersistentRealmStore(base, identity), Driver())
        File(base, "active.properties").writeText("broken=true\n")
        val next = Driver()
        assertThrows(IllegalArgumentException::class.java) { run(PersistentRealmStore(base, identity), next) }
        assertEquals(0, next.preparations)
    }
    @Test fun missingActiveGenerationNeverMeansFreshInstall() = workspace { base ->
        val first = Driver(); run(PersistentRealmStore(base, identity), first)
        assertTrue(first.roots.first().renameTo(File(base.parentFile, "retained-outside")))
        val next = Driver()
        assertThrows(IllegalArgumentException::class.java) { run(PersistentRealmStore(base, identity), next) }
        assertEquals(0, next.preparations)
    }
    @Test fun changedSourceIdentityOrPreparedReceiptBlocksReuse() = workspace { base ->
        val driver = Driver(); run(PersistentRealmStore(base, identity), driver)
        assertThrows(IllegalArgumentException::class.java) {
            run(PersistentRealmStore(base, identity + ("seedSha256" to "b".repeat(64))), Driver())
        }
        File(driver.roots.first(), "prepared.properties").appendText("compiledUpdaterChecks=23\n")
        assertThrows(IllegalArgumentException::class.java) { run(PersistentRealmStore(base, identity), Driver()) }
    }
    @Test fun activeGenerationCannotBeReimportedOrReplaced() = workspace { base ->
        val store = PersistentRealmStore(base, identity); run(store, Driver())
        store.exclusive {
            val realm = store.active()!!
            assertThrows(IllegalStateException::class.java) { store.requireFresh(realm) }
            assertThrows(IllegalStateException::class.java) { store.createFresh() }
            assertThrows(IllegalStateException::class.java) { store.activate(realm) }
        }
    }
    @Test fun nonemptyCandidateIsNotFreshAndCannotImportAgain() = workspace { base ->
        val store = PersistentRealmStore(base, identity)
        store.exclusive {
            val realm = store.createFresh(); store.requireFresh(realm)
            File(realm.directory, "partial-database").writeText("retain")
            assertThrows(IllegalStateException::class.java) { store.requireFresh(realm) }
        }
    }
    @Test fun concurrentOwnerCannotEnterTheRealm() = workspace { base ->
        PersistentRealmStore(base, identity).exclusive {
            assertThrows(OverlappingFileLockException::class.java) { PersistentRealmStore(base, identity).exclusive { fail("Second owner entered") } }
        }
    }
    @Test fun symlinkedGenerationIsRefusedWithoutTouchingItsTarget() = workspace { base ->
        val driver = Driver(); run(PersistentRealmStore(base, identity), driver)
        val root = driver.roots.first(); val target = File(base.parentFile, "outside")
        assertTrue(root.renameTo(target)); Files.createSymbolicLink(root.toPath(), target.toPath())
        assertThrows(IllegalArgumentException::class.java) { run(PersistentRealmStore(base, identity), Driver()) }
        assertEquals("committed", File(target, "retained-data").readText()); root.delete()
    }
    @Test fun ambiguousLaunchMarkerCannotBeClearedAsIfStopped() = workspace { base ->
        val store = PersistentRealmStore(base, identity)
        val owner = ProcessObservation.self(File("/proc/self/stat").readText().substringBefore(' ').toInt())
        store.exclusive { store.serverLaunching(owner) }
        assertThrows(IllegalArgumentException::class.java) { PersistentRealmStore(base, identity).exclusive { fail("Ambiguous writer accepted") } }
    }
    @Test fun livingOwnerOrChildBlocksAnotherSession() = workspace { base ->
        val store = PersistentRealmStore(base, identity)
        val owner = ProcessObservation.self(File("/proc/self/stat").readText().substringBefore(' ').toInt())
        store.exclusive {
            store.serverLaunching(owner); store.serverStarted(owner, owner.copy(parentPid = owner.pid))
            assertThrows(IllegalStateException::class.java) { store.serverStopped(owner) }
        }
        assertThrows(IllegalStateException::class.java) { PersistentRealmStore(base, identity).exclusive { fail("Living owner accepted") } }
    }
    @Test fun independentlyAbsentOwnerAndChildPermitRecovery() = workspace { base ->
        val store = PersistentRealmStore(base, identity)
        val owner = ProcessIdentity(Int.MAX_VALUE - 1, 1, 1); val child = ProcessIdentity(Int.MAX_VALUE, 2, owner.pid)
        store.exclusive { store.serverLaunching(owner); store.serverStarted(owner, child) }
        PersistentRealmStore(base, identity).exclusive { assertFalse(File(base, "writer.properties").exists()) }
    }
    @Test fun failureAfterAtomicActivationNeverCausesSeedReplay() = workspace { base ->
        val store = PersistentRealmStore(base, identity) { dir ->
            if (dir == base && File(base, "active.properties").exists()) throw IOException("Injected directory sync interruption")
        }
        assertThrows(IOException::class.java) { run(store, Driver()) }
        val retry = Driver().apply { failPrepare = true }
        assertFalse(run(PersistentRealmStore(base, identity), retry).getValue("seedImportNeeded"))
        assertEquals(0, retry.preparations)
    }
    @Test fun cancellationBeforeWorkCannotActivateOrImport() = workspace { base ->
        val driver = Driver().apply { cancelled = true }
        assertThrows(IllegalStateException::class.java) { run(PersistentRealmStore(base, identity), driver) }
        assertEquals(0, driver.preparations); assertFalse(File(base, "active.properties").exists())
    }
    @Test fun canaryRejectsWrongTokenAndInvalidIdentifier() {
        val id = "00000000-0000-0000-0000-000000000001"
        assertThrows(IllegalStateException::class.java) { RealmStorageCanary.verify(id) { "1:wrong" } }
        assertThrows(IllegalArgumentException::class.java) { RealmStorageCanary.create("unsafe';") { fail("Unsafe query"); "" } }
        RealmStorageCanary.verify(id) { "1:$id\n" }
    }
    @Test fun reopeningRequiresEverySchemaAndARecordedCleanShutdown() = workspace { base ->
        base.mkdir()
        val attempt = "00000000-0000-0000-0000-000000000001"
        fun evidence(metadataChecks: Int, clean: Boolean): Map<String, Any> {
            val file = File(base, "journal-$metadataChecks-$clean.properties")
            val journal = NativeInvocationJournal(file, attempt)
            val db = journal.begin("server", "database-server", "REALM_REOPEN", null)
            journal.checkpoint(db, "database_ready")
            for ((index, stage) in (listOf("REALM_VERIFY") + List(metadataChecks) { "REALM_METADATA" }).withIndex()) {
                val id = journal.begin("tool-${index + 1}", "database-client", stage, 10000)
                journal.finish(id, "exited", 0, 0, 0)
            }
            journal.checkpoint(db, "sql_shutdown_requested"); journal.finish(db, "exited", 0, 0, 0)
            if (clean) journal.checkpoint(db, "clean_shutdown")
            return RealmStorageNativeEvidence.read(file, attempt, false)
        }
        assertEquals(true, evidence(3, true)["qualified"])
        assertEquals(false, evidence(2, true)["qualified"])
        assertEquals(false, evidence(3, false)["qualified"])
    }
}
