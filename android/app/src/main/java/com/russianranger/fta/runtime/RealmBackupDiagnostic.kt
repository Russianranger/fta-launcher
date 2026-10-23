package com.russianranger.fta.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicBoolean

object RealmBackupDiagnostic {
    const val PASSED = "REALM_BACKUP_PASSED"
    data class ExportIdentity(val attempt: String, val sha256: String, val bytes: Long)
    private fun root(context: Context) = File(context.filesDir, "realm-backups")
    private fun attemptDirectory(context: Context, attempt: String): File {
        require(NativeInvocationJournal.validAttemptId(attempt)); return File(root(context), "b-$attempt")
    }
    fun run(context: Context, cancelled: AtomicBoolean, status: (String, String) -> Unit,
            track: (DatabaseDiagnostic?) -> Unit): String {
        val attempt = File(context.filesDir, "runtime/diagnostic-attempt.id").readText().trim()
        val directory = attemptDirectory(context, attempt)
        val identity = RealmStorageDiagnostic.identity(context)
        val store = RealmStorageDiagnostic.store(context, identity)
        val receipt = JSONObject().put("formatVersion", 1).put("attemptId", attempt).put("identity", JSONObject(identity))
        RealmBackupProtocol.fields.forEach { receipt.put(it, false) }
        fun save() = RuntimeRecoveryService.atomic(File(context.filesDir, "runtime/realm-backup.json"), receipt.toString())
        save()
        val parent = root(context)
        if (!Files.exists(parent.toPath(), NOFOLLOW_LINKS)) check(parent.mkdir())
        require(Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS))
        val deadline = System.nanoTime() + 30L * 60 * 1_000_000_000
        val driver = object : RealmBackupProtocol.Driver {
            override fun checkActive() {
                check(!cancelled.get()) { "Realm backup stopped" }
                check(System.nanoTime() < deadline) { "Realm backup exceeded its deadline" }
            }
            private fun verify(targetStore: PersistentRealmStore, realm: RealmGeneration, pass: Int, label: String) {
                val session = RealmDatabaseSession(targetStore, realm, attempt, false, pass)
                val test = DatabaseDiagnostic(context, cancelled, status, realm = session)
                try { track(test); test.run() } finally { test.stopOwned(); track(null) }
                val journal = File(session.visitDirectory(), "native-invocations.properties")
                check(RealmStorageNativeEvidence.read(journal, attempt, false)["qualified"] == true)
                val evidence = File(root(context), "e-$attempt")
                if (!Files.exists(evidence.toPath(), NOFOLLOW_LINKS)) check(evidence.mkdir())
                require(Files.isDirectory(evidence.toPath(), NOFOLLOW_LINKS))
                require(journal.length() in 1..512_000)
                RuntimeRecoveryService.atomic(File(evidence, "$label.properties"), journal.readText())
            }
            override fun verifyOriginal(realm: RealmGeneration) {
                status("BACKUP_SOURCE", "Checking the retained realm and shutting it down before backup.")
                verify(store, realm, 1, "original")
                status("BACKUP_COPY", "Creating and checking a cold realm backup. No seed SQL is imported.")
            }
            override fun restoredStore(directory: File) = PersistentRealmStore(directory, identity, ::syncDirectory)
            override fun stageRestored(realm: RealmGeneration) {
                status("BACKUP_RESTORE", "Restoring into an isolated copy; the active realm is retained.")
                PackagedRuntime.inspect(context).stageData(context, File(realm.directory, "payload"), ::checkActive)
                check(File(realm.directory, "plugin").mkdir())
            }
            override fun verifyRestored(store: PersistentRealmStore, realm: RealmGeneration, pass: Int) {
                status("BACKUP_VERIFY", "Verifying restored database restart $pass of 2.")
                verify(store, realm, pass, "restore$pass")
            }
            override fun syncDirectory(directory: File) = RealmStorageDiagnostic.syncDirectory(directory)
        }
        val result = RealmBackupProtocol.run(store, identity, directory, driver) { key, value -> receipt.put(key, value); save() }
        receipt.put("archiveSha256", result.sha256).put("archiveBytes", result.archive.length())
            .put("fileCount", result.fileCount).put("uncompressedBytes", result.uncompressedBytes)
            .put("activePointerSha256", result.activePointerSha256)
        save()
        // Publish export availability only after both restored launches and active-data equality pass.
        RuntimeRecoveryService.atomic(File(root(context), "latest.json"), receipt.toString())
        return "Realm backup and isolated restore passed. Both restored reopens matched all 24 strict checks and shut down cleanly. The active realm was preserved. Export the verified backup to keep a separate copy; it contains private database data and credentials."
    }
    private fun read(file: File): JSONObject {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..8192)
        return JSONObject(file.readText()).also { require(it.opt("formatVersion") == 1) }
    }
    fun export(context: Context, attempt: String, state: String?): Map<String, Any> {
        val receipt = runCatching { read(File(context.filesDir, "runtime/realm-backup.json"))
            .also { require(it.opt("attemptId") == attempt) } }.getOrNull()
            ?: return mapOf("qualified" to false, "evidenceAvailable" to false)
        val flags = RealmBackupProtocol.fields.associateWith { receipt.opt(it) == true }
        val result = mutableMapOf<String, Any>("attemptId" to attempt, "evidenceAvailable" to true, "qualified" to false,
            "checks" to flags, "seedImported" to false, "activeRealmReplaced" to false, "worldReady" to false)
        runCatching {
            require(RealmBackupProtocol.fields.all { receipt.opt(it) is Boolean })
            val identity = RealmStorageDiagnostic.identity(context)
            require(identity.all { (key, value) -> receipt.getJSONObject("identity").opt(key) == value })
            val phases = listOf("original", "restore1", "restore2").associateWith { name ->
                RealmStorageNativeEvidence.read(File(root(context), "e-$attempt/$name.properties"), attempt, false)
            }
            result["phases"] = phases; result["identity"] = identity
            val archive = File(attemptDirectory(context, attempt), "realm-backup.zip")
            val archiveVerified = archive.length() == receipt.optLong("archiveBytes", -1) &&
                ColdRealmBackup.sha256(archive) == receipt.optString("archiveSha256")
            result["archiveVerifiedAtExport"] = archiveVerified
            listOf("archiveSha256", "archiveBytes", "fileCount", "uncompressedBytes").forEach { result[it] = receipt.get(it) }
            val exported = runCatching { read(File(root(context), "exported.json")) }.getOrNull()
            result["exportedCopyVerified"] = exported?.opt("attemptId") == attempt &&
                exported.opt("archiveSha256") == receipt.opt("archiveSha256") && exported.opt("archiveBytes") == receipt.opt("archiveBytes")
            val store = RealmStorageDiagnostic.store(context, identity)
            val pointerMatches = store.exclusive { store.activePointerDigest() == receipt.optString("activePointerSha256") }
            result["activePointerVerifiedAtExport"] = pointerMatches
            result["qualified"] = state == PASSED && flags.values.all { it } && phases.values.all { it["qualified"] == true } && archiveVerified && pointerMatches
        }
        return result
    }
    fun exportLatest(context: Context, output: OutputStream): ExportIdentity {
        val receipt = read(File(root(context), "latest.json"))
        require(RealmBackupProtocol.fields.all { receipt.opt(it) == true })
        val archive = File(attemptDirectory(context, receipt.getString("attemptId")), "realm-backup.zip")
        require(archive.length() == receipt.getLong("archiveBytes") && ColdRealmBackup.sha256(archive) == receipt.getString("archiveSha256"))
        archive.inputStream().use { it.copyTo(output, 256 * 1024) }; output.flush()
        return ExportIdentity(receipt.getString("attemptId"), receipt.getString("archiveSha256"), receipt.getLong("archiveBytes"))
    }
    fun verifyExport(context: Context, identity: ExportIdentity, input: InputStream) {
        ColdRealmBackup.verifyExport(input, identity.bytes, identity.sha256)
        RuntimeRecoveryService.atomic(File(root(context), "exported.json"), JSONObject().put("formatVersion", 1)
            .put("attemptId", identity.attempt).put("archiveSha256", identity.sha256).put("archiveBytes", identity.bytes).toString())
    }
}
