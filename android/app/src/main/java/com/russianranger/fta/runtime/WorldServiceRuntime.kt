package com.russianranger.fta.runtime

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.concurrent.atomic.AtomicBoolean

/** Retained realm only. Own both leases until every native child has stopped. */
object WorldServiceRuntime {
    const val STOPPED = "WORLD_SERVICE_STOPPED"
    private val fields = LocalServiceRuntime.fields + WorldSessionEvidence.fields
    private fun file(context: Context) = File(context.filesDir, "runtime/world-session.json")
    fun run(context: Context, cancelled: AtomicBoolean, stop: AtomicBoolean,
        status: (String, String) -> Unit, activityBound: () -> Boolean, track: (DatabaseDiagnostic?) -> Unit): String {
        val attempt = File(context.filesDir, "runtime/diagnostic-attempt.id").readText().trim()
        require(NativeInvocationJournal.validAttemptId(attempt))
        val identity = RealmStorageDiagnostic.identity(context)
        val store = RealmStorageDiagnostic.store(context, identity)
        val receipt = JSONObject().put("formatVersion", 1).put("attemptId", attempt).put("identity", JSONObject(identity))
            .put("completed", false).put("healthySamples", 0).put("elapsedMs", 0).put("screenOffHealthyMs", 0).put("stopReason", "running")
        (fields + WorldSessionEvidence.diagnosticFields).forEach { receipt.put(it, false) }
        fun save() = RuntimeRecoveryService.atomic(file(context), receipt.toString())
        fun observed(key: String, value: Boolean) {
            require(key in fields || key in WorldSessionEvidence.diagnosticFields || key == "staleStartupFlagCleared")
            if (receipt.opt(key) != value) { receipt.put(key, value); save() }
        }
        save()
        val verifyEnd = SystemClock.elapsedRealtime() + 3_600_000
        var lastUpdate = 0L
        val cache = GameDataCache()
        val operationStore = GameDataDiagnostics.store(context)
        val started = System.currentTimeMillis()
        var operation = GameDataOperation.Record(attempt, ProcessObservation.self(android.os.Process.myPid()),
            "verify", started, started, stage = "verify")
        fun checkpoint() {
            val vm = Runtime.getRuntime()
            operation = operation.copy(updated = System.currentTimeMillis(), freeStorage = context.filesDir.usableSpace.coerceAtLeast(0),
                heapUsed = (vm.totalMemory() - vm.freeMemory()).coerceIn(0, vm.maxMemory()), heapMax = vm.maxMemory(),
                memory = GameDataDiagnostics.memory(context, activityBound()), cache = cache.stats())
            operationStore.write(operation)
        }
        checkpoint(); GameDataDiagnostics.markProcess(context, attempt)
        try {
            GameDataImport.store(context, cache).withVerified({
                check(!cancelled.get() && !stop.get())
                check(SystemClock.elapsedRealtime() < verifyEnd) { "Asset verification deadline exceeded" }
            }, { done, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 1000 || done == total) {
                    lastUpdate = now
                    operation = operation.copy(completed = done, total = total); checkpoint()
                    status("WORLD_ASSET_VERIFY", "Verifying retained game data: ${done / 1048576} / ${total / 1048576} MiB. Keep the launcher visible until the world session starts.")
                }
            }) { assets, directory ->
                // This first native gate is bound to the unchanged, physically published code-26 candidate.
                require(assets.archiveSha256 == "407337836b15348dc21c360c60ddee7df5c67519ae735083b4fb952477273225" &&
                    assets.catalogSha256 == "0189b8b22be7673e3e9b2bf06c83a61d65bb7f6ac561198514cd57a28eb41bff" &&
                    assets.files == 22817 && assets.bytes == 7625943031L)
                receipt.put("archiveSha256", assets.archiveSha256).put("catalogSha256", assets.catalogSha256)
                    .put("assetGeneration", assets.generation).put("assetFiles", assets.files).put("assetBytes", assets.bytes)
                observed("assetsRehashed", true)
                // The rehash sub-operation is complete even if later native startup fails.
                operation = operation.copy(state = "FINISHED", completed = assets.bytes, total = assets.bytes, archive = assets.archiveSha256)
                checkpoint()
                store.exclusive {
                    val realm = store.active() ?: error("Prepare the persistent realm first")
                    receipt.put("realmId", realm.id); save()
                    receipt.put("backupSha256", LocalAuthDiagnostic.verifiedBackup(context, cancelled, identity, store))
                    observed("verifiedBackupAvailable", true)
                    val session = RealmDatabaseSession(store, realm, attempt, false, 1)
                    val control = LocalServiceControl(stop::get) { progress ->
                        receipt.put("healthySamples", progress.healthySamples).put("elapsedMs", progress.elapsedMs)
                            .put("screenOffHealthyMs", progress.screenOffHealthyMs).put("stopReason", progress.stopReason); save()
                        if (progress.stopReason == "running") status("WORLD_SERVICE_RUNNING",
                            "World, auth and database health checks are passing. Screen-off health: ${progress.screenOffHealthyMs / 1000} / 120 seconds. Lock the screen for at least three minutes, then reopen and use Stop. Session limit: 30 minutes.")
                    }
                    val test = DatabaseDiagnostic(context, cancelled, status, realm = session, localAuthObserve = ::observed,
                        localService = control, worldSession = WorldSessionControl(directory, ::observed))
                    try { track(test); test.run() } finally { test.stopOwned(); track(null) }
                    check(store.active() == realm); observed("activeRealmRetained", true)
                    if (receipt.optString("stopReason") != "before_start") {
                        check(LocalAuthDiagnostic.nativeEvidence(File(session.visitDirectory(), "native-invocations.properties"), attempt, true, true)["qualified"] == true)
                        check(fields.all { receipt.opt(it) == true })
                        receipt.put("completed", true); save()
                    }
                }
            }
        } finally {
            if (operation.state == "RUNNING") {
                operation = operation.copy(state = if (cancelled.get() || stop.get()) "STOPPED" else "FAILED")
                runCatching { checkpoint() }
            }
        }
        return "World session stopped; the retained realm and assets are preserved. Export the diagnostic report. " +
            if (receipt.optLong("screenOffHealthyMs") >= LocalServiceProtocol.SCREEN_OFF_REQUIRED_MS) "The 120-second screen-off observation was recorded."
            else "The 120-second screen-off observation is still incomplete."
    }
    fun export(context: Context, attempt: String, state: String?): Map<String, Any> {
        val result = mutableMapOf<String, Any>("qualified" to false, "databaseFallbackValidated" to false,
            "worldReady" to false, "screenOffQualified" to false, "gameLoginQualified" to false, "seedImported" to false)
        runCatching {
            val file = file(context)
            require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..8192)
            val r = JSONObject(file.readText())
            require(r.opt("formatVersion") == 1 && r.opt("attemptId") == attempt && NativeInvocationJournal.validAttemptId(attempt))
            val identity = RealmStorageDiagnostic.identity(context)
            require(identity.all { (key, value) -> r.getJSONObject("identity").opt(key) == value })
            require(fields.all { r.opt(it) is Boolean })
            result["checks"] = fields.associateWith { r.opt(it) == true }
            result["failureMarkers"] = WorldSessionEvidence.diagnosticFields.associateWith { r.opt(it) == true }
            result["attemptId"] = attempt
            val assets = GameDataImport.store(context).active()
            val assetsMatch = assets != null && assets.generation == r.optString("assetGeneration") &&
                assets.archiveSha256 == r.optString("archiveSha256") && assets.catalogSha256 == r.optString("catalogSha256") &&
                assets.files == r.optInt("assetFiles") && assets.bytes == r.optLong("assetBytes")
            result["activeAssetsMatch"] = assetsMatch
            if (assetsMatch) {
                result["archiveSha256"] = assets!!.archiveSha256; result["catalogSha256"] = assets.catalogSha256
                result["assetFiles"] = assets.files; result["assetBytes"] = assets.bytes
            }
            val samples = r.getInt("healthySamples"); val elapsed = r.getLong("elapsedMs"); val off = r.getLong("screenOffHealthyMs")
            require(samples in 0..185 && elapsed in 0..LocalServiceProtocol.MAX_SESSION_MS + 120_000 && off in 0..elapsed)
            val reason = r.getString("stopReason"); require(reason in setOf("running", "user", "session_limit", "before_start"))
            result["healthySamples"] = samples; result["elapsedMs"] = elapsed; result["screenOffHealthyMs"] = off; result["stopReason"] = reason
            val store = RealmStorageDiagnostic.store(context, identity)
            store.exclusive {
                val realm = store.inspectGeneration(r.getString("realmId"))
                val native = LocalAuthDiagnostic.nativeEvidence(File(realm.directory, "visits/$attempt-1/native-invocations.properties"), attempt, true, true)
                result["nativeEvidence"] = native
                result["retainedWorldLogs"] = WorldLogEvidence.export(File(realm.directory, "visits/$attempt-1"), attempt,
                    state in setOf("FAILED", "STOPPED", STOPPED), WorldLogEvidence.dictionary(context.assets.open(WorldLogEvidence.DICTIONARY)))
                val completed = state == STOPPED && r.opt("completed") == true && fields.all { r.opt(it) == true } &&
                    WorldSessionEvidence.diagnosticFields.none { r.opt(it) == true } &&
                    assetsMatch && store.active() == realm && native["qualified"] == true && native["healthySamples"] == samples && samples > 0 &&
                    reason in setOf("user", "session_limit")
                val qualified = completed && off >= LocalServiceProtocol.SCREEN_OFF_REQUIRED_MS
                result["sessionCompleted"] = completed; result["qualified"] = qualified
                result["databaseFallbackValidated"] = qualified; result["worldReady"] = qualified; result["screenOffQualified"] = qualified
            }
        }
        return result
    }
}
