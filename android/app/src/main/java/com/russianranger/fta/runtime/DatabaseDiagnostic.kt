package com.russianranger.fta.runtime

import android.content.Context
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.util.AtomicFile
import com.russianranger.fta.Inputs
import org.json.JSONObject

/** A fresh socket-only database test; the optional auth probe imports only pinned auth seed files. */
class DatabaseDiagnostic(
    private val context: Context,
    private val cancelled: AtomicBoolean,
    private val status: (String, String) -> Unit,
    private val includeAuth: Boolean = false,
    private val includeFullSeed: Boolean = false,
    private val includeWorldUpdater: Boolean = false,
    private val includeLifecycle: Boolean = false,
    private val recoveryStore: RecoveryStore? = null,
    private val recoveryPhase: RecoveryPhase? = null,
    private val realm: RealmDatabaseSession? = null
) {
    private val owned = ConcurrentHashMap<Long, String>()
    private lateinit var root: File
    private lateinit var runtime: PackagedRuntime
    private var server = 0L
    private var clientOptions: File? = null
    private var worldInspectorQueue: File? = null
    private var realmLogDirectory: File? = null
    private var realmServerHandle = 0L
    private lateinit var socket: File
    private lateinit var characterSetsDirectory: File
    private var step = 0
    private var currentStage = "CHECKING"
    private var currentAttemptId: String? = null
    private var invocationJournal: NativeInvocationJournal? = null
    private val evidenceHandles = ConcurrentHashMap<Long, Int>()
    private val needsFullSeed = includeFullSeed || includeWorldUpdater
    private val deadline = System.nanoTime() + (if (includeWorldUpdater) 2400L else if (includeFullSeed) 1800L else if (includeAuth) 420L else 300L) * 1_000_000_000L

    fun run(): String {
        require(listOf(includeAuth, includeFullSeed, includeWorldUpdater, includeLifecycle, recoveryStore != null).count { it } <= 1) { "Select one diagnostic scope" }
        require((recoveryStore == null) == (recoveryPhase == null))
        if (realm != null) require(!includeAuth && !includeFullSeed && !includeLifecycle && recoveryStore == null && includeWorldUpdater == realm.preparing)
        runtime = PackagedRuntime.inspect(context)
        if (includeAuth) require("authserver" in runtime.manifest.capabilities && "auth-server" in runtime.executables)
        if (includeWorldUpdater) require("worldserver" in runtime.manifest.capabilities && "world-server" in runtime.executables &&
            runtime.manifest.files.any { it.path == WorldUpdaterMetadata.PATH }) { "The compiled world updater payload is unavailable" }
        require(runtime.manifest.bootstrap != null) { "A pinned bootstrap recipe is required" }
        if (recoveryPhase == RecoveryPhase.RESUME) return runRecoveryResume(recoveryStore!!)
        if (realm != null && !realm.preparing) return runRealmReopen(realm)
        val parent = if (realm == null) File(context.filesDir, "runtime/database-diagnostics").apply { check(mkdirs() || isDirectory) }
            else realm.generation.directory.parentFile!!
        // At most two previous diagnostic directories are retained. These never hold user saves.
        if (realm == null) parent.listFiles()?.filter { it.isDirectory && it.name.startsWith("g-") }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.deleteRecursively() }
        check(parent.usableSpace >= (if (needsFullSeed) 3L * 1024 * 1024 * 1024 else 512L * 1024 * 1024)) { "Insufficient free private storage for the disposable diagnostic" }
        root = if (realm == null) File(parent, "g-${UUID.randomUUID()}").apply { check(mkdir()) }
            else realm.generation.directory.also { realm.store.requireFresh(realm.generation) }
        val attemptFile = File(context.filesDir, "runtime/diagnostic-attempt.id")
        val attemptId = attemptFile.takeIf { it.isFile && it.length() <= 64 }?.readText()?.trim()
        require(attemptId != null && NativeInvocationJournal.validAttemptId(attemptId)) { "Current diagnostic attempt identity is unavailable" }
        currentAttemptId = attemptId
        invocationJournal = NativeInvocationJournal(File(root, "native-invocations.properties"), attemptId)
        recoveryStore?.write(RecoveryWorkerRecord(recoveryPhase!!, rootName = root.name))
        checkCancelled()
        setStage("STAGING", "Verifying packaged database bootstrap and system data.")
        val bootstrap = runtime.stageData(context, File(root, "payload"), ::checkCancelled)
        val provider = File(root, "payload/data/provider")
        // The provider was relocated from Termux's share/mariadb layout. Its
        // message-file override does not redirect the separate charset loader.
        characterSetsDirectory = File(provider, "share/mysql/charsets")
        check(characterSetsDirectory.isDirectory &&
            listOf("Index.xml", "ascii.xml").all { File(characterSetsDirectory, it).isFile }) {
            "Pinned database character-set data is unavailable"
        }
        val datadir = File(root, "database").apply { check(mkdir()) }
        val runDirectory = File(context.filesDir, "run").apply { check(mkdirs() || isDirectory) }
        socket = File(runDirectory, "d.sock")
        if (socket.exists()) check(socket.delete()) { "Stale private diagnostic socket could not be removed" }
        // Linux sockaddr_un paths are limited to 108 bytes, including termination.
        check(socket.absolutePath.toByteArray().size < 104) { "Private database socket path is too long" }
        val pluginDirectory = File(root, "plugin").apply { check(mkdir()) }
        val options = databaseOptions(provider, datadir, pluginDirectory)
        try {
            setStage("BOOTSTRAPPING", "Initializing a fresh disposable database. Existing saves are untouched.")
            runTool("database-server", options + listOf("--bootstrap", "--log-warnings=0", "--enforce-storage-engine="), bootstrap, 180_000)
            File(datadir, "mariadb_upgrade_info").writeText("${runtime.manifest.providerVersion}-MariaDB")
            checkCancelled()
            setStage("STARTING", "Starting socket-only MariaDB with bounded memory settings.")
            server = launch("database-server", options, null, "server")
            val deadline = System.nanoTime() + 45_000_000_000L
            var ready = false
            while (System.nanoTime() < deadline) {
                checkCancelled()
                check(waitTracked(server, 0) == Int.MIN_VALUE) { "Database exited before socket readiness" }
                if (socket.exists()) {
                    val probe = runCatching { client("SELECT 1;", 2_000) }
                    if (probe.isSuccess && probe.getOrThrow().trim() == "1") { ready = true; break }
                }
                Thread.sleep(150)
            }
            check(ready) { "Database socket did not become ready" }
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "database_ready") }
            setStage("CHECKING", "Testing local credentials, transactions, system tables, Unicode and ASCII character sets.")
            val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            client("ALTER USER 'root'@'localhost' IDENTIFIED BY '$secret'; FLUSH PRIVILEGES;", 10_000)
            clientOptions = File(root, "client.cnf").apply {
                writeText("[client]\nuser=root\npassword=$secret\nprotocol=socket\nsocket=${socket.absolutePath}\n")
                setReadable(false, false); setWritable(false, false)
                check(setReadable(true, true) && setWritable(true, true))
            }
            val result = client("""
                SELECT CONCAT('FTA_VERSION=',VERSION());
                SELECT CONCAT('FTA_SYSTEM_TABLES=',COUNT(*)) FROM information_schema.tables WHERE table_schema='mysql';
                SELECT CONCAT('FTA_COLLATION=',COUNT(*)) FROM information_schema.collations WHERE collation_name='utf8mb4_0900_ai_ci';
                CREATE DATABASE fta_diagnostic;
                CREATE TABLE fta_diagnostic.probe(id INT PRIMARY KEY, text_value VARCHAR(32),
                    ascii_value VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin)
                    ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
                START TRANSACTION;
                INSERT INTO fta_diagnostic.probe VALUES (1, CONVERT(0xF09F9495 USING utf8mb4), 0x4654412D4153434949);
                SELECT CONCAT('FTA_UNICODE_ROWS=',COUNT(*)) FROM fta_diagnostic.probe
                    WHERE text_value=CONVERT(0xF09F9495 USING utf8mb4);
                SELECT CONCAT('FTA_ASCII_ROWS=',COUNT(*)) FROM fta_diagnostic.probe
                    WHERE HEX(ascii_value)='4654412D4153434949'
                    AND CHARSET(ascii_value)='ascii' AND COLLATION(ascii_value)='ascii_bin';
                ROLLBACK;
                SELECT CONCAT('FTA_ROLLBACK_ROWS=',COUNT(*)) FROM fta_diagnostic.probe;
                DROP DATABASE fta_diagnostic;
            """.trimIndent(), 15_000)
            val values = result.lineSequence().mapNotNull { line ->
                val at = line.indexOf('='); if (at <= 0) null else line.substring(0, at) to line.substring(at + 1).trim()
            }.toMap()
            check(values["FTA_VERSION"]?.startsWith(runtime.manifest.providerVersion) == true)
            check((values["FTA_SYSTEM_TABLES"]?.toIntOrNull() ?: 0) > 0)
            // Actual DDL/Unicode insertion above is the compatibility proof; an alias
            // need not appear as its own row in INFORMATION_SCHEMA on every engine.
            check(values["FTA_COLLATION"]?.toIntOrNull() != null)
            check(values["FTA_UNICODE_ROWS"] == "1") { "Unicode collation round trip failed" }
            check(values["FTA_ASCII_ROWS"] == "1") { "ASCII character-set round trip failed" }
            check(values["FTA_ROLLBACK_ROWS"] == "0") { "InnoDB rollback probe failed" }
            if (recoveryStore != null) runRecoveryHold(recoveryStore, recoveryPhase!!)
            if (includeLifecycle) {
                runLifecycleDiagnostic(options)
                return "Disposable database lifecycle checks passed: transaction cancellation and rollback, clean restart, SIGKILL recovery with committed data retained and an interrupted transaction rolled back, and final SQL shutdown. Screen-off observations are in the exported report. Android service-process death, realm storage and gameplay remain separate gates."
            }
            if (includeAuth) runAuthUpdaterDiagnostic()
            if (needsFullSeed) runFullSeedDiagnostic()
            if (includeWorldUpdater) runWorldUpdaterDiagnostic()
            if (realm != null) {
                setStage("REALM_PREPARING", "Binding the new persistent realm to its launcher identity.")
                RealmStorageCanary.create(realm.generation.id) { client(it, 10_000) }
                client("DROP USER 'fta_world_diag'@'localhost';", 5_000)
            }
            setStage("STOPPING", "Stopping the diagnostic database with SQL SHUTDOWN.")
            client("SHUTDOWN;", 10_000)
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "sql_shutdown_requested") }
            val stopped = waitTracked(server, 15_000)
            check(stopped != Int.MIN_VALUE && stopped == 0) { "Database did not shut down cleanly" }
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "clean_shutdown") }
            owned.remove(server)
            evidenceHandles.remove(server)
            server = 0
            return if (includeWorldUpdater)
                "The original compiled auth, character and world updaters completed against a fresh verified seed. Exact resulting receipts and schema metadata matched; original normal startup stopped at missing starting-area maps, then MariaDB shut down cleanly. This qualifies only the disposable compiled updater gate. World startup, listeners and gameplay remain unqualified."
                else if (includeFullSeed)
                "All 563 original tables and the world trigger imported unchanged into a fresh disposable database; object inventories and original updater receipt baselines matched, then MariaDB shut down cleanly. Runtime SQL was not applied. Compiled world/character updaters, worldserver and game login remain unqualified."
                else if (includeAuth)
                "Original ARM64 authserver dry run and updater passed against the unchanged 27-table auth seed, then the disposable database shut down cleanly. No auth listener was opened. Full seed, worldserver and game login remain unqualified."
                else "MariaDB ${runtime.manifest.providerVersion}: fresh bootstrap, private credentials, socket health, system tables, Unicode/ASCII character sets, InnoDB rollback and clean shutdown passed. Seed compatibility and AzerothCore remain unqualified."
        } finally {
            // SQL shutdown is preferred. The JNI helper owns process groups and enforces deadlines.
            if (server != 0L && !cancelled.get()) runCatching {
                client("SHUTDOWN;", 3_000)
                evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "sql_shutdown_requested") }
                if (waitTracked(server, 3_000) == 0) {
                    evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "clean_shutdown") }
                }
            }
            stopOwned()
            if (realm == null) clientOptions?.delete()
            if (::socket.isInitialized) socket.delete()
        }
    }

    private fun databaseOptions(provider: File, datadir: File, pluginDirectory: File) = listOf(
            "--no-defaults", "--basedir=${provider.absolutePath}", "--datadir=${datadir.absolutePath}",
            "--plugin-dir=${pluginDirectory.absolutePath}",
            "--lc-messages-dir=${File(provider, "share/mysql").absolutePath}",
            "--character-sets-dir=${characterSetsDirectory.absolutePath}",
            "--socket=${socket.absolutePath}", "--pid-file=${File(root, "db.pid").absolutePath}",
            "--skip-networking", "--skip-name-resolve", "--performance-schema=OFF",
            "--innodb-buffer-pool-size=64M", "--innodb-log-file-size=16M", "--aria-pagecache-buffer-size=16M", "--key-buffer-size=8M",
            "--max-connections=8", "--thread-cache-size=2", "--tmp-table-size=8M", "--max-heap-table-size=8M",
            "--max-allowed-packet=${if (needsFullSeed) "256M" else "16M"}"
        )

    private fun checkCancelled() {
        check(!cancelled.get()) { "Database diagnostic cancelled" }
        check(System.nanoTime() < deadline) { "Database diagnostic exceeded its total deadline" }
    }

    private fun runRealmReopen(session: RealmDatabaseSession): String {
        root = session.generation.directory
        val datadir = File(root, "database")
        require(java.nio.file.Files.isDirectory(datadir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val provider = File(root, "payload/data/provider")
        runtime.manifest.files.filter { it.role == "data" }.forEach { entry ->
            checkCancelled(); RuntimeManifest.verifyFile(File(root, "payload/${entry.path}"), entry)
        }
        characterSetsDirectory = File(provider, "share/mysql/charsets")
        socket = File(context.filesDir, "run/d.sock")
        val credentials = File(root, "client.cnf")
        require(java.nio.file.Files.isRegularFile(credentials.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && credentials.length() <= 1024)
        val lines = credentials.readLines()
        require(lines.size == 5 && lines[0] == "[client]" && lines[1] == "user=root" &&
            lines[2].matches(Regex("password=[0-9a-f]{48}")) && lines[3] == "protocol=socket" && lines[4] == "socket=${socket.absolutePath}")
        clientOptions = credentials
        currentAttemptId = session.attempt
        realmLogDirectory = session.visitDirectory().apply { check(mkdirs()) }
        invocationJournal = NativeInvocationJournal(File(realmLogDirectory, "native-invocations.properties"), session.attempt)
        try {
            if (socket.exists()) check(socket.delete())
            setStage("REALM_REOPEN", "Opening the existing realm database without importing seed SQL.")
            server = launch("database-server", databaseOptions(provider, datadir, File(root, "plugin")), null, "server")
            val readyEnd = System.nanoTime() + 45_000_000_000L
            while (true) {
                checkCancelled(); check(waitTracked(server, 0) == Int.MIN_VALUE)
                if (socket.exists() && runCatching { client("SELECT 1;", 2_000).trim() == "1" }.getOrDefault(false)) break
                check(System.nanoTime() < readyEnd); Thread.sleep(150)
            }
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "database_ready") }
            setStage("REALM_VERIFY", "Checking the retained realm identity and original database expectations.")
            RealmStorageCanary.verify(session.generation.id) { client(it, 10_000) }
            // Read-only exact schema/receipt checks. No updater, seed or user-row write runs here.
            setStage("REALM_METADATA", "Checking all 24 unchanged schema and updater receipt expectations.")
            val lock = WorldUpdaterMetadata.load(context)
            lock.schemas.forEach { schema ->
                check(WorldUpdaterEvidence.parseValidation(client(WorldUpdaterEvidence.schemaSql(schema, lock.includeDirectories), 30_000), schema).values.all { it })
            }
            setStage("STOPPING", "Stopping the retained realm database with SQL SHUTDOWN.")
            client("SHUTDOWN;", 10_000)
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "sql_shutdown_requested") }
            check(waitTracked(server, 15_000) == 0)
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "clean_shutdown") }
            owned.remove(server); evidenceHandles.remove(server); server = 0
            return "Persistent realm identity, all 24 metadata checks and clean shutdown passed."
        } finally {
            if (server != 0L && !cancelled.get()) runCatching { client("SHUTDOWN;", 3_000); waitTracked(server, 3_000) }
            stopOwned(); socket.delete()
            // The retained credentials and datadir are never deleted on ordinary stop/failure.
        }
    }

    private fun runRecoveryHold(store: RecoveryStore, phase: RecoveryPhase): Nothing {
        require(phase in setOf(RecoveryPhase.CANCEL, RecoveryPhase.CRASH) && currentAttemptId == store.attemptId)
        setStage("RECOVERY_HOLD", "Preparing the disposable service recovery checkpoint.")
        RecoveryCanary.create(store.attemptId) { client(it, 10_000) }
        val script = File(root, "recovery-transaction.sql").apply { writeText(DatabaseLifecycleProbe.TRANSACTION) }
        val handle = launch("database-client", listOf("--defaults-file=${clientOptions!!.absolutePath}",
            "--character-sets-dir=${characterSetsDirectory.absolutePath}", "--batch", "--skip-column-names", "--connect-timeout=2"),
            script, "tool-${++step}", 300_000)
        val readyEnd = System.nanoTime() + 10_000_000_000L
        while (true) {
            checkCancelled()
            check(waitTracked(handle, 0) == Int.MIN_VALUE)
            if (client(DatabaseLifecycleProbe.ACTIVE, 2_000).trim() == "1") break
            check(System.nanoTime() < readyEnd); Thread.sleep(100)
        }
        val owner = ProcessObservation.self(android.os.Process.myPid())
        val children = listOf(server, handle).map { ProcessObservation.live(NativeProcessBridge.processId(it)) }
        check(children.all { it.parentPid == owner.pid } && children.map { it.pid }.distinct().size == 2)
        store.write(RecoveryWorkerRecord(phase, "READY", root.name, owner, children))
        // Controller either uses the real Stop PendingIntent, or asks this nonexported
        // service to kill only its own process. Losing the controller times out here.
        while (true) {
            checkCancelled()
            check(waitTracked(server, 0) == Int.MIN_VALUE && waitTracked(handle, 0) == Int.MIN_VALUE)
            Thread.sleep(100)
        }
    }

    private fun runRecoveryResume(store: RecoveryStore): String {
        val previous = store.read(RecoveryPhase.CRASH) ?: error("Crash checkpoint is absent")
        require(RuntimeRecoveryProtocol.ready(previous))
        require(ProcessObservation.ended(previous.owner!!) && previous.children.all { ProcessObservation.ended(it) })
        root = store.verifiedRoot(File(context.filesDir, "runtime/database-diagnostics"), previous)
        val datadir = File(root, "database")
        require(java.nio.file.Files.isDirectory(datadir.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        val provider = File(root, "payload/data/provider")
        runtime.manifest.files.filter { it.role == "data" }.forEach { entry ->
            checkCancelled(); RuntimeManifest.verifyFile(File(root, "payload/${entry.path}"), entry)
        }
        characterSetsDirectory = File(provider, "share/mysql/charsets")
        socket = File(context.filesDir, "run/d.sock")
        val credentials = File(root, "client.cnf")
        require(java.nio.file.Files.isRegularFile(credentials.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && credentials.length() <= 1024)
        val lines = credentials.readLines()
        require(lines.size == 5 && lines[0] == "[client]" && lines[1] == "user=root" &&
            lines[2].matches(Regex("password=[0-9a-f]{48}")) && lines[3] == "protocol=socket" && lines[4] == "socket=${socket.absolutePath}")
        clientOptions = credentials
        currentAttemptId = store.attemptId
        invocationJournal = NativeInvocationJournal(File(root, "native-recovery-resume.properties"), store.attemptId)
        // Reusing the datadir must not overwrite the killed worker's native logs.
        check(File(root, "recovery-logs").mkdir())
        val owner = ProcessObservation.self(android.os.Process.myPid())
        require(!previous.owner.sameProcess(owner))
        store.write(RecoveryWorkerRecord(RecoveryPhase.RESUME, rootName = root.name, owner = owner))
        try {
            if (socket.exists()) check(socket.delete())
            setStage("RECOVERY_RESUME", "Reopening the disposable database after runtime service death.")
            server = launch("database-server", databaseOptions(provider, datadir, File(root, "plugin")), null, "server")
            val readyEnd = System.nanoTime() + 45_000_000_000L
            while (true) {
                checkCancelled(); check(waitTracked(server, 0) == Int.MIN_VALUE)
                if (socket.exists() && runCatching { client("SELECT 1;", 2_000).trim() == "1" }.getOrDefault(false)) break
                check(System.nanoTime() < readyEnd); Thread.sleep(150)
            }
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "database_ready") }
            setStage("RECOVERY_VALIDATE", "Checking recovered commit, interrupted transaction and clean shutdown.")
            RecoveryCanary.verify(store.attemptId) { client(it, 10_000) }
            client("SHUTDOWN;", 10_000)
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "sql_shutdown_requested") }
            check(waitTracked(server, 15_000) == 0)
            evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "clean_shutdown") }
            owned.remove(server); evidenceHandles.remove(server); server = 0
            store.state(RecoveryPhase.RESUME, "RECOVERED")
            return "Disposable database recovered after runtime service death and shut down cleanly."
        } finally {
            stopOwned(); credentials.delete(); socket.delete()
        }
    }

    private fun runLifecycleDiagnostic(options: List<String>) {
        val flags = DatabaseLifecycleProbe.fields.associateWith { false }.toMutableMap()
        fun receipt() {
            val info = JSONObject(flags as Map<*, *>).put("formatVersion", 1)
                .put("attemptId", currentAttemptId).put("seedImported", false)
                .put("serviceProcessDeathQualified", false)
            val atomic = AtomicFile(File(context.filesDir, "runtime/database-lifecycle.json"))
            val output = atomic.startWrite()
            try { output.write(info.toString().toByteArray()); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
        receipt()
        val driver = object : DatabaseLifecycleProbe.Driver {
            override fun query(sql: String) = client(sql, 10_000)
            override fun beginClient(sql: String): Long {
                val script = File(root, "lifecycle-transaction.sql").apply { writeText(sql) }
                return launch("database-client", listOf("--defaults-file=${clientOptions!!.absolutePath}",
                    "--character-sets-dir=${characterSetsDirectory.absolutePath}", "--batch", "--skip-column-names", "--connect-timeout=2"),
                    script, "tool-${++step}", 300_000)
            }
            override fun clientRunning(handle: Long) = waitTracked(handle, 0) == Int.MIN_VALUE
            override fun cancelClient(handle: Long): Int {
                val exit = NativeProcessBridge.stop(handle, 1_000)
                check(exit != Int.MIN_VALUE) { "Lifecycle client was not reaped" }
                finishEvidence(handle, "cancelled", exit)
                owned.remove(handle); evidenceHandles.remove(handle)
                return exit
            }
            override fun stopDatabase(force: Boolean): Int {
                val handle = server
                check(handle != 0L)
                val exit = if (force) {
                    NativeProcessBridge.stop(handle, 0).also { finishEvidence(handle, "stopped", it.takeIf { value -> value != Int.MIN_VALUE }) }
                } else {
                    client("SHUTDOWN;", 10_000)
                    evidenceHandles[handle]?.let { invocationJournal?.checkpoint(it, "sql_shutdown_requested") }
                    waitTracked(handle, 15_000).also {
                        if (it == 0) evidenceHandles[handle]?.let { id -> invocationJournal?.checkpoint(id, "clean_shutdown") }
                    }
                }
                check(exit != Int.MIN_VALUE) { "Lifecycle database was not reaped" }
                owned.remove(handle); evidenceHandles.remove(handle); server = 0
                return exit
            }
            override fun restartDatabase() {
                check(server == 0L) { "Previous database is still owned" }
                // Only after observed termination may the same disposable datadir/socket reopen.
                if (socket.exists()) check(socket.delete())
                server = launch("database-server", options, null, "tool-${++step}")
                val readyDeadline = System.nanoTime() + 45_000_000_000L
                while (true) {
                    checkCancelled()
                    check(waitTracked(server, 0) == Int.MIN_VALUE) { "Database exited during lifecycle restart" }
                    if (socket.exists() && runCatching { client("SELECT 1;", 2_000).trim() == "1" }.getOrDefault(false)) break
                    check(System.nanoTime() < readyDeadline) { "Database restart did not become ready" }
                    Thread.sleep(150)
                }
                evidenceHandles[server]?.let { invocationJournal?.checkpoint(it, "database_ready") }
            }
            override fun stage(value: String) = setStage(value, when (value) {
                "LIFECYCLE_SCREEN_OFF" -> "Screen-off check is running for 90 seconds. Lock the Thor now, wait two minutes, then reopen and export the report."
                "LIFECYCLE_CRASH" -> "Testing forced-stop recovery of the disposable database."
                "LIFECYCLE_CANCEL" -> "Cancelling a live disposable transaction and checking rollback."
                else -> "Testing disposable database durability and clean restart."
            })
            override fun checkActive() = checkCancelled()
            override fun pause(ms: Long) { checkCancelled(); Thread.sleep(ms); checkCancelled() }
            override fun monotonicMs() = System.nanoTime() / 1_000_000
            override fun screenOff() = !context.getSystemService(android.os.PowerManager::class.java).isInteractive
        }
        DatabaseLifecycleProbe.run(driver) { flags[it] = true; receipt() }
    }

    private fun runAuthUpdaterDiagnostic() {
        check("authserver" in runtime.manifest.capabilities && "auth-server" in runtime.executables) { "Packaged authserver is unavailable" }
        setStage("AUTH_INPUT", "Verifying the exact private database ZIP and auth-only file identities.")
        val info = JSONObject().put("formatVersion", 1).put("attemptId", currentAttemptId)
            .put("archiveSha256", Inputs.database.sha256)
            .put("plannedTableCount", 27).put("importedTableCount", 0).put("runtimeAuthUpdates", 0)
            .put("authTcpListener", false).put("dryRunPassed", false)
            .put("startedAtEpochMs", System.currentTimeMillis())
            .put("authExecutableSha256", runtime.manifest.files.single { it.role == "auth-server" }.sha256)
        fun receipt() {
            val destination = AtomicFile(File(context.filesDir, "runtime/auth-diagnostic.json"))
            val output = destination.startWrite()
            try { output.write(info.toString().toByteArray()); destination.finishWrite(output) }
            catch (failure: Exception) { destination.failWrite(output); throw failure }
        }
        // Reset attempt evidence before input verification so a failed retry cannot
        // retain a previous pass as the current auth diagnostic result.
        receipt()
        val lock = AuthSeedMetadata.load(context)
        val archive = VerifiedInputReader.resolve(File(context.filesDir, "imports"), Inputs.database)
        val staged = AuthSeedStore().stage(archive, lock, File(root, "auth-seed-stage"))
        info.put("runtimeAuthUpdates", 1)
        receipt()
        check(client("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='acore_auth';", 5_000).trim() == "0") {
            "Auth diagnostic requires an absent disposable auth schema"
        }
        client("CREATE DATABASE acore_auth CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", 5_000)
        staged.tableFiles.forEachIndexed { index, file ->
            checkCancelled()
            setStage("AUTH_IMPORT", "Importing verified auth table ${index + 1} of 27 into the disposable database.")
            runClientScript(file, 10_000, "acore_auth")
            info.put("importedTableCount", index + 1)
            receipt()
        }
        check(client("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='acore_auth';", 5_000).trim() == "27")
        // Inspect only eligibility booleans. The original seed and updater remain unchanged.
        setStage("AUTH_INPUT", "Checking realm eligibility without exporting realm details.")
        RealmEligibilityEvidence.parse(client(RealmEligibilityEvidence.SQL, 5_000)).forEach { (field, value) ->
            info.put(field, value)
        }
        receipt()
        val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        client("CREATE USER 'fta_auth_diag'@'localhost' IDENTIFIED BY '$secret'; GRANT ALL PRIVILEGES ON acore_auth.* TO 'fta_auth_diag'@'localhost';", 5_000)
        val temp = File(root, "auth-private-temp").apply { check(mkdir()) }
        val config = File(root, "authserver-diagnostic.conf")
        try {
            config.writeText(AuthDiagnosticConfig.render(socket, runtime.executables.getValue("database-client"),
                staged.runtimeDirectory, temp, "fta_auth_diag", secret))
            config.setReadable(false, false); config.setWritable(false, false)
            check(config.setReadable(true, true) && config.setWritable(true, true))
            setStage("AUTH_VERSION", "Checking the packaged original authserver build identity.")
            if (!runTool("auth-server", listOf("--version"), null, 10_000).contains("AzerothCore")) {
                invocationJournal?.validationFailure("auth-server", "AUTH_VERSION", "version_marker_missing")
                error("Original auth version marker is missing")
            }
            setStage("AUTH_UPDATER", "Running the original auth database updater and dry run without a network listener.")
            val result = runTool("auth-server", listOf("--config", config.absolutePath, "--dry-run"), null, 180_000)
            if (!result.contains("Dry run completed, terminating.")) {
                invocationJournal?.validationFailure("auth-server", "AUTH_UPDATER", "completion_marker_missing")
                error("Original auth dry-run completion marker is missing")
            }
            check(client("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='acore_auth';", 5_000).trim() == "27")
            info.put("dryRunPassed", true).put("completedAtEpochMs", System.currentTimeMillis())
            receipt()
        } finally {
            config.delete()
            // Original DBUpdater writes this private temporary client options file.
            File(temp, "mysql_ac.conf").delete()
        }
    }

    private fun runFullSeedDiagnostic() {
        setStage("SEED_INPUT", "Verifying the full private database ZIP for a fresh disposable import.")
        val info = JSONObject().put("formatVersion", 1).put("attemptId", currentAttemptId)
            .put("archiveSha256", Inputs.database.sha256)
            .put("plannedTableCount", 563).put("importedTableCount", 0)
            .put("plannedTriggerCount", 1).put("importedTriggerCount", 0)
            .put("fullSeedImported", false).put("validationPassed", false)
            .put("runtimeSqlApplied", false).put("startedAtEpochMs", System.currentTimeMillis())
        fun receipt() {
            val file = AtomicFile(File(context.filesDir, "runtime/full-seed-diagnostic.json"))
            val output = file.startWrite()
            try { output.write(info.toString().toByteArray()); file.finishWrite(output) }
            catch (failure: Exception) { file.failWrite(output); throw failure }
        }
        receipt()
        val lock = FullSeedMetadata.load(context)
        val archive = VerifiedInputReader.resolve(File(context.filesDir, "imports"), Inputs.database, ::checkCancelled)
        val stageDirectory = File(root, "full-seed-stage")
        try {
            setStage("SEED_STAGING", "Staging the unchanged full seed with per-file identity checks.")
            val staged = FullSeedStore().stage(archive, lock, stageDirectory, ::checkCancelled) { done, total ->
                setStage("SEED_STAGING", "Verifying full seed file $done of $total.")
            }
            checkCancelled()
            val names = FullSeedStore.SCHEMAS.joinToString(",") { "'$it'" }
            // Even empty existing schemas are refused. The datadir is owned by this new attempt.
            check(client("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name IN ($names);", 5_000).trim() == "0") {
                "Full seed requires all three schemas to be absent"
            }
            FullSeedStore.SCHEMAS.forEach { schema ->
                client("CREATE DATABASE `$schema` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;", 5_000)
            }
            var tables = 0
            var triggers = 0
            staged.entries.forEachIndexed { index, (entry, file) ->
                checkCancelled()
                setStage("SEED_IMPORT", "Importing original seed file ${index + 1} of 564 into the disposable database.")
                runClientScript(file, 300_000, entry.schema)
                if (entry.kind == "table") tables++ else triggers++
                info.put("importedTableCount", tables).put("importedTriggerCount", triggers)
                receipt()
            }
            check(tables == 563 && triggers == 1)
            setStage("SEED_VALIDATION", "Validating exact schema objects and unchanged updater receipt baselines.")
            val schemaResults = JSONObject()
            lock.schemas.forEach { schema ->
                checkCancelled()
                val result = client(FullSeedEvidence.inventorySql(schema, lock.files) + "\n" +
                    FullSeedEvidence.receiptSql(schema), 15_000)
                val flags = FullSeedEvidence.parseValidation(result, schema)
                schemaResults.put(schema.name, JSONObject(flags))
                info.put("schemas", schemaResults)
                receipt()
                check(flags.values.all { it }) { "Full seed metadata or receipt baseline differs" }
            }
            info.put("fullSeedImported", true).put("validationPassed", true)
                .put("completedAtEpochMs", System.currentTimeMillis())
            receipt()
        } finally {
            // The imported ZIP remains in its verified input generation. Expanded SQL is reproducible.
            stageDirectory.deleteRecursively()
        }
    }

    private fun runWorldUpdaterDiagnostic() {
        setStage("WORLD_INPUT", "Verifying the signed source policy and complete original runtime SQL inventory.")
        val lock = WorldUpdaterMetadata.load(context)
        val columnSchema = lock.schemas.single { it.name == "acore_world" }
        val seedColumnTables = FullSeedMetadata.load(context).files
            .filter { it.schema == "acore_world" && it.kind == "table" }.map { it.name }.sorted()
        var columnBefore: WorldColumnSnapshot? = null
        var columnAfter: WorldColumnSnapshot? = null
        val build = runtime.manifest.worldBuild ?: error("Compiled world build metadata is unavailable")
        check(build.portProfile == lock.sourceProfile && build.sourceTreeSha256 == lock.sourceTreeSha256 &&
            build.sourceArchiveSha256 == lock.sourceArchiveSha256) { "Compiled world build and source policy differ" }
        val info = JSONObject().put("formatVersion", 1).put("attemptId", currentAttemptId)
            .put("archiveSha256", lock.archiveSha256).put("sourceArchiveSha256", lock.sourceArchiveSha256)
            .put("sourceProfile", lock.sourceProfile).put("sourceTreeSha256", lock.sourceTreeSha256)
            .put("worldExecutableSha256", runtime.manifest.files.single { it.role == "world-server" }.sha256)
            .put("worldUpdaterLockSha256", runtime.manifest.files.single { it.path == WorldUpdaterMetadata.PATH }.sha256)
            .put("plannedRuntimeSqlFiles", 236).put("stagedRuntimeSqlFiles", 0).put("moduleCount", 52).put("moduleSqlCount", 0)
            .put("freshSeedBaselinePassed", true).put("compiledUpdaterPassed", false).put("runtimeSqlAttempted", false)
            .put("runtimeSqlCompletionQualified", false)
            .put("worldStartupQualified", false).put("worldTcpListener", false)
            .put("startedAtEpochMs", System.currentTimeMillis())
        fun receipt() {
            val atomic = AtomicFile(File(context.filesDir, "runtime/world-updater-diagnostic.json"))
            val output = atomic.startWrite()
            try { output.write(info.toString().toByteArray()); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
        fun columnReceipt() {
            val value = WorldColumnEvidence.receiptMap(currentAttemptId!!, build.worldserverSha256,
                build.compiledUpdaterLockSha256, build.sourceTreeSha256, columnSchema, seedColumnTables, columnBefore, columnAfter)
            val bytes = JSONObject(value).toString().toByteArray()
            check(bytes.size <= WorldColumnEvidence.MAX_RECEIPT_BYTES) { "Column diagnostic receipt exceeds its bound" }
            val atomic = AtomicFile(File(context.filesDir, "runtime/world-column-diagnostic.json"))
            val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) }
            catch (failure: Exception) { atomic.failWrite(output); throw failure }
        }
        receipt()
        val archive = VerifiedInputReader.resolve(File(context.filesDir, "imports"), Inputs.database, ::checkCancelled)
        val stage = File(root, "world-runtime-sql")
        val config = File(root, "worldserver-diagnostic.conf")
        val temp = File(root, "world-private-temp").apply { check(mkdir()) }
        try {
            setStage("WORLD_STAGING", "Staging all 236 unchanged runtime SQL files in their original updater paths.")
            val sourceDirectory = WorldUpdaterStore().stage(archive, lock, stage, ::checkCancelled)
            info.put("stagedRuntimeSqlFiles", 236); receipt()
            lock.schemas.forEach { schema ->
                check(FullSeedEvidence.parseFlags(client(WorldUpdaterEvidence.includeSql(schema.name, lock.includeDirectories), 5_000),
                    listOf("includeInventoryMatches")).values.all { it }) { "Original updater include policy differs" }
            }
            // CONF_DIR=configs is compiled into this reviewed Android world profile.
            // Only source-bound original module defaults are copied; no live config is opened.
            val moduleConfigs = runtime.manifest.files.filter { it.path.startsWith("data/world-config/modules/") }
            check(moduleConfigs.size == 49 && moduleConfigs.all {
                it.path.removePrefix("data/world-config/modules/").matches(Regex("[A-Za-z0-9_.+-]{1,120}\\.conf"))
            }) { "Complete original module configuration defaults are unavailable" }
            val configDirectory = File(root, "configs/modules").apply { check(mkdirs()) }
            moduleConfigs.forEach { entry ->
                checkCancelled()
                File(root, "payload/${entry.path}").copyTo(File(configDirectory, entry.path.substringAfterLast('/')))
            }
            val emptyData = File(root, "world-empty-data").apply { check(mkdir()) }
            check(emptyData.listFiles()?.isEmpty() == true)
            worldInspectorQueue = WorldUpdaterEnvironment.createQueue(root)
            val secret = ByteArray(24).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            client("CREATE USER 'fta_world_diag'@'localhost' IDENTIFIED BY '$secret';" +
                FullSeedStore.SCHEMAS.joinToString(" ") { "GRANT ALL PRIVILEGES ON `$it`.* TO 'fta_world_diag'@'localhost';" }, 5_000)
            val orderedLog = File(root, "world-updater.log")
            config.writeText(WorldUpdaterConfig.render(socket, runtime.executables.getValue("database-client"), sourceDirectory,
                emptyData, temp, orderedLog, "fta_world_diag", secret))
            config.setReadable(false, false); config.setWritable(false, false)
            check(config.setReadable(true, true) && config.setWritable(true, true))
            setStage("WORLD_METADATA_BEFORE", "Recording bounded world catalog hashes before the compiled updater.")
            columnBefore = WorldColumnEvidence.parseSnapshot(client(WorldColumnEvidence.schemaSql(columnSchema), 30_000),
                columnSchema, seedColumnTables)
            columnReceipt()
            setStage("WORLD_UPDATER", "Running the compiled original three-database updater through normal startup to its missing-map stop.")
            info.put("runtimeSqlAttempted", true); receipt()
            val boundary = runWorldBoundary(config, orderedLog, lock.updatePhases)
            boundary.forEach { (field, value) -> info.put(field, value) }
            info.put("runtimeSqlCompletionQualified", true); receipt()
            // Preserve the observed catalog even when the unchanged strict postcondition below fails.
            setStage("WORLD_METADATA_AFTER", "Recording bounded world catalog hashes after the expected native stop.")
            columnAfter = WorldColumnEvidence.parseSnapshot(client(WorldColumnEvidence.schemaSql(columnSchema), 30_000),
                columnSchema, columnSchema.tableNames)
            columnReceipt()
            setStage("WORLD_VALIDATION", "Validating exact resulting updater receipts, release receipts and schema metadata.")
            val results = JSONObject()
            lock.schemas.forEach { schema ->
                checkCancelled()
                val flags = WorldUpdaterEvidence.parseValidation(client(WorldUpdaterEvidence.schemaSql(schema, lock.includeDirectories), 30_000), schema)
                results.put(schema.name, JSONObject(flags)); info.put("schemas", results); receipt()
                if (!flags.values.all { it }) {
                    invocationJournal?.validationFailure("world-server", "WORLD_UPDATER", "world_postcondition_mismatch")
                    error("Compiled updater postconditions differ")
                }
            }
            info.put("compiledUpdaterPassed", true).put("completedAtEpochMs", System.currentTimeMillis()); receipt()
        } finally {
            config.delete()
            File(temp, "mysql_ac.conf").delete()
            stage.deleteRecursively()
            worldInspectorQueue = null
        }
    }

    private fun runWorldBoundary(config: File, orderedLog: File, phases: List<WorldUpdatePhase>): Map<String, Boolean> {
        val timeout = 600_000L
        val label = "tool-${++step}"
        val handle = launch("world-server", listOf("--config", config.absolutePath), null, label, timeout)
        val commandDeadline = System.nanoTime() + timeout * 1_000_000
        try {
            var exit = Int.MIN_VALUE
            while (System.nanoTime() < commandDeadline) {
                checkCancelled()
                exit = waitTracked(handle, 200)
                if (exit != Int.MIN_VALUE) break
            }
            if (exit == Int.MIN_VALUE) finishEvidence(handle, "timed_out", null)
            // Reap/stop every owned child before the root client inspects postconditions.
            if (exit != Int.MIN_VALUE) stopTracked(handle, 1_000, "stopped")
            return try {
                WorldUpdaterEvidence.boundary(exit, WorldUpdaterEvidence.readComplete(orderedLog),
                    WorldUpdaterEvidence.readComplete(File(root, "$label.out")), WorldUpdaterEvidence.readComplete(File(root, "$label.err")), phases)
            } catch (failure: Exception) {
                invocationJournal?.validationFailure("world-server", "WORLD_UPDATER", "world_boundary_invalid")
                throw failure
            }
        } catch (failure: Exception) {
            finishEvidence(handle, when {
                cancelled.get() -> "cancelled"
                System.nanoTime() >= commandDeadline || System.nanoTime() >= deadline -> "timed_out"
                else -> "wait_failed"
            }, null)
            throw failure
        } finally {
            if (owned.containsKey(handle)) runCatching { stopTracked(handle, 1_000, if (cancelled.get()) "cancelled" else "stopped") }
        }
    }

    private fun environment(role: String): Array<String> = WorldUpdaterEnvironment.forRole(arrayOf(
        "PATH=/system/bin", "HOME=${root.absolutePath}", "TMPDIR=${root.absolutePath}",
        "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=${runtime.libraryDirectory.absolutePath}:${runtime.nativeDirectory.absolutePath}"
    ), role, root, worldInspectorQueue)

    private fun setStage(stage: String, message: String) {
        currentStage = stage
        status(stage, message)
    }

    private fun logFile(label: String, suffix: String) = File(
        realmLogDirectory ?: if (recoveryPhase == RecoveryPhase.RESUME) File(root, "recovery-logs") else root, "$label.$suffix")
    private fun logBytes(label: String, suffix: String) = logFile(label, suffix).length()

    private fun finishEvidence(handle: Long, outcome: String, exitCode: Int?) {
        val id = evidenceHandles[handle] ?: return
        val label = owned[handle] ?: return
        invocationJournal?.finish(id, outcome, exitCode, logBytes(label, "out"), logBytes(label, "err"))
    }

    private fun waitTracked(handle: Long, timeoutMs: Long): Int {
        return try {
            val exit = NativeProcessBridge.waitFor(handle, timeoutMs)
            if (exit != Int.MIN_VALUE) {
                finishEvidence(handle, "exited", exit)
                if (handle == realmServerHandle) { realm!!.stopped(); realmServerHandle = 0L }
            }
            exit
        } catch (failure: Exception) {
            finishEvidence(handle, "wait_failed", null)
            throw failure
        }
    }

    private fun stopTracked(handle: Long, graceMs: Long, outcome: String) {
        try {
            NativeEvidenceCleanup.stop(
                recordBefore = { finishEvidence(handle, outcome, null) },
                nativeStop = { NativeProcessBridge.stop(handle, graceMs) },
                recordAfter = { exit ->
                    evidenceHandles[handle]?.let { invocationJournal?.terminated(it, exit.takeIf { value -> value != Int.MIN_VALUE }) }
                    if (handle == realmServerHandle && exit != Int.MIN_VALUE) { realm!!.stopped(); realmServerHandle = 0L }
                }
            )
        } finally {
            owned.remove(handle)
            evidenceHandles.remove(handle)
        }
    }

    private fun launch(role: String, args: List<String>, stdin: File?, label: String, timeoutMs: Long? = null): Long {
        checkCancelled()
        val executable = runtime.executables.getValue(role)
        check(executable.canonicalFile.parentFile == runtime.nativeDirectory)
        val evidence = invocationJournal?.begin(label, role, currentStage, timeoutMs)
        if (realm != null && role == "database-server") realm.launching()
        val handle = try {
            NativeProcessBridge.start(executable.absolutePath, args.toTypedArray(), environment(role),
                root.absolutePath, stdin?.absolutePath, logFile(label, "out").absolutePath, logFile(label, "err").absolutePath)
        } catch (failure: Exception) {
            evidence?.let { invocationJournal?.finish(it, "start_failed", null, logBytes(label, "out"), logBytes(label, "err")) }
            throw failure
        }
        owned[handle] = label
        if (evidence != null) evidenceHandles[handle] = evidence
        if (realm != null && role == "database-server") {
            realmServerHandle = handle
            realm.started(NativeProcessBridge.processId(handle))
        }
        if (cancelled.get()) {
            runCatching { stopTracked(handle, 1_000, "cancelled") }
            checkCancelled()
        }
        return handle
    }

    private fun runTool(role: String, args: List<String>, stdin: File?, timeout: Long): String {
        val label = "tool-${++step}"
        val handle = launch(role, args, stdin, label, timeout)
        val commandDeadline = System.nanoTime() + timeout * 1_000_000
        try {
            var exit = Int.MIN_VALUE
            while (System.nanoTime() < commandDeadline) {
                checkCancelled()
                exit = waitTracked(handle, minOf(200, timeout))
                if (exit != Int.MIN_VALUE) break
            }
            if (exit == Int.MIN_VALUE) finishEvidence(handle, "timed_out", null)
            check(exit == 0) { "Native diagnostic command failed or timed out" }
            val output = logFile(label, "out")
            if (output.length() > 64 * 1024) {
                invocationJournal?.validationFailure(role, currentStage, "output_limit_exceeded")
                error("Native diagnostic output exceeded its limit")
            }
            return output.readText()
        } catch (failure: Exception) {
            finishEvidence(handle, when {
                cancelled.get() -> "cancelled"
                System.nanoTime() >= commandDeadline || System.nanoTime() >= deadline -> "timed_out"
                else -> "wait_failed"
            }, null)
            throw failure
        } finally {
            if (owned.containsKey(handle)) runCatching { stopTracked(handle, 1_000, if (cancelled.get()) "cancelled" else "stopped") }
        }
    }

    private fun client(sql: String, timeout: Long): String {
        val script = File(root, "query-${UUID.randomUUID()}.sql").apply { writeText(sql) }
        return try {
            runClientScript(script, timeout)
        } finally { script.delete() }
    }

    private fun runClientScript(script: File, timeout: Long, database: String? = null): String {
        require(database == null || database in FullSeedStore.SCHEMAS)
        val args = if (clientOptions != null) listOf("--defaults-file=${clientOptions!!.absolutePath}")
            else listOf("--no-defaults", "--user=root", "--protocol=socket", "--socket=${socket.absolutePath}")
        return runTool("database-client", args + listOf("--character-sets-dir=${characterSetsDirectory.absolutePath}",
            "--batch", "--skip-column-names", "--connect-timeout=2") +
            (if (needsFullSeed) listOf("--binary-mode", "--local-infile=0", "--default-character-set=utf8mb4", "--max-allowed-packet=256M") else emptyList()) +
            listOfNotNull(database), script, timeout)
    }

    fun stopOwned() {
        val serverHandle = server
        val order = owned.keys.filter { it != serverHandle } + owned.keys.filter { it == serverHandle }
        order.forEach { handle ->
            runCatching { stopTracked(handle, 2_000, if (cancelled.get()) "cancelled" else "stopped") }
        }
        server = 0
    }
}

/** Fixed-schema evidence: invocation arguments, environment and private paths have no fields here. */
internal data class NativeInvocationEvidence(
    val id: Int,
    val log: String,
    val role: String,
    val stage: String,
    val startedAtEpochMs: Long,
    val timeoutMs: Long?,
    val outcome: String = "running",
    val exitCode: Int? = null,
    val terminationExitCode: Int? = null,
    val timedOut: Boolean = false,
    val cancelled: Boolean = false,
    val durationMs: Long = 0,
    val stdoutBytes: Long = 0,
    val stderrBytes: Long = 0,
    val validationFailure: String? = null,
    val readyObserved: Boolean = false,
    val sqlShutdownRequested: Boolean = false,
    val cleanShutdownObserved: Boolean = false
)

/** Atomic, bounded and usable from ordinary JVM tests without invoking Android APIs. */
internal object NativeEvidenceCleanup {
    /** Recording errors must never prevent the owned native child from being stopped. */
    fun <T> stop(recordBefore: () -> Unit, nativeStop: () -> T, recordAfter: (T) -> Unit): T {
        runCatching { recordBefore() }
        val result = nativeStop()
        runCatching { recordAfter(result) }
        return result
    }
}

internal class NativeInvocationJournal(private val file: File, private val attemptId: String = UUID.randomUUID().toString()) {
    init { require(validAttemptId(attemptId)) }
    private val records = mutableListOf<NativeInvocationEvidence>()
    private var nextId = 1
    private var dropped = 0
    private val clocks = mutableMapOf<Int, Long>()

    @Synchronized fun begin(log: String, role: String, stage: String, timeoutMs: Long?): Int {
        require(log.matches(Regex("server|tool-[0-9]{1,5}")))
        require(role in roles && stage in stages && (timeoutMs == null || timeoutMs in 1..600_000))
        if (records.size == MAX_RECORDS) {
            val removable = records.indexOfFirst { it.outcome != "running" }
            check(removable >= 0) { "Invocation evidence capacity reached" }
            clocks.remove(records.removeAt(removable).id)
            dropped++
        }
        val id = nextId++
        clocks[id] = System.nanoTime()
        records += NativeInvocationEvidence(id, log, role, stage, System.currentTimeMillis(), timeoutMs)
        persist()
        return id
    }

    @Synchronized fun finish(id: Int, outcome: String, exitCode: Int?, stdoutBytes: Long, stderrBytes: Long) {
        require(outcome in outcomes && outcome != "running")
        require(exitCode == null || exitCode in 0..65_535)
        val index = records.indexOfFirst { it.id == id }
        if (index < 0 || records[index].outcome != "running") return
        val before = records[index]
        records[index] = before.copy(outcome = outcome, exitCode = exitCode,
            timedOut = outcome == "timed_out", cancelled = outcome == "cancelled",
            durationMs = ((System.nanoTime() - (clocks[id] ?: System.nanoTime())) / 1_000_000).coerceAtLeast(0),
            stdoutBytes = stdoutBytes.coerceIn(0, MAX_LOG_BYTES), stderrBytes = stderrBytes.coerceIn(0, MAX_LOG_BYTES))
        persist()
    }

    @Synchronized fun terminated(id: Int, exitCode: Int?) {
        require(exitCode == null || exitCode in 0..65_535)
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        records[index] = records[index].copy(terminationExitCode = exitCode)
        persist()
    }

    @Synchronized fun checkpoint(id: Int, checkpoint: String) {
        require(checkpoint in setOf("database_ready", "sql_shutdown_requested", "clean_shutdown"))
        val index = records.indexOfFirst { it.id == id }
        if (index < 0) return
        val before = records[index]
        require(before.role == "database-server")
        records[index] = when (checkpoint) {
            "database_ready" -> {
                require(before.outcome == "running")
                before.copy(readyObserved = true)
            }
            "sql_shutdown_requested" -> {
                require(before.readyObserved)
                before.copy(sqlShutdownRequested = true)
            }
            else -> {
                require(before.sqlShutdownRequested && before.exitCode == 0 && before.outcome == "exited")
                before.copy(cleanShutdownObserved = true)
            }
        }
        persist()
    }

    @Synchronized fun validationFailure(role: String, stage: String, reason: String) {
        require(role in roles && stage in stages && reason in validations)
        val index = records.indexOfLast { it.role == role && it.stage == stage }
        if (index < 0) return
        records[index] = records[index].copy(validationFailure = reason)
        persist()
    }

    private fun persist() {
        val properties = java.util.Properties().apply {
            setProperty("formatVersion", "1")
            setProperty("attemptId", attemptId)
            setProperty("count", records.size.toString())
            setProperty("droppedCount", dropped.toString())
            records.forEachIndexed { index, value ->
                fun field(name: String, content: Any?) { if (content != null) setProperty("$index.$name", content.toString()) }
                field("id", value.id); field("log", value.log); field("role", value.role); field("stage", value.stage)
                field("startedAtEpochMs", value.startedAtEpochMs); field("timeoutMs", value.timeoutMs)
                field("outcome", value.outcome); field("exitCode", value.exitCode); field("terminationExitCode", value.terminationExitCode)
                field("timedOut", value.timedOut); field("cancelled", value.cancelled); field("durationMs", value.durationMs)
                field("stdoutBytes", value.stdoutBytes); field("stderrBytes", value.stderrBytes); field("validationFailure", value.validationFailure)
                field("readyObserved", value.readyObserved); field("sqlShutdownRequested", value.sqlShutdownRequested); field("cleanShutdownObserved", value.cleanShutdownObserved)
            }
        }
        val temporary = File(file.parentFile, ".${file.name}.new")
        try {
            java.io.FileOutputStream(temporary).use { output ->
                properties.store(output, "FTA fixed-schema native invocation evidence")
                output.fd.sync()
            }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    companion object {
        const val MAX_RECORDS = 1024
        const val MAX_FILE_BYTES = 2 * 1024 * 1024L
        const val MAX_LOG_BYTES = 8 * 1024 * 1024L
        private val roles = setOf("database-server", "database-client", "auth-server", "world-server")
        private val stages = setOf("CHECKING", "STAGING", "BOOTSTRAPPING", "STARTING", "AUTH_INPUT", "AUTH_IMPORT", "AUTH_VERSION", "AUTH_UPDATER", "SEED_INPUT", "SEED_STAGING", "SEED_IMPORT", "SEED_VALIDATION", "WORLD_INPUT", "WORLD_STAGING", "WORLD_METADATA_BEFORE", "WORLD_UPDATER", "WORLD_METADATA_AFTER", "WORLD_VALIDATION", "STOPPING", "CLEANUP",
            "LIFECYCLE_WRITE", "LIFECYCLE_RESTART", "LIFECYCLE_CANCEL", "LIFECYCLE_SCREEN_OFF", "LIFECYCLE_CRASH", "LIFECYCLE_FINISH",
            "RECOVERY_HOLD", "RECOVERY_RESUME", "RECOVERY_VALIDATE", "REALM_PREPARING", "REALM_REOPEN", "REALM_VERIFY", "REALM_METADATA")
        private val outcomes = setOf("running", "exited", "timed_out", "cancelled", "start_failed", "wait_failed", "stopped", "supervisor_failed")
        private val validations = setOf("output_limit_exceeded", "version_marker_missing", "completion_marker_missing", "world_boundary_invalid", "world_postcondition_mismatch")

        /** Returns no evidence for malformed files; never exports unvalidated stored text. */
        fun validAttemptId(value: String) = value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))

        fun read(file: File, expectedAttemptId: String? = null): Pair<List<NativeInvocationEvidence>, Int>? = runCatching {
            require(java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && file.length() <= MAX_FILE_BYTES)
            val properties = java.util.Properties().apply { file.inputStream().use { load(it) } }
            require(properties.getProperty("formatVersion") == "1")
            val storedAttempt = properties.getProperty("attemptId")
            require(validAttemptId(storedAttempt) && (expectedAttemptId == null || storedAttempt == expectedAttemptId))
            val count = properties.getProperty("count").toInt().also { require(it in 0..MAX_RECORDS) }
            val dropped = properties.getProperty("droppedCount").toInt().also { require(it >= 0) }
            val records = (0 until count).map { index ->
                fun field(name: String) = properties.getProperty("$index.$name")
                fun number(name: String) = field(name)?.toInt()?.also { require(it in 0..65_535) }
                fun bytes(name: String) = field(name).toLong().also { require(it in 0..MAX_LOG_BYTES) }
                fun flag(name: String) = field(name).also { require(it == "true" || it == "false") } == "true"
                val id = field("id").toInt().also { require(it > 0) }
                val log = field("log").also { require(it.matches(Regex("server|tool-[0-9]{1,5}"))) }
                val role = field("role").also { require(it in roles) }
                val stage = field("stage").also { require(it in stages) }
                val started = field("startedAtEpochMs").toLong().also { require(it >= 0) }
                val timeout = field("timeoutMs")?.toLong()?.also { require(it in 1..600_000) }
                val outcome = field("outcome").also { require(it in outcomes) }
                val duration = field("durationMs").toLong().also { require(it in 0..86_400_000) }
                val validation = field("validationFailure").also { require(it == null || it in validations) }
                NativeInvocationEvidence(id, log, role, stage, started, timeout, outcome, number("exitCode"), number("terminationExitCode"),
                    flag("timedOut"), flag("cancelled"), duration, bytes("stdoutBytes"), bytes("stderrBytes"), validation,
                    flag("readyObserved"), flag("sqlShutdownRequested"), flag("cleanShutdownObserved"))
            }
            require(records.map { it.id }.toSet().size == records.size)
            records to dropped
        }.getOrNull()
    }
}


/** Aggregate eligibility only; no realm name, address, identifier, count or row is retained. */
internal object RealmEligibilityEvidence {
    private val markers = linkedMapOf(
        "FTA_REALM_ROWS_PRESENT" to "realmRowsPresent",
        "FTA_REALM_ELIGIBLE_AFTER_OFFLINE_ROWS_PRESENT" to "eligibleAfterOfflineRowsPresent",
        "FTA_REALM_ALL_THREE_IPV4" to "all3addressesNumericIPv4"
    )
    val fields: List<String> = markers.values.toList()

    // Mirrors the original flag|OFFLINE(2), then flag<>3 candidate filter. Numeric
    // IPv4 checks cover the three address fields of every eligible candidate.
    // With no eligible candidate, all3addressesNumericIPv4 is explicitly false.
    val SQL = """
        SELECT CONCAT('FTA_REALM_ROWS_PRESENT=',EXISTS(SELECT 1 FROM acore_auth.realmlist));
        SELECT CONCAT('FTA_REALM_ELIGIBLE_AFTER_OFFLINE_ROWS_PRESENT=',
            EXISTS(SELECT 1 FROM acore_auth.realmlist WHERE (flag | 2) <> 3));
        SELECT CONCAT('FTA_REALM_ALL_THREE_IPV4=',
            EXISTS(SELECT 1 FROM acore_auth.realmlist WHERE (flag | 2) <> 3) AND
            NOT EXISTS(SELECT 1 FROM acore_auth.realmlist WHERE (flag | 2) <> 3 AND
                (COALESCE(IS_IPV4(address),0) <> 1 OR COALESCE(IS_IPV4(localAddress),0) <> 1
                    OR COALESCE(IS_IPV4(localSubnetMask),0) <> 1)));
    """.trimIndent()

    fun parse(output: String): Map<String, Boolean> {
        require(output.length <= 512) { "Realm eligibility result exceeds its limit" }
        val values = linkedMapOf<String, Boolean>()
        output.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.trim().split('=')
            require(parts.size == 2 && parts[1] in setOf("0", "1")) { "Unexpected realm eligibility result" }
            val field = markers[parts[0]] ?: throw IllegalArgumentException("Unexpected realm eligibility field")
            require(field !in values) { "Duplicate realm eligibility field" }
            values[field] = parts[1] == "1"
        }
        require(values.size == fields.size && consistent(values)) { "Incomplete or inconsistent realm eligibility result" }
        return values
    }

    /** A report accepts the complete consistent boolean set, never coerced strings or rows. */
    fun forward(values: Map<String, Any?>): Map<String, Boolean> {
        if (fields.any { values[it] !is Boolean }) return emptyMap()
        val result = fields.associateWith { values.getValue(it) as Boolean }
        return if (consistent(result)) result else emptyMap()
    }

    private fun consistent(values: Map<String, Boolean>) =
        (values["eligibleAfterOfflineRowsPresent"] != true || values["realmRowsPresent"] == true) &&
            (values["all3addressesNumericIPv4"] != true || values["eligibleAfterOfflineRowsPresent"] == true)
}
