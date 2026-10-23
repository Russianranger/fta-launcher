package com.russianranger.fta.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicBoolean

/** A foreground session over the existing realm; no bootstrap or seed replay path. */
object LocalServiceRuntime {
    const val STOPPED = "LOCAL_SERVICE_STOPPED"
    val fields = listOf("verifiedBackupAvailable", "metadataBeforePassed", "offlineRealmStatePrepared") +
        LocalServiceProtocol.fields + listOf("temporaryRoleRemoved", "metadataAfterPassed", "databaseCleanShutdown", "activeRealmRetained")
    private fun file(context: Context, previous: Boolean = false) = File(context.filesDir,
        "runtime/local-service-${if (previous) "previous" else "current"}.json")
    private fun read(file: File): JSONObject {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..8192)
        return JSONObject(file.readText()).also { require(it.opt("formatVersion") == 1) }
    }
    fun run(context: Context, cancelled: AtomicBoolean, stopRequested: AtomicBoolean,
        status: (String, String) -> Unit, track: (DatabaseDiagnostic?) -> Unit): String {
        val attempt = File(context.filesDir, "runtime/diagnostic-attempt.id").readText().trim()
        require(NativeInvocationJournal.validAttemptId(attempt))
        val identity = RealmStorageDiagnostic.identity(context)
        val store = RealmStorageDiagnostic.store(context, identity)
        // Keep only the immediately preceding session. A failed or interrupted session
        // breaks the restart chain; it cannot silently fall back to an older success.
        val previous = runCatching { read(file(context)) }.getOrNull()
        RuntimeRecoveryService.atomic(file(context, true), (previous ?: JSONObject().put("formatVersion", 1)).toString())
        val receipt = JSONObject().put("formatVersion", 1).put("attemptId", attempt).put("identity", JSONObject(identity))
            .put("completed", false).put("healthySamples", 0).put("elapsedMs", 0).put("screenOffHealthyMs", 0).put("stopReason", "running")
        fields.forEach { receipt.put(it, false) }
        fun save() = RuntimeRecoveryService.atomic(file(context), receipt.toString())
        fun observed(key: String, value: Boolean) {
            require(key in fields || key == "staleStartupFlagCleared"); receipt.put(key, value); save()
        }
        save()
        store.exclusive {
            val realm = store.active() ?: error("Prepare the persistent realm first")
            receipt.put("realmId", realm.id); save()
            receipt.put("backupSha256", LocalAuthDiagnostic.verifiedBackup(context, cancelled, identity, store))
            observed("verifiedBackupAvailable", true)
            val session = RealmDatabaseSession(store, realm, attempt, false, 1)
            val control = LocalServiceControl(stopRequested::get) { progress ->
                receipt.put("healthySamples", progress.healthySamples).put("elapsedMs", progress.elapsedMs)
                    .put("screenOffHealthyMs", progress.screenOffHealthyMs).put("stopReason", progress.stopReason); save()
                if (progress.stopReason == "running") status("LOCAL_SERVICE_RUNNING",
                    "Local database and auth are running. Verified screen-off health: ${progress.screenOffHealthyMs / 1000} / 120 seconds. ${if (progress.screenOffHealthyMs >= LocalServiceProtocol.SCREEN_OFF_REQUIRED_MS) "Screen-off requirement met." else "Lock the screen for at least three minutes to cover complete health intervals."} Use Stop when finished; the session limit is 30 minutes.")
            }
            val test = DatabaseDiagnostic(context, cancelled, status, realm = session, localAuthObserve = ::observed, localService = control)
            try { track(test); test.run() } finally { test.stopOwned(); track(null) }
            check(store.active() == realm); observed("activeRealmRetained", true)
            if (receipt.optString("stopReason") != "before_start") {
                check(LocalAuthDiagnostic.nativeEvidence(File(session.visitDirectory(), "native-invocations.properties"), attempt, true)["qualified"] == true)
                check(fields.all { receipt.opt(it) == true })
                receipt.put("completed", true); save()
            }
        }
        return if (receipt.optString("stopReason") == "before_start") "Stop completed before the auth listener started. The retained database shut down cleanly."
            else "Local service stopped cleanly${if (receipt.optString("stopReason") == "session_limit") " at its 30-minute limit" else " at your request"}. Auth stopped before SQL database shutdown; all 24 original metadata checks still match. You can start another session. Export the report after your second stop."
    }
    private fun evidence(receipt: JSONObject, identity: Map<String, String>, store: PersistentRealmStore): Map<String, Any> {
        val attempt = receipt.getString("attemptId")
        require(NativeInvocationJournal.validAttemptId(attempt))
        require(identity.all { (key, value) -> receipt.getJSONObject("identity").opt(key) == value })
        require(fields.all { receipt.opt(it) is Boolean })
        val realm = store.inspectGeneration(receipt.getString("realmId"))
        val native = LocalAuthDiagnostic.nativeEvidence(File(realm.directory, "visits/$attempt-1/native-invocations.properties"), attempt, true)
        val samples = receipt.getInt("healthySamples"); val elapsed = receipt.getLong("elapsedMs"); val off = receipt.getLong("screenOffHealthyMs")
        val reason = receipt.getString("stopReason")
        require(samples in 0..185 && elapsed in 0..LocalServiceProtocol.MAX_SESSION_MS + 60_000 && off in 0..elapsed)
        require(reason in setOf("running", "user", "session_limit", "before_start"))
        val qualified = receipt.opt("completed") == true && fields.all { receipt.opt(it) == true } &&
            native["qualified"] == true && native["healthySamples"] == samples && samples > 0 &&
            reason in setOf("user", "session_limit") && store.active() == realm
        return mapOf("attemptId" to attempt, "qualified" to qualified, "checks" to fields.associateWith { receipt.opt(it) == true },
            "nativeEvidence" to native, "activeGenerationVerified" to (store.active() == realm),
            "healthySamples" to samples, "elapsedMs" to elapsed, "screenOffHealthyMs" to off,
            "screenOffQualified" to (qualified && off >= LocalServiceProtocol.SCREEN_OFF_REQUIRED_MS), "stopReason" to reason)
    }
    fun export(context: Context, attempt: String, state: String?): Map<String, Any> {
        val result = mutableMapOf<String, Any>("qualified" to false, "restartQualified" to false, "screenOffQualified" to false,
            "evidenceAvailable" to false, "seedImported" to false, "worldReady" to false, "gameLoginQualified" to false,
            "bindAddress" to "127.0.0.1", "sessionLimitMs" to LocalServiceProtocol.MAX_SESSION_MS)
        runCatching {
            val current = read(file(context)); require(current.opt("attemptId") == attempt)
            val identity = RealmStorageDiagnostic.identity(context)
            val store = RealmStorageDiagnostic.store(context, identity)
            store.exclusive {
                val now = evidence(current, identity, store)
                val previous = runCatching {
                    val before = read(file(context, true))
                    require(before.opt("realmId") == current.opt("realmId") && before.opt("attemptId") != attempt)
                    evidence(before, identity, store)
                }.getOrNull()
                val qualified = state == STOPPED && now["qualified"] == true
                result["evidenceAvailable"] = true; result["identity"] = identity; result["currentSession"] = now
                previous?.let { result["previousSession"] = it }
                result["qualified"] = qualified
                result["restartQualified"] = qualified && previous?.get("qualified") == true
                result["screenOffQualified"] = qualified && (now["screenOffQualified"] == true || previous?.get("screenOffQualified") == true)
            }
        }
        return result
    }
}
