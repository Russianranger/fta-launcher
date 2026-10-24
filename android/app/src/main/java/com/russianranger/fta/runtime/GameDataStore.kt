package com.russianranger.fta.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile

data class GameDataFile(val path: String, val bytes: Long, val sha256: String)
data class GameDataReceipt(val generation: String, val archiveSha256: String, val catalogSha256: String,
    val files: Int, val bytes: Long)
class GameDataRejected(val preflight: GameDataPreflight) : java.io.IOException("Game-data preflight rejected the candidate")

/** Inert game assets in their own generations. This store never opens a database or executes anything. */
class GameDataStore(private val root: File, private val policyText: String,
    private val syncDirectory: (File) -> Unit = { FileChannel.open(it.toPath(), READ).use { c -> c.force(true) } }) {
    private val policy = GameDataPolicy.parse(policyText)
    private val policySha = hex(MessageDigest.getInstance("SHA-256").digest(policyText.toByteArray()))
    private val generationPattern = Regex("g-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    init { if (!root.exists()) check(root.mkdirs()); require(Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) }
    private fun <T> exclusive(operation: () -> T): T = FileChannel.open(File(root, "lease").toPath(), CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
        val lease = channel.tryLock() ?: error("Game data is already in use")
        lease.use { operation() }
    }
    private fun read(file: File, max: Long): Properties {
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() in 1..max)
        return Properties().apply { file.inputStream().use { load(it) } }
    }
    private fun atomic(file: File, text: String) {
        val temp = File(file.parentFile, ".write-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { it.write(text.toByteArray()); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(file.parentFile!!)
        } finally { temp.delete() }
    }
    private fun text(values: Map<String, Any>) = values.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
    private fun pointer(): String? {
        val file = File(root, "active")
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() <= 64)
        return file.readText().also { require(generationPattern.matches(it)) }
    }
    fun active(): GameDataReceipt? = exclusive { receipt() }
    private fun receipt(): GameDataReceipt? {
        val name = pointer() ?: return null
        val directory = File(root, name); require(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val p = read(File(directory, "receipt.properties"), 4096)
        require(p.getProperty("formatVersion") == "1" && p.getProperty("policySha256") == policySha)
        val archive = p.getProperty("archiveSha256"); val catalog = p.getProperty("catalogSha256")
        require(archive.matches(Regex("[a-f0-9]{64}")) && catalog.matches(Regex("[a-f0-9]{64}")))
        val files = p.getProperty("files").toInt(); val bytes = p.getProperty("bytes").toLong()
        require(files in 1..GameDataPolicy.MAX_FILES && bytes in 1..GameDataPolicy.MAX_TOTAL_BYTES)
        return GameDataReceipt(name, archive, catalog, files, bytes)
    }
    private fun report(state: String, archive: String?, result: GameDataPreflight? = null, catalog: String? = null,
        stage: String? = null, failure: Exception? = null) {
        val values = mutableMapOf<String, Any>("formatVersion" to 1, "state" to state, "policySha256" to policySha,
            "files" to (result?.files ?: 0), "bytes" to (result?.bytes ?: 0), "problemCount" to (result?.problemCount ?: 0))
        archive?.let { values["archiveSha256"] = it }; catalog?.let { values["catalogSha256"] = it }
        values["stockDbcFiles"] = result?.counts?.get("stock_dbc") ?: 0
        values["db2CompanionFiles"] = result?.counts?.get("db2_companions") ?: 0
        stage?.let { values["failureStage"] = it }
        failure?.let {
            values["failureReason"] = (it as? GameDataImportFailure)?.reason ?: "operation_failed"
            (it as? GameDataImportFailure)?.asset?.let { path -> values["failureAsset"] = path }
            (it as? GameDataImportFailure)?.let { detail ->
                values["rejectedAssetCount"] = detail.rejectedAssetCount
                detail.rejectedAssets.forEachIndexed { i, path -> values["rejectedAsset.$i"] = path }
            }
        }
        GameDataPolicy.GROUPS.forEach { values["count.$it"] = result?.counts?.get(it) ?: 0 }
        result?.problems?.forEachIndexed { i, p -> values["problem.$i.path"] = p.path; values["problem.$i.reason"] = p.reason }
        atomic(File(root, "last-report.properties"), text(values))
    }
    /** Stored receipt is historical import evidence; it never asserts present payload integrity or world readiness. */
    fun export(): Map<String, Any> = exclusive {
        val result = mutableMapOf<String, Any>("available" to false, "worldReady" to false, "gameLoginQualified" to false,
            "nativeSemanticValidationPassed" to false, "policySha256" to policySha)
        runCatching {
            val parsed = mutableMapOf<String, Any>()
            val p = read(File(root, "last-report.properties"), 32768)
            require(p.getProperty("formatVersion") == "1" && p.getProperty("policySha256") == policySha)
            val state = p.getProperty("state"); require(state in setOf("COPYING", "IMPORTED", "VERIFIED", "REJECTED", "FAILED"))
            parsed["available"] = true; parsed["state"] = state
            parsed["preflightPassed"] = state in setOf("IMPORTED", "VERIFIED")
            for (key in listOf("files", "bytes", "problemCount") + GameDataPolicy.GROUPS.map { "count.$it" }) {
                val n = p.getProperty(key).toLong(); require(n in 0..GameDataPolicy.MAX_TOTAL_BYTES); parsed[key] = n
            }
            for (key in listOf("archiveSha256", "catalogSha256")) p.getProperty(key)?.let {
                require(it.matches(Regex("[a-f0-9]{64}"))); parsed[key] = it
            }
            parsed["stockDbcFiles"] = (p.getProperty("stockDbcFiles") ?: "0").toLong().also { require(it in 0..GameDataPolicy.MAX_FILES.toLong()) }
            parsed["db2CompanionFiles"] = (p.getProperty("db2CompanionFiles") ?: "0").toLong().also { require(it in 0..GameDataPolicy.MAX_FILES.toLong()) }
            p.getProperty("failureStage")?.let { require(it in FAILURE_STAGES); parsed["failureStage"] = it }
            p.getProperty("failureReason")?.let { require(it in GameDataZip.FAILURE_REASONS); parsed["failureReason"] = it }
            p.getProperty("failureAsset")?.let {
                GameDataPolicy.path(it); require(it.substringBefore('/') in GameDataPolicy.GROUPS); parsed["failureAsset"] = it
            }
            val rejectedCount = (p.getProperty("rejectedAssetCount") ?: "0").toInt()
            require(rejectedCount in 0..GameDataPolicy.MAX_FILES)
            parsed["rejectedAssetCount"] = rejectedCount
            parsed["rejectedAssets"] = (0 until minOf(32, rejectedCount)).map { i ->
                val path = p.getProperty("rejectedAsset.$i") ?: error("Incomplete rejected asset report")
                GameDataPolicy.path(path); require(path.substringBefore('/') in GameDataPolicy.GROUPS)
                path
            }
            parsed["problems"] = (0 until 32).mapNotNull { i ->
                val path = p.getProperty("problem.$i.path") ?: return@mapNotNull null
                GameDataPolicy.path(path); require(path.substringBefore('/') in GameDataPolicy.GROUPS)
                val reason = p.getProperty("problem.$i.reason"); require(reason.matches(Regex("[a-z_]{1,48}")))
                mapOf("asset" to path, "reason" to reason)
            }
            val current = receipt()
            parsed["activeCandidateRetained"] = current != null
            parsed["activeCatalogMatchesReport"] = current != null && current.catalogSha256 == p.getProperty("catalogSha256")
            result.putAll(parsed)
        }
        result
    }
    fun import(input: InputStream, active: () -> Unit = {}, progress: (String, Long, Long) -> Unit = { _, _, _ -> },
        boundary: (String, String?) -> Unit = { _, _ -> }): GameDataReceipt = exclusive {
        val previous = pointer()
        val generation = "g-${UUID.randomUUID()}"
        val staging = File(root, ".partial-${UUID.randomUUID()}"); val destination = File(root, generation)
        var published = false; var archiveHash: String? = null; var stage = "copy"
        try {
            report("COPYING", null)
            boundary("copy", null); progress("copy", 0, 0)
            root.listFiles()?.filter { it.name.startsWith(".partial-") }?.forEach(::deleteTree)
            check(staging.mkdir())
            val archive = File(staging, ".input.zip")
            val digest = MessageDigest.getInstance("SHA-256"); var copied = 0L
            FileOutputStream(archive).use { out ->
                val writer = GameDataWriteback(out) { active(); out.fd.sync(); active() }
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    active(); if (root.usableSpace < 64L * 1024 * 1024) throw GameDataImportFailure("insufficient_space")
                    val n = input.read(buffer); if (n < 0) break; if (n == 0) continue
                    copied += n; if (copied > GameDataPolicy.MAX_TOTAL_BYTES) throw GameDataImportFailure("archive_limit")
                    writer.write(buffer, 0, n); digest.update(buffer, 0, n); progress("copy", copied, 0)
                }
                stage = "copy_sync"; boundary(stage, null); progress(stage, copied, copied)
                writer.finish()
            }
            archiveHash = hex(digest.digest())
            stage = "archive_index"
            boundary(stage, archiveHash)
            val entries = GameDataZip.inspect(archive, active) { done, total -> progress(stage, done, total) }
            val total = entries.sumOf { it.bytes }
            if (root.usableSpace < total + 64L * 1024 * 1024) throw GameDataImportFailure("insufficient_space")
            stage = "extract"
            boundary(stage, archiveHash); progress(stage, 0, total)
            val files = mutableListOf<GameDataFile>(); var extracted = 0L
            ZipFile(archive).use { zip ->
                entries.forEach { spec ->
                    active()
                    val entry = zip.getEntry(spec.name); require(entry != null && entry.size == spec.bytes && entry.compressedSize == spec.compressed && entry.crc == spec.crc)
                    val target = File(staging, spec.path); check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    val hash = MessageDigest.getInstance("SHA-256"); val crc = CRC32(); var size = 0L
                    zip.getInputStream(entry).use { source ->
                        FileChannel.open(target.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                            val output = GameDataWriteback(Channels.newOutputStream(channel)) { active(); channel.force(true); active() }
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                active(); val n = source.read(buffer); if (n < 0) break; if (n == 0) continue
                                size += n; extracted += n; require(size <= spec.bytes && extracted <= total)
                                output.write(buffer, 0, n); hash.update(buffer, 0, n); crc.update(buffer, 0, n)
                                progress("extract", extracted, total)
                            }
                            output.finish()
                        }
                    }
                    if (size != spec.bytes || crc.value != spec.crc) throw GameDataImportFailure("asset_integrity", spec.path)
                    check(target.setExecutable(false, false))
                    FileChannel.open(target.toPath(), WRITE, NOFOLLOW_LINKS).use { it.force(true) }
                    files += GameDataFile(spec.path, size, hex(hash.digest()))
                }
            }
            stage = "preflight"; boundary(stage, archiveHash); active(); progress(stage, total, total)
            val result = policy.inspect(staging, files, active)
            if (!result.passed) { report("REJECTED", archiveHash, result, stage = stage); throw GameDataRejected(result) }
            stage = "publish"
            boundary(stage, archiveHash); progress(stage, total, total)
            check(archive.delete())
            val catalog = files.sortedBy { it.path }.joinToString("\n", postfix = "\n") { "${it.sha256}\t${it.bytes}\t${it.path}" }
            atomic(File(staging, "files.tsv"), catalog)
            val catalogHash = hex(MessageDigest.getInstance("SHA-256").digest(catalog.toByteArray()))
            atomic(File(staging, "receipt.properties"), text(mapOf("formatVersion" to 1, "policySha256" to policySha,
                "archiveSha256" to archiveHash, "catalogSha256" to catalogHash, "files" to files.size, "bytes" to total)))
            // Sync all newly created directory entries before publishing the generation.
            Files.walk(staging.toPath()).use { paths -> paths.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }.forEach { syncDirectory(it.toFile()) } }
            Files.move(staging.toPath(), destination.toPath(), ATOMIC_MOVE); syncDirectory(root)
            val pointerTemp = File(root, ".active-${UUID.randomUUID()}")
            try {
                FileOutputStream(pointerTemp).use { it.write(generation.toByteArray()); it.fd.sync() }
                active(); Files.move(pointerTemp.toPath(), File(root, "active").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                published = true; syncDirectory(root)
            } finally { pointerTemp.delete() }
            report("IMPORTED", archiveHash, result, catalogHash)
            root.listFiles()?.filter { generationPattern.matches(it.name) && it.name !in setOf(generation, previous) }?.forEach(::deleteTree)
            GameDataReceipt(generation, archiveHash, catalogHash, files.size, total)
        } catch (failure: Exception) {
            if (failure !is GameDataRejected) runCatching { report("FAILED", archiveHash, stage = stage, failure = failure) }
            throw failure
        } finally { deleteTree(staging); if (!published) deleteTree(destination) }
    }
    /** Full rehash and format preflight after a later app/process restart. */
    fun verify(active: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): GameDataReceipt = exclusive {
        val current = receipt() ?: error("No imported game data")
        val directory = File(root, current.generation)
        try {
            val catalog = File(directory, "files.tsv")
            require(Files.isRegularFile(catalog.toPath(), NOFOLLOW_LINKS) && catalog.length() in 1..32L * 1024 * 1024)
            require(sha(catalog, active) == current.catalogSha256)
            val files = catalog.readLines().map { line ->
                val v = line.split('\t'); require(v.size == 3 && v[0].matches(Regex("[a-f0-9]{64}")))
                GameDataPolicy.assetPath(v[2]); val n = v[1].toLong(); require(n in 0..GameDataPolicy.MAX_FILE_BYTES)
                GameDataFile(v[2], n, v[0])
            }
            require(files.size == current.files && files.map { it.path }.toSet().size == files.size && files.sumOf { it.bytes } == current.bytes)
            val expected = files.map { it.path }.toSet() + setOf("files.tsv", "receipt.properties")
            val actual = mutableSetOf<String>()
            Files.walk(directory.toPath()).use { paths -> paths.filter { it != directory.toPath() }.forEach { path ->
                active()
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS))
                    actual += directory.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                }
            } }
            require(actual == expected)
            var checked = 0L
            files.forEach { entry ->
                active(); val file = File(directory, entry.path)
                var parent = file.parentFile!!
                while (parent != directory) { require(Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS)); parent = parent.parentFile!! }
                require(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == entry.bytes && sha(file, active) == entry.sha256)
                checked += entry.bytes; progress(checked, current.bytes)
            }
            val result = policy.inspect(directory, files, active)
            require(result.passed)
            report("VERIFIED", current.archiveSha256, result, current.catalogSha256)
            current
        } catch (failure: Exception) { runCatching { report("FAILED", current.archiveSha256, stage = "verify", failure = failure) }; throw failure }
    }
    companion object {
        private val FAILURE_STAGES = setOf("copy", "copy_sync", "archive_index", "extract", "preflight", "publish", "verify")
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
        private fun sha(file: File, active: () -> Unit): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(256 * 1024); while (true) { active(); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return hex(digest.digest())
        }
        private fun deleteTree(file: File) {
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
            Files.walk(file.toPath()).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }
}
