package com.russianranger.fta.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Synthetic data only. A per-attempt token proves the resumed datadir is this test's. */
object RecoveryCanary {
    fun create(attempt: String, query: (String) -> String) {
        require(NativeInvocationJournal.validAttemptId(attempt))
        query(DatabaseLifecycleProbe.CREATE + " CREATE TABLE fta_lifecycle.session_token(value CHAR(36)) ENGINE=InnoDB;" +
            " INSERT INTO fta_lifecycle.session_token VALUES('$attempt');")
        check(query(DatabaseLifecycleProbe.CHECK).trim() == "1")
    }
    fun verify(attempt: String, query: (String) -> String) {
        require(NativeInvocationJournal.validAttemptId(attempt))
        check(query("SELECT value FROM fta_lifecycle.session_token;").trim() == attempt)
        check(query(DatabaseLifecycleProbe.ACTIVE).trim() == "0")
        check(query(DatabaseLifecycleProbe.CHECK).trim() == "1")
    }
}

data class ProcessIdentity(val pid: Int, val startTicks: Long, val parentPid: Int) {
    init { require(pid > 0 && startTicks > 0 && parentPid >= 0) }
    // Reparenting is NOT termination. PPID is used only to bind initial ownership.
    fun sameProcess(other: ProcessIdentity) = pid == other.pid && startTicks == other.startTicks
}
data class ProcessSample(val identity: ProcessIdentity, val state: Char) {
    val alive get() = state !in setOf('Z', 'X', 'x')
}
object ProcessObservation {
    fun parse(text: String, pid: Int): ProcessSample {
        require(text.length in 1..4096 && text.substringBefore(' ').toInt() == pid)
        val close = text.lastIndexOf(')')
        require(close > text.indexOf('(') && text.indexOf('(') > 0)
        val fields = text.substring(close + 1).trim().split(Regex("\\s+"))
        require(fields.size >= 20 && fields[0].length == 1 && fields[0][0] in "RSDTtXZxPIW")
        return ProcessSample(ProcessIdentity(pid, fields[19].toLong(), fields[1].toInt()), fields[0][0])
    }
    private fun readFile(file: File, pid: Int): ProcessSample? {
        require(pid > 0)
        val bytes = try { Files.newInputStream(file.toPath()).use { input ->
            val result = ByteArray(4097)
            var used = 0
            while (used < result.size) { val count = input.read(result, used, result.size - used); if (count < 0) break; used += count }
            require(used <= 4096); result.copyOf(used)
        } } catch (_: NoSuchFileException) { return null }
        // Permission errors and malformed observations must propagate, never imply death.
        return parse(bytes.toString(Charsets.UTF_8), pid)
    }
    fun read(pid: Int): ProcessSample? = readFile(File("/proc/$pid/stat"), pid)
    fun self(expectedPid: Int): ProcessIdentity = readFile(File("/proc/self/stat"), expectedPid)
        ?.also { check(it.alive) }?.identity ?: error("Own process identity is unavailable")
    fun ended(expected: ProcessIdentity, observed: ProcessSample?) =
        observed == null || !expected.sameProcess(observed.identity) || !observed.alive
    fun ended(expected: ProcessIdentity) = ended(expected, read(expected.pid))
    fun live(pid: Int): ProcessIdentity = read(pid)?.also { check(it.alive) }?.identity
        ?: error("Process identity is unavailable")
}

enum class RecoveryPhase { CANCEL, CRASH, RESUME }
data class RecoveryWorkerRecord(
    val phase: RecoveryPhase,
    val state: String = "STARTING",
    val rootName: String = "",
    val owner: ProcessIdentity? = null,
    val children: List<ProcessIdentity> = emptyList(),
    val destroyed: Boolean = false
)

/** Private fixed-schema control record; PIDs, paths and credentials are never exported. */
class RecoveryStore(val directory: File, val attemptId: String) {
    init { require(NativeInvocationJournal.validAttemptId(attemptId)) }
    private fun file(phase: RecoveryPhase) = File(directory, "${phase.name.lowercase()}.properties")
    @Synchronized fun write(record: RecoveryWorkerRecord) {
        require(record.state in STATES && record.children.size <= 2)
        require(record.rootName.isEmpty() || validRoot(record.rootName))
        check(directory.mkdirs() || directory.isDirectory)
        val properties = Properties().apply {
            setProperty("formatVersion", "1"); setProperty("attemptId", attemptId)
            setProperty("phase", record.phase.name); setProperty("state", record.state)
            setProperty("root", record.rootName); setProperty("destroyed", record.destroyed.toString())
            fun putIdentity(key: String, value: ProcessIdentity) { setProperty(key, "${value.pid}:${value.startTicks}:${value.parentPid}") }
            record.owner?.let { putIdentity("owner", it) }
            setProperty("children", record.children.size.toString())
            record.children.forEachIndexed { i, child -> putIdentity("child$i", child) }
        }
        val target = file(record.phase)
        val temporary = File(directory, ".${target.name}.new")
        try {
            java.io.FileOutputStream(temporary).use { properties.store(it, "FTA private recovery control"); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    @Synchronized fun read(phase: RecoveryPhase): RecoveryWorkerRecord? {
        val target = file(phase)
        if (!target.exists()) return null
        require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS) && target.length() <= 4096)
        val p = Properties().apply { target.inputStream().use { load(it) } }
        require(p.getProperty("formatVersion") == "1" && p.getProperty("attemptId") == attemptId && p.getProperty("phase") == phase.name)
        fun identity(key: String): ProcessIdentity? = p.getProperty(key)?.split(':')?.let {
            require(it.size == 3); ProcessIdentity(it[0].toInt(), it[1].toLong(), it[2].toInt())
        }
        val count = p.getProperty("children").toInt().also { require(it in 0..2) }
        val state = p.getProperty("state").also { require(it in STATES) }
        val root = p.getProperty("root").also { require(it.isEmpty() || validRoot(it)) }
        val destroyed = p.getProperty("destroyed").also { require(it in setOf("true", "false")) } == "true"
        return RecoveryWorkerRecord(phase, state, root, identity("owner"), (0 until count).map { identity("child$it")!! }, destroyed)
    }
    @Synchronized fun state(phase: RecoveryPhase, value: String, destroyed: Boolean? = null) {
        val before = read(phase) ?: RecoveryWorkerRecord(phase)
        write(before.copy(state = value, destroyed = destroyed ?: before.destroyed))
    }
    fun verifiedRoot(parent: File, record: RecoveryWorkerRecord): File {
        require(validRoot(record.rootName))
        val root = File(parent, record.rootName)
        require(Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS) && root.canonicalFile.parentFile == parent.canonicalFile)
        return root
    }
    companion object {
        private val STATES = setOf("STARTING", "READY", "STOPPED", "RECOVERED", "FAILED")
        fun validRoot(value: String) = value.startsWith("g-") && NativeInvocationJournal.validAttemptId(value.removePrefix("g-"))
    }
}

/** Controller remains in the UI process while the nonexported :runtime service dies. */
object RuntimeRecoveryProtocol {
    const val PASSED = "RUNTIME_RECOVERY_PASSED"
    val fields = listOf("stopActionDelivered", "stopCleanupObserved", "ownerDeathRequested",
        "ownerDeathObserved", "nativeChildrenTerminated", "sameDatabaseRecovered", "finalCleanShutdown")
    interface Driver {
        fun start(phase: RecoveryPhase)
        fun checkpoint(phase: RecoveryPhase): RecoveryWorkerRecord?
        fun stopAction()
        fun killOwner()
        fun ended(identity: ProcessIdentity): Boolean
        fun checkActive()
        fun pause()
        fun nowMs(): Long
    }
    fun ready(record: RecoveryWorkerRecord): Boolean = record.state == "READY" && !record.destroyed &&
        RecoveryStore.validRoot(record.rootName) && record.owner != null && record.children.size == 2 &&
        record.children.map { it.pid }.distinct().size == 2 && record.children.all { it.parentPid == record.owner.pid }
    fun run(driver: Driver, record: (String) -> Unit) {
        fun waitFor(phase: RecoveryPhase, predicate: (RecoveryWorkerRecord) -> Boolean): RecoveryWorkerRecord {
            val end = driver.nowMs() + 240_000
            while (true) {
                driver.checkActive()
                driver.checkpoint(phase)?.let {
                    check(it.phase == phase && it.state != "FAILED") { "Recovery worker failed" }
                    if (predicate(it)) return it
                }
                check(driver.nowMs() < end) { "Recovery worker timed out" }; driver.pause()
            }
        }
        fun awaitEnd(identities: List<ProcessIdentity>) {
            val end = driver.nowMs() + 15_000
            while (true) {
                driver.checkActive()
                if (identities.all { driver.ended(it) }) return
                check(driver.nowMs() < end) { "Owned process survived the recovery action" }; driver.pause()
            }
        }
        driver.start(RecoveryPhase.CANCEL)
        val cancel = waitFor(RecoveryPhase.CANCEL, ::ready)
        check(cancel.children.none { driver.ended(it) })
        driver.stopAction(); record("stopActionDelivered")
        val stopped = waitFor(RecoveryPhase.CANCEL) { it.state == "STOPPED" && it.destroyed }
        check(stopped.rootName == cancel.rootName && stopped.children == cancel.children)
        awaitEnd(cancel.children); record("stopCleanupObserved")
        driver.start(RecoveryPhase.CRASH)
        val crash = waitFor(RecoveryPhase.CRASH, ::ready)
        check(crash.rootName != cancel.rootName)
        check((crash.children + crash.owner!!).none { driver.ended(it) })
        driver.killOwner(); record("ownerDeathRequested")
        awaitEnd(listOf(crash.owner)); record("ownerDeathObserved")
        awaitEnd(crash.children); record("nativeChildrenTerminated")
        driver.start(RecoveryPhase.RESUME)
        val recovered = waitFor(RecoveryPhase.RESUME) { it.state == "RECOVERED" && it.destroyed }
        check(recovered.rootName == crash.rootName && recovered.owner != null &&
            !crash.owner.sameProcess(recovered.owner)) { "Recovery did not use a new owner and the same database" }
        record("sameDatabaseRecovered"); record("finalCleanShutdown")
    }
}
