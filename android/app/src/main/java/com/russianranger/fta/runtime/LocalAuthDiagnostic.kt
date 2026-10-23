package com.russianranger.fta.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicBoolean

/** Retained database plus original loopback authserver; never bootstraps or imports a seed. */
object LocalAuthDiagnostic {
    const val PASSED = "LOCAL_AUTH_PASSED"
    val fields = listOf("verifiedBackupAvailable", "metadataBeforePassed", "offlineRealmStatePrepared") + LocalAuthProtocol.fields +
        listOf("temporaryRoleRemoved", "metadataAfterPassed", "databaseCleanShutdown", "activeRealmRetained")
    private fun read(file: File): JSONObject {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..8192)
        return JSONObject(file.readText()).also { require(it.opt("formatVersion") == 1) }
    }
    fun run(context: Context, cancelled: AtomicBoolean, status: (String, String) -> Unit, track: (DatabaseDiagnostic?) -> Unit): String {
        val attempt = File(context.filesDir, "runtime/diagnostic-attempt.id").readText().trim()
        require(NativeInvocationJournal.validAttemptId(attempt))
        val identity = RealmStorageDiagnostic.identity(context)
        val store = RealmStorageDiagnostic.store(context, identity)
        val receipt = JSONObject().put("formatVersion", 1).put("attemptId", attempt).put("identity", JSONObject(identity))
        fields.forEach { receipt.put(it, false) }
        fun save() = RuntimeRecoveryService.atomic(File(context.filesDir, "runtime/local-auth.json"), receipt.toString())
        fun observed(key: String, value: Boolean) { require(key in fields || key == "staleStartupFlagCleared"); receipt.put(key, value); save() }
        save()
        store.exclusive {
            val realm = store.active() ?: error("Prepare the persistent realm first")
            receipt.put("realmId", realm.id); save()
            receipt.put("backupSha256", verifiedBackup(context, cancelled, identity, store)); observed("verifiedBackupAvailable", true)
            val session = RealmDatabaseSession(store, realm, attempt, false, 1)
            val test = DatabaseDiagnostic(context, cancelled, status, realm = session, localAuthObserve = ::observed)
            try { track(test); test.run() } finally { test.stopOwned(); track(null) }
            check(nativeEvidence(File(session.visitDirectory(), "native-invocations.properties"), attempt)["qualified"] == true)
            check(store.active() == realm); observed("activeRealmRetained", true)
        }
        return "Local auth service passed: two original loopback listener starts, build-12340 protocol replies, sustained database health, clean auth stops and final SQL shutdown. All 24 metadata expectations still match. The realm and backup are retained; world startup and game login remain separate."
    }
    internal fun verifiedBackup(context: Context, cancelled: AtomicBoolean, identity: Map<String, String>, store: PersistentRealmStore): String {
        val backup = read(File(context.filesDir, "realm-backups/latest.json"))
        val backupAttempt = backup.getString("attemptId")
        require(NativeInvocationJournal.validAttemptId(backupAttempt))
        require(RealmBackupProtocol.fields.all { backup.opt(it) == true })
        require(identity.all { (key, value) -> backup.getJSONObject("identity").opt(key) == value })
        require(store.activePointerDigest() == backup.optString("activePointerSha256"))
        val archive = File(context.filesDir, "realm-backups/b-$backupAttempt/realm-backup.zip")
        require(Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS) && archive.length() == backup.getLong("archiveBytes"))
        require(ColdRealmBackup.sha256(archive) { check(!cancelled.get()) } == backup.getString("archiveSha256"))
        return backup.getString("archiveSha256")
    }
    internal fun nativeEvidence(file: File, attempt: String, service: Boolean = false): Map<String, Any> {
        val (records, dropped) = NativeInvocationJournal.read(file, attempt) ?: return mapOf("available" to false, "qualified" to false)
        val complete = records.isNotEmpty() && dropped == 0 && records.all {
            it.outcome == "exited" && it.exitCode == 0 && !it.timedOut && !it.cancelled && it.validationFailure == null &&
                it.stdoutBytes < NativeInvocationJournal.MAX_LOG_BYTES && it.stderrBytes < NativeInvocationJournal.MAX_LOG_BYTES
        }
        val db = records.filter { it.role == "database-server" }
        val auth = records.filter { it.role == "auth-server" }
        val qualified = complete && db.size == 1 && db.single().let { it.readyObserved && it.sqlShutdownRequested && it.cleanShutdownObserved } &&
            auth.size == (if (service) 1 else 2) && auth.all { it.stage == "LOCAL_AUTH_START" && it.terminationExitCode == 0 } &&
            records.count { it.stage == "REALM_METADATA" && it.role == "database-client" } == 6 &&
            records.count { it.stage == "REALM_VERIFY" && it.role == "database-client" } == 2
        return mapOf("available" to true, "qualified" to qualified, "recordCount" to records.size, "droppedCount" to dropped,
            "healthySamples" to records.count { it.stage == "LOCAL_SERVICE_RUNNING" && it.role == "database-client" },
            "nativeInvocations" to records.map { mapOf("id" to it.id, "role" to it.role, "stage" to it.stage,
                "outcome" to it.outcome, "exitCode" to it.exitCode, "terminationExitCode" to it.terminationExitCode,
                "timedOut" to it.timedOut, "cancelled" to it.cancelled, "validationFailure" to it.validationFailure,
                "durationMs" to it.durationMs, "databaseReadyObserved" to it.readyObserved,
                "sqlShutdownRequested" to it.sqlShutdownRequested, "cleanShutdownObserved" to it.cleanShutdownObserved) })
    }
    fun export(context: Context, attempt: String, state: String?): Map<String, Any> {
        val result = mutableMapOf<String, Any>("qualified" to false, "evidenceAvailable" to false,
            "seedImported" to false, "worldReady" to false, "gameLoginQualified" to false, "bindAddress" to "127.0.0.1")
        runCatching {
            val receipt = read(File(context.filesDir, "runtime/local-auth.json")); require(receipt.opt("attemptId") == attempt)
            val identity = RealmStorageDiagnostic.identity(context)
            require(identity.all { (key, value) -> receipt.getJSONObject("identity").opt(key) == value })
            require(fields.all { receipt.opt(it) is Boolean })
            result["evidenceAvailable"] = true; result["attemptId"] = attempt; result["identity"] = identity
            result["checks"] = fields.associateWith { receipt.opt(it) == true }
            result["staleStartupFlagCleared"] = receipt.opt("staleStartupFlagCleared") == true
            val store = RealmStorageDiagnostic.store(context, identity)
            store.exclusive {
                val realm = store.inspectGeneration(receipt.getString("realmId"))
                val journal = File(realm.directory, "visits/$attempt-1/native-invocations.properties")
                val native = nativeEvidence(journal, attempt); result["nativeEvidence"] = native
                result["activeGenerationVerified"] = store.active() == realm
                result["qualified"] = state == PASSED && fields.all { receipt.opt(it) == true } &&
                    native["qualified"] == true && store.active() == realm
            }
        }
        return result
    }
}
