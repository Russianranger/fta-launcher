package com.russianranger.fta.runtime

import android.content.Context
import android.os.Build
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

class RuntimeUnavailable(message: String) : IllegalStateException(message)

data class PackagedRuntime(
    val manifest: RuntimeManifest,
    val nativeDirectory: File,
    val executables: Map<String, File>,
    val libraryDirectory: File,
    val manifestSha256: String = ""
) {
    /** Copy only hash-declared provider data from the APK into a fresh private workspace. */
    fun stageData(context: Context, directory: File, checkCancelled: () -> Unit): File {
        check(!directory.exists()) { "Provider data workspace must be fresh" }
        check(directory.mkdirs())
        manifest.files.filter { it.role == "data" }.forEach { entry ->
            checkCancelled()
            val output = File(directory, entry.path)
            check(output.canonicalPath.startsWith(directory.canonicalPath + File.separator))
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            context.assets.open("runtime/${entry.path}").use { input ->
                java.io.FileOutputStream(output).use { target ->
                    val buffer = ByteArray(256 * 1024)
                    var count = 0L
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        check(count <= entry.size) { "Packaged provider data exceeds manifest size" }
                        target.write(buffer, 0, read)
                    }
                    target.fd.sync()
                }
            }
            RuntimeManifest.verifyFile(output, entry)
        }
        val bootstrap = manifest.bootstrap ?: throw RuntimeUnavailable("Pinned native bootstrap is unavailable")
        return File(directory, bootstrap.path)
    }

    companion object {
        private const val MANIFEST = "fta-runtime-manifest.json"
        private val platformLibraries = setOf("libc.so", "libdl.so", "libm.so", "liblog.so", "libandroid.so", "libz.so")
        private val libraryName = Regex("lib[A-Za-z0-9_+.-]+\\.so(?:\\.[0-9]+)*")

        /** Called only on a worker. The signed APK manifest is the executable trust root. */
        fun inspect(context: Context): PackagedRuntime {
            if ("arm64-v8a" !in Build.SUPPORTED_ABIS) throw RuntimeUnavailable("This provider requires Android ARM64")
            val json = try {
                context.assets.open(MANIFEST).use { input ->
                    val bytes = input.readBytesBounded(512 * 1024)
                    bytes.toString(Charsets.UTF_8)
                }
            } catch (_: Exception) { throw RuntimeUnavailable("No native provider is packaged in this APK") }
            val manifest = RuntimeManifest.parse(json)
            if ("database" !in manifest.capabilities) throw RuntimeUnavailable("Packaged database capability is unavailable")
            val natives = manifest.files.filter { it.path.startsWith("native/") }
            val nativeDirectory = File(context.applicationInfo.nativeLibraryDir).canonicalFile
            val byLogical = mutableMapOf<String, File>()
            val executables = mutableMapOf<String, File>()
            natives.forEach { entry ->
                val filename = entry.path.removePrefix("native/")
                val file = File(nativeDirectory, filename).canonicalFile
                if (file.parentFile != nativeDirectory || !file.isFile || file.length() != entry.size) {
                    throw RuntimeUnavailable("APK native library extraction is incomplete")
                }
                file.inputStream().use { input ->
                    if (sha256(input) != entry.sha256) throw RuntimeUnavailable("Packaged native library integrity check failed")
                }
                val header = file.inputStream().use { it.readBytesBounded(64, stopAtLimit = true) }
                if (header.size < 20 || header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    header[4] != 2.toByte() || header[5] != 1.toByte() ||
                    (header[16].toInt() and 255) !in setOf(2, 3) || header[17] != 0.toByte() ||
                    header[18] != 183.toByte() || header[19] != 0.toByte()) {
                    throw RuntimeUnavailable("Packaged native file is not an ARM64 ELF")
                }
                if (entry.role != "library") {
                    if (executables.put(entry.role, file) != null) throw RuntimeUnavailable("Duplicate packaged executable role")
                }
                val logicalName = entry.logicalName ?: filename
                if (!libraryName.matches(logicalName) || byLogical.put(logicalName, file) != null) {
                    throw RuntimeUnavailable("Invalid or duplicate native library name")
                }
            }
            if (!executables.keys.containsAll(listOf("database-server", "database-client"))) {
                throw RuntimeUnavailable("Packaged database executables are incomplete")
            }
            natives.flatMap { it.needed }.forEach { name ->
                if (name !in platformLibraries && name !in byLogical) throw RuntimeUnavailable("A native provider dependency is missing")
            }
            // Preserve SONAMEs without putting executable bytes in writable app storage.
            // Every symlink resolves to the integrity-checked, immutable extracted APK library.
            val directory = NativeDependencyLinks.prepare(File(context.filesDir, "runtime/provider-libs"),
                json, nativeDirectory, byLogical)
            return PackagedRuntime(manifest, nativeDirectory, executables, directory,
                sha256(json.byteInputStream(Charsets.UTF_8)))
        }

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }

        private fun InputStream.readBytesBounded(limit: Int, stopAtLimit: Boolean = false): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(minOf(8192, limit))
            while (output.size() < limit) {
                val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read < 0) return output.toByteArray()
                output.write(buffer, 0, read)
            }
            if (!stopAtLimit && read() >= 0) throw RuntimeUnavailable("Packaged manifest exceeds its size limit")
            return output.toByteArray()
        }
    }
}
