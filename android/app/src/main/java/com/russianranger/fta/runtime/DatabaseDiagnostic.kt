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
    private val includeAuth: Boolean = false
) {
    private val owned = ConcurrentHashMap<Long, String>()
    private lateinit var root: File
    private lateinit var runtime: PackagedRuntime
    private var server = 0L
    private var clientOptions: File? = null
    private lateinit var socket: File
    private var step = 0
    private val deadline = System.nanoTime() + (if (includeAuth) 420L else 300L) * 1_000_000_000L

    fun run(): String {
        runtime = PackagedRuntime.inspect(context)
        if (includeAuth) require("authserver" in runtime.manifest.capabilities && "auth-server" in runtime.executables)
        require(runtime.manifest.bootstrap != null) { "A pinned bootstrap recipe is required" }
        val parent = File(context.filesDir, "runtime/database-diagnostics").apply { check(mkdirs() || isDirectory) }
        // At most two previous diagnostic directories are retained. These never hold user saves.
        parent.listFiles()?.filter { it.isDirectory && it.name.startsWith("g-") }
            ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.deleteRecursively() }
        check(parent.usableSpace >= 512L * 1024 * 1024) { "At least 512 MiB free private storage is required" }
        root = File(parent, "g-${UUID.randomUUID()}").apply { check(mkdir()) }
        checkCancelled()
        status("STAGING", "Verifying packaged database bootstrap and system data.")
        val bootstrap = runtime.stageData(context, File(root, "payload"), ::checkCancelled)
        val provider = File(root, "payload/data/provider")
        val datadir = File(root, "database").apply { check(mkdir()) }
        val runDirectory = File(context.filesDir, "run").apply { check(mkdirs() || isDirectory) }
        socket = File(runDirectory, "d.sock")
        if (socket.exists()) check(socket.delete()) { "Stale private diagnostic socket could not be removed" }
        // Linux sockaddr_un paths are limited to 108 bytes, including termination.
        check(socket.absolutePath.toByteArray().size < 104) { "Private database socket path is too long" }
        val pluginDirectory = File(root, "plugin").apply { check(mkdir()) }
        val options = listOf(
            "--no-defaults", "--basedir=${provider.absolutePath}", "--datadir=${datadir.absolutePath}",
            "--plugin-dir=${pluginDirectory.absolutePath}",
            "--lc-messages-dir=${File(provider, "share/mysql").absolutePath}",
            "--socket=${socket.absolutePath}", "--pid-file=${File(root, "db.pid").absolutePath}",
            "--skip-networking", "--skip-name-resolve", "--performance-schema=OFF",
            "--innodb-buffer-pool-size=64M", "--innodb-log-file-size=16M", "--aria-pagecache-buffer-size=16M", "--key-buffer-size=8M",
            "--max-connections=8", "--thread-cache-size=2", "--tmp-table-size=8M", "--max-heap-table-size=8M",
            "--max-allowed-packet=16M"
        )
        try {
            status("BOOTSTRAPPING", "Initializing a fresh disposable database. Existing saves are untouched.")
            runTool("database-server", options + listOf("--bootstrap", "--log-warnings=0", "--enforce-storage-engine="), bootstrap, 180_000)
            File(datadir, "mariadb_upgrade_info").writeText("${runtime.manifest.providerVersion}-MariaDB")
            checkCancelled()
            status("STARTING", "Starting socket-only MariaDB with bounded memory settings.")
            server = launch("database-server", options, null, "server")
            val deadline = System.nanoTime() + 45_000_000_000L
            var ready = false
            while (System.nanoTime() < deadline) {
                checkCancelled()
                check(NativeProcessBridge.waitFor(server, 0) == Int.MIN_VALUE) { "Database exited before socket readiness" }
                if (socket.exists()) {
                    val probe = runCatching { client("SELECT 1;", 2_000) }
                    if (probe.isSuccess && probe.getOrThrow().trim() == "1") { ready = true; break }
                }
                Thread.sleep(150)
            }
            check(ready) { "Database socket did not become ready" }
            status("CHECKING", "Testing local credentials, transactions, system tables and MySQL 8 collation alias.")
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
                CREATE TABLE fta_diagnostic.probe(id INT PRIMARY KEY, text_value VARCHAR(32))
                    ENGINE=InnoDB CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
                START TRANSACTION;
                INSERT INTO fta_diagnostic.probe VALUES (1, CONVERT(0xF09F9495 USING utf8mb4));
                SELECT CONCAT('FTA_UNICODE_ROWS=',COUNT(*)) FROM fta_diagnostic.probe
                    WHERE text_value=CONVERT(0xF09F9495 USING utf8mb4);
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
            check(values["FTA_ROLLBACK_ROWS"] == "0") { "InnoDB rollback probe failed" }
            if (includeAuth) runAuthUpdaterDiagnostic()
            status("STOPPING", "Stopping the diagnostic database with SQL SHUTDOWN.")
            client("SHUTDOWN;", 10_000)
            val stopped = NativeProcessBridge.waitFor(server, 15_000)
            check(stopped != Int.MIN_VALUE && stopped == 0) { "Database did not shut down cleanly" }
            owned.remove(server)
            server = 0
            return if (includeAuth)
                "Original ARM64 authserver dry run and updater passed against the unchanged 27-table auth seed, then the disposable database shut down cleanly. No auth listener was opened. Full seed, worldserver and game login remain unqualified."
                else "MariaDB ${runtime.manifest.providerVersion}: fresh bootstrap, private credentials, socket health, system tables, collation alias, InnoDB rollback and clean shutdown passed. Seed compatibility and AzerothCore remain unqualified."
        } finally {
            // SQL shutdown is preferred. The JNI helper owns process groups and enforces deadlines.
            if (server != 0L && !cancelled.get()) runCatching { client("SHUTDOWN;", 3_000) }
            stopOwned()
            clientOptions?.delete()
            if (::socket.isInitialized) socket.delete()
        }
    }

    private fun checkCancelled() {
        check(!cancelled.get()) { "Database diagnostic cancelled" }
        check(System.nanoTime() < deadline) { "Database diagnostic exceeded its total deadline" }
    }

    private fun runAuthUpdaterDiagnostic() {
        check("authserver" in runtime.manifest.capabilities && "auth-server" in runtime.executables) { "Packaged authserver is unavailable" }
        status("AUTH_INPUT", "Verifying the exact private database ZIP and auth-only file identities.")
        val info = JSONObject().put("formatVersion", 1).put("archiveSha256", Inputs.database.sha256)
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
            status("AUTH_IMPORT", "Importing verified auth table ${index + 1} of 27 into the disposable database.")
            runClientScript(file, 10_000, "acore_auth")
            info.put("importedTableCount", index + 1)
            receipt()
        }
        check(client("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='acore_auth';", 5_000).trim() == "27")
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
            status("AUTH_VERSION", "Checking the packaged original authserver build identity.")
            check(runTool("auth-server", listOf("--version"), null, 10_000).contains("AzerothCore"))
            status("AUTH_UPDATER", "Running the original auth database updater and dry run without a network listener.")
            val result = runTool("auth-server", listOf("--config", config.absolutePath, "--dry-run"), null, 180_000)
            check(result.contains("Dry run completed, terminating.")) { "Original auth dry-run completion marker is missing" }
            check(client("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='acore_auth';", 5_000).trim() == "27")
            info.put("dryRunPassed", true).put("completedAtEpochMs", System.currentTimeMillis())
            receipt()
        } finally {
            config.delete()
            // Original DBUpdater writes this private temporary client options file.
            File(temp, "mysql_ac.conf").delete()
        }
    }

    private fun environment(): Array<String> = arrayOf(
        "PATH=/system/bin", "HOME=${root.absolutePath}", "TMPDIR=${root.absolutePath}",
        "ANDROID_ROOT=/system", "ANDROID_DATA=/data",
        "LD_LIBRARY_PATH=${runtime.libraryDirectory.absolutePath}:${runtime.nativeDirectory.absolutePath}"
    )

    private fun launch(role: String, args: List<String>, stdin: File?, label: String): Long {
        checkCancelled()
        val executable = runtime.executables.getValue(role)
        check(executable.canonicalFile.parentFile == runtime.nativeDirectory)
        val handle = NativeProcessBridge.start(executable.absolutePath, args.toTypedArray(), environment(),
            root.absolutePath, stdin?.absolutePath, File(root, "$label.out").absolutePath, File(root, "$label.err").absolutePath)
        owned[handle] = label
        if (cancelled.get()) {
            runCatching { NativeProcessBridge.stop(handle, 1_000) }
            owned.remove(handle)
            checkCancelled()
        }
        return handle
    }

    private fun runTool(role: String, args: List<String>, stdin: File?, timeout: Long): String {
        val label = "tool-${++step}"
        val handle = launch(role, args, stdin, label)
        try {
            val deadline = System.nanoTime() + timeout * 1_000_000
            var exit = Int.MIN_VALUE
            while (System.nanoTime() < deadline) {
                checkCancelled()
                exit = NativeProcessBridge.waitFor(handle, minOf(200, timeout))
                if (exit != Int.MIN_VALUE) break
            }
            check(exit == 0) { "Native diagnostic command failed or timed out" }
            owned.remove(handle)
            val output = File(root, "$label.out")
            check(output.length() <= 64 * 1024) { "Native diagnostic output exceeded its limit" }
            return output.readText()
        } finally {
            if (owned.remove(handle) != null) runCatching { NativeProcessBridge.stop(handle, 1_000) }
        }
    }

    private fun client(sql: String, timeout: Long): String {
        val script = File(root, "query-${UUID.randomUUID()}.sql").apply { writeText(sql) }
        return try {
            runClientScript(script, timeout)
        } finally { script.delete() }
    }

    private fun runClientScript(script: File, timeout: Long, database: String? = null): String {
        require(database == null || database == "acore_auth")
        val args = if (clientOptions != null) listOf("--defaults-file=${clientOptions!!.absolutePath}")
            else listOf("--no-defaults", "--user=root", "--protocol=socket", "--socket=${socket.absolutePath}")
        return runTool("database-client", args + listOf("--batch", "--skip-column-names", "--connect-timeout=2") +
            listOfNotNull(database), script, timeout)
    }

    fun stopOwned() {
        val serverHandle = server
        val order = owned.keys.filter { it != serverHandle } + owned.keys.filter { it == serverHandle }
        order.forEach { handle ->
            runCatching { NativeProcessBridge.stop(handle, 2_000) }
            owned.remove(handle)
        }
        server = 0
    }
}
