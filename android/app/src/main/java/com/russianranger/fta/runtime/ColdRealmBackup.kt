package com.russianranger.fta.runtime

import java.io.*
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class RealmBackupEntry(val path: String, val directory: Boolean, val size: Long, val sha256: String)
data class RealmBackupManifest(val realmId: String, val identity: Map<String, String>, val entries: List<RealmBackupEntry>)

/** Cold database files and private credentials only. No executable, seed SQL or arbitrary destination. */
object ColdRealmBackup {
    const val MAX_BYTES = 32L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 50_000
    private const val MANIFEST = "fta-backup.properties"
    private const val MAX_MANIFEST = 8 * 1024 * 1024
    private val metadata = setOf("client.cnf", "identity.properties", "prepared.properties")
    private val emptyHash = MessageDigest.getInstance("SHA-256").digest().hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun validPath(path: String): Boolean = path.length in 1..512 && !path.startsWith('/') &&
        path.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } &&
        path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") } &&
        (path in metadata || path == "database" || path.startsWith("database/"))
    fun sha256(file: File, checkActive: () -> Unit = {}): String {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { input ->
            val bytes = ByteArray(256 * 1024)
            while (true) { checkActive(); val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return digest.digest().hex()
    }
    fun verifyExport(input: InputStream, expectedBytes: Long, expectedSha256: String) {
        require(expectedBytes in 1..(MAX_BYTES + MAX_MANIFEST + 64L * 1024 * 1024))
        require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(256 * 1024); var size = 0L
        while (true) { val n = input.read(buffer); if (n < 0) break; size += n; require(size <= expectedBytes); digest.update(buffer, 0, n) }
        require(size == expectedBytes && digest.digest().hex() == expectedSha256) { "Exported backup integrity differs" }
    }
    fun inventory(root: File, checkActive: () -> Unit = {}): List<RealmBackupEntry> {
        require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS))
        val result = mutableListOf<RealmBackupEntry>()
        var bytes = 0L
        fun visit(file: File) {
            checkActive(); require(result.size < MAX_ENTRIES)
            val path = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
            require(validPath(path))
            val directory = Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)
            require(directory || Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) { "Backup refuses links and special files" }
            val size = if (directory) 0L else file.length()
            require(size in 0..MAX_BYTES && bytes <= MAX_BYTES - size); bytes += size
            result += RealmBackupEntry(path, directory, size, if (directory) emptyHash else sha256(file, checkActive))
            if (directory) file.listFiles()?.sortedBy { it.name }?.forEach(::visit) ?: error("Cannot list database directory")
        }
        metadata.sorted().forEach { visit(File(root, it)) }
        visit(File(root, "database"))
        return result.sortedBy { it.path }.also { validate(RealmBackupManifest("00000000-0000-0000-0000-000000000000", PersistentRealmStore.IDENTITY_FIELDS.associateWith { "0".repeat(64) }, it)) }
    }
    private fun validate(manifest: RealmBackupManifest) {
        require(NativeInvocationJournal.validAttemptId(manifest.realmId))
        require(manifest.identity.keys == PersistentRealmStore.IDENTITY_FIELDS && manifest.identity.values.all { it.matches(Regex("[a-f0-9]{64}")) })
        val entries = manifest.entries
        require(entries.size in 4..MAX_ENTRIES && entries.map { it.path } == entries.map { it.path }.sorted())
        val paths = entries.associateBy { it.path }
        require(paths.size == entries.size && metadata.all { paths[it]?.directory == false } && paths["database"]?.directory == true)
        var bytes = 0L
        entries.forEach { entry ->
            require(validPath(entry.path) && entry.size in 0..MAX_BYTES && entry.sha256.matches(Regex("[a-f0-9]{64}")))
            if (entry.path in metadata) require(entry.size in 1..8192)
            require(!entry.directory || (entry.path == "database" || entry.path.startsWith("database/")) && entry.size == 0L && entry.sha256 == emptyHash)
            require(bytes <= MAX_BYTES - entry.size); bytes += entry.size
            if ('/' in entry.path) require(paths[entry.path.substringBeforeLast('/')]?.directory == true)
        }
    }
    private fun encode(manifest: RealmBackupManifest): ByteArray {
        validate(manifest)
        val p = Properties()
        p["format"] = "FTA_COLD_REALM_1"; p["realmId"] = manifest.realmId; p["count"] = manifest.entries.size.toString()
        manifest.identity.forEach { (key, value) -> p["identity.$key"] = value }
        manifest.entries.forEachIndexed { i, e ->
            p["$i.path"] = e.path; p["$i.directory"] = e.directory.toString(); p["$i.size"] = e.size.toString(); p["$i.sha256"] = e.sha256
        }
        return ByteArrayOutputStream().also { p.store(it, "Private From the Ashes cold realm backup") }.toByteArray().also { require(it.size <= MAX_MANIFEST) }
    }
    private fun decode(bytes: ByteArray): RealmBackupManifest {
        require(bytes.size in 1..MAX_MANIFEST)
        val p = Properties().apply { ByteArrayInputStream(bytes).use { load(it) } }
        require(p.getProperty("format") == "FTA_COLD_REALM_1")
        val count = p.getProperty("count").toInt(); require(count in 4..MAX_ENTRIES)
        require(p.size == 3 + PersistentRealmStore.IDENTITY_FIELDS.size + 4 * count)
        val identity = PersistentRealmStore.IDENTITY_FIELDS.associateWith { p.getProperty("identity.$it") ?: error("Missing backup identity") }
        val entries = (0 until count).map { i ->
            val directory = p.getProperty("$i.directory"); require(directory in setOf("true", "false"))
            RealmBackupEntry(p.getProperty("$i.path"), directory == "true", p.getProperty("$i.size").toLong(), p.getProperty("$i.sha256"))
        }
        return RealmBackupManifest(p.getProperty("realmId"), identity, entries).also(::validate)
    }
    fun create(root: File, archive: File, manifest: RealmBackupManifest, syncDirectory: (File) -> Unit, checkActive: () -> Unit): String {
        validate(manifest); require(!Files.exists(archive.toPath(), NOFOLLOW_LINKS))
        require(inventory(root, checkActive) == manifest.entries)
        val partial = File(archive.parentFile, ".${archive.name}.partial")
        var created = false
        try {
            Files.newOutputStream(partial.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).close()
            created = true
            FileOutputStream(partial).use { raw ->
                ZipOutputStream(BufferedOutputStream(raw)).use { zip ->
                zip.setLevel(1)
                zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(encode(manifest)); zip.closeEntry()
                manifest.entries.forEach { e ->
                    checkActive(); zip.putNextEntry(ZipEntry(e.path + if (e.directory) "/" else ""))
                    if (!e.directory) Files.newInputStream(File(root, e.path).toPath(), NOFOLLOW_LINKS).use { input ->
                        val digest = MessageDigest.getInstance("SHA-256"); var size = 0L; val buffer = ByteArray(256 * 1024)
                        while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break
                            size += n; check(size <= e.size); digest.update(buffer, 0, n); zip.write(buffer, 0, n) }
                        check(size == e.size && digest.digest().hex() == e.sha256) { "Database changed during cold backup" }
                    }
                    zip.closeEntry()
                }
                zip.finish(); zip.flush(); raw.fd.sync()
                }
            }
            checkActive(); Files.move(partial.toPath(), archive.toPath(), ATOMIC_MOVE); syncDirectory(archive.parentFile!!)
            return sha256(archive, checkActive)
        } finally { if (created) partial.delete() }
    }
    fun inspect(archive: File): RealmBackupManifest {
        require(Files.isRegularFile(archive.toPath(), NOFOLLOW_LINKS) && archive.length() in 1..(MAX_BYTES + MAX_MANIFEST + 64L * 1024 * 1024))
        return ZipFile(archive).use { zip ->
            val list = zip.entries().asSequence().take(MAX_ENTRIES + 2).toList()
            require(list.size in 5..(MAX_ENTRIES + 1) && list.map { it.name }.distinct().size == list.size)
            val first = list.first(); require(first.name == MANIFEST && first.size in 1..MAX_MANIFEST.toLong())
            val bytes = zip.getInputStream(first).use { input ->
                val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() <= MAX_MANIFEST - n); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            val manifest = decode(bytes)
            require(list.drop(1).map { it.name } == manifest.entries.map { it.path + if (it.directory) "/" else "" })
            manifest.entries.zip(list.drop(1)).forEach { (e, z) -> require(z.size == e.size && z.isDirectory == e.directory) }
            manifest
        }
    }
    fun restore(archive: File, expectedSha256: String, manifest: RealmBackupManifest, destination: File,
                syncDirectory: (File) -> Unit, checkActive: () -> Unit) {
        require(destination.isDirectory && destination.list()?.isEmpty() == true && !Files.isSymbolicLink(destination.toPath()))
        require(sha256(archive, checkActive) == expectedSha256 && inspect(archive) == manifest)
        ZipFile(archive).use { zip ->
            manifest.entries.forEach { e ->
                checkActive(); val target = File(destination, e.path)
                check(target.canonicalPath.startsWith(destination.canonicalPath + File.separator))
                if (e.directory) { check(target.mkdir()); syncDirectory(target.parentFile!!) }
                else {
                    val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
                    Files.newOutputStream(target.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).close()
                    FileOutputStream(target).use { out ->
                        zip.getInputStream(zip.getEntry(e.path)).use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) { checkActive(); val n = input.read(buffer); if (n < 0) break
                                size += n; require(size <= e.size); digest.update(buffer, 0, n); out.write(buffer, 0, n) }
                        }
                        require(size == e.size && digest.digest().hex() == e.sha256) { "Backup file integrity differs" }
                        out.fd.sync()
                    }
                    check(target.setReadable(false, false) && target.setWritable(false, false) && target.setReadable(true, true) && target.setWritable(true, true))
                    syncDirectory(target.parentFile!!)
                }
            }
        }
        check(inventory(destination, checkActive) == manifest.entries)
    }
}
