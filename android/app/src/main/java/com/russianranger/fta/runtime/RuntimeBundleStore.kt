package com.russianranger.fta.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipFile

/** Imported native files remain inert: Android executes only verified APK nativeLibraryDir files. */
data class RuntimeBundleReceipt(val generation: String, val directory: File, val manifest: RuntimeManifest)

class RuntimeBundleStore(private val root: File) {
    private val writer = ReentrantLock()
    private val generationPattern = Regex("g-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    @Volatile private var verified: RuntimeBundleReceipt? = null

    init {
        check(root.mkdirs() || root.isDirectory) { "Private runtime storage unavailable" }
        val name = activeName()
        if (name != null) {
            verified = runCatching { validateGeneration(name) }.getOrNull()
            if (verified == null) File(root, "active").delete()
        }
        cleanup()
    }

    /** Full hashes were checked by the constructor/import on a worker before this receipt is exposed. */
    fun active(): RuntimeBundleReceipt? = verified

    fun import(input: InputStream, progress: (Long) -> Unit = {}): RuntimeBundleReceipt {
        check(writer.tryLock()) { "Another runtime import is running" }
        val staging = File(root, ".partial-${UUID.randomUUID()}")
        val generation = "g-${UUID.randomUUID()}"
        val destination = File(root, generation)
        var published = false
        try {
            check(staging.mkdir()) { "Cannot stage private runtime bundle" }
            val spool = File(staging, ".input.zip")
            FileOutputStream(spool).use { output ->
                val buffer = ByteArray(256 * 1024)
                var copied = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Runtime import interrupted")
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    copied += read
                    if (copied > RuntimeManifest.MAX_TOTAL_BYTES) throw IOException("Compressed runtime bundle exceeds size limit")
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
            validateCentralDirectory(spool)
            val manifest: RuntimeManifest
            ZipFile(spool).use { zip ->
                val manifestEntry = zip.getEntry(RuntimeManifest.FILE_NAME)
                    ?: throw IOException("Runtime bundle manifest is missing")
                val manifestBytes = zip.getInputStream(manifestEntry).use {
                    readBounded(it, RuntimeManifest.MAX_MANIFEST_BYTES)
                }
                manifest = RuntimeManifest.parse(manifestBytes.toString(Charsets.UTF_8))
                val declared = manifest.files.associateBy { it.path }
                val entries = zip.entries().toList()
                val payload = entries.filterNot { it.isDirectory || it.name == RuntimeManifest.FILE_NAME }
                if (payload.map { it.name }.toSet() != declared.keys) {
                    throw IOException("Runtime ZIP payload and manifest files differ")
                }
                var extracted = 0L
                entries.filter { it.isDirectory }.forEach { entry ->
                    if (entry.size != 0L) throw IOException("Runtime ZIP directory has payload")
                    val name = entry.name.removeSuffix("/")
                    if (name != "native" && name != "data" && !name.startsWith("data/")) {
                        throw IOException("Unexpected runtime ZIP directory")
                    }
                }
                payload.forEach { entry ->
                    val spec = declared.getValue(entry.name)
                    if (entry.size != spec.size) throw IOException("Runtime ZIP file size differs from manifest")
                    val target = File(staging, spec.path)
                    check(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    zip.getInputStream(entry).use { source ->
                        FileOutputStream(target).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var size = 0L
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw IOException("Runtime import interrupted")
                                val read = source.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                size += read
                                extracted += read
                                if (size > spec.size || extracted > RuntimeManifest.MAX_TOTAL_BYTES) {
                                    throw IOException("Runtime ZIP payload exceeds declared size")
                                }
                                output.write(buffer, 0, read)
                                progress(extracted)
                            }
                            if (size != spec.size) throw IOException("Runtime ZIP payload is truncated")
                            output.fd.sync()
                        }
                    }
                    target.setReadable(true, true)
                    target.setWritable(true, true)
                    target.setExecutable(false, false)
                    RuntimeManifest.verifyFile(target, spec)
                }
                FileOutputStream(File(staging, RuntimeManifest.FILE_NAME)).use { output ->
                    output.write(manifestBytes)
                    output.fd.sync()
                }
            }
            if (!spool.delete()) throw IOException("Cannot remove staged ZIP")
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            val pointer = File(root, ".active-${UUID.randomUUID()}")
            try {
                FileOutputStream(pointer).use { output ->
                    output.write(generation.toByteArray(Charsets.US_ASCII))
                    output.fd.sync()
                }
                Files.move(pointer.toPath(), File(root, "active").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                published = true
            } finally { pointer.delete() }
            val receipt = RuntimeBundleReceipt(generation, destination, manifest)
            verified = receipt
            cleanup()
            return receipt
        } finally {
            deleteTree(staging)
            if (!published) deleteTree(destination)
            writer.unlock()
        }
    }

    private fun validateGeneration(name: String): RuntimeBundleReceipt {
        val directory = File(root, name)
        val manifestFile = File(directory, RuntimeManifest.FILE_NAME)
        if (!Files.isRegularFile(manifestFile.toPath(), LinkOption.NOFOLLOW_LINKS) ||
            manifestFile.length() > RuntimeManifest.MAX_MANIFEST_BYTES) throw IOException("Saved runtime manifest unavailable")
        val manifest = RuntimeManifest.parse(manifestFile.readText(Charsets.UTF_8))
        manifest.files.forEach { RuntimeManifest.verifyFile(File(directory, it.path), it) }
        return RuntimeBundleReceipt(name, directory, manifest)
    }

    private fun activeName(): String? {
        val pointer = File(root, "active")
        if (!Files.isRegularFile(pointer.toPath(), LinkOption.NOFOLLOW_LINKS) || pointer.length() > 64) return null
        return pointer.readText().takeIf { generationPattern.matches(it) }
    }

    private fun cleanup() {
        val keep = verified?.generation
        root.listFiles()?.forEach { file ->
            if (file.name.startsWith(".partial-") || file.name.startsWith(".active-") ||
                (generationPattern.matches(file.name) && file.name != keep)) deleteTree(file)
        }
    }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        // Files.walk does not follow symlinks; cleanup cannot descend outside the private root.
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (result.size().toLong() + read > limit) throw IOException("Runtime manifest exceeds size limit")
            result.write(buffer, 0, read)
        }
        return result.toByteArray()
    }

    /** Inspect central Unix mode bits before Java's ZIP API can erase symlink metadata. */
    private fun validateCentralDirectory(file: File) {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 22) throw IOException("Runtime ZIP is incomplete")
            val tailSize = minOf(input.length(), 65_557L).toInt()
            val tail = ByteArray(tailSize)
            input.seek(input.length() - tailSize)
            input.readFully(tail)
            fun short(bytes: ByteArray, offset: Int) = (bytes[offset].toInt() and 255) or
                ((bytes[offset + 1].toInt() and 255) shl 8)
            fun unsigned(bytes: ByteArray, offset: Int) = (short(bytes, offset).toLong() or
                (short(bytes, offset + 2).toLong() shl 16))
            val end = (tail.size - 22 downTo 0).firstOrNull { offset ->
                unsigned(tail, offset) == 0x06054b50L && offset + 22 + short(tail, offset + 20) == tail.size
            } ?: throw IOException("Runtime ZIP directory is missing")
            val count = short(tail, end + 10)
            val centralSize = unsigned(tail, end + 12)
            val centralOffset = unsigned(tail, end + 16)
            val endOffset = input.length() - tailSize + end
            if (short(tail, end + 4) != 0 || short(tail, end + 6) != 0 ||
                short(tail, end + 8) != count || count !in 1..RuntimeManifest.MAX_ENTRIES ||
                centralOffset + centralSize != endOffset) {
                throw IOException("Split, oversized or ZIP64 runtime archives are unsupported")
            }
            input.seek(centralOffset)
            val names = mutableSetOf<String>()
            repeat(count) {
                val header = ByteArray(46)
                input.readFully(header)
                if (unsigned(header, 0) != 0x02014b50L) throw IOException("Invalid runtime ZIP directory record")
                val flags = short(header, 8)
                val method = short(header, 10)
                val nameSize = short(header, 28)
                val extraSize = short(header, 30)
                val commentSize = short(header, 32)
                if (flags and 1 != 0 || method !in setOf(0, 8) || nameSize !in 1..241 || short(header, 34) != 0) {
                    throw IOException("Unsupported encrypted or malformed runtime ZIP entry")
                }
                val nameBytes = ByteArray(nameSize)
                input.readFully(nameBytes)
                if (nameBytes.any { it.toInt() !in 32..126 }) throw IOException("Runtime ZIP paths must use portable ASCII")
                val name = nameBytes.toString(Charsets.US_ASCII)
                RuntimeManifest.validatePath(name, name.endsWith('/'))
                if (!names.add(name.lowercase())) throw IOException("Duplicate runtime ZIP entry")
                val mode = ((unsigned(header, 38) ushr 16) and 0xf000).toInt()
                if (mode !in setOf(0, 0x8000, 0x4000) || (mode == 0x4000 && !name.endsWith('/'))) {
                    throw IOException("Runtime ZIP symlinks and special files are forbidden")
                }
                val size = unsigned(header, 24)
                val limit = if (name == RuntimeManifest.FILE_NAME) RuntimeManifest.MAX_MANIFEST_BYTES else RuntimeManifest.MAX_FILE_BYTES
                if (size > limit) throw IOException("Runtime ZIP entry exceeds size limit")
                val extra = ByteArray(extraSize)
                input.readFully(extra)
                var cursor = 0
                while (cursor < extra.size) {
                    if (cursor + 4 > extra.size) throw IOException("Malformed runtime ZIP extra field")
                    val field = short(extra, cursor)
                    val length = short(extra, cursor + 2)
                    if (field == 1 || cursor + 4 + length > extra.size) throw IOException("ZIP64 or malformed runtime ZIP extra field")
                    cursor += 4 + length
                }
                input.seek(input.filePointer + commentSize)
                if (input.filePointer > centralOffset + centralSize) throw IOException("Runtime ZIP directory extends beyond bounds")
            }
            if (input.filePointer != centralOffset + centralSize) throw IOException("Runtime ZIP directory size mismatch")
        }
    }
}
