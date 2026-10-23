package com.russianranger.fta.runtime

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID

data class RealmGeneration(val id: String, val directory: File)

/** Fresh-only generations. An invalid active pointer is an error, never an absent realm. */
class PersistentRealmStore(
    private val base: File,
    private val identity: Map<String, String>,
    private val syncDirectory: (File) -> Unit = { FileChannel.open(it.toPath(), READ).use { channel -> channel.force(true) } }
) {
    init {
        require(identity.keys == IDENTITY_FIELDS && identity.values.all { it.matches(Regex("[a-f0-9]{64}")) })
    }
    private val generations get() = File(base, "generations")
    private val activeFile get() = File(base, "active.properties")
    private val pendingFile get() = File(base, "pending.properties")
    private val writerFile get() = File(base, "writer.properties")
    private var leased = false
    private fun exists(file: File) = Files.exists(file.toPath(), NOFOLLOW_LINKS)
    private fun directory(file: File) {
        if (!exists(file)) { check(file.mkdir()); syncDirectory(file.parentFile!!) }
        require(Files.isDirectory(file.toPath(), NOFOLLOW_LINKS))
    }
    fun <T> exclusive(operation: () -> T): T {
        directory(base); directory(generations)
        val lease = File(base, "lease")
        if (exists(lease)) require(Files.isRegularFile(lease.toPath(), NOFOLLOW_LINKS))
        return FileChannel.open(lease.toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = channel.tryLock() ?: error("Realm storage is already in use")
            lock.use {
                check(!leased); leased = true
                try { verifyWriterEnded(); operation() } finally { leased = false }
            }
        }
    }
    private fun requireLease() = check(leased) { "Realm storage lease is required" }
    private fun write(file: File, values: Map<String, String>) {
        requireLease()
        val temporary = File(file.parentFile, ".${file.name}-${UUID.randomUUID()}.new")
        try {
            Files.newOutputStream(temporary.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).close()
            check(temporary.setReadable(false, false) && temporary.setWritable(false, false))
            check(temporary.setReadable(true, true) && temporary.setWritable(true, true))
            FileOutputStream(temporary).use { output ->
                Properties().apply { putAll(values) }.store(output, "FTA private realm state")
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(file.parentFile!!)
        } finally { temporary.delete() }
    }
    private fun read(file: File): Map<String, String> {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..8192)
        val p = Properties().apply { Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { load(it) } }
        return p.stringPropertyNames().associateWith { p.getProperty(it) }
    }
    private fun generation(id: String): RealmGeneration {
        require(NativeInvocationJournal.validAttemptId(id))
        val root = File(generations, "r-$id")
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS) && root.canonicalFile.parentFile == generations.canonicalFile)
        val bound = read(File(root, "identity.properties"))
        require(bound == identity + mapOf("formatVersion" to "1", "realmId" to id)) { "Realm source identity differs" }
        return RealmGeneration(id, root)
    }
    fun inspectGeneration(id: String): RealmGeneration { requireLease(); return generation(id) }
    private fun preparedFile(realm: RealmGeneration) = File(realm.directory, "prepared.properties")
    private fun preparedDigest(realm: RealmGeneration): String {
        val file = preparedFile(realm)
        require(read(file) == identity + mapOf("formatVersion" to "1", "realmId" to realm.id,
            "freshSeedChecks" to "15", "compiledUpdaterChecks" to "24", "initialCleanShutdown" to "true"))
        return MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun active(): RealmGeneration? {
        requireLease()
        if (!exists(activeFile)) return null
        val value = read(activeFile)
        require(value.keys == setOf("formatVersion", "realmId", "preparedSha256") && value["formatVersion"] == "1")
        val realm = generation(value.getValue("realmId"))
        require(preparedDigest(realm) == value["preparedSha256"]) { "Active realm receipt differs" }
        return realm
    }
    fun preparedCandidate(): RealmGeneration? {
        requireLease()
        if (!exists(pendingFile)) return null
        val value = read(pendingFile)
        require(value.keys == setOf("realmId"))
        val realm = generation(value.getValue("realmId"))
        if (!exists(preparedFile(realm))) return null
        preparedDigest(realm)
        return realm
    }
    fun createFresh(): RealmGeneration {
        requireLease(); check(active() == null && preparedCandidate() == null)
        // Incomplete older candidates are retained. Never replay seed SQL into one.
        val id = UUID.randomUUID().toString()
        val root = File(generations, "r-$id")
        check(root.mkdir()); syncDirectory(generations)
        write(File(root, "identity.properties"), identity + mapOf("formatVersion" to "1", "realmId" to id))
        write(pendingFile, mapOf("realmId" to id))
        return generation(id)
    }
    fun requireFresh(realm: RealmGeneration) {
        requireLease(); check(active() == null && generation(realm.id) == realm)
        check(realm.directory.list()?.toSet() == setOf("identity.properties")) { "Seed import requires a new empty generation" }
    }
    fun prepared(realm: RealmGeneration) {
        requireLease(); check(active() == null && generation(realm.id) == realm && !exists(writerFile))
        check(!exists(preparedFile(realm)))
        write(preparedFile(realm), identity + mapOf("formatVersion" to "1", "realmId" to realm.id,
            "freshSeedChecks" to "15", "compiledUpdaterChecks" to "24", "initialCleanShutdown" to "true"))
    }
    fun activate(realm: RealmGeneration) {
        requireLease(); check(active() == null && !exists(writerFile))
        check(preparedCandidate() == realm)
        write(activeFile, mapOf("formatVersion" to "1", "realmId" to realm.id, "preparedSha256" to preparedDigest(realm)))
        // Activation is authoritative even if deleting the redundant pending pointer fails.
        if (pendingFile.delete()) syncDirectory(base)
    }
    fun serverLaunching(owner: ProcessIdentity) {
        requireLease(); check(!exists(writerFile))
        write(writerFile, mapOf("state" to "LAUNCHING", "owner" to encode(owner)))
    }
    fun serverStarted(owner: ProcessIdentity, child: ProcessIdentity) {
        requireLease(); require(child.parentPid == owner.pid)
        require(read(writerFile) == mapOf("state" to "LAUNCHING", "owner" to encode(owner)))
        write(writerFile, mapOf("state" to "RUNNING", "owner" to encode(owner), "child" to encode(child)))
    }
    fun serverStopped(owner: ProcessIdentity) {
        requireLease()
        val value = read(writerFile)
        require(value["state"] == "RUNNING" && decode(value.getValue("owner")).sameProcess(owner))
        check(ProcessObservation.ended(decode(value.getValue("child")))) { "Realm database remains alive" }
        check(writerFile.delete()); syncDirectory(base)
    }
    private fun verifyWriterEnded() {
        if (!exists(writerFile)) return
        val value = read(writerFile)
        // An interruption in the launch/identity-write window is ambiguous: fail closed.
        require(value.keys == setOf("state", "owner", "child") && value["state"] == "RUNNING") { "Realm database ownership is incomplete" }
        val owner = decode(value.getValue("owner")); val child = decode(value.getValue("child"))
        require(child.parentPid == owner.pid)
        check(ProcessObservation.ended(owner) && ProcessObservation.ended(child)) { "A previous realm owner or database is still active" }
        check(writerFile.delete()); syncDirectory(base)
    }
    companion object {
        val IDENTITY_FIELDS = setOf("seedSha256", "providerManifestSha256", "worldExecutableSha256", "updaterLockSha256", "sourceTreeSha256")
        private fun encode(p: ProcessIdentity) = "${p.pid}:${p.startTicks}:${p.parentPid}"
        private fun decode(s: String): ProcessIdentity = s.split(':').let {
            require(it.size == 3); ProcessIdentity(it[0].toInt(), it[1].toLong(), it[2].toInt())
        }
    }
}

/** No seed path is reachable once an active generation exists. */
object RealmStorageProtocol {
    val fields = listOf("freshOnlyPolicy", "preparedReceiptVerified", "firstReopenPassed", "secondReopenPassed",
        "sameGenerationRetained", "activePointerVerified", "finalCleanShutdown")
    interface Driver {
        fun prepare(realm: RealmGeneration)
        fun verify(realm: RealmGeneration, pass: Int)
        fun checkActive()
    }
    fun run(store: PersistentRealmStore, driver: Driver, observe: (String, Boolean) -> Unit) = store.exclusive {
        driver.checkActive()
        val before = store.active()
        observe("seedImportNeeded", before == null && store.preparedCandidate() == null)
        val realm = before ?: store.preparedCandidate() ?: store.createFresh().also {
            store.requireFresh(it); driver.prepare(it); driver.checkActive(); store.prepared(it)
        }
        observe("freshOnlyPolicy", true); observe("preparedReceiptVerified", true)
        driver.checkActive(); driver.verify(realm, 1); observe("firstReopenPassed", true)
        driver.checkActive(); driver.verify(realm, 2); observe("secondReopenPassed", true)
        observe("sameGenerationRetained", true); driver.checkActive()
        if (before == null) store.activate(realm)
        check(store.active() == realm); observe("activePointerVerified", true)
        observe("finalCleanShutdown", true)
    }
}

/** Launcher-owned control schema; original acore schemas and SQL are not edited. */
object RealmStorageCanary {
    fun create(id: String, query: (String) -> String) {
        require(NativeInvocationJournal.validAttemptId(id))
        query("CREATE DATABASE fta_launcher; CREATE TABLE fta_launcher.realm_identity(id INT PRIMARY KEY, token CHAR(36) NOT NULL) ENGINE=InnoDB; INSERT INTO fta_launcher.realm_identity VALUES(1,'$id');")
        verify(id, query)
    }
    fun verify(id: String, query: (String) -> String) {
        require(NativeInvocationJournal.validAttemptId(id))
        check(query("SELECT CONCAT(id,':',token) FROM fta_launcher.realm_identity ORDER BY id;").trim() == "1:$id") { "Persistent realm identity differs" }
    }
}
