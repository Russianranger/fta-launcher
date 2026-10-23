package com.russianranger.fta.runtime

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.russianranger.fta.Inputs
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

data class RealmDatabaseSession(val store: PersistentRealmStore, val generation: RealmGeneration,
    val attempt: String, val preparing: Boolean, val pass: Int = 0) {
    private val owner = ProcessObservation.self(Process.myPid())
    init { require(NativeInvocationJournal.validAttemptId(attempt) && pass in 0..2 && preparing == (pass == 0)) }
    fun visitDirectory(): File {
        val parent = File(generation.directory, "visits")
        if (!java.nio.file.Files.exists(parent.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) check(parent.mkdir())
        require(java.nio.file.Files.isDirectory(parent.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        return File(parent, "$attempt-$pass")
    }
    fun launching() = store.serverLaunching(owner)
    fun started(pid: Int) = store.serverStarted(owner, ProcessObservation.live(pid))
    fun stopped() = store.serverStopped(owner)
}

object RealmStorageDiagnostic {
    const val PASSED = "REALM_STORAGE_PASSED"
    internal fun identity(context: Context): Map<String, String> {
        val runtime = PackagedRuntime.inspect(context)
        val build = runtime.manifest.worldBuild ?: error("Original compiled updater is unavailable")
        val manifest = context.assets.open("fta-runtime-manifest.json").use { it.readBytes() }
        return mapOf("seedSha256" to Inputs.database.sha256,
            "providerManifestSha256" to MessageDigest.getInstance("SHA-256").digest(manifest).joinToString("") { "%02x".format(it.toInt() and 255) },
            "worldExecutableSha256" to build.worldserverSha256, "updaterLockSha256" to build.compiledUpdaterLockSha256,
            "sourceTreeSha256" to build.sourceTreeSha256)
    }
    internal fun store(context: Context, identity: Map<String, String>) = PersistentRealmStore(File(context.filesDir, "persistent-realm"), identity, ::syncDirectory)
    internal fun syncDirectory(dir: File) {
        val fd = if (android.os.Build.VERSION.SDK_INT >= 27)
            Os.open(dir.absolutePath, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
        else throw RuntimeUnavailable("Persistent realm storage requires Android 8.1 or newer")
        try {
            check(OsConstants.S_ISDIR(Os.fstat(fd).st_mode)); Os.fsync(fd)
        } finally { Os.close(fd) }
    }
    fun run(context: Context, cancelled: AtomicBoolean, status: (String, String) -> Unit,
            track: (DatabaseDiagnostic?) -> Unit): String {
        val attempt = File(context.filesDir, "runtime/diagnostic-attempt.id").readText().trim()
        require(NativeInvocationJournal.validAttemptId(attempt))
        val identity = identity(context)
        val store = store(context, identity)
        val receipt = JSONObject().put("formatVersion", 1).put("attemptId", attempt).put("identity", JSONObject(identity))
            .put("seedImportNeeded", false)
        RealmStorageProtocol.fields.forEach { receipt.put(it, false) }
        fun save() = RuntimeRecoveryService.atomic(File(context.filesDir, "runtime/realm-storage.json"), receipt.toString())
        save()
        val driver = object : RealmStorageProtocol.Driver {
            override fun checkActive() { check(!cancelled.get()) { "Realm preparation stopped" } }
            private fun execute(realm: RealmGeneration, pass: Int) {
                receipt.put("realmId", realm.id); save()
                val session = RealmDatabaseSession(store, realm, attempt, pass == 0, pass)
                val diagnostic = DatabaseDiagnostic(context, cancelled, status, includeWorldUpdater = pass == 0, realm = session)
                try { track(diagnostic); diagnostic.run() } finally { diagnostic.stopOwned(); track(null) }
                val path = if (pass == 0) File(realm.directory, "native-invocations.properties")
                    else File(session.visitDirectory(), "native-invocations.properties")
                check(RealmStorageNativeEvidence.read(path, attempt, pass == 0)["qualified"] == true) { "Realm native evidence is incomplete" }
            }
            override fun prepare(realm: RealmGeneration) {
                status("REALM_PREPARING", "Preparing a new persistent realm from the verified seed. An existing realm is never overwritten.")
                execute(realm, 0)
            }
            override fun verify(realm: RealmGeneration, pass: Int) {
                status("REALM_REOPEN", "Verifying persistent database restart $pass of 2. Seed SQL is not replayed.")
                execute(realm, pass)
            }
        }
        RealmStorageProtocol.run(store, driver) { key, value -> receipt.put(key, value); save() }
        return "Persistent realm storage passed: original seed and updater preparation, two reopen checks of the same database, all 24 unchanged metadata checks, and clean shutdown. Later checks reuse this realm without seed import. World startup and gameplay still need matching client data and service orchestration."
    }
    fun export(context: Context, attempt: String, state: String?): Map<String, Any> {
        val file = File(context.filesDir, "runtime/realm-storage.json")
        val receipt = runCatching {
            require(file.isFile && file.length() in 1..8192)
            JSONObject(file.readText()).also { require(it.opt("formatVersion") == 1 && it.opt("attemptId") == attempt) }
        }.getOrNull() ?: return mapOf("qualified" to false, "evidenceAvailable" to false)
        val fields = RealmStorageProtocol.fields + "seedImportNeeded"
        val flags = fields.associateWith { receipt.opt(it) == true }
        val result = mutableMapOf<String, Any>("attemptId" to attempt, "evidenceAvailable" to true,
            "qualified" to false, "checks" to flags, "worldReady" to false)
        runCatching {
            require(fields.all { receipt.opt(it) is Boolean })
            val bound = identity(context)
            require(bound.all { (key, value) -> receipt.getJSONObject("identity").opt(key) == value })
            result["identity"] = bound
            val store = store(context, bound)
            store.exclusive {
                val realm = store.inspectGeneration(receipt.getString("realmId"))
                val phases = linkedMapOf<String, Map<String, Any>>()
                if (flags.getValue("seedImportNeeded")) phases["prepare"] =
                    RealmStorageNativeEvidence.read(File(realm.directory, "native-invocations.properties"), attempt, true)
                for (pass in 1..2) phases["reopen$pass"] = RealmStorageNativeEvidence.read(
                    File(realm.directory, "visits/$attempt-$pass/native-invocations.properties"), attempt, false)
                result["phases"] = phases
                result["activeGenerationVerified"] = store.active() == realm
                result["qualified"] = state == PASSED && RealmStorageProtocol.fields.all { flags.getValue(it) } &&
                    phases.values.all { it["qualified"] == true } && store.active() == realm
            }
        }
        return result
    }
}

internal object RealmStorageNativeEvidence {
    fun read(file: File, attempt: String, preparation: Boolean): Map<String, Any> {
        val journal = NativeInvocationJournal.read(file, attempt) ?: return mapOf("available" to false, "qualified" to false)
        val (records, dropped) = journal
        val complete = dropped == 0 && records.isNotEmpty() && records.none { it.cancelled || it.timedOut || it.validationFailure != null ||
            it.stdoutBytes >= NativeInvocationJournal.MAX_LOG_BYTES || it.stderrBytes >= NativeInvocationJournal.MAX_LOG_BYTES }
        val databases = records.filter { it.role == "database-server" && it.readyObserved }
        val clean = databases.size == 1 && databases.single().let { it.exitCode == 0 && it.sqlShutdownRequested && it.cleanShutdownObserved }
        val qualified = complete && clean && if (preparation)
            WorldUpdaterEvidence.qualified(true, WorldUpdaterEvidence.PASSED, records, dropped)
        else records.all { it.outcome == "exited" && it.exitCode == 0 } &&
            records.count { it.role == "database-client" && it.stage == "REALM_METADATA" } == 3 &&
            records.count { it.role == "database-client" && it.stage == "REALM_VERIFY" } == 1
        return mapOf("available" to true, "qualified" to qualified, "recordCount" to records.size, "droppedCount" to dropped,
            "nativeInvocations" to records.map { value -> mapOf("id" to value.id, "role" to value.role, "stage" to value.stage,
                "outcome" to value.outcome, "exitCode" to value.exitCode, "terminationExitCode" to value.terminationExitCode,
                "durationMs" to value.durationMs, "timedOut" to value.timedOut, "cancelled" to value.cancelled,
                "validationFailure" to value.validationFailure, "databaseReadyObserved" to value.readyObserved,
                "sqlShutdownRequested" to value.sqlShutdownRequested, "cleanShutdownObserved" to value.cleanShutdownObserved,
                "stdoutBytes" to value.stdoutBytes, "stderrBytes" to value.stderrBytes) })
    }
}
