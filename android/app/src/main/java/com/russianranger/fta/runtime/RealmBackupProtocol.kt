package com.russianranger.fta.runtime

import java.io.File

/** Serialize a clean snapshot and a complete restore rehearsal without replacing active storage. */
object RealmBackupProtocol {
    val fields = listOf("activeRealmVerified", "originalCleanShutdown", "archiveVerified", "restoredFilesMatch",
        "firstRestoredReopenPassed", "secondRestoredReopenPassed", "activeRealmUnchanged", "finalCleanShutdown")
    data class Result(val archive: File, val sha256: String, val fileCount: Int, val uncompressedBytes: Long, val activePointerSha256: String)
    interface Driver {
        fun checkActive()
        fun verifyOriginal(realm: RealmGeneration)
        fun restoredStore(directory: File): PersistentRealmStore
        fun stageRestored(realm: RealmGeneration)
        fun verifyRestored(store: PersistentRealmStore, realm: RealmGeneration, pass: Int)
        fun syncDirectory(directory: File)
    }
    fun run(store: PersistentRealmStore, identity: Map<String, String>, directory: File, driver: Driver,
            observe: (String, Boolean) -> Unit): Result = store.exclusive {
        driver.checkActive()
        val original = store.active() ?: error("Prepare the persistent realm before making a backup")
        val pointer = store.activePointerDigest()
        observe("activeRealmVerified", true)
        driver.verifyOriginal(original); observe("originalCleanShutdown", true)
        driver.checkActive(); check(directory.mkdir()) { "Backup attempt must use a new directory" }
        driver.syncDirectory(directory.parentFile!!)
        val manifest = RealmBackupManifest(original.id, identity, ColdRealmBackup.inventory(original.directory, driver::checkActive))
        val bytes = manifest.entries.sumOf { it.size }
        check(directory.usableSpace >= bytes * 2 + 256L * 1024 * 1024) { "Insufficient space for backup and isolated restore" }
        val archive = File(directory, "realm-backup.zip")
        val sha = ColdRealmBackup.create(original.directory, archive, manifest, driver::syncDirectory, driver::checkActive)
        check(ColdRealmBackup.inspect(archive) == manifest); observe("archiveVerified", true)
        val restoredBase = File(directory, "restore")
        val restoredStore = driver.restoredStore(restoredBase)
        restoredStore.exclusive {
            val restored = restoredStore.restorePrepared(original.id) {
                ColdRealmBackup.restore(archive, sha, manifest, it, driver::syncDirectory, driver::checkActive)
            }
            observe("restoredFilesMatch", true)
            driver.stageRestored(restored)
            driver.checkActive(); driver.verifyRestored(restoredStore, restored, 1); observe("firstRestoredReopenPassed", true)
            driver.checkActive(); driver.verifyRestored(restoredStore, restored, 2); observe("secondRestoredReopenPassed", true)
            // Verification never promotes the restored copy or changes the original active pointer.
            check(restoredStore.active() == null)
            driver.checkActive()
            check(store.active() == original && store.activePointerDigest() == pointer &&
                ColdRealmBackup.inventory(original.directory, driver::checkActive) == manifest.entries)
            observe("activeRealmUnchanged", true); observe("finalCleanShutdown", true)
        }
        // Only this attempt's completed, stopped rehearsal copy is disposable.
        check(restoredBase.deleteRecursively())
        Result(archive, sha, manifest.entries.count { !it.directory }, bytes, pointer)
    }
}
