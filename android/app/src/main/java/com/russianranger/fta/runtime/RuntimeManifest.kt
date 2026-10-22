package com.russianranger.fta.runtime

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Untrusted input metadata. Neither parsing nor importing grants execution authority. */
data class RuntimeFile(
    val path: String,
    val sha256: String,
    val size: Long,
    val role: String,
    val needed: List<String> = emptyList(),
    val logicalName: String? = null,
    val sonames: List<String> = emptyList(),
    val interpreter: String? = null,
    val minimumLoadAlignment: Long? = null
) {
    val nativeName: String? get() = path.takeIf { it.startsWith("native/") }?.substringAfter('/')
}

data class RuntimeManifest(
    val formatVersion: Int,
    val abi: String,
    val providerVersion: String,
    val capabilities: Set<String>,
    val files: List<RuntimeFile>,
    val bootstrap: RuntimeFile? = null,
    val providerId: String? = null
) {
    companion object {
        const val FILE_NAME = "fta-runtime-manifest.json"
        const val MAX_MANIFEST_BYTES = 1_048_576L
        const val MAX_FILE_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_ENTRIES = 4096
        private val roles = setOf("database-server", "database-client", "auth-server", "world-server", "library", "data")
        private val knownCapabilities = setOf("database", "authserver", "worldserver")

        fun parse(json: String): RuntimeManifest {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) { "Runtime manifest exceeds size limit" }
            val root = StrictJson(json).parse().objectValue()
            root.onlyKeys("formatVersion", "abi", "providerVersion", "capabilities", "files", "bootstrap", "providerId", "qualification")
            val providerId = root["providerId"]?.stringValue()
            require(providerId == null || providerId.matches(Regex("[A-Za-z0-9._+-]{1,120}"))) { "Invalid provider identity" }
            // Qualification claims are metadata, never authority to execute or mark a world ready.
            root["qualification"]?.objectValue()?.let { qualification ->
                qualification.onlyKeys("androidExecuted", "databaseSeedCompatible", "publicRedistributionQualified")
                qualification.values.forEach { it.booleanValue() }
            }
            require(root.required("formatVersion").integerValue() == 1L) { "Unsupported runtime manifest version" }
            require(root.required("abi").stringValue() == "arm64-v8a") { "Runtime requires arm64-v8a" }
            val version = root.required("providerVersion").stringValue()
            require(version.matches(Regex("[A-Za-z0-9._+-]{1,80}"))) { "Invalid provider version" }
            val caps = root.required("capabilities").arrayValue().map { it.stringValue() }
            require(caps.isNotEmpty() && caps.size == caps.toSet().size && caps.all { it in knownCapabilities }) {
                "Unsupported or duplicate runtime capability"
            }
            val entries = root.required("files").arrayValue()
            require(entries.isNotEmpty() && entries.size <= MAX_ENTRIES) { "Invalid runtime file count" }
            val files = entries.map { entry ->
                val value = entry.objectValue()
                value.onlyKeys("path", "sha256", "size", "role", "needed", "logicalName", "sonames", "interpreter", "minimumLoadAlignment")
                val path = value.required("path").stringValue()
                validatePath(path)
                val hash = value.required("sha256").stringValue()
                require(hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid runtime file digest" }
                val bytes = value.required("size").integerValue()
                require(bytes in 0..MAX_FILE_BYTES) { "Runtime file exceeds size limit" }
                val role = value.required("role").stringValue()
                require(role in roles) { "Unsupported runtime file role" }
                if (role == "data") require(path.startsWith("data/")) { "Runtime data must remain beneath data/" }
                else require(path.matches(Regex("native/lib[A-Za-z0-9_.+-]+\\.so"))) {
                    "Native runtime files must have unique Android lib*.so names"
                }
                val needed = value["needed"]?.arrayValue()?.map { it.stringValue() } ?: emptyList()
                require(needed.size <= 128 && needed.size == needed.toSet().size &&
                    needed.all { it.matches(Regex("[A-Za-z0-9_.+-]{1,120}")) }) { "Invalid native dependency list" }
                require(role != "data" || needed.isEmpty()) { "Data files cannot declare native dependencies" }
                val logicalName = value["logicalName"]?.takeUnless { it === JsonNull }?.stringValue()
                require(logicalName == null || (role != "data" &&
                    logicalName.matches(Regex("[A-Za-z0-9_.+-]{1,120}")))) { "Invalid native logical name" }
                val sonames = value["sonames"]?.arrayValue()?.map { it.stringValue() } ?: emptyList()
                require(sonames.size <= 4 && sonames.size == sonames.toSet().size &&
                    sonames.all { it.matches(Regex("[A-Za-z0-9_.+-]{1,120}")) }) { "Invalid native SONAME metadata" }
                val interpreter = value["interpreter"]?.takeUnless { it === JsonNull }?.stringValue()
                require(interpreter == null || interpreter == "/system/bin/linker64") { "Unsupported native ELF interpreter" }
                val alignment = value["minimumLoadAlignment"]?.integerValue()
                require(alignment == null || (alignment in 4096..2_097_152 && alignment and (alignment - 1) == 0L)) {
                    "Invalid native ELF segment alignment metadata"
                }
                if (role == "data") require(sonames.isEmpty() && interpreter == null && alignment == null) {
                    "Data files cannot declare ELF metadata"
                } else require(bytes >= 64) { "Native ELF payload is incomplete" }
                RuntimeFile(path, hash, bytes, role, needed, logicalName, sonames, interpreter, alignment)
            }
            require(files.map { it.path.lowercase() }.toSet().size == files.size) { "Duplicate runtime file path" }
            val logicalNames = files.mapNotNull { it.logicalName }
            require(logicalNames.toSet().size == logicalNames.size) { "Duplicate native logical name" }
            require(files.sumOf { it.size } <= MAX_TOTAL_BYTES) { "Runtime bundle exceeds total size limit" }
            val requiredRoles = mapOf("database" to listOf("database-server", "database-client"),
                "authserver" to listOf("auth-server"), "worldserver" to listOf("world-server"))
            requiredRoles.forEach { (capability, capabilityRoles) ->
                capabilityRoles.forEach { role ->
                    val count = files.count { it.role == role }
                    require(count == if (capability in caps) 1 else 0) { "Runtime capability and executable roles differ" }
                }
            }
            require("worldserver" !in caps || ("authserver" in caps && "database" in caps)) {
                "Worldserver requires authserver and database capabilities"
            }
            require("authserver" !in caps || "database" in caps) { "Authserver requires database capability" }
            val bootstrap = root["bootstrap"]?.takeUnless { it === JsonNull }?.objectValue()?.let { value ->
                value.onlyKeys("path", "sha256", "size")
                val path = value.required("path").stringValue()
                val hash = value.required("sha256").stringValue()
                val size = value.required("size").integerValue()
                files.singleOrNull { it.path == path && it.role == "data" && it.sha256 == hash && it.size == size }
                    ?: throw IllegalArgumentException("Bootstrap must match a declared data file")
            }
            return RuntimeManifest(1, "arm64-v8a", version, caps.toSet(), files, bootstrap, providerId)
        }

        fun validatePath(path: String, directory: Boolean = false) {
            val value = if (directory) path.removeSuffix("/") else path
            require(value.isNotEmpty() && value.length <= 240 && !value.startsWith('/') && !value.contains('\\')) {
                "Unsafe runtime path"
            }
            require(value.split('/').all { it != "." && it != ".." &&
                it.matches(Regex("[A-Za-z0-9._+-]{1,120}")) }) { "Unsafe runtime path component" }
        }

        fun verifyFile(file: File, spec: RuntimeFile) {
            if (!java.nio.file.Files.isRegularFile(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) ||
                file.length() != spec.size) throw IOException("Runtime file is missing or has the wrong size")
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            if (actual != spec.sha256) throw IOException("Runtime file digest differs from manifest")
            if (spec.nativeName != null) verifyArm64Elf(file)
        }

        fun verifyArm64Elf(file: File) {
            val header = ByteArray(64)
            file.inputStream().use { input ->
                var count = 0
                while (count < header.size) {
                    val read = input.read(header, count, header.size - count)
                    if (read < 0) throw IOException("Native runtime ELF header is incomplete")
                    count += read
                }
            }
            fun word(offset: Int) = (header[offset].toInt() and 255) or ((header[offset + 1].toInt() and 255) shl 8)
            if (!header.copyOfRange(0, 4).contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) ||
                header[4].toInt() != 2 || header[5].toInt() != 1 || header[6].toInt() != 1 ||
                word(16) !in setOf(2, 3) || word(18) != 183 || word(52) != 64) {
                throw IOException("Native runtime must be a little-endian ARM64 ELF executable or library")
            }
        }
    }
}

/** Small strict JSON reader avoids Android-only parsers and rejects duplicate object keys. */
private class StrictJson(private val source: String) {
    private var cursor = 0
    fun parse(): Any {
        val result = value(0)
        whitespace()
        require(cursor == source.length) { "Trailing runtime manifest content" }
        return result
    }
    private fun value(depth: Int): Any {
        require(depth < 12) { "Runtime manifest JSON nesting exceeds limit" }
        whitespace()
        return when (source.getOrNull(cursor)) {
            '{' -> {
                cursor++
                val values = linkedMapOf<String, Any>()
                whitespace()
                if (consume('}')) values else {
                    do {
                        whitespace()
                        val key = string()
                        require(!values.containsKey(key)) { "Duplicate runtime manifest key" }
                        whitespace(); expect(':')
                        values[key] = value(depth + 1)
                        whitespace()
                        if (consume('}')) break
                        expect(',')
                    } while (true)
                    values
                }
            }
            '[' -> {
                cursor++
                val values = mutableListOf<Any>()
                whitespace()
                if (consume(']')) values else {
                    do {
                        values.add(value(depth + 1))
                        require(values.size <= RuntimeManifest.MAX_ENTRIES) { "Runtime manifest array exceeds limit" }
                        whitespace()
                        if (consume(']')) break
                        expect(',')
                    } while (true)
                    values
                }
            }
            '"' -> string()
            't' -> { literal("true"); true }
            'f' -> { literal("false"); false }
            'n' -> { literal("null"); JsonNull }
            '-', in '0'..'9' -> {
                val start = cursor
                if (consume('-')) require(source.getOrNull(cursor) in '0'..'9') { "Invalid JSON number" }
                if (consume('0')) require(source.getOrNull(cursor) !in '0'..'9') { "Invalid leading JSON zero" }
                else {
                    require(source.getOrNull(cursor) in '1'..'9') { "Invalid JSON number" }
                    while (source.getOrNull(cursor) in '0'..'9') cursor++
                }
                require(source.getOrNull(cursor) !in listOf('.', 'e', 'E')) { "Only integer runtime manifest numbers are supported" }
                source.substring(start, cursor).toLongOrNull() ?: throw IllegalArgumentException("Invalid JSON integer range")
            }
            else -> throw IllegalArgumentException("Unsupported runtime manifest JSON value")
        }
    }
    private fun string(): String {
        expect('"')
        val result = StringBuilder()
        while (cursor < source.length) {
            val character = source[cursor++]
            if (character == '"') return result.toString()
            require(character.code >= 32) { "Invalid JSON string control character" }
            if (character != '\\') result.append(character) else {
                require(cursor < source.length) { "Incomplete JSON escape" }
                result.append(when (val escaped = source[cursor++]) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> {
                        require(cursor + 4 <= source.length) { "Incomplete Unicode escape" }
                        val code = source.substring(cursor, cursor + 4).toIntOrNull(16)
                            ?: throw IllegalArgumentException("Invalid Unicode escape")
                        cursor += 4
                        code.toChar()
                    }
                    else -> throw IllegalArgumentException("Invalid JSON escape")
                })
            }
        }
        throw IllegalArgumentException("Unterminated JSON string")
    }
    private fun literal(value: String) {
        require(source.startsWith(value, cursor)) { "Invalid runtime manifest JSON literal" }
        cursor += value.length
    }
    private fun whitespace() { while (source.getOrNull(cursor) in listOf(' ', '\n', '\r', '\t')) cursor++ }
    private fun consume(character: Char): Boolean = if (source.getOrNull(cursor) == character) { cursor++; true } else false
    private fun expect(character: Char) { require(consume(character)) { "Invalid runtime manifest JSON punctuation" } }
}

private object JsonNull
private fun Any.booleanValue(): Boolean = this as? Boolean ?: throw IllegalArgumentException("Runtime manifest boolean required")

@Suppress("UNCHECKED_CAST")
private fun Any.objectValue(): Map<String, Any> = this as? Map<String, Any>
    ?: throw IllegalArgumentException("Runtime manifest object required")
@Suppress("UNCHECKED_CAST")
private fun Any.arrayValue(): List<Any> = this as? List<Any>
    ?: throw IllegalArgumentException("Runtime manifest array required")
private fun Any.stringValue(): String = this as? String ?: throw IllegalArgumentException("Runtime manifest string required")
private fun Any.integerValue(): Long = this as? Long ?: throw IllegalArgumentException("Runtime manifest integer required")
private fun Map<String, Any>.required(key: String): Any = this[key] ?: throw IllegalArgumentException("Missing runtime manifest field")
private fun Map<String, Any>.onlyKeys(vararg keys: String) {
    require(this.keys.all { it in keys }) { "Unsupported runtime manifest field" }
}
